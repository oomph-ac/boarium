package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected indices and values were read with a separate script from the vendored blockdata/data/bedrock/1.26.30/block_states.json.gz and block_palette.nbt.
class BedrockBlockTableTest {
    private static final Path DUMP = Path.of("../blockdata/data/bedrock/1.26.30/block_states.json.gz");

    private static final int TRAPDOOR_TOP = 1286;
    private static final int PITCHER_CROP_LOWER = 1315;
    private static final int STONE = 2706;
    private static final int UNKNOWN = 7361;
    private static final int BORDER_BLOCK = 8713;
    private static final int AIR = 13094;
    private static final int BED = 13095;
    private static final int LECTERN = 13559;
    private static final int FLOWING_LAVA = 14549;
    private static final int CAULDRON = 15044;
    private static final int SMOOTH_STONE_SLAB_BOTTOM = 15124;
    private static final int POINTED_DRIPSTONE_MIDDLE = 14962;
    private static final int TURTLE_EGG = 15873;

    private static BedrockBlockTable table;

    @BeforeAll
    static void load() {
        table = BedrockBlockTable.load();
    }

    @Test
    void header() {
        assertEquals(16913, table.paletteSize());
        assertEquals(1001, table.protocol());
        assertEquals("1.26.33.1", table.sourceVersion());
        assertEquals(18168865, table.blockVersion());
        assertSame(BedrockBlockTable.get(), BedrockBlockTable.get());
    }

    @Test
    void everyIndexHasData() {
        for (int i = 0; i < table.paletteSize(); i++) {
            String name = table.name(i);
            NbtMap states = table.states(i);
            assertTrue(name.startsWith("minecraft:"), name);
            assertEquals(0, table.collision(i).length % 6);
            assertTrue(Float.isFinite(table.hardness(i)) && Float.isFinite(table.friction(i)), name);
            assertEquals(name.equals("minecraft:unknown") ? -2 : CustomBlock.networkHash(name, states), table.networkHash(i));
            assertEquals(i, table.indexOfHash(table.networkHash(i)));
            assertEquals(i, table.index(name, states));
        }
    }

