package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientBatchDecoder;
import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.client.ClientGameStart;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.SpectrumLoginData;
import ac.oomph.boarium.server.ServerConn;
import ac.oomph.boarium.server.ServerGameData;
import ac.oomph.boarium.server.ServerHandshake;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.transport.Transport;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One player on the proxy: a logged-in client and its connection to the downstream server it plays on, as spectrum-internal's Session.
 * Once the player spawns, packets pass between the two, and the client's latency goes to the server at the settings' interval.
 * The player can be transferred to another server; when its server is lost, it is transferred once to the fallback server, if there is one, and otherwise disconnected.
 * The session's server, its transfers and the fallback change on the client's event loop.
 * A transfer asked for while one runs waits for it, and the latest such address wins.
 */
public final class Session {
    // spectrum-internal's Session.Transfer timeout.
    static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(1);
    private static final String CANT_CONNECT = "disconnectionScreen.internalError.cantConnect";

    private final ClientLogin client;
    private final BedrockServerSession session;
    private final EventLoop loop;
    private final Transport transport;
    private final BedrockCodec codec;
    private final SessionSettings settings;
    private final String clientAddr;
    private final byte[] clientData;
    private final byte[] identityData;
    private final Tracker tracker = new Tracker();

    private volatile ServerConn server;
    private volatile String serverAddr;
    private volatile byte[] cache = new byte[0];
    private volatile long serverLatency;
    private volatile long clientRoundTrip;
    // The player's entity runtime ID and dimension from the client's StartGame, which a transfer does not change.
    private long runtimeId;
    private int dimension;
    private boolean inFallback;
    private CompletableFuture<Void> transfer;
    private String queuedAddr;
    private CompletableFuture<Void> queued;

    private Session(ClientLogin client, Transport transport, BedrockCodec codec, SessionSettings settings) {
        this.client = client;
        this.session = client.session();
        this.loop = this.session.getPeer().getChannel().eventLoop();
        this.transport = transport;
        this.codec = codec;
        this.settings = settings;
        this.clientAddr = address((InetSocketAddress) this.session.getPeer().getChannel().remoteAddress());
        this.clientData = SpectrumLoginData.clientData(client.clientData());
        this.identityData = SpectrumLoginData.identity(client.identity());
    }

