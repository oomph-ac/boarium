package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.AbstractBoarBlockState;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.Dimension;
import ac.boar.anticheat.util.geyser.BlockEntityInfo;
import ac.boar.anticheat.util.geyser.BoarChunkSection;
import ac.boar.anticheat.util.math.Box;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected Java state IDs come from Geyser 2.11.3-b1248 (Geyser.jar): its state names, its withValue, and the string replacing that Boar's Geyser adapter does for fences and bars.
// Which sides connect is worked out by hand from GeyserBlockUtil, and the boxes from BedrockCollision.buildThinBarsShape and GeyserBlockUtil.computeWallShape.
class TableConnectionsTest {
    private static final int STONE = 1;
    private static final int OAK_LEAVES = 279;
    private static final int PUMPKIN = 8332;
    private static final int OAK_FENCE = 6965;
    private static final int NETHER_BRICK_FENCE = 9366;
    private static final int OAK_FENCE_GATE_EAST = 8677;
    private static final int OAK_FENCE_GATE_NORTH = 8653;
    private static final int GLASS_PANE = 8331;
    private static final int WALL_POST = 9984;
    private static final int WALL_NO_POST = 9990;
    private static final int STAIRS_EAST_BOTTOM = 3977;
    private static final int STAIRS_NORTH_BOTTOM = 3918;
    private static final int STAIRS_SOUTH_BOTTOM = 3938;
    private static final int STAIRS_NORTH_TOP = 3908;
    private static final int CHEST_SINGLE = 3988;
    private static final int TRAPPED_CHEST = 11207;

    // oak_fence and glass_pane with these sides connected, never waterlogged
    private static final int FENCE_NONE = 6996;
    private static final int FENCE_EAST = 6980;
    private static final int FENCE_NORTH = 6988;
    private static final int FENCE_NORTH_SOUTH = 6984;
    private static final int PANE_NORTH_SOUTH = 8319;
    private static final int PANE_NORTH = 8323;

    private static final Vector3i POS = Vector3i.from(8, 64, 8);

