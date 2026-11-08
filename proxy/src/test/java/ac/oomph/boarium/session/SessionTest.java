package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.Transfer;
import ac.oomph.boarium.server.packet.UpdateCache;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;

    private EventLoopGroup group;
    private ClientListener listener;
    private FakeSpectrumServer server;
    private TestClient client;
    private Processor processor = Processor.NONE;
    private final CompletableFuture<CompletableFuture<Session>> session = new CompletableFuture<>();

    @BeforeEach
    void setUp() throws Exception {
        this.group = new NioEventLoopGroup(2);
        this.server = new FakeSpectrumServer(this.group, CODEC);
    }

    @AfterEach
    void tearDown() {
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.server.close();
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private void connect(Duration timeout) throws Exception {
        this.connect(this.server.address(), timeout);
        this.client.loginDone.get(10, TimeUnit.SECONDS);
    }

    private void connect(String addr, Duration timeout) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1),
                (ClientLogin login) -> this.session.complete(Session.login(login, transport, addr, new SessionSettings(null, timeout, SessionSettings.DEFAULT.latencyInterval(), joining -> this.processor))));
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.client.connect(this.group, this.listener.address());
    }

    private void requestChunkRadius(int radius) throws Exception {
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(radius);
        this.client.session.get().sendPacket(request);
    }

    // Plays the client's part of the game start, as the game does.
    private Session spawn() throws Exception {
        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        this.requestChunkRadius(8);
        assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, this.client.await(PlayStatusPacket.class).getStatus());
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        this.client.session.get().sendPacket(initialized);
        return this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    }

    private void assertOneDisconnect() throws Exception {
        assertEquals("disconnectionScreen.internalError.cantConnect", this.client.await(DisconnectPacket.class).getKickMessage());
        // A second Disconnect would come right after the first.
        Thread.sleep(500);
        assertTrue(this.client.received.stream().noneMatch(p -> p instanceof DisconnectPacket));
    }

    @Test
    void logsTheClientInThroughTheServer() throws Exception {
        this.connect(Duration.ofMinutes(1));

        ConnectionRequest request = this.server.request.get(10, TimeUnit.SECONDS);
        assertEquals(CODEC.getProtocolVersion(), request.protocolId());
        assertTrue(request.addr().startsWith("127.0.0.1:"), request.addr());
        assertTrue(new String(request.identityData(), StandardCharsets.UTF_8).contains("\"displayName\":\"Tester\""));
        assertTrue(new String(request.clientData(), StandardCharsets.UTF_8).contains("\"ThirdPartyName\":\"Tester\""));

        Session session = this.spawn();
        assertTrue(session.client().session().isConnected());
        assertInstanceOf(RequestChunkRadiusPacket.class, this.server.await());
        assertInstanceOf(ClientCacheStatusPacket.class, this.server.await());
        assertEquals(FakeSpectrumServer.RUNTIME_ID, assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, this.server.await()).getRuntimeEntityId());
    }

    @Test
    void givesTheClientTheEntityIdsFromTheConnectionResponse() throws Exception {
        this.connect(Duration.ofMinutes(1));

        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        assertEquals(FakeSpectrumServer.RUNTIME_ID, startGame.getRuntimeEntityId());
        assertEquals(FakeSpectrumServer.UNIQUE_ID, startGame.getUniqueEntityId());
    }

    @Test
    void disconnectsTheClientOnceWhenItsGameStartFails() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.client.await(StartGamePacket.class);
        this.requestChunkRadius(0);

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
    }

    @Test
    void disconnectsTheClientWhenTheServerClosesDuringTheGameStart() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.client.await(StartGamePacket.class);
        this.server.connection.get(10, TimeUnit.SECONDS).close().sync();

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
    }

    @Test
    void disconnectsTheClientWhenTheServerNeverAnswers() throws Exception {
        this.server.answer = false;
        this.connect(Duration.ofMillis(500));

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
        assertTrue(this.server.connection.get(10, TimeUnit.SECONDS).closeFuture().await(10, TimeUnit.SECONDS));
    }

    @Test
    void disconnectsTheClientWhenNoServerListens() throws Exception {
        String addr = this.server.address();
        this.server.close();
        this.connect(addr, Duration.ofMinutes(1));

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
    }

    @Test
    void endsTheLoginWhenTheClientSendsAPacketThatCannotBeRead() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.client.await(StartGamePacket.class);
        TestClient.sendBrokenPacket(this.client.session.get());

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS));
        this.assertOneDisconnect();
        assertTrue(this.server.connection.get(10, TimeUnit.SECONDS).closeFuture().await(10, TimeUnit.SECONDS));
    }

    @Test
    void disconnectsTheClientForAnAddressWithoutAPort() throws Exception {
        this.connect("127.0.0.1", Duration.ofMinutes(1));

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
    }

    @Test
    void closesTheServerConnectionWhenTheClientLeavesDuringTheLogin() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.client.await(StartGamePacket.class);
        this.client.session.get().disconnect();

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        assertTrue(this.server.connection.get(10, TimeUnit.SECONDS).closeFuture().await(10, TimeUnit.SECONDS));
    }

    @Test
    void closesTheServerConnectionWhenTheClientLeaves() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawn();
        this.client.session.get().disconnect();

        assertTrue(this.server.connection.get(10, TimeUnit.SECONDS).closeFuture().await(10, TimeUnit.SECONDS));
    }

    @Test
    void disconnectsTheClientWhenTheServerCloses() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawn();
        Channel connection = this.server.connection.get(10, TimeUnit.SECONDS);
        connection.close().sync();

        assertEquals("disconnectionScreen.internalError.cantConnect", this.client.await(DisconnectPacket.class).getKickMessage());
    }

    // The spawn leaves its own packets with the client and the server; tests below start after them.
    private Session spawnAndSettle() throws Exception {
        Session session = this.spawn();
        assertInstanceOf(RequestChunkRadiusPacket.class, this.server.await());
        assertInstanceOf(ClientCacheStatusPacket.class, this.server.await());
        assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, this.server.await());
        return session;
    }

    @Test
    void passesTheServersPacketsToTheClientOnItsFlush() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.server.send("SetTime", false);
        this.server.send(new Flush());
        assertInstanceOf(SetTimePacket.class, this.client.await(SetTimePacket.class));
    }

    @Test
    void sendsThePacketsHeldBackDuringTheConnectionSequenceAtSpawn() throws Exception {
        this.connect(Duration.ofMinutes(1));
        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        this.requestChunkRadius(8);
        assertEquals(8, this.client.await(ChunkRadiusUpdatedPacket.class).getRadius());
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        this.client.session.get().sendPacket(initialized);
        this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);

        // The server's own ChunkRadiusUpdated from the sequence, with no Flush from the server.
        assertEquals(12, this.client.await(ChunkRadiusUpdatedPacket.class).getRadius());
    }

    @Test
    void holdsTheServersPacketsUntilItsFlush() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.server.send("SetTime", false);
        Thread.sleep(500);
        assertTrue(this.client.received.stream().noneMatch(p -> p instanceof SetTimePacket));
        this.server.send(new Flush());
        this.client.await(SetTimePacket.class);
    }

    @Test
    void passesOnPacketsTheServerAskedTheProxyToDecode() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.server.send("SetTime", true);
        this.server.send(new Flush());
        assertInstanceOf(SetTimePacket.class, this.client.await(SetTimePacket.class));
    }

    private static TextPacket chat(String message) {
        TextPacket text = new TextPacket();
        text.setType(TextPacket.Type.CHAT);
        text.setSourceName("Tester");
        text.setMessage(message);
        text.setXuid("");
        text.setPlatformChatId("");
        text.setFilteredMessage("");
        return text;
    }

    @Test
    void passesEachClientBatchToTheServerAsOne() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();
        this.server.batches.clear();

        this.client.session.get().getPeer().sendPacketsImmediately(0, 0, chat("one"), chat("two"));

        assertEquals(2, FakeSpectrumServer.poll(this.server.batches).size());
        assertEquals("one", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
        assertEquals("two", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
    }

    @Test
    void passesOnWhatTheClientSentInTheBatchThatSpawnedIt() throws Exception {
        this.connect(Duration.ofMinutes(1));
        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        this.requestChunkRadius(8);
        this.client.await(PlayStatusPacket.class);
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        this.client.session.get().getPeer().sendPacketsImmediately(0, 0, initialized, chat("same batch"));
        this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);

        assertInstanceOf(RequestChunkRadiusPacket.class, this.server.await());
        assertInstanceOf(ClientCacheStatusPacket.class, this.server.await());
        assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, this.server.await());
        assertEquals("same batch", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
    }

    @Test
    void dropsPacketsForASecondPlayerOnTheConnection() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.client.session.get().getPeer().sendPacketImmediately(1, 0, chat("second player"));
        this.client.session.get().getPeer().sendPacketImmediately(0, 0, chat("first player"));
        assertEquals("first player", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
    }

    @Test
    void sendsTheClientsLatencyToTheServer() throws Exception {
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        long before = System.currentTimeMillis();
        Latency latency = assertInstanceOf(Latency.class, FakeSpectrumServer.poll(this.server.spectrum));
        assertTrue(latency.latency() >= 0);
        assertTrue(latency.timestamp() >= before);
    }

    @Test
    void keepsTheServersLatencyAndCache() throws Exception {
        this.connect(Duration.ofMinutes(1));
        Session session = this.spawnAndSettle();

        this.server.send(new Latency(1000, System.currentTimeMillis()));
        this.server.send(new UpdateCache(new byte[]{1, 2, 3}));
        long deadline = System.currentTimeMillis() + 10_000;
        while (session.cache().length == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertArrayEquals(new byte[]{1, 2, 3}, session.cache());
        assertTrue(session.latency() >= 1000);
    }

    @Test
    void buildsSpectrumsEmptyChunks() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(SessionTest.class.getResourceAsStream("spectrum-empty-chunks.txt"), StandardCharsets.UTF_8))) {
            List<String[]> chunks = reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split(" ")).toList();
            assertEquals(3, chunks.size());
            for (String[] chunk : chunks) {
                ByteBuf built = TransferScreen.emptyChunk(Integer.parseInt(chunk[0]));
                assertEquals(chunk[1], ByteBufUtil.hexDump(built), "dimension " + chunk[0]);
                built.release();
            }
        }
    }

    private void connectWithFallback(String fallback) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1),
                login -> this.session.complete(Session.login(login, transport, this.server.address(), new SessionSettings(fallback, Duration.ofMinutes(1), SessionSettings.DEFAULT.latencyInterval(), joining -> this.processor))));
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.client.connect(this.group, this.listener.address());
        this.client.loginDone.get(10, TimeUnit.SECONDS);
    }

    // Waits for the second server to take the player through the connection sequence.
    private void assertJoined(FakeSpectrumServer next) throws Exception {
        next.request.get(10, TimeUnit.SECONDS);
        assertInstanceOf(RequestChunkRadiusPacket.class, next.await());
        assertInstanceOf(ClientCacheStatusPacket.class, next.await());
        assertEquals(FakeSpectrumServer.RUNTIME_ID, assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, next.await()).getRuntimeEntityId());
    }

    @Test
    void transfersThePlayerWhenTheServerAsks() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            this.server.send(new UpdateCache(new byte[]{7}));
            AddEntityPacket entity = new AddEntityPacket();
            entity.setUniqueEntityId(42);
            entity.setRuntimeEntityId(42);
            entity.setIdentifier("minecraft:pig");
            entity.setPosition(Vector3f.ZERO);
            entity.setMotion(Vector3f.ZERO);
            entity.setRotation(Vector2f.ZERO);
            entity.setBodyRotation(0);
            this.server.send(entity);
            this.server.send(new Transfer(next.address()));

            assertTrue(this.server.connection.get(10, TimeUnit.SECONDS).closeFuture().await(10, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{7}, next.request.get(10, TimeUnit.SECONDS).cache());
            assertTrue(this.client.await(SetEntityDataPacket.class).getMetadata().getFlags().get(EntityFlag.NO_AI));
            assertEquals(1, this.client.await(ChangeDimensionPacket.class).getDimension());
            this.client.await(LevelChunkPacket.class);
            assertEquals(42, this.client.await(RemoveEntityPacket.class).getUniqueEntityId());
            this.client.await(MovePlayerPacket.class);
            this.assertJoined(next);
            assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, this.client.await(PlayStatusPacket.class).getStatus());
            assertEquals(0, this.client.await(ChangeDimensionPacket.class).getDimension());
            assertEquals(next.address(), session.serverAddress());

            // The client's packets now go to the new server.
            this.client.session.get().getPeer().sendPacketImmediately(0, 0, chat("after"));
            assertEquals("after", assertInstanceOf(TextPacket.class, next.await()).getMessage());
        } finally {
            next.close();
        }
    }

    @Test
    void runsATransferAskedForDuringAnotherOnceItEnds() throws Exception {
        FakeSpectrumServer second = new FakeSpectrumServer(this.group, CODEC);
        FakeSpectrumServer third = new FakeSpectrumServer(this.group, CODEC);
        FakeSpectrumServer fourth = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            CompletableFuture<Void> toSecond = session.transfer(second.address());
            CompletableFuture<Void> toThird = session.transfer(third.address());
            CompletableFuture<Void> toFourth = session.transfer(fourth.address());

            toSecond.get(10, TimeUnit.SECONDS);
            // The latest address waiting wins.
            assertThrows(ExecutionException.class, () -> toThird.get(10, TimeUnit.SECONDS));
            toFourth.get(10, TimeUnit.SECONDS);
            this.assertJoined(fourth);
            assertEquals(fourth.address(), session.serverAddress());
            assertTrue(!third.connection.isDone());
        } finally {
            second.close();
            third.close();
            fourth.close();
        }
    }

    @Test
    void transfersThePlayerWhenTheServerAsksDuringTheLogin() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.server.transferDuringSequence = next.address();
            this.connect(Duration.ofMinutes(1));
            this.spawn();

            this.assertJoined(next);
        } finally {
            next.close();
        }
    }

    @Test
    void followsOnlyTheServersFirstTransfer() throws Exception {
        FakeSpectrumServer first = new FakeSpectrumServer(this.group, CODEC);
        FakeSpectrumServer second = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            this.server.sendTogether(new Transfer(first.address()), "SetTime", new Transfer(second.address()), new Flush());

            this.assertJoined(first);
            // A transfer to the second server would have followed the first by now.
            Thread.sleep(500);
            assertEquals(first.address(), session.serverAddress());
            assertTrue(!second.connection.isDone());
            assertTrue(this.client.received.stream().noneMatch(p -> p instanceof SetTimePacket));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void disconnectsAPlayerTheFallbackServerKeepsDropping() throws Exception {
        FakeSpectrumServer fallback = new FakeSpectrumServer(this.group, CODEC);
        try {
            fallback.closeAfterSpawn = true;
            this.connectWithFallback(fallback.address());
            this.spawnAndSettle();
            this.server.connection.get(10, TimeUnit.SECONDS).close().sync();

            this.assertJoined(fallback);
            this.assertOneDisconnect();
        } finally {
            fallback.close();
        }
    }

    @Test
    void movesThePlayerToTheFallbackServerWhenItsServerIsLost() throws Exception {
        FakeSpectrumServer fallback = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connectWithFallback(fallback.address());
            this.spawnAndSettle();
            this.server.connection.get(10, TimeUnit.SECONDS).close().sync();

            this.assertJoined(fallback);
            assertTrue(this.client.received.stream().noneMatch(p -> p instanceof DisconnectPacket));
        } finally {
            fallback.close();
        }
    }

    @Test
    void sendsThePlayerBackToTheFallbackServerWhenATransferFromItFails() throws Exception {
        FakeSpectrumServer fallback = new FakeSpectrumServer(this.group, CODEC);
        FakeSpectrumServer dead = new FakeSpectrumServer(this.group, CODEC);
        String deadAddr = dead.address();
        dead.close();
        try {
            this.connectWithFallback(fallback.address());
            this.spawnAndSettle();
            this.server.connection.get(10, TimeUnit.SECONDS).close().sync();
            this.assertJoined(fallback);

            fallback.send(new Transfer(deadAddr));
            // The fallback server takes the player again: its second ConnectionRequest.
            assertInstanceOf(ConnectionRequest.class, FakeSpectrumServer.poll(fallback.spectrum));
            assertTrue(this.client.received.stream().noneMatch(p -> p instanceof DisconnectPacket));
        } finally {
            fallback.close();
        }
    }

    @Test
    void disconnectsThePlayerWhenTheFallbackServerFailsToo() throws Exception {
        FakeSpectrumServer fallback = new FakeSpectrumServer(this.group, CODEC);
        String addr = fallback.address();
        fallback.close();
        this.connectWithFallback(addr);
        this.spawnAndSettle();
        this.server.connection.get(10, TimeUnit.SECONDS).close().sync();

        this.assertOneDisconnect();
    }

    // Records what it sees, and changes chat and time packets by their text.
    private static final class TestProcessor implements Processor {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Set<Integer> clientDecode() {
            return Set.of(CODEC.getPacketDefinition(TextPacket.class).getId());
        }

        @Override
        public Set<Integer> serverDecode() {
            return Set.of(CODEC.getPacketDefinition(SetTimePacket.class).getId());
        }

        @Override
        public void startGame(Session session, StartGamePacket startGame) {
            this.calls.add("startGame " + startGame.getLevelName());
        }

        @Override
        public void client(Session session, List<PacketContext> batch) {
            for (PacketContext context : batch) {
                if (context.packet() instanceof TextPacket text) {
                    if (text.getMessage().equals("drop")) {
                        context.cancel();
                    } else if (text.getMessage().equals("change")) {
                        text.setMessage("changed");
                        context.setModified();
                    }
                }
            }
        }

        @Override
        public void server(Session session, PacketContext packet) {
            if (packet.packet() instanceof SetTimePacket time) {
                if (time.getTime() == 42) {
                    time.setTime(7000);
                    packet.setModified();
                }
            }
        }

        @Override
        public void flush(Session session) {
            this.calls.add("flush");
        }

        @Override
        public void preTransfer(Session session, String origin, String target) {
            this.calls.add("preTransfer");
        }

        @Override
        public void postTransfer(Session session, String origin, String target) {
            this.calls.add("postTransfer");
        }

        @Override
        public void transferFailure(Session session, String origin, String target) {
            this.calls.add("transferFailure");
        }

        @Override
        public void disconnect(Session session) {
            this.calls.add("disconnect");
        }
    }

    @Test
    void letsTheProcessorDropAndChangeClientPackets() throws Exception {
        TestProcessor processor = new TestProcessor();
        this.processor = processor;
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.client.session.get().getPeer().sendPacketsImmediately(0, 0, chat("drop"), chat("change"), chat("keep"));
        assertEquals("changed", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
        assertEquals("keep", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
        assertEquals("startGame boarium", processor.calls.getFirst());
    }

    @Test
    void letsTheProcessorChangeServerPacketsItAskedToDecode() throws Exception {
        TestProcessor processor = new TestProcessor();
        this.processor = processor;
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        // The gophertunnel SetTime holds the time 42, and comes as bytes.
        this.server.send("SetTime", false);
        this.server.send(new Flush());
        assertEquals(7000, this.client.await(SetTimePacket.class).getTime());
        assertTrue(processor.calls.contains("flush"));
    }

    @Test
    void sendsTheProcessorsOwnPacketsWithTheFlush() throws Exception {
        this.processor = new Processor() {
            @Override
            public void flush(Session session) {
                session.sendToClient(chat("from the processor"), false);
                session.sendToServer(chat("to the server"));
            }
        };
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.server.send(new Flush());
        assertEquals("from the processor", this.client.await(TextPacket.class).getMessage());
        assertEquals("to the server", assertInstanceOf(TextPacket.class, this.server.await()).getMessage());
    }

    @Test
    void tellsTheProcessorAboutTransfersAndTheDisconnect() throws Exception {
        TestProcessor processor = new TestProcessor();
        this.processor = processor;
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            session.transfer(next.address()).get(10, TimeUnit.SECONDS);
            this.client.session.get().disconnect();

            long deadline = System.currentTimeMillis() + 10_000;
            while (!processor.calls.contains("disconnect") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(List.of("preTransfer", "postTransfer", "disconnect"), processor.calls.stream().filter(call -> !call.startsWith("startGame") && !call.equals("flush")).toList());
        } finally {
            next.close();
        }
    }

    @Test
    void showsTheProcessorWhatTheClientSendsDuringATransfer() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        this.processor = new Processor() {
            @Override
            public Set<Integer> clientDecode() {
                return Set.of(CODEC.getPacketDefinition(TextPacket.class).getId());
            }

            @Override
            public void client(Session session, List<PacketContext> batch) {
                batch.forEach(context -> seen.add(((TextPacket) context.packet()).getMessage()));
            }
        };
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            next.answer = false;
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            session.transfer(next.address());
            next.request.get(10, TimeUnit.SECONDS);
            this.client.session.get().getPeer().sendPacketImmediately(0, 0, chat("during the transfer"));

            long deadline = System.currentTimeMillis() + 10_000;
            while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(List.of("during the transfer"), seen);
            assertTrue(next.received.isEmpty());
        } finally {
            next.close();
        }
    }

    @Test
    void showsTheProcessorWhatATransferSendsTheClient() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        this.processor = new Processor() {
            @Override
            public void server(Session session, PacketContext packet) {
                if (packet.packet() != null) {
                    seen.add(packet.packet().getClass().getSimpleName());
                }
            }
        };
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            this.connect(Duration.ofMinutes(1));
            Session session = this.spawnAndSettle();
            session.transfer(next.address()).get(10, TimeUnit.SECONDS);

            assertTrue(seen.containsAll(List.of("SetEntityDataPacket", "ChangeDimensionPacket", "LevelChunkPacket", "MovePlayerPacket", "PlayStatusPacket")), seen.toString());
        } finally {
            next.close();
        }
    }

    @Test
    void disconnectsThePlayerWhenTheProcessorFails() throws Exception {
        this.processor = new Processor() {
            @Override
            public void flush(Session session) {
                throw new IllegalStateException("broken processor");
            }
        };
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        this.server.send(new Flush());
        this.assertOneDisconnect();
    }

    @Test
    void tellsTheProcessorTheClientSpawnedBeforeTheServerHears() throws Exception {
        CompletableFuture<Boolean> serverHeardFirst = new CompletableFuture<>();
        this.processor = new Processor() {
            @Override
            public void spawned(Session session) {
                serverHeardFirst.complete(SessionTest.this.server.received.stream().anyMatch(p -> p instanceof SetLocalPlayerAsInitializedPacket));
            }
        };
        this.connect(Duration.ofMinutes(1));
        this.spawnAndSettle();

        assertFalse(serverHeardFirst.get(10, TimeUnit.SECONDS));
    }

    @Test
    void disconnectsThePlayerWhenTheProcessorFailsAtSpawn() throws Exception {
        this.processor = new Processor() {
            @Override
            public void spawned(Session session) {
                throw new IllegalStateException("broken processor");
            }
        };
        this.connect(Duration.ofMinutes(1));

        assertThrows(ExecutionException.class, () -> this.spawn());
        this.assertOneDisconnect();
        // The hook runs before the server is told, so its failure stops that.
        assertTrue(this.server.received.stream().noneMatch(p -> p instanceof SetLocalPlayerAsInitializedPacket));
    }

    @Test
    void disconnectsThePlayerWhenTheProcessorFailsBeforeATransfer() throws Exception {
        this.processor = new Processor() {
            @Override
            public void preTransfer(Session session, String origin, String target) {
                throw new IllegalStateException("broken processor");
            }
        };
        this.connect(Duration.ofMinutes(1));
        Session session = this.spawnAndSettle();

        assertThrows(ExecutionException.class, () -> session.transfer("127.0.0.1:1").get(10, TimeUnit.SECONDS));
        this.assertOneDisconnect();
    }

    @Test
    void tellsTheProcessorAboutTheDisconnectOfAFailedLogin() throws Exception {
        TestProcessor processor = new TestProcessor();
        this.processor = processor;
        this.server.answer = false;
        this.connect(Duration.ofMillis(500));

        assertThrows(ExecutionException.class, () -> this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!processor.calls.contains("disconnect") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of("disconnect"), processor.calls);
    }

    @Test
    void writesTheClientAddressAsHostAndPort() throws Exception {
        assertEquals("127.0.0.1:19132", Session.address(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 19132)));
        assertEquals("[0:0:0:0:0:0:0:1]:19132", Session.address(new InetSocketAddress(InetAddress.getByName("::1"), 19132)));
        assertEquals("0.0.0.0:0", Session.address(InetSocketAddress.createUnresolved("player.example", 19132)));
        assertEquals("0.0.0.0:0", Session.address(new LocalAddress("player")));
    }
}
