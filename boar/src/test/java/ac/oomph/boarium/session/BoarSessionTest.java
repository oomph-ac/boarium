package ac.oomph.boarium.session;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.mappings.block.Blocks;
import ac.boar.mappings.entity.EntityDefinitions;
import ac.boar.mappings.item.Items;
import ac.boar.protocol.PacketEvents;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.boar.BoariumBoar;
import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// In the session package to reach the fake server's own sends.
class BoarSessionTest {
    private static final BedrockCodec CODEC = Boarium.CODEC;
    // Every packet Boar's listeners see, after them.
    private static final Recorder RECORDER = new Recorder();

    @TempDir
    static Path dataFolder;
    private static Function<ClientLogin, Processor> processors;

    private EventLoopGroup group;
    private ClientListener listener;
    private FakeSpectrumServer server;
    private TestClient client;
    private final CompletableFuture<CompletableFuture<Session>> session = new CompletableFuture<>();
    private PrintStream stderr;
    private final ByteArrayOutputStream logged = new ByteArrayOutputStream();

    // Boar starts once in a JVM.
    @BeforeAll
    static void startBoar() throws Exception {
        processors = BoariumBoar.start(dataFolder);
        PacketEvents.getApi().register(RECORDER);
    }

    @BeforeEach
    void setUp() throws Exception {
        this.stderr = System.err;
        System.setErr(new PrintStream(new Tee(this.stderr, this.logged), true, StandardCharsets.UTF_8));
        RECORDER.received.clear();
        RECORDER.sent.clear();
        this.group = new NioEventLoopGroup(2);
        this.server = new FakeSpectrumServer(this.group, CODEC);
    }

    @AfterEach
    void tearDown() {
        System.setErr(this.stderr);
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.server.close();
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void startsWithEveryReferenceFilled() {
        assertTrue(Blocks.AIR.find().isPresent());
        assertTrue(Items.BOW.find().isPresent());
        assertEquals(1.8F, EntityDefinitions.PLAYER.find().orElseThrow().height());
    }

    @Test
    void addsThePlayerAtStartGameAndRemovesItAtDisconnect() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertNotNull(player);
        // Set by Boar's StartGame listener.
        assertEquals(FakeSpectrumServer.RUNTIME_ID, player.runtimeEntityId);
        assertTrue(RECORDER.sent.contains(StartGamePacket.class));
        // Boar's movement checks skip a player in a loading screen.
        assertFalse(player.inLoadingScreen);

        this.client.session.get().disconnect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Boar.getInstance().getPlayerManager().containsKey(session) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(Boar.getInstance().getPlayerManager().containsKey(session));
        assertTrue(player.future.isCancelled());
        this.assertNothingFailed();
    }

    @Test
    void showsTheClientsAndServersPacketsToBoar() throws Exception {
        this.spawn();
        assertInstanceOf(RequestChunkRadiusPacket.class, this.server.await());
        assertFalse(assertInstanceOf(ClientCacheStatusPacket.class, this.server.await()).isSupported());

        this.client.session.get().sendPacket(authInput());
        while (!(this.server.await() instanceof PlayerAuthInputPacket)) {
            // The client's other packets.
        }
        assertTrue(RECORDER.received.contains(PlayerAuthInputPacket.class));

        SetEntityMotionPacket motion = new SetEntityMotionPacket();
        motion.setRuntimeEntityId(FakeSpectrumServer.RUNTIME_ID);
        motion.setMotion(Vector3f.from(0, 0.42, 0));
        this.server.send(motion);
        this.server.send(new Flush());
        this.client.await(SetEntityMotionPacket.class);
        assertTrue(RECORDER.sent.contains(SetEntityMotionPacket.class));
        // Boar's acknowledgment ends the batch.
        BedrockPacket next = this.client.received.poll(10, TimeUnit.SECONDS);
        assertTrue(assertInstanceOf(NetworkStackLatencyPacket.class, next).isFromServer());

        assertTrue(this.client.received.stream().noneMatch(packet -> packet instanceof DisconnectPacket));
        this.assertNothingFailed();
    }

    private Session spawn() throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
        String addr = this.server.address();
        SessionSettings settings = new SessionSettings(null, Duration.ofMinutes(1), SessionSettings.DEFAULT.latencyInterval(), processors);
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), CODEC, pong, false, Duration.ofMinutes(1), login -> this.session.complete(Session.login(login, transport, CODEC, addr, settings)));
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.client.connect(this.group, this.listener.address());
        this.client.loginDone.get(10, TimeUnit.SECONDS);

        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(8);
        this.client.session.get().sendPacket(request);
        assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, this.client.await(PlayStatusPacket.class).getStatus());
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        this.client.session.get().sendPacket(initialized);
        return this.session.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    }

    private static PlayerAuthInputPacket authInput() {
        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        input.setRotation(Vector3f.ZERO);
        input.setPosition(Vector3f.from(0, 64, 0));
        input.setMotion(Vector2f.ZERO);
        input.setInputMode(InputMode.MOUSE);
        input.setPlayMode(ClientPlayMode.NORMAL);
        input.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        input.setDelta(Vector3f.ZERO);
        input.setInteractRotation(Vector2f.ZERO);
        input.setAnalogMoveVector(Vector2f.ZERO);
        input.setVehicleRotation(Vector2f.ZERO);
        input.setCameraOrientation(Vector3f.ZERO);
        input.setRawMoveVector(Vector2f.ZERO);
        input.setTick(1);
        return input;
    }

    // Boar's listeners and boarium log what they throw, and the log goes to standard error.
    private void assertNothingFailed() {
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains(" ERROR ") || log.contains("Exception"), log);
    }

    private static final class Recorder implements PacketListener {
        final BlockingQueue<Class<?>> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Class<?>> sent = new LinkedBlockingQueue<>();

        @Override
        public void onPacketReceived(CloudburstPacketEvent event) {
            this.received.add(event.getPacket().getClass());
        }

        @Override
        public void onPacketSend(CloudburstPacketEvent event) {
            this.sent.add(event.getPacket().getClass());
        }
    }

    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        Tee(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int b) throws java.io.IOException {
            this.first.write(b);
            this.second.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws java.io.IOException {
            this.first.write(b, off, len);
            this.second.write(b, off, len);
        }
    }
}
