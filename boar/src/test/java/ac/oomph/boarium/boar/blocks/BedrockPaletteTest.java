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
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected IDs come from small references written here: a merge of names sorted with List.sort, and network hashes from Cloudburst's NBT writer.
class BedrockPaletteTest {
    private static final int SORTED_AIR = 13094;
    private static final int SORTED_STONE = 2706;

    private static BedrockBlockTable table;

    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
        table = BedrockBlockTable.get();
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

    // An entry of the reference palette: its block name, and its vanilla palette index, or -1 and the state of a custom block
    private record Entry(String name, int vanillaIndex, int customState) {
    }

    @Test
    void sortedIdsMoveTheVanillaStates() {
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(false, pipe(), lamp()));

        // The client's palette: vanilla entries, then the custom states, stably sorted by name hash.
        List<Entry> reference = new ArrayList<>();
        for (int i = 0; i < table.paletteSize(); i++) {
            reference.add(new Entry(table.name(i), i, -1));
        }
        for (int s = 0; s < 8; s++) {
            reference.add(new Entry("cubecraft:pipe", -1, s));
        }
        for (int s = 0; s < 3; s++) {
            reference.add(new Entry("cubecraft:lamp", -1, s));
        }
        reference.sort((a, b) -> Long.compareUnsigned(fnv1(a.name()), fnv1(b.name())));
        for (int runtimeId = 0; runtimeId < reference.size(); runtimeId++) {
            Entry entry = reference.get(runtimeId);
            int id = info.toIntermediary().applyAsInt(runtimeId);
            assertEquals(runtimeId, info.fromIntermediary().applyAsInt(id));
            if (entry.vanillaIndex() >= 0) {
                assertEquals(entry.vanillaIndex(), id);
                assertTrue(info.isKnownRawId(runtimeId));
            } else {
                // A custom state has a custom ID, is air, and is not known.
                assertTrue(id >= BedrockPalette.FIRST_CUSTOM_ID, entry.name());
                assertTrue(BedrockState.FACTORY.create(id, null, 0).isAir());
                assertFalse(info.isKnownRawId(runtimeId));
                assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(runtimeId));
                assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(runtimeId));
                // A custom block's states have consecutive IDs, in the client's order.
                Entry firstState = new Entry(entry.name(), -1, 0);
                int firstRuntimeId = reference.indexOf(firstState);
                assertEquals(info.toIntermediary().applyAsInt(firstRuntimeId) + entry.customState(), id);
            }
        }

        // The pipe moves stone and air, and the lamp moves neither.
        assertEquals(SORTED_STONE + 8, info.fromIntermediary().applyAsInt(SORTED_STONE));
        assertEquals(SORTED_AIR + 8, info.airId());
        assertEquals(SORTED_STONE, info.toIntermediary().applyAsInt(SORTED_STONE + 8));
        assertFalse(info.isKnownRawId(reference.size()));
        assertEquals(List.of(SORTED_AIR + 8), info.airIds());
    }

    @Test
    void customDefinitionsNameTheirStates() throws IOException {
        CustomBlock pipe = CustomBlock.of(pipe());
        for (boolean hashed : new boolean[]{false, true}) {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(hashed, pipe()));
            // The last of the pipe's states: the last facing, lit
            NbtMap westLit = NbtMap.builder().putString("facing", "west").putByte("lit", (byte) 1).build();
            assertEquals(westLit, pipe.state(7));
            int runtimeId = hashed ? referenceHash("cubecraft:pipe", westLit) : customRuntimeIds(info).getLast();
            int id = info.toIntermediary().applyAsInt(runtimeId);
            assertTrue(id >= BedrockPalette.FIRST_CUSTOM_ID);
            SimpleBlockDefinition definition = (SimpleBlockDefinition) BedrockPalette.of(info).definition(id);
            assertEquals("cubecraft:pipe", definition.getIdentifier());
            assertEquals(runtimeId, definition.getRuntimeId());
            assertEquals(NbtMap.builder().putString("name", "cubecraft:pipe").putCompound("states", westLit).build(), definition.getState());
        }
    }

    // The runtime IDs of the custom states of a palette with sorted IDs, in order
    private static List<Integer> customRuntimeIds(BlockMappingInfo info) {
        List<Integer> custom = new ArrayList<>();
        for (int runtimeId = 0; runtimeId < table.paletteSize() + 11; runtimeId++) {
            if (info.toIntermediary().applyAsInt(runtimeId) >= BedrockPalette.FIRST_CUSTOM_ID) {
                custom.add(runtimeId);
            }
        }
        return custom;
    }

    @Test
    void playersOfDifferentServersShareCustomIds() {
        BlockMappingInfo both = BedrockPalette.mappingInfo(startGame(false, pipe(), lamp()));
        BlockMappingInfo pipeOnly = BedrockPalette.mappingInfo(startGame(true, pipe()));
        // The pipe sorts before the lamp.
        List<Integer> custom = customRuntimeIds(both);
        assertEquals(11, custom.size());
        int sortedPipe = custom.getFirst();
        int id = both.toIntermediary().applyAsInt(sortedPipe);
        // The same state has another runtime ID for the other player, and the same custom ID.
        int hashedPipe = pipeOnly.fromIntermediary().applyAsInt(id);
        assertNotEquals(sortedPipe, hashedPipe);
        assertEquals(id, pipeOnly.toIntermediary().applyAsInt(hashedPipe));
        // A custom ID the palette does not have is air.
        int lamp = both.toIntermediary().applyAsInt(custom.getLast());
        assertTrue(lamp >= BedrockPalette.FIRST_CUSTOM_ID);
        assertEquals(pipeOnly.airId(), pipeOnly.fromIntermediary().applyAsInt(lamp));
    }

    @Test
    void customIdsStopAtTheirCapAndWarnOnce() {
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 10);
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            assertEquals(100, ids.of(CustomBlock.of(pipe())));
            // 8 + 3 states are past the cap of 10.
            assertEquals(-1, ids.of(CustomBlock.of(lamp())));
            assertEquals(-1, ids.of(CustomBlock.of(lamp())));
            // Once the cap is reached, a block that would fit gets none either, and a block with IDs keeps them.
            assertEquals(-1, ids.of(CustomBlock.of(block("cubecraft:plain"))));
            assertEquals(100, ids.of(CustomBlock.of(pipe())));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("custom block IDs", -1).length - 1, log);
        assertTrue(log.contains("cubecraft:lamp"), log);
    }

    @Test
    void customBlocksPastTheCapAreNotCovered() {
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            for (boolean hashed : new boolean[] {false, true}) {
                // The pipe's 2 states are past a cap of 1.
                BlockMappingInfo info = new BedrockPalette(hashed, List.of(CustomBlock.of(pipe())), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1)).mappingInfo;
                assertFalse(BedrockPalette.runtimeIdsUnknown(info));
                assertFalse(BedrockPalette.coversEveryBlock(info));
            }
        } finally {
            System.setErr(stderr);
        }
    }

    @Test
    void hashedIdsAddTheCustomStates() throws IOException {
        int stone = table.firstIndex("minecraft:stone");
        assertEquals(table.networkHash(stone), referenceHash("minecraft:stone", table.states(stone)));

        BlockMappingInfo vanilla = BedrockPalette.mappingInfo(startGame(true));
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, pipe()));
        assertNotSame(vanilla, info);
        NbtMap northLit = NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build();
        int custom = referenceHash("cubecraft:pipe", northLit);
        assertTrue(info.toIntermediary().applyAsInt(custom) >= BedrockPalette.FIRST_CUSTOM_ID);
        assertEquals(custom, info.fromIntermediary().applyAsInt(info.toIntermediary().applyAsInt(custom)));
        assertFalse(info.isKnownRawId(custom));
        assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(custom));
        assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(custom));
        assertEquals(FallingFlowMaterial.NOT_SEARCHED, vanilla.fallingFlowMaterial().apply(custom));
        assertEquals(table.firstIndex("minecraft:air"), vanilla.toIntermediary().applyAsInt(custom));

        for (int i = 0; i < table.paletteSize(); i++) {
            assertEquals(table.networkHash(i), info.fromIntermediary().applyAsInt(i));
            assertEquals(i, info.toIntermediary().applyAsInt(table.networkHash(i)));
        }
        assertTrue(info.isKnownRawId(table.networkHash(stone)));
        // A sorted ID means nothing here.
        assertEquals(table.firstIndex("minecraft:air"), vanilla.toIntermediary().applyAsInt(1));
    }

    @Test
    void definitionsAndMaterials() {
        for (boolean hashed : new boolean[]{false, true}) {
            BedrockPalette palette = BedrockPalette.of(hashed);
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(hashed));
            SimpleBlockDefinition lava = (SimpleBlockDefinition) info.lavaDefinition();
            assertEquals("minecraft:lava", lava.getIdentifier());
            assertEquals(NbtMap.builder().putInt("liquid_depth", 0).build(), lava.getState().getCompound("states"));
            SimpleBlockDefinition water = (SimpleBlockDefinition) info.waterDefinition();
            assertEquals("minecraft:water", water.getIdentifier());
            assertEquals(NbtMap.builder().putInt("liquid_depth", 0).build(), water.getState().getCompound("states"));
            assertEquals("minecraft:powder_snow", ((SimpleBlockDefinition) info.powderSnowDefinition()).getIdentifier());
            assertEquals(hashed ? table.networkHash(SORTED_AIR) : SORTED_AIR, info.airId());
            assertEquals(List.of(info.airId()), info.airIds());

            int frame = table.firstIndex("minecraft:frame");
            assertTrue(info.isItemFrame(palette.definition(frame)));
            assertTrue(info.isItemFrame(palette.definition(table.firstIndex("minecraft:glow_frame"))));
            assertFalse(info.isItemFrame(palette.definition(SORTED_STONE)));

            // Boar's data has every block of the 1.26.30 palette. Without custom blocks, a runtime ID is the palette index or the network hash.
            for (int i = 0; i < table.paletteSize(); i++) {
                int runtimeId = info.fromIntermediary().applyAsInt(i);
                assertEquals(hashed ? table.networkHash(i) : i, runtimeId, table.name(i));
                assertTrue(info.fallingFlowMaterial().apply(runtimeId).isKnown(), table.name(i));
                assertEquals(i, info.toIntermediary().applyAsInt(runtimeId));
                assertTrue(info.isKnownRawId(runtimeId));
            }
            assertEquals(FallingFlowMaterial.NOT_SEARCHED, info.fallingFlowMaterial().apply(12345678));
            assertEquals(HorseJumpMaterial.HONEY, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(table.firstIndex("minecraft:honey_block"))));
            assertEquals(HorseJumpMaterial.CARPET, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(table.firstIndex("minecraft:white_carpet"))));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(SORTED_STONE)));
            assertEquals(HorseJumpMaterial.UNKNOWN, info.horseJumpMaterial().apply(12345678));
            assertFalse(info.isKnownRawId(12345678));
        }
    }

    @Test
    void aDataDrivenVanillaBlockIsNotMerged() {
        for (boolean hashed : new boolean[]{false, true}) {
            assertSame(BedrockPalette.mappingInfo(startGame(hashed)), BedrockPalette.mappingInfo(startGame(hashed, block("minecraft:stone"))));
        }
    }

    @Test
    void playersWithTheSameBlocksShareOnePalette() {
        BlockMappingInfo first = BedrockPalette.mappingInfo(startGame(false, pipe(), lamp()));
        assertSame(first, BedrockPalette.mappingInfo(startGame(false, pipe(), lamp())));
        assertNotSame(first, BedrockPalette.mappingInfo(startGame(false, lamp())));
    }

    @Test
    void theCacheKeepsTheLastEightLists() {
        BlockMappingInfo first = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0")));
        for (int i = 1; i < 8; i++) {
            BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        assertSame(first, BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0"))));
        for (int i = 8; i < 16; i++) {
            BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        BlockMappingInfo again = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0")));
        assertNotSame(first, again);
        // A palette made again gives the block the same custom ID.
        int runtimeId = 0;
        while (first.toIntermediary().applyAsInt(runtimeId) < BedrockPalette.FIRST_CUSTOM_ID) {
            runtimeId++;
        }
        assertEquals(first.toIntermediary().applyAsInt(runtimeId), again.toIntermediary().applyAsInt(runtimeId));
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
        BlockMappingInfo full = BedrockPalette.mappingInfo(startGame(false, customStates(allowed)));
        assertTrue(full.isKnownRawId(full.fromIntermediary().applyAsInt(SORTED_STONE)));
        BlockMappingInfo over = BedrockPalette.mappingInfo(startGame(false, customStates(allowed + 1)));
        assertFalse(over.isKnownRawId(over.fromIntermediary().applyAsInt(SORTED_STONE)));
    }

    @Test
    void aHashClashMakesTheCustomIdsUnknown() throws IOException {
        // Two values found by search, whose states have the same network hash
        BlockPropertyData clash = block("cubecraft:clash", property("n", NbtType.STRING, List.of("v37092", "v219640")));
        assertEquals(referenceHash("cubecraft:clash", NbtMap.builder().putString("n", "v37092").build()), referenceHash("cubecraft:clash", NbtMap.builder().putString("n", "v219640").build()));
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, clash));
        assertTrue(hashed.isKnownRawId(hashed.fromIntermediary().applyAsInt(SORTED_STONE)));
        assertFalse(BedrockPalette.coversEveryBlock(hashed));
        // Sorted IDs do not use the hash.
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, clash));
        assertTrue(sorted.isKnownRawId(sorted.fromIntermediary().applyAsInt(SORTED_STONE)));
        assertTrue(BedrockPalette.coversEveryBlock(sorted));
    }

    @Test
    void malformedBlocksMakeTheirIdsUnknown() {
        BlockPropertyData noValues = block("cubecraft:broken", property("open", NbtType.BYTE, List.of()));
        // With sorted IDs, the custom block could have moved any vanilla ID.
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, pipe(), noValues));
        int stone = sorted.fromIntermediary().applyAsInt(SORTED_STONE);
        assertEquals(SORTED_STONE, stone);
        assertEquals(SORTED_AIR, sorted.toIntermediary().applyAsInt(stone));
        assertFalse(sorted.isKnownRawId(stone));
        assertFalse(sorted.isKnownRawId(sorted.airId()));
        assertTrue(BedrockPalette.runtimeIdsUnknown(sorted));
        assertFalse(BedrockPalette.coversEveryBlock(sorted));
        BlockMappingInfo twice = BedrockPalette.mappingInfo(startGame(false, pipe(), pipe()));
        assertFalse(twice.isKnownRawId(twice.fromIntermediary().applyAsInt(SORTED_STONE)));

        // With hashed IDs, custom blocks never move vanilla IDs.
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, noValues));
        assertTrue(hashed.isKnownRawId(hashed.fromIntermediary().applyAsInt(SORTED_STONE)));
        assertFalse(BedrockPalette.runtimeIdsUnknown(hashed));
        assertFalse(BedrockPalette.coversEveryBlock(hashed));
        assertTrue(BedrockPalette.coversEveryBlock(BedrockPalette.mappingInfo(startGame(true, pipe()))));
    }
}