    private static BlockMappingInfo mapping;
    private BoarPlayer player;

    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
        mapping = TablePalette.mappingInfo(new StartGamePacket());
    }

    // A player with one empty overworld chunk at chunk 0, 0
    @BeforeEach
    void world() {
        this.player = new BoarPlayer(new Offline(), null, null, mapping, null, null, null, Map.of(), true);
        this.player.compensatedWorld.setDimension(Dimension.OVERWORLD);
        this.player.compensatedWorld.put(0, 0, new BoarChunkSection[24], new boolean[24]);
    }

    private void set(Vector3i pos, int state) {
        this.player.compensatedWorld.updateBlock(pos, 0, mapping.fromIntermediary().applyAsInt(state));
    }

    private BoarBlockState at(Vector3i pos) {
        return this.player.compensatedWorld.getBlockState(pos, 0);
    }

    private int reshaped(int state) {
        return reshaped(TableBlockState.FACTORY.create(state, POS, 0));
    }

    private int reshaped(BoarBlockState self) {
        return ((AbstractBoarBlockState) self).delegate().applyConnectionShape(this.player, self, POS).intermediaryId();
    }

    private List<Box> override(int state) {
        return ((AbstractBoarBlockState) TableBlockState.FACTORY.create(state, POS, 0)).delegate().connectionCollisionOverride(this.player, POS);
    }

    @Test
    void fenceConnectsToSturdyFacesOnly() {
        assertEquals(FENCE_NONE, this.reshaped(OAK_FENCE));
        this.set(POS.east(), STONE);
        assertEquals(FENCE_EAST, this.reshaped(OAK_FENCE));
    }

    @Test
    void fenceSkipsLeavesAndPumpkinsThoughTheyAreSturdy() {
        assertTrue(TableBlockState.FACTORY.create(OAK_LEAVES, POS, 0).isFaceSturdy(null));
        assertTrue(TableBlockState.FACTORY.create(PUMPKIN, POS, 0).isFaceSturdy(null));
        this.set(POS.north(), OAK_LEAVES);
        this.set(POS.east(), PUMPKIN);
        assertEquals(FENCE_NONE, this.reshaped(OAK_FENCE));
    }

    @Test
    void fenceConnectsToAGateTurnedAcross() {
        this.set(POS.north(), OAK_FENCE_GATE_NORTH);
        assertEquals(FENCE_NONE, this.reshaped(OAK_FENCE));
        this.set(POS.north(), OAK_FENCE_GATE_EAST);
        assertEquals(FENCE_NORTH, this.reshaped(OAK_FENCE));
    }

    @Test
    void fenceConnectsToTheSameKindOfFence() {
        this.set(POS, OAK_FENCE);
        this.set(POS.north(), OAK_FENCE);
        this.set(POS.south(), OAK_FENCE);
        this.set(POS.east(), NETHER_BRICK_FENCE);
        this.set(POS.west(), NETHER_BRICK_FENCE);
        // The fence as Boar reads it from the world, which keeps no connections.
        assertEquals(FENCE_NORTH_SOUTH, this.reshaped(this.at(POS)));
    }

    @Test
    void paneBetweenPanesHasThinBars() {
        this.set(POS, GLASS_PANE);
        this.set(POS.north(), GLASS_PANE);
        this.set(POS.south(), GLASS_PANE);
        assertEquals(PANE_NORTH_SOUTH, this.reshaped(GLASS_PANE));
        float[] bars = {0.4375F, 0, 0, 0.5625F, 1, 0.5F, 0.4375F, 0, 0.5F, 0.5625F, 1, 1};
        assertBoxes(this.override(GLASS_PANE), bars);
        // Boar's own collision uses them, moved to the block.
        float[] moved = bars.clone();
        for (int i = 0; i < moved.length; i++) {
            moved[i] += i % 3 == 1 ? POS.getY() : 8;
        }
        assertBoxes(this.at(POS).findCollision(this.player, POS, null, false), moved);
    }

    @Test
    void paneJoinsAWall() {
        this.set(POS.north(), WALL_POST);
        assertEquals(PANE_NORTH, this.reshaped(GLASS_PANE));
        assertBoxes(this.override(GLASS_PANE), 0.4375F, 0, 0, 0.5625F, 1, 0.5F);
    }

    @Test
    void lonePaneIsAPost() {
        assertBoxes(this.override(GLASS_PANE), 0.4375F, 0, 0.4375F, 0.5625F, 1, 0.5625F);
    }

    @Test
    void wallIsOneBoxOneAndAHalfHigh() {
        this.set(POS.east(), WALL_POST);
        this.set(POS.west(), WALL_POST);
        // Straight with nothing above: no post.
        assertBoxes(this.override(WALL_POST), 0, 0, 0.3125F, 1, 1.5F, 0.6875F);
        this.set(POS.up(), STONE);
        assertBoxes(this.override(WALL_POST), 0, 0, 0.25F, 1, 1.5F, 0.75F);
        this.set(POS.up(), WALL_POST);
        assertEquals("true", BlockTable.get().value(this.at(POS.up()).intermediaryId(), "up"));
        assertBoxes(this.override(WALL_POST), 0, 0, 0.25F, 1, 1.5F, 0.75F);
        this.set(POS.up(), WALL_NO_POST);
        assertEquals("false", BlockTable.get().value(this.at(POS.up()).intermediaryId(), "up"));
        assertBoxes(this.override(WALL_POST), 0, 0, 0.3125F, 1, 1.5F, 0.6875F);
    }

    @Test
    void wallWithOneSideHasAPost() {
        this.set(POS.north(), STONE);
        assertBoxes(this.override(WALL_POST), 0.25F, 0, 0, 0.75F, 1.5F, 0.75F);
    }

    @Test
    void wallJoinsAGateTurnedAcross() {
        this.set(POS.north(), OAK_FENCE_GATE_NORTH);
        assertBoxes(this.override(WALL_POST), 0.25F, 0, 0.25F, 0.75F, 1.5F, 0.75F);
        this.set(POS.north(), OAK_FENCE_GATE_EAST);
        assertBoxes(this.override(WALL_POST), 0.25F, 0, 0, 0.75F, 1.5F, 0.75F);
    }

    @Test
    void otherBlocksHaveNoOverride() {
        assertNull(this.override(OAK_FENCE));
        assertNull(this.override(STONE));
    }

    @Test
    void stairsAheadMakeAnOuterCorner() {
        this.set(POS.east(), STAIRS_NORTH_BOTTOM);
        assertEquals(3983, this.reshaped(STAIRS_EAST_BOTTOM));
        this.set(POS.east(), STAIRS_SOUTH_BOTTOM);
        assertEquals(3985, this.reshaped(STAIRS_EAST_BOTTOM));
    }

    @Test
    void stairsBehindMakeAnInnerCorner() {
        this.set(POS.west(), STAIRS_NORTH_BOTTOM);
        assertEquals(3979, this.reshaped(STAIRS_EAST_BOTTOM));
        this.set(POS.west(), STAIRS_SOUTH_BOTTOM);
        assertEquals(3981, this.reshaped(STAIRS_EAST_BOTTOM));
    }

    @Test
    void stairsOfTheOtherHalfStayStraight() {
        this.set(POS.east(), STAIRS_NORTH_TOP);
        BoarBlockState self = TableBlockState.FACTORY.create(STAIRS_EAST_BOTTOM, POS, 0);
        assertSame(self, ((AbstractBoarBlockState) self).delegate().applyConnectionShape(this.player, self, POS));
    }

    // The stairs ahead would make an outer corner, but the stairs on that corner's side face the same way and are of the same half.
    @Test
    void stairsBesideOfTheSameWayStayStraight() {
        this.set(POS.east(), STAIRS_NORTH_BOTTOM);
        this.set(POS.south(), STAIRS_EAST_BOTTOM);
        assertEquals(STAIRS_EAST_BOTTOM, this.reshaped(STAIRS_EAST_BOTTOM));
    }

    @Test
    void chestHalfComesFromItsBlockEntity() {
        assertEquals(CHEST_SINGLE, this.reshaped(CHEST_SINGLE));
        this.set(POS.east(), CHEST_SINGLE);
        this.pair(POS.getX() + 1, POS.getZ(), 1);
        assertEquals(3992, this.reshaped(CHEST_SINGLE));
        this.pair(POS.getX() + 1, POS.getZ(), 0);
        assertEquals(3990, this.reshaped(CHEST_SINGLE));
        // Trapped chests too, but a pair of different chests is two single chests.
        this.set(POS.east(), TRAPPED_CHEST);
        assertEquals(CHEST_SINGLE, this.reshaped(CHEST_SINGLE));
        this.pair(POS.getX() + 1, POS.getZ(), 1);
        assertEquals(11211, this.reshaped(TRAPPED_CHEST));
        // Too far away to pair
        this.pair(POS.getX() + 2, POS.getZ(), 1);
        assertEquals(TRAPPED_CHEST, this.reshaped(TRAPPED_CHEST));
    }

    // Geyser's adapter pairs only chests and trapped chests.
    @Test
    void copperChestStaysSingle() {
        BlockTable table = BlockTable.get();
        int copper = table.firstState(table.block("minecraft:copper_chest"));
        this.set(POS.east(), copper);
        this.pair(POS.getX() + 1, POS.getZ(), 1);
        assertEquals(copper, this.reshaped(copper));
    }

    private void pair(int x, int z, int lead) {
        List<BlockEntityInfo> entities = this.player.compensatedWorld.getChunk(0, 0).blockEntities();
        entities.clear();
        entities.add(new BlockEntityInfo(POS.getX(), POS.getY(), POS.getZ(), NbtMap.builder().putInt("pairx", x).putInt("pairz", z).putInt("pairlead", lead).build()));
    }

    // Boar's collision asks the session only which random offsets the platform uses.
    private record Offline() implements NetworkSession {
        @Override
        public String name() {
            return "Tester";
        }

        @Override
        public UUID uuid() {
            return new UUID(0, 0);
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public boolean requiresPingMagnitude() {
            return false;
        }

        @Override
        public int protocolVersion() {
            return 0;
        }
    }

    private static void assertBoxes(List<Box> boxes, float... expected) {
        float[] actual = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, actual, i * 6, 6);
        }
        assertArrayEquals(expected, actual);
    }
}
