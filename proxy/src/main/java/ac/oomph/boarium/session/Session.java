package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientBatchDecoder;
import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.client.ClientGameStart;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.SpectrumLoginData;
import ac.oomph.boarium.protocol.Definitions;
import ac.oomph.boarium.server.ServerConn;
import ac.oomph.boarium.server.ServerGameData;
import ac.oomph.boarium.server.ServerHandshake;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.SpectrumPackets;
import ac.oomph.boarium.server.packet.Transfer;
import ac.oomph.boarium.server.packet.UpdateCache;
import ac.oomph.boarium.transport.Transport;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.channel.TransportChannel;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One player on the proxy: a logged-in client and its connection to the downstream server it plays on, as spectrum-internal's Session.
 * Once the player spawns, packets pass between the two, and the client's latency goes to the server at the settings' interval.
 * The player can be transferred to another server; when its server is lost, it is transferred once to the fallback server, if there is one, and otherwise disconnected.
 * A {@link Processor} from the settings sees the traffic on the way.
 * The session's server, its transfers and the fallback change on the client's event loop.
 * A transfer asked for while one runs waits for it, and the latest such address wins.
 */
public final class Session {
    // spectrum-internal's Session.Transfer timeout.
    static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(1);
    private static final String CANT_CONNECT = "disconnectionScreen.internalError.cantConnect";
    private static final Logger LOG = LoggerFactory.getLogger(Session.class);

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
    private final Processor processor;
    private final Set<Integer> clientDecode;
    private final Set<Integer> serverDecode;
    // Decodes what servers send for the processor, with the client's item definitions and Cloudburst's limits for a game client, which a server's large lists and strings need.
    private final BedrockCodecHelper serverHelper;

    private volatile ServerConn server;
    private volatile String serverAddr;
    // What the server last stored for this player with UpdateCache, which the next server's ConnectionRequest carries.
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

    private Session(ClientLogin client, Transport transport, SessionSettings settings) {
        this.client = client;
        this.session = client.session();
        this.loop = this.session.getPeer().getChannel().eventLoop();
        this.transport = transport;
        // Every server of the session speaks the client's protocol, as with spectrum-internal's sync_protocol on.
        this.codec = this.session.getCodec();
        this.settings = settings;
        this.processor = settings.processors().apply(client);
        // A client sends ClientCacheStatus again after it spawns. The server would turn its blob cache on from that one, so it is decoded to be corrected while the processor takes no cache.
        Set<Integer> clientDecode = new HashSet<>(this.processor.clientDecode());
        if (!this.processor.clientCache()) {
            clientDecode.add(this.codec.getPacketDefinition(ClientCacheStatusPacket.class).getId());
        }
        this.clientDecode = Set.copyOf(clientDecode);
        this.serverDecode = Set.copyOf(this.processor.serverDecode());
        this.serverHelper = this.codec.createHelper();
        this.serverHelper.setEncodingSettings(EncodingSettings.CLIENT);
        this.serverHelper.setBlockDefinitions(Definitions.ANY_BLOCK);
        this.clientAddr = address(this.session.getPeer().getChannel().remoteAddress());
        this.clientData = SpectrumLoginData.clientData(client.clientData());
        this.identityData = SpectrumLoginData.identity(client.identity());
    }

