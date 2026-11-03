package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
                // A custom state has a custom ID, is a full cube, and is not known.
                assertTrue(id >= BedrockPalette.FIRST_CUSTOM_ID, entry.name());
                assertFullCube(id);
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

    // The runtime IDs of the custom states with custom IDs of a palette with sorted IDs, in order
    private static List<Integer> customRuntimeIds(BlockMappingInfo info) {
        List<Integer> custom = new ArrayList<>();
        for (int runtimeId = 0; runtimeId < table.paletteSize() + 11; runtimeId++) {
            int id = info.toIntermediary().applyAsInt(runtimeId);
            if (id >= BedrockPalette.FIRST_CUSTOM_ID && id < BedrockPalette.UNKNOWN_ID) {
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
        // A custom ID the palette does not have is never resent.
        int lamp = both.toIntermediary().applyAsInt(custom.getLast());
        assertTrue(lamp >= BedrockPalette.FIRST_CUSTOM_ID);
        assertTrue(BedrockPalette.isUnknown(BedrockPalette.of(pipeOnly).definition(lamp)));
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
    void customBlocksPastTheCapAreFullCubesThatResendThemselves() throws IOException {
        CustomBlock pipe = CustomBlock.of(pipe());
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            for (boolean hashed : new boolean[] {false, true}) {
                // The pipe's 8 states are past a cap of 1.
                BlockMappingInfo info = new BedrockPalette(hashed, List.of(pipe), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1)).mappingInfo();
                assertFalse(BedrockPalette.runtimeIdsUnknown(info));
                List<Integer> runtimeIds = new ArrayList<>();
                if (hashed) {
                    for (int s = 0; s < pipe.stateCount(); s++) {
                        runtimeIds.add(referenceHash("cubecraft:pipe", pipe.state(s)));
                    }
                } else {
                    for (int runtimeId = 0; runtimeId < table.paletteSize() + pipe.stateCount(); runtimeId++) {
                        if (info.toIntermediary().applyAsInt(runtimeId) >= BedrockPalette.FIRST_CUSTOM_ID) {
                            runtimeIds.add(runtimeId);
                        }
                    }
                }
                assertEquals(pipe.stateCount(), runtimeIds.size());
                for (int runtimeId : runtimeIds) {
                    int id = info.toIntermediary().applyAsInt(runtimeId);
                    assertTrue(id > BedrockPalette.UNKNOWN_ID);
                    assertFullCube(id);
                    assertEquals(runtimeId, BedrockPalette.definition(info, id).getRuntimeId());
                    assertFalse(info.isKnownRawId(runtimeId));
                }
                // The warning names the block, once.
                String log = logged.toString(StandardCharsets.UTF_8);
                logged.reset();
                assertEquals(1, log.split("Boar does not know the shape of custom block cubecraft:pipe", -1).length - 1, log);
                assertFalse(log.contains("Boar does not know block runtime ID"), log);
            }
        } finally {
            System.setErr(stderr);
        }
    }

    @Test
    void unknownBlocksAreFullCubesThatResendThemselves() throws IOException {
        NbtMap northLit = NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build();
        int hashedPipe = referenceHash("cubecraft:pipe", northLit);
        int sortedPipe = customRuntimeIds(BedrockPalette.mappingInfo(startGame(false, pipe()))).getFirst();
        int unknownHash = unknownHash(0);
        Map<BlockMappingInfo, Integer> blocks = Map.of(
                BedrockPalette.mappingInfo(startGame(false, pipe())), sortedPipe,
                BedrockPalette.mappingInfo(startGame(true, pipe())), hashedPipe,
                BedrockPalette.mappingInfo(startGame(true)), unknownHash,
                // A sorted ID past the palette
                BedrockPalette.mappingInfo(startGame(false)), table.paletteSize() + 5);
        blocks.forEach((info, runtimeId) -> {
            int id = info.toIntermediary().applyAsInt(runtimeId);
            assertFullCube(id);
            assertEquals(runtimeId, info.fromIntermediary().applyAsInt(id));
            assertEquals(id, info.toIntermediary().applyAsInt(runtimeId));
            assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(runtimeId));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(runtimeId));
            assertFalse(info.isKnownRawId(runtimeId));
        });
    }

    // The nth runtime ID from 0x5EED0000 up that is no vanilla network hash
    private static int unknownHash(int n) {
        int hash = 0x5EED0000 - 1;
        for (int found = -1; found < n; ) {
            hash++;
            if (table.indexOfHash(hash) < 0) {
                found++;
            }
        }
        return hash;
    }

    private static void assertFullCube(int id) {
        BoarBlockState state = BedrockState.FACTORY.create(id, null, 0);
        assertFalse(state.isAir(), "intermediary ID " + id);
        assertEquals(1, state.getCollisionBoxes().size());
        Box box = state.getCollisionBoxes().getFirst();
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
        assertTrue(state.isFaceSturdy(null));
        assertFalse(state.isReplaceable(null));
        assertFalse(state.isWaterlogged());
    }

    @Test
    void warnsOnceForEachUnknownBlock() throws IOException {
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            for (boolean hashed : new boolean[] {false, true}) {
                BedrockPalette palette = new BedrockPalette(hashed, List.of(CustomBlock.of(pipe())), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS));
                BlockMappingInfo info = palette.mappingInfo();
                int pipe = hashed ? referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build()) : customRuntimeIds(info).getFirst();
                int unknown = hashed ? unknownHash(1) : table.paletteSize() + 20;
                for (int read = 0; read < 3; read++) {
                    info.toIntermediary().applyAsInt(pipe);
                    info.toIntermediary().applyAsInt(unknown);
                }
                // A custom block is logged once for its palette, and an unknown ID once for each player.
                BlockMappingInfo other = palette.mappingInfo();
                other.toIntermediary().applyAsInt(pipe);
                other.toIntermediary().applyAsInt(unknown);
                String log = logged.toString(StandardCharsets.UTF_8);
                logged.reset();
                assertEquals(1, log.split("Boar does not know the shape of custom block cubecraft:pipe, so it treats it as a full 1x1x1 block", -1).length - 1, log);
                assertEquals(2, log.split("Boar does not know block runtime ID " + unknown + ", so it treats it as a full 1x1x1 block", -1).length - 1, log);
            }

            // Past its cap, a player's mapping logs no more unknown IDs.
            BlockMappingInfo info = new BedrockPalette(true, List.of(), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1)).mappingInfo();
            for (int n = 0; n < BedrockPalette.MAX_WARNED_IDS + 5; n++) {
                info.toIntermediary().applyAsInt(unknownHash(n));
            }
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(BedrockPalette.MAX_WARNED_IDS, log.split("Boar does not know block runtime ID", -1).length - 1, log);
        assertTrue(log.contains(unknownHash(BedrockPalette.MAX_WARNED_IDS - 1) + ", so it treats it as a full 1x1x1 block, and logs no more unknown IDs for this player"), log);
    }

    @Test
    void keepsABoundedNumberOfUnknownHashesForEachPlayer() {
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BedrockPalette palette = new BedrockPalette(true, List.of(), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1));
            BlockMappingInfo info = palette.mappingInfo();
            List<Integer> ids = new ArrayList<>();
            for (int n = 0; n < BedrockPalette.MAX_KEPT_HASHES; n++) {
                int id = info.toIntermediary().applyAsInt(unknownHash(n));
                assertTrue(id > BedrockPalette.UNKNOWN_ID);
                ids.add(id);
            }
            assertEquals(BedrockPalette.MAX_KEPT_HASHES, ids.stream().distinct().count());
            // Past the bound, an unknown hash is the one full cube that Boar never resends, and the IDs kept before stay.
            int past = unknownHash(BedrockPalette.MAX_KEPT_HASHES);
            for (int n = BedrockPalette.MAX_KEPT_HASHES; n < BedrockPalette.MAX_KEPT_HASHES + 100; n++) {
                int id = info.toIntermediary().applyAsInt(unknownHash(n));
                assertEquals(BedrockPalette.UNKNOWN_ID, id);
                assertFullCube(id);
                assertTrue(BedrockPalette.isUnknown(BedrockPalette.definition(info, id)));
            }
            for (int n = 0; n < BedrockPalette.MAX_KEPT_HASHES; n++) {
                assertEquals(unknownHash(n), BedrockPalette.definition(info, ids.get(n)).getRuntimeId());
            }
            // An ID past those kept is not resent either.
            assertTrue(BedrockPalette.isUnknown(BedrockPalette.definition(info, ids.getLast() + 1)));

            // Another player of the same palette has a table of its own.
            BlockMappingInfo other = palette.mappingInfo();
            int id = other.toIntermediary().applyAsInt(past);
            assertTrue(id > BedrockPalette.UNKNOWN_ID);
            assertEquals(past, other.fromIntermediary().applyAsInt(id));

            // A sorted ID is an index in the client's palette, which Boar reads only up to a million states.
            BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false));
            assertEquals(BedrockPalette.UNKNOWN_ID, sorted.toIntermediary().applyAsInt(-1));
            assertEquals(BedrockPalette.UNKNOWN_ID, sorted.toIntermediary().applyAsInt(1 << 20));
            assertEquals((1 << 20) - 1, sorted.fromIntermediary().applyAsInt(sorted.toIntermediary().applyAsInt((1 << 20) - 1)));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("Boar has kept " + BedrockPalette.MAX_KEPT_HASHES + " unknown block runtime IDs for one player", -1).length - 1, log);
    }

    @Test
    void hashedIdsAddTheCustomStates() throws IOException {
        int stone = table.firstIndex("minecraft:stone");
        assertEquals(table.networkHash(stone), referenceHash("minecraft:stone", table.states(stone)));

        BlockMappingInfo vanilla = BedrockPalette.mappingInfo(startGame(true));
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, pipe()));
        assertNotSame(BedrockPalette.of(vanilla), BedrockPalette.of(info));
        NbtMap northLit = NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build();
        int custom = referenceHash("cubecraft:pipe", northLit);
        assertTrue(info.toIntermediary().applyAsInt(custom) >= BedrockPalette.FIRST_CUSTOM_ID);
        assertEquals(custom, info.fromIntermediary().applyAsInt(info.toIntermediary().applyAsInt(custom)));
        assertFalse(info.isKnownRawId(custom));
        assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(custom));
        assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(custom));
        // Without the pipe, its hash is an unknown full cube.
        assertTrue(vanilla.toIntermediary().applyAsInt(custom) > BedrockPalette.UNKNOWN_ID);

        for (int i = 0; i < table.paletteSize(); i++) {
            assertEquals(table.networkHash(i), info.fromIntermediary().applyAsInt(i));
            assertEquals(i, info.toIntermediary().applyAsInt(table.networkHash(i)));
        }
        assertTrue(info.isKnownRawId(table.networkHash(stone)));
        // A sorted ID means nothing here.
        assertFalse(vanilla.isKnownRawId(1));
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
            assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(12345678));
            assertEquals(HorseJumpMaterial.HONEY, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(table.firstIndex("minecraft:honey_block"))));
            assertEquals(HorseJumpMaterial.CARPET, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(table.firstIndex("minecraft:white_carpet"))));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(info.fromIntermediary().applyAsInt(SORTED_STONE)));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(12345678));
            assertFalse(info.isKnownRawId(12345678));
        }
    }

    @Test
    void aDataDrivenVanillaBlockIsNotMerged() {
        for (boolean hashed : new boolean[]{false, true}) {
            assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed, block("minecraft:stone")))));
        }
    }

    @Test
    void playersWithTheSameBlocksShareOnePalette() {
        BedrockPalette first = BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, pipe(), lamp())));
        assertSame(first, BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, pipe(), lamp()))));
        assertNotSame(first, BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, lamp()))));
    }

    @Test
    void theCacheKeepsTheLastEightLists() {
        BlockMappingInfo first = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0")));
        for (int i = 1; i < 8; i++) {
            BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        assertSame(BedrockPalette.of(first), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0")))));
        for (int i = 8; i < 16; i++) {
            BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_" + i)));
        }
        BlockMappingInfo again = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:evict_0")));
        assertNotSame(BedrockPalette.of(first), BedrockPalette.of(again));
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
        int clashHash = referenceHash("cubecraft:clash", NbtMap.builder().putString("n", "v37092").build());
        assertEquals(clashHash, hashed.fromIntermediary().applyAsInt(hashed.toIntermediary().applyAsInt(clashHash)));
        assertFalse(hashed.isKnownRawId(clashHash));
        // Sorted IDs do not use the hash.
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, clash));
        assertTrue(sorted.isKnownRawId(sorted.fromIntermediary().applyAsInt(SORTED_STONE)));
        assertEquals(2, customRuntimeIds(sorted).size());
    }

    @Test
    void malformedBlocksMakeTheirIdsUnknown() {
        BlockPropertyData noValues = block("cubecraft:broken", property("open", NbtType.BYTE, List.of()));
        // With sorted IDs, the custom block could have moved any vanilla ID.
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, pipe(), noValues));
        int stone = sorted.fromIntermediary().applyAsInt(SORTED_STONE);
        assertEquals(SORTED_STONE, stone);
        assertFalse(sorted.isKnownRawId(stone));
        assertFalse(sorted.isKnownRawId(sorted.airId()));
        assertTrue(BedrockPalette.runtimeIdsUnknown(sorted));
        BlockMappingInfo twice = BedrockPalette.mappingInfo(startGame(false, pipe(), pipe()));
        assertFalse(twice.isKnownRawId(twice.fromIntermediary().applyAsInt(SORTED_STONE)));

        // With hashed IDs, custom blocks never move vanilla IDs.
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, noValues));
        assertTrue(hashed.isKnownRawId(hashed.fromIntermediary().applyAsInt(SORTED_STONE)));
        assertFalse(BedrockPalette.runtimeIdsUnknown(hashed));
    }
}
