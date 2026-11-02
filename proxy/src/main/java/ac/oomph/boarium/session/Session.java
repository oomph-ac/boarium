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
import java.util.concurrent.TimeUnit;

/**
 * One player on the proxy: a logged-in client and its connection to a downstream server, which close together.
 * Once the player spawns, packets pass between the two, and the client's latency goes to the server every {@link #LATENCY_INTERVAL}.
 */
public final class Session {
    // spectrum-internal's default latency_interval.
    static final Duration LATENCY_INTERVAL = Duration.ofSeconds(3);
    private static final String CANT_CONNECT = "disconnectionScreen.internalError.cantConnect";

    private final ClientLogin client;
    private final ServerConn server;
    private final ServerForwarder forwarder;
    private volatile long clientRoundTrip;

    private Session(ClientLogin client, ServerConn server, ServerForwarder forwarder) {
        this.client = client;
        this.server = server;
        this.forwarder = forwarder;
    }

    /**
     * Connects a logged-in client to the server at {@code addr} the way spectrum-internal's Session.Login does: dial, the Spectrum connection sequence, the client's StartGame, then spawn.
     * The client is disconnected if any step fails or the whole login takes longer than {@code timeout}.
     *
     * @return completes once the player has spawned on the server, and fails if the login does
     */
    public static CompletableFuture<Session> login(ClientLogin client, Transport transport, String addr, BedrockCodec codec, Duration timeout) {
        BedrockServerSession session = client.session();
        session.getPeer().getChannel().eventLoop().execute(() -> ClientBatchDecoder.holdAfterSpawn(session));
        CompletableFuture<ServerConn> dial;
        try {
            ConnectionRequest request = new ConnectionRequest(address((InetSocketAddress) session.getPeer().getChannel().remoteAddress()), SpectrumLoginData.clientData(client.clientData()),
                    SpectrumLoginData.identity(client.identity()), codec.getProtocolVersion(), new byte[0]);
            dial = ServerConn.dial(transport, addr, codec, conn -> new ServerHandshake(conn, request, client.clientCacheSupported(), new ServerForwarder(session)));
        } catch (RuntimeException e) {
            // Such as client data that cannot be read, or an address without a port.
            dial = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Session> result = dial.thenCompose(conn -> {
            ServerHandshake handshake = conn.channel().pipeline().get(ServerHandshake.class);
            if (handshake == null) {
                return CompletableFuture.failedFuture(new ClosedChannelException());
            }
            // The client's game start does not watch the server, so a server close during it ends the login here.
            CompletableFuture<Void> serverClosed = new CompletableFuture<>();
            conn.channel().closeFuture().addListener(closed -> serverClosed.completeExceptionally(conn.cause() != null ? conn.cause() : new ClosedChannelException()));
            return handshake.connected()
                    .thenCompose(data -> ClientGameStart.start(session, clientStartGame(data), data.items(), 0).applyToEither(serverClosed, spawned -> spawned))
                    .thenCompose(spawned -> handshake.spawn())
                    .thenApply(spawned -> new Session(client, conn, conn.channel().pipeline().get(ServerForwarder.class)));
        });
        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);

        ClientDisconnect.ended(session).thenRun(() -> result.completeExceptionally(new ClosedChannelException()));
        CompletableFuture<ServerConn> dialed = dial;
        result.whenComplete((joined, error) -> {
            if (error != null) {
                dialed.cancel(false);
                dialed.thenAccept(ServerConn::close);
                ClientDisconnect.disconnect(session, CANT_CONNECT);
                return;
            }
            joined.start();
        });
        return result;
    }

    // The client gets the server's StartGame with the player's entity IDs from the ConnectionResponse, as in spectrum-internal's server.Conn.
    private static StartGamePacket clientStartGame(ServerGameData data) {
        StartGamePacket startGame = data.startGame();
        startGame.setRuntimeEntityId(data.runtimeId());
        startGame.setUniqueEntityId(data.uniqueId());
        return startGame;
    }

    // host:port, with brackets around an IPv6 host.
    static String address(InetSocketAddress address) {
        String host = address.getAddress().getHostAddress();
        return (address.getAddress() instanceof Inet6Address ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    private void start() {
        BedrockServerSession session = this.client.session();
        EventLoop loop = session.getPeer().getChannel().eventLoop();
        loop.execute(() -> {
            if (ClientDisconnect.ended(session).isDone()) {
                this.server.close();
                return;
            }
            ScheduledFuture<?> latency = loop.scheduleAtFixedRate(this::sendLatency, LATENCY_INTERVAL.toMillis(), LATENCY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            ClientDisconnect.ended(session).thenRun(() -> {
                latency.cancel(false);
                this.server.close();
            });
            session.setPacketHandler(new BedrockPacketHandler() {
                @Override
                public void onDisconnect(CharSequence reason) {
                    Session.this.server.close();
                }
            });
            // Each of the client's batches goes to the server as one, with its packets as the client sent them.
            ClientBatchDecoder.forwardBatches(session, this.server::writeBatch);
            // The packets the server sent during the connection sequence, which were passed on at spawn, would otherwise wait for its next Flush.
            session.getPeer().getChannel().flush();
        });
        this.server.channel().closeFuture().addListener(closed -> ClientDisconnect.disconnect(session, CANT_CONNECT));
    }

    // As spectrum-internal's handleLatency does. RakNet's measure is in milliseconds, and 0 before the first pong.
    private void sendLatency() {
        this.clientRoundTrip = ((RakChannel) this.client.session().getPeer().getChannel()).rakPipeline().get(RakSessionCodec.class).getPing();
        this.server.write(new Latency(this.clientRoundTrip, System.currentTimeMillis()));
    }

    /**
     * The latency between the player and the server in milliseconds: the client's round trip time, as last sent to the server, plus the latency the server last reported.
     */
    public long latency() {
        return this.clientRoundTrip + this.forwarder.latency();
    }

    public ClientLogin client() {
        return this.client;
    }

    public ServerConn server() {
        return this.server;
    }
}
