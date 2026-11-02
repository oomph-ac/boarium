package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.FallingFlowMaterials;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.boar.mappings.block.HorseJumpMaterials;
import it.unimi.dsi.fastutil.ints.IntList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * The Bedrock block palette of one client: the block table's palette and the custom blocks its server adds in StartGame, with the runtime IDs the client uses.
 * A runtime ID is the index in the palette sorted by the hash of the block name, or the network hash when StartGame sets blockNetworkIdsHashed.
 * Custom blocks have no Java state, so Boar reads them as air, and as unknown where it asks.
 */
public final class TablePalette {
    private static final Logger LOG = LoggerFactory.getLogger(TablePalette.class);
    private static final BlockTable TABLE = BlockTable.get();
    // Bounds the memory a server's custom blocks can take; the client itself only warns past 65536 states.
    private static final int MAX_STATES = 1 << 20;
    // Many players on one server get the same block properties.
    private static final int CACHED_PALETTES = 8;
    private static final Map<Key, TablePalette> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, TablePalette> eldest) {
            return this.size() > CACHED_PALETTES;
        }
    };

    // What a runtime ID is, when it is not the index of a vanilla palette entry
    private static final int CUSTOM = -2;
    private static final int UNDEFINED = -1;

    private final boolean hashedIds;
    // Each runtime ID's vanilla palette index, or CUSTOM; null with hashed IDs
    private final int[] sortedEntries;
    // The runtime IDs of custom states, sorted; with hashed IDs only
    private final int[] customHashes;
    private final boolean idsUnknown;
    // By vanilla palette index
    private final SimpleBlockDefinition[] definitions;
    private final BlockMappingInfo mappingInfo;

    private TablePalette(boolean hashedIds, List<CustomBlock> blocks, boolean idsUnknown) {
        this.hashedIds = hashedIds;
        this.idsUnknown = idsUnknown;
        if (hashedIds) {
            this.sortedEntries = null;
            this.customHashes = customHashes(blocks);
            this.definitions = Vanilla.HASHED_DEFINITIONS;
        } else {
            this.sortedEntries = sortedEntries(blocks);
            this.customHashes = new int[0];
            this.definitions = new SimpleBlockDefinition[TABLE.paletteSize()];
            for (int runtimeId = 0; runtimeId < this.sortedEntries.length; runtimeId++) {
                int i = this.sortedEntries[runtimeId];
                if (i >= 0) {
                    this.definitions[i] = new SimpleBlockDefinition(TABLE.paletteName(i), runtimeId, Vanilla.STATES[i]);
                }
            }
        }

        IntFunction<FallingFlowMaterial> flowOf = runtimeId -> {
            if (!Vanilla.FALLING_FLOW_SUPPORTED) {
                return FallingFlowMaterial.UNSUPPORTED_PROTOCOL;
            }
            int entry = this.entry(runtimeId);
            return entry >= 0 ? Vanilla.FLOWS[entry] : entry == CUSTOM ? FallingFlowMaterial.CUSTOM : FallingFlowMaterial.NOT_SEARCHED;
        };
        // An UNKNOWN material also marks the ID as one the palette does not define, for BlockMappingInfo.isKnownRawId.
        IntFunction<HorseJumpMaterial> jumpOf = runtimeId -> {
            int entry = this.entry(runtimeId);
            return entry >= 0 ? Vanilla.JUMPS[entry] : entry == CUSTOM ? HorseJumpMaterial.NONE : HorseJumpMaterial.UNKNOWN;
        };
        this.mappingInfo = new BlockMappingInfo(
                this.definition(0),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:water"))),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:lava"))),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:powder_snow"))),
                IntList.of(this.runtimeId(0), this.runtimeId(TABLE.defaultState(TABLE.block("minecraft:cave_air"))), this.runtimeId(TABLE.defaultState(TABLE.block("minecraft:void_air")))),
                definition -> this.isItemFrame(definition.getRuntimeId()),
                runtimeId -> {
                    int entry = this.entry(runtimeId);
                    return entry >= 0 ? TABLE.paletteToJava(entry) : 0;
                },
                new ToRuntime(this),
                flowOf,
                jumpOf);
    }

    /**
     * The block mapping for a client that was sent this StartGame.
     * Blocks in its block properties that the palette already has, such as data-driven vanilla blocks, are not custom.
     */
    public static BlockMappingInfo mappingInfo(StartGamePacket startGame) {
        Key key = new Key(startGame.isBlockNetworkIdsHashed(), List.copyOf(startGame.getBlockProperties()));
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, TablePalette::build).mappingInfo;
        }
    }

    // When Boar cannot read the custom blocks, their runtime IDs are unknown. With sorted IDs they also move the vanilla IDs, so every ID is unknown.
    private static TablePalette build(Key key) {
        List<CustomBlock> blocks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int states = TABLE.paletteSize();
        try {
            for (BlockPropertyData data : key.blocks()) {
                if (Vanilla.NAMES.contains(data.getName())) {
                    continue;
                }
                if (!names.add(data.getName())) {
                    throw new IllegalArgumentException(data.getName() + " is listed twice");
                }
                CustomBlock block = CustomBlock.of(data);
                states += block.stateCount();
                if (states > MAX_STATES) {
                    throw new IllegalArgumentException("the palette would have more than " + MAX_STATES + " states");
                }
                blocks.add(block);
            }
            return blocks.isEmpty() ? of(key.hashed()) : new TablePalette(key.hashed(), blocks, false);
        } catch (IllegalArgumentException e) {
            LOG.error("StartGame has custom blocks Boar cannot read ({}), so Boar treats {} as unknown", e.getMessage(), key.hashed() ? "their block IDs" : "every block ID");
            return key.hashed() ? of(true) : Vanilla.UNKNOWN;
        }
    }

    // The palette without custom blocks
    static TablePalette of(boolean hashedIds) {
        return hashedIds ? Vanilla.HASHED : Vanilla.SORTED;
    }

    static BlockDefinition definition(BoarPlayer player, int state) {
        return ((ToRuntime) player.mappingInfo.fromIntermediary()).palette().definition(state);
    }

    // A state the table does not have is air, as in Geyser.
    BlockDefinition definition(int state) {
        return this.definitions[TABLE.toRuntime(state >= 0 && state < TABLE.stateCount() ? state : 0, false)];
    }

    int runtimeId(int state) {
        return this.definition(state).getRuntimeId();
    }

    // The vanilla palette index of a runtime ID, or CUSTOM, or UNDEFINED
    private int entry(int runtimeId) {
        if (this.idsUnknown) {
            return UNDEFINED;
        }
        if (this.hashedIds) {
            int index = TABLE.paletteIndex(runtimeId, true);
            return index >= 0 ? index : Arrays.binarySearch(this.customHashes, runtimeId) >= 0 ? CUSTOM : UNDEFINED;
        }
        return runtimeId >= 0 && runtimeId < this.sortedEntries.length ? this.sortedEntries[runtimeId] : UNDEFINED;
    }

    private boolean isItemFrame(int runtimeId) {
        int entry = this.entry(runtimeId);
        return entry >= 0 && (TABLE.paletteName(entry).equals("minecraft:frame") || TABLE.paletteName(entry).equals("minecraft:glow_frame"));
    }

    // The client sorts its palette by FNV-1 64 of the block name, keeping the order of states with the same name. The vanilla palette is sorted already, and on equal hashes a vanilla entry comes first, as in Geyser.
    private static int[] sortedEntries(List<CustomBlock> blocks) {
        List<CustomBlock> byName = new ArrayList<>(blocks);
        byName.sort(Comparator.comparing(block -> nameHash(block.name()), Long::compareUnsigned));
        int[] entries = new int[TABLE.paletteSize() + blocks.stream().mapToInt(CustomBlock::stateCount).sum()];
        int vanilla = 0;
        int next = 0;
        for (CustomBlock block : byName) {
            long hash = nameHash(block.name());
            while (vanilla < TABLE.paletteSize() && Long.compareUnsigned(Vanilla.NAME_HASHES[vanilla], hash) <= 0) {
                entries[next++] = vanilla++;
            }
            for (int s = 0; s < block.stateCount(); s++) {
                entries[next++] = CUSTOM;
            }
        }
        while (vanilla < TABLE.paletteSize()) {
            entries[next++] = vanilla++;
        }
        return entries;
    }

    // A hash shared with another state would make the runtime ID mean two states.
    private static int[] customHashes(List<CustomBlock> blocks) {
        int[] hashes = blocks.stream().flatMapToInt(block -> IntStream.range(0, block.stateCount()).map(s -> CustomBlock.networkHash(block.name(), block.state(s)))).sorted().toArray();
        for (int i = 0; i < hashes.length; i++) {
            if (i > 0 && hashes[i] == hashes[i - 1] || TABLE.paletteIndex(hashes[i], true) >= 0) {
                throw new IllegalArgumentException("two block states have the network hash " + hashes[i]);
            }
        }
        return hashes;
    }

    // FNV-1 64 (multiply, then XOR) of the UTF-8 name, as the client's HashedString
    static long nameHash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash *= 0x100000001b3L;
            hash ^= b & 0xFF;
        }
        return hash;
    }

    private record Key(boolean hashed, List<BlockPropertyData> blocks) {
    }

    private record ToRuntime(TablePalette palette) implements IntUnaryOperator {
        @Override
        public int applyAsInt(int state) {
            return this.palette.runtimeId(state);
        }
    }

    // What every palette shares, by vanilla palette index
    private static final class Vanilla {
        static final Set<String> NAMES = new HashSet<>();
        static final NbtMap[] STATES = new NbtMap[TABLE.paletteSize()];
        static final long[] NAME_HASHES = new long[TABLE.paletteSize()];
        static final FallingFlowMaterial[] FLOWS = new FallingFlowMaterial[TABLE.paletteSize()];
        static final HorseJumpMaterial[] JUMPS = new HorseJumpMaterial[TABLE.paletteSize()];
        // Custom blocks never move hashed IDs, so every hashed palette shares these.
        static final SimpleBlockDefinition[] HASHED_DEFINITIONS = new SimpleBlockDefinition[TABLE.paletteSize()];
        static final boolean FALLING_FLOW_SUPPORTED;

        static {
            FallingFlowMaterials.Lookup fallingFlow = FallingFlowMaterials.forProtocol(TABLE.protocol());
            FALLING_FLOW_SUPPORTED = fallingFlow.isSupported();
            for (int i = 0; i < TABLE.paletteSize(); i++) {
                String name = TABLE.paletteName(i);
                NbtMap states = TABLE.paletteStates(i);
                NAMES.add(name);
                STATES[i] = NbtMap.builder().putString("name", name).putCompound("states", states).build();
                NAME_HASHES[i] = nameHash(name);
                FLOWS[i] = fallingFlow.resolve(name, states);
                JUMPS[i] = HorseJumpMaterials.resolve(name);
                HASHED_DEFINITIONS[i] = new SimpleBlockDefinition(name, TABLE.networkHash(i), STATES[i]);
            }
        }

        static final TablePalette SORTED = new TablePalette(false, List.of(), false);
        static final TablePalette HASHED = new TablePalette(true, List.of(), false);
        static final TablePalette UNKNOWN = new TablePalette(false, List.of(), true);
    }
}
