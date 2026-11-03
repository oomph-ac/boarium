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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * The Bedrock block palette of one client: the vanilla palette and the custom blocks its server adds in StartGame, with the runtime IDs the client uses.
 * A runtime ID is the index in the palette sorted by the hash of the block name, or the network hash when StartGame sets blockNetworkIdsHashed.
 * Boar's intermediary ID of a vanilla state is its palette index. A custom state's is a custom ID past the palette, which every player shares.
 */
public final class BedrockPalette {
    private static final Logger LOG = LoggerFactory.getLogger(BedrockPalette.class);
    private static final BedrockBlockTable TABLE = BedrockBlockTable.get();
    // Bounds the memory a server's custom blocks can take.
    private static final int MAX_STATES = 1 << 20;
    // Many players on one server get the same block properties.
    private static final int CACHED_PALETTES = 8;
    private static final Map<Key, BedrockPalette> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, BedrockPalette> eldest) {
            return this.size() > CACHED_PALETTES;
        }
    };

    // Custom IDs are never freed, so their number is capped.
    static final int FIRST_CUSTOM_ID = TABLE.paletteSize();
    static final int MAX_CUSTOM_IDS = 1 << 22;
    private static final CustomIds CUSTOM_IDS = new CustomIds(FIRST_CUSTOM_ID, MAX_CUSTOM_IDS);

    private static final int AIR = TABLE.firstIndex("minecraft:air");
    // What a runtime ID is, when it is not a state with an intermediary ID: a custom state with no custom ID, or an ID the palette does not define
    private static final int CUSTOM = -2;
    private static final int UNDEFINED = -1;

    private final boolean hashedIds;
    private final boolean idsUnknown;
    // False when Boar could not read the server's custom blocks, or some got no custom ID, so Boar cannot send those blocks back to the client as themselves.
    private final boolean coversEveryBlock;
    // Each runtime ID's intermediary ID, or CUSTOM; null with hashed IDs
    private final int[] sortedEntries;
    // The runtime IDs of custom states, sorted, and the intermediary ID (or CUSTOM) of each; with hashed IDs only
    private final int[] customHashes;
    private final int[] customHashIds;
    // By vanilla palette index
    private final SimpleBlockDefinition[] definitions;
    // The custom blocks with custom IDs, by their first custom ID, and with sorted IDs the runtime ID of their first state
    private final int[] customStarts;
    private final CustomBlock[] customBlocks;
    private final int[] customFirstRuntimeIds;
    private final Map<Integer, BlockDefinition> customDefinitions = new ConcurrentHashMap<>();
    final BlockMappingInfo mappingInfo;

    private BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread) {
        this(hashedIds, blocks, customBlocksUnread, CUSTOM_IDS);
    }

    // Tests give a palette custom IDs of its own, so that it can run out of them.
    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds) {
        this.hashedIds = hashedIds;
        this.idsUnknown = customBlocksUnread && !hashedIds;
        Map<CustomBlock, Integer> firstIds = new HashMap<>();
        for (CustomBlock block : blocks) {
            firstIds.put(block, customIds.of(block));
        }
        this.coversEveryBlock = !customBlocksUnread && firstIds.values().stream().allMatch(id -> id >= 0);
        Map<CustomBlock, Integer> firstRuntimeIds = new HashMap<>();
        if (hashedIds) {
            this.sortedEntries = null;
            long[] byHash = customHashes(blocks, firstIds);
            this.customHashes = new int[byHash.length];
            this.customHashIds = new int[byHash.length];
            for (int i = 0; i < byHash.length; i++) {
                this.customHashes[i] = (int) (byHash[i] >> 32);
                this.customHashIds[i] = (int) byHash[i];
            }
            this.definitions = Vanilla.HASHED_DEFINITIONS;
        } else {
            this.sortedEntries = sortedEntries(blocks, firstIds, firstRuntimeIds);
            this.customHashes = new int[0];
            this.customHashIds = new int[0];
            this.definitions = new SimpleBlockDefinition[TABLE.paletteSize()];
            for (int runtimeId = 0; runtimeId < this.sortedEntries.length; runtimeId++) {
                int i = this.sortedEntries[runtimeId];
                if (isVanilla(i)) {
                    this.definitions[i] = new SimpleBlockDefinition(TABLE.name(i), runtimeId, Vanilla.STATES[i]);
                }
            }
        }

        List<CustomBlock> withIds = blocks.stream().filter(block -> firstIds.get(block) >= 0).sorted(Comparator.comparing(firstIds::get)).toList();
        this.customStarts = withIds.stream().mapToInt(firstIds::get).toArray();
        this.customBlocks = withIds.toArray(CustomBlock[]::new);
        this.customFirstRuntimeIds = withIds.stream().mapToInt(block -> firstRuntimeIds.getOrDefault(block, -1)).toArray();

        // Custom states are air to Boar, so it must not trust what it reads of them; rawIdDefined is false for them as for IDs the palette does not define.
        this.mappingInfo = new BlockMappingInfo(
                this.definition(AIR),
                this.definition(TABLE.defaultIndex("minecraft:water")),
                this.definition(TABLE.defaultIndex("minecraft:lava")),
                this.definition(TABLE.defaultIndex("minecraft:powder_snow")),
                IntList.of(this.definition(AIR).getRuntimeId()),
                definition -> this.isItemFrame(definition.getRuntimeId()),
                runtimeId -> {
                    int entry = this.entry(runtimeId);
                    return entry >= 0 ? entry : AIR;
                },
                new ToRuntime(this),
                runtimeId -> {
                    if (!Vanilla.FALLING_FLOW_SUPPORTED) {
                        return FallingFlowMaterial.UNSUPPORTED_PROTOCOL;
                    }
                    int entry = this.entry(runtimeId);
                    return isVanilla(entry) ? Vanilla.FLOWS[entry] : entry == UNDEFINED ? FallingFlowMaterial.NOT_SEARCHED : FallingFlowMaterial.CUSTOM;
                },
                runtimeId -> {
                    int entry = this.entry(runtimeId);
                    return isVanilla(entry) ? Vanilla.JUMPS[entry] : entry == UNDEFINED ? HorseJumpMaterial.UNKNOWN : HorseJumpMaterial.NONE;
                },
                runtimeId -> isVanilla(this.entry(runtimeId)));
    }

    /**
     * The block mapping for a client that was sent this StartGame.
     * Blocks in its block properties that the palette already has, such as data-driven vanilla blocks, are not custom.
     */
    public static BlockMappingInfo mappingInfo(StartGamePacket startGame) {
        Key key = new Key(startGame.isBlockNetworkIdsHashed(), List.copyOf(startGame.getBlockProperties()));
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, BedrockPalette::build).mappingInfo;
        }
    }

    // When Boar cannot read the custom blocks, their runtime IDs are unknown. With sorted IDs they also move the vanilla IDs, so every ID is unknown.
    private static BedrockPalette build(Key key) {
        List<CustomBlock> blocks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int states = TABLE.paletteSize();
        try {
            for (BlockPropertyData data : key.blocks()) {
                if (TABLE.firstIndex(data.getName()) >= 0) {
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
            return blocks.isEmpty() ? of(key.hashed()) : new BedrockPalette(key.hashed(), blocks, false);
        } catch (IllegalArgumentException e) {
            LOG.error("StartGame has custom blocks Boar cannot read ({}), so Boar treats {} as unknown", e.getMessage(), key.hashed() ? "their block IDs" : "every block ID");
            return key.hashed() ? Vanilla.UNREAD_HASHED : Vanilla.UNKNOWN;
        }
    }

    // The palette without custom blocks
    static BedrockPalette of(boolean hashedIds) {
        return hashedIds ? Vanilla.HASHED : Vanilla.SORTED;
    }

    // Whether a mapping is for a client whose every block runtime ID is unknown
    public static boolean runtimeIdsUnknown(BlockMappingInfo info) {
        return of(info).idsUnknown;
    }

    // Whether Boar can send every block of a mapping's client back to it as itself
    public static boolean coversEveryBlock(BlockMappingInfo info) {
        return of(info).coversEveryBlock;
    }

    static BlockDefinition definition(BoarPlayer player, int id) {
        return of(player.mappingInfo).definition(id);
    }

    // The palette a mapping came from
    static BedrockPalette of(BlockMappingInfo info) {
        return ((ToRuntime) info.fromIntermediary()).palette();
    }

    // The definition of an intermediary ID; an ID this palette does not have is air, as in Geyser.
    BlockDefinition definition(int id) {
        if (isVanilla(id)) {
            return this.definitions[id];
        }
        int block = this.customBlock(id);
        return block < 0 ? this.definitions[AIR] : this.customDefinitions.computeIfAbsent(id, custom -> this.customDefinition(block, custom - this.customStarts[block]));
    }

    // The index in customBlocks of the block with this custom ID, or -1
    private int customBlock(int id) {
        int i = Arrays.binarySearch(this.customStarts, id);
        if (i < 0) {
            i = -i - 2;
        }
        return i >= 0 && id - this.customStarts[i] < this.customBlocks[i].stateCount() ? i : -1;
    }

    private BlockDefinition customDefinition(int block, int state) {
        CustomBlock custom = this.customBlocks[block];
        NbtMap states = custom.state(state);
        int runtimeId = this.hashedIds ? CustomBlock.networkHash(custom.name(), states) : this.customFirstRuntimeIds[block] + state;
        return new SimpleBlockDefinition(custom.name(), runtimeId, NbtMap.builder().putString("name", custom.name()).putCompound("states", states).build());
    }

    // The intermediary ID of a runtime ID, or CUSTOM, or UNDEFINED
    private int entry(int runtimeId) {
        if (this.idsUnknown) {
            return UNDEFINED;
        }
        if (this.hashedIds) {
            int index = TABLE.indexOfHash(runtimeId);
            int custom = Arrays.binarySearch(this.customHashes, runtimeId);
            return index >= 0 ? index : custom >= 0 ? this.customHashIds[custom] : UNDEFINED;
        }
        return runtimeId >= 0 && runtimeId < this.sortedEntries.length ? this.sortedEntries[runtimeId] : UNDEFINED;
    }

    private static boolean isVanilla(int id) {
        return id >= 0 && id < TABLE.paletteSize();
    }

    private boolean isItemFrame(int runtimeId) {
        int entry = this.entry(runtimeId);
        return isVanilla(entry) && (TABLE.name(entry).equals("minecraft:frame") || TABLE.name(entry).equals("minecraft:glow_frame"));
    }

    // The client sorts its palette by FNV-1 64 of the block name, keeping the order of states with the same name. The vanilla palette is sorted already, and on equal hashes a vanilla entry comes first, as in Geyser.
    private static int[] sortedEntries(List<CustomBlock> blocks, Map<CustomBlock, Integer> firstIds, Map<CustomBlock, Integer> firstRuntimeIds) {
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
            firstRuntimeIds.put(block, next);
            int first = firstIds.get(block);
            for (int s = 0; s < block.stateCount(); s++) {
                entries[next++] = first >= 0 ? first + s : CUSTOM;
            }
        }
        while (vanilla < TABLE.paletteSize()) {
            entries[next++] = vanilla++;
        }
        return entries;
    }

    // Each custom state's network hash in the high half and its intermediary ID (or CUSTOM) in the low half, sorted by hash. A hash shared with another state would make the runtime ID mean two states.
    private static long[] customHashes(List<CustomBlock> blocks, Map<CustomBlock, Integer> firstIds) {
        List<Long> hashes = new ArrayList<>();
        for (CustomBlock block : blocks) {
            int first = firstIds.get(block);
            for (int s = 0; s < block.stateCount(); s++) {
                int id = first >= 0 ? first + s : CUSTOM;
                hashes.add((long) CustomBlock.networkHash(block.name(), block.state(s)) << 32 | id & 0xFFFFFFFFL);
            }
        }
        long[] sorted = hashes.stream().mapToLong(Long::longValue).sorted().toArray();
        for (int i = 0; i < sorted.length; i++) {
            int hash = (int) (sorted[i] >> 32);
            if (i > 0 && hash == (int) (sorted[i - 1] >> 32) || TABLE.indexOfHash(hash) >= 0) {
                throw new IllegalArgumentException("two block states have the network hash " + hash);
            }
        }
        return sorted;
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

    /**
     * The custom IDs every palette shares: a custom block gets one for each state, in the client's order, the first time any palette has it.
     * Past the cap, a block gets none, so its states are unknown air.
     */
    static final class CustomIds {
        private final Map<CustomBlock, Integer> firstIds = new HashMap<>();
        private final int first;
        private final int max;
        private int next;
        private boolean full;

        CustomIds(int first, int max) {
            this.first = first;
            this.max = max;
            this.next = first;
        }

        // The first custom ID of a block, or -1 once the IDs have run out
        synchronized int of(CustomBlock block) {
            Integer id = this.firstIds.get(block);
            if (id != null) {
                return id;
            }
            if (this.full || this.next - this.first > this.max - block.stateCount()) {
                if (!this.full) {
                    this.full = true;
                    LOG.warn("Boar has used its {} custom block IDs, so the custom blocks it sees from now on, starting with {}, are unknown air", this.max, block.name());
                }
                return -1;
            }
            this.firstIds.put(block, this.next);
            this.next += block.stateCount();
            return this.firstIds.get(block);
        }
    }

    private record ToRuntime(BedrockPalette palette) implements IntUnaryOperator {
        @Override
        public int applyAsInt(int id) {
            return this.palette.definition(id).getRuntimeId();
        }
    }

    // What every palette shares, by vanilla palette index
    private static final class Vanilla {
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
                String name = TABLE.name(i);
                STATES[i] = NbtMap.builder().putString("name", name).putCompound("states", TABLE.states(i)).build();
                NAME_HASHES[i] = nameHash(name);
                FLOWS[i] = fallingFlow.resolve(name, TABLE.states(i));
                JUMPS[i] = HorseJumpMaterials.resolve(name);
                HASHED_DEFINITIONS[i] = new SimpleBlockDefinition(name, TABLE.networkHash(i), STATES[i]);
            }
        }

        static final BedrockPalette SORTED = new BedrockPalette(false, List.of(), false);
        static final BedrockPalette HASHED = new BedrockPalette(true, List.of(), false);
        static final BedrockPalette UNKNOWN = new BedrockPalette(false, List.of(), true);
        static final BedrockPalette UNREAD_HASHED = new BedrockPalette(true, List.of(), true);
    }
}
