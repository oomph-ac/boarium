package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Custom IDs and shapes of blocks that no palette has any more are freed. Tests that free IDs give their palettes custom IDs and a clock of their own.
class CustomIdsTest {
    private static final NbtMap VANILLA_BLOCK_DATA = NbtMap.builder().putInt("block_id", 0).build();
    private static final long QUARANTINE = BedrockPalette.CustomIds.QUARANTINE;
    private static final AtomicInteger SHAPES_MADE = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
    }

    // A block of this many states, each a box of a height no other block made here has
    private static CustomBlock block(String name, int states) {
        List<NbtMap> permutations = new ArrayList<>();
        for (int s = 0; s < states; s++) {
            float height = 1 + SHAPES_MADE.incrementAndGet() / 8192F;
            NbtMap box = NbtMap.builder().putFloat("minX", 0).putFloat("minY", 0).putFloat("minZ", 0).putFloat("maxX", 16).putFloat("maxY", height).putFloat("maxZ", 16).build();
            NbtMap collision = NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND, box).build();
            NbtMap components = NbtMap.builder().putCompound("minecraft:collision_box", collision).build();
            permutations.add(NbtMap.builder().putString("condition", "q.block_state('p') == " + s).putCompound("components", components).build());
        }
        NbtMap property = NbtMap.builder().putString("name", "p").putList("enum", NbtType.INT, IntStream.range(0, states).boxed().toList()).build();
        return CustomBlock.of(new BlockPropertyData(name, NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, property)
                .putCompound("components", NbtMap.EMPTY)
                .putList("permutations", NbtType.COMPOUND, permutations)
                .putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build()));
    }

    // The first custom ID of a block in a mapping with hashed IDs, or -1 when the block has none
    private static int firstId(BlockMappingInfo info, CustomBlock block) {
        int id = info.toIntermediary().applyAsInt(CustomBlock.networkHash(block.name(), block.state(0)));
        return id >= BedrockPalette.FIRST_CUSTOM_ID && id < BedrockPalette.UNKNOWN_ID ? id : -1;
    }

    private static int firstId(BedrockPalette.CustomIds ids, CustomBlock block) {
        return ids.of(List.of(block), Long.MAX_VALUE)[0];
    }

    private static int sharedShapesAfterGc() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(20);
        }
        return CustomCollision.sharedShapes();
    }

    // 10,000 servers in a row, each with a new block of 8 states, ask for 80,000 IDs and shapes, more than the 65,536 of each that Boar keeps.
    @Test
    void palettesThatComeAndGoKeepTheirIdsAndShapesBounded() throws InterruptedException {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 1 << 16, QUARANTINE, () -> now[0]);
        int shapesBefore = sharedShapesAfterGc();
        for (int i = 0; i < 10_000; i++) {
            CustomBlock block = block("cubecraft:churn_" + i, 8);
            assertNull(block.collision().guess(), "palette " + i);
            BlockMappingInfo info = new BedrockPalette(true, List.of(block), false, ids).mappingInfo();
            int first = firstId(info, block);
            assertTrue(first >= 0, "palette " + i);
            for (int s = 0; s < 8; s++) {
                assertSame(block.collision().boxes(s), ids.boxes(first + s));
            }
            BedrockPalette.release(info);
            now[0] += QUARANTINE;
        }
        assertTrue(sharedShapesAfterGc() < shapesBefore + 100);
    }

    // A block keeps its IDs while a palette of any block table has it, and they are freed after the last one lets go.
    @Test
    void palettesOfEveryTableShareOnePool() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 64, QUARANTINE, () -> now[0]);
        CustomBlock pipe = block("cubecraft:shared_pipe", 8);
        BlockMappingInfo v26_30 = new BedrockPalette(BedrockBlockTable.get(), true, List.of(pipe), ids).mappingInfo();
        BlockMappingInfo v26_50 = new BedrockPalette(BedrockBlockTable.forProtocol(2193), true, List.of(pipe), ids).mappingInfo();
        assertEquals(BedrockPalette.FIRST_CUSTOM_ID, firstId(v26_30, pipe));
        assertEquals(BedrockPalette.FIRST_CUSTOM_ID, firstId(v26_50, pipe));

        BedrockPalette.release(v26_30);
        now[0] += QUARANTINE;
        assertEquals(BedrockPalette.FIRST_CUSTOM_ID + 8, firstId(ids, block("cubecraft:after_one", 8)));
        assertEquals("cubecraft:shared_pipe", ids.type(BedrockPalette.FIRST_CUSTOM_ID).toString());

        BedrockPalette.release(v26_50);
        now[0] += QUARANTINE;
        assertEquals(BedrockPalette.FIRST_CUSTOM_ID, firstId(ids, block("cubecraft:after_both", 8)));
    }

    @Test
    void freedIdsWaitOutTheQuarantineAndLeaveHoles() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 12, QUARANTINE, () -> now[0]);
        CustomBlock a = block("cubecraft:a", 8);
        CustomBlock b = block("cubecraft:b", 3);
        CustomBlock c = block("cubecraft:c", 1);
        CustomBlock d = block("cubecraft:d", 2);
        assertEquals(List.of(100, 108, 111), IntStream.of(ids.of(List.of(a, b, c), Long.MAX_VALUE)).boxed().toList());

        PrintStream stderr = System.err;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        System.setErr(new PrintStream(logged, true, StandardCharsets.UTF_8));
        try {
            ids.release(new CustomBlock[] {b});
            now[0] += QUARANTINE - 1;
            // B's IDs are not reused yet, and still read as B.
            assertEquals(-1, firstId(ids, d));
            assertEquals("cubecraft:b", ids.type(110).toString());
            assertSame(b.collision().boxes(2), ids.boxes(110));
            // B comes back to its IDs, and leaves again.
            assertEquals(108, firstId(ids, b));
            ids.release(new CustomBlock[] {b});
        } finally {
            System.setErr(stderr);
        }
        String log = logged.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("cubecraft:d") && log.contains("3 of them are held"), log);

        now[0] += QUARANTINE;
        assertEquals(108, firstId(ids, d));
        // D takes 108 and 109 of B's 108 to 110, so 110 has no block.
        assertEquals("cubecraft:a", ids.type(107).toString());
        assertEquals("cubecraft:d", ids.type(109).toString());
        assertNull(ids.type(110));
        assertSame(CustomCollision.FULL_CUBE, ids.boxes(110));
        assertEquals("cubecraft:c", ids.type(111).toString());

        // Freed neighbours merge: A's 8, D's 2 and the hole make room for 11 states at 100.
        ids.release(new CustomBlock[] {a, d});
        now[0] += QUARANTINE;
        CustomBlock e = block("cubecraft:e", 11);
        assertEquals(100, firstId(ids, e));
        assertEquals("cubecraft:e", ids.type(110).toString());
        assertEquals("cubecraft:c", ids.type(111).toString());
    }

    // X is released, taken again and released again at the same time, which leaves two releases of X waiting. Its IDs are freed once, so Y and Z fill the 11 IDs and nothing is left.
    @Test
    void aBlockReleasedTwiceAtOneTimeIsFreedOnce() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 11, QUARANTINE, () -> now[0]);
        CustomBlock a = block("cubecraft:once_a", 8);
        CustomBlock x = block("cubecraft:once_x", 3);
        assertEquals(List.of(100, 108), IntStream.of(ids.of(List.of(a, x), Long.MAX_VALUE)).boxed().toList());
        ids.release(new CustomBlock[] {a, x});
        assertEquals(108, firstId(ids, x));
        ids.release(new CustomBlock[] {x});

        now[0] += QUARANTINE;
        CustomBlock y = block("cubecraft:once_y", 3);
        CustomBlock z = block("cubecraft:once_z", 8);
        assertEquals(List.of(100, 103), IntStream.of(ids.of(List.of(y, z), Long.MAX_VALUE)).boxed().toList());
        assertEquals(-1, firstId(ids, block("cubecraft:once_w", 1)));
        assertEquals("cubecraft:once_z", ids.type(110).toString());
    }

    // A block too big for any hole does not keep smaller blocks out of them.
    @Test
    void smallBlocksStillFitTheHolesABigBlockDoesNot() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 64, QUARANTINE, () -> now[0]);
        List<CustomBlock> blocks = IntStream.range(0, 8).mapToObj(i -> block("cubecraft:hole_" + i, 8)).toList();
        ids.of(blocks, Long.MAX_VALUE);
        ids.release(new CustomBlock[] {blocks.get(0), blocks.get(2), blocks.get(4), blocks.get(6)});
        now[0] += QUARANTINE;
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            assertEquals(-1, firstId(ids, block("cubecraft:hole_big", 16)));
        } finally {
            System.setErr(stderr);
        }
        assertEquals(100, firstId(ids, block("cubecraft:hole_small", 2)));
    }

    // Free ranges only shrink until IDs are freed, so after a block of 16 states finds none, blocks of 16 or more states fail without looking at them.
    @Test
    void blocksAsLargeAsOneThatFoundNoRangeFailWithoutAScan() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(100, 64, QUARANTINE, () -> now[0]);
        List<CustomBlock> blocks = IntStream.range(0, 8).mapToObj(i -> block("cubecraft:miss_" + i, 8)).toList();
        ids.of(blocks, Long.MAX_VALUE);
        ids.release(new CustomBlock[] {blocks.get(0), blocks.get(2)});
        now[0] += QUARANTINE;
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            assertEquals(-1, firstId(ids, block("cubecraft:miss_16", 16)));
            int scanned = ids.scannedRanges;
            assertEquals(-1, firstId(ids, block("cubecraft:miss_other_16", 16)));
            assertEquals(-1, firstId(ids, block("cubecraft:miss_20", 20)));
            assertEquals(scanned, ids.scannedRanges);
            assertEquals(100, firstId(ids, block("cubecraft:miss_4", 4)));
            assertTrue(ids.scannedRanges > scanned);

            // Freeing 108 to 115 joins it to 104 to 107 and 116 to 123, where 16 states fit.
            ids.release(new CustomBlock[] {blocks.get(1)});
            now[0] += QUARANTINE;
            assertEquals(104, firstId(ids, block("cubecraft:miss_16_again", 16)));
        } finally {
            System.setErr(stderr);
        }
    }

    // Only blocks with custom IDs count against the cap on shapes, whether or not the shapes of other blocks were collected yet.
    @Test
    void theShapeCapCountsTheShapesOfBlocksWithIds() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 64, QUARANTINE, () -> now[0]);
        int before = CustomCollision.heldShapes();
        CustomBlock block = block("cubecraft:held", 3);
        assertEquals(before, CustomCollision.heldShapes());
        firstId(ids, block);
        assertEquals(before + 3, CustomCollision.heldShapes());
        // A second palette with the block adds no shapes.
        firstId(ids, block);
        assertEquals(before + 3, CustomCollision.heldShapes());
        ids.release(new CustomBlock[] {block, block});
        now[0] += QUARANTINE;
        ids.of(List.of(), Long.MAX_VALUE);
        assertEquals(before, CustomCollision.heldShapes());

        List<CustomBlock> kept = new ArrayList<>();
        for (int i = 0; i <= CustomCollision.MAX_SHAPES; i++) {
            CustomBlock unused = block("cubecraft:unused_" + i, 1);
            if (i % 4096 == 0) {
                kept.add(unused);
            }
        }
        assertNull(block("cubecraft:after_unused", 1).collision().guess());
        assertEquals(before, CustomCollision.heldShapes(), kept.size() + " blocks kept");
    }

    @Test
    void releasingAMappingTwiceLeavesTheOtherPlayersBlock() {
        long[] now = {0};
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 64, QUARANTINE, () -> now[0]);
        CustomBlock block = block("cubecraft:twice", 4);
        BedrockPalette palette = new BedrockPalette(true, List.of(block), false, ids);
        BlockMappingInfo first = palette.mappingInfo();
        BlockMappingInfo second = palette.mappingInfo();
        int id = firstId(first, block);

        BedrockPalette.release(first);
        BedrockPalette.release(first);
        assertTrue(BedrockPalette.released(first));
        now[0] += QUARANTINE;
        ids.of(List.of(), Long.MAX_VALUE);
        assertEquals("cubecraft:twice", ids.type(id).toString());

        BedrockPalette.release(second);
        now[0] += QUARANTINE;
        ids.of(List.of(), Long.MAX_VALUE);
        assertNull(ids.type(id));
        // The block's IDs may belong to another block now, so its palette makes no more mappings.
        assertThrows(IllegalStateException.class, palette::mappingInfo);
    }

    // The cache holds its palettes, and lets go of the one it drops.
    @Test
    void theCacheLetsGoOfThePalettesItDrops() {
        String name = "cubecraft:cached_" + System.nanoTime();
        CustomBlock block = CustomBlock.of(block(name));
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(block(name)));
        BedrockPalette palette = BedrockPalette.of(info);
        BedrockPalette.release(info);
        BlockMappingInfo again = BedrockPalette.mappingInfo(startGame(block(name)));
        assertSame(palette, BedrockPalette.of(again));
        BedrockPalette.release(again);

        for (int i = 0; i < 8; i++) {
            BedrockPalette.release(BedrockPalette.mappingInfo(startGame(block(name + "_" + i))));
        }
        assertThrows(IllegalStateException.class, palette::mappingInfo);
        BlockMappingInfo rebuilt = BedrockPalette.mappingInfo(startGame(block(name)));
        assertNotSame(palette, BedrockPalette.of(rebuilt));
        // The block is still in quarantine, so the new palette gets its IDs back.
        assertTrue(firstId(info, block) >= 0);
        assertEquals(firstId(info, block), firstId(rebuilt, block));
        BedrockPalette.release(rebuilt);
    }

    private static BlockPropertyData block(String name) {
        return new BlockPropertyData(name, NbtMap.builder().putCompound("components", NbtMap.EMPTY).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA).build());
    }

    private static StartGamePacket startGame(BlockPropertyData block) {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(true);
        startGame.getBlockProperties().add(block);
        return startGame;
    }

    // Threads build and release palettes of blocks from one pool, with IDs freed at once and too few for every block, and each palette reads its own blocks while it holds them.
    @Test
    void palettesBuiltAndReleasedOnManyThreadsReadTheirOwnBlocks() throws Exception {
        BedrockPalette.CustomIds ids = new BedrockPalette.CustomIds(BedrockPalette.FIRST_CUSTOM_ID, 128, 0, System::nanoTime);
        List<CustomBlock> pool = IntStream.range(0, 48).mapToObj(i -> block("cubecraft:stress_" + i, 1 + i % 8)).toList();
        AtomicInteger wrong = new AtomicInteger();
        AtomicInteger read = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(threads.submit(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 1000; i++) {
                        List<CustomBlock> blocks = new ArrayList<>(pool);
                        Collections.shuffle(blocks, random);
                        blocks = blocks.subList(0, 1 + random.nextInt(4));
                        BlockMappingInfo info = new BedrockPalette(true, blocks, false, ids).mappingInfo();
                        for (CustomBlock block : blocks) {
                            int first = firstId(info, block);
                            if (first < 0) {
                                continue;
                            }
                            read.incrementAndGet();
                            for (int s = 0; s < block.stateCount(); s++) {
                                List<Box> boxes = ids.boxes(first + s);
                                CustomBlockType type = ids.type(first + s);
                                if (boxes != block.collision().boxes(s) || type == null || !type.toString().equals(block.name())) {
                                    wrong.incrementAndGet();
                                }
                            }
                        }
                        BedrockPalette.release(info);
                    }
                    return null;
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        } finally {
            threads.shutdownNow();
        }
        assertEquals(0, wrong.get());
        assertTrue(read.get() > 1000, "only " + read.get() + " blocks got IDs");
        assertFalse(ids.of(List.of(block("cubecraft:after", 128)), Long.MAX_VALUE)[0] < 0, "the IDs are not all free again");
    }
}