    /**
     * Connects a logged-in client to the server at {@code addr} the way spectrum-internal's Session.Login does: dial, the Spectrum connection sequence, the client's StartGame, then spawn.
     * The client is disconnected if any step fails or the whole login takes longer than the settings' login timeout.
     *
     * @return completes once the player has spawned on the server, and fails if the login does
     */
    public static CompletableFuture<Session> login(ClientLogin client, Transport transport, BedrockCodec codec, String addr, SessionSettings settings) {
        BedrockServerSession session = client.session();
        session.getPeer().getChannel().eventLoop().execute(() -> ClientBatchDecoder.holdAfterSpawn(session));
        Session joined;
        try {
            joined = new Session(client, transport, codec, settings);
        } catch (RuntimeException e) {
            // Such as client data that cannot be read.
            ClientDisconnect.disconnect(session, CANT_CONNECT);
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<Connected> connected = joined.connect(addr);
        CompletableFuture<Session> result = connected.thenComposeAsync(next -> {
            StartGamePacket startGame = next.data().startGame();
            startGame.setRuntimeEntityId(next.data().runtimeId());
            startGame.setUniqueEntityId(next.data().uniqueId());
            joined.runtimeId = next.data().runtimeId();
            joined.dimension = startGame.getDimensionId();
            joined.server = next.server();
            joined.serverAddr = addr;
            return ClientGameStart.start(session, startGame, next.data().items(), 0)
                    .applyToEither(next.closed(), spawned -> spawned)
                    .thenCompose(spawned -> next.handshake().spawn())
                    .thenApply(spawned -> joined);
        }, joined.loop);
        result.orTimeout(settings.loginTimeout().toMillis(), TimeUnit.MILLISECONDS);

        ClientDisconnect.ended(session).thenRun(() -> result.completeExceptionally(new ClosedChannelException()));
        result.whenComplete((spawned, error) -> {
            if (error != null) {
                connected.cancel(false);
                connected.thenAccept(next -> next.server().close());
                ClientDisconnect.disconnect(session, CANT_CONNECT);
                return;
            }
            joined.start();
        });
        return result;
    }

    // A server that has run the Spectrum connection sequence and waits for the player to spawn; closed fails once its connection closes.
    private record Connected(ServerConn server, ServerHandshake handshake, ServerGameData data, CompletableFuture<Void> closed) {
    }

    private CompletableFuture<Connected> connect(String addr) {
        CompletableFuture<ServerConn> dial;
        try {
            ConnectionRequest request = new ConnectionRequest(this.clientAddr, this.clientData, this.identityData, this.codec.getProtocolVersion(), this.cache);
            dial = ServerConn.dial(this.transport, addr, this.codec, conn -> new ServerHandshake(conn, request, this.client.clientCacheSupported(), new ServerForwarder(this, conn)));
        } catch (RuntimeException e) {
            // Such as an address without a port.
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Connected> connected = dial.thenCompose(conn -> {
            ServerHandshake handshake = conn.channel().pipeline().get(ServerHandshake.class);
            if (handshake == null) {
                return CompletableFuture.failedFuture(new ClosedChannelException());
            }
            // The steps after the sequence do not watch the server, so a server close during them fails the closed future.
            CompletableFuture<Void> closed = new CompletableFuture<>();
            conn.channel().closeFuture().addListener(done -> closed.completeExceptionally(conn.cause() != null ? conn.cause() : new ClosedChannelException()));
            return handshake.connected().thenApply(data -> new Connected(conn, handshake, data, closed));
        });
        connected.whenComplete((next, error) -> {
            if (error != null) {
                dial.cancel(false);
                dial.thenAccept(ServerConn::close);
            }
        });
        return connected;
    }

    private void start() {
        this.loop.execute(() -> {
            // A transfer the server asked for during the login can have started already, and taken the server away.
            ServerConn server = this.server;
            if (ClientDisconnect.ended(this.session).isDone()) {
                if (server != null) {
                    server.close();
                }
                if (this.transfer != null) {
                    this.transfer.cancel(false);
                }
                return;
            }
            long interval = this.settings.latencyInterval().toMillis();
            ScheduledFuture<?> latency = this.loop.scheduleAtFixedRate(this::sendLatency, interval, interval, TimeUnit.MILLISECONDS);
            ClientDisconnect.ended(this.session).thenRun(() -> {
                latency.cancel(false);
                if (this.transfer != null) {
                    this.transfer.cancel(false);
                }
                if (this.queued != null) {
                    this.queued.completeExceptionally(new ClosedChannelException());
                }
                ServerConn current = this.server;
                if (current != null) {
                    current.close();
                }
            });
            this.session.setPacketHandler(new BedrockPacketHandler() {
                @Override
                public void onDisconnect(CharSequence reason) {
                    ServerConn current = Session.this.server;
                    if (current != null) {
                        current.close();
                    }
                }
            });
            // Each of the client's batches goes to the server as one, with its packets as the client sent them; during a transfer they are dropped, since the next server has not spawned the player yet.
            ClientBatchDecoder.forwardBatches(this.session, batch -> {
                ServerConn current = this.server;
                if (current != null && this.transfer == null) {
                    current.writeBatch(batch);
                }
            });
            // The packets the server sent during the connection sequence, which were passed on at spawn, would otherwise wait for its next Flush.
            this.clientChannel().flush();
            if (server != null) {
                this.watch(server);
            }
        });
    }

    private void watch(ServerConn server) {
        server.channel().closeFuture().addListener(closed -> this.execute(() -> {
            if (server == this.server && this.transfer == null) {
                this.server = null;
                this.fallback();
            }
        }));
    }

    // One fallback try, as spectrum-internal's fallback.
    private void fallback() {
        if (ClientDisconnect.ended(this.session).isDone()) {
            return;
        }
        if (this.settings.fallbackServer() == null || this.inFallback) {
            ClientDisconnect.disconnect(this.session, CANT_CONNECT);
            return;
        }
        this.inFallback = true;
        this.startTransfer(this.settings.fallbackServer(), new CompletableFuture<>(), true);
    }

    /**
     * Moves the player to the server at {@code addr}, as spectrum-internal's Session.Transfer does.
     * The current server is closed first. If the transfer fails or takes longer than {@link #TRANSFER_TIMEOUT}, the player goes to the fallback server, or is disconnected.
     *
     * @return completes once the player has spawned on the new server; fails if this transfer fails, if the one it waited for fails, if a later one replaced it before it started, or if the client has left
     */
    public CompletableFuture<Void> transfer(String addr) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            this.loop.execute(() -> this.startTransfer(addr, done, false));
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(new ClosedChannelException());
        }
        return done;
    }

