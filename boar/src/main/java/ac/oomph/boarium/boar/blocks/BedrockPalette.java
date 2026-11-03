package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.FallingFlowMaterials;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.boar.mappings.block.HorseJumpMaterials;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    // Bounds the custom states Boar hashes for one palette, and the runtime IDs of states without custom IDs that a player's mapping keeps.
    private static final int MAX_STATES = 1 << 20;
    // Bounds the work of building one palette, which other logins wait for.
    static final Limits LIMITS = new Limits(MAX_STATES, 1L << 27, 4L * MAX_STATES);
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
    // How many custom block names a palette logs for each kind of warning, and how many unknown runtime IDs a player's mapping logs
    static final int MAX_WARNED_NAMES = 256;
    static final int MAX_WARNED_IDS = 16;
    private static final int MAX_LOGGED_NAME = 64;

    private static final int AIR = TABLE.firstIndex("minecraft:air");
    // What a runtime ID is, when it is not a state with an intermediary ID: a state of the custom block at cappedBlocks[CUSTOM - entry], which has no custom IDs, or an ID the palette does not define
    private static final int CUSTOM = -2;
    private static final int UNDEFINED = -1;

    private final boolean hashedIds;
    private final boolean idsUnknown;
    // With sorted IDs, the palette in runs of runtime IDs, as Runs has them; null with hashed IDs
    private final Runs sortedRuns;
    // With hashed IDs, the runtime IDs that are not a vanilla state's own network hash, sorted, and the entry of each
    private final int[] nonVanillaHashes;
    private final int[] nonVanillaHashIds;
    // With hashed IDs, the runtime ID of each custom ID whose state the client gave another ID than its network hash
    private final Int2ObjectOpenHashMap<Integer> movedCustomIds;
    // By vanilla palette index
    private final SimpleBlockDefinition[] definitions;
    // The custom blocks with custom IDs, by their first custom ID, and with sorted IDs the runtime ID of their first state
    private final int[] customStarts;
    private final CustomBlock[] customBlocks;
    private final int[] customFirstRuntimeIds;
    private final Map<Integer, BlockDefinition> customDefinitions = new ConcurrentHashMap<>();
    // Boar's block of each custom block in customBlocks, and the index of each by name
    private final CustomBlockType[] customTypes;
    private final Map<String, Integer> customIndices = new HashMap<>();
    // The custom blocks with no custom IDs
    private final CustomBlock[] cappedBlocks;
    // Whether the custom block of each index in customBlocks, then in cappedBlocks, was logged, and how many were. Read without the lock, and set with it, so that each is logged once.
    private final boolean[] warnedBlocks;
    // Whether each custom block in customBlocks that Boar does not place was logged; these count against the same limit as warnedBlocks
    private final boolean[] warnedPlacements;
    private int warnedNames;

    private BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread) {
        this(hashedIds, blocks, customBlocksUnread, CUSTOM_IDS, LIMITS);
    }

    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds) {
        this(hashedIds, blocks, customBlocksUnread, customIds, LIMITS);
    }

    // Tests give a palette custom IDs and limits of its own, so that it can run out of them. Throws IllegalArgumentException past the limits on hashing.
    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds, Limits limits) {
        this.hashedIds = hashedIds;
        this.idsUnknown = customBlocksUnread && !hashedIds;
        // A block with no custom IDs gets CUSTOM minus its index in cappedBlocks. One palette takes no more than its share of the custom IDs, so that it leaves some for other servers.
        Map<CustomBlock, Integer> firstIds = new HashMap<>();
        List<CustomBlock> capped = new ArrayList<>();
        long taken = 0;
        for (CustomBlock block : blocks) {
            int first = taken + block.stateCount() <= limits.customIds() ? customIds.of(block) : -1;
            taken += first < 0 ? 0 : block.stateCount();
            if (first < 0) {
                first = CUSTOM - capped.size();
                capped.add(block);
            }
            firstIds.put(block, first);
        }
        this.cappedBlocks = capped.toArray(CustomBlock[]::new);
        Map<CustomBlock, Integer> firstRuntimeIds = new HashMap<>();
        Runs runs = Runs.of(blocks, firstIds, firstRuntimeIds);
        if (hashedIds) {
            this.sortedRuns = null;
            this.movedCustomIds = new Int2ObjectOpenHashMap<>();
            Int2ObjectOpenHashMap<Owner> owners = hashedIds(runs, this.movedCustomIds, limits);
            this.nonVanillaHashes = owners.int2ObjectEntrySet().stream()
                    .filter(owner -> !isVanilla(owner.getValue().entry()) || TABLE.networkHash(owner.getValue().entry()) != owner.getIntKey())
                    .mapToInt(Int2ObjectMap.Entry::getIntKey).sorted().toArray();
            this.nonVanillaHashIds = Arrays.stream(this.nonVanillaHashes).map(id -> owners.get(id).entry()).toArray();
            SimpleBlockDefinition[] definitions = Vanilla.HASHED_DEFINITIONS;
            for (int i = 0; i < this.nonVanillaHashes.length; i++) {
                int entry = this.nonVanillaHashIds[i];
                if (isVanilla(entry)) {
                    definitions = definitions == Vanilla.HASHED_DEFINITIONS ? definitions.clone() : definitions;
                    definitions[entry] = new SimpleBlockDefinition(TABLE.name(entry), this.nonVanillaHashes[i], Vanilla.STATES[entry]);
                }
            }
            this.definitions = definitions;
        } else {
            this.sortedRuns = runs;
            this.nonVanillaHashes = new int[0];
            this.nonVanillaHashIds = new int[0];
            this.movedCustomIds = new Int2ObjectOpenHashMap<>();
            this.definitions = new SimpleBlockDefinition[TABLE.paletteSize()];
            for (int run = 0; run < runs.starts().length; run++) {
                int first = runs.entries()[run];
                if (isVanilla(first)) {
                    for (int i = first; i < first + runs.length(run); i++) {
                        this.definitions[i] = new SimpleBlockDefinition(TABLE.name(i), runs.starts()[run] + i - first, Vanilla.STATES[i]);
                    }
                }
            }
        }

        List<CustomBlock> withIds = blocks.stream().filter(block -> firstIds.get(block) >= 0).sorted(Comparator.comparing(firstIds::get)).toList();
        this.customStarts = withIds.stream().mapToInt(firstIds::get).toArray();
        this.customBlocks = withIds.toArray(CustomBlock[]::new);
        this.customFirstRuntimeIds = withIds.stream().mapToInt(block -> firstRuntimeIds.getOrDefault(block, -1)).toArray();
        this.customTypes = withIds.stream().map(block -> customIds.type(firstIds.get(block))).toArray(CustomBlockType[]::new);
        for (int i = 0; i < this.customBlocks.length; i++) {
            this.customIndices.put(this.customBlocks[i].name(), i);
        }
        this.warnedBlocks = new boolean[this.customBlocks.length + this.cappedBlocks.length];
        this.warnedPlacements = new boolean[this.customBlocks.length];
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
        // As BlockDefinitionGroup::digestServerBlockProperties: it skips a block without a vanilla_block_data compound before it reads the block's properties, so the block has no states, and an earlier block of its name stays. Of the other blocks, it keeps the last of a name.
        Map<String, BlockPropertyData> byName = new LinkedHashMap<>();
        Set<String> skipped = new LinkedHashSet<>();
        for (BlockPropertyData data : key.blocks()) {
            if (TABLE.firstIndex(data.getName()) >= 0) {
                continue;
            }
            if (data.getProperties().get("vanilla_block_data") instanceof NbtMap) {
                byName.put(data.getName(), data);
            } else {
                skipped.add(data.getName());
            }
        }
        int warned = 0;
        for (String name : skipped) {
            if (++warned > MAX_WARNED_NAMES) {
                break;
            }
            LOG.warn("A copy of custom block {} has no vanilla_block_data compound, so the client skips that copy, and so does Boar{}", name, warned == MAX_WARNED_NAMES ? ", and logs no more skipped blocks of this palette" : "");
        }
        List<CustomBlock> blocks = new ArrayList<>();
        CustomCollision.Budget collisionBudget = new CustomCollision.Budget();
        CustomCollision.Budget componentsBudget = new CustomCollision.Budget();
        long states = 0;
        try {
            for (BlockPropertyData data : byName.values()) {
                CustomBlock block = CustomBlock.of(data, collisionBudget, componentsBudget);
                states += block.stateCount();
                if (key.hashed() && states > MAX_STATES) {
                    throw new IllegalArgumentException("Boar hashes no more than " + MAX_STATES + " custom states");
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

    // The random offset that moves the collision boxes of a custom ID, or null
    static CustomComponents.RandomOffset customOffset(int id) {
        return CUSTOM_IDS.offset(id);
    }

    // The friction of a custom ID, or that of a block Boar does not know when no custom block has it
    static float customFriction(int id) {
        return CUSTOM_IDS.friction(id);
    }

    static boolean customReplaceable(int id) {
        return CUSTOM_IDS.replaceable(id);
    }

    static Block customType(int id) {
        return CUSTOM_IDS.type(id);
    }

    /**
     * Boar's block of the custom block of this name in a mapping's palette, when Boar places it as the client does, or null.
     * A custom block without custom IDs has no block of its own. Logs once why Boar does not place a custom block.
     */
    public static Block placedBlock(BlockMappingInfo info, String name) {
        BedrockPalette palette = of(info);
        Integer index = palette.customIndices.get(name);
        if (index == null) {
            return null;
        }
        CustomBlockType type = palette.customTypes[index];
        if (type.unplaced() == null) {
            return type;
        }
        palette.warnPlacement(index, type.unplaced());
        return null;
    }

    // Whether a mapping's palette has a block of this name
    public static boolean hasBlock(BlockMappingInfo info, String name) {
        BedrockPalette palette = of(info);
        return TABLE.firstIndex(name) >= 0 || palette.customIndices.containsKey(name) || Arrays.stream(palette.cappedBlocks).anyMatch(block -> block.name().equals(name));
    }

    private void warnPlacement(int index, String reason) {
        if (this.warnedPlacements[index] || this.warnedNames >= MAX_WARNED_NAMES) {
            return;
        }
        synchronized (this.warnedBlocks) {
            if (this.warnedPlacements[index] || this.warnedNames >= MAX_WARNED_NAMES) {
                return;
            }
            this.warnedPlacements[index] = true;
            this.warnedNames++;
            String name = this.customBlocks[index].name();
            LOG.warn("Boar does not place custom block {} before the server does, since {}{}", name.length() > MAX_LOGGED_NAME ? name.substring(0, MAX_LOGGED_NAME) + "..." : name, reason, this.warnedNames == MAX_WARNED_NAMES ? ", and logs no more custom blocks of this palette" : "");
        }
    }

    // Logs the custom block at this index in customBlocks, then in cappedBlocks, the first time it is read, when Boar takes some of its states for full cubes or may not predict their friction, replaceability or destroy time.
    private void warnBlock(int index) {
        if (this.warnedBlocks[index] || this.warnedNames >= MAX_WARNED_NAMES) {
            return;
        }
        String shape = index < this.customBlocks.length ? this.customBlocks[index].collision().guess() : "Boar has no custom IDs left for it";
        String components = index < this.customBlocks.length ? this.customBlocks[index].components().guess() : null;
        if (shape == null && components == null) {
            return;
        }
        synchronized (this.warnedBlocks) {
            if (this.warnedBlocks[index] || this.warnedNames >= MAX_WARNED_NAMES) {
                return;
            }
            this.warnedBlocks[index] = true;
            this.warnedNames++;
            CustomBlock block = index < this.customBlocks.length ? this.customBlocks[index] : this.cappedBlocks[index - this.customBlocks.length];
            String last = this.warnedNames == MAX_WARNED_NAMES ? ", and logs no more custom blocks of this palette" : "";
            String unknownComponents = "the friction, replaceability or destroy time of some states of custom block " + block.name() + " (" + components + ")";
            if (shape == null) {
                LOG.warn("Boar may not predict {}{}", unknownComponents, last);
            } else {
                LOG.warn("Boar does not know the shape of custom block {} ({}), so it treats the states it cannot read as full 1x1x1 blocks{}{}", block.name(), shape, components == null ? "" : ". It also may not predict " + unknownComponents, last);
            }
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
        int runtimeId = this.hashedIds ? this.movedCustomIds.getOrDefault(this.customStarts[block] + state, (Integer) CustomBlock.networkHash(custom.name(), states)) : this.customFirstRuntimeIds[block] + state;
        return new SimpleBlockDefinition(custom.name(), runtimeId, NbtMap.builder().putString("name", custom.name()).putCompound("states", states).build());
    }

    // The intermediary ID of a runtime ID, or CUSTOM minus an index in cappedBlocks, or UNDEFINED
    private int entry(int runtimeId) {
        if (this.idsUnknown) {
            return UNDEFINED;
        }
        if (this.hashedIds) {
            int moved = Arrays.binarySearch(this.nonVanillaHashes, runtimeId);
            int index = TABLE.indexOfHash(runtimeId);
            return moved >= 0 ? this.nonVanillaHashIds[moved] : index >= 0 ? index : UNDEFINED;
        }
        return this.sortedRuns.entry(runtimeId);
    }

    private static boolean isVanilla(int id) {
        return id >= 0 && id < TABLE.paletteSize();
    }

    private boolean isItemFrame(int runtimeId) {
        int entry = this.entry(runtimeId);
        return isVanilla(entry) && (TABLE.name(entry).equals("minecraft:frame") || TABLE.name(entry).equals("minecraft:glow_frame"));
    }

    /**
     * The state the client gives each runtime ID of a palette with hashed IDs.
     * The client gives each state its network hash, in the order of the sorted palette, or the next ID that is free or held by a state of the same block.
     * A shared ID means the later state, and minecraft:unknown always gets 0xFFFFFFFE (BlockHashPalette::assignBlockNetworkId, BlockHashPalette::appendBlock).
     *
     * @param movedCustomIds gets the runtime ID of each custom ID whose state does not have its network hash
     */
    private static Int2ObjectOpenHashMap<Owner> hashedIds(Runs runs, Int2ObjectOpenHashMap<Integer> movedCustomIds, Limits limits) {
        Int2ObjectOpenHashMap<Owner> owners = new Int2ObjectOpenHashMap<>();
        CustomCollision.Budget bytes = new CustomCollision.Budget(limits.hashedBytes());
        CustomCollision.Budget steps = new CustomCollision.Budget(limits.clashSteps());
        for (int run = 0; run < runs.starts().length; run++) {
            int first = runs.entries()[run];
            CustomBlock block = runs.blocks()[run];
            for (int s = 0; s < runs.length(run); s++) {
                int entry = block == null ? first + s : first <= CUSTOM ? first : first + s;
                String name = block == null ? TABLE.name(entry) : block.name();
                int hash;
                if (block == null) {
                    hash = TABLE.networkHash(entry);
                } else {
                    byte[] state = CustomBlock.networkState(name, block.state(s));
                    if (!bytes.take(state.length)) {
                        throw new IllegalArgumentException("Boar hashes no more than " + limits.hashedBytes() + " bytes of custom states");
                    }
                    hash = CustomBlock.fnv1a(state);
                }
                int id = hash;
                while (!name.equals("minecraft:unknown") && owners.containsKey(id)) {
                    // Past 0xFFFFFFFC, the client looks at the state of runtime ID 0 instead, which may not exist.
                    if (Integer.compareUnsigned(id, 0xFFFFFFFC) > 0) {
                        throw new IllegalArgumentException("the runtime IDs of two block states clash past 0xFFFFFFFC");
                    }
                    if (owners.get(id).name().equals(name)) {
                        break;
                    }
                    if (!steps.take(1)) {
                        throw new IllegalArgumentException("the runtime IDs of block states clash more than " + limits.clashSteps() + " times");
                    }
                    id++;
                }
                owners.put(id, new Owner(name, entry));
                if (block != null && id != hash && first > CUSTOM) {
                    movedCustomIds.put(entry, Integer.valueOf(id));
                }
            }
        }
        return owners;
    }

    private record Owner(String name, int entry) {
    }

    /**
     * @param customIds the most custom IDs one palette takes
     * @param hashedBytes the most bytes of custom states Boar hashes for one palette with hashed IDs
     * @param clashSteps the most times its runtime IDs move past another block's, as the client moves them
     */
    record Limits(int customIds, long hashedBytes, long clashSteps) {
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
     * A palette in the client's order, in runs of runtime IDs: vanilla states, or the states of one custom block. The client sorts its palette by FNV-1 64 of the block name, keeping the order of states with the same name.
     * The vanilla palette is sorted already, and on equal hashes a vanilla entry comes first, as in Geyser.
     *
     * @param starts the runtime ID each run starts at
     * @param entries the entry of each run's first runtime ID: a vanilla palette index, a first custom ID, or CUSTOM minus an index in cappedBlocks, which every state of that block has
     * @param blocks the custom block of each run, or null
     * @param size the number of runtime IDs
     */
    private record Runs(int[] starts, int[] entries, CustomBlock[] blocks, int size) {
        static Runs of(List<CustomBlock> blocks, Map<CustomBlock, Integer> firstIds, Map<CustomBlock, Integer> firstRuntimeIds) {
            List<CustomBlock> byName = new ArrayList<>(blocks);
            byName.sort(Comparator.comparing(block -> nameHash(block.name()), Long::compareUnsigned));
            IntList starts = new IntArrayList();
            IntList entries = new IntArrayList();
            List<CustomBlock> runBlocks = new ArrayList<>();
            long next = 0;
            int vanilla = 0;
            for (int b = 0; b <= byName.size(); b++) {
                CustomBlock block = b < byName.size() ? byName.get(b) : null;
                int end = vanilla;
                while (end < TABLE.paletteSize() && (block == null || Long.compareUnsigned(Vanilla.NAME_HASHES[end], nameHash(block.name())) <= 0)) {
                    end++;
                }
                if (end > vanilla) {
                    starts.add((int) next);
                    entries.add(vanilla);
                    runBlocks.add(null);
                    next += end - vanilla;
                    vanilla = end;
                }
                if (block != null) {
                    firstRuntimeIds.put(block, (int) next);
                    starts.add((int) next);
                    entries.add((int) firstIds.get(block));
                    runBlocks.add(block);
                    next += block.stateCount();
                    if (next > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("the palette would have more than " + Integer.MAX_VALUE + " states");
                    }
                }
            }
            return new Runs(starts.toIntArray(), entries.toIntArray(), runBlocks.toArray(CustomBlock[]::new), (int) next);
        }

        int length(int run) {
            return (run + 1 < this.starts.length ? this.starts[run + 1] : this.size) - this.starts[run];
        }

        // The entry of a runtime ID, or UNDEFINED
        int entry(int runtimeId) {
            if (runtimeId < 0 || runtimeId >= this.size) {
                return UNDEFINED;
            }
            int run = Arrays.binarySearch(this.starts, runtimeId);
            run = run < 0 ? -run - 2 : run;
            int first = this.entries[run];
            return first <= CUSTOM ? first : first + runtimeId - this.starts[run];
        }
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
        private volatile Assigned assigned = new Assigned(new int[0], new CustomBlock[0], new CustomBlockType[0], 0, 0);

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
            CustomBlockType[] types = assigned.types();
            if (assigned.count() == starts.length) {
                starts = Arrays.copyOf(starts, Math.max(16, starts.length * 2));
                blocks = Arrays.copyOf(blocks, starts.length);
                types = Arrays.copyOf(types, starts.length);
            }
            starts[assigned.count()] = this.next;
            blocks[assigned.count()] = block;
            types[assigned.count()] = new CustomBlockType(block, this.next);
            this.next += block.stateCount();
            this.assigned = new Assigned(starts, blocks, types, assigned.count() + 1, this.next);
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

        CustomComponents.RandomOffset offset(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i < 0 ? null : assigned.blocks()[i].collision().offset(id - assigned.starts()[i]);
        }

        float friction(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i < 0 ? CustomComponents.DEFAULT_FRICTION : assigned.blocks()[i].components().friction(id - assigned.starts()[i]);
        }

        boolean replaceable(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i >= 0 && assigned.blocks()[i].components().replaceable(id - assigned.starts()[i]);
        }

        // Boar's block of the custom block with this custom ID, or null
        CustomBlockType type(int id) {
            Assigned assigned = this.assigned;
            int i = assigned.blockOf(id);
            return i < 0 ? null : assigned.types()[i];
        }

        // Each block's IDs end where the next block's start, and the last block's at end.
        private record Assigned(int[] starts, CustomBlock[] blocks, CustomBlockType[] types, int count, int end) {
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
        // Every hashed palette shares these, unless a custom state takes a vanilla state's network hash.
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
