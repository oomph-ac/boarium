package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.oomph.boarium.protocol.BedrockVersions;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The block table of each protocol. Expected values were read with a separate script from the block_palette.<version>.nbt files in Geyser 2.11.0-SNAPSHOT's core jar (26_0 to 26_30) and 2.12.1-SNAPSHOT's (26_40 to 26_60).
class BedrockBlockTablesTest {
    private static final int AIR_HASH = -604749536;
    private static final int STONE_HASH = -2144268767;

    /**
     * @param fingerprint {@link BedrockBlockTableTest#fingerprint} of Geyser's palette
     * @param block a block that is new in this version, or one that is in every version for 26.0, with the runtime ID (sorted, then hashed) of its first state
     */
    record Version(int protocol, String source, int size, String fingerprint, int air, int stone, String block, int blockIndex, int blockHash) {
        @Override
        public String toString() {
            return this.source;
        }
    }

    static List<Version> versions() {
        return List.of(
                new Version(924, "1.26.0.2", 15845, "707f83339b775bbcfbb3b68f4cc6a05332f6700c53882fdf90d99dbd82d81b03", 12530, 2532, "minecraft:oak_stairs", 1357, -1054044407),
                new Version(944, "1.26.12.2", 15846, "1a2f804173003f00c8c5c2e3722812adbb0189ba7f62f00e7c0460676aace48d", 12531, 2533, "minecraft:golden_dandelion", 1704, -758812583),
                new Version(975, "1.26.20.4", 16899, "fca5d0dcf1d4a439aa04acbb80e889397632c851a0fe4e39be6f6cd1a571e79c", 13080, 2696, "minecraft:sulfur_wall", 7087, 1535325837),
                new Version(1001, "1.26.33.1", 16913, "966564b76e8d97da64e7bae92473b79c7c435924659a76c38a341fb0d6bc49ea", 13094, 2706, "minecraft:sulfur_spike", 1533, -278983287),
                new Version(2168, "1.26.40.8", 17499, "42aa612a5fce3723e23e98820a80b84a97595c6599a5eb91b0509677719c0db3", 13629, 2736, "minecraft:yellow_poplar_leaves", 15940, -700046363),
                new Version(2193, "1.26.50.5", 22091, "066dbc28219ef2ccc8c7cf47bcfa090c52cc8e6f317c7163dcb2fe5faa13aab3", 17025, 3317, "minecraft:yellow_wool_stairs", 7155, -829473644),
                new Version(2225, "1.26.60.6", 23960, "85d576052589510e387b55b038165ae272d3fc49248549c5c9728af13a5e0eb2", 18894, 3317, "minecraft:icicle", 6673, -849936457));
    }

    @ParameterizedTest
    @MethodSource("versions")
    void tableIsGeysers(Version version) throws NoSuchAlgorithmException {
        BedrockBlockTable table = BedrockBlockTable.forProtocol(version.protocol());
        assertEquals(version.protocol(), table.protocol());
        assertEquals(version.source(), table.sourceVersion());
        assertEquals(version.size(), table.paletteSize());
        assertEquals(version.fingerprint(), BedrockBlockTableTest.fingerprint(table.paletteSize(), table::name, table::states, table::networkHash));
        assertEquals(version.air(), table.firstIndex("minecraft:air"));
        assertEquals(AIR_HASH, table.networkHash(version.air()));
        assertEquals(version.stone(), table.firstIndex("minecraft:stone"));
        assertEquals(STONE_HASH, table.networkHash(version.stone()));
        assertEquals(version.blockIndex(), table.firstIndex(version.block()));
        assertEquals(version.blockHash(), table.networkHash(version.blockIndex()));
        // Stairs have their corner as a state from 26.50.
        assertEquals(version.protocol() >= 2193, table.states(table.firstIndex("minecraft:oak_stairs")).containsKey("minecraft:corner"));
    }

    @Test
    void everyProtocolHasATable() {
        for (BedrockVersions.Version version : BedrockVersions.SUPPORTED) {
            assertNotNull(BedrockBlockTable.forProtocol(version.codec().getProtocolVersion()), version.names().toString());
        }
        assertSame(BedrockBlockTable.forProtocol(2168), BedrockBlockTable.forProtocol(2169));
        assertSame(BedrockBlockTable.get(), BedrockBlockTable.forProtocol(1001));
        assertNull(BedrockBlockTable.forProtocol(1000));
    }