    private void startTransfer(String addr, CompletableFuture<Void> done, boolean toFallback) {
        if (ClientDisconnect.ended(this.session).isDone()) {
            done.completeExceptionally(new ClosedChannelException());
            return;
        }
        if (this.transfer != null) {
            if (this.queued != null) {
                this.queued.completeExceptionally(new IllegalStateException("replaced by a later transfer"));
            }
            this.queuedAddr = addr;
            this.queued = done;
            return;
        }
        this.transfer = done;
        // A transfer away from the fallback allows another fallback; a fallback that only succeeds does not, so a fallback server that keeps dropping the player cannot hold it in a loop.
        if (!toFallback) {
            this.inFallback = false;
        }
        TransferScreen.freeze(this.session, this.runtimeId);
        ServerConn old = this.server;
        this.server = null;
        if (old != null) {
            old.close();
        }

        CompletableFuture<Connected> connected = this.connect(addr);
        CompletableFuture<Connected> spawned = connected.thenComposeAsync(next -> {
            TransferScreen.play(this.session, this.dimension, next.data());
            TransferScreen.resetWorld(this.session, this.tracker, this.runtimeId, next.data());
            this.server = next.server();
            this.serverAddr = addr;
            return next.handshake().spawn().applyToEither(next.closed(), ignored -> next);
        }, this.loop);
        spawned.orTimeout(TRANSFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        done.whenComplete((ignored, error) -> {
            if (error != null) {
                spawned.cancel(false);
            }
        });
        spawned.whenCompleteAsync((next, error) -> {
            this.transfer = null;
            // A client that left while the transfer ran leaves nobody to play on the new server.
            if (error != null || done.isCompletedExceptionally() || ClientDisconnect.ended(this.session).isDone()) {
                connected.cancel(false);
                connected.thenAccept(failed -> failed.server().close());
                this.server = null;
                done.completeExceptionally(error != null ? error : new ClosedChannelException());
                if (this.queued != null) {
                    this.queued.completeExceptionally(new IllegalStateException("the transfer before it failed"));
                    this.queued = null;
                }
                this.fallback();
                return;
            }
            TransferScreen.clear(this.session, next.data());
            this.clientChannel().flush();
            this.watch(next.server());
            done.complete(null);
            if (this.queued != null) {
                CompletableFuture<Void> waiting = this.queued;
                this.queued = null;
                this.startTransfer(this.queuedAddr, waiting, false);
            }
        }, this.loop);
    }

    // Runs on the client's event loop; once that loop has shut down, the client is gone and there is nothing to run.
    private void execute(Runnable task) {
        try {
            this.loop.execute(task);
        } catch (RejectedExecutionException e) {
            // Nothing to do.
        }
    }

    // As spectrum-internal's handleLatency does. RakNet's measure is in milliseconds, and 0 before the first pong.
    private void sendLatency() {
        this.clientRoundTrip = ((RakChannel) this.clientChannel()).rakPipeline().get(RakSessionCodec.class).getPing();
        ServerConn current = this.server;
        if (current != null) {
            current.write(new Latency(this.clientRoundTrip, System.currentTimeMillis()));
        }
    }

    // Whether packets from server reach the client: it is the current server, and the client is still there.
    boolean forwardsFrom(ServerConn server) {
        return server == this.server && !ClientDisconnect.ended(this.session).isDone();
    }

    Channel clientChannel() {
        return this.session.getPeer().getChannel();
    }

    Tracker tracker() {
        return this.tracker;
    }

    void serverLatency(long latency) {
        this.serverLatency = latency;
    }

    // What the server last stored for this player with UpdateCache, which the next server's ConnectionRequest carries.
    void cache(byte[] cache) {
        this.cache = cache;
    }

    byte[] cache() {
        return this.cache;
    }

    // host:port, with brackets around an IPv6 host.
    static String address(InetSocketAddress address) {
        String host = address.getAddress().getHostAddress();
        return (address.getAddress() instanceof Inet6Address ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    /**
     * The latency between the player and the server in milliseconds: the client's round trip time, as last sent to the server, plus the latency the server last reported.
     */
    public long latency() {
        return this.clientRoundTrip + this.serverLatency;
    }

    public ClientLogin client() {
        return this.client;
    }

    /**
     * The server the player is on, or null during a transfer and once its server is lost.
     */
    public ServerConn server() {
        return this.server;
    }

    /**
     * The address of the server the player is on, or last went to.
     */
    public String serverAddress() {
        return this.serverAddr;
    }
}
