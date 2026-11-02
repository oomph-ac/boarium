package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.block.VanillaBoarBlockState;
import ac.boar.anticheat.data.block.impl.BedBlockState;
import ac.boar.anticheat.data.block.impl.HoneyBlockState;
import ac.boar.anticheat.data.block.impl.SlimeBlockState;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Direction;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.Blocks;
import ac.boar.mappings.block.ChestType;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.boar.mappings.block.Properties;
import ac.boar.mappings.block.Property;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Java state IDs and runtime IDs as in BlockTableTest, from Geyser 2.11.3-b1248 and the 26_30 palette.
class TableBlocksTest {
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 86;
    private static final int SHORT_GRASS = 2248;
    private static final int SEAGRASS = 2254;
    private static final int OAK_STAIRS_FIRST = 3907;
    private static final int SNOW_1 = 6919;
    private static final int SNOW_2 = 6920;
    private static final int OAK_SLAB_BOTTOM = 13333;
    private static final int WATER_CAULDRON_1 = 9461;
    private static final int OAK_DOOR_UPPER = 5658;

    private static final int SORTED_AIR = 13094;
    private static final int SORTED_WATER = 9808;
    private static final int SORTED_LAVA = 5585;
    private static final int SORTED_STONE = 2706;
    private static final int HASHED_AIR = -604749536;
    private static final int HASHED_STONE = -2144268767;

    private static BlockTable table;

