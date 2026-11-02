package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.UpdateCache;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;

    private EventLoopGroup group;
    private ClientListener listener;
    private FakeSpectrumServer server;
    private TestClient client;
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
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), CODEC, pong, false, Duration.ofMinutes(1),
                (ClientLogin login) -> this.session.complete(Session.login(login, transport, addr, CODEC, timeout)));
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
        ServerForwarder forwarder = session.server().channel().pipeline().get(ServerForwarder.class);
        long deadline = System.currentTimeMillis() + 10_000;
        while (forwarder.cache().length == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertArrayEquals(new byte[]{1, 2, 3}, forwarder.cache());
        assertTrue(session.latency() >= 1000);
    }

    @Test
    void writesTheClientAddressAsHostAndPort() throws Exception {
        assertEquals("127.0.0.1:19132", Session.address(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 19132)));
        assertEquals("[0:0:0:0:0:0:0:1]:19132", Session.address(new InetSocketAddress(InetAddress.getByName("::1"), 19132)));
    }
}
