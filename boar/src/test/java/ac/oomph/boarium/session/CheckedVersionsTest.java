package ac.oomph.boarium.session;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Vec3;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.boar.blocks.BedrockBlockTable;
import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.protocol.Definitions;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Players on a protocol other than 1.26.30 whom Boar checks: Boar reads their blocks and items by their own version's IDs.
class CheckedVersionsTest {
    // A sub-chunk's local X and Z of the blocks the chunk has at Y 1, over a stone floor at Y 0
    private static final Vector3i FENCE = Vector3i.from(12, 1, 12);
    private static final Vector3i PLANKS = Vector3i.from(12, 1, 2);
    private static final Vector3i POPLAR_FENCE = PLANKS.add(1, 0, 0);
    private static final SimpleItemDefinition POPLAR_PLANKS = new SimpleItemDefinition("minecraft:poplar_planks", 2000, false);
    private static final List<ItemDefinition> ITEMS = List.of(new SimpleItemDefinition("minecraft:stone", 1, false), POPLAR_PLANKS);

    private EventLoopGroup group;
    private ClientListener listener;
    private FakeSpectrumServer server;
    private TestClient client;
    private PrintStream stderr;
    private final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    private int protocol;
    private StartGamePacket startGame;

    // 26.40 and 26.45, whose poplar planks 1.26.30 does not have, and 26.60, which Boar checks only in the tests, whose fences have connection states.
    static List<Named<BedrockCodec>> codecs() {
        return List.of(Named.of("26.40", Bedrock_v2168_hotfix4.CODEC), Named.of("26.45", Bedrock_v2169.CODEC), Named.of("26.60", Bedrock_v2225.CODEC));
    }

    @BeforeEach
    void setUp() {
        this.stderr = System.err;
        System.setErr(new PrintStream(new Tee(this.stderr, this.logged), true, StandardCharsets.UTF_8));
        this.group = new NioEventLoopGroup(2);
    }

