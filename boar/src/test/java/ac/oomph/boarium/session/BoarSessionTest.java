package ac.oomph.boarium.session;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.compensated.CompensatedInventory;
import ac.boar.anticheat.compensated.cache.entity.EntityCache;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.effect.Effect;
import ac.boar.anticheat.data.enchantment.Enchantment;
import ac.boar.anticheat.data.vanilla.StatusEffect;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.VehicleType;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.Blocks;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinitions;
import ac.boar.mappings.entity.EntityTypes;
import ac.boar.mappings.item.Items;
import ac.boar.protocol.PacketEvents;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.boar.blocks.BlockTable;
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
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
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
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(x, 0, z, 0).intermediaryId() != 0);
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

        // A fence in the line joins the fences east and west of it, as oak_fence[east=true,north=false,south=false,waterlogged=false,west=true] (Java state 6979 in Geyser).
        List<Box> expected = BoarBlockState.create(6979, fence, 0).getCollisionBoxes().stream().map(box -> box.offset(fence.getX(), fence.getY(), fence.getZ())).toList();
        List<Box> boxes = onLoop(session, () -> player.compensatedWorld.getBlockState(fence, 0).findCollision(player, fence, null, false));
        assertArrayEquals(corners(expected), corners(boxes));
        this.assertNothingFailed();
    }

    // cubecraft:pipe sorts before stone and air by name hash, so its 2 states move both by 2.
    @Test
    void readsTheBlocksOfAChunkWhenTheServerAddsCustomBlocks() throws Exception {
        Session session = this.spawn(login -> new AddsCustomBlock(processors.apply(login)));
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
        awaitOnLoop(session, () -> player.compensatedWorld.getBlockState(x, 0, z, 0).intermediaryId() != 0);
        BoarBlockState floor = onLoop(session, () -> player.compensatedWorld.getBlockState(x, 0, z, 0));
        assertEquals("minecraft:stone", floor.block().toString());
        assertEquals(stone, floor.definition(player).getRuntimeId());
        assertTrue(onLoop(session, () -> player.compensatedWorld.getBlockState(x, 1, z, 0).isAir()));
        assertEquals(air, player.mappingInfo.airId());
        // Without custom blocks, stone has another ID.
        assertNotEquals(floor.intermediaryId(), player.mappingInfo.toIntermediary().applyAsInt(STONE_RUNTIME_ID));
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
        BlockTable table = BlockTable.get();
        int fence = table.toRuntime(table.defaultState(table.block("minecraft:oak_fence")), false);
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

    // Boar's processor, with a custom block of 2 states added to the StartGame the client is sent
    private record AddsCustomBlock(Processor boar) implements Processor {
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
            NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
            startGame.getBlockProperties().add(new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("properties", NbtType.COMPOUND, open).build()));
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
