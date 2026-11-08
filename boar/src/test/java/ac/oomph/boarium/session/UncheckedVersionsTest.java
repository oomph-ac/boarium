package ac.oomph.boarium.session;

import ac.boar.anticheat.Boar;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Players on a protocol that Boar does not check play through the proxy with Boar running, but unchecked.
class UncheckedVersionsTest {
    private EventLoopGroup group;
    private ClientListener listener;
    private PrintStream stderr;
    private final ByteArrayOutputStream logged = new ByteArrayOutputStream();

    static List<Named<BedrockCodec>> codecs() {
        return List.of(Named.of("26.0", Bedrock_v924.CODEC), Named.of("26.10", Bedrock_v944.CODEC));
    }

    @BeforeEach
    void setUp() {
        this.stderr = System.err;
        System.setErr(new PrintStream(new Tee(this.stderr, this.logged), true, StandardCharsets.UTF_8));
        this.group = new NioEventLoopGroup(2);
        TestBoar.forgetWarnings();
    }

    @AfterEach
    void tearDown() {
        System.setErr(this.stderr);
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void playsWithoutBoarOnItsOwnProtocol(BedrockCodec codec) throws Exception {
        Function<ClientLogin, Processor> processors = TestBoar.start();
        FakeSpectrumServer server = new FakeSpectrumServer(this.group, codec);
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, codec);
        try {
            CompletableFuture<CompletableFuture<Session>> joined = new CompletableFuture<>();
            BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(codec.getProtocolVersion()).version(codec.getMinecraftVersion()).gameType("Survival");
            TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
            SessionSettings settings = new SessionSettings(null, Duration.ofMinutes(1), SessionSettings.DEFAULT.latencyInterval(), processors);
            this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1), login -> joined.complete(Session.login(login, transport, server.address(), settings)));
            TestClient client = new TestClient("Tester", codec, codec.getProtocolVersion());
            client.connect(this.group, this.listener.rakNetAddress());
            client.loginDone.get(10, TimeUnit.SECONDS);

            StartGamePacket startGame = client.await(StartGamePacket.class);
            RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
            request.setRadius(8);
            client.session.get().sendPacket(request);
            assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, client.await(PlayStatusPacket.class).getStatus());
            SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
            initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
            client.session.get().sendPacket(initialized);
            Session session = joined.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);

            assertEquals(codec.getProtocolVersion(), server.request.get(10, TimeUnit.SECONDS).protocolId());
            assertSame(codec, session.codec());
            assertNull(Boar.getInstance().getPlayerManager().get(session));
            assertInstanceOf(RequestChunkRadiusPacket.class, server.await());
            // The test client supports the blob cache, and without Boar nothing turns it off.
            assertTrue(assertInstanceOf(ClientCacheStatusPacket.class, server.await()).isSupported());

            PlayerAuthInputPacket input = authInput();
            byte[] sent = encode(codec, input);
            client.session.get().sendPacket(input);
            while (FakeSpectrumServer.poll(server.batches).stream().noneMatch(packet -> Arrays.equals(sent, packet))) {
                // The client's other batches.
            }
            // One packet the server asks the proxy to decode, which goes to the client encoded again, and one that passes as its bytes.
            SetEntityMotionPacket motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(FakeSpectrumServer.RUNTIME_ID);
            motion.setMotion(Vector3f.from(0, 0.42, 0));
            server.send(motion);
            server.send("SetTime", false);
            server.send(new Flush());
            assertEquals(Vector3f.from(0, 0.42, 0), client.await(SetEntityMotionPacket.class).getMotion());
            client.await(SetTimePacket.class);

            session.transfer(next.address()).get(10, TimeUnit.SECONDS);
            assertEquals(codec.getProtocolVersion(), next.request.get(10, TimeUnit.SECONDS).protocolId());
            assertSame(codec, client.session.get().getCodec());
            client.session.get().sendPacket(authInput());
            while (!(next.await() instanceof PlayerAuthInputPacket)) {
                // The client's other packets.
            }

            assertTrue(client.received.stream().noneMatch(packet -> packet instanceof DisconnectPacket));
            String log = this.logged.toString(StandardCharsets.UTF_8);
            assertEquals(1, log.split("Boar does not check players on protocol " + codec.getProtocolVersion() + ",", -1).length - 1, log);
            assertFalse(log.contains(" ERROR ") || log.contains("Exception"), log);
        } finally {
            server.close();
            next.close();
        }
    }

    // The packet's header and body, as the client writes them.
    private static byte[] encode(BedrockCodec codec, BedrockPacket packet) {
        ByteBuf out = Unpooled.buffer();
        try {
            VarInts.writeUnsignedInt(out, codec.getPacketDefinition(packet.getClass()).getId());
            codec.tryEncode(codec.createHelper(), out, packet);
            return ByteBufUtil.getBytes(out);
        } finally {
            out.release();
        }
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

    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        Tee(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            this.first.write(b);
            this.second.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            this.first.write(b, off, len);
            this.second.write(b, off, len);
        }
    }
}
