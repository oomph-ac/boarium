package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected IDs come from small references written here: a merge of names sorted with List.sort, and network hashes from Cloudburst's NBT writer.
class TablePaletteTest {
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int SORTED_AIR = 13094;
    private static final int SORTED_STONE = 2706;

    private static BlockTable table;

    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
        table = BlockTable.get();
    }

    // 8 states; its name sorts before stone and air
    private static BlockPropertyData pipe() {
        return block("cubecraft:pipe", property("facing", NbtType.STRING, List.of("north", "east", "south", "west")), property("lit", NbtType.BYTE, List.of((byte) 0, (byte) 1)));
    }

    // 3 states; its name sorts after stone and air
    private static BlockPropertyData lamp() {
        return block("cubecraft:lamp", property("level", NbtType.INT, List.of(0, 1, 2)));
    }

    private static BlockPropertyData block(String name, NbtMap... properties) {
        return new BlockPropertyData(name, NbtMap.builder().putList("properties", NbtType.COMPOUND, properties).putCompound("components", NbtMap.EMPTY).build());
    }

    private static <T> NbtMap property(String name, NbtType<T> type, List<T> values) {
        return NbtMap.builder().putString("name", name).putList("enum", type, values).build();
    }

    private static StartGamePacket startGame(boolean hashed, BlockPropertyData... blocks) {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(hashed);
        startGame.getBlockProperties().addAll(List.of(blocks));
        return startGame;
    }

    private static long fnv1(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash * 0x100000001b3L) ^ (b & 0xFF);
        }
        return hash;
    }

    @Test
    void theVanillaPaletteIsSortedByName() {
        for (int i = 1; i < table.paletteSize(); i++) {
            assertTrue(Long.compareUnsigned(fnv1(table.paletteName(i - 1)), fnv1(table.paletteName(i))) <= 0, table.paletteName(i));
        }
        assertEquals(fnv1("minecraft:stone"), TablePalette.nameHash("minecraft:stone"));
    }

    // An entry of the reference palette: its block name, and its vanilla palette index or -1 for a custom state
    private record Entry(String name, int vanillaIndex) {
    }

    @Test
    void sortedIdsMoveTheVanillaStates() {
        BlockMappingInfo info = TablePalette.mappingInfo(startGame(false, pipe(), lamp()));

        // The client's palette: vanilla entries, then the custom states, stably sorted by name hash.
        List<Entry> reference = new ArrayList<>();
        for (int i = 0; i < table.paletteSize(); i++) {
            reference.add(new Entry(table.paletteName(i), i));
        }
        for (int s = 0; s < 8; s++) {
            reference.add(new Entry("cubecraft:pipe", -1));
        }
        for (int s = 0; s < 3; s++) {
            reference.add(new Entry("cubecraft:lamp", -1));
        }
        reference.sort((a, b) -> Long.compareUnsigned(fnv1(a.name()), fnv1(b.name())));
        int[] newId = new int[table.paletteSize()];
        for (int runtimeId = 0; runtimeId < reference.size(); runtimeId++) {
            Entry entry = reference.get(runtimeId);
            if (entry.vanillaIndex() >= 0) {
                newId[entry.vanillaIndex()] = runtimeId;
                assertEquals(table.paletteToJava(entry.vanillaIndex()), info.toIntermediary().applyAsInt(runtimeId));
            } else {
                // A custom state is air, and not known.
                assertEquals(AIR, info.toIntermediary().applyAsInt(runtimeId));
                assertFalse(info.isKnownRawId(runtimeId));
                assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(runtimeId));
                assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(runtimeId));
            }
        }
        for (int state = 0; state < table.stateCount(); state++) {
            assertEquals(newId[table.toRuntime(state, false)], info.fromIntermediary().applyAsInt(state));
        }

        // The pipe moves stone and air, and the lamp moves neither.
        assertEquals(SORTED_STONE + 8, info.fromIntermediary().applyAsInt(STONE));
        assertEquals(SORTED_AIR + 8, info.airId());
        assertEquals(STONE, info.toIntermediary().applyAsInt(SORTED_STONE + 8));
        // Several Java states share air's palette entry, and the highest Java ID wins.
        assertEquals(table.toJava(SORTED_AIR, false), info.toIntermediary().applyAsInt(SORTED_AIR + 8));
        assertEquals(SORTED_AIR + 8, info.fromIntermediary().applyAsInt(info.toIntermediary().applyAsInt(SORTED_AIR + 8)));
        assertTrue(info.isKnownRawId(SORTED_STONE + 8));
        assertFalse(info.isKnownRawId(reference.size()));
        assertEquals(List.of(SORTED_AIR + 8, SORTED_AIR + 8, SORTED_AIR + 8), info.airIds());
    }

    @Test
    void statesChangeTheFirstPropertyFastest() {
        CustomBlock pipe = CustomBlock.of(pipe());
        List<NbtMap> states = new ArrayList<>();
        for (int i = 0; i < pipe.stateCount(); i++) {
            states.add(pipe.state(i));
        }
        List<NbtMap> expected = new ArrayList<>();
        for (byte lit = 0; lit <= 1; lit++) {
            for (String facing : List.of("north", "east", "south", "west")) {
                expected.add(NbtMap.builder().putString("facing", facing).putByte("lit", lit).build());
            }
        }
        assertEquals(expected, states);
        // A block without properties has one state.
        CustomBlock plain = CustomBlock.of(new BlockPropertyData("cubecraft:plain", NbtMap.EMPTY));
        assertEquals(1, plain.stateCount());
        assertEquals(NbtMap.EMPTY, plain.state(0));
    }

    // FNV-1a 32 of the state written by Cloudburst's little-endian NBT writer, as an unnamed compound
    private static int referenceHash(String name, NbtMap states) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = NbtUtils.createWriterLE(bytes)) {
            out.writeTag(NbtMap.builder().putString("name", name).putCompound("states", states).build());
        }
        int hash = 0x811c9dc5;
        for (byte b : bytes.toByteArray()) {
            hash = (hash ^ (b & 0xFF)) * 0x01000193;
        }
        return hash;
    }

    @Test
    void hashedIdsAddTheCustomStates() throws IOException {
        int stone = table.toRuntime(STONE, false);
        assertEquals(table.networkHash(stone), referenceHash("minecraft:stone", table.paletteStates(stone)));
        for (int i = 0; i < table.paletteSize(); i++) {
            if (!table.paletteName(i).equals("minecraft:unknown")) {
                assertEquals(table.networkHash(i), CustomBlock.networkHash(table.paletteName(i), table.paletteStates(i)), table.paletteName(i));
            }
        }

        BlockMappingInfo vanilla = TablePalette.mappingInfo(startGame(true));
        BlockMappingInfo info = TablePalette.mappingInfo(startGame(true, pipe()));
        assertNotSame(vanilla, info);
        NbtMap northLit = NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build();
        int custom = referenceHash("cubecraft:pipe", northLit);
        assertEquals(AIR, info.toIntermediary().applyAsInt(custom));
        assertFalse(info.isKnownRawId(custom));
        assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(custom));
        assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(custom));
        assertEquals(FallingFlowMaterial.NOT_SEARCHED, vanilla.fallingFlowMaterial().apply(custom));

        for (int state = 0; state < table.stateCount(); state++) {
            assertEquals(vanilla.fromIntermediary().applyAsInt(state), info.fromIntermediary().applyAsInt(state));
        }
        assertEquals(STONE, info.toIntermediary().applyAsInt(table.networkHash(stone)));
        assertTrue(info.isKnownRawId(table.networkHash(stone)));
    }

    @Test
    void aDataDrivenVanillaBlockIsNotMerged() {
        for (boolean hashed : new boolean[]{false, true}) {
            assertSame(TablePalette.mappingInfo(startGame(hashed)), TablePalette.mappingInfo(startGame(hashed, block("minecraft:stone"))));
        }
    }

    @Test
    void playersWithTheSameBlocksShareOnePalette() {
        BlockMappingInfo first = TablePalette.mappingInfo(startGame(false, pipe(), lamp()));
        assertSame(first, TablePalette.mappingInfo(startGame(false, pipe(), lamp())));
        assertNotSame(first, TablePalette.mappingInfo(startGame(false, lamp())));
    }

    @Test
    void theCacheKeepsTheLastEightLists() {
        BlockMappingInfo first = TablePalette.mappingInfo(startGame(false, block("cubecraft:evict_0")));
        for (int i = 1; i < 8; i++) {
            TablePalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        assertSame(first, TablePalette.mappingInfo(startGame(false, block("cubecraft:evict_0"))));
        for (int i = 8; i < 16; i++) {
            TablePalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        assertNotSame(first, TablePalette.mappingInfo(startGame(false, block("cubecraft:evict_0"))));
    }

    // Blocks with this many custom states in all, as blocks of at most 65536 states, the most one block can have
    private static BlockPropertyData[] customStates(int count) {
        List<BlockPropertyData> blocks = new ArrayList<>();
        for (int b = 0; count > 0; b++) {
            List<Integer> values = new ArrayList<>();
            for (int v = 0; v < Math.min(count, 0x10000); v++) {
                values.add(v);
            }
            blocks.add(block("cubecraft:many_" + b, property("n", NbtType.INT, values)));
            count -= values.size();
        }
        return blocks.toArray(BlockPropertyData[]::new);
    }

    @Test
    void aPaletteOfMoreThanAMillionStatesIsNotRead() {
        int allowed = (1 << 20) - table.paletteSize();
        BlockMappingInfo full = TablePalette.mappingInfo(startGame(false, customStates(allowed)));
        assertTrue(full.isKnownRawId(full.fromIntermediary().applyAsInt(STONE)));
        BlockMappingInfo over = TablePalette.mappingInfo(startGame(false, customStates(allowed + 1)));
        assertFalse(over.isKnownRawId(over.fromIntermediary().applyAsInt(STONE)));
    }

    @Test
    void manyPropertiesAreRejectedQuickly() {
        NbtMap[] properties = new NbtMap[60_000];
        for (int i = 0; i < properties.length; i++) {
            properties[i] = property("p" + i, NbtType.BYTE, List.of((byte) 0));
        }
        BlockPropertyData block = block("cubecraft:wide", properties);
        // Each property takes at least 1 bit, so the 17th is past the client's 16.
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block)));
    }

    @Test
    void aHashClashMakesTheCustomIdsUnknown() throws IOException {
        // Two values found by search, whose states have the same network hash
        BlockPropertyData clash = block("cubecraft:clash", property("n", NbtType.STRING, List.of("v37092", "v219640")));
        assertEquals(referenceHash("cubecraft:clash", NbtMap.builder().putString("n", "v37092").build()), referenceHash("cubecraft:clash", NbtMap.builder().putString("n", "v219640").build()));
        assertSame(TablePalette.mappingInfo(startGame(true)), TablePalette.mappingInfo(startGame(true, clash)));
        // Sorted IDs do not use the hash.
        BlockMappingInfo sorted = TablePalette.mappingInfo(startGame(false, clash));
        assertTrue(sorted.isKnownRawId(sorted.fromIntermediary().applyAsInt(STONE)));
    }

    @Test
    void malformedBlocksMakeTheirIdsUnknown() {
        BlockPropertyData noValues = block("cubecraft:broken", property("open", NbtType.BYTE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(noValues));
        // 65537 values need 17 bits, more than the client's 16.
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i <= 0x10000; i++) {
            many.add(i);
        }
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block("cubecraft:huge", property("n", NbtType.INT, many))));

        // With sorted IDs, the custom block could have moved any vanilla ID.
        BlockMappingInfo sorted = TablePalette.mappingInfo(startGame(false, pipe(), noValues));
        int stone = sorted.fromIntermediary().applyAsInt(STONE);
        assertEquals(SORTED_STONE, stone);
        assertEquals(AIR, sorted.toIntermediary().applyAsInt(stone));
        assertFalse(sorted.isKnownRawId(stone));
        assertFalse(sorted.isKnownRawId(sorted.airId()));
        BlockMappingInfo twice = TablePalette.mappingInfo(startGame(false, pipe(), pipe()));
        assertFalse(twice.isKnownRawId(twice.fromIntermediary().applyAsInt(STONE)));

        // With hashed IDs, custom blocks never move vanilla IDs.
        assertSame(TablePalette.mappingInfo(startGame(true)), TablePalette.mappingInfo(startGame(true, noValues)));
    }
}