    // A palette of another table has the runtime IDs of its own clients, and custom IDs past its own vanilla states.
    @ParameterizedTest
    @MethodSource("versions")
    void paletteOfEachTable(Version version) {
        BedrockBlockTable table = BedrockBlockTable.forProtocol(version.protocol());
        for (boolean hashed : new boolean[]{false, true}) {
            BlockMappingInfo info = BedrockPalette.mappingInfo(table, startGame(hashed));
            assertEquals(version.stone(), info.toIntermediary().applyAsInt(hashed ? STONE_HASH : version.stone()));
            assertEquals(hashed ? version.blockHash() : version.blockIndex(), info.fromIntermediary().applyAsInt(version.blockIndex()));
            assertEquals(hashed ? AIR_HASH : version.air(), info.airDefinition().getRuntimeId());

            BlockMappingInfo custom = BedrockPalette.mappingInfo(table, startGame(hashed, pipe()));
            int first = -1;
            for (int id = table.paletteSize(); id < table.paletteSize() + BedrockPalette.MAX_CUSTOM_IDS && first < 0; id++) {
                if (!BedrockPalette.isUnknown(BedrockPalette.definition(custom, id))) {
                    first = id;
                }
            }
            assertTrue(first >= table.paletteSize(), version.source());
            for (int state = 0; state < 8; state++) {
                int runtimeId = BedrockPalette.definition(custom, first + state).getRuntimeId();
                assertEquals(first + state, custom.toIntermediary().applyAsInt(runtimeId));
            }
            // Boar places a custom block only for its own table, whose IDs its block states have.
            assertEquals(table == BedrockBlockTable.get(), BedrockPalette.placedBlock(custom, "cubecraft:pipe") != null);
            if (table != BedrockBlockTable.get()) {
                assertNotSame(BedrockPalette.of(custom), BedrockPalette.of(BedrockPalette.mappingInfo(startGame(hashed, pipe()))));
            }
            BedrockPalette.release(info);
            BedrockPalette.release(custom);
        }
    }

    // A table loaded again shares the palettes and custom IDs of its protocol's table.
    @Test
    void tableLoadedAgain() {
        BlockMappingInfo again = BedrockPalette.mappingInfo(BedrockBlockTable.load(), startGame(true, pipe()));
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, pipe()));
        assertSame(BedrockPalette.of(info), BedrockPalette.of(again));
        BedrockPalette.release(again);
        BedrockPalette.release(info);
    }

    // Each table is about 7 to 11 MiB; boarium loads one when it first needs it.
    @Test
    void heap() throws InterruptedException {
        List<BedrockBlockTable> tables = new ArrayList<>();
        long before = usedHeap();
        for (String version : List.of("26_0", "26_10", "26_20", "26_30", "26_40", "26_50", "26_60")) {
            tables.add(BedrockBlockTable.load("/ac/oomph/boarium/boar/blocks/bedrock_blocks." + version + ".bin.gz"));
        }
        long used = usedHeap() - before;
        System.out.println("Seven block tables take " + used / 1024 + " KiB of heap");
        assertTrue(used < 96L << 20, used / 1024 + " KiB");
        assertEquals(7, tables.size());
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

    // 8 states
    private static BlockPropertyData pipe() {
        NbtMap facing = NbtMap.builder().putString("name", "facing").putList("enum", NbtType.STRING, List.of("north", "east", "south", "west")).build();
        NbtMap lit = NbtMap.builder().putString("name", "lit").putList("enum", NbtType.BYTE, List.of((byte) 0, (byte) 1)).build();
        return new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("properties", NbtType.COMPOUND, facing, lit).putCompound("components", NbtMap.EMPTY)
                .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 0).build()).build());
    }

    private static StartGamePacket startGame(boolean hashed, BlockPropertyData... blocks) {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(hashed);
        startGame.getBlockProperties().addAll(List.of(blocks));
        return startGame;
    }
}
