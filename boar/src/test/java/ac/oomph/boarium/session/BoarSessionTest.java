package ac.oomph.boarium.session;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.compensated.CompensatedInventory;
import ac.boar.anticheat.compensated.cache.entity.EntityCache;
import ac.boar.anticheat.data.ItemUseTracker;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.effect.Effect;
import ac.boar.anticheat.data.enchantment.Enchantment;
import ac.boar.anticheat.data.vanilla.StatusEffect;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.VehicleType;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.anticheat.util.block.BlockUtil;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Vec3;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import ac.boar.mappings.block.Blocks;
import ac.boar.mappings.block.Properties;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinitions;
import ac.boar.mappings.entity.EntityTypes;
import ac.boar.mappings.item.Items;
import ac.boar.protocol.PacketEvents;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.boar.blocks.BedrockBlockTable;
import ac.oomph.boarium.boar.blocks.BedrockPalette;
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
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainer;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlot;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// In the session package to reach the fake server's own sends.
class BoarSessionTest {
    private static final BedrockCodec CODEC = Boarium.CODEC;
    // Every packet Boar's listeners see, after them.
    private static final Recorder RECORDER = new Recorder();
    // The 1.26.30 runtime IDs of air and stone when StartGame does not ask for hashed IDs
    private static final int AIR_RUNTIME_ID = 13094;
    private static final int STONE_RUNTIME_ID = 2706;
    private static final int FENCE_Z = 8;
    private static final int WALL_X = 5;
    // The client skips a block without this compound. Geyser 2.12 writes a block_id in it.
    private static final NbtMap VANILLA_BLOCK_DATA = NbtMap.builder().putInt("block_id", 0).build();
    private static final long BOAT_RUNTIME_ID = 20;
    private static final long BOAT_UNIQUE_ID = -20;
    private static final long HORSE_RUNTIME_ID = 21;
    private static final long HORSE_UNIQUE_ID = -21;
    // The fake server's items, with their 1.26.30 runtime IDs
    private static final SimpleItemDefinition STONE = new SimpleItemDefinition("minecraft:stone", 1, false);
    private static final SimpleItemDefinition ELYTRA = new SimpleItemDefinition("minecraft:elytra", 603, false);
    private static final SimpleItemDefinition LEATHER_BOOTS = new SimpleItemDefinition("minecraft:leather_boots", 370, false);
    private static final List<ItemDefinition> ITEMS = List.of(
            STONE, new SimpleItemDefinition("minecraft:shield", 387, false), new SimpleItemDefinition("minecraft:bow", 331, false),
            ELYTRA, LEATHER_BOOTS, new SimpleItemDefinition("minecraft:bucket", 392, false), new SimpleItemDefinition("minecraft:water_bucket", 394, false));

    private static Function<ClientLogin, Processor> processors;

    private EventLoopGroup group;
    private ClientListener listener;
    private FakeSpectrumServer server;
    private TestClient client;
    private final CompletableFuture<CompletableFuture<Session>> session = new CompletableFuture<>();
    private PrintStream stderr;
    private final ByteArrayOutputStream logged = new ByteArrayOutputStream();
    private StartGamePacket startGame;

    @BeforeAll
    static void startBoar() throws Exception {
        processors = TestBoar.start();
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
        this.server.items = ITEMS;
    }