    @Test
    void stoneIsAFullSolidCube() {
        assertEquals(STONE, table.index("minecraft:stone", NbtMap.EMPTY));
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, table.collision(STONE));
        assertTrue(table.isSolid(STONE));
        assertFalse(table.canContainLiquidSource(STONE));
        assertEquals(1.5f, table.hardness(STONE));
        assertEquals(0.6f, table.friction(STONE));
    }

    @Test
    void airHasNoBoxes() {
        assertEquals(AIR, table.index("minecraft:air", NbtMap.EMPTY));
        assertEquals(0, table.collision(AIR).length);
        assertFalse(table.isSolid(AIR));
        assertTrue(table.canContainLiquidSource(AIR));
        assertEquals(-1f, table.hardness(AIR));
        assertEquals(0.9f, table.friction(AIR));
    }

    @Test
    void shapes() {
        assertEquals("minecraft:bed", table.name(BED));
        assertEquals(NbtMap.builder().putInt("direction", 0).putByte("head_piece_bit", (byte) 0).putByte("occupied_bit", (byte) 0).build(), table.states(BED));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5625f, 1}, table.collision(BED));

        assertEquals("minecraft:lectern", table.name(LECTERN));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.9f, 1}, table.collision(LECTERN));

        assertEquals("minecraft:cauldron", table.name(CAULDRON));
        assertArrayEquals(new float[]{
                0, 0, 0, 1, 0.3125f, 1,
                0, 0, 0, 0.125f, 1, 1,
                0, 0, 0, 1, 1, 0.125f,
                0.875f, 0, 0, 1, 1, 1,
                0, 0, 0.875f, 1, 1, 1}, table.collision(CAULDRON));

        assertEquals(SMOOTH_STONE_SLAB_BOTTOM, table.index("minecraft:smooth_stone_slab", NbtMap.builder().putString("minecraft:vertical_half", "bottom").build()));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5f, 1}, table.collision(SMOOTH_STONE_SLAB_BOTTOM));
        assertArrayEquals(new float[]{0, 0.5f, 0, 1, 1, 1}, table.collision(SMOOTH_STONE_SLAB_BOTTOM + 1));

        assertEquals("minecraft:flowing_lava", table.name(FLOWING_LAVA));
        assertEquals(0, table.collision(FLOWING_LAVA).length);
        assertEquals(100f, table.hardness(FLOWING_LAVA));

        assertEquals("minecraft:border_block", table.name(BORDER_BLOCK));
        assertArrayEquals(new float[]{0, 0, 0, 1, 1.5f, 1}, table.collision(BORDER_BLOCK));
        assertTrue(table.canContainLiquidSource(BORDER_BLOCK));

        // Bedrock's pitcher crop reaches below its block.
        assertEquals("minecraft:pitcher_crop", table.name(PITCHER_CROP_LOWER));
        assertArrayEquals(new float[]{0.3125f, -0.0625f, 0.3125f, 0.6875f, 0.1875f, 0.6875f}, table.collision(PITCHER_CROP_LOWER));
    }

    // The dump prints these as 0.817499, 0.899999, 1.799999, 0.754166 and 0.199999.
    @Test
    void cutDecimals() {
        assertEquals("minecraft:trapdoor", table.name(TRAPDOOR_TOP));
        assertArrayEquals(new float[]{0, 0.8175f, 0, 1, 1, 1}, table.collision(TRAPDOOR_TOP));
        assertEquals(0.9f, table.collision(LECTERN)[4]);
        assertEquals(1.8f, table.hardness(table.firstIndex("minecraft:white_concrete")));

        assertEquals("minecraft:pointed_dripstone", table.name(POINTED_DRIPSTONE_MIDDLE));
        assertEquals(0.754166f, table.collision(POINTED_DRIPSTONE_MIDDLE)[5]);
        assertEquals("minecraft:turtle_egg", table.name(TURTLE_EGG));
        assertEquals(0.199999f, table.collision(TURTLE_EGG)[0]);
    }

    @Test
    void entriesByName() {
        assertEquals(STONE, table.firstIndex("minecraft:stone"));
        assertEquals(1, table.stateCount("minecraft:stone"));
        assertEquals(BED, table.firstIndex("minecraft:bed"));
        assertEquals(16, table.stateCount("minecraft:bed"));
        assertEquals(-1, table.firstIndex("minecraft:cave_air"));
        assertEquals(0, table.stateCount("minecraft:cave_air"));

        int total = 0;
        for (int i = 0; i < table.paletteSize(); i++) {
            int first = table.firstIndex(table.name(i));
            assertTrue(first <= i && i < first + table.stateCount(table.name(i)), table.name(i));
            if (first == i) {
                total += table.stateCount(table.name(i));
            }
        }
        assertEquals(table.paletteSize(), total);
    }

    @Test
    void unknownHasHashMinusTwo() {
        assertEquals("minecraft:unknown", table.name(UNKNOWN));
        assertEquals(-2, table.networkHash(UNKNOWN));
        assertEquals(UNKNOWN, table.indexOfHash(-2));
        assertEquals(-1, table.indexOfHash(12345));
        assertEquals(-1, table.index("minecraft:stone", NbtMap.builder().putInt("stone_type", 0).build()));
    }

    // Every state in the dump has a palette entry with its hash and name, and no two share one.
    @Test
    void hashJoinIsComplete() throws IOException {
        String dump;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(DUMP))) {
            dump = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher state = Pattern.compile("\"blockStateHash\":(\\d+),.*?\"name\":\"([^\"]+)\"").matcher(dump);
        BitSet seen = new BitSet();
        int count = 0;
        while (state.find()) {
            int index = table.indexOfHash((int) Long.parseLong(state.group(1)));
            assertTrue(index >= 0, state.group(0));
            assertEquals(state.group(2), table.name(index));
            assertFalse(seen.get(index), state.group(0));
            seen.set(index);
            count++;
        }
        assertEquals(table.paletteSize(), count);
        assertEquals(table.paletteSize(), seen.cardinality());
    }

    // The client sorts the palette by the hash of the block name, so a palette index is its runtime ID; the Java table's palette and TablePalette agree.
    @Test
    void indexIsTheSortedRuntimeId() {
        for (int i = 1; i < table.paletteSize(); i++) {
            assertTrue(Long.compareUnsigned(TablePalette.nameHash(table.name(i - 1)), TablePalette.nameHash(table.name(i))) <= 0, table.name(i));
        }

        BlockTable java = BlockTable.get();
        assertEquals(java.paletteSize(), table.paletteSize());
        for (int i = 0; i < table.paletteSize(); i++) {
            assertEquals(java.paletteName(i), table.name(i));
            assertEquals(java.paletteStates(i), table.states(i));
            assertEquals(java.networkHash(i), table.networkHash(i));
        }

        TablePalette sorted = TablePalette.of(false);
        TablePalette hashed = TablePalette.of(true);
        for (int state = 0; state < java.stateCount(); state++) {
            BlockDefinition definition = sorted.definition(state);
            int index = definition.getRuntimeId();
            NbtMap nbt = ((SimpleBlockDefinition) definition).getState();
            assertEquals(table.name(index), nbt.getString("name"));
            assertEquals(table.states(index), nbt.getCompound("states"));
            assertEquals(table.networkHash(index), hashed.definition(state).getRuntimeId());
        }
    }

    @Test
    void rejectsOtherData() throws IOException {
        byte[] javaTable;
        try (InputStream in = BlockTable.class.getResourceAsStream(BlockTable.RESOURCE)) {
            javaTable = in.readAllBytes();
        }
        IOException wrongTable = assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(javaTable)));
        assertTrue(wrongTable.getMessage().contains("BBT2"), wrongTable.getMessage());

        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream("not a table".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(gzip("not a table, but long enough".getBytes(StandardCharsets.UTF_8)))));

        byte[] gzip;
        try (InputStream in = BedrockBlockTable.class.getResourceAsStream(BedrockBlockTable.RESOURCE)) {
            gzip = in.readAllBytes();
        }
        byte[] data;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            data = in.readAllBytes();
        }
        BedrockBlockTable.load(new ByteArrayInputStream(gzip(data)));

        byte[] newerFormat = data.clone();
        newerFormat[7] = 2;
        IOException format = assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(gzip(newerFormat))));
        assertTrue(format.getMessage().contains("format 2"), format.getMessage());

        // The table ends with the last entry's shape, flags, hardness and friction.
        byte[] unknownFlag = data.clone();
        unknownFlag[data.length - 9] = 4;
        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(gzip(unknownFlag))));

        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(gzip(Arrays.copyOf(data, data.length + 1)))));
        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(gzip(Arrays.copyOf(data, data.length - 1)))));
        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(Arrays.copyOf(gzip, gzip.length / 2))));

        // The gzip trailer is the CRC-32, then the length.
        byte[] badCrc = gzip.clone();
        badCrc[badCrc.length - 8] ^= 1;
        assertThrows(IOException.class, () -> BedrockBlockTable.load(new ByteArrayInputStream(badCrc)));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(data);
        }
        return out.toByteArray();
    }
}
