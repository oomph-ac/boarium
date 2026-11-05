package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected IDs come from small references written here: a merge of names sorted with List.sort, and network hashes from Cloudburst's NBT writer.
class BedrockPaletteTest {
    private static final int SORTED_AIR = 13094;
    private static final int SORTED_STONE = 2706;
    // The client skips a block without this compound. Geyser 2.12 writes a block_id in it.
    private static final NbtMap VANILLA_BLOCK_DATA = NbtMap.builder().putInt("block_id", 0).build();

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

    // pipe, with a permutation that sets its collision under a condition Boar cannot read
    private static BlockPropertyData unreadPipe() {
        NbtMap disabled = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build()).build();
        NbtMap permutation = NbtMap.builder().putString("condition", "q.cardinal_facing == 2").putCompound("components", disabled).build();
        return new BlockPropertyData("cubecraft:pipe", pipe().getProperties().toBuilder().putList("permutations", NbtType.COMPOUND, permutation).build());
    }

    // 3 states; its name sorts after stone and air
    private static BlockPropertyData lamp() {
        return block("cubecraft:lamp", property("level", NbtType.INT, List.of(0, 1, 2)));
    }

    private static BlockPropertyData block(String name, NbtMap... properties) {
        return new BlockPropertyData(name, NbtMap.builder().putList("properties", NbtType.COMPOUND, properties).putCompound("components", NbtMap.EMPTY).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    // This block as a server that leaves out vanilla_block_data sends it
    private static BlockPropertyData withoutVanillaBlockData(BlockPropertyData block) {
        NbtMapBuilder properties = block.getProperties().toBuilder();
        properties.remove("vanilla_block_data");
        return new BlockPropertyData(block.getName(), properties.build());
    }

    // 3 states; its name sorts before stone and air
    private static BlockPropertyData vent() {
        return block("cubecraft:vent", property("level", NbtType.INT, List.of(0, 1, 2)));
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

    // Boar's intermediary ID of a runtime ID, as a 1.26.30 palette index when it is a vanilla state
    private static int toId(BlockMappingInfo info, int runtimeId) {
        return SharedIds.own(info.toIntermediary().applyAsInt(runtimeId));
    }

    // The runtime ID of a 1.26.30 palette index, or of an ID past the shared table
    private static int fromId(BlockMappingInfo info, int id) {
        return info.fromIntermediary().applyAsInt(SharedIds.shared(id));
    }

    private static BlockDefinition definition(BlockMappingInfo info, int id) {
        return BedrockPalette.definition(info, SharedIds.shared(id));
    }

    private static BlockDefinition definition(BedrockPalette palette, int id) {
        return palette.definition(SharedIds.shared(id));
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
            int id = toId(info, runtimeId);
            assertEquals(runtimeId, fromId(info, id));
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
                assertEquals(toId(info, firstRuntimeId) + entry.customState(), id);
            }
        }

        // The pipe moves stone and air, and the lamp moves neither.
        assertEquals(SORTED_STONE + 8, fromId(info, SORTED_STONE));
        assertEquals(SORTED_AIR + 8, info.airId());
        assertEquals(SORTED_STONE, toId(info, SORTED_STONE + 8));
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
            int id = toId(info, runtimeId);
            assertTrue(id >= BedrockPalette.FIRST_CUSTOM_ID);
            SimpleBlockDefinition definition = (SimpleBlockDefinition) definition(BedrockPalette.of(info), id);
            assertEquals("cubecraft:pipe", definition.getIdentifier());
            assertEquals(runtimeId, definition.getRuntimeId());
            assertEquals(NbtMap.builder().putString("name", "cubecraft:pipe").putCompound("states", westLit).build(), definition.getState());
        }
    }

    // The runtime IDs of the custom states with custom IDs of a palette with sorted IDs, in order
    private static List<Integer> customRuntimeIds(BlockMappingInfo info) {
        return customRuntimeIds(info, table.paletteSize() + 11);
    }

    // As above, among the runtime IDs below this one
    private static List<Integer> customRuntimeIds(BlockMappingInfo info, int end) {
        List<Integer> custom = new ArrayList<>();
        for (int runtimeId = 0; runtimeId < end; runtimeId++) {
            int id = toId(info, runtimeId);
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
        int id = toId(both, sortedPipe);
        // The same state has another runtime ID for the other player, and the same custom ID.
        int hashedPipe = fromId(pipeOnly, id);
        assertNotEquals(sortedPipe, hashedPipe);
        assertEquals(id, toId(pipeOnly, hashedPipe));
        // A custom ID the palette does not have is never resent.
        int lamp = toId(both, custom.getLast());
        assertTrue(lamp >= BedrockPalette.FIRST_CUSTOM_ID);
        assertTrue(BedrockPalette.isUnknown(definition(BedrockPalette.of(pipeOnly), lamp)));
    }

    @Test
    void customIdsStopAtTheirCapAndWarnOnce() {
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 10);
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            assertEquals(100, ids.of(List.of(CustomBlock.of(pipe())), Long.MAX_VALUE)[0]);
            // 8 + 3 states are past the cap of 10.
            assertEquals(-1, ids.of(List.of(CustomBlock.of(lamp())), Long.MAX_VALUE)[0]);
            assertEquals(-1, ids.of(List.of(CustomBlock.of(lamp())), Long.MAX_VALUE)[0]);
            // A block that fits still gets IDs, and a block with IDs keeps them.
            assertEquals(108, ids.of(List.of(CustomBlock.of(block("cubecraft:plain"))), Long.MAX_VALUE)[0]);
            assertEquals(100, ids.of(List.of(CustomBlock.of(pipe())), Long.MAX_VALUE)[0]);
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
                        if (toId(info, runtimeId) >= BedrockPalette.FIRST_CUSTOM_ID) {
                            runtimeIds.add(runtimeId);
                        }
                    }
                }
                assertEquals(pipe.stateCount(), runtimeIds.size());
                for (int runtimeId : runtimeIds) {
                    int id = toId(info, runtimeId);
                    assertTrue(id > BedrockPalette.UNKNOWN_ID);
                    assertFullCube(id);
                    assertEquals(runtimeId, definition(info, id).getRuntimeId());
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
            int id = toId(info, runtimeId);
            assertFullCube(id);
            assertEquals(runtimeId, fromId(info, id));
            assertEquals(id, toId(info, runtimeId));
            assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(runtimeId));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(runtimeId));
            assertFalse(info.isKnownRawId(runtimeId));
        });
    }

    // A custom state's intermediary ID gives Boar its block's boxes, with sorted and hashed IDs.
    @Test
    void customStatesHaveTheirBlocksBoxes() throws IOException {
        NbtMap disabled = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build()).build();
        NbtMap slab = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", true)
                .putList("boxes", NbtType.COMPOUND, NbtMap.builder().putFloat("maxX", 16).putFloat("maxY", 8).putFloat("maxZ", 16).build()).build()).build();
        NbtMap open = NbtMap.builder().putString("condition", "q.block_state('open') == true").putCompound("components", disabled).build();
        BlockPropertyData gate = new BlockPropertyData("cubecraft:gate", NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, property("open", NbtType.BYTE, List.of((byte) 0, (byte) 1)))
                .putCompound("components", slab)
                .putList("permutations", NbtType.COMPOUND, open)
                .putCompound("vanilla_block_data", VANILLA_BLOCK_DATA)
                .build());
        for (boolean hashed : new boolean[]{false, true}) {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(hashed, gate));
            List<Integer> runtimeIds = hashed
                    ? List.of(referenceHash("cubecraft:gate", NbtMap.builder().putByte("open", (byte) 0).build()), referenceHash("cubecraft:gate", NbtMap.builder().putByte("open", (byte) 1).build()))
                    : customRuntimeIds(info);
            BoarBlockState closed = BedrockState.FACTORY.create(info.toIntermediary().applyAsInt(runtimeIds.get(0)), null, 0);
            BoarBlockState opened = BedrockState.FACTORY.create(info.toIntermediary().applyAsInt(runtimeIds.get(1)), null, 0);
            assertEquals(1, closed.getCollisionBoxes().size());
            Box box = closed.getCollisionBoxes().getFirst();
            assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
            assertFalse(closed.isFaceSturdy(null));
            assertEquals(List.of(), opened.getCollisionBoxes());
            assertFalse(opened.isFaceSturdy(null));
            assertFalse(opened.isAir());
        }
    }

    // An open path is icy and can be built over, and no state can be mined.
    @Test
    void customStatesHaveTheirBlocksFrictionReplaceableAndDestroyTime() throws IOException {
        NbtMap icy = NbtMap.builder().putCompound("minecraft:friction", NbtMap.builder().putFloat("value", 0.02F).build()).putCompound("minecraft:replaceable", NbtMap.EMPTY).build();
        BlockPropertyData path = new BlockPropertyData("cubecraft:path", NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, property("open", NbtType.BYTE, List.of((byte) 0, (byte) 1)))
                .putCompound("components", NbtMap.builder().putCompound("minecraft:destructible_by_mining", NbtMap.builder().putFloat("value", -1).build()).build())
                .putList("permutations", NbtType.COMPOUND, NbtMap.builder().putString("condition", "q.block_state('open')").putCompound("components", icy).build())
                .putCompound("vanilla_block_data", VANILLA_BLOCK_DATA)
                .build());
        for (boolean hashed : new boolean[]{false, true}) {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(hashed, path));
            List<Integer> runtimeIds = hashed
                    ? List.of(referenceHash("cubecraft:path", NbtMap.builder().putByte("open", (byte) 0).build()), referenceHash("cubecraft:path", NbtMap.builder().putByte("open", (byte) 1).build()))
                    : customRuntimeIds(info);
            BoarBlockState closed = BedrockState.FACTORY.create(info.toIntermediary().applyAsInt(runtimeIds.get(0)), null, 0);
            BoarBlockState opened = BedrockState.FACTORY.create(info.toIntermediary().applyAsInt(runtimeIds.get(1)), null, 0);
            assertEquals(0.6F, closed.getFriction());
            assertFalse(closed.isReplaceable(null));
            assertEquals(1 - 0.02F, opened.getFriction());
            assertTrue(opened.isReplaceable(null));
            assertEquals(-1, opened.block().destroyTime());
        }
        // A state of no custom block has the friction of a block Boar does not know.
        assertEquals(0.6F, BedrockState.FACTORY.create(BedrockPalette.UNKNOWN_ID, null, 0).getFriction());
    }

    @Test
    void warnsOnceForABlockWithComponentsItCannotRead() {
        NbtMap icy = NbtMap.builder().putCompound("minecraft:friction", NbtMap.builder().putFloat("value", 0.02F).build()).build();
        NbtMap permutation = NbtMap.builder().putString("condition", "q.cardinal_facing == 2").putCompound("components", icy).build();
        BlockPropertyData pipe = new BlockPropertyData("cubecraft:pipe", pipe().getProperties().toBuilder().putList("permutations", NbtType.COMPOUND, permutation).build());
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BedrockPalette palette = new BedrockPalette(false, List.of(CustomBlock.of(pipe)), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS));
            BlockMappingInfo info = palette.mappingInfo();
            for (int runtimeId : customRuntimeIds(info)) {
                toId(info, runtimeId);
            }
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        String warning = "Boar may not predict the friction, replaceability or destroy time of some states of custom block cubecraft:pipe (Boar cannot read the permutation condition \"q.cardinal_facing == 2\")";
        assertEquals(1, log.split(Pattern.quote(warning), -1).length - 1, log);
        assertFalse(log.contains("shape"), log);
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
                BedrockPalette palette = new BedrockPalette(hashed, List.of(CustomBlock.of(unreadPipe())), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS));
                BlockMappingInfo info = palette.mappingInfo();
                int pipe = hashed ? referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 1).build()) : customRuntimeIds(info).getFirst();
                int unknown = hashed ? unknownHash(1) : table.paletteSize() + 20;
                for (int read = 0; read < 3; read++) {
                    toId(info, pipe);
                    toId(info, unknown);
                }
                // A custom block is logged once for its palette, and an unknown ID once for each player.
                BlockMappingInfo other = palette.mappingInfo();
                toId(other, pipe);
                toId(other, unknown);
                String log = logged.toString(StandardCharsets.UTF_8);
                logged.reset();
                assertEquals(1, log.split(Pattern.quote("Boar does not know the shape of custom block cubecraft:pipe (Boar cannot read the permutation condition \"q.cardinal_facing == 2\"), so it treats the states it cannot read as full 1x1x1 blocks"), -1).length - 1, log);
                assertEquals(2, log.split("Boar does not know block runtime ID " + unknown + ", so it treats it as a full 1x1x1 block", -1).length - 1, log);
            }

            // A custom block whose boxes Boar reads is not logged.
            BedrockPalette readable = new BedrockPalette(false, List.of(CustomBlock.of(pipe())), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS));
            BlockMappingInfo readableInfo = readable.mappingInfo();
            toId(readableInfo, customRuntimeIds(readableInfo).getFirst());
            String readableLog = logged.toString(StandardCharsets.UTF_8);
            logged.reset();
            assertFalse(readableLog.contains("cubecraft:pipe"), readableLog);

            // Past its cap, a player's mapping logs no more unknown IDs.
            BlockMappingInfo info = new BedrockPalette(true, List.of(), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1)).mappingInfo();
            for (int n = 0; n < BedrockPalette.MAX_WARNED_IDS + 5; n++) {
                toId(info, unknownHash(n));
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
                int id = toId(info, unknownHash(n));
                assertTrue(id > BedrockPalette.UNKNOWN_ID);
                ids.add(id);
            }
            assertEquals(BedrockPalette.MAX_KEPT_HASHES, ids.stream().distinct().count());
            // Past the bound, an unknown hash is the one full cube that Boar never resends, and the IDs kept before stay.
            int past = unknownHash(BedrockPalette.MAX_KEPT_HASHES);
            for (int n = BedrockPalette.MAX_KEPT_HASHES; n < BedrockPalette.MAX_KEPT_HASHES + 100; n++) {
                int id = toId(info, unknownHash(n));
                assertEquals(BedrockPalette.UNKNOWN_ID, id);
                assertFullCube(id);
                assertTrue(BedrockPalette.isUnknown(definition(info, id)));
            }
            for (int n = 0; n < BedrockPalette.MAX_KEPT_HASHES; n++) {
                assertEquals(unknownHash(n), definition(info, ids.get(n)).getRuntimeId());
            }
            // An ID past those kept is not resent either.
            assertTrue(BedrockPalette.isUnknown(definition(info, ids.getLast() + 1)));

            // Another player of the same palette has a table of its own.
            BlockMappingInfo other = palette.mappingInfo();
            int id = toId(other, past);
            assertTrue(id > BedrockPalette.UNKNOWN_ID);
            assertEquals(past, fromId(other, id));

            // A sorted ID is an index in the client's palette, which Boar reads only up to a million states.
            BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false));
            assertEquals(BedrockPalette.UNKNOWN_ID, toId(sorted, -1));
            assertEquals(BedrockPalette.UNKNOWN_ID, toId(sorted, 1 << 20));
            assertEquals((1 << 20) - 1, fromId(sorted, toId(sorted, (1 << 20) - 1)));
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
        assertTrue(toId(info, custom) >= BedrockPalette.FIRST_CUSTOM_ID);
        assertEquals(custom, fromId(info, toId(info, custom)));
        assertFalse(info.isKnownRawId(custom));
        assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(custom));
        assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(custom));
        // Without the pipe, its hash is an unknown full cube.
        assertTrue(toId(vanilla, custom) > BedrockPalette.UNKNOWN_ID);

        for (int i = 0; i < table.paletteSize(); i++) {
            assertEquals(table.networkHash(i), fromId(info, i));
            assertEquals(i, toId(info, table.networkHash(i)));
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
            assertTrue(info.isItemFrame(definition(palette, frame)));
            assertTrue(info.isItemFrame(definition(palette, table.firstIndex("minecraft:glow_frame"))));
            assertFalse(info.isItemFrame(definition(palette, SORTED_STONE)));

            // Boar's data has every block of the 1.26.30 palette. Without custom blocks, a runtime ID is the palette index or the network hash.
            for (int i = 0; i < table.paletteSize(); i++) {
                int runtimeId = fromId(info, i);
                assertEquals(hashed ? table.networkHash(i) : i, runtimeId, table.name(i));
                assertTrue(info.fallingFlowMaterial().apply(runtimeId).isKnown(), table.name(i));
                assertEquals(i, toId(info, runtimeId));
                assertTrue(info.isKnownRawId(runtimeId));
            }
            assertEquals(FallingFlowMaterial.CUSTOM, info.fallingFlowMaterial().apply(12345678));
            assertEquals(HorseJumpMaterial.HONEY, info.horseJumpMaterial().apply(fromId(info, table.firstIndex("minecraft:honey_block"))));
            assertEquals(HorseJumpMaterial.CARPET, info.horseJumpMaterial().apply(fromId(info, table.firstIndex("minecraft:white_carpet"))));
            assertEquals(HorseJumpMaterial.NONE, info.horseJumpMaterial().apply(fromId(info, SORTED_STONE)));
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

    // pipe, with a block_archetype compound in its vanilla_block_data
    private static BlockPropertyData archetypePipe(NbtMap archetype) {
        return new BlockPropertyData("cubecraft:pipe", pipe().getProperties().toBuilder().putCompound("vanilla_block_data", VANILLA_BLOCK_DATA.toBuilder().putCompound("block_archetype", archetype).build()).build());
    }

    // The client makes a door archetype block a vanilla door, whose own states come before the pipe's, so Boar cannot read the block.
    @Test
    void anArchetypeBlockIsUnread() throws IOException {
        BlockPropertyData door = archetypePipe(NbtMap.builder().putString("type", "door_block").build());
        assertTrue(BedrockPalette.runtimeIdsUnknown(BedrockPalette.mappingInfo(startGame(false, door))));
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, door, lamp()));
        assertFalse(BedrockPalette.runtimeIdsUnknown(hashed));
        assertTrue(toId(hashed, referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 0).build())) > BedrockPalette.UNKNOWN_ID);
        assertTrue(toId(hashed, referenceHash("cubecraft:lamp", state("level", 0))) > BedrockPalette.UNKNOWN_ID);
        assertTrue(hashed.isKnownRawId(fromId(hashed, SORTED_STONE)));

        // The client ignores a type it does not know, such as one of a later version, and a type that is not a string.
        List<NbtMap> pipeStates = new ArrayList<>();
        for (byte lit = 0; lit <= 1; lit++) {
            for (String facing : List.of("north", "east", "south", "west")) {
                pipeStates.add(NbtMap.builder().putString("facing", facing).putByte("lit", lit).build());
            }
        }
        assertStatesBeforeStone(archetypePipe(NbtMap.builder().putString("type", "wall_foliage_block").build()), pipeStates);
        assertStatesBeforeStone(archetypePipe(NbtMap.builder().putString("type", "Door_block").build()), pipeStates);
        assertStatesBeforeStone(archetypePipe(NbtMap.builder().putInt("type", 3).build()), pipeStates);
        // It reads block_archetype only in vanilla_block_data.
        assertStatesBeforeStone(new BlockPropertyData("cubecraft:pipe", pipe().getProperties().toBuilder().putCompound("block_archetype", NbtMap.builder().putString("type", "door_block").build()).build()), pipeStates);
    }

    @Test
    void warnsOnceOfAnArchetypeBlock() {
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BedrockPalette.mappingInfo(startGame(false, archetypePipe(NbtMap.builder().putString("type", "stair_block").build()), lamp()));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("cubecraft:pipe: it has the block archetype stair_block, whose block class Boar does not know", -1).length - 1, log);
        assertFalse(log.contains("cubecraft:lamp"), log);
    }

    // A block of a vanilla name gives the vanilla block its properties and the states of its traits, which the vanilla block does not have, so its runtime IDs change.
    @Test
    void aVanillaBlockWithNewStatesIsUnread() throws IOException {
        String gate = "minecraft:fence_gate";
        assertTrue(table.states(table.firstIndex(gate)).containsKey("minecraft:cardinal_direction"));
        assertFalse(table.states(table.firstIndex(gate)).containsKey("minecraft:facing_direction"));
        BlockPropertyData withProperty = block("minecraft:stone", property("level", NbtType.INT, List.of(0, 1, 2)));
        BlockPropertyData withTrait = new BlockPropertyData(gate, block(gate).getProperties().toBuilder().putList("traits", NbtType.COMPOUND, direction("facing_direction")).build());
        for (BlockPropertyData merged : List.of(withProperty, withTrait)) {
            assertTrue(BedrockPalette.runtimeIdsUnknown(BedrockPalette.mappingInfo(startGame(false, merged, pipe()))), merged.getName());
            BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, merged, pipe()));
            assertTrue(toId(hashed, referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 0).build())) > BedrockPalette.UNKNOWN_ID, merged.getName());
        }
        // The client skips a copy without vanilla_block_data before it adds anything.
        assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, withoutVanillaBlockData(withProperty)))));
    }

    // A trait whose states the vanilla block already has keeps the vanilla block's states and runtime IDs, so Boar logs it once and keeps the vanilla block.
    @Test
    void aVanillaBlockWithItsOwnTraitStatesIsKept() {
        String gate = "minecraft:fence_gate";
        BlockPropertyData withTrait = new BlockPropertyData(gate, block(gate).getProperties().toBuilder().putList("traits", NbtType.COMPOUND, direction("cardinal_direction")).build());
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            for (boolean hashed : new boolean[]{false, true}) {
                assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed, withTrait, withTrait))));
            }
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        // Once for each of the two palettes
        assertEquals(2, log.split("vanilla block " + gate + " traits whose states it already has", -1).length - 1, log);
    }

    // The client keeps the last copy of a vanilla name, so a copy with a property that a plain copy follows adds nothing.
    @Test
    void theLastCopyOfAVanillaBlockCounts() {
        BlockPropertyData withProperty = block("minecraft:stone", property("level", NbtType.INT, List.of(0, 1, 2)));
        assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, withProperty, block("minecraft:stone")))));
        assertTrue(BedrockPalette.runtimeIdsUnknown(BedrockPalette.mappingInfo(startGame(false, block("minecraft:stone"), withProperty))));
    }

    // The client runs an archetype's factory only for a new name, so a vanilla block with an archetype keeps its states.
    @Test
    void aVanillaBlockIgnoresItsArchetype() {
        BlockPropertyData stairs = new BlockPropertyData("minecraft:oak_stairs", NbtMap.builder().putCompound("vanilla_block_data", VANILLA_BLOCK_DATA.toBuilder().putCompound("block_archetype", NbtMap.builder().putString("type", "stair_block").build()).build()).build());
        for (boolean hashed : new boolean[]{false, true}) {
            assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed, stairs))));
        }
    }

    @Test
    void warnsOnceOfAVanillaBlockWithComponents() {
        NbtMap collision = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build()).build();
        BlockPropertyData withComponents = new BlockPropertyData("minecraft:dirt", block("minecraft:dirt").getProperties().toBuilder().putCompound("components", collision).build());
        BlockPropertyData withPermutations = new BlockPropertyData("minecraft:sand", block("minecraft:sand").getProperties().toBuilder().putList("permutations", NbtType.COMPOUND, NbtMap.builder().putCompound("components", collision).build()).build());
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            assertSame(BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false))), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(false, withComponents, withComponents, withPermutations, block("minecraft:gravel")))));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("components or permutations", -1).length - 1, log);
        // An empty components compound, as block() writes, is none.
        assertTrue(log.contains("to vanilla blocks, 2 of them (minecraft:dirt, minecraft:sand), which"), log);
        assertFalse(log.contains("minecraft:gravel"), log);
    }

    @Test
    void warnsOfOneVanillaBlockWithComponentsOrNone() {
        NbtMap collision = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build()).build();
        BlockPropertyData withComponents = new BlockPropertyData("minecraft:dirt", block("minecraft:dirt").getProperties().toBuilder().putCompound("components", collision).build());
        PrintStream stderr = System.err;
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(one, true, StandardCharsets.UTF_8));
            BedrockPalette.mappingInfo(startGame(true, withComponents));
            System.setErr(new PrintStream(none, true, StandardCharsets.UTF_8));
            BedrockPalette.mappingInfo(startGame(true, block("minecraft:dirt")));
        } finally {
            System.setErr(stderr);
        }
        String log = one.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("components or permutations", -1).length - 1, log);
        assertTrue(log.contains("to vanilla blocks, 1 of them (minecraft:dirt), which"), log);
        assertFalse(none.toString(StandardCharsets.UTF_8).contains("components or permutations"), none.toString(StandardCharsets.UTF_8));
    }

    // Geyser 2.12.1 sends these to every 26.50 client: data_driven_blocks.26_50.nbt, copied unchanged from its core jar.
    @Test
    void geyserDataDrivenBlocksOf26_50LogOneLine() throws IOException {
        StartGamePacket startGame = startGame(true);
        try (InputStream in = BedrockPaletteTest.class.getResourceAsStream("/ac/oomph/boarium/boar/geyser/data_driven_blocks.26_50.nbt");
             NBTInputStream nbt = NbtUtils.createGZIPReader(in)) {
            ((NbtMap) nbt.readTag()).forEach((name, properties) -> startGame.getBlockProperties().add(new BlockPropertyData(name, (NbtMap) properties)));
        }
        assertEquals(98, startGame.getBlockProperties().size());
        BedrockBlockTable v26_50 = BedrockBlockTable.forProtocol(2193);
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BlockMappingInfo first = BedrockPalette.mappingInfo(v26_50, startGame);
            // A second player with the same StartGame gets the cached palette, so it logs nothing more.
            assertSame(BedrockPalette.of(first), BedrockPalette.of(BedrockPalette.mappingInfo(v26_50, startGame)));
            assertFalse(BedrockPalette.runtimeIdsUnknown(first));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("components or permutations", -1).length - 1, log);
        assertTrue(Pattern.compile("to vanilla blocks, 98 of them \\((minecraft:\\w+, ){5}\\.\\.\\.\\), which").matcher(log).find(), log);
    }

    private static NbtMap direction(String... states) {
        NbtMapBuilder enabled = NbtMap.builder();
        for (String state : states) {
            enabled.putByte(state, (byte) 1);
        }
        return NbtMap.builder().putString("name", "minecraft:placement_direction").putCompound("enabled_states", enabled.build()).build();
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
        while (toId(first, runtimeId) < BedrockPalette.FIRST_CUSTOM_ID) {
            runtimeId++;
        }
        assertEquals(toId(first, runtimeId), toId(again, runtimeId));
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

    // Asserts that a StartGame with sorted IDs and this one custom block, which sorts before stone, gives the client's palette: the block's states, in this order, just before stone, and stone moved by their number.
    private static void assertStatesBeforeStone(BlockPropertyData block, List<NbtMap> states) {
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(false, block));
        assertTrue(Long.compareUnsigned(BedrockPalette.nameHash(block.getName()), BedrockPalette.nameHash("minecraft:stone")) < 0, block.getName());
        assertFalse(BedrockPalette.runtimeIdsUnknown(info));
        int stone = SORTED_STONE + states.size();
        assertEquals(stone, fromId(info, SORTED_STONE));
        assertEquals(SORTED_STONE, toId(info, stone));
        assertTrue(info.isKnownRawId(stone));
        List<Integer> custom = customRuntimeIds(info, table.paletteSize() + states.size());
        assertEquals(states.size(), custom.size());
        for (int s = 0; s < states.size(); s++) {
            int runtimeId = custom.getFirst() + s;
            assertEquals(runtimeId, custom.get(s));
            SimpleBlockDefinition definition = (SimpleBlockDefinition) definition(BedrockPalette.of(info), toId(info, runtimeId));
            assertEquals(runtimeId, definition.getRuntimeId());
            assertEquals(NbtMap.builder().putString("name", block.getName()).putCompound("states", states.get(s)).build(), definition.getState());
        }
    }

    private static NbtMap state(String key, Object value) {
        return NbtMap.fromMap(Map.of(key, value));
    }

    // Each case once made Boar treat every sorted runtime ID as unknown.
    @Test
    void readsCustomBlocksAsTheClientDoes() {
        // Properties that are not a list of compounds are none.
        assertStatesBeforeStone(new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putCompound("properties", NbtMap.EMPTY).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build()), List.of(NbtMap.EMPTY));
        // A property without a name is named "".
        assertStatesBeforeStone(block("cubecraft:pipe", NbtMap.builder().putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build()), List.of(state("", (byte) 0), state("", (byte) 1)));
        // A property without an "enum" list is skipped.
        NbtMap noEnum = NbtMap.builder().putString("name", "skipped").build();
        assertStatesBeforeStone(block("cubecraft:pipe", noEnum, property("level", NbtType.INT, List.of(1, 2))), List.of(state("level", 1), state("level", 2)));
        // Values of another NBT type, and a value listed twice
        assertStatesBeforeStone(block("cubecraft:pipe", property("f", NbtType.FLOAT, List.of(0.5F, 0.5F))), List.of(state("f", 0.5F), state("f", 0.5F)));
        // Of two properties with the same name, a state shows the last.
        assertStatesBeforeStone(block("cubecraft:pipe", property("f", NbtType.STRING, List.of("n", "e")), property("f", NbtType.STRING, List.of("s", "w"))),
                List.of(state("f", "s"), state("f", "s"), state("f", "w"), state("f", "w")));
        // Of two blocks with the same name, the client keeps the last.
        assertStatesBeforeStone(block("cubecraft:pipe", property("level", NbtType.INT, List.of(1, 2, 3))), List.of(state("level", 1), state("level", 2), state("level", 3)));
        BlockMappingInfo twice = BedrockPalette.mappingInfo(startGame(false, pipe(), block("cubecraft:pipe", property("level", NbtType.INT, List.of(1, 2, 3)))));
        assertEquals(SORTED_STONE + 3, fromId(twice, SORTED_STONE));
    }

    // The client skips a block without a vanilla_block_data compound, so with sorted IDs only the blocks it keeps move stone.
    @Test
    void sortedIdsSkipABlockWithoutVanillaBlockData() {
        assertTrue(Long.compareUnsigned(BedrockPalette.nameHash("cubecraft:vent"), BedrockPalette.nameHash("minecraft:stone")) < 0);
        assertTrue(Long.compareUnsigned(BedrockPalette.nameHash("cubecraft:broken"), BedrockPalette.nameHash("minecraft:stone")) < 0);
        // Without the compound, with a tag of another type, and a block whose property has no values, which the client never reads
        BlockPropertyData otherType = new BlockPropertyData("cubecraft:vent", vent().getProperties().toBuilder().putInt("vanilla_block_data", 0).build());
        BlockPropertyData broken = withoutVanillaBlockData(block("cubecraft:broken", property("open", NbtType.BYTE, List.of())));
        for (BlockPropertyData skipped : List.of(withoutVanillaBlockData(vent()), otherType, broken)) {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(false, skipped, pipe()));
            assertFalse(BedrockPalette.runtimeIdsUnknown(info));
            assertEquals(SORTED_STONE + 8, fromId(info, SORTED_STONE));
            assertEquals(SORTED_STONE, toId(info, SORTED_STONE + 8));
            List<Integer> custom = customRuntimeIds(info);
            assertEquals(8, custom.size());
            for (int runtimeId : custom) {
                assertEquals("cubecraft:pipe", ((SimpleBlockDefinition) definition(info, toId(info, runtimeId))).getIdentifier());
            }
        }
        // The client keeps a block whatever its compound holds, so an empty one will do.
        BlockPropertyData empty = new BlockPropertyData("cubecraft:vent", vent().getProperties().toBuilder().putCompound("vanilla_block_data", NbtMap.EMPTY).build());
        assertEquals(SORTED_STONE + 11, fromId(BedrockPalette.mappingInfo(startGame(false, empty, pipe())), SORTED_STONE));
    }

    // With hashed IDs, the states of a block the client skips are runtime IDs it does not know.
    @Test
    void hashedIdsSkipABlockWithoutVanillaBlockData() throws IOException {
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, withoutVanillaBlockData(vent()), pipe()));
        for (int level = 0; level < 3; level++) {
            int runtimeId = referenceHash("cubecraft:vent", state("level", level));
            assertTrue(table.indexOfHash(runtimeId) < 0);
            assertTrue(toId(info, runtimeId) > BedrockPalette.UNKNOWN_ID, "level " + level);
            assertFalse(info.isKnownRawId(runtimeId));
        }
        int pipe = toId(info, referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 0).build()));
        assertTrue(pipe >= BedrockPalette.FIRST_CUSTOM_ID && pipe < BedrockPalette.UNKNOWN_ID);
        assertTrue(info.isKnownRawId(fromId(info, SORTED_STONE)));
    }

    // The client skips a block without vanilla_block_data before it keeps the last block of a name, so an earlier block of that name stays, and a name whose every block lacks it is gone.
    @Test
    void aBlockWithoutVanillaBlockDataLeavesTheEarlierBlockOfItsName() throws IOException {
        BlockPropertyData levels = block("cubecraft:pipe", property("level", NbtType.INT, List.of(1, 2, 3)));
        assertEquals(SORTED_STONE + 8, fromId(BedrockPalette.mappingInfo(startGame(false, pipe(), withoutVanillaBlockData(levels))), SORTED_STONE));
        assertEquals(SORTED_STONE + 3, fromId(BedrockPalette.mappingInfo(startGame(false, withoutVanillaBlockData(pipe()), levels)), SORTED_STONE));
        BlockMappingInfo neither = BedrockPalette.mappingInfo(startGame(false, withoutVanillaBlockData(pipe()), withoutVanillaBlockData(levels)));
        assertEquals(SORTED_STONE, fromId(neither, SORTED_STONE));
        assertEquals(List.of(), customRuntimeIds(neither));

        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, pipe(), withoutVanillaBlockData(levels)));
        int pipe = toId(hashed, referenceHash("cubecraft:pipe", NbtMap.builder().putString("facing", "north").putByte("lit", (byte) 0).build()));
        assertTrue(pipe >= BedrockPalette.FIRST_CUSTOM_ID && pipe < BedrockPalette.UNKNOWN_ID);
        assertTrue(toId(hashed, referenceHash("cubecraft:pipe", state("level", 1))) > BedrockPalette.UNKNOWN_ID);
    }

    @Test
    void warnsOnceForEachBlockWithoutVanillaBlockData() {
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BlockPropertyData a = withoutVanillaBlockData(block("cubecraft:skipped_a"));
            BlockPropertyData b = withoutVanillaBlockData(block("cubecraft:skipped_b"));
            BedrockPalette.mappingInfo(startGame(false, a, b, a, pipe()));
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("cubecraft:skipped_a has no vanilla_block_data", -1).length - 1, log);
        assertEquals(1, log.split("cubecraft:skipped_b has no vanilla_block_data", -1).length - 1, log);
        assertFalse(log.contains("cubecraft:pipe"), log);
    }

    // 17 bools need 17 bits, past the client's 16: the 17th is always false.
    @Test
    void readsABlockOfMoreThan16Bits() {
        NbtMap[] properties = new NbtMap[17];
        for (int p = 0; p < properties.length; p++) {
            properties[p] = property("p" + (char) ('a' + p), NbtType.BYTE, List.of((byte) 0, (byte) 1));
        }
        List<NbtMap> states = new ArrayList<>();
        for (int s = 0; s < 0x10000; s++) {
            Map<String, Object> state = new TreeMap<>();
            for (int p = 0; p < properties.length; p++) {
                state.put("p" + (char) ('a' + p), (byte) (p < 16 ? s >> p & 1 : 0));
            }
            states.add(NbtMap.fromMap(state));
        }
        assertStatesBeforeStone(block("cubecraft:pipe", properties), states);
    }

    // The client only warns of many states. Boar hashes no more than 2^20 custom states, so past them it does not know the custom states of a palette with hashed IDs.
    @Test
    void readsAPaletteOfMoreThanAMillionStates() {
        BlockPropertyData[] blocks = customStates(17 << 16);
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, blocks));
        assertFalse(BedrockPalette.runtimeIdsUnknown(sorted));
        int before = 0;
        for (BlockPropertyData block : blocks) {
            before += Long.compareUnsigned(BedrockPalette.nameHash(block.getName()), BedrockPalette.nameHash("minecraft:stone")) < 0 ? 0x10000 : 0;
        }
        assertEquals(SORTED_STONE + before, fromId(sorted, SORTED_STONE));
        assertEquals(SORTED_STONE, toId(sorted, SORTED_STONE + before));

        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, blocks));
        assertTrue(hashed.isKnownRawId(fromId(hashed, SORTED_STONE)));
        int many = CustomBlock.networkHash("cubecraft:many_0", NbtMap.builder().putInt("n", 0).build());
        assertTrue(toId(hashed, many) > BedrockPalette.UNKNOWN_ID);
    }

    // Two states of one block with the same network hash share it, and it means the later.
    @Test
    void statesOfOneBlockShareTheirHash() throws IOException {
        // Two values found by search, whose states have the same network hash
        BlockPropertyData clash = block("cubecraft:clash", property("n", NbtType.STRING, List.of("v37092", "v219640")));
        int clashHash = referenceHash("cubecraft:clash", state("n", "v37092"));
        assertEquals(clashHash, referenceHash("cubecraft:clash", state("n", "v219640")));
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, clash));
        BedrockPalette palette = BedrockPalette.of(hashed);
        int later = toId(hashed, clashHash);
        assertTrue(later >= BedrockPalette.FIRST_CUSTOM_ID && later < BedrockPalette.UNKNOWN_ID);
        assertEquals(state("n", "v219640"), ((SimpleBlockDefinition) definition(palette, later)).getState().getCompound("states"));
        assertEquals(clashHash, definition(palette, later - 1).getRuntimeId());
        // Sorted IDs do not use the hash.
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, clash));
        assertTrue(sorted.isKnownRawId(fromId(sorted, SORTED_STONE)));
        assertEquals(2, customRuntimeIds(sorted).size());
    }

    // A state whose network hash a state of another block has takes the next free runtime ID.
    @Test
    void aStateOfAnotherBlockTakesTheNextHash() throws IOException {
        // Values found by search, whose states have the same network hash
        BlockPropertyData a = block("cubecraft:clash_a", property("n", NbtType.STRING, List.of("v205368")));
        BlockPropertyData b = block("cubecraft:clash_b", property("n", NbtType.STRING, List.of("v82536")));
        int hash = referenceHash("cubecraft:clash_a", state("n", "v205368"));
        assertEquals(hash, referenceHash("cubecraft:clash_b", state("n", "v82536")));
        assertTrue(table.indexOfHash(hash) < 0 && table.indexOfHash(hash + 1) < 0);
        boolean aFirst = Long.compareUnsigned(BedrockPalette.nameHash("cubecraft:clash_a"), BedrockPalette.nameHash("cubecraft:clash_b")) < 0;
        String first = aFirst ? "cubecraft:clash_a" : "cubecraft:clash_b";
        String second = aFirst ? "cubecraft:clash_b" : "cubecraft:clash_a";
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, b, a));
        BedrockPalette palette = BedrockPalette.of(info);
        BlockDefinition firstState = definition(palette, toId(info, hash));
        BlockDefinition secondState = definition(palette, toId(info, hash + 1));
        assertEquals(first, ((SimpleBlockDefinition) firstState).getIdentifier());
        assertEquals(second, ((SimpleBlockDefinition) secondState).getIdentifier());
        assertEquals(hash, firstState.getRuntimeId());
        assertEquals(hash + 1, secondState.getRuntimeId());
        assertTrue(info.isKnownRawId(fromId(info, SORTED_STONE)));
    }

    // A custom state whose network hash a vanilla state has: the state that comes first in the sorted palette keeps it, and the other takes the next free ID.
    @Test
    void aCustomStateWithAVanillaHash() throws IOException {
        // Values found by search, whose states have the hash of a vanilla state
        int sculkHash = referenceHash("cubecraft:pipe", state("n", "v583610"));
        int sculk = table.indexOfHash(sculkHash);
        assertEquals("minecraft:sculk_vein", table.name(sculk));
        assertTrue(table.indexOfHash(sculkHash + 1) < 0);
        assertTrue(Long.compareUnsigned(BedrockPalette.nameHash("cubecraft:pipe"), BedrockPalette.nameHash("minecraft:sculk_vein")) < 0);
        int bookshelfHash = referenceHash("cubecraft:lamp", state("n", "v281011"));
        int bookshelf = table.indexOfHash(bookshelfHash);
        assertEquals("minecraft:chiseled_bookshelf", table.name(bookshelf));
        assertTrue(table.indexOfHash(bookshelfHash + 1) < 0);
        assertTrue(Long.compareUnsigned(BedrockPalette.nameHash("minecraft:chiseled_bookshelf"), BedrockPalette.nameHash("cubecraft:lamp")) < 0);

        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, block("cubecraft:pipe", property("n", NbtType.STRING, List.of("v583610"))), block("cubecraft:lamp", property("n", NbtType.STRING, List.of("v281011")))));
        BedrockPalette palette = BedrockPalette.of(info);
        // The pipe comes before sculk_vein, which moves to the next ID.
        int pipe = toId(info, sculkHash);
        assertTrue(pipe >= BedrockPalette.FIRST_CUSTOM_ID && pipe < BedrockPalette.UNKNOWN_ID);
        assertEquals(sculkHash, definition(palette, pipe).getRuntimeId());
        assertEquals(sculk, toId(info, sculkHash + 1));
        assertEquals(sculkHash + 1, fromId(info, sculk));
        assertTrue(info.isKnownRawId(sculkHash + 1));
        assertFalse(info.isKnownRawId(sculkHash));
        // chiseled_bookshelf comes before the lamp, which moves to the next ID.
        assertEquals(bookshelf, toId(info, bookshelfHash));
        assertEquals(bookshelfHash, fromId(info, bookshelf));
        int lamp = toId(info, bookshelfHash + 1);
        assertTrue(lamp >= BedrockPalette.FIRST_CUSTOM_ID && lamp < BedrockPalette.UNKNOWN_ID);
        assertEquals(bookshelfHash + 1, definition(palette, lamp).getRuntimeId());
        assertEquals(state("n", "v281011"), ((SimpleBlockDefinition) definition(palette, lamp)).getState().getCompound("states"));
        assertTrue(info.isKnownRawId(fromId(info, SORTED_STONE)));
    }

    // Every palette with the same custom block shares one Boar block of it, whose default state is the block's first state, as the client places it.
    @Test
    void placesTheFirstStateOfACustomBlock() {
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:placed_pipe", property("facing", NbtType.STRING, List.of("north", "east")))));
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, block("cubecraft:placed_pipe", property("facing", NbtType.STRING, List.of("north", "east")))));
        Block pipe = BedrockPalette.placedBlock(sorted, "cubecraft:placed_pipe");
        assertNotNull(pipe);
        assertSame(pipe, BedrockPalette.placedBlock(hashed, "cubecraft:placed_pipe"));
        assertEquals("cubecraft:placed_pipe", pipe.toString());

        BoarBlockState placed = pipe.defaultBlockState(Vector3i.ZERO, 0);
        assertTrue(placed.intermediaryId() >= BedrockPalette.FIRST_CUSTOM_ID);
        assertEquals(NbtMap.builder().putString("facing", "north").build(), ((SimpleBlockDefinition) definition(sorted, placed.intermediaryId())).getState().getCompound("states"));
        // Each state of the block is a state of that block, and the states of another block are not.
        assertTrue(placed.is(pipe));
        assertTrue(BedrockState.FACTORY.create(placed.intermediaryId() + 1, Vector3i.ZERO, 0).is(pipe));
        BlockMappingInfo other = BedrockPalette.mappingInfo(startGame(false, block("cubecraft:placed_pipe", property("facing", NbtType.STRING, List.of("up", "down")))));
        Block otherPipe = BedrockPalette.placedBlock(other, "cubecraft:placed_pipe");
        assertNotSame(pipe, otherPipe);
        assertFalse(otherPipe.defaultBlockState(Vector3i.ZERO, 0).is(pipe));

        assertNull(BedrockPalette.placedBlock(sorted, "cubecraft:no_such_block"));
        assertNull(BedrockPalette.placedBlock(sorted, "minecraft:stone"));
    }

    // Boar does not place a block whose trait states change its collision, a block in the minecraft namespace, whose state the item's data value picks, a block with a placement filter, or a block of several parts.
    // A block whose trait states keep its collision is placed in its default state.
    @Test
    void placesNoCustomBlockWhoseStateItCannotSet() {
        NbtMap cardinal = trait("minecraft:placement_direction", "cardinal_direction");
        BlockPropertyData logBlock = withTraits(block("cubecraft:placed_log"), cardinal);
        NbtMap disabled = NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build()).build();
        NbtMap open = NbtMap.builder().putString("condition", "q.block_state('minecraft:cardinal_direction') == 'east'").putCompound("components", disabled).build();
        BlockPropertyData gate = withProperties(withTraits(block("cubecraft:placed_gate"), cardinal), properties -> properties.putList("permutations", NbtType.COMPOUND, open).putInt("molangVersion", 12));
        // The connection states change no collision here.
        BlockPropertyData wall = withTraits(block("cubecraft:placed_wall"), NbtMap.builder().putString("name", "minecraft:connection").putInt("type", 0).putInt("enabled_states", 1).build());
        BlockPropertyData door = withTraits(block("cubecraft:placed_door"), NbtMap.builder().putString("name", "minecraft:multi_block").putCompound("enabled_states", NbtMap.builder().putByte("multi_block", (byte) 1).build()).putByte("part_count", (byte) 2).build());
        NbtMap filter = NbtMap.builder().putCompound("minecraft:placement_filter", NbtMap.builder().putList("conditions", NbtType.COMPOUND).build()).build();
        BlockPropertyData torch = withProperties(block("cubecraft:placed_torch"), properties -> properties.putCompound("components", filter));
        BlockPropertyData lamp = withProperties(block("cubecraft:placed_lamp"), properties -> properties.putList("permutations", NbtType.COMPOUND, NbtMap.builder().putString("condition", "1").putCompound("components", filter).build()));
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(false, logBlock, gate, wall, door, torch, lamp, block("minecraft:placed_marble")));
            assertNotNull(BedrockPalette.placedBlock(info, "cubecraft:placed_log"));
            assertNotNull(BedrockPalette.placedBlock(info, "cubecraft:placed_wall"));
            for (String name : List.of("cubecraft:placed_gate", "cubecraft:placed_gate", "cubecraft:placed_door", "cubecraft:placed_torch", "cubecraft:placed_lamp", "minecraft:placed_marble")) {
                assertNull(BedrockPalette.placedBlock(info, name), name);
                assertTrue(BedrockPalette.hasBlock(info, name), name);
            }
            // The gate's states still have their own block.
            int gateId = customRuntimeIds(info, table.paletteSize() + 32).stream().mapToInt(runtimeId -> toId(info, runtimeId))
                    .filter(id -> definition(info, id) instanceof SimpleBlockDefinition definition && definition.getState().getString("name").equals("cubecraft:placed_gate")).findFirst().orElseThrow();
            CustomBlockType type = (CustomBlockType) BedrockState.FACTORY.create(gateId, Vector3i.ZERO, 0).block();
            assertEquals(List.of("minecraft:cardinal_direction"), type.unsetStates());
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("custom block cubecraft:placed_gate before the server does, since its traits add \\[minecraft:cardinal_direction], which change its collision", -1).length - 1, log);
        assertTrue(log.contains("custom block cubecraft:placed_door before the server does, since Boar does not follow its minecraft:multi_block parts"), log);
        assertTrue(log.contains("custom block cubecraft:placed_torch before the server does, since Boar does not check its minecraft:placement_filter"), log);
        assertTrue(log.contains("custom block cubecraft:placed_lamp before the server does, since Boar does not check its minecraft:placement_filter"), log);
        assertTrue(log.contains("custom block minecraft:placed_marble before the server does, since Boar does not know which state its item's data value picks"), log);
        assertFalse(log.contains("placed_log") || log.contains("placed_wall"), log);
    }

    // These warnings count against the palette's limit, and cut long names.
    @Test
    void logsTheCustomBlocksItDoesNotPlaceUpToTheLimit() {
        String longName = "cubecraft:" + "x".repeat(100);
        List<BlockPropertyData> blocks = new ArrayList<>();
        for (int i = 0; i <= BedrockPalette.MAX_WARNED_NAMES + 1; i++) {
            blocks.add(block(i == 0 ? longName : "minecraft:unplaced_" + i));
        }
        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, withProperties(blocks.getFirst(), properties -> properties.putCompound("components", NbtMap.builder().putCompound("minecraft:placement_filter", NbtMap.EMPTY).build()))));
            assertNull(BedrockPalette.placedBlock(info, longName));
            BlockMappingInfo many = BedrockPalette.mappingInfo(startGame(true, blocks.subList(1, blocks.size()).toArray(BlockPropertyData[]::new)));
            for (BlockPropertyData block : blocks.subList(1, blocks.size())) {
                assertNull(BedrockPalette.placedBlock(many, block.getName()));
            }
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("custom block cubecraft:" + "x".repeat(54) + "... before the server does"), log);
        assertFalse(log.contains(longName), log);
        assertEquals(BedrockPalette.MAX_WARNED_NAMES, log.split("custom block minecraft:unplaced_", -1).length - 1);
        assertTrue(log.contains("and logs no more custom blocks of this palette"), log);
    }

    // A custom block without custom IDs has no block of its own.
    @Test
    void placesNoCustomBlockPastTheCap() {
        BedrockPalette palette = new BedrockPalette(false, List.of(CustomBlock.of(pipe())), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1));
        assertNull(BedrockPalette.placedBlock(palette.mappingInfo(), "cubecraft:pipe"));
        assertTrue(BedrockPalette.hasBlock(palette.mappingInfo(), "cubecraft:pipe"));
    }

    private static NbtMap trait(String name, String state) {
        return NbtMap.builder().putString("name", name).putCompound("enabled_states", NbtMap.builder().putByte(state, (byte) 1).build()).build();
    }

    private static BlockPropertyData withTraits(BlockPropertyData block, NbtMap... traits) {
        return withProperties(block, properties -> properties.putList("traits", NbtType.COMPOUND, traits));
    }

    private static BlockPropertyData withProperties(BlockPropertyData block, UnaryOperator<NbtMapBuilder> change) {
        return new BlockPropertyData(block.getName(), change.apply(block.getProperties().toBuilder()).build());
    }

    // Past its limits on hashing, Boar does not read a palette with hashed IDs.
    @Test
    void hashingStopsAtItsLimits() {
        List<CustomBlock> clash = List.of(CustomBlock.of(block("cubecraft:clash_a", property("n", NbtType.STRING, List.of("v205368")))), CustomBlock.of(block("cubecraft:clash_b", property("n", NbtType.STRING, List.of("v82536")))));
        BedrockPalette.Limits limits = BedrockPalette.LIMITS;
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS);
        // The two states clash once.
        new BedrockPalette(true, clash, false, ids, new BedrockPalette.Limits(limits.customIds(), limits.hashedBytes(), 1));
        assertThrows(IllegalArgumentException.class, () -> new BedrockPalette(true, clash, false, ids, new BedrockPalette.Limits(limits.customIds(), limits.hashedBytes(), 0)));
        assertThrows(IllegalArgumentException.class, () -> new BedrockPalette(true, clash, false, ids, new BedrockPalette.Limits(limits.customIds(), 10, limits.clashSteps())));
        // Sorted IDs need no hashes.
        new BedrockPalette(false, clash, false, ids, new BedrockPalette.Limits(limits.customIds(), 10, 0));
    }

    // One palette takes no more than its share of the custom IDs, and its other blocks keep their runtime IDs.
    @Test
    void aPaletteTakesItsShareOfCustomIds() {
        CustomBlock pipe = CustomBlock.of(pipe());
        CustomBlock lamp = CustomBlock.of(lamp());
        BedrockPalette.Limits limits = BedrockPalette.LIMITS;
        BlockMappingInfo info = new BedrockPalette(false, List.of(pipe, lamp), false, new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, BedrockPalette.MAX_CUSTOM_IDS),
                new BedrockPalette.Limits(pipe.stateCount(), limits.hashedBytes(), limits.clashSteps())).mappingInfo();
        int withIds = 0;
        int kept = 0;
        for (int runtimeId = 0; runtimeId < table.paletteSize() + 11; runtimeId++) {
            int id = toId(info, runtimeId);
            withIds += id >= BedrockPalette.FIRST_CUSTOM_ID && id < BedrockPalette.UNKNOWN_ID ? 1 : 0;
            kept += id > BedrockPalette.UNKNOWN_ID && definition(info, id).getRuntimeId() == runtimeId ? 1 : 0;
        }
        assertEquals(pipe.stateCount(), withIds);
        assertEquals(lamp.stateCount(), kept);
    }

    // The client cannot read a property without values, so with sorted IDs, the custom block could have moved any vanilla ID.
    @Test
    void aPropertyWithoutValuesMakesTheIdsUnknown() {
        BlockPropertyData noValues = block("cubecraft:broken", property("open", NbtType.BYTE, List.of()));
        BlockMappingInfo sorted = BedrockPalette.mappingInfo(startGame(false, pipe(), noValues));
        int stone = fromId(sorted, SORTED_STONE);
        assertEquals(SORTED_STONE, stone);
        assertFalse(sorted.isKnownRawId(stone));
        assertFalse(sorted.isKnownRawId(sorted.airId()));
        assertTrue(BedrockPalette.runtimeIdsUnknown(sorted));

        // With hashed IDs, Boar still knows the vanilla IDs.
        BlockMappingInfo hashed = BedrockPalette.mappingInfo(startGame(true, noValues));
        assertTrue(hashed.isKnownRawId(fromId(hashed, SORTED_STONE)));
        assertFalse(BedrockPalette.runtimeIdsUnknown(hashed));
    }
}