    @AfterEach
    void tearDown() {
        TestBoar.mitigations(false);
        System.setErr(this.stderr);
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.server.close();
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void startsWithTheReferencesFilled() {
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
        // TestBoar starts Boar with mitigations off.
        assertTrue(player.disableMitigations());

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
    void showsBoarTheWorldATransferLeavesBehind() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            Session session = this.spawn();
            session.transfer(next.address()).get(10, TimeUnit.SECONDS);

            assertTrue(RECORDER.sent.contains(ChangeDimensionPacket.class));
            assertTrue(RECORDER.sent.contains(LevelChunkPacket.class));
            this.assertNothingFailed();
        } finally {
            next.close();
        }
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

    @Test
    void readsTheBlocksOfAChunk() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(this.startGame.isBlockNetworkIdsHashed());

        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(stoneFloor(AIR_RUNTIME_ID, STONE_RUNTIME_ID));
        this.server.send(chunk);
        this.server.send(new Flush());

        int x = (chunkX << 4) + 3;
        int z = (chunkZ << 4) + 5;
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(x, 0, z, 0).isAir());
        BoarBlockState floor = onLoop(session, () -> player.compensatedWorld.getBlockState(x, 0, z, 0));
        assertEquals("minecraft:stone", floor.block().toString());
        assertTrue(floor.isFaceSturdy(player));
        assertEquals(STONE_RUNTIME_ID, floor.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(x, 1, z, 0).isAir()));
        this.assertNothingFailed();
    }

    @Test
    void standsNextToAFenceLine() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(fenceLine());
        this.server.send(chunk);
        this.server.send(new Flush());

        Vector3i fence = Vector3i.from((chunkX << 4) + 5, 1, (chunkZ << 4) + FENCE_Z);
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(fence, 0).block().toString().equals("minecraft:oak_fence"));

        // Standing on the floor, against the fence's south side, as the client says with its eyes 1.62 above its feet.
        Vector3f eyes = Vector3f.from(fence.getX() + 0.5F, fence.getY() + 1.62F, fence.getZ() + 0.925F);
        for (int tick = 1; tick <= 20; tick++) {
            this.answerLatency();
            PlayerAuthInputPacket input = authInput();
            input.setPosition(eyes);
            input.setTick(tick);
            this.client.session.get().sendPacket(input);
            Thread.sleep(50);
        }
        awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= 20);
        // Boar ran its movement prediction there, not only its checks for a teleport or a loading screen.
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));

        // A fence in the line joins the fences east and west of it: one bar from west to east, as the client's FenceBlock::addCollisionShapes builds it.
        List<Box> expected = List.of(new Box(0, 0, 0.375F, 1, 1.5F, 0.625F).offset(fence.getX(), fence.getY(), fence.getZ()));
        List<Box> boxes = onLoop(session, () -> player.compensatedWorld.getBlockState(fence, 0).findCollision(player, fence, null, false));
        assertArrayEquals(corners(expected), corners(boxes));
        this.assertNothingFailed();
    }

    // cubecraft:pipe sorts before stone and air by name hash, so its 2 states move both by 2.
    @Test
    void readsTheBlocksOfAChunkWhenTheServerAddsCustomBlocks() throws Exception {
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(pipe())));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        int air = AIR_RUNTIME_ID + 2;
        int stone = STONE_RUNTIME_ID + 2;

        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(stoneFloor(air, stone));
        this.server.send(chunk);
        this.server.send(new Flush());

        int x = (chunkX << 4) + 3;
        int z = (chunkZ << 4) + 5;
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(x, 0, z, 0).isAir());
        BoarBlockState floor = onLoop(session, () -> player.compensatedWorld.getBlockState(x, 0, z, 0));
        assertEquals("minecraft:stone", floor.block().toString());
        assertEquals(stone, floor.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(x, 1, z, 0).isAir()));
        assertEquals(air, player.mappingInfo.airId());
        // Without custom blocks, stone has another ID.
        assertNotEquals(floor.intermediaryId(), player.mappingInfo.toIntermediary().applyAsInt(STONE_RUNTIME_ID));

        // The pipe's states are full cubes with IDs of their own, which Boar sends back as the pipe.
        int pipes = 0;
        for (int runtimeId = 0; runtimeId < AIR_RUNTIME_ID + 2; runtimeId++) {
            int id = player.mappingInfo.toIntermediary().applyAsInt(runtimeId);
            BoarBlockState state = BoarBlockState.create(id, Vector3i.ZERO, 0);
            if (id >= BedrockBlockTable.get().paletteSize()) {
                assertFalse(state.isAir());
                assertArrayEquals(corners(List.of(new Box(0, 0, 0, 1, 1, 1))), corners(state.getCollisionBoxes()));
                assertEquals(runtimeId, state.definition(player).getRuntimeId());
                pipes++;
            }
        }
        assertEquals(2, pipes);
        this.assertNothingFailed();
    }

    // Flowing lava and the top half of a double slab are Bedrock states that Geyser's Java mapping folds into others.
    @Test
    void readsStatesThatOnlyBedrockHas() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        BedrockBlockTable table = BedrockBlockTable.get();
        int lava = state("minecraft:flowing_lava", "liquid_depth", 3);
        int slab = state("minecraft:oak_double_slab", "minecraft:vertical_half", "top");

        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        int[] blocks = new int[4096];
        Arrays.fill(blocks, AIR_RUNTIME_ID);
        blocks[3 << 8 | 5 << 4 | 1] = lava;
        blocks[4 << 8 | 5 << 4 | 1] = slab;
        this.sendChunk(chunkX, chunkZ, subChunk(0, blocks));

        Vector3i lavaPos = Vector3i.from((chunkX << 4) + 3, 1, (chunkZ << 4) + 5);
        Vector3i slabPos = lavaPos.add(1, 0, 0);
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(slabPos, 0).isAir());
        BoarBlockState lavaState = onLoop(session, () -> player.compensatedWorld.getBlockState(lavaPos, 0));
        assertTrue(lavaState.is(Blocks.LAVA));
        assertEquals(3, lavaState.get(Properties.LEVEL));
        assertEquals(lava, lavaState.intermediaryId());
        assertEquals(lava, lavaState.definition(player).getRuntimeId());

        BoarBlockState slabState = onLoop(session, () -> player.compensatedWorld.getBlockState(slabPos, 0));
        assertEquals("minecraft:oak_slab", slabState.block().toString());
        assertEquals("double", slabState.get(Properties.SLAB_TYPE));
        assertEquals("minecraft:oak_double_slab", table.name(slabState.intermediaryId()));
        assertEquals(slab, slabState.definition(player).getRuntimeId());
        assertArrayEquals(corners(List.of(new Box(0, 0, 0, 1, 1, 1))), corners(slabState.getCollisionBoxes()));
        this.assertNothingFailed();
    }

    // Walks east on top of a line of fences, which holds the player up only where the fences join each other.
    @Test
    void walksAlongTheTopOfAFenceLine() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        this.sendChunk(chunkX, chunkZ, fenceLine());
        Vector3i fence = Vector3i.from((chunkX << 4) + 2, 1, (chunkZ << 4) + FENCE_Z);
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(fence, 0).block().toString().equals("minecraft:oak_fence"));

        List<Violation> violations = new CopyOnWriteArrayList<>();
        ViolationListener listener = violation -> {
            if (violation.player() == player) {
                violations.add(violation);
            }
        };
        Boar.getInstance().getViolationRegistry().register(listener);
        try {
            // Standing still first, then walking east slowly, as the client moves on a block of friction 0.6: its input times 0.98 at speed 0.1 a tick, and velocity times 0.546 after each tick.
            // Each step is shorter than the 0.15 between two places where the posts alone would hold the player, so it stops between posts.
            // The client also sends its velocity after the tick, with the gravity that the block under it stops in the next tick.
            float feetY = fence.getY() + 1.5F;
            float x = fence.getX() + 0.5F;
            float z = fence.getZ() + 0.5F;
            Vec3 velocity = Vec3.ZERO.clone();
            for (int tick = 1; tick <= 70; tick++) {
                boolean walking = tick > 10;
                if (walking) {
                    Vec3 input = MathUtil.getInputVector(new Vec3(0, 0, 0.3F * 0.98F), 0.1F, -90);
                    velocity = velocity.add(input);
                    x += velocity.x;
                    z += velocity.z;
                    velocity = velocity.multiply(0.546F, 1, 0.546F);
                }
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setRotation(Vector3f.from(0, -90, -90));
                // A real client's eyes are 1.62001 above its feet.
                input.setPosition(Vector3f.from(x, feetY + 1.62001F, z));
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
            awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= 70);
            // The player crossed several fences, and Boar predicted every step there.
            assertTrue(x > fence.getX() + 3, "x " + x);
            assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
            assertTrue(onLoop(session, () -> player.onGround));
            assertEquals(List.of(), violations.stream().map(violation -> violation.check().getClass().getSimpleName() + " " + violation.verbose()).toList());
        } finally {
            Boar.getInstance().getViolationRegistry().unregister(listener);
        }
        this.assertNothingFailed();
    }

    // Boar resends layer 1 when it undoes a block, and a Bedrock stair holds its water there, which the resend must keep.
    @Test
    void keepsTheWaterOfAWaterloggedStairBoarResends() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));
        int stairs = BedrockBlockTable.get().defaultIndex("minecraft:oak_stairs");
        int water = player.mappingInfo.waterId();

        // The block undoOutOfReach clicks
        Vector3i clicked = this.startGame.getPlayerPosition().toInt().add(30, -2, 0);
        int[] layer0 = new int[4096];
        int[] layer1 = new int[4096];
        Arrays.fill(layer0, AIR_RUNTIME_ID);
        Arrays.fill(layer1, AIR_RUNTIME_ID);
        int index = (clicked.getX() & 15) << 8 | (clicked.getZ() & 15) << 4 | clicked.getY() & 15;
        layer0[index] = stairs;
        layer1[index] = water;
        this.sendChunk(clicked.getX() >> 4, clicked.getZ() >> 4, subChunk(clicked.getY() >> 4, layer0, layer1));
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(clicked, 1).is(Blocks.WATER));
        assertFalse(onLoop(session, () -> player.compensatedWorld.getBlockState(clicked, 0).isWaterlogged()));

        this.client.received.clear();
        this.undoOutOfReach(session, 0, stones(64, 1));
        List<UpdateBlockPacket> resent = new ArrayList<>();
        while (resent.size() < 4) {
            if (this.client.received.poll(10, TimeUnit.SECONDS) instanceof UpdateBlockPacket update) {
                resent.add(update);
            }
        }
        UpdateBlockPacket stairLayer0 = resent.stream().filter(update -> update.getBlockPosition().equals(clicked) && update.getDataLayer() == 0).findFirst().orElseThrow();
        UpdateBlockPacket stairLayer1 = resent.stream().filter(update -> update.getBlockPosition().equals(clicked) && update.getDataLayer() == 1).findFirst().orElseThrow();
        assertEquals(stairs, stairLayer0.getDefinition().getRuntimeId());
        assertEquals(water, stairLayer1.getDefinition().getRuntimeId());
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(clicked, 1).is(Blocks.WATER)));
        this.assertNothingFailed();
    }

    @Test
    void givesThePlayerAnEffectTheServerSends() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        MobEffectPacket effect = new MobEffectPacket();
        effect.setRuntimeEntityId(FakeSpectrumServer.RUNTIME_ID);
        effect.setEvent(MobEffectPacket.Event.ADD);
        // Jump boost II
        effect.setEffectId(8);
        effect.setAmplifier(1);
        effect.setDuration(200);
        this.server.send(effect);
        this.server.send(new Flush());

        this.answerLatencyAfter(MobEffectPacket.class);
        awaitOnLoop(session, () -> player.hasEffect(Effect.JUMP_BOOST));
        StatusEffect jumpBoost = onLoop(session, () -> player.getEffect(Effect.JUMP_BOOST));
        assertNotNull(jumpBoost);
        assertEquals(1, jumpBoost.getAmplifier());
        this.assertNothingFailed();
    }

    @Test
    void readsTheEnchantmentsOfTheBootsTheServerSends() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.readItemsOnTheClient();
        NbtMap depthStrider = NbtMap.builder().putShort("id", (short) 7).putShort("lvl", (short) 3).build();
        ItemData boots = ItemData.builder()
                .definition(LEATHER_BOOTS)
                .count(1)
                .tag(NbtMap.builder().putList("ench", NbtType.COMPOUND, depthStrider).build())
                .build();
        InventoryContentPacket armor = new InventoryContentPacket();
        armor.setContainerId(ContainerId.ARMOR);
        armor.setContents(List.of(ItemData.AIR, ItemData.AIR, ItemData.AIR, boots));
        this.server.send(armor);
        this.server.send(new Flush());

        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.armorContainer.get(3).isEmpty());
        ItemData read = onLoop(session, () -> player.compensatedInventory.armorContainer.get(3).getData());
        assertEquals(Map.of(Enchantment.DEPTH_STRIDER, 3), CompensatedInventory.getEnchantments(read));
        this.assertNothingFailed();
    }

    @Test
    void glidesWithTheElytraTheServerGives() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.readItemsOnTheClient();
        InventoryContentPacket armor = new InventoryContentPacket();
        armor.setContainerId(ContainerId.ARMOR);
        armor.setContents(List.of(ItemData.AIR, ItemData.builder().definition(ELYTRA).count(1).build(), ItemData.AIR, ItemData.AIR));
        this.server.send(armor);
        this.server.send(new Flush());
        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.armorContainer.get(1).isEmpty());

        // A client in the air starts to glide when its player jumps again with an elytra on.
        PlayerAuthInputPacket input = authInput();
        input.getInputData().add(PlayerAuthInputData.START_GLIDING);
        this.client.session.get().sendPacket(input);
        awaitOnLoop(session, () -> RECORDER.received.contains(PlayerAuthInputPacket.class));
        assertTrue(onLoop(session, () -> player.getFlagTracker().has(EntityFlag.GLIDING)));
        this.assertNothingFailed();
    }

    @Test
    void placesTheStoneBlockThePlayerHolds() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.readItemsOnTheClient();
        List<ItemData> inventory = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        inventory.set(0, stones());
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(inventory);
        this.server.send(content);

        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(stoneFloor(AIR_RUNTIME_ID, STONE_RUNTIME_ID));
        this.server.send(chunk);
        this.server.send(new Flush());
        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.inventoryContainer.get(0).isEmpty());

        // Standing on the floor, so the block the player clicks is within its reach.
        Vector3i feet = Vector3i.from((chunkX << 4) + 3, 1, (chunkZ << 4) + 5);
        for (int tick = 1; tick <= 5; tick++) {
            this.answerLatency();
            PlayerAuthInputPacket input = authInput();
            input.setPosition(Vector3f.from(feet.getX() + 0.5F, feet.getY() + 1.62F, feet.getZ() + 0.5F));
            input.setTick(tick);
            this.client.session.get().sendPacket(input);
            Thread.sleep(50);
        }
        awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= 5);

        // The top face of the floor two blocks east, clicked with the stone in hotbar slot 0.
        Vector3i clicked = feet.add(2, -1, 0);
        InventoryTransactionPacket place = new InventoryTransactionPacket();
        place.setTransactionType(InventoryTransactionType.ITEM_USE);
        place.setActionType(0);
        place.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        place.setBlockPosition(clicked);
        place.setBlockFace(1);
        place.setHotbarSlot(0);
        place.setItemInHand(stones());
        place.setPlayerPosition(Vector3f.from(feet.getX() + 0.5F, feet.getY() + 1.62F, feet.getZ() + 0.5F));
        place.setClickPosition(Vector3f.from(0.5F, 1, 0.5F));
        place.setBlockDefinition(Definitions.ANY_BLOCK.getDefinition(STONE_RUNTIME_ID));
        place.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        this.client.session.get().sendPacket(place);

        Vector3i placed = clicked.add(0, 1, 0);
        awaitOnLoop(session, () -> RECORDER.received.contains(InventoryTransactionPacket.class));
        assertEquals("minecraft:stone", onLoop(session, () -> player.compensatedWorld.getBlockState(placed, 0).block().toString()));
        this.assertNothingFailed();
    }

    @Test
    void tracksAnEntityTheServerAddsUntilItIsRemoved() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.server.send(addEntity(BOAT_RUNTIME_ID, BOAT_UNIQUE_ID, "minecraft:boat"));
        this.server.send(addEntity(HORSE_RUNTIME_ID, HORSE_UNIQUE_ID, "minecraft:horse"));
        this.server.send(new Flush());

        this.answerLatencyAfter(AddEntityPacket.class);
        awaitOnLoop(session, () -> player.compensatedWorld.getEntity(BOAT_RUNTIME_ID) != null && player.compensatedWorld.getEntity(HORSE_RUNTIME_ID) != null);
        EntityCache boat = onLoop(session, () -> player.compensatedWorld.getEntity(BOAT_RUNTIME_ID));
        assertEquals("minecraft:boat", boat.getDefinition().identifier());
        assertEquals(VehicleType.BOAT, boat.getVehicleType());
        // Without size metadata, Boar gives a boat Geyser's size itself.
        assertEquals(1.6F, boat.getDimensions().width());
        assertEquals(0.575F, boat.getDimensions().height());
        EntityCache horse = onLoop(session, () -> player.compensatedWorld.getEntity(HORSE_RUNTIME_ID));
        assertEquals(VehicleType.HORSE, horse.getVehicleType());
        assertEquals(1.3965F, horse.getDimensions().width());
        assertEquals(1.6F, horse.getDimensions().height());

        RemoveEntityPacket remove = new RemoveEntityPacket();
        remove.setUniqueEntityId(BOAT_UNIQUE_ID);
        this.server.send(remove);
        this.server.send(new Flush());
        this.answerLatencyAfter(RemoveEntityPacket.class);
        awaitOnLoop(session, () -> player.compensatedWorld.getTrackedEntity(BOAT_RUNTIME_ID) == null);
        assertNull(onLoop(session, () -> player.compensatedWorld.getTrackedEntity(BOAT_RUNTIME_ID)));
        assertNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(BOAT_RUNTIME_ID)));
        assertNotNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(HORSE_RUNTIME_ID)));
        this.assertNothingFailed();
    }

    @Test
    void ridesTheBoatThePlayerIsLinkedTo() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.server.send(addEntity(BOAT_RUNTIME_ID, BOAT_UNIQUE_ID, "minecraft:boat"));
        this.server.send(link(BOAT_UNIQUE_ID, EntityLinkData.Type.RIDER));
        this.server.send(new Flush());
        this.client.await(SetEntityLinkPacket.class);
        Entity vehicle = onLoop(session, () -> player.getEntity().vehicle());
        assertNotNull(vehicle);
        assertEquals("minecraft:boat", vehicle.definition().identifier());

        this.server.send(link(BOAT_UNIQUE_ID, EntityLinkData.Type.REMOVE));
        this.server.send(new Flush());
        this.client.await(SetEntityLinkPacket.class);
        assertNull(onLoop(session, () -> player.getEntity().vehicle()));

        // A link to another entity is not the player's ride.
        long otherBoatUniqueId = -40;
        long otherRiderUniqueId = -41;
        AddEntityPacket boatWithAnotherRider = addEntity(40, otherBoatUniqueId, "minecraft:boat");
        boatWithAnotherRider.getEntityLinks().add(new EntityLinkData(otherBoatUniqueId, otherRiderUniqueId, EntityLinkData.Type.RIDER, false, false));
        this.server.send(boatWithAnotherRider);
        this.server.send(new Flush());
        this.client.await(AddEntityPacket.class);
        assertNull(onLoop(session, () -> player.getEntity().vehicle()));
        this.assertNothingFailed();
    }

    @Test
    void endsTheRideWhenTheServerRemovesTheVehicle() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        // The rider can come with the vehicle's spawn.
        AddEntityPacket boat = addEntity(BOAT_RUNTIME_ID, BOAT_UNIQUE_ID, "minecraft:boat");
        boat.getEntityLinks().add(new EntityLinkData(BOAT_UNIQUE_ID, FakeSpectrumServer.UNIQUE_ID, EntityLinkData.Type.RIDER, false, false));
        this.server.send(boat);
        this.server.send(new Flush());
        this.client.await(AddEntityPacket.class);
        assertEquals("minecraft:boat", onLoop(session, () -> player.getEntity().vehicle().definition().identifier()));

        RemoveEntityPacket remove = new RemoveEntityPacket();
        remove.setUniqueEntityId(BOAT_UNIQUE_ID);
        this.server.send(remove);
        this.server.send(new Flush());
        this.client.await(RemoveEntityPacket.class);
        assertNull(onLoop(session, () -> player.getEntity().vehicle()));
        this.assertNothingFailed();
    }

    // The old server's link names an entity it never added, so the proxy has nothing of it to remove at the transfer.
    @Test
    void forgetsTheRideOfTheLastServerAtATransfer() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            Session session = this.spawn();
            BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
            this.server.send(link(BOAT_UNIQUE_ID, EntityLinkData.Type.RIDER));
            this.server.send(new Flush());
            this.client.await(SetEntityLinkPacket.class);

            session.transfer(next.address()).get(10, TimeUnit.SECONDS);
            next.send(addEntity(BOAT_RUNTIME_ID, BOAT_UNIQUE_ID, "minecraft:boat"));
            next.send(new Flush());
            this.client.await(AddEntityPacket.class);
            assertNotNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(BOAT_RUNTIME_ID)));
            assertNull(onLoop(session, () -> player.getEntity().vehicle()));
            this.assertNothingFailed();
        } finally {
            next.close();
        }
    }

    @Test
    void leavesAStaticSeatWithoutADefinition() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        AddEntityPacket seat = addEntity(50, -50, TestBoar.STATIC_SEAT);
        seat.getEntityLinks().add(new EntityLinkData(-50, FakeSpectrumServer.UNIQUE_ID, EntityLinkData.Type.RIDER, false, false));
        this.server.send(seat);
        this.server.send(new Flush());

        this.answerLatencyAfter(AddEntityPacket.class);
        awaitOnLoop(session, () -> player.staticSeat != null);
        assertEquals(-50, onLoop(session, () -> player.staticSeat.actor.uniqueId));
        assertNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(50)));
        assertNull(onLoop(session, () -> player.compensatedWorld.getTrackedEntity(50)));
        this.assertNothingFailed();
    }

    @Test
    void forgetsTheLastServersEntitiesAtATransfer() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        try {
            Session session = this.spawn();
            BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
            this.server.send(addEntity(BOAT_RUNTIME_ID, BOAT_UNIQUE_ID, "minecraft:boat"));
            this.server.send(link(BOAT_UNIQUE_ID, EntityLinkData.Type.RIDER));
            this.server.send(new Flush());
            this.client.await(SetEntityLinkPacket.class);
            assertNotNull(onLoop(session, () -> player.getEntity().vehicle()));

            session.transfer(next.address()).get(10, TimeUnit.SECONDS);
            assertNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(BOAT_RUNTIME_ID)));
            assertNull(onLoop(session, () -> player.getEntity().vehicle()));
            this.assertNothingFailed();
        } finally {
            next.close();
        }
    }

    @Test
    void tracksAnotherPlayerAsAPlayer() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        AddPlayerPacket add = new AddPlayerPacket();
        add.setUuid(UUID.randomUUID());
        add.setUsername("Other");
        add.setPlatformChatId("");
        add.setDeviceId("");
        add.setGameType(GameType.SURVIVAL);
        add.setUniqueEntityId(-30);
        add.setRuntimeEntityId(30);
        add.setPosition(this.startGame.getPlayerPosition());
        add.setMotion(Vector3f.ZERO);
        add.setRotation(Vector3f.ZERO);
        add.setHand(ItemData.AIR);
        this.server.send(add);
        this.server.send(new Flush());

        this.answerLatencyAfter(AddPlayerPacket.class);
        awaitOnLoop(session, () -> player.compensatedWorld.getEntity(30) != null);
        EntityCache other = onLoop(session, () -> player.compensatedWorld.getEntity(30));
        assertTrue(other.getType().is(EntityTypes.PLAYER));
        assertEquals(1.62001F, other.getYOffset());
        assertEquals(0.6F, other.getDimensions().width());
        assertEquals(1.8F, other.getDimensions().height());
        this.assertNothingFailed();
    }

    @Test
    void tracksNoMoreThanItsLimitOfEntities() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        // EntityTracker.MAX_ENTITIES
        int limit = 8192;
        for (int i = 0; i <= limit; i++) {
            this.server.send(addEntity(100 + i, -100 - i, "minecraft:pig"));
        }
        this.server.send(new Flush());

        String error = "has more than " + limit + " entities";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!this.logged.toString(StandardCharsets.UTF_8).contains(error) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains(" ERROR ") && log.contains(error), log);
        assertNotNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(100 + limit - 1)));
        assertNull(onLoop(session, () -> player.getEntityAccessor().definitionByRuntimeId(100 + limit)));
        assertFalse(log.contains("Exception"), log);
    }

    @Test
    void followsTheHotbarSlotTheClientHolds() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.client.session.get().sendPacket(hold(3));
        awaitOnLoop(session, () -> RECORDER.received.contains(MobEquipmentPacket.class));
        assertEquals(3, onLoop(session, () -> player.getInventoryAccessor().heldItemSlot()));

        this.client.session.get().sendPacket(hold(5));
        awaitOnLoop(session, () -> player.getInventoryAccessor().heldItemSlot() == 5);
        assertEquals(5, onLoop(session, () -> player.getInventoryAccessor().heldItemSlot()));
        this.assertNothingFailed();
    }

    @Test
    void sendsTheClientTheItemTheServerLastSentForASlot() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.holdStones(session, player, 2);

        onLoop(session, () -> {
            player.getInventoryAccessor().updateSlot(player.getInventoryAccessor().heldItemSlot());
            return null;
        });
        InventorySlotPacket slot = this.client.await(InventorySlotPacket.class);
        assertEquals(ContainerId.INVENTORY, slot.getContainerId());
        assertEquals(2, slot.getSlot());
        assertTrue(stones().equals(slot.getItem(), true, true, true), slot.getItem().toString());
        assertNull(this.server.received.poll(500, TimeUnit.MILLISECONDS));
        // Boar's listeners never saw it.
        assertFalse(RECORDER.sent.contains(InventorySlotPacket.class));
        this.assertNothingFailed();
    }

    @Test
    void releasesNoItemWhileMitigationsAreOff() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertTrue(player.disableMitigations());
        this.holdStones(session, player, 2);

        List<BedrockPacket> toServer = this.releaseFromBoar(session, player);
        assertTrue(toServer.stream().noneMatch(InventoryTransactionPacket.class::isInstance), toServer.toString());
        assertNull(this.server.received.poll(500, TimeUnit.MILLISECONDS));
        this.assertNothingFailed();
    }

    @Test
    void releasesTheHeldItemOnTheServerWhileMitigationsAreOn() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        onLoop(session, () -> {
            player.setDisableMitigations(false);
            return null;
        });
        this.holdStones(session, player, 2);

        List<BedrockPacket> toServer = this.releaseFromBoar(session, player);
        InventoryTransactionPacket release = toServer.stream().filter(InventoryTransactionPacket.class::isInstance).map(InventoryTransactionPacket.class::cast).findFirst().orElseThrow(() -> new AssertionError(toServer));
        assertEquals(InventoryTransactionType.ITEM_RELEASE, release.getTransactionType());
        assertEquals(0, release.getActionType());
        assertEquals(0, release.getLegacyRequestId());
        assertTrue(release.getActions().isEmpty());
        assertEquals(2, release.getHotbarSlot());
        assertTrue(stones().equals(release.getItemInHand(), true, true, true), release.getItemInHand().toString());
        assertEquals(this.startGame.getPlayerPosition(), release.getHeadPosition());
        assertEquals(1, toServer.stream().filter(InventoryTransactionPacket.class::isInstance).count());
        // Boar's own NetworkStackLatency packets are all the client gets.
        List<BedrockPacket> toClient = new ArrayList<>();
        for (BedrockPacket packet; (packet = this.client.received.poll(500, TimeUnit.MILLISECONDS)) != null; ) {
            if (!(packet instanceof NetworkStackLatencyPacket)) {
                toClient.add(packet);
            }
        }
        assertTrue(toClient.isEmpty(), toClient.toString());
        this.assertNothingFailed();
    }

    @Test
    void sendsTheClientTheServersNewItemThatBoarHasNotApplied() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));

        // The client gets the new stack, but does not acknowledge it, so Boar keeps the old one.
        InventorySlotPacket newer = new InventorySlotPacket();
        newer.setContainerId(ContainerId.INVENTORY);
        newer.setSlot(0);
        newer.setItem(stones(10, 2));
        this.server.send(newer);
        this.server.send(new Flush());
        assertEquals(10, this.client.await(InventorySlotPacket.class).getItem().getCount());
        assertEquals(64, onLoop(session, () -> player.compensatedInventory.inventoryContainer.get(0).getData().getCount()));

        InventorySlotPacket resent = this.placeOutOfReach(session, 0, stones(64, 1));
        assertEquals(0, resent.getSlot());
        assertEquals(10, resent.getItem().getCount());
        assertEquals(2, resent.getItem().getNetId());
        this.assertNothingFailed();
    }

    @Test
    void sendsTheClientBoarsCountAfterThePlacementsItAccepted() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));
        Vector3i feet = this.standOnAStoneFloor(session);

        for (int placed = 0; placed < 3; placed++) {
            // The top face of the floor two blocks east, then to either side of it.
            this.client.session.get().sendPacket(place(feet.add(2, -1, placed == 2 ? -1 : placed), eyes(feet), stones(64 - placed, 1)));
        }
        awaitOnLoop(session, () -> player.compensatedInventory.inventoryContainer.get(0).getData().getCount() == 61);
        assertEquals(61, onLoop(session, () -> player.compensatedInventory.inventoryContainer.get(0).getData().getCount()));

        InventorySlotPacket resent = this.placeOutOfReach(session, 0, stones(61, 1));
        assertEquals(61, resent.getItem().getCount());
        assertEquals(1, resent.getItem().getNetId());
        this.assertNothingFailed();
    }

    @Test
    void sendsTheClientTheItemAStackResponseMoved() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));

        // The player opens its inventory, as dragonfly shows it, and moves the stack from slot 0 to slot 1.
        ContainerOpenPacket open = new ContainerOpenPacket();
        open.setId((byte) ContainerId.INVENTORY);
        open.setType(ContainerType.INVENTORY);
        open.setBlockPosition(this.startGame.getPlayerPosition().toInt());
        open.setUniqueEntityId(-1);
        this.server.send(open);
        this.server.send(new Flush());
        this.answerLatencyAfter(ContainerOpenPacket.class);
        awaitOnLoop(session, () -> player.compensatedInventory.openContainer != null);
        assertNotNull(onLoop(session, () -> player.compensatedInventory.openContainer));
        int requestId = -3;
        PlaceAction move = new PlaceAction(64, slotData(0, 1), slotData(1, 0));
        ItemStackRequestPacket request = new ItemStackRequestPacket();
        request.getRequests().add(new ItemStackRequest(requestId, new ItemStackRequestAction[]{move}, new String[0]));
        this.client.session.get().sendPacket(request);
        this.client.session.get().sendPacket(hold(1));
        while (!(this.server.await() instanceof MobEquipmentPacket)) {
            // The request.
        }

        // As dragonfly answers: only the response, naming the request's container, with a new stack network ID for the moved stack.
        ItemStackResponseContainer changes = new ItemStackResponseContainer(ContainerSlotType.HOTBAR_AND_INVENTORY, List.of(
                new ItemStackResponseSlot(0, 0, 0, 0, "", 0, ""),
                new ItemStackResponseSlot(1, 1, 64, 3, "", 0, "")), new FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null));
        ItemStackResponsePacket response = new ItemStackResponsePacket();
        response.getEntries().add(new ItemStackResponse(ItemStackResponseStatus.OK, requestId, List.of(changes)));
        this.server.send(response);
        this.server.send(new Flush());
        this.answerLatencyAfter(ItemStackResponsePacket.class);
        awaitOnLoop(session, () -> player.compensatedInventory.inventoryContainer.get(1).getData().getNetId() == 3);
        assertEquals(64, onLoop(session, () -> player.compensatedInventory.inventoryContainer.get(1).getData().getCount()));
        assertEquals(1, onLoop(session, () -> player.getInventoryAccessor().heldItemSlot()));

        InventorySlotPacket resent = this.placeOutOfReach(session, 1, stones(64, 3));
        assertEquals(1, resent.getSlot());
        assertEquals(64, resent.getItem().getCount());
        assertEquals(3, resent.getItem().getNetId());
        this.assertNothingFailed();
    }

    @Test
    void sendsBoarsUndoWithoutWaitingForAQuietServer() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));

        this.undoOutOfReach(session, 0, stones(64, 1));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        List<Class<?>> toClient = new ArrayList<>();
        for (BedrockPacket packet; !toClient.contains(NetworkStackLatencyPacket.class) && (packet = this.client.received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
            // Skips Boar's latency packets from before the placement
            if (!toClient.isEmpty() || packet instanceof UpdateBlockPacket) {
                toClient.add(packet.getClass());
            }
        }
        // Both blocks and the held slot, then Boar's acknowledgment
        assertEquals(List.of(UpdateBlockPacket.class, UpdateBlockPacket.class, InventorySlotPacket.class, UpdateBlockPacket.class, UpdateBlockPacket.class, InventorySlotPacket.class, NetworkStackLatencyPacket.class), toClient);
        this.assertNothingFailed();
    }

    // Boar cannot read a property without values, nor can the client, so with sorted IDs Boar knows none of the client's runtime IDs.
    @Test
    void leavesThePlayerUncheckedWhenItsRuntimeIdsAreUnknown() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(pipe(), noValues())));
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        assertNull(Boar.getInstance().getPlayerManager().get(session));
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Boar does not check Tester, since it does not know the client's block runtime IDs"), log);
        this.logged.reset();

        this.readItemsOnTheClient();
        SetPlayerGameTypePacket survival = new SetPlayerGameTypePacket();
        survival.setGamemode(GameType.SURVIVAL.ordinal());
        this.server.send(survival);
        this.server.send(new Flush());
        assertEquals(GameType.SURVIVAL.ordinal(), this.client.await(SetPlayerGameTypePacket.class).getGamemode());
        Vector3f eyes = this.startGame.getPlayerPosition();
        this.client.session.get().sendPacket(place(eyes.toInt().add(30, -2, 0), eyes, stones()));
        while (!(this.server.await() instanceof InventoryTransactionPacket)) {
            // The client's other packets.
        }
        // Boar's listeners saw no packet of either side, not even the StartGame.
        assertTrue(RECORDER.received.isEmpty(), RECORDER.received.toString());
        assertTrue(RECORDER.sent.isEmpty(), RECORDER.sent.toString());
        this.assertNothingFailed();
    }

    // The client makes a block with a door archetype a vanilla door, whose states Boar does not know, so with sorted IDs Boar knows none of the client's runtime IDs.
    @Test
    void leavesThePlayerUncheckedWhenTheServerAddsAnArchetypeBlock() throws Exception {
        NbtMap archetype = NbtMap.builder().putString("type", "door_block").build();
        BlockPropertyData door = new BlockPropertyData("cubecraft:pipe", pipe().getProperties().toBuilder().putCompound("vanilla_block_data", VANILLA_BLOCK_DATA.toBuilder().putCompound("block_archetype", archetype).build()).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(door)));
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        assertNull(Boar.getInstance().getPlayerManager().get(session));
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("cubecraft:pipe: it has the block archetype door_block, whose block class Boar does not know"), log);
        assertTrue(log.contains("Boar does not check Tester, since it does not know the client's block runtime IDs"), log);
        this.logged.reset();
        this.readItemsOnTheClient();
        this.assertNothingFailed();
    }

    // With hashed IDs, Boar keeps the runtime ID of each block it cannot read, so it can send the block back.
    @Test
    void keepsMitigationsOnWhenItCannotReadTheCustomBlocks() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), true, List.of(pipe(), noValues())));
        assertTrue(this.startGame.isBlockNetworkIdsHashed());
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());

        this.readItemsOnTheClient();
        BedrockBlockTable table = BedrockBlockTable.get();
        int unread = 0x5EEDF00E;
        assertTrue(table.indexOfHash(unread) < 0);
        Vector3i floor = this.standOnAFloor(session, table.networkHash(table.firstIndex("minecraft:air")), unread, 1).sub(0, 1, 0);
        this.client.received.clear();
        onLoop(session, () -> {
            BlockUtil.syncBlock(player, floor);
            return null;
        });
        assertEquals(unread, this.resentLayerZero(floor).getDefinition().getRuntimeId());
    }

    // The client keeps the last of two blocks with the same name: the pipe's 2 states, which move stone and air by 2.
    @Test
    void checksAPlayerWhoseServerListsABlockTwice() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(plainBlock("cubecraft:pipe"), pipe())));
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        BoarPlayer player = this.withMitigations(session);
        this.readItemsOnTheClient();
        int air = AIR_RUNTIME_ID + 2;
        int stone = STONE_RUNTIME_ID + 2;
        assertEquals(air, player.mappingInfo.airId());

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, air, stone, 20));
        Vector3i floor = Vector3i.from((this.startGame.getPlayerPosition().getFloorX() & ~15) + 3, 0, (this.startGame.getPlayerPosition().getFloorZ() & ~15) + 5);
        BoarBlockState floorState = onLoop(session, () -> player.compensatedWorld.getBlockState(floor, 0));
        assertEquals("minecraft:stone", floorState.block().toString());
        assertEquals(stone, floorState.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        this.assertNothingFailed();
    }

    // The client skips the two blocks without vanilla_block_data, which sort before stone, so only the pipe's 2 states move stone and air, and the broken block does not make the IDs unknown.
    @Test
    void checksAPlayerWhoseServerSendsBlocksWithoutVanillaBlockData() throws Exception {
        TestBoar.mitigations(true);
        NbtMap level = NbtMap.builder().putString("name", "level").putList("enum", NbtType.INT, 0, 1, 2).build();
        BlockPropertyData vent = withoutVanillaBlockData(customBlock("cubecraft:vent", level, null, null));
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(withoutVanillaBlockData(noValues()), vent, pipe())));
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        BoarPlayer player = this.withMitigations(session);
        this.readItemsOnTheClient();
        int air = AIR_RUNTIME_ID + 2;
        int stone = STONE_RUNTIME_ID + 2;
        assertEquals(air, player.mappingInfo.airId());

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, air, stone, 20));
        Vector3i floor = Vector3i.from((this.startGame.getPlayerPosition().getFloorX() & ~15) + 3, 0, (this.startGame.getPlayerPosition().getFloorZ() & ~15) + 5);
        BoarBlockState floorState = onLoop(session, () -> player.compensatedWorld.getBlockState(floor, 0));
        assertEquals("minecraft:stone", floorState.block().toString());
        assertEquals(stone, floorState.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        this.assertNothingFailed();
    }

    // The pipe's placement_direction trait gives it 4 states of minecraft:cardinal_direction, which move stone and air by 4.
    @Test
    void checksAPlayerWhoseServerSendsABlockWithTraits() throws Exception {
        TestBoar.mitigations(true);
        NbtMap direction = NbtMap.builder().putString("name", "minecraft:placement_direction").putCompound("enabled_states", NbtMap.builder().putByte("cardinal_direction", (byte) 1).build()).putFloat("y_rotation_offset", 0).build();
        BlockPropertyData pipe = new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("traits", NbtType.COMPOUND, direction).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(pipe)));
        assertFalse(this.startGame.isBlockNetworkIdsHashed());
        BoarPlayer player = this.withMitigations(session);
        this.readItemsOnTheClient();
        int air = AIR_RUNTIME_ID + 4;
        int stone = STONE_RUNTIME_ID + 4;
        assertEquals(air, player.mappingInfo.airId());

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, air, stone, 20));
        Vector3i floor = Vector3i.from((this.startGame.getPlayerPosition().getFloorX() & ~15) + 3, 0, (this.startGame.getPlayerPosition().getFloorZ() & ~15) + 5);
        BoarBlockState floorState = onLoop(session, () -> player.compensatedWorld.getBlockState(floor, 0));
        assertEquals("minecraft:stone", floorState.block().toString());
        assertEquals(stone, floorState.definition(player).getRuntimeId());
        assertFalse(BedrockPalette.runtimeIdsUnknown(player.mappingInfo));
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        this.assertNothingFailed();
    }

    @Test
    void keepsMitigationsOnWhenItReadsEveryCustomBlock() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(pipe())));
        assertFalse(Boar.getInstance().getPlayerManager().get(session).disableMitigations());
        this.assertNothingFailed();
    }

    // Without minecraft:collision_box, a custom block is a full cube to the client, and so to Boar.
    @Test
    void standsOnACustomBlockFloor() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(plainBlock("cubecraft:tiles"))));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        int tiles = this.customRuntimeId(session, player);

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, AIR_RUNTIME_ID, tiles, 20));
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("cubecraft:tiles"), log);
        this.assertNothingFailed();
    }

    // A custom block whose collision_box is half a block high holds the player at half a block.
    @Test
    void standsOnACustomSlab() throws Exception {
        TestBoar.mitigations(true);
        BlockPropertyData shelf = customBlock("cubecraft:shelf", null, collisionBox(0, 0, 0, 16, 8, 16), null);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(shelf)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        int shelfId = this.customRuntimeId(session, player);

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, AIR_RUNTIME_ID, shelfId, 20, 0.5F));
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("cubecraft:shelf"), log);
        this.assertNothingFailed();
    }

    // A custom block whose collision_box is disabled lets the player walk through it, where a full cube would stop them.
    @Test
    void walksThroughACustomBlockWithoutCollision() throws Exception {
        TestBoar.mitigations(true);
        BlockPropertyData ghost = customBlock("cubecraft:ghost", null, NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build(), null);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(ghost)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        int ghostId = this.customRuntimeId(session, player);
        this.walksThroughAWall(session, player, ghostId, -1);
    }

    // A permutation's collision_box applies to the states its condition matches: an open portcullis lets the player through, and a closed one is a full cube.
    @Test
    void walksThroughAnOpenCustomGate() throws Exception {
        TestBoar.mitigations(true);
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
        NbtMap opened = NbtMap.builder().putString("condition", "q.block_state('open') == true")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build()).build()).build();
        BlockPropertyData portcullis = customBlock("cubecraft:portcullis", open, null, opened);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(portcullis)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        // The first state is closed and the second open, since open is the only property.
        int closedId = this.customRuntimeId(session, player);
        Vector3i closed = this.walksThroughAWall(session, player, closedId + 1, closedId);
        assertArrayEquals(corners(List.of(new Box(0, 0, 0, 1, 1, 1))), corners(onLoop(session, () -> player.compensatedWorld.getBlockState(closed, 0).getCollisionBoxes())));
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(closed, 0).isFaceSturdy(player)));
    }

    // An open gate as Geyser 2.12 sends a block of its custom mappings: string properties, molangVersion 1 and a q.block_property condition (BlockMappingsReader_v1).
    @Test
    void walksThroughAnOpenGeyserMappedGate() throws Exception {
        TestBoar.mitigations(true);
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.STRING, "false", "true").build();
        NbtMap opened = NbtMap.builder().putString("condition", "q.block_property('open') == 'true'")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build()).build()).build();
        BlockPropertyData built = customBlock("geyser:gate", open, collisionBox(0, 0, 0, 16, 16, 16), opened);
        BlockPropertyData gate = new BlockPropertyData(built.getName(), built.getProperties().toBuilder().putInt("molangVersion", 1).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(gate)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        // The first state is "false" and the second "true", in the order of the values.
        int closedId = this.customRuntimeId(session, player);
        Vector3i closed = this.walksThroughAWall(session, player, closedId + 1, closedId);
        assertArrayEquals(corners(List.of(new Box(0, 0, 0, 1, 1, 1))), corners(onLoop(session, () -> player.compensatedWorld.getBlockState(closed, 0).getCollisionBoxes())));
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("geyser:gate"), log);
    }

    // A slab whose transformation turns it a quarter about Z, as Geyser 2.12 writes it, stands upright in the east half of its block. The player walks east against it with no violations, and a client that walks through it is flagged.
    @Test
    void walksAgainstACustomSlabTurnedUpright() throws Exception {
        TestBoar.mitigations(true);
        NbtMap turned = NbtMap.builder().putInt("RX", 0).putInt("RY", 0).putInt("RZ", 1)
                .putFloat("SX", 1).putFloat("SY", 1).putFloat("SZ", 1).putFloat("TX", 0).putFloat("TY", 0).putFloat("TZ", 0).build();
        BlockPropertyData built = customBlock("cubecraft:shelf", null, collisionBox(0, 0, 0, 16, 8, 16), null);
        BlockPropertyData panel = new BlockPropertyData(built.getName(), built.getProperties().toBuilder()
                .putCompound("components", built.getProperties().getCompound("components").toBuilder().putCompound("minecraft:transformation", turned).build()).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(panel)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        int panelId = this.customRuntimeId(session, player);
        Vector3i wall = this.sendAWall(session, player, panelId, -1);
        assertArrayEquals(corners(List.of(new Box(0.5F, 0, 0, 1, 1, 1))), corners(onLoop(session, () -> player.compensatedWorld.getBlockState(wall, 0).getCollisionBoxes())));
        assertFalse(onLoop(session, () -> player.compensatedWorld.getBlockState(wall, 0).isFaceSturdy(player)));

        Walk walk = this.walkEast(session, player, wall, wall.getX() + 0.5F);
        // The player's front stopped at the slab's face, half a block into the wall's block.
        assertEquals(wall.getX() + 0.5F - 0.3F, walk.x(), 1e-6F);
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertEquals(List.of(), walk.violations());
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("cubecraft:shelf"), log);
        this.assertNothingFailed();

        // The same steps again, from where the player stands, by a client that walks through the slab
        Walk lie = this.walkEast(session, player, wall, Float.POSITIVE_INFINITY, walk.x(), 0, walk.nextTick());
        assertTrue(lie.x() - 0.3F > wall.getX() + 1, "x " + lie.x());
        assertNotEquals(List.of(), lie.violations());
    }

    // A cube whose random_offset moves it -0.5 to -0.0625 blocks on X stands a whole block west of its position, since the client rounds the offset down for a collision_box. The player walks east against it with no violations, and a client that walks to the unmoved cube is flagged.
    @Test
    void walksAgainstACustomCubeMovedByItsRandomOffset() throws Exception {
        TestBoar.mitigations(true);
        NbtMap offset = NbtMap.builder().putCompound("x", offsetAxis(-8, -1)).putCompound("y", offsetAxis(0, 0)).putCompound("z", offsetAxis(0, 8)).build();
        BlockPropertyData built = customBlock("cubecraft:reed", null, collisionBox(0, 0, 0, 16, 16, 16), null);
        BlockPropertyData reed = new BlockPropertyData(built.getName(), built.getProperties().toBuilder()
                .putCompound("components", built.getProperties().getCompound("components").toBuilder().putCompound("minecraft:random_offset", offset).build()).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(reed)));
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        assertFalse(player.disableMitigations());
        int reedId = this.customRuntimeId(session, player);
        Vector3i wall = this.sendAWall(session, player, reedId, -1);
        assertArrayEquals(corners(List.of(new Box(0, 0, 0, 1, 1, 1))), corners(onLoop(session, () -> player.compensatedWorld.getBlockState(wall, 0).getCollisionBoxes())));

        Walk walk = this.walkEast(session, player, wall, wall.getX() - 1);
        assertEquals(wall.getX() - 1 - 0.3F, walk.x(), 1e-6F);
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertEquals(List.of(), walk.violations());
        String log = this.logged.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("cubecraft:reed"), log);
        this.assertNothingFailed();

        // The same steps again, from where the player stands, by a client that walks on to where the cube would be without its offset
        Walk lie = this.walkEast(session, player, wall, wall.getX(), walk.x(), 0, walk.nextTick());
        assertEquals(wall.getX() - 0.3F, lie.x(), 1e-6F);
        assertNotEquals(List.of(), lie.violations());
    }

    // One axis of a minecraft:random_offset as BlockRandomOffsetDescription's schema reads it: any value from min to max, in 1/16 of a block
    private static NbtMap offsetAxis(float min, float max) {
        return NbtMap.builder().putCompound("range", NbtMap.builder().putFloat("min", min).putFloat("max", max).build()).putInt("steps", 0).build();
    }

    // Sends a chunk with a stone floor at Y 0 and a wall of this block across the player's way at local X WALL_X and Y 1 and 2, and walks east through it with no violations.
    // A block of the other ID, unless it is -1, stands away from the way, and its position is returned.
    private Vector3i walksThroughAWall(Session session, BoarPlayer player, int wall, int other) throws Exception {
        Vector3i wallPos = this.sendAWall(session, player, wall, other);
        Walk walk = this.walkEast(session, player, wallPos, Float.POSITIVE_INFINITY);
        // The player's whole width went past the wall, and Boar predicted every step.
        assertTrue(walk.x() - 0.3F > wallPos.getX() + 1, "x " + walk.x());
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), walk.violations());
        this.assertNothingFailed();
        return wallPos.add(12 - WALL_X, 0, 2 - FENCE_Z);
    }

    // Sends a chunk with a stone floor at Y 0 and a wall of this block across the player's way at local X WALL_X and Y 1 and 2, and returns the wall's block on the player's way.
    // A block of the other ID, unless it is -1, stands at local (12, 1, 2), away from the way.
    private Vector3i sendAWall(Session session, BoarPlayer player, int wall, int other) throws Exception {
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        int[] blocks = new int[4096];
        Arrays.fill(blocks, AIR_RUNTIME_ID);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                blocks[x << 8 | z << 4] = STONE_RUNTIME_ID;
                if (x == WALL_X) {
                    blocks[x << 8 | z << 4 | 1] = wall;
                    blocks[x << 8 | z << 4 | 2] = wall;
                }
            }
        }
        if (other != -1) {
            blocks[12 << 8 | 2 << 4 | 1] = other;
        }
        this.sendChunk(chunkX, chunkZ, subChunk(0, blocks));
        Vector3i wallPos = Vector3i.from((chunkX << 4) + WALL_X, 1, (chunkZ << 4) + FENCE_Z);
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(wallPos, 0).isAir());
        return wallPos;
    }

    private record Walk(float x, int nextTick, List<String> violations) {
    }

    // Stands still for 10 ticks, then walks east for 80, as the client moves on stone, from 2.5 into the chunk on the wall's line.
    private Walk walkEast(Session session, BoarPlayer player, Vector3i wall, float face) throws Exception {
        return this.walkEast(session, player, wall, face, ((wall.getX() >> 4) << 4) + 2.5F, 10, 1);
    }

    // The client stops the player's front at the face, at this X, where it collides: its X velocity becomes 0 and it says HORIZONTAL_COLLISION. Returns where it ended and the violations Boar flagged.
    private Walk walkEast(Session session, BoarPlayer player, Vector3i wall, float face, float x, int still, int firstTick) throws Exception {
        List<Violation> violations = new CopyOnWriteArrayList<>();
        ViolationListener listener = violation -> {
            if (violation.player() == player) {
                violations.add(violation);
            }
        };
        Boar.getInstance().getViolationRegistry().register(listener);
        try {
            float z = wall.getZ() + 0.5F;
            Vec3 velocity = Vec3.ZERO.clone();
            long before = RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count();
            int ticks = still + 80;
            for (int tick = 1; tick <= ticks; tick++) {
                boolean walking = tick > still;
                boolean collided = false;
                if (walking) {
                    Vec3 input = MathUtil.getInputVector(new Vec3(0, 0, 0.3F * 0.98F), 0.1F, -90);
                    velocity = velocity.add(input);
                    x += velocity.x;
                    z += velocity.z;
                    if (x + 0.3F > face) {
                        x = face - 0.3F;
                        velocity = new Vec3(0, velocity.y, velocity.z);
                        collided = true;
                    }
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
                if (collided) {
                    input.getInputData().add(PlayerAuthInputData.HORIZONTAL_COLLISION);
                }
                input.setDelta(Vector3f.from(velocity.x, -0.0784F, velocity.z));
                input.setTick(firstTick + tick - 1);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }
            awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= before + ticks);
        } finally {
            Boar.getInstance().getViolationRegistry().unregister(listener);
        }
        return new Walk(x, firstTick + still + 80, violations.stream().map(violation -> violation.check().getClass().getSimpleName() + " " + violation.verbose()).toList());
    }

    // Boar takes the custom block for a full cube, so a click on it places the stone above it.
    @Test
    void placesABlockOnACustomBlock() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(plainBlock("cubecraft:lamp"))));
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));
        int lamp = this.customRuntimeId(session, player);
        Vector3i feet = this.standOnAFloor(session, AIR_RUNTIME_ID, lamp, 5);

        Vector3i clicked = feet.add(2, -1, 0);
        this.client.session.get().sendPacket(place(clicked, eyes(feet), stones(64, 1)));
        InventoryTransactionPacket placed = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (BedrockPacket packet; placed == null && (packet = this.server.received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
            if (packet instanceof InventoryTransactionPacket transaction && transaction.getTransactionType() == InventoryTransactionType.ITEM_USE) {
                placed = transaction;
            }
        }
        assertNotNull(placed, "the placement did not reach the server");
        assertEquals(clicked, placed.getBlockPosition());
        assertEquals("minecraft:stone", onLoop(session, () -> player.compensatedWorld.getBlockState(clicked.add(0, 1, 0), 0).block().toString()));
        this.assertNothingFailed();
    }

    // A click on a custom block with minecraft:replaceable, like grass, places the stone in its place, as the client does.
    @Test
    void placesABlockIntoACustomGrass() throws Exception {
        TestBoar.mitigations(true);
        NbtMap components = NbtMap.builder()
                .putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build())
                .putCompound("minecraft:replaceable", NbtMap.EMPTY)
                .build();
        // cubecraft:tall_grass sorts after stone and air by name hash, so it moves neither.
        BlockPropertyData grass = new BlockPropertyData("cubecraft:tall_grass", NbtMap.builder().putCompound("components", components).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(grass)));
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));
        int grassId = this.customRuntimeId(session, player);

        // A stone floor at Y 0 with the grass on it, two blocks east of the player's feet
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        Vector3i feet = Vector3i.from((chunkX << 4) + 3, 1, (chunkZ << 4) + 5);
        Vector3i clicked = feet.add(2, 0, 0);
        int[] blocks = new int[4096];
        Arrays.fill(blocks, AIR_RUNTIME_ID);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                blocks[x << 8 | z << 4] = STONE_RUNTIME_ID;
            }
        }
        blocks[(clicked.getX() & 15) << 8 | (clicked.getZ() & 15) << 4 | 1] = grassId;
        this.sendChunk(chunkX, chunkZ, subChunk(0, blocks));
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(clicked, 0).block().toString().equals("cubecraft:tall_grass"));

        List<String> violations = this.violationsWhile(player, () -> {
            for (int tick = 1; tick <= 5; tick++) {
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setPosition(eyes(feet));
                input.setDelta(Vector3f.from(0, -0.0784F, 0));
                input.setTick(tick);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }
            this.client.session.get().sendPacket(place(clicked, eyes(feet), stones(64, 1)));
            InventoryTransactionPacket placed = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (BedrockPacket packet; placed == null && (packet = this.server.received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
                if (packet instanceof InventoryTransactionPacket transaction && transaction.getTransactionType() == InventoryTransactionType.ITEM_USE) {
                    placed = transaction;
                }
            }
            assertNotNull(placed, "the placement did not reach the server");
            return null;
        });
        assertEquals(List.of(), violations);
        assertEquals("minecraft:stone", onLoop(session, () -> player.compensatedWorld.getBlockState(clicked, 0).block().toString()));
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(clicked.add(0, 1, 0), 0).isAir()));
        this.assertNothingFailed();
    }

    // A custom block whose minecraft:friction value is 0.02 is as slippery as ice to the client, whose friction is 1 minus the value.
    // The player walks east on a floor of it, then lets go and slides, with no violations.
    @Test
    void walksAndStopsOnACustomIceFloor() throws Exception {
        TestBoar.mitigations(true);
        NbtMap components = NbtMap.builder().putCompound("minecraft:friction", NbtMap.builder().putFloat("value", 0.02F).build()).build();
        BlockPropertyData ice = new BlockPropertyData("cubecraft:tiles", NbtMap.builder().putCompound("components", components).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(ice)));
        BoarPlayer player = this.withMitigations(session);
        int iceId = this.customRuntimeId(session, player);

        float friction = 1 - 0.02F;
        // Where the player is when it lets go, and where it ends
        float[] x = new float[2];
        Vector3i[] feet = new Vector3i[1];
        List<String> violations = this.violationsWhile(player, () -> {
            feet[0] = this.standOnAFloor(session, AIR_RUNTIME_ID, iceId, 5);
            // As the client moves on a block of this friction (GroundTravelTypeSystemImpl::_calcGroundFrictionV2): its input times 0.98 at speed 0.1 times (0.546 / (friction * 0.91))^3 a tick, and velocity times friction * 0.91 after each tick.
            float drag = friction * 0.91F;
            float ratio = 0.54600006F / drag;
            float speed = ratio * ratio * ratio * 0.1F;
            x[1] = feet[0].getX() + 0.5F;
            Vec3 velocity = Vec3.ZERO.clone();
            long before = RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count();
            for (int tick = 6, walked = 0; walked < 60; tick++, walked++) {
                boolean walking = walked < 20;
                if (walking) {
                    velocity = velocity.add(MathUtil.getInputVector(new Vec3(0, 0, 0.3F * 0.98F), speed, -90));
                }
                x[1] += velocity.x;
                velocity = velocity.multiply(drag, 1, drag);
                if (walked == 19) {
                    x[0] = x[1];
                }
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setRotation(Vector3f.from(0, -90, -90));
                input.setPosition(Vector3f.from(x[1], feet[0].getY() + 1.62001F, feet[0].getZ() + 0.5F));
                if (walking) {
                    input.getInputData().add(PlayerAuthInputData.UP);
                    input.setMotion(Vector2f.from(0, 0.3F));
                    input.setRawMoveVector(Vector2f.from(0, 0.3F));
                    input.setAnalogMoveVector(Vector2f.from(0, 0.3F));
                }
                input.setDelta(Vector3f.from(velocity.x, -0.0784F, 0));
                input.setTick(tick);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }
            awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= before + 60);
            return null;
        });
        // On ice the player slides more than 0.4 blocks after it lets go, where on stone it would stop within 0.08.
        assertTrue(x[1] - x[0] > 0.4F, "slid " + (x[1] - x[0]));
        assertEquals(List.of(), violations);
        assertEquals(friction, onLoop(session, () -> player.compensatedWorld.getBlockState(feet[0].sub(0, 1, 0), 0).getFriction()));
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        this.assertNothingFailed();
    }

    // A server sends the item of its custom block named after the block, with version NONE, as Geyser and dragonfly do. The client places the block's first state, and so does Boar, before the server sends the block.
    @Test
    void standsOnTheCustomBlockItPlacedBeforeTheServerSendsIt() throws Exception {
        TestBoar.mitigations(true);
        SimpleItemDefinition tilesItem = new SimpleItemDefinition("cubecraft:tiles", -600, ItemVersion.NONE, false, null);
        List<ItemDefinition> items = new ArrayList<>(ITEMS);
        items.add(tilesItem);
        this.server.items = items;
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), false, List.of(plainBlock("cubecraft:tiles"))));
        BoarPlayer player = this.withMitigations(session);
        int tiles = this.customRuntimeId(session, player);
        ItemData tilesStack = ItemData.builder().definition(tilesItem).count(64).blockDefinition(Definitions.ANY_BLOCK.getDefinition(tiles)).usingNetId(true).netId(1).build();
        this.giveAndAcknowledge(session, player, 0, tilesStack);

        // A stone floor at Y 0 with a hole east of the player's feet
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        Vector3i feet = Vector3i.from((chunkX << 4) + 3, 1, (chunkZ << 4) + 5);
        Vector3i hole = feet.add(1, -1, 0);
        int[] blocks = new int[4096];
        Arrays.fill(blocks, AIR_RUNTIME_ID);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                blocks[x << 8 | z << 4] = STONE_RUNTIME_ID;
            }
        }
        blocks[(hole.getX() & 15) << 8 | (hole.getZ() & 15) << 4] = AIR_RUNTIME_ID;
        this.sendChunk(chunkX, chunkZ, subChunk(0, blocks));
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(feet.sub(0, 1, 0), 0).isAir());

        List<String> violations = this.violationsWhile(player, () -> {
            int tick = 1;
            for (; tick <= 5; tick++) {
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setPosition(eyes(feet));
                input.setDelta(Vector3f.from(0, -0.0784F, 0));
                input.setTick(tick);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }

            // A click on the east face of the floor under the player's feet fills the hole.
            InventoryTransactionPacket place = place(feet.sub(0, 1, 0), eyes(feet), tilesStack);
            place.setBlockFace(5);
            place.setClickPosition(Vector3f.from(1, 0.5F, 0.5F));
            this.client.session.get().sendPacket(place);
            awaitOnLoop(session, () -> RECORDER.received.contains(InventoryTransactionPacket.class));
            assertEquals("cubecraft:tiles", onLoop(session, () -> player.compensatedWorld.getBlockState(hole, 0).block().toString()));
            assertEquals(tiles, onLoop(session, () -> player.mappingInfo.fromIntermediary().applyAsInt(player.compensatedWorld.getBlockState(hole, 0).intermediaryId())));

            // Walks east for 15 ticks, as the client moves on stone, then stops with its whole width over the placed block. The server never sends the block.
            float x = feet.getX() + 0.5F;
            Vec3 velocity = Vec3.ZERO.clone();
            long before = RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count();
            int last = tick + 15 + 20;
            for (int walked = 0; tick < last; tick++, walked++) {
                boolean walking = walked < 15;
                if (walking) {
                    velocity = velocity.add(MathUtil.getInputVector(new Vec3(0, 0, 0.3F * 0.98F), 0.1F, -90));
                }
                x += velocity.x;
                velocity = velocity.multiply(0.546F, 1, 0.546F);
                this.answerLatency();
                PlayerAuthInputPacket input = authInput();
                input.setRotation(Vector3f.from(0, -90, -90));
                input.setPosition(Vector3f.from(x, feet.getY() + 1.62001F, feet.getZ() + 0.5F));
                if (walking) {
                    input.getInputData().add(PlayerAuthInputData.UP);
                    input.setMotion(Vector2f.from(0, 0.3F));
                    input.setRawMoveVector(Vector2f.from(0, 0.3F));
                    input.setAnalogMoveVector(Vector2f.from(0, 0.3F));
                }
                input.setDelta(Vector3f.from(velocity.x, -0.0784F, 0));
                input.setTick(tick);
                this.client.session.get().sendPacket(input);
                Thread.sleep(50);
            }
            awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= before + 35);
            assertTrue(x - 0.3F > hole.getX() && x + 0.3F < hole.getX() + 1, "x " + x);
            return null;
        });
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        assertEquals("cubecraft:tiles", onLoop(session, () -> player.compensatedWorld.getBlockState(hole, 0).block().toString()));
        this.assertNothingFailed();
    }

    // A hashed runtime ID that is no state of the palette, such as a block of a newer Bedrock version, is a full cube that Boar can send back.
    @Test
    void standsOnABlockItDoesNotKnow() throws Exception {
        TestBoar.mitigations(true);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), true, List.of()));
        BoarPlayer player = this.withMitigations(session);
        this.readItemsOnTheClient();
        BedrockBlockTable table = BedrockBlockTable.get();
        int air = table.networkHash(table.firstIndex("minecraft:air"));
        int unknown = 0x5EEDF00D;
        assertTrue(table.indexOfHash(unknown) < 0);

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, air, unknown, 20));
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);

        // Boar's undo of the floor sends the client the same block.
        Vector3i floor = Vector3i.from((this.startGame.getPlayerPosition().getFloorX() & ~15) + 3, 0, (this.startGame.getPlayerPosition().getFloorZ() & ~15) + 5);
        this.client.received.clear();
        onLoop(session, () -> {
            BlockUtil.syncBlock(player, floor);
            return null;
        });
        assertEquals(unknown, this.resentLayerZero(floor).getDefinition().getRuntimeId());
        this.assertNothingFailed();
    }

    // A custom state with stone's network hash comes before stone in the sorted palette, so it takes stone's ID and stone takes the next.
    @Test
    void standsOnStoneThatACustomStateMoved() throws Exception {
        TestBoar.mitigations(true);
        // A value found by search, whose state has stone's network hash
        BlockPropertyData clash = new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("properties", NbtType.COMPOUND, NbtMap.builder().putString("name", "n").putList("enum", NbtType.STRING, "vajo54aake").build()).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
        BedrockBlockTable table = BedrockBlockTable.get();
        int stone = table.networkHash(table.firstIndex("minecraft:stone"));
        ByteArrayOutputStream state = new ByteArrayOutputStream();
        try (NBTOutputStream out = NbtUtils.createWriterLE(state)) {
            out.writeTag(NbtMap.builder().putString("name", "cubecraft:pipe").putCompound("states", NbtMap.builder().putString("n", "vajo54aake").build()).build());
        }
        int hash = 0x811c9dc5;
        for (byte b : state.toByteArray()) {
            hash = (hash ^ (b & 0xFF)) * 0x01000193;
        }
        assertEquals(stone, hash);
        assertTrue(table.indexOfHash(stone + 1) < 0);
        Session session = this.spawn(login -> new AddsCustomBlocks(processors.apply(login), true, List.of(clash)));
        assertTrue(this.startGame.isBlockNetworkIdsHashed());
        BoarPlayer player = this.withMitigations(session);
        this.readItemsOnTheClient();
        int air = table.networkHash(table.firstIndex("minecraft:air"));

        List<String> violations = this.violationsWhile(player, () -> this.standOnAFloor(session, air, stone + 1, 20));
        Vector3i floor = Vector3i.from((this.startGame.getPlayerPosition().getFloorX() & ~15) + 3, 0, (this.startGame.getPlayerPosition().getFloorZ() & ~15) + 5);
        BoarBlockState floorState = onLoop(session, () -> player.compensatedWorld.getBlockState(floor, 0));
        assertEquals("minecraft:stone", floorState.block().toString());
        assertEquals(stone + 1, floorState.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.getMovementTrace().dump("")).contains("path: prediction"));
        assertTrue(onLoop(session, () -> player.onGround));
        assertEquals(List.of(), violations);
        this.assertNothingFailed();
    }

    // UNKNOWN_ID is a block Boar has no runtime ID for, so it cannot undo it.
    @Test
    void doesNotResendABlockWithoutARuntimeId() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.readItemsOnTheClient();
        Vector3i unknown = this.startGame.getPlayerPosition().toInt().add(5, 0, 0);
        Vector3i stone = unknown.add(1, 0, 0);
        this.client.received.clear();
        onLoop(session, () -> {
            BlockUtil.syncBlock(player, unknown, BoarBlockState.create(BedrockPalette.UNKNOWN_ID, unknown, 0));
            BlockUtil.syncBlock(player, stone, BoarBlockState.create(BedrockBlockTable.get().firstIndex("minecraft:stone"), stone, 0));
            return null;
        });
        // Boar writes the client both blocks in order, so every packet before the stone's is kept.
        List<BedrockPacket> received = new ArrayList<>();
        while (received.stream().noneMatch(packet -> packet instanceof UpdateBlockPacket update && update.getBlockPosition().equals(stone) && update.getDataLayer() == 0)) {
            BedrockPacket packet = this.client.received.poll(10, TimeUnit.SECONDS);
            assertNotNull(packet, "no UpdateBlock at " + stone + " after " + received);
            received.add(packet);
        }
        assertTrue(received.stream().noneMatch(packet -> packet instanceof UpdateBlockPacket update && update.getBlockPosition().equals(unknown) && update.getDataLayer() == 0), received.toString());
        this.assertNothingFailed();
    }

    @Test
    void sendsBoarsUndoWithTheServersOpenBatch() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = this.withMitigations(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));

        // A batch the server has begun and not flushed yet, with Boar's undo written in the middle
        SetPlayerGameTypePacket survival = new SetPlayerGameTypePacket();
        survival.setGamemode(GameType.SURVIVAL.ordinal());
        RECORDER.sent.clear();
        this.client.received.clear();
        this.server.send(survival);
        awaitOnLoop(session, () -> RECORDER.sent.contains(SetPlayerGameTypePacket.class));
        this.undoOutOfReach(session, 0, stones(64, 1));
        SetTimePacket time = new SetTimePacket();
        time.setTime(6000);
        this.server.send(time);
        this.assertOnlyLatencyPacketsFor(300);

        this.server.send(new Flush());
        assertEquals(List.of(SetPlayerGameTypePacket.class, UpdateBlockPacket.class, UpdateBlockPacket.class, InventorySlotPacket.class, UpdateBlockPacket.class, UpdateBlockPacket.class, InventorySlotPacket.class, SetTimePacket.class,
                NetworkStackLatencyPacket.class), this.receivedUntilAcknowledgment());
        this.assertNothingFailed();
    }

    @Test
    void sendsBoarsSlotWithABatchTheServerBeginsBeforeTheFlushRuns() throws Exception {
        Session session = this.spawn();
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        this.giveAndAcknowledge(session, player, 0, stones(64, 1));

        // updateSlot queues the flush, and a server batch begins before it runs.
        this.client.received.clear();
        onLoop(session, () -> {
            player.getInventoryAccessor().updateSlot(0);
            TestBoar.openServerBatch(player);
            return null;
        });
        this.assertOnlyLatencyPacketsFor(300);

        this.server.send(new Flush());
        assertEquals(List.of(InventorySlotPacket.class, NetworkStackLatencyPacket.class), this.receivedUntilAcknowledgment());
        this.assertNothingFailed();
    }

    @Test
    void forgetsTheLastServersItemsAtATransfer() throws Exception {
        FakeSpectrumServer next = new FakeSpectrumServer(this.group, CODEC);
        next.items = ITEMS;
        try {
            Session session = this.spawn();
            BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
            this.giveAndAcknowledge(session, player, 0, stones(64, 1));
            // A new stack the client does not acknowledge before it leaves the server.
            InventorySlotPacket newer = new InventorySlotPacket();
            newer.setContainerId(ContainerId.INVENTORY);
            newer.setSlot(0);
            newer.setItem(stones(10, 2));
            this.server.send(newer);
            this.server.send(new Flush());
            this.client.await(InventorySlotPacket.class);

            session.transfer(next.address()).get(10, TimeUnit.SECONDS);
            onLoop(session, () -> {
                player.getInventoryAccessor().updateSlot(0);
                return null;
            });
            ItemData resent = this.client.await(InventorySlotPacket.class).getItem();
            assertEquals(64, resent.getCount());
            assertEquals(1, resent.getNetId());
            this.assertNothingFailed();
        } finally {
            next.close();
        }
    }

    // Turns Boar's mitigations on for this player, so that it undoes the placements it rejects, and has the server put the player in survival, where Boar counts the items the player uses and moves.
    private BoarPlayer withMitigations(Session session) throws Exception {
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        onLoop(session, () -> {
            player.setDisableMitigations(false);
            return null;
        });
        SetPlayerGameTypePacket survival = new SetPlayerGameTypePacket();
        survival.setGamemode(GameType.SURVIVAL.ordinal());
        this.server.send(survival);
        this.server.send(new Flush());
        this.answerLatencyAfter(SetPlayerGameTypePacket.class);
        awaitOnLoop(session, () -> player.gameType == GameType.SURVIVAL);
        assertEquals(GameType.SURVIVAL, onLoop(session, () -> player.gameType));
        return player;
    }

    // Has the server send the player's inventory with this item in this slot, which the client acknowledges.
    private void giveAndAcknowledge(Session session, BoarPlayer player, int slot, ItemData item) throws Exception {
        this.readItemsOnTheClient();
        List<ItemData> inventory = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        inventory.set(slot, item);
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(inventory);
        this.server.send(content);
        this.server.send(new Flush());
        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.inventoryContainer.get(slot).isEmpty());
    }

    private Vector3i standOnAStoneFloor(Session session) throws Exception {
        return this.standOnAFloor(session, AIR_RUNTIME_ID, STONE_RUNTIME_ID, 5);
    }

    // Sends the chunk under the StartGame position with a floor of this block at Y 0, and inputs standing on it for this many ticks. Returns the block of the player's feet.
    private Vector3i standOnAFloor(Session session, int air, int floor, int ticks) throws Exception {
        return this.standOnAFloor(session, air, floor, ticks, 1);
    }

    // As above, on a floor whose top is this high above Y 0
    private Vector3i standOnAFloor(Session session, int air, int floor, int ticks, float top) throws Exception {
        int chunkX = this.startGame.getPlayerPosition().getFloorX() >> 4;
        int chunkZ = this.startGame.getPlayerPosition().getFloorZ() >> 4;
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(stoneFloor(air, floor));
        this.server.send(chunk);
        this.server.send(new Flush());
        Vector3i feet = Vector3i.from((chunkX << 4) + 3, 1, (chunkZ << 4) + 5);
        BoarPlayer player = Boar.getInstance().getPlayerManager().get(session);
        awaitOnLoop(session, () -> !player.compensatedWorld.getBlockState(feet.sub(0, 1, 0), 0).isAir());
        long before = RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count();
        for (int tick = 1; tick <= ticks; tick++) {
            this.answerLatency();
            PlayerAuthInputPacket input = authInput();
            input.setPosition(eyes(feet).sub(0, 1 - top, 0));
            // The gravity that the floor stops in the next tick
            input.setDelta(Vector3f.from(0, -0.0784F, 0));
            input.setTick(tick);
            this.client.session.get().sendPacket(input);
            Thread.sleep(50);
        }
        awaitOnLoop(session, () -> RECORDER.received.stream().filter(PlayerAuthInputPacket.class::equals).count() >= before + ticks);
        return feet;
    }

    // The violations of this player that Boar flags while this runs, each as its check and details
    private List<String> violationsWhile(BoarPlayer player, Callable<?> run) throws Exception {
        List<String> violations = new CopyOnWriteArrayList<>();
        ViolationListener listener = violation -> {
            if (violation.player() == player) {
                violations.add(violation.check().getClass().getSimpleName() + " " + violation.verbose());
            }
        };
        Boar.getInstance().getViolationRegistry().register(listener);
        try {
            run.call();
        } finally {
            Boar.getInstance().getViolationRegistry().unregister(listener);
        }
        return violations;
    }

    // The first runtime ID of a custom state in the player's palette
    private int customRuntimeId(Session session, BoarPlayer player) throws Exception {
        return onLoop(session, () -> {
            for (int runtimeId = 0; ; runtimeId++) {
                if (player.mappingInfo.toIntermediary().applyAsInt(runtimeId) >= BedrockBlockTable.get().paletteSize()) {
                    return runtimeId;
                }
            }
        });
    }

    // The next layer 0 UpdateBlock the client gets at this position
    private UpdateBlockPacket resentLayerZero(Vector3i position) throws Exception {
        while (true) {
            BedrockPacket packet = this.client.received.poll(10, TimeUnit.SECONDS);
            assertNotNull(packet, "no UpdateBlock at " + position);
            if (packet instanceof UpdateBlockPacket update && update.getBlockPosition().equals(position) && update.getDataLayer() == 0) {
                return update;
            }
        }
    }

    // Places a block far out of reach while holding this slot, which Boar undoes, and returns the slot it sends the client then.
    private InventorySlotPacket placeOutOfReach(Session session, int slot, ItemData inHand) throws Exception {
        this.undoOutOfReach(session, slot, inHand);
        return this.client.await(InventorySlotPacket.class);
    }

    // Places a block far out of reach while holding this slot, and waits until Boar has written the client its undo.
    private void undoOutOfReach(Session session, int slot, ItemData inHand) throws Exception {
        Vector3f eyes = this.startGame.getPlayerPosition();
        InventoryTransactionPacket place = place(eyes.toInt().add(30, -2, 0), eyes, inHand);
        place.setHotbarSlot(slot);
        RECORDER.sent.clear();
        this.client.session.get().sendPacket(place);
        awaitOnLoop(session, () -> RECORDER.sent.contains(UpdateBlockPacket.class));
    }

    // A click on the top face of this block with the item in hotbar slot 0, as a real client sends it.
    private static InventoryTransactionPacket place(Vector3i clicked, Vector3f eyes, ItemData inHand) {
        InventoryTransactionPacket place = new InventoryTransactionPacket();
        place.setTransactionType(InventoryTransactionType.ITEM_USE);
        place.setActionType(0);
        place.setTriggerType(ItemUseTransaction.TriggerType.PLAYER_INPUT);
        place.setBlockPosition(clicked);
        place.setBlockFace(1);
        place.setHotbarSlot(0);
        place.setItemInHand(inHand);
        place.setPlayerPosition(eyes);
        place.setClickPosition(Vector3f.from(0.5F, 1, 0.5F));
        place.setBlockDefinition(Definitions.ANY_BLOCK.getDefinition(STONE_RUNTIME_ID));
        place.setClientInteractPrediction(ItemUseTransaction.PredictedResult.SUCCESS);
        return place;
    }

    // Where the client says its eyes are when it stands in this block
    private static Vector3f eyes(Vector3i feet) {
        return Vector3f.from(feet.getX() + 0.5F, feet.getY() + 1.62F, feet.getZ() + 0.5F);
    }

    // A slot of the player's inventory in an item stack request, with the stack network ID the client thinks it holds
    private static ItemStackRequestSlotData slotData(int slot, int netId) {
        return new ItemStackRequestSlotData(ContainerSlotType.HOTBAR_AND_INVENTORY, slot, netId, new FullContainerName(ContainerSlotType.HOTBAR_AND_INVENTORY, null));
    }

    // Gives the player stone in this hotbar slot and has the client hold it, once the server has all the client's earlier packets.
    private void holdStones(Session session, BoarPlayer player, int slot) throws Exception {
        this.readItemsOnTheClient();
        List<ItemData> inventory = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        inventory.set(slot, stones());
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(inventory);
        this.server.send(content);
        this.server.send(new Flush());
        this.answerLatencyAfter(InventoryContentPacket.class);
        awaitOnLoop(session, () -> !player.compensatedInventory.inventoryContainer.get(slot).isEmpty());

        this.client.session.get().sendPacket(hold(slot));
        while (!(this.server.await() instanceof MobEquipmentPacket)) {
            // The client's other packets.
        }
        assertEquals(slot, onLoop(session, () -> player.getInventoryAccessor().heldItemSlot()));
    }

    // Has the server say the player uses an item the client never started to use, which Boar answers by releasing the item at the client's next input.
    // The release's head position is that of the input before, at the StartGame position.
    // Returns what the server got up to that input.
    private List<BedrockPacket> releaseFromBoar(Session session, BoarPlayer player) throws Exception {
        Vector3f head = this.startGame.getPlayerPosition();
        this.answerLatency();
        PlayerAuthInputPacket first = authInput();
        first.setPosition(head);
        this.client.session.get().sendPacket(first);
        while (!(this.server.await() instanceof PlayerAuthInputPacket)) {
            // The client's latency answers.
        }

        SetEntityDataPacket using = new SetEntityDataPacket();
        using.setRuntimeEntityId(FakeSpectrumServer.RUNTIME_ID);
        using.getMetadata().setFlag(EntityFlag.USING_ITEM, true);
        this.server.send(using);
        this.server.send(new Flush());
        this.answerLatencyAfter(SetEntityDataPacket.class);
        awaitOnLoop(session, () -> player.getItemUseTracker().getDirtyUsing() == ItemUseTracker.DirtyUsing.METADATA);
        assertEquals(ItemUseTracker.DirtyUsing.METADATA, onLoop(session, () -> player.getItemUseTracker().getDirtyUsing()));

        PlayerAuthInputPacket second = authInput();
        second.setPosition(head.add(0, 0, 0.1F));
        second.setTick(2);
        this.client.session.get().sendPacket(second);
        List<BedrockPacket> toServer = new ArrayList<>();
        for (BedrockPacket packet; !((packet = this.server.await()) instanceof PlayerAuthInputPacket); ) {
            toServer.add(packet);
        }
        // Boar handled the server's use; since the client is not using an item, it called releaseItem first.
        assertEquals(ItemUseTracker.DirtyUsing.NONE, onLoop(session, () -> player.getItemUseTracker().getDirtyUsing()));
        return toServer;
    }

    // The client's MobEquipment when its player picks this hotbar slot, with an empty item in the packet.
    private static MobEquipmentPacket hold(int slot) {
        MobEquipmentPacket equipment = new MobEquipmentPacket();
        equipment.setRuntimeEntityId(FakeSpectrumServer.RUNTIME_ID);
        equipment.setItem(ItemData.AIR);
        equipment.setContainerId(ContainerId.INVENTORY);
        equipment.setInventorySlot(slot);
        equipment.setHotbarSlot(slot);
        return equipment;
    }

    private AddEntityPacket addEntity(long runtimeId, long uniqueId, String identifier) {
        AddEntityPacket add = new AddEntityPacket();
        add.setIdentifier(identifier);
        add.setRuntimeEntityId(runtimeId);
        add.setUniqueEntityId(uniqueId);
        add.setPosition(this.startGame.getPlayerPosition().add(2, 0, 0));
        add.setMotion(Vector3f.ZERO);
        add.setRotation(Vector2f.ZERO);
        return add;
    }

    // A link between the vehicle and the player, by their unique IDs.
    private static SetEntityLinkPacket link(long vehicleUniqueId, EntityLinkData.Type type) {
        SetEntityLinkPacket link = new SetEntityLinkPacket();
        link.setEntityLink(new EntityLinkData(vehicleUniqueId, FakeSpectrumServer.UNIQUE_ID, type, false, false));
        return link;
    }

    // A sub-chunk at Y 0 to 15 with stone at Y 0 and air above, with these runtime IDs.
    private static ByteBuf stoneFloor(int air, int stone) {
        ByteBuf data = Unpooled.buffer();
        // Sub-chunk version 9, one layer, at sub-chunk Y 0
        data.writeByte(9).writeByte(1).writeByte(0);
        // One bit per block, with runtime IDs in the palette
        data.writeByte(1 << 1 | 1);
        // Blocks are indexed x << 8 | z << 4 | y, so in each 32-block word the blocks at Y 0 are bits 0 and 16.
        for (int word = 0; word < 4096 / 32; word++) {
            data.writeIntLE(1 | 1 << 16);
        }
        VarInts.writeInt(data, 2);
        VarInts.writeInt(data, air);
        VarInts.writeInt(data, stone);
        return data;
    }

    // A sub-chunk at Y 0 to 15 with a stone floor at Y 0 and a line of oak fences along X at Y 1 and local Z FENCE_Z.
    private static ByteBuf fenceLine() {
        int fence = BedrockBlockTable.get().defaultIndex("minecraft:oak_fence");
        ByteBuf data = Unpooled.buffer();
        data.writeByte(9).writeByte(1).writeByte(0);
        // Two bits per block, so 16 blocks in each word, at index x << 8 | z << 4 | y: one word is one column of Y 0 to 15.
        data.writeByte(2 << 1 | 1);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                data.writeIntLE(z == FENCE_Z ? 1 | 2 << 2 : 1);
            }
        }
        VarInts.writeInt(data, 3);
        VarInts.writeInt(data, AIR_RUNTIME_ID);
        VarInts.writeInt(data, STONE_RUNTIME_ID);
        VarInts.writeInt(data, fence);
        return data;
    }

    // A sub-chunk at this section Y with these runtime IDs, one array for each layer, by block index x << 8 | z << 4 | y.
    private static ByteBuf subChunk(int sectionY, int[]... layers) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(9).writeByte(layers.length).writeByte(sectionY);
        for (int[] blocks : layers) {
            List<Integer> palette = Arrays.stream(blocks).distinct().boxed().toList();
            // 8 bits per block, so 4 blocks in each word
            data.writeByte(8 << 1 | 1);
            for (int word = 0; word < 4096 / 4; word++) {
                int bits = 0;
                for (int b = 0; b < 4; b++) {
                    bits |= palette.indexOf(blocks[word * 4 + b]) << b * 8;
                }
                data.writeIntLE(bits);
            }
            VarInts.writeInt(data, palette.size());
            palette.forEach(id -> VarInts.writeInt(data, id));
        }
        return data;
    }

    private void sendChunk(int chunkX, int chunkZ, ByteBuf data) throws Exception {
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setChunkX(chunkX);
        chunk.setChunkZ(chunkZ);
        chunk.setDimension(0);
        chunk.setSubChunksLength(1);
        chunk.setData(data);
        this.server.send(chunk);
        this.server.send(new Flush());
    }

    // The palette index, and so the runtime ID without custom blocks, of the state of this block with these values instead
    private static int state(String name, Object... values) {
        BedrockBlockTable table = BedrockBlockTable.get();
        NbtMapBuilder states = table.states(table.firstIndex(name)).toBuilder();
        for (int i = 0; i < values.length; i += 2) {
            states.put((String) values[i], values[i + 1]);
        }
        int index = table.index(name, states.build());
        assertTrue(index >= 0, name + " " + List.of(values));
        return index;
    }

    // Each box's minX, minY, minZ, maxX, maxY, maxZ in a row, since Box does not override equals(Object)
    private static float[] corners(List<Box> boxes) {
        float[] corners = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, corners, i * 6, 6);
        }
        return corners;
    }

    // A stack of stone as the server and a real client send it, with the block it places.
    private static ItemData stones() {
        return ItemData.builder().definition(STONE).count(64).blockDefinition(Definitions.ANY_BLOCK.getDefinition(STONE_RUNTIME_ID)).build();
    }

    // A stack of stone with the stack network ID the server gave it
    private static ItemData stones(int count, int netId) {
        return stones().toBuilder().count(count).usingNetId(true).netId(netId).build();
    }

    // The test client has no item or block definitions of its own, and needs them to read the items the server sends.
    private void readItemsOnTheClient() throws Exception {
        BedrockCodecHelper clientHelper = this.client.session.get().getPeer().getCodecHelper();
        clientHelper.setItemDefinitions(Definitions.items(this.server.items));
        clientHelper.setBlockDefinitions(Definitions.ANY_BLOCK);
    }

    // Waits this long, and fails if the client gets any packet but Boar's latency packets.
    private void assertOnlyLatencyPacketsFor(long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        List<Class<?>> received = new ArrayList<>();
        for (BedrockPacket packet; (packet = this.client.received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
            received.add(packet.getClass());
        }
        assertTrue(received.stream().allMatch(NetworkStackLatencyPacket.class::equals), received.toString());
    }

    // The packets the client gets up to the next NetworkStackLatency.
    private List<Class<?>> receivedUntilAcknowledgment() throws Exception {
        List<Class<?>> received = new ArrayList<>();
        while (!received.contains(NetworkStackLatencyPacket.class)) {
            BedrockPacket packet = this.client.received.poll(10, TimeUnit.SECONDS);
            assertNotNull(packet, received.toString());
            received.add(packet.getClass());
        }
        return received;
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

    private Session spawn() throws Exception {
        return this.spawn(processors);
    }

    private Session spawn(Function<ClientLogin, Processor> processors) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        TcpTransport transport = new TcpTransport(this.group, Duration.ofSeconds(5));
        String addr = this.server.address();
        SessionSettings settings = new SessionSettings(null, Duration.ofMinutes(1), SessionSettings.DEFAULT.latencyInterval(), processors);
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), CODEC, pong, false, Duration.ofMinutes(1), login -> this.session.complete(Session.login(login, transport, CODEC, addr, settings)));
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
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

    // A custom block of 1 state. cubecraft:tiles and cubecraft:lamp sort after stone and air by name hash, so they move neither.
    private static BlockPropertyData plainBlock(String name) {
        return new BlockPropertyData(name, NbtMap.builder().putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    // A custom block with this property, or none, and this base minecraft:collision_box and permutation, each of which may be null
    private static BlockPropertyData customBlock(String name, NbtMap property, NbtMap collisionBox, NbtMap permutation) {
        NbtMapBuilder properties = NbtMap.builder();
        if (property != null) {
            properties.putList("properties", NbtType.COMPOUND, property);
        }
        properties.putCompound("components", collisionBox == null ? NbtMap.EMPTY : NbtMap.builder().putCompound("minecraft:collision_box", collisionBox).build());
        if (permutation != null) {
            properties.putList("permutations", NbtType.COMPOUND, permutation);
        }
        return new BlockPropertyData(name, properties.putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    // A minecraft:collision_box of one box, with corners in 1/16 of a block, as Geyser 2.12 writes it
    private static NbtMap collisionBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        NbtMap box = NbtMap.builder().putFloat("minX", minX).putFloat("minY", minY).putFloat("minZ", minZ).putFloat("maxX", maxX).putFloat("maxY", maxY).putFloat("maxZ", maxZ).build();
        return NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND, box).build();
    }

    // This block as a server that leaves out vanilla_block_data sends it
    private static BlockPropertyData withoutVanillaBlockData(BlockPropertyData block) {
        NbtMapBuilder properties = block.getProperties().toBuilder();
        properties.remove("vanilla_block_data");
        return new BlockPropertyData(block.getName(), properties.build());
    }

    // A block with a property without values, which the client cannot read
    private static BlockPropertyData noValues() {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, List.of()).build();
        return new BlockPropertyData("cubecraft:broken", NbtMap.builder().putList("properties", NbtType.COMPOUND, open).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    // A custom block of 2 states
    private static BlockPropertyData pipe() {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
        return new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("properties", NbtType.COMPOUND, open).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    // Boar's processor, with these custom blocks added to the StartGame the client is sent, and its hashed IDs set
    private record AddsCustomBlocks(Processor boar, boolean hashedIds, List<BlockPropertyData> blocks) implements Processor {
        @Override
        public Set<Integer> clientDecode() {
            return this.boar.clientDecode();
        }

        @Override
        public Set<Integer> serverDecode() {
            return this.boar.serverDecode();
        }

        @Override
        public boolean clientCache() {
            return this.boar.clientCache();
        }

        @Override
        public void startGame(Session session, StartGamePacket startGame) {
            startGame.getBlockProperties().addAll(this.blocks);
            startGame.setBlockNetworkIdsHashed(this.hashedIds);
            this.boar.startGame(session, startGame);
        }

        @Override
        public void spawned(Session session) {
            this.boar.spawned(session);
        }

        @Override
        public void client(Session session, List<PacketContext> batch) {
            this.boar.client(session, batch);
        }

        @Override
        public void server(Session session, PacketContext packet) {
            this.boar.server(session, packet);
        }

        @Override
        public void preTransfer(Session session, String origin, String target) {
            this.boar.preTransfer(session, origin, target);
        }

        @Override
        public void flush(Session session) {
            this.boar.flush(session);
        }

        @Override
        public void disconnect(Session session) {
            this.boar.disconnect(session);
        }
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
