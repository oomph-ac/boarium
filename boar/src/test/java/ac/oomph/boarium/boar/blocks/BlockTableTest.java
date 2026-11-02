package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected IDs and values were read from Geyser 2.11.3-b1248 (geyser-sim-run/Geyser.jar): BLOCK_STATES, Block and the 26_30 palette.
class BlockTableTest {
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 86;
    private static final int LAVA = 102;
    private static final int SEAGRASS = 2254;
    private static final int SHORT_GRASS = 2248;
    private static final int OAK_STAIRS_FIRST = 3907;
    private static final int CHEST_SINGLE = 3988;
    private static final int SNOW_1 = 6919;
    private static final int SNOW_2 = 6920;
    private static final int COBBLESTONE_WALL_UP = 10116;
    private static final int COBBLESTONE_WALL_NOT_UP = 10122;
    private static final int OAK_SLAB_BOTTOM = 13333;
    private static final int SHULKER_BOX_UP = 14868;

    private static BlockTable table;

    @BeforeAll
    static void load() {
        table = BlockTable.load();
    }

    @Test
    void counts() {
        assertEquals(1001, table.protocol());
        assertEquals(32366, table.stateCount());
        assertEquals(1196, table.blockCount());
        assertEquals(16913, table.paletteSize());
        assertEquals(1022, table.itemBlocks().size());
        assertEquals("2.11.3-b1248 (git-master-751d2aa)", table.geyserBuild());
        assertSame(BlockTable.get(), BlockTable.get());
    }

    @Test
    void blockWithoutProperties() {
        assertEquals(0, table.propertyNames(table.blockOf(STONE)).length);
        assertNull(table.value(STONE, "facing"));
        assertEquals(-1, table.with(STONE, "facing", "north"));
    }

    @Test
    void integerProperty() {
        int snow = table.blockOf(SNOW_1);
        assertArrayEquals(new String[]{"1", "2", "3", "4", "5", "6", "7", "8"}, table.propertyValues(snow, "layers"));
        assertEquals("1", table.value(SNOW_1, "layers"));
        assertEquals(SNOW_1 + 7, table.with(SNOW_1, "layers", "8"));
        assertEquals(SNOW_2, table.with(SNOW_1 + 7, "layers", "2"));
        assertEquals(-1, table.with(SNOW_1, "layers", "9"));
    }

    @Test
    void propertiesThatShareAName() {
        int hopperFacing = table.property("facing", "down", "north", "south", "west", "east");
        int stairsFacing = table.property("facing", "north", "south", "west", "east");
        assertTrue(hopperFacing >= 0);
        assertTrue(stairsFacing >= 0);
        assertEquals(-1, table.property("facing", "east", "west"));
        int hopper = table.firstState(table.block("minecraft:hopper"));
        assertEquals(11313, hopper);
        assertEquals("down", table.value(hopper, hopperFacing));
        assertEquals(11317, table.with(hopper, hopperFacing, "east"));
        assertNull(table.value(hopper, stairsFacing));
        assertEquals(-1, table.with(hopper, stairsFacing, "north"));
        assertEquals(6, table.propertyValues(table.block("minecraft:piston"), "facing").length);

        int doorHalf = table.property("half", "upper", "lower");
        int stairsHalf = table.property("half", "top", "bottom");
        assertEquals("upper", table.value(5658, doorHalf));
        assertEquals(5666, table.with(5658, doorHalf, "lower"));
        assertNull(table.value(5658, stairsHalf));
        assertEquals("top", table.value(OAK_STAIRS_FIRST, stairsHalf));
        assertNull(table.value(OAK_STAIRS_FIRST, doorHalf));

        int fluidLevel = table.property("level", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
        int cauldronLevel = table.property("level", "1", "2", "3");
        int waterCauldron = 9461;
        assertEquals("0", table.value(WATER, fluidLevel));
        assertNull(table.value(WATER, cauldronLevel));
        assertEquals("1", table.value(waterCauldron, cauldronLevel));
        assertEquals(9463, table.with(waterCauldron, cauldronLevel, "3"));
        assertNull(table.value(waterCauldron, fluidLevel));
        assertNull(table.value(table.firstState(table.block("minecraft:lava_cauldron")), cauldronLevel));
    }

    @Test
    void slabType() {
        int slab = table.blockOf(OAK_SLAB_BOTTOM);
        assertArrayEquals(new String[]{"top", "bottom", "double"}, table.propertyValues(slab, "type"));
        assertEquals("bottom", table.value(OAK_SLAB_BOTTOM, "type"));
        assertEquals(13335, table.with(OAK_SLAB_BOTTOM, "type", "double"));
        assertEquals(13331, table.with(OAK_SLAB_BOTTOM, "type", "top"));
    }

    @Test
    void badDataFails() throws IOException {
        byte[] gzip;
        try (InputStream in = BlockTable.class.getResourceAsStream(BlockTable.RESOURCE)) {
            gzip = in.readAllBytes();
        }
        byte[] data;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            data = in.readAllBytes();
        }

        byte[] badMagic = data.clone();
        badMagic[0] ^= 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(badMagic);
        }
        assertThrows(IOException.class, () -> BlockTable.load(new ByteArrayInputStream(out.toByteArray())));