    /**
     * Connects a logged-in client to the server at {@code addr} the way spectrum-internal's Session.Login does: dial, the Spectrum connection sequence, the client's StartGame, then spawn.
     * The client is disconnected if any step fails or the whole login takes longer than the settings' login timeout.
     *
     * @return completes once the player has spawned on the server, and fails if the login does
     */
    public static CompletableFuture<Session> login(ClientLogin client, Transport transport, String addr, SessionSettings settings) {
        BedrockServerSession session = client.session();
        session.getPeer().getChannel().eventLoop().execute(() -> ClientBatchDecoder.holdAfterSpawn(session));
        Session joined;
        try {
            joined = new Session(client, transport, settings);
        } catch (RuntimeException e) {
            // Such as client data that cannot be read.
            ClientDisconnect.disconnect(session, CANT_CONNECT);
            return CompletableFuture.failedFuture(e);
        }

        ClientDisconnect.ended(session).thenRun(() -> joined.hook(() -> joined.processor.disconnect(joined)));
        CompletableFuture<Connected> connected = joined.connect(addr);
        CompletableFuture<Session> result = connected.thenComposeAsync(next -> {
            StartGamePacket startGame = next.data().startGame();
            startGame.setRuntimeEntityId(next.data().runtimeId());
            startGame.setUniqueEntityId(next.data().uniqueId());
            joined.runtimeId = next.data().runtimeId();
            joined.dimension = startGame.getDimensionId();
            joined.server = next.server();
            joined.serverAddr = addr;
            joined.serverHelper.setItemDefinitions(Definitions.items(next.data().items()));
            if (!joined.hook(() -> joined.processor.startGame(joined, startGame))) {
                return CompletableFuture.failedFuture(new IllegalStateException("the processor failed at StartGame"));
            }
            return ClientGameStart.start(session, startGame, next.data().items(), 0)
                    .applyToEither(next.closed(), spawned -> spawned)
                    .thenCompose(spawned -> joined.hook(() -> joined.processor.spawned(joined))
                            ? next.handshake().spawn()
                            : CompletableFuture.failedFuture(new IllegalStateException("the processor failed at spawn")))
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
            dial = ServerConn.dial(this.transport, addr, this.codec, conn -> new ServerHandshake(conn, request, this.client.clientCacheSupported() && this.processor.clientCache(), new ServerForwarder(this, conn)));
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
            // Each of the client's batches goes to the server as one, with its packets as the client sent them.
            // During a transfer the next server has not spawned the player yet, so a batch goes to no server, but the processor still sees it, such as the client's answers to its NetworkStackLatency packets.
            ClientBatchDecoder.forwardBatches(this.session, batch -> this.fromClient(this.transfer == null ? this.server : null, batch));
            // The packets the server sent during the connection sequence, which were passed on at spawn, would otherwise wait for its next Flush.
            this.flushToClient();
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
        String origin = this.server != null ? this.serverAddr : null;
        if (!this.hook(() -> this.processor.preTransfer(this, origin, addr))) {
            done.completeExceptionally(new IllegalStateException("the processor failed before the transfer"));
            return;
        }
        this.transfer = done;
        // A transfer away from the fallback allows another fallback; a fallback that only succeeds does not, so a fallback server that keeps dropping the player cannot hold it in a loop.
        if (!toFallback) {
            this.inFallback = false;
        }
        TransferScreen.freeze(this::sendFromProxy, this.runtimeId);
        this.flushToClient();
        ServerConn old = this.server;
        this.server = null;
        if (old != null) {
            old.close();
        }

        CompletableFuture<Connected> connected = this.connect(addr);
        CompletableFuture<Connected> spawned = connected.thenComposeAsync(next -> {
            TransferScreen.play(this::sendFromProxy, this.dimension, next.data());
            TransferScreen.resetWorld(this::sendFromProxy, this.tracker, this.runtimeId, next.data());
            this.flushToClient();
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
                // A client that left has had its disconnect hook already.
                if (!ClientDisconnect.ended(this.session).isDone()) {
                    this.hook(() -> this.processor.transferFailure(this, origin, addr));
                }
                if (this.queued != null) {
                    this.queued.completeExceptionally(new IllegalStateException("the transfer before it failed"));
                    this.queued = null;
                }
                this.fallback();
                return;
            }
            TransferScreen.clear(this::sendFromProxy, next.data());
            this.flushToClient();
            this.watch(next.server());
            done.complete(null);
            this.hook(() -> this.processor.postTransfer(this, origin, addr));
            if (this.queued != null) {
                CompletableFuture<Void> waiting = this.queued;
                this.queued = null;
                this.startTransfer(this.queuedAddr, waiting, false);
            }
        }, this.loop);
    }

    // A batch from the client, on its event loop, through the processor to the server, or to no server when it is null.
    private void fromClient(ServerConn server, List<ByteBuf> batch) {
        if (this.processor == Processor.NONE) {
            if (server != null) {
                server.writeBatch(batch);
            }
            return;
        }
        List<PacketContext> contexts = new ArrayList<>(batch.size());
        List<ByteBuf> encoded = new ArrayList<>();
        try {
            for (ByteBuf payload : batch) {
                BedrockPacket packet;
                try {
                    packet = this.decodeIfAsked(payload, this.clientDecode, this.session.getPeer().getCodecHelper(), PacketRecipient.SERVER);
                } catch (RuntimeException e) {
                    // Any client can send a packet that does not decode, so this is no error of the proxy's.
                    LOG.debug("{} sent a packet that does not decode", this.client.identity().displayName(), e);
                    ClientDisconnect.disconnect(this.session, CANT_CONNECT);
                    return;
                }
                PacketContext context = new PacketContext(payload, packet);
                // The processor reads chunks only when they come whole, as the ClientCacheStatus sent at the server's spawn says.
                if (packet instanceof ClientCacheStatusPacket status && status.isSupported() && !this.processor.clientCache()) {
                    status.setSupported(false);
                    context.setModified();
                }
                contexts.add(context);
            }
            if (!this.hook(() -> this.processor.client(this, contexts))) {
                return;
            }
            List<ByteBuf> out = new ArrayList<>(contexts.size());
            for (PacketContext context : contexts) {
                if (context.cancelled()) {
                    continue;
                }
                if (context.modified()) {
                    ByteBuf packet = this.encode(context.packet());
                    encoded.add(packet);
                    out.add(packet);
                } else {
                    out.add(context.payload());
                }
            }
            if (server != null && !out.isEmpty()) {
                server.writeBatch(out);
            }
        } catch (RuntimeException e) {
            this.fail("could not encode a packet the processor changed", e);
        } finally {
            encoded.forEach(ByteBuf::release);
            // A decoded packet can hold a slice of the batch, such as an unknown packet's body.
            contexts.forEach(context -> ReferenceCountUtil.release(context.packet()));
        }
    }

    /**
     * What a server sends, on the client's event loop, through the processor to the client; msg is released here or passed on.
     * Packets are written to the client without a flush, and the server's Flush sends them, so each of the server's batches reaches the client as one batch at once, unless it is larger than RakNet can send as one message.
     * As in Spectrum's production proxy, which turns gophertunnel's flush timer off, there is no flush timer.
     */
    void fromServer(ServerConn server, Object msg) {
        if (!this.forwardsFrom(server)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        switch (msg) {
            case ByteBuf payload -> this.fromServer(payload);
            case BedrockPacket packet -> {
                if (this.processor != Processor.NONE) {
                    PacketContext context = new PacketContext(null, packet);
                    if (!this.hook(() -> this.processor.server(this, context)) || context.cancelled()) {
                        ReferenceCountUtil.release(packet);
                        return;
                    }
                }
                this.tracker.observe(packet);
                this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
            }
            case Flush flush -> {
                this.flushToClient();
            }
            case Latency latency -> this.serverLatency = latency.latency();
            case UpdateCache update -> this.cache = update.cache();
            case Transfer transfer -> this.transfer(transfer.addr());
            default -> ReferenceCountUtil.release(msg);
        }
    }

    private void fromServer(ByteBuf payload) {
        if (this.processor == Processor.NONE) {
            // Cloudburst writes a set packet buffer as it is, so the wrapper needs no packet ID.
            this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, null, payload));
            return;
        }
        BedrockPacket packet;
        try {
            packet = this.decodeIfAsked(payload, this.serverDecode, this.serverHelper, PacketRecipient.CLIENT);
        } catch (RuntimeException e) {
            payload.release();
            this.fail("could not decode a packet from the server", e);
            return;
        }
        PacketContext context = new PacketContext(payload, packet);
        if (!this.hook(() -> this.processor.server(this, context)) || context.cancelled()) {
            payload.release();
            ReferenceCountUtil.release(packet);
            return;
        }
        if (packet != null) {
            this.tracker.observe(packet);
        }
        if (context.modified()) {
            payload.release();
            this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        } else {
            this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, null, payload));
            ReferenceCountUtil.release(packet);
        }
    }

    // A packet of the proxy's own to the client, which the processor sees as one from the server, so that it knows what the client was shown; it goes out with the next flushToClient.
    // The processor cannot drop it, since the client needs each of these to move from the old server's world to the next one.
    private void sendFromProxy(BedrockPacket packet) {
        if (this.processor != Processor.NONE && !this.hook(() -> this.processor.server(this, new PacketContext(null, packet)))) {
            ReferenceCountUtil.release(packet);
            return;
        }
        this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
    }

    private void flushToClient() {
        if (this.processor == Processor.NONE || this.hook(() -> this.processor.flush(this))) {
            this.clientChannel().flush();
        }
    }

    // The packet decoded, when its ID is one the processor asked for.
    private BedrockPacket decodeIfAsked(ByteBuf payload, Set<Integer> ids, BedrockCodecHelper helper, PacketRecipient recipient) {
        if (ids.isEmpty()) {
            return null;
        }
        ByteBuf in = payload.duplicate();
        int id = VarInts.readUnsignedInt(in) & SpectrumPackets.PACKET_ID_MASK;
        if (!ids.contains(id)) {
            return null;
        }
        return this.codec.tryDecode(helper, in, id, recipient);
    }

    // The packet's header and body, as the client and the server read them.
    private ByteBuf encode(BedrockPacket packet) {
        int id = packet instanceof UnknownPacket unknown ? unknown.getPacketId() : this.codec.getPacketDefinition(packet.getClass()).getId();
        ByteBuf out = this.clientChannel().alloc().buffer();
        try {
            VarInts.writeUnsignedInt(out, id);
            this.codec.tryEncode(this.session.getPeer().getCodecHelper(), out, packet);
            return out;
        } catch (RuntimeException e) {
            out.release();
            throw e;
        }
    }

    // Runs a processor hook. One that throws leaves the player's traffic in an unknown state, so the player is disconnected.
    private boolean hook(Runnable call) {
        try {
            call.run();
            return true;
        } catch (RuntimeException e) {
            this.fail("the processor failed", e);
            return false;
        }
    }

    private void fail(String what, RuntimeException e) {
        LOG.error("{} for {}", what, this.client.identity().displayName(), e);
        ClientDisconnect.disconnect(this.session, CANT_CONNECT);
    }

    /**
     * Sends a packet to the client, without going through the processor. It goes out with the next flush, or at once when {@code flush} is set.
     */
    public void sendToClient(BedrockPacket packet, boolean flush) {
        this.runOnLoop(() -> {
            if (flush) {
                this.clientChannel().writeAndFlush(BedrockPacketWrapper.create(0, 0, 0, packet, null));
            } else {
                this.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
            }
        }, packet);
    }

    /**
     * Sends a packet to the player's server, without going through the processor. It is dropped when the player has no server, such as during a transfer.
     */
    public void sendToServer(BedrockPacket packet) {
        this.runOnLoop(() -> {
            ServerConn current = this.server;
            if (current != null && this.transfer == null) {
                current.write(packet);
            } else {
                ReferenceCountUtil.release(packet);
            }
        }, packet);
    }

    // At once on the client's event loop, so that a packet a processor sends in flush goes out with that flush; from another thread, it waits its turn there.
    private void runOnLoop(Runnable task, BedrockPacket packet) {
        if (this.loop.inEventLoop()) {
            task.run();
        } else {
            this.execute(task, packet);
        }
    }

    // Runs on the client's event loop; once that loop has shut down, the client is gone and there is nothing to run.
    private void execute(Runnable task) {
        this.execute(task, null);
    }

    // As execute, releasing packet when the loop has shut down.
    private void execute(Runnable task, BedrockPacket packet) {
        try {
            this.loop.execute(task);
        } catch (RejectedExecutionException e) {
            ReferenceCountUtil.release(packet);
        }
    }

    // As spectrum-internal's handleLatency does. The transport's measure is in milliseconds, and 0 while it is unknown.
    private void sendLatency() {
        this.clientRoundTrip = ((TransportChannel) this.clientChannel()).getPing();
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

    byte[] cache() {
        return this.cache;
    }

    // host:port, with brackets around an IPv6 host, or 0.0.0.0:0 for an address without an IP, since Spectrum reads it as a UDP address.
    static String address(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return "0.0.0.0:0";
        }
        String host = inet.getAddress().getHostAddress();
        return (inet.getAddress() instanceof Inet6Address ? "[" + host + "]" : host) + ":" + inet.getPort();
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
     * The codec of the client's protocol.
     */
    public BedrockCodec codec() {
        return this.codec;
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

    /**
     * The item definitions of the first server, which the client is sent with its StartGame and keeps for the whole session. Set before {@link Processor#startGame}.
     */
    public DefinitionRegistry<ItemDefinition> itemDefinitions() {
        return this.serverHelper.getItemDefinitions();
    }
}