    @AfterEach
    void tearDown() {
        TestBoar.check(this.protocol, false);
        System.setErr(this.stderr);
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        if (this.server != null) {
            this.server.close();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void readsWalksAndPlacesWithItsOwnBlocksAndItems(BedrockCodec codec) throws Exception {
        this.protocol = codec.getProtocolVersion();
        if (this.protocol == Bedrock_v2225.CODEC.getProtocolVersion()) {
            TestBoar.check(this.protocol, true);
        }
        BedrockBlockTable table = BedrockBlockTable.forProtocol(this.protocol);
        int stone = table.defaultIndex("minecraft:stone");
        int fence = table.defaultIndex("minecraft:oak_fence");
        int planks = table.defaultIndex("minecraft:poplar_planks");
        int poplarFence = table.defaultIndex("minecraft:poplar_fence");
        this.server = new FakeSpectrumServer(this.group, codec);
        this.server.items = ITEMS;
        Session session = this.spawn(codec);
        StartGamePacket startGame = this.startGame;
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertNotNull(player);
        assertFalse(startGame.isBlockNetworkIdsHashed());

        // The chunk the player stands in, with its runtime IDs, which are the indices of its version's block table.
        int chunkX = startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = startGame.getPlayerPosition().getFloorZ() >> 4;
        Vector3i origin = Vector3i.from(chunkX << 4, 0, chunkZ << 4);
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(subChunk(table.defaultIndex("minecraft:air"), stone, Map.of(FENCE, fence, PLANKS, planks, POPLAR_FENCE, poplarFence)));
        this.server.send(chunk);
        this.server.send(new Flush());
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(origin, 0).isAir());
        assertBlock(session, player, origin, "minecraft:stone", stone);
        assertBlock(session, player, origin.add(FENCE), "minecraft:oak_fence", fence);
        assertBlock(session, player, origin.add(PLANKS), "minecraft:poplar_planks", planks);
        assertBlock(session, player, origin.add(POPLAR_FENCE), "minecraft:poplar_fence", poplarFence);
        // A 26.40 poplar fence has no states, so it joins the planks west of it by its connection rule; a 26.60 one has states, and its default state joins nothing.
        Vector3i poplarFenceAt = origin.add(POPLAR_FENCE);
        List<Box> boxes = onLoop(session, () -> player.compensatedWorld.getBlockState(poplarFenceAt, 0).findCollision(player, poplarFenceAt, null, false));
        float west = (float) boxes.stream().mapToDouble(box -> box.minX).min().orElseThrow() - poplarFenceAt.getX();
        assertEquals(this.protocol < Bedrock_v2225.CODEC.getProtocolVersion() ? 0 : 0.375F, west, 1e-6F, boxes.toString());

        this.readItemsOnTheClient();
        List<ItemData> inventory = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        inventory.set(0, poplarPlanks(planks));
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(inventory);
        this.server.send(content);
        this.server.send(new Flush());
        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.inventoryContainer.get(0).isEmpty());

        List<Violation> violations = new CopyOnWriteArrayList<>();
        ViolationListener violationListener = violation -> {
            if (violation.player() == player) {
                violations.add(violation);
            }
        };
        Boar.getInstance().getViolationRegistry().register(violationListener);
        Vector3f eyes;
        try {
            // Standing on the floor, then walking east as the client does on a block of friction 0.6: its input times 0.98 at speed 0.1 a tick, and velocity times 0.546 after each tick.
            float x = origin.getX() + 3.5F;
            float z = origin.getZ() + 5.5F;
            Vec3 velocity = Vec3.ZERO.clone();
            int ticks = 40;
            for (int tick = 1; tick <= ticks; tick++) {
                boolean walking = tick > 10;
                if (walking) {
                    velocity = velocity.add(MathUtil.getInputVector(new Vec3(0, 0, 0.3F * 0.98F), 0.1F, -90));
                    x += velocity.x;
                    z += velocity.z;
                    velocity = velocity.multiply(0.546F, 1, 0.546F);
                }
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setRotation(Vector3f.from(0, -90, -90));
                input.setPosition(Vector3f.from(x, 1 + 1.62001F, z));
                if (walking) {
                    input.getInputData().add(PlayerAuthInputData.UP);
                    input.setMotion(Vector2f.from(0, 0.3F));
                    input.setRawMoveVector(Vector2f.from(0, 0.3F));
                    input.setAnalogMoveVector(Vector2f.from(0, 0.3F));
                }
                input.setDelta(Vector3f.from(velocity.x, -0.0784F, velocity.z));
                input.setTick(tick);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }
            // The proxy passes an input on once Boar has handled it.
            while (!(this.server.await() instanceof PlayerAuthInputPacket input && input.getTick() == ticks)) {
                // The client's other packets.
            }
            assertTrue(x > origin.getX() + 4.5F, "x " + x);
            assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
            assertTrue(onLoop(session, () -> player.onGround));
            assertEquals(List.of(), violations.stream().map(violation -> violation.check().getClass().getSimpleName() + " " + violation.verbose()).toList());
            eyes = Vector3f.from(x, 1 + 1.62001F, z);
        } finally {
            Boar.getInstance().getViolationRegistry().unregister(violationListener);
        }

        // The top face of the floor two blocks east, clicked with the poplar planks in hotbar slot 0.
        Vector3i clicked = Vector3i.from(eyes.getFloorX() + 2, 0, eyes.getFloorZ());
        InventoryTransactionPacket place = new InventoryTransactionPacket();
        place.setTransactionType(InventoryTransactionType.ITEM_USE);
        place.setActionType(0);
        place.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        place.setBlockPosition(clicked);
        place.setBlockFace(1);
        place.setHotbarSlot(0);
        place.setItemInHand(poplarPlanks(planks));
        place.setPlayerPosition(eyes);
        place.setClickPosition(Vector3f.from(0.5F, 1, 0.5F));
        place.setBlockDefinition(Definitions.ANY_BLOCK.getDefinition(stone));
        place.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        this.client.session.get().sendPacket(place);
        while (!(this.server.await() instanceof InventoryTransactionPacket)) {
            // The client's other packets.
        }
        assertBlock(session, player, clicked.add(0, 1, 0), "minecraft:poplar_planks", planks);
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains(" ERROR ") || log.contains("Exception"), log);
    }

    private static void assertBlock(Session session, BoarPlayer player, Vector3i position, String name, int runtimeId) throws Exception {
        BoarBlockState state = onLoop(session, () -> player.compensatedWorld.getBlockState(position, 0));
        assertEquals(name, state.block().toString(), position.toString());
        assertEquals(runtimeId, onLoop(session, () -> state.definition(player).getRuntimeId()), position.toString());
    }

    // A stack of poplar planks as the server and a real client send it, with the block it places.
    private static ItemData poplarPlanks(int runtimeId) {
        return ItemData.builder().definition(POPLAR_PLANKS).count(64).blockDefinition(Definitions.ANY_BLOCK.getDefinition(runtimeId)).build();
    }

    // A sub-chunk at Y 0 to 15 of air with a floor of stone at Y 0 and these blocks, by local position.
    private static ByteBuf subChunk(int air, int stone, Map<Vector3i, Integer> blocks) {
        List<Integer> palette = new ArrayList<>(List.of(air, stone));
        blocks.values().forEach(palette::add);
        ByteBuf data = Unpooled.buffer();
        // Sub-chunk version 9, one layer, at sub-chunk Y 0
        data.writeByte(9).writeByte(1).writeByte(0);
        // 8 bits per block, so 4 blocks in each word, at index x << 8 | z << 4 | y
        data.writeByte(8 << 1 | 1);
        for (int word = 0; word < 4096 / 4; word++) {
            int bits = 0;
            for (int b = 0; b < 4; b++) {
                int index = word * 4 + b;
                Vector3i position = Vector3i.from(index >> 8, index & 15, index >> 4 & 15);
                int id = position.getY() == 0 ? stone : blocks.getOrDefault(position, air);
                bits |= palette.indexOf(id) << b * 8;
            }
            data.writeIntLE(bits);
        }
        VarInts.writeInt(data, palette.size());
        palette.forEach(id -> VarInts.writeInt(data, id));
        return data;
    }

    // The test client has no item or block definitions of its own, and needs them to read the items the server sends.
    private void readItemsOnTheClient() throws Exception {
        BedrockCodecHelper clientHelper = this.client.session.get().getPeer().getCodecHelper();
        clientHelper.setItemDefinitions(Definitions.items(ITEMS));
        clientHelper.setBlockDefinitions(Definitions.ANY_BLOCK);
    }

    // Answers each NetworkStackLatency the client was sent, as a real client does.
    private void answerLatency() throws Exception {
        for (BedrockPacket packet; (packet = this.client.received.poll()) != null; ) {
            if (packet instanceof NetworkStackLatencyPacket latency && latency.isFromServer()) {
                this.answer(latency);
            }
        }
    }

    // Answers each NetworkStackLatency the client is sent, up to the first after a packet of this type, so Boar applies that packet once the client has it.
    private void answerLatencyAfter(Class<? extends BedrockPacket> type) throws Exception {
        boolean seen = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            BedrockPacket packet = this.client.received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (packet == null) {
                throw new IllegalStateException("no NetworkStackLatency after " + type.getSimpleName() + " within 10 seconds");
            }
            seen |= type.isInstance(packet);
            if (packet instanceof NetworkStackLatencyPacket latency && latency.isFromServer()) {
                this.answer(latency);
                if (seen) {
                    return;
                }
            }
        }
    }

    // A client that is not a PlayStation answers with the timestamp times a million, which Boar's NetworkLatencyPackets divides out.
    private void answer(NetworkStackLatencyPacket latency) throws Exception {
        NetworkStackLatencyPacket answer = new NetworkStackLatencyPacket();
        answer.setTimestamp(latency.getTimestamp() * 1_000_000);
        this.client.session.get().sendPacket(answer);
    }

    // Boar's world is read on the client's event loop, where Boar changes it.
    private static <T> T onLoop(Session session, Callable<T> read) throws Exception {
        return session.client().session().getPeer().getChannel().eventLoop().submit(read).get(10, TimeUnit.SECONDS);
    }

    private static void awaitOnLoop(Session session, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onLoop(session, condition::getAsBoolean) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private Session spawn(BedrockCodec codec) throws Exception {
        CompletableFuture<CompletableFuture<Session>> joined = new CompletableFuture<>();
        Function<ClientLogin, Processor> processors = TestBoar.start();
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(codec.getProtocolVersion()).version(codec.getMinecraftVersion()).gameType("Survival");
        TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
        SessionSettings settings = new SessionSettings(null, Duration.ofMinutes(1), SessionSettings.DEFAULT.latencyInterval(), processors);
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1), login -> joined.complete(Session.login(login, transport, this.server.address(), settings)));
        this.client = new TestClient("Tester", codec, codec.getProtocolVersion());
        this.client.connect(this.group, this.listener.address());
        this.client.loginDone.get(10, TimeUnit.SECONDS);

        StartGamePacket startGame = this.client.await(StartGamePacket.class);
        this.startGame = startGame;
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(8);
        this.client.session.get().sendPacket(request);
        assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, this.client.await(PlayStatusPacket.class).getStatus());
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        this.client.session.get().sendPacket(initialized);
        return joined.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
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