        // The table ends with the block index of its last block item.
        byte[] missingBlock = data.clone();
        missingBlock[missingBlock.length - 2] = (byte) 0xFF;
        missingBlock[missingBlock.length - 1] = (byte) 0xFF;
        ByteArrayOutputStream missingBlockOut = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(missingBlockOut)) {
            zip.write(missingBlock);
        }
        assertThrows(IOException.class, () -> BlockTable.load(new ByteArrayInputStream(missingBlockOut.toByteArray())));

        assertThrows(IOException.class, () -> BlockTable.load(new ByteArrayInputStream(Arrays.copyOf(gzip, gzip.length / 2))));

        // The gzip trailer is the CRC-32, then the length.
        byte[] badCrc = gzip.clone();
        badCrc[badCrc.length - 8] ^= 1;
        assertThrows(IOException.class, () -> BlockTable.load(new ByteArrayInputStream(badCrc)));
    }

    @Test
    void stoneIsAFullSturdyCube() {
        assertEquals("minecraft:stone", table.name(STONE));
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, table.collision(STONE));
        assertTrue(table.isFaceSturdy(STONE));
        assertFalse(table.isReplaceable(STONE));
        assertEquals(1.5f, table.destroyTime(table.blockOf(STONE)));
        assertEquals(50f, table.destroyTime(table.block("minecraft:obsidian")));
    }

    @Test
    void airHasNoBoxes() {
        assertEquals("minecraft:air", table.name(AIR));
        assertEquals(0, table.collision(AIR).length);
        assertFalse(table.isFaceSturdy(AIR));
        assertTrue(table.isReplaceable(AIR));
    }

    @Test
    void shapes() {
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5f, 1}, table.collision(OAK_SLAB_BOTTOM));
        assertFalse(table.isFaceSturdy(OAK_SLAB_BOTTOM));
        // Geyser's SolidCollision makes shulker boxes a full cube.
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, table.collision(SHULKER_BOX_UP));
        assertTrue(table.isFaceSturdy(SHULKER_BOX_UP));
    }

    @Test
    void oakStairsProperties() {
        int block = table.block("minecraft:oak_stairs");
        assertEquals(OAK_STAIRS_FIRST, table.firstState(block));
        assertEquals(OAK_STAIRS_FIRST, table.defaultState(block));
        assertArrayEquals(new String[]{"facing", "half", "shape", "waterlogged"}, table.propertyNames(block));
        assertArrayEquals(new String[]{"north", "south", "west", "east"}, table.propertyValues(block, "facing"));
        assertEquals("north", table.value(OAK_STAIRS_FIRST, "facing"));
        assertEquals("top", table.value(OAK_STAIRS_FIRST, "half"));
        assertEquals("true", table.value(OAK_STAIRS_FIRST, "waterlogged"));
        assertTrue((table.categories(block) & BlockTable.STAIRS) != 0);

        int east = table.with(3964, "facing", "east");
        assertEquals(3984, east);
        assertEquals("outer_left", table.value(east, "shape"));
        assertEquals(3964, table.with(east, "facing", "west"));
        assertEquals(-1, table.with(east, "facing", "up"));
        assertEquals(-1, table.with(east, "lit", "true"));
    }

    @Test
    void wallUsesTheProductStride() {
        // Geyser's withValue gives 10121 here (waterlogged=true, west=tall).
        assertEquals(COBBLESTONE_WALL_NOT_UP, table.with(COBBLESTONE_WALL_UP, "up", "false"));
        assertEquals(COBBLESTONE_WALL_UP, table.with(COBBLESTONE_WALL_NOT_UP, "up", "true"));
        assertTrue((table.categories(table.blockOf(COBBLESTONE_WALL_UP)) & BlockTable.WALL) != 0);
    }

    @Test
    void withMatchesValueEverywhere() {
        for (int state = 0; state < table.stateCount(); state++) {
            int block = table.blockOf(state);
            for (String property : table.propertyNames(block)) {
                for (String value : table.propertyValues(block, property)) {
                    int changed = table.with(state, property, value);
                    assertEquals(block, table.blockOf(changed));
                    for (String other : table.propertyNames(block)) {
                        assertEquals(other.equals(property) ? value : table.value(state, other), table.value(changed, other));
                    }
                }
            }
        }
    }

    @Test
    void flags() {
        assertTrue(table.isWaterlogged(OAK_STAIRS_FIRST));
        assertFalse(table.isWaterlogged(table.with(OAK_STAIRS_FIRST, "waterlogged", "false")));
        assertTrue(table.isWaterlogged(SEAGRASS));
        assertFalse(table.isWaterlogged(STONE));

        assertTrue(table.isReplaceable(SHORT_GRASS));
        assertTrue(table.isReplaceable(SNOW_1));
        assertFalse(table.isReplaceable(SNOW_2));
    }

    @Test
    void categories() {
        assertTrue((table.categories(table.blockOf(CHEST_SINGLE)) & BlockTable.CHEST) != 0);
        assertTrue((table.categories(table.block("minecraft:oak_fence")) & BlockTable.FENCE) != 0);
        assertTrue((table.categories(table.block("minecraft:oak_door")) & BlockTable.DOOR) != 0);
        assertTrue((table.categories(table.block("minecraft:ladder")) & BlockTable.CLIMBABLE) != 0);
        assertTrue((table.categories(table.block("minecraft:red_bed")) & BlockTable.BED) != 0);
        assertEquals(0, table.categories(table.blockOf(STONE)));
    }

    @Test
    void defaultState() {
        assertEquals(5327, table.defaultState(table.block("minecraft:furnace")));
    }

    @Test
    void runtimeIds() {
        assertEquals(13094, table.toRuntime(AIR, false));
        assertEquals(9808, table.toRuntime(WATER, false));
        assertEquals(5585, table.toRuntime(LAVA, false));
        assertEquals(2706, table.toRuntime(STONE, false));
        assertEquals("minecraft:lava", table.paletteName(5585));
        assertEquals(NbtMap.builder().putInt("liquid_depth", 0).build(), table.paletteStates(5585));
        assertEquals(NbtMap.builder().putByte("upside_down_bit", (byte) 0).putInt("weirdo_direction", 0).build(), table.paletteStates(1520));
    }

    @Test
    void hashedRuntimeIds() {
        assertEquals(-2144268767, table.networkHash(2706));
        assertEquals(-604749536, table.toRuntime(AIR, true));
        assertEquals(STONE, table.toJava(-2144268767, true));
        assertEquals(2706, table.paletteIndex(-2144268767, true));
        assertEquals(-1, table.paletteIndex(12345, true));
        assertEquals(AIR, table.toJava(12345, true));
    }

    @Test
    void javaToRuntimeRoundTrip() {
        assertEquals(STONE, table.toJava(table.toRuntime(STONE, false), false));
        for (boolean hashed : new boolean[]{false, true}) {
            for (int state = 0; state < table.stateCount(); state++) {
                int runtimeId = table.toRuntime(state, hashed);
                int back = table.toJava(runtimeId, hashed);
                assertTrue(back >= state);
                assertEquals(runtimeId, table.toRuntime(back, hashed));
            }
        }
    }
}
