package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.FallingFlowMaterials;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.boar.mappings.block.HorseJumpMaterials;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
 * A custom state has the collision boxes of its block's minecraft:collision_box components, which {@link #customBoxes(int)} gives by custom ID.
 * Boar treats a custom state it cannot read the boxes of, or a runtime ID the palette does not define, as a full 1x1x1 block.
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
    // A full cube that Boar has no runtime ID for, so it cannot send it back to the client
    public static final int UNKNOWN_ID = FIRST_CUSTOM_ID + MAX_CUSTOM_IDS;
    private static final BlockDefinition UNKNOWN_DEFINITION = () -> -1;
    // Each intermediary ID from FIRST_KEPT_ID keeps one runtime ID that is not a state with an ID of its own, so that Boar can send it back: with sorted IDs by the runtime ID itself, with hashed IDs in the order the player's mapping first read them.
    private static final int FIRST_KEPT_ID = UNKNOWN_ID + 1;
    // Bounds the memory a server's unknown hashed IDs can take for each player.
    static final int MAX_KEPT_HASHES = 1 << 12;
    static final int END_ID = FIRST_KEPT_ID + MAX_STATES;
    // How many custom block names a palette logs, and how many unknown runtime IDs a player's mapping logs
    static final int MAX_WARNED_NAMES = 256;
    static final int MAX_WARNED_IDS = 16;

    private static final int AIR = TABLE.firstIndex("minecraft:air");
    // What a runtime ID is, when it is not a state with an intermediary ID: a state of the custom block at cappedBlocks[CUSTOM - entry], which has no custom IDs, or an ID the palette does not define
    private static final int CUSTOM = -2;
    private static final int UNDEFINED = -1;

    private final boolean hashedIds;
    private final boolean idsUnknown;
    // Each runtime ID's entry; null with hashed IDs
    private final int[] sortedEntries;
    // The runtime IDs of custom states, sorted, and the entry of each; with hashed IDs only
    private final int[] customHashes;
    private final int[] customHashIds;
    // By vanilla palette index
    private final SimpleBlockDefinition[] definitions;
    // The custom blocks with custom IDs, by their first custom ID, and with sorted IDs the runtime ID of their first state
    private final int[] customStarts;
    private final CustomBlock[] customBlocks;
    private final int[] customFirstRuntimeIds;
    private final Map<Integer, BlockDefinition> customDefinitions = new ConcurrentHashMap<>();
    // The custom blocks with no custom IDs
    private final CustomBlock[] cappedBlocks;
    // Whether the custom block of each index in customBlocks, then in cappedBlocks, was logged, and how many were. Read without the lock, and set with it, so that each is logged once.
    private final boolean[] warnedBlocks;
    private int warnedNames;

    private BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread) {
        this(hashedIds, blocks, customBlocksUnread, CUSTOM_IDS);
    }

    // Tests give a palette custom IDs of its own, so that it can run out of them.
    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds) {
        this.hashedIds = hashedIds;
        this.idsUnknown = customBlocksUnread && !hashedIds;
        // A block with no custom IDs gets CUSTOM minus its index in cappedBlocks.
        Map<CustomBlock, Integer> firstIds = new HashMap<>();
        List<CustomBlock> capped = new ArrayList<>();
        for (CustomBlock block : blocks) {
            int first = customIds.of(block);
            if (first < 0) {
                first = CUSTOM - capped.size();
                capped.add(block);
            }
            firstIds.put(block, first);
        }
        this.cappedBlocks = capped.toArray(CustomBlock[]::new);
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
        this.warnedBlocks = new boolean[this.customBlocks.length + this.cappedBlocks.length];
    }

    /**
     * A new block mapping for one client that was sent this StartGame, with the palette that clients with the same blocks share.
     * Blocks in its block properties that the palette already has, such as data-driven vanilla blocks, are not custom.
     */
    public static BlockMappingInfo mappingInfo(StartGamePacket startGame) {
        Key key = new Key(startGame.isBlockNetworkIdsHashed(), List.copyOf(startGame.getBlockProperties()));
        BedrockPalette palette;
        synchronized (CACHE) {
            palette = CACHE.computeIfAbsent(key, BedrockPalette::build);
        }
        return palette.mappingInfo();
    }

    // A new block mapping of this palette. Boar only guesses the shape of a block that is not a vanilla state, so rawIdDefined is false for it.
    BlockMappingInfo mappingInfo() {
        PlayerIds ids = new PlayerIds(this);
        return new BlockMappingInfo(
                this.definition(AIR),
                this.definition(TABLE.defaultIndex("minecraft:water")),
                this.definition(TABLE.defaultIndex("minecraft:lava")),
                this.definition(TABLE.defaultIndex("minecraft:powder_snow")),
                IntList.of(this.definition(AIR).getRuntimeId()),
                definition -> this.isItemFrame(definition.getRuntimeId()),
                ids::intermediaryId,
                ids,
                // Boar falls back to the block state for FallingFlowMaterial.CUSTOM, which is a full cube.
                runtimeId -> {
                    if (!Vanilla.FALLING_FLOW_SUPPORTED) {
                        return FallingFlowMaterial.UNSUPPORTED_PROTOCOL;
                    }
                    int entry = this.entry(runtimeId);
                    return isVanilla(entry) ? Vanilla.FLOWS[entry] : FallingFlowMaterial.CUSTOM;
                },
                runtimeId -> {
                    int entry = this.entry(runtimeId);
                    return isVanilla(entry) ? Vanilla.JUMPS[entry] : HorseJumpMaterial.NONE;
                },
                runtimeId -> isVanilla(this.entry(runtimeId)));
    }

    // When Boar cannot read the custom blocks, their runtime IDs are unknown. With sorted IDs they also move the vanilla IDs, so every ID is unknown.
    private static BedrockPalette build(Key key) {
        List<CustomBlock> blocks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        CustomCollision.Budget budget = new CustomCollision.Budget();
        int states = TABLE.paletteSize();
        try {
            for (BlockPropertyData data : key.blocks()) {
                if (TABLE.firstIndex(data.getName()) >= 0) {
                    continue;
                }
                if (!names.add(data.getName())) {
                    throw new IllegalArgumentException(data.getName() + " is listed twice");
                }
                CustomBlock block = CustomBlock.of(data, budget);
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

    // Whether this is the definition of UNKNOWN_ID, which Boar must not resend
    public static boolean isUnknown(BlockDefinition definition) {
        return definition == UNKNOWN_DEFINITION;
    }

    static BlockDefinition definition(BoarPlayer player, int id) {
        return definition(player.mappingInfo, id);
    }

    // The definition of an intermediary ID in a mapping, or UNKNOWN_DEFINITION when the mapping has no runtime ID for it
    static BlockDefinition definition(BlockMappingInfo info, int id) {
        return ((PlayerIds) info.fromIntermediary()).definition(id);
    }

    // The palette a mapping came from
    static BedrockPalette of(BlockMappingInfo info) {
        return ((PlayerIds) info.fromIntermediary()).palette;
    }

    // The definition of an intermediary ID, or UNKNOWN_DEFINITION when this palette has no runtime ID for it. A mapping holds the runtime IDs of the IDs from FIRST_KEPT_ID.
    BlockDefinition definition(int id) {
        if (isVanilla(id)) {
            return this.definitions[id];
        }
        int block = id < UNKNOWN_ID ? this.customBlock(id) : -1;
        return block < 0 ? UNKNOWN_DEFINITION : this.customDefinitions.computeIfAbsent(id, custom -> this.customDefinition(block, custom - this.customStarts[block]));
    }

    // The collision boxes of a custom ID, or a full cube when no custom block has it
    static List<Box> customBoxes(int id) {
        return CUSTOM_IDS.boxes(id);
    }

    static boolean customFaceSturdy(int id) {
        return CUSTOM_IDS.faceSturdy(id);
    }

    // Logs the custom block at this index in customBlocks, then in cappedBlocks, the first time it is read, when Boar takes some of its states for full cubes.
    private void warnBlock(int index) {
        if (this.warnedBlocks[index] || this.warnedNames >= MAX_WARNED_NAMES) {
            return;
        }
        String guess = index < this.customBlocks.length ? this.customBlocks[index].collision().guess() : "Boar has no custom IDs left for it";
        if (guess == null) {
            return;
        }
        synchronized (this.warnedBlocks) {
            if (this.warnedBlocks[index] || this.warnedNames >= MAX_WARNED_NAMES) {
                return;
            }
            this.warnedBlocks[index] = true;
            this.warnedNames++;
            CustomBlock block = index < this.customBlocks.length ? this.customBlocks[index] : this.cappedBlocks[index - this.customBlocks.length];
            LOG.warn("Boar does not know the shape of custom block {} ({}), so it treats the states it cannot read as full 1x1x1 blocks{}", block.name(), guess, this.warnedNames == MAX_WARNED_NAMES ? ", and logs no more custom blocks of this palette" : "");
        }
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

    // The intermediary ID of a runtime ID, or CUSTOM minus an index in cappedBlocks, or UNDEFINED
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
                entries[next++] = first >= 0 ? first + s : first;
            }
        }
        while (vanilla < TABLE.paletteSize()) {
            entries[next++] = vanilla++;
        }
        return entries;
    }

    // Each custom state's network hash in the high half and its entry in the low half, sorted by hash. A hash shared with another state would make the runtime ID mean two states.
    private static long[] customHashes(List<CustomBlock> blocks, Map<CustomBlock, Integer> firstIds) {
        List<Long> hashes = new ArrayList<>();
        for (CustomBlock block : blocks) {
            int first = firstIds.get(block);
            for (int s = 0; s < block.stateCount(); s++) {
                int id = first >= 0 ? first + s : first;
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
     * Past the cap, a block gets none, so a player's mapping keeps its states by runtime ID instead.
     */
    static final class CustomIds {
        private final Map<CustomBlock, Integer> firstIds = new HashMap<>();
        private final int first;
        private final int max;
        private int next;
        private boolean full;
        // The blocks by first custom ID, which BedrockState reads without the lock
        private volatile Assigned assigned = new Assigned(new int[0], new CustomBlock[0], 0, 0);

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
                    LOG.warn("Boar has used its {} custom block IDs, so it treats the custom blocks it sees from now on, starting with {}, as blocks it does not know", this.max, block.name());
                }
                return -1;
            }
            this.firstIds.put(block, this.next);
            Assigned assigned = this.assigned;
            int[] starts = assigned.starts();
            CustomBlock[] blocks = assigned.blocks();
            if (assigned.count() == starts.length) {
                starts = Arrays.copyOf(starts, Math.max(16, starts.length * 2));
                blocks = Arrays.copyOf(blocks, starts.length);
            }
            starts[assigned.count()] = this.next;
            blocks[assigned.count()] = block;
            this.next += block.stateCount();
            this.assigned = new Assigned(starts, blocks, assigned.count() + 1, this.next);
            return this.firstIds.get(block);
        }

        List<Box> boxes(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i < 0 ? CustomCollision.FULL_CUBE : assigned.blocks()[i].collision().boxes(id - assigned.starts()[i]);
        }

        boolean faceSturdy(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i < 0 || assigned.blocks()[i].collision().faceSturdy(id - assigned.starts()[i]);
        }

        // Each block's IDs end where the next block's start, and the last block's at end.
        private record Assigned(int[] starts, CustomBlock[] blocks, int count, int end) {
            // The index of the block with this custom ID, or -1
            int blockOf(int id) {
                int i = Arrays.binarySearch(this.starts, 0, this.count, id);
                if (i < 0) {
                    i = -i - 2;
                }
                return i >= 0 && id < this.end ? i : -1;
            }
        }
    }

    /**
     * One player's mapping of runtime IDs that are not a state with an intermediary ID of their own: it keeps each under an ID from FIRST_KEPT_ID, so that Boar can send it back, and logs it.
     * A server's unknown IDs then reach only its own players. Only the player's event loop uses it, as with Boar's world, so it takes no lock.
     */
    private static final class PlayerIds implements IntUnaryOperator {
        private final BedrockPalette palette;
        // With hashed IDs, the kept runtime IDs by their index past FIRST_KEPT_ID, and that index by runtime ID
        private final IntList keptHashes = new IntArrayList();
        private final Int2ObjectOpenHashMap<Integer> keptHashIndices = new Int2ObjectOpenHashMap<>();
        // The unknown runtime IDs logged so far, which stop at MAX_WARNED_IDS
        private final int[] warnedIds = new int[MAX_WARNED_IDS];
        private int warnedCount;

        PlayerIds(BedrockPalette palette) {
            this.palette = palette;
        }

        int intermediaryId(int runtimeId) {
            int entry = this.palette.entry(runtimeId);
            if (isVanilla(entry)) {
                return entry;
            }
            if (entry >= 0) {
                this.palette.warnBlock(this.palette.customBlock(entry));
                return entry;
            }
            if (entry != UNDEFINED) {
                this.palette.warnBlock(this.palette.customBlocks.length + CUSTOM - entry);
            }
            return this.keep(runtimeId, entry == UNDEFINED);
        }

        // The intermediary ID that keeps a runtime ID, or UNKNOWN_ID when it cannot keep it
        private int keep(int runtimeId, boolean warn) {
            if (!this.palette.hashedIds) {
                if (warn) {
                    this.warnId(runtimeId);
                }
                return runtimeId >= 0 && runtimeId < MAX_STATES ? FIRST_KEPT_ID + runtimeId : UNKNOWN_ID;
            }
            Integer kept = this.keptHashIndices.get(runtimeId);
            if (kept != null) {
                return FIRST_KEPT_ID + kept;
            }
            if (warn) {
                this.warnId(runtimeId);
            }
            if (this.keptHashes.size() == MAX_KEPT_HASHES) {
                return UNKNOWN_ID;
            }
            this.keptHashIndices.put(runtimeId, Integer.valueOf(this.keptHashes.size()));
            this.keptHashes.add(runtimeId);
            if (this.keptHashes.size() == MAX_KEPT_HASHES) {
                LOG.warn("Boar has kept {} unknown block runtime IDs for one player, so it will not resend the blocks of any more", MAX_KEPT_HASHES);
            }
            return FIRST_KEPT_ID + this.keptHashes.size() - 1;
        }

        private void warnId(int runtimeId) {
            if (this.warnedCount == MAX_WARNED_IDS) {
                return;
            }
            for (int i = 0; i < this.warnedCount; i++) {
                if (this.warnedIds[i] == runtimeId) {
                    return;
                }
            }
            this.warnedIds[this.warnedCount++] = runtimeId;
            LOG.warn("Boar does not know block runtime ID {}, so it treats it as a full 1x1x1 block{}", runtimeId, this.warnedCount == MAX_WARNED_IDS ? ", and logs no more unknown IDs for this player" : "");
        }

        BlockDefinition definition(int id) {
            if (id < FIRST_KEPT_ID) {
                return this.palette.definition(id);
            }
            int index = id - FIRST_KEPT_ID;
            if (!this.palette.hashedIds) {
                return () -> index;
            }
            if (index >= this.keptHashes.size()) {
                return UNKNOWN_DEFINITION;
            }
            int runtimeId = this.keptHashes.getInt(index);
            return () -> runtimeId;
        }

        @Override
        public int applyAsInt(int id) {
            return this.definition(id).getRuntimeId();
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