    // Boar's Properties and BoarBlockState.create need Boar started.
    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
        table = BlockTable.get();
    }

    private static BoarBlockState state(int id) {
        return TableBlockState.FACTORY.create(id, Vector3i.ZERO, 0);
    }

    private static int first(String name) {
        return table.firstState(table.block(name));
    }

    private static Block block(String name) {
        return TableBlock.of(table.block(name));
    }

    @Test
    void everyPropertyReadsItsBlock() {
        assertEquals(0, state(first("minecraft:bamboo")).get(Properties.AGE_1));
        assertEquals(0, state(first("minecraft:sweet_berry_bush")).get(Properties.AGE_3));
        assertEquals("floor", state(first("minecraft:bell")).get(Properties.BELL_ATTACHMENT));
        assertEquals(0, state(first("minecraft:cake")).get(Properties.BITES));
        assertEquals(ChestType.SINGLE, state(first("minecraft:chest")).get(Properties.CHEST_TYPE));
        assertEquals("left", state(first("minecraft:oak_door")).get(Properties.DOOR_HINGE));
        assertEquals(true, state(first("minecraft:bubble_column")).get(Properties.DRAG));
        assertEquals("tip_merge", state(first("minecraft:pointed_dripstone")).get(Properties.DRIPSTONE_THICKNESS));
        assertEquals("top", state(OAK_STAIRS_FIRST).get(Properties.HALF));
        assertEquals(true, state(first("minecraft:lantern")).get(Properties.HANGING));
        assertEquals(true, state(first("minecraft:lectern")).get(Properties.HAS_BOOK));
        assertEquals(Direction.NORTH, state(OAK_STAIRS_FIRST).get(Properties.HORIZONTAL_FACING));
        assertEquals(0, state(WATER).get(Properties.LEVEL));
        assertEquals(true, state(first("minecraft:furnace")).get(Properties.LIT));
        assertEquals(true, state(first("minecraft:oak_trapdoor")).get(Properties.OPEN));
        assertEquals(0, state(first("minecraft:respawn_anchor")).get(Properties.RESPAWN_ANCHOR_CHARGES));
        assertEquals("bottom", state(OAK_SLAB_BOTTOM).get(Properties.SLAB_TYPE));
        assertEquals("inactive", state(first("minecraft:vault")).get(Properties.VAULT_STATE));
        assertEquals(Direction.UP, state(first("minecraft:pointed_dripstone")).get(Properties.VERTICAL_DIRECTION));
        assertEquals(true, state(OAK_STAIRS_FIRST).get(Properties.WATERLOGGED));

        assertNull(state(STONE).get(Properties.WATERLOGGED));
        assertThrows(IllegalArgumentException.class, () -> TableProperties.INSTANCE.get("shape"));
    }

    @Test
    void getAndWithRoundTrip() {
        BoarBlockState east = state(OAK_STAIRS_FIRST).with(Properties.HORIZONTAL_FACING, Direction.EAST);
        assertEquals("east", table.value(east.intermediaryId(), "facing"));
        assertEquals(Direction.EAST, east.get(Properties.HORIZONTAL_FACING));
        assertEquals(OAK_STAIRS_FIRST, east.with(Properties.HORIZONTAL_FACING, Direction.NORTH).intermediaryId());

        assertEquals(13335, state(OAK_SLAB_BOTTOM).with(Properties.SLAB_TYPE, "double").intermediaryId());

        // A door's half is upper or lower, which is not Boar's half (top or bottom).
        BoarBlockState door = state(OAK_DOOR_UPPER);
        assertNull(door.get(Properties.HALF));
        assertThrows(IllegalArgumentException.class, () -> door.with(Properties.HALF, "top"));
        BoarBlockState right = door.with(Properties.DOOR_HINGE, "right");
        assertEquals("right", right.get(Properties.DOOR_HINGE));
        assertEquals("upper", table.value(right.intermediaryId(), "half"));
        assertEquals(OAK_DOOR_UPPER, right.with(Properties.DOOR_HINGE, "left").intermediaryId());

        BoarBlockState left = state(first("minecraft:chest")).with(Properties.CHEST_TYPE, ChestType.LEFT);
        assertEquals(ChestType.LEFT, left.get(Properties.CHEST_TYPE));
        assertEquals("left", table.value(left.intermediaryId(), "type"));

        BoarBlockState flowing = state(WATER).with(Properties.LEVEL, 8);
        assertEquals(8, flowing.get(Properties.LEVEL));
        assertEquals(WATER, flowing.with(Properties.LEVEL, 0).intermediaryId());

        // A cauldron's level runs 1 to 3, so it is not Boar's level, as in Geyser.
        assertNull(state(WATER_CAULDRON_1).get(Properties.LEVEL));
        assertNull(state(first("minecraft:cauldron")).get(Properties.LEVEL));
    }

    @Test
    void collisionAndFaceSturdy() {
        assertBoxes(state(STONE).getCollisionBoxes(), 0, 0, 0, 1, 1, 1);
        assertTrue(state(STONE).isFaceSturdy(null));
        assertTrue(state(AIR).getCollisionBoxes().isEmpty());
        assertFalse(state(AIR).isFaceSturdy(null));
        assertBoxes(state(OAK_SLAB_BOTTOM).getCollisionBoxes(), 0, 0, 0, 1, 0.5F, 1);
        assertFalse(state(OAK_SLAB_BOTTOM).isFaceSturdy(null));
        // Upside down, facing north, in Geyser's two boxes: the north half at full height, then the upper south quarter.
        assertBoxes(state(OAK_STAIRS_FIRST).getCollisionBoxes(), 0, 0, 0, 1, 1, 0.5F, 0, 0.5F, 0.5F, 1, 1, 1);
        assertFalse(state(OAK_STAIRS_FIRST).isFaceSturdy(null));
    }

    private static void assertBoxes(List<Box> boxes, float... expected) {
        float[] actual = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, actual, i * 6, 6);
        }
        assertArrayEquals(expected, actual);
    }

    @Test
    void waterloggedAndReplaceable() {
        assertTrue(state(SEAGRASS).isWaterlogged());
        assertFalse(state(STONE).isWaterlogged());
        assertTrue(state(SHORT_GRASS).isReplaceable(null));
        assertTrue(state(SNOW_1).isReplaceable(null));
        assertFalse(state(SNOW_2).isReplaceable(null));
        assertFalse(state(STONE).isReplaceable(null));
    }

    @Test
    void blocks() {
        Block stone = state(STONE).block();
        assertEquals(block("minecraft:stone"), stone);
        assertTrue(state(STONE).is(stone));
        assertFalse(state(AIR).is(stone));
        assertEquals(1.5F, stone.destroyTime());
        assertEquals(5327, block("minecraft:furnace").defaultBlockState(Vector3i.ZERO, 0).intermediaryId());
        assertEquals(block("minecraft:water"), Blocks.WATER.find().orElseThrow());
        assertTrue(stone.is(Reference.direct(stone)));
        // Java has no such block.
        assertEquals(-1, table.block("minecraft:invisible_bedrock"));
        assertTrue(Blocks.INVISIBLE_BEDROCK.find().isEmpty());
    }

    @Test
    void stateClassesByName() {
        assertInstanceOf(HoneyBlockState.class, state(first("minecraft:honey_block")));
        assertInstanceOf(SlimeBlockState.class, state(first("minecraft:slime_block")));
        assertInstanceOf(BedBlockState.class, state(first("minecraft:red_bed")));
        assertSame(VanillaBoarBlockState.class, state(STONE).getClass());
        // Like Geyser, an ID the table does not have is air.
        assertEquals(AIR, state(table.stateCount()).intermediaryId());
        assertEquals(AIR, state(-1).intermediaryId());
    }

    @Test
    void categories() {
        BlockMappings mappings = new TableBlockMappings();
        assertTrue(mappings.getFenceBlocks().contains(block("minecraft:oak_fence")));
        assertTrue(mappings.getWallBlocks().contains(block("minecraft:cobblestone_wall")));
        assertTrue(mappings.getStairsBlocks().contains(block("minecraft:oak_stairs")));
        assertTrue(mappings.getLeavesBlocks().contains(block("minecraft:oak_leaves")));
        assertTrue(mappings.getBedBlocks().contains(block("minecraft:red_bed")));
        assertTrue(mappings.getDoorBlocks().contains(block("minecraft:oak_door")));
        assertFalse(mappings.getFenceBlocks().contains(block("minecraft:oak_fence_gate")));
        assertTrue(mappings.getFenceGateBlocks().contains(block("minecraft:oak_fence_gate")));
        assertTrue(mappings.getInteractiveBlocks().contains(block("minecraft:oak_door")));
        assertTrue(mappings.getInteractiveBlocks().contains(block("minecraft:crafting_table")));
        assertFalse(mappings.getInteractiveBlocks().contains(block("minecraft:stone")));
        assertTrue(mappings.getItemToBlock().isEmpty());
    }

    @Test
    void sortedMapping() {
        BlockMappingInfo info = TablePalette.mappingInfo(new StartGamePacket());
        assertEquals(SORTED_AIR, info.airId());
        assertEquals(SORTED_WATER, info.waterId());
        assertEquals(SORTED_LAVA, info.lavaDefinition().getRuntimeId());
        assertEquals("minecraft:powder_snow", ((SimpleBlockDefinition) info.powderSnowDefinition()).getIdentifier());
        // Cave air and void air are air on Bedrock.
        assertEquals(List.of(SORTED_AIR, SORTED_AIR, SORTED_AIR), info.airIds());
        assertEquals(STONE, info.toIntermediary().applyAsInt(SORTED_STONE));
        assertEquals(SORTED_STONE, info.fromIntermediary().applyAsInt(STONE));
        assertRoundTrips(info);
    }

    @Test
    void hashedMapping() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(true);
        BlockMappingInfo info = TablePalette.mappingInfo(startGame);
        assertEquals(HASHED_AIR, info.airId());
        assertEquals(table.networkHash(SORTED_WATER), info.waterId());
        assertEquals(List.of(HASHED_AIR, HASHED_AIR, HASHED_AIR), info.airIds());
        assertEquals(STONE, info.toIntermediary().applyAsInt(HASHED_STONE));
        assertEquals(HASHED_STONE, info.fromIntermediary().applyAsInt(STONE));
        // A sorted ID means nothing here.
        assertEquals(AIR, info.toIntermediary().applyAsInt(SORTED_STONE));
        assertRoundTrips(info);
        assertNotEquals(TablePalette.mappingInfo(new StartGamePacket()), info);
    }

    private static void assertRoundTrips(BlockMappingInfo info) {
        for (int state = 0; state < table.stateCount(); state += 97) {
            int runtimeId = info.fromIntermediary().applyAsInt(state);
            assertEquals(runtimeId, info.fromIntermediary().applyAsInt(info.toIntermediary().applyAsInt(runtimeId)));
            assertTrue(info.isKnownRawId(runtimeId));
        }
    }

    @Test
    void definitionsAndMaterials() {
        for (boolean hashed : new boolean[]{false, true}) {
            TablePalette palette = TablePalette.of(hashed);
            StartGamePacket startGame = new StartGamePacket();
            startGame.setBlockNetworkIdsHashed(hashed);
            BlockMappingInfo info = TablePalette.mappingInfo(startGame);
            SimpleBlockDefinition lava = (SimpleBlockDefinition) palette.definition(table.defaultState(table.block("minecraft:lava")));
            assertEquals("minecraft:lava", lava.getIdentifier());
            assertEquals(NbtMap.builder().putInt("liquid_depth", 0).build(), lava.getState().getCompound("states"));

            int frame = -1;
            for (int i = 0; i < table.paletteSize() && frame < 0; i++) {
                frame = table.paletteName(i).equals("minecraft:frame") ? i : -1;
            }
            assertTrue(info.isItemFrame(new SimpleBlockDefinition("minecraft:frame", table.runtimeId(frame, hashed), NbtMap.EMPTY)));
            assertFalse(info.isItemFrame(palette.definition(STONE)));

            // Boar's data has every block of the 1.26.30 palette.
            for (int i = 0; i < table.paletteSize(); i++) {
                assertTrue(info.fallingFlowMaterial().apply(table.runtimeId(i, hashed)).isKnown(), table.paletteName(i));
            }
            assertEquals(FallingFlowMaterial.NOT_SEARCHED, info.fallingFlowMaterial().apply(12345678));

            assertEquals(HorseJumpMaterial.HONEY, info.horseJumpMaterial().apply(palette.runtimeId(first("minecraft:honey_block"))));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(palette.runtimeId(STONE)));
            assertEquals(HorseJumpMaterial.UNKNOWN, info.horseJumpMaterial().apply(12345678));
            assertFalse(info.isKnownRawId(12345678));
        }
    }

    @Test
    void customBlocksHaveNoMapping() {
        StartGamePacket custom = new StartGamePacket();
        custom.getBlockProperties().add(new BlockPropertyData("cubecraft:custom_block", NbtMap.EMPTY));
        assertNull(TablePalette.mappingInfo(custom));

        // A block the palette has, such as a data-driven vanilla block, is not custom.
        StartGamePacket vanilla = new StartGamePacket();
        vanilla.getBlockProperties().add(new BlockPropertyData("minecraft:stone", NbtMap.EMPTY));
        assertSame(TablePalette.mappingInfo(new StartGamePacket()), TablePalette.mappingInfo(vanilla));
    }

    @Test
    void propertiesAreBoundOnce() {
        Property<String> half = TableProperties.INSTANCE.get("half");
        assertSame(Properties.HALF, half);
    }
}
