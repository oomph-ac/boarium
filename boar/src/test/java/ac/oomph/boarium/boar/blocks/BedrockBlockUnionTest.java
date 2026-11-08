package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockBlockUnionTest {
    private static final int V26_30 = 1001;
    private static final int V26_50 = 2193;
    private static final int V26_60 = 2225;
    private static final List<Integer> PROTOCOLS = BedrockBlockTable.VERSIONS.keySet().stream().sorted().toList();

    private final BedrockBlockUnion union = BedrockBlockUnion.get();
    private final BedrockBlockTable shared = union.table();

    @Test
    void counts() {
        Set<String> names = new HashSet<>();
        for (int id = 0; id < shared.paletteSize(); id++) {
            names.add(shared.name(id));
        }
        System.out.println("The shared block table has " + shared.paletteSize() + " states of " + names.size() + " names");
        assertEquals(24587, shared.paletteSize());
        assertEquals(1480, names.size());
    }

    @Test
    void hashesAreUnique() {
        Set<Integer> hashes = new HashSet<>();
        for (int id = 0; id < shared.paletteSize(); id++) {
            assertTrue(hashes.add(shared.networkHash(id)), shared.name(id) + " " + shared.states(id));
            assertEquals(id, shared.indexOfHash(shared.networkHash(id)));
        }
    }

    @Test
    void ownToSharedToOwnIsTheSameEntry() {
        for (int protocol : PROTOCOLS) {
            BedrockBlockTable own = BedrockBlockTable.forProtocol(protocol);
            for (int i = 0; i < own.paletteSize(); i++) {
                int id = union.toShared(protocol, i);
                assertEquals(own.name(i), shared.name(id));
                assertEquals(own.states(i), shared.states(id));
                assertEquals(own.networkHash(i), shared.networkHash(id));
                assertEquals(i, union.fromShared(protocol, id), protocol + " " + own.name(i) + " " + own.states(i));
            }
        }
    }

    // Each name's default is the default of the newest table that has the name.
    @Test
    void defaultsAreTheNewestTables() {
        List<BedrockBlockTable> newestFirst = PROTOCOLS.reversed().stream().map(BedrockBlockTable::forProtocol).distinct().toList();
        for (int id = 0; id < shared.paletteSize(); id += shared.stateCount(shared.name(id))) {
            String name = shared.name(id);
            BedrockBlockTable newest = newestFirst.stream().filter(table -> table.defaultIndex(name) >= 0).findFirst().orElseThrow();
            assertEquals(newest.states(newest.defaultIndex(name)), shared.states(shared.defaultIndex(name)), name);
        }
    }

    // A 26.30 fence has no states; from 26.50 it has one per side it connects to.
    @Test
    void fenceProjectsToNoConnections() {
        BedrockBlockTable v26_30 = BedrockBlockTable.forProtocol(V26_30);
        BedrockBlockTable v26_50 = BedrockBlockTable.forProtocol(V26_50);
        int old = v26_30.index("minecraft:oak_fence", NbtMap.EMPTY);
        int id = union.toShared(V26_30, old);
        int projected = union.fromShared(V26_50, id);
        assertEquals(NbtMap.builder().putByte("minecraft:connection_east", (byte) 0).putByte("minecraft:connection_north", (byte) 0)
                .putByte("minecraft:connection_south", (byte) 0).putByte("minecraft:connection_west", (byte) 0).build(), v26_50.states(projected));
        assertEquals(old, union.fromShared(V26_30, union.toShared(V26_50, projected)));

        int connected = v26_50.index("minecraft:oak_fence", v26_50.states(projected).toBuilder().putByte("minecraft:connection_north", (byte) 1).build());
        assertNotEquals(-1, connected);
        assertEquals(old, union.fromShared(V26_30, union.toShared(V26_50, connected)));
    }

    @Test
    void cornerStairProjectsToStraightStair() {
        BedrockBlockTable v26_30 = BedrockBlockTable.forProtocol(V26_30);
        BedrockBlockTable v26_50 = BedrockBlockTable.forProtocol(V26_50);
        NbtMap corner = NbtMap.builder().putString("minecraft:corner", "inner_left").putByte("upside_down_bit", (byte) 1).putInt("weirdo_direction", 2).build();
        int index = v26_50.index("minecraft:oak_stairs", corner);
        assertNotEquals(-1, index);
        int projected = union.fromShared(V26_30, union.toShared(V26_50, index));
        assertEquals(NbtMap.builder().putByte("upside_down_bit", (byte) 1).putInt("weirdo_direction", 2).build(), v26_30.states(projected));
        assertEquals(v26_50.index("minecraft:oak_stairs", corner.toBuilder().putString("minecraft:corner", "none").build()), union.fromShared(V26_50, union.toShared(V26_30, projected)));
    }

    @Test
    void newBlockIsMissingOnOlderVersions() {
        BedrockBlockTable v26_60 = BedrockBlockTable.forProtocol(V26_60);
        assertEquals(-1, BedrockBlockTable.forProtocol(V26_50).firstIndex("minecraft:icicle"));
        int id = union.toShared(V26_60, v26_60.firstIndex("minecraft:icicle"));
        assertEquals(-1, union.fromShared(V26_30, id));
        assertEquals(-1, union.fromShared(V26_50, id));
    }

    // A value the version lacks for one key does not lose the values of the other keys.
    @Test
    void newValueKeepsTheOtherKeys() {
        List<NbtMap> stairs = new ArrayList<>();
        for (byte upsideDown = 0; upsideDown < 2; upsideDown++) {
            for (int direction = 0; direction < 4; direction++) {
                stairs.add(NbtMap.builder().putByte("upside_down_bit", upsideDown).putInt("weirdo_direction", direction).build());
            }
        }
        int size = stairs.size();
        String[] names = new String[size];
        Arrays.fill(names, "minecraft:oak_stairs");
        int[] hashes = new int[size];
        Arrays.setAll(hashes, i -> i);
        BedrockBlockTable own = new BedrockBlockTable(1, "test", 1, new float[][]{new float[0]}, names, stairs.toArray(NbtMap[]::new), hashes, new char[size], new byte[size], new float[size], new float[size], new int[]{0});

        NbtMap newDirection = NbtMap.builder().putByte("upside_down_bit", (byte) 1).putInt("weirdo_direction", 4).build();
        int projected = BedrockBlockUnion.project(own, "minecraft:oak_stairs", newDirection, BedrockBlockUnion.values(own, "minecraft:oak_stairs"));
        assertEquals(NbtMap.builder().putByte("upside_down_bit", (byte) 1).putInt("weirdo_direction", 0).build(), own.states(projected));
    }

    // The real tables never need the per-key fallback: every shared value of a key a version has for a name is one of that version's values for it.
    @Test
    void realTablesHaveEveryValue() {
        List<String> missing = new ArrayList<>();
        for (BedrockBlockTable own : PROTOCOLS.stream().map(BedrockBlockTable::forProtocol).distinct().toList()) {
            for (int id = 0; id < shared.paletteSize(); id += shared.stateCount(shared.name(id))) {
                String name = shared.name(id);
                Map<String, Set<Object>> values = BedrockBlockUnion.values(own, name);
                for (int i = id; i < id + shared.stateCount(name); i++) {
                    shared.states(i).forEach((key, value) -> {
                        if (values.containsKey(key) && !values.get(key).contains(value)) {
                            missing.add(own.sourceVersion() + " " + name + " " + key + "=" + value);
                        }
                    });
                }
            }
        }
        assertEquals(List.of(), missing, "A version lacks a value that a newer or older one has for the same key; check whether the new value changes what another key means before trusting the per-key fallback");
    }

    @Test
    void indexesOutOfRangeThrow() {
        assertThrows(IllegalArgumentException.class, () -> union.toShared(V26_30, BedrockBlockTable.forProtocol(V26_30).paletteSize()));
        assertThrows(IllegalArgumentException.class, () -> union.fromShared(V26_30, -1));
        assertThrows(IllegalArgumentException.class, () -> union.fromShared(1000, 0));
    }

    // The resource must be what build makes from the tables beside it: ./gradlew :boar:generateBedrockBlockUnion writes it.
    @Test
    void resourceIsTheBuiltTable() {
        BedrockBlockTable built = BedrockBlockUnion.build();
        assertEquals(built.sourceVersion(), shared.sourceVersion());
        assertEquals(built.paletteSize(), shared.paletteSize());
        for (int id = 0; id < built.paletteSize(); id++) {
            assertEquals(built.name(id), shared.name(id));
            assertEquals(built.states(id), shared.states(id));
            assertEquals(built.networkHash(id), shared.networkHash(id));
            assertArrayEquals(built.collision(id), shared.collision(id));
            assertEquals(built.flags(id), shared.flags(id));
            assertEquals(built.hardness(id), shared.hardness(id));
            assertEquals(built.friction(id), shared.friction(id));
            assertEquals(built.defaultIndex(built.name(id)), shared.defaultIndex(shared.name(id)));
        }
    }

    // Builds a second union from the tables already loaded, so it measures the merge alone.
    @Test
    void timeAndHeap() throws InterruptedException {
        for (int protocol : PROTOCOLS) {
            BedrockBlockTable.forProtocol(protocol);
        }
        long tablesStart = System.nanoTime();
        for (String version : Set.copyOf(BedrockBlockTable.VERSIONS.values())) {
            BedrockBlockTable.load("/ac/oomph/boarium/boar/blocks/bedrock_blocks." + version + ".bin.gz");
        }
        long tablesTime = System.nanoTime() - tablesStart;

        long before = usedHeap();
        long start = System.nanoTime();
        BedrockBlockTable again = BedrockBlockUnion.build();
        long time = System.nanoTime() - start;
        long used = usedHeap() - before;
        System.out.println("Seven block tables load in " + tablesTime / 1_000_000 + " ms; the shared table builds in " + time / 1_000_000 + " ms and takes " + used / 1024 + " KiB of heap");
        assertEquals(shared.paletteSize(), again.paletteSize());
        assertTrue(used < 32L << 20, used / 1024 + " KiB");
    }

    // In a JVM of its own, so the other tests' tables do not count: at start Boar loads the shared table and the tables of the protocols it checks, five since 26.45 shares 26.40's.
    @Test
    void heapAtStart() throws Exception {
        Path dir = Files.createDirectories(Path.of("build"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), HeapAtStart.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        List<String> lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        assertEquals(0, process.waitFor(), String.join("\n", lines));
        Map<String, String[]> heap = new HashMap<>();
        lines.stream().map(line -> line.split(" ")).filter(words -> words.length == 3).forEach(words -> heap.put(words[0], words));
        long tables = Long.parseLong(heap.get("tables")[1]);
        long start = Long.parseLong(heap.get("start")[1]);
        long all = Long.parseLong(heap.get("all")[1]);
        System.out.println("The shared and 1.26.30 block tables take " + tables / 1024 + " KiB of heap; Boar takes " + start / 1024 + " KiB at start with " + heap.get("start")[2] + " protocol tables loaded, and " + all / 1024 + " KiB with all " + heap.get("all")[2]);
        assertEquals("5", heap.get("start")[2]);
        assertTrue(tables < 32L << 20, tables / 1024 + " KiB");
        // About 91 MiB, so one more protocol table at start (about 7 MiB) fails this
        assertTrue(start < 95L << 20, start / 1024 + " KiB");
    }

    private static long usedHeap() throws InterruptedException {
        long least = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
            least = Math.min(least, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        }
        return least;
    }
}
