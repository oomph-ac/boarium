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
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The Bedrock block palette of one client: the vanilla palette of its block table and the custom blocks its server adds in StartGame, with the runtime IDs the client uses.
 * A runtime ID is the index in the palette sorted by the hash of the block name, or the network hash when StartGame sets blockNetworkIdsHashed.
 * Boar's intermediary ID of a vanilla state is its shared ID in {@link BedrockBlockUnion}, which the palette converts to and from its own table's palette index. A custom state's is a custom ID past the shared table, which every player shares.
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
    // Many players on one server get the same block properties. The cache counts as a holder of each palette in it.
    private static final int CACHED_PALETTES = 8;

    // A server can send new custom blocks with each StartGame, so the custom IDs of blocks that no palette has any more are freed, and their number is capped.
    static final int FIRST_CUSTOM_ID = BedrockBlockUnion.get().table().paletteSize();
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
    private static final int MAX_EXAMPLE_NAMES = 5;

    // What a runtime ID is, when it is not a state with an intermediary ID: a state of the custom block at cappedBlocks[CUSTOM - entry], which has no custom IDs, or an ID the palette does not define
    private static final int CUSTOM = -2;
    private static final int UNDEFINED = -1;

    private final Vanilla vanilla;
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
    // The blocks customIds gave IDs to for this palette, which it gets back once nothing holds the palette: the cache, or a player's mapping until BedrockPalette.release.
    private final CustomIds customIds;
    private final CustomBlock[] heldBlocks;
    private int holders;
    private boolean released;

    private BedrockPalette(Vanilla vanilla, boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread) {
        this(vanilla, hashedIds, blocks, customBlocksUnread, CUSTOM_IDS, LIMITS);
    }

    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds) {
        this(Vanilla.of(TABLE), hashedIds, blocks, customBlocksUnread, customIds, LIMITS);
    }

    BedrockPalette(BedrockBlockTable table, boolean hashedIds, List<CustomBlock> blocks, CustomIds customIds) {
        this(Vanilla.of(table), hashedIds, blocks, false, customIds, LIMITS);
    }

    // Tests give a palette custom IDs and limits of its own, so that it can run out of them. Throws IllegalArgumentException past the limits on hashing.
    BedrockPalette(boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds, Limits limits) {
        this(Vanilla.of(TABLE), hashedIds, blocks, customBlocksUnread, customIds, limits);
    }

    private BedrockPalette(Vanilla vanilla, boolean hashedIds, List<CustomBlock> blocks, boolean customBlocksUnread, CustomIds customIds, Limits limits) {
        BedrockBlockTable table = vanilla.table;
        this.vanilla = vanilla;
        this.hashedIds = hashedIds;
        this.idsUnknown = customBlocksUnread && !hashedIds;
        // A block with no custom IDs gets CUSTOM minus its index in cappedBlocks. One palette takes no more than its share of the custom IDs, so that it leaves some for other servers.
        int[] firsts = customIds.of(blocks, limits.customIds());
        this.customIds = customIds;
        this.heldBlocks = IntStream.range(0, firsts.length).filter(i -> firsts[i] >= 0).mapToObj(blocks::get).toArray(CustomBlock[]::new);
        Map<CustomBlock, Integer> firstIds = new HashMap<>();
        List<CustomBlock> capped = new ArrayList<>();
        for (int i = 0; i < firsts.length; i++) {
            if (firsts[i] < 0) {
                firstIds.put(blocks.get(i), CUSTOM - capped.size());
                capped.add(blocks.get(i));
            } else {
                firstIds.put(blocks.get(i), firsts[i]);
            }
        }
        this.cappedBlocks = capped.toArray(CustomBlock[]::new);
        Map<CustomBlock, Integer> firstRuntimeIds = new HashMap<>();
        this.movedCustomIds = new Int2ObjectOpenHashMap<>();
        Runs runs;
        Int2ObjectOpenHashMap<Owner> owners;
        try {
            runs = Runs.of(vanilla, blocks, firstIds, firstRuntimeIds);
            owners = hashedIds ? hashedIds(table, runs, this.movedCustomIds, limits) : null;
        } catch (RuntimeException e) {
            customIds.release(this.heldBlocks);
            throw e;
        }
        if (hashedIds) {
            this.sortedRuns = null;
            this.nonVanillaHashes = owners.int2ObjectEntrySet().stream()
                    .filter(owner -> !this.isVanilla(owner.getValue().entry()) || table.networkHash(owner.getValue().entry()) != owner.getIntKey())
                    .mapToInt(Int2ObjectMap.Entry::getIntKey).sorted().toArray();
            this.nonVanillaHashIds = Arrays.stream(this.nonVanillaHashes).map(id -> owners.get(id).entry()).toArray();
            SimpleBlockDefinition[] definitions = vanilla.hashedDefinitions;
            for (int i = 0; i < this.nonVanillaHashes.length; i++) {
                int entry = this.nonVanillaHashIds[i];
                if (this.isVanilla(entry)) {
                    definitions = definitions == vanilla.hashedDefinitions ? definitions.clone() : definitions;
                    definitions[entry] = new SimpleBlockDefinition(table.name(entry), this.nonVanillaHashes[i], vanilla.states[entry]);
                }
            }
            this.definitions = definitions;
        } else {
            this.sortedRuns = runs;
            this.nonVanillaHashes = new int[0];
            this.nonVanillaHashIds = new int[0];
            this.definitions = new SimpleBlockDefinition[table.paletteSize()];
            for (int run = 0; run < runs.starts().length; run++) {
                int first = runs.entries()[run];
                if (this.isVanilla(first)) {
                    for (int i = first; i < first + runs.length(run); i++) {
                        this.definitions[i] = new SimpleBlockDefinition(table.name(i), runs.starts()[run] + i - first, vanilla.states[i]);
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
        return mappingInfo(TABLE, startGame);
    }

    // The same, for a client of this table's protocol
    public static BlockMappingInfo mappingInfo(BedrockBlockTable table, StartGamePacket startGame) {
        Vanilla vanilla = Vanilla.of(table);
        Key key = new Key(startGame.isBlockNetworkIdsHashed(), List.copyOf(startGame.getBlockProperties()));
        // The mapping holds its palette before the lock is let go, so that the cache cannot drop the palette's blocks first.
        synchronized (vanilla.cache) {
            BedrockPalette palette = vanilla.cache.get(key);
            if (palette == null) {
                palette = build(vanilla, key);
                palette.hold();
                vanilla.cache.put(key, palette);
            }
            return palette.mappingInfo();
        }
    }

    /**
     * Lets go of the palette of a mapping that {@link #mappingInfo(StartGamePacket)} made, once its player has left. Releasing a mapping again does nothing.
     * Its custom blocks keep their IDs while another mapping or the cache holds the palette.
     */
    public static void release(BlockMappingInfo info) {
        ((PlayerIds) info.fromIntermediary()).release();
    }

    // Public for tests
    public static boolean released(BlockMappingInfo info) {
        return ((PlayerIds) info.fromIntermediary()).released;
    }

    private synchronized void hold() {
        if (this.released) {
            throw new IllegalStateException("The custom blocks of this palette may have other IDs now");
        }
        this.holders++;
    }

    private synchronized void drop() {
        if (--this.holders == 0 && this.heldBlocks.length > 0) {
            this.released = true;
            this.customIds.release(this.heldBlocks);
        }
    }

    // A new block mapping of this palette, which holds it until it is released. Boar only guesses the shape of a block that is not a vanilla state, so rawIdDefined is false for it.
    BlockMappingInfo mappingInfo() {
        PlayerIds ids = new PlayerIds(this);
        BedrockBlockTable table = this.vanilla.table;
        int air = table.firstIndex("minecraft:air");
        BlockMappingInfo info = new BlockMappingInfo(
                this.definitions[air],
                this.definitions[table.defaultIndex("minecraft:water")],
                this.definitions[table.defaultIndex("minecraft:lava")],
                this.definitions[table.defaultIndex("minecraft:powder_snow")],
                IntList.of(this.definitions[air].getRuntimeId()),
                definition -> this.isItemFrame(definition.getRuntimeId()),
                ids::intermediaryId,
                ids,
                // Boar falls back to the block state for FallingFlowMaterial.CUSTOM, which is a full cube.
                runtimeId -> {
                    if (!this.vanilla.fallingFlowSupported) {
                        return FallingFlowMaterial.UNSUPPORTED_PROTOCOL;
                    }
                    int entry = this.entry(runtimeId);
                    return this.isVanilla(entry) ? this.vanilla.flows[entry] : FallingFlowMaterial.CUSTOM;
                },
                runtimeId -> {
                    int entry = this.entry(runtimeId);
                    return this.isVanilla(entry) ? this.vanilla.jumps[entry] : HorseJumpMaterial.NONE;
                },
                runtimeId -> this.isVanilla(this.entry(runtimeId)));
        this.hold();
        return info;
    }

    // When Boar cannot read the custom blocks, their runtime IDs are unknown. With sorted IDs they also move the vanilla IDs, so every ID is unknown.
    private static BedrockPalette build(Vanilla shared, Key key) {
        BedrockBlockTable table = shared.table;
        // As BlockDefinitionGroup::digestServerBlockProperties: it skips a block without a vanilla_block_data compound before it reads the block's properties, so the block has no states, and an earlier block of its name stays. Of the other blocks, it keeps the last of a name.
        Map<String, BlockPropertyData> byName = new LinkedHashMap<>();
        Map<String, BlockPropertyData> vanilla = new LinkedHashMap<>();
        Set<String> skipped = new LinkedHashSet<>();
        for (BlockPropertyData data : key.blocks()) {
            boolean read = data.getProperties().get("vanilla_block_data") instanceof NbtMap;
            // A block of a vanilla name: the client adds its traits and properties to that vanilla block, since the name is in the minecraft namespace.
            if (table.firstIndex(data.getName()) >= 0) {
                if (read) {
                    vanilla.put(data.getName(), data);
                }
            } else if (read) {
                byName.put(data.getName(), data);
            } else {
                skipped.add(data.getName());
            }
        }
        warn(skipped, "A copy of custom block {} has no vanilla_block_data compound, so the client skips that copy, and so does Boar{}");
        List<CustomBlock> blocks = new ArrayList<>();
        CustomCollision.Budget collisionBudget = new CustomCollision.Budget();
        CustomCollision.Budget componentsBudget = new CustomCollision.Budget();
        long states = 0;
        try {
            Set<String> merged = new LinkedHashSet<>();
            Set<String> withComponents = new LinkedHashSet<>();
            for (BlockPropertyData data : vanilla.values()) {
                if (CustomBlock.keepsVanillaStates(data, table.states(table.firstIndex(data.getName())))) {
                    merged.add(data.getName());
                }
                if (data.getProperties().get("components") instanceof NbtMap components && !components.isEmpty()
                        || data.getProperties().get("permutations") instanceof NbtList<?> permutations && !permutations.isEmpty()) {
                    withComponents.add(data.getName());
                }
            }
            warn(merged, "StartGame gives vanilla block {} traits whose states it already has, so the client keeps its states, and so does Boar{}");
            // One line for all of them, since Geyser gives 98 vanilla blocks components on 26.50 and later
            if (!withComponents.isEmpty()) {
                String examples = withComponents.stream().limit(MAX_EXAMPLE_NAMES).collect(Collectors.joining(", ")) + (withComponents.size() > MAX_EXAMPLE_NAMES ? ", ..." : "");
                LOG.warn("StartGame gives components or permutations to vanilla blocks, {} of them ({}), which the client adds to those blocks and which can change their collision shapes, but Boar keeps the vanilla shapes", withComponents.size(), examples);
            }
            for (BlockPropertyData data : byName.values()) {
                CustomBlock block = CustomBlock.of(data, collisionBudget, componentsBudget);
                states += block.stateCount();
                if (key.hashed() && states > MAX_STATES) {
                    throw new IllegalArgumentException("Boar hashes no more than " + MAX_STATES + " custom states");
                }
                blocks.add(block);
            }
            return blocks.isEmpty() ? shared.of(key.hashed()) : new BedrockPalette(shared, key.hashed(), blocks, false);
        } catch (IllegalArgumentException e) {
            LOG.error("StartGame has custom blocks Boar cannot read ({}), so Boar treats {} as unknown", e.getMessage(), key.hashed() ? "their block IDs" : "every block ID");
            return key.hashed() ? shared.unreadHashed : shared.unknown;
        }
    }

    // Logs each of these block names with this message, which takes the name and a suffix for the last name logged, up to MAX_WARNED_NAMES of them
    private static void warn(Set<String> names, String message) {
        int warned = 0;
        for (String name : names) {
            if (++warned > MAX_WARNED_NAMES) {
                break;
            }
            LOG.warn(message, name, warned == MAX_WARNED_NAMES ? ", and logs no more such blocks of this palette" : "");
        }
    }

    // The palette without custom blocks
    static BedrockPalette of(boolean hashedIds) {
        return Vanilla.of(TABLE).of(hashedIds);
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

    // The definition of an intermediary ID: for a shared ID, that of this table's closest state, or UNKNOWN_DEFINITION when the table has no block of its name or this palette has no runtime ID for it. A mapping holds the runtime IDs of the IDs from FIRST_KEPT_ID.
    BlockDefinition definition(int id) {
        if (id >= 0 && id < FIRST_CUSTOM_ID) {
            int index = this.vanilla.ids.fromShared()[id];
            return index < 0 ? UNKNOWN_DEFINITION : this.definitions[index];
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
        return palette.vanilla.table.firstIndex(name) >= 0 || palette.customIndices.containsKey(name) || Arrays.stream(palette.cappedBlocks).anyMatch(block -> block.name().equals(name));
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

    // The palette index or custom ID of a runtime ID, or CUSTOM minus an index in cappedBlocks, or UNDEFINED
    private int entry(int runtimeId) {
        if (this.idsUnknown) {
            return UNDEFINED;
        }
        if (this.hashedIds) {
            int moved = Arrays.binarySearch(this.nonVanillaHashes, runtimeId);
            int index = this.vanilla.table.indexOfHash(runtimeId);
            return moved >= 0 ? this.nonVanillaHashIds[moved] : index >= 0 ? index : UNDEFINED;
        }
        return this.sortedRuns.entry(runtimeId);
    }

    private boolean isVanilla(int id) {
        return id >= 0 && id < this.vanilla.table.paletteSize();
    }

    private boolean isItemFrame(int runtimeId) {
        int entry = this.entry(runtimeId);
        return this.isVanilla(entry) && (this.vanilla.table.name(entry).equals("minecraft:frame") || this.vanilla.table.name(entry).equals("minecraft:glow_frame"));
    }

    /**
     * The state the client gives each runtime ID of a palette with hashed IDs.
     * The client gives each state its network hash, in the order of the sorted palette, or the next ID that is free or held by a state of the same block.
     * A shared ID means the later state, and minecraft:unknown always gets 0xFFFFFFFE (BlockHashPalette::assignBlockNetworkId, BlockHashPalette::appendBlock).
     *
     * @param movedCustomIds gets the runtime ID of each custom ID whose state does not have its network hash
     */
    private static Int2ObjectOpenHashMap<Owner> hashedIds(BedrockBlockTable table, Runs runs, Int2ObjectOpenHashMap<Integer> movedCustomIds, Limits limits) {
        Int2ObjectOpenHashMap<Owner> owners = new Int2ObjectOpenHashMap<>();
        CustomCollision.Budget bytes = new CustomCollision.Budget(limits.hashedBytes());
        CustomCollision.Budget steps = new CustomCollision.Budget(limits.clashSteps());
        for (int run = 0; run < runs.starts().length; run++) {
            int first = runs.entries()[run];
            CustomBlock block = runs.blocks()[run];
            for (int s = 0; s < runs.length(run); s++) {
                int entry = block == null ? first + s : first <= CUSTOM ? first : first + s;
                String name = block == null ? table.name(entry) : block.name();
                int hash;
                if (block == null) {
                    hash = table.networkHash(entry);
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
        static Runs of(Vanilla shared, List<CustomBlock> blocks, Map<CustomBlock, Integer> firstIds, Map<CustomBlock, Integer> firstRuntimeIds) {
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
                while (end < shared.table.paletteSize() && (block == null || Long.compareUnsigned(shared.nameHashes[end], nameHash(block.name())) <= 0)) {
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
     * The custom IDs that every palette shares, whatever its block table: a custom block gets one for each state, in the client's order, while any palette has it.
     * A block that no palette has keeps its IDs for a quarantine time, so that a state Boar made before its last player left still reads that block. Then they go to the first later block that fits.
     * Past the cap, or when no free range fits it, a block gets none, so a player's mapping keeps its states by runtime ID instead.
     * Freed IDs wait out the quarantine, so more new states than the cap within one quarantine time leave later blocks without IDs until it passes.
     */
    static final class CustomIds {
        static final long QUARANTINE = TimeUnit.MINUTES.toNanos(1);
        private final Map<CustomBlock, Assignment> assignments = new HashMap<>();
        private final TreeMap<Integer, Assignment> byFirstId = new TreeMap<>();
        // The ranges of free IDs, from each first ID to its end
        private final TreeMap<Integer, Integer> free = new TreeMap<>();
        // The blocks that no palette had any more, in the order they were released
        private final ArrayDeque<Release> releases = new ArrayDeque<>();
        private final int max;
        private final long quarantine;
        private final LongSupplier clock;
        private int quarantinedIds;
        // The fewest states of a block that found no free range since IDs were last freed, so that blocks with as many states or more fail without a scan, and the warning is logged once
        private int smallestMiss = Integer.MAX_VALUE;
        // The free ranges assign has looked at, for tests
        int scannedRanges;
        // The blocks by first custom ID, which BedrockState reads without the lock
        private volatile Assigned assigned = new Assigned(new int[0], new CustomBlock[0], new CustomBlockType[0]);

        CustomIds(int first, int max) {
            this(first, max, QUARANTINE, System::nanoTime);
        }

        CustomIds(int first, int max, long quarantine, LongSupplier clock) {
            this.max = max;
            this.quarantine = quarantine;
            this.clock = clock;
            this.free.put(first, first + max);
        }

        /**
         * The first custom ID of each block, or -1 for a block past the IDs left, or past {@code limit} IDs for the list. Each block with IDs keeps them until {@link #release} gives it back as many times.
         */
        synchronized int[] of(List<CustomBlock> blocks, long limit) {
            boolean changed = this.freeQuarantined();
            int[] firsts = new int[blocks.size()];
            long taken = 0;
            for (int i = 0; i < firsts.length; i++) {
                CustomBlock block = blocks.get(i);
                Assignment assignment = null;
                if (taken + block.stateCount() <= limit) {
                    assignment = this.assignments.get(block);
                    if (assignment == null) {
                        assignment = this.assign(block);
                        changed |= assignment != null;
                    } else if (assignment.palettes == 0) {
                        this.quarantinedIds -= block.stateCount();
                    }
                }
                if (assignment == null) {
                    firsts[i] = -1;
                    continue;
                }
                assignment.palettes++;
                firsts[i] = assignment.first;
                taken += block.stateCount();
            }
            if (changed) {
                this.publish();
            }
            return firsts;
        }

        synchronized void release(CustomBlock[] blocks) {
            long now = this.clock.getAsLong();
            for (CustomBlock block : blocks) {
                Assignment assignment = this.assignments.get(block);
                if (--assignment.palettes == 0) {
                    assignment.release = new Release(assignment, now);
                    this.releases.add(assignment.release);
                    this.quarantinedIds += block.stateCount();
                }
            }
        }

        // The block's new IDs, from the first free range that fits it, or null when no free range fits it
        private Assignment assign(CustomBlock block) {
            if (block.stateCount() >= this.smallestMiss) {
                return null;
            }
            for (Map.Entry<Integer, Integer> range : this.free.entrySet()) {
                this.scannedRanges++;
                int first = range.getKey();
                int end = range.getValue();
                if (end - first >= block.stateCount()) {
                    this.free.remove(first);
                    if (end > first + block.stateCount()) {
                        this.free.put(first + block.stateCount(), end);
                    }
                    Assignment assignment = new Assignment(block, first, new CustomBlockType(block, first));
                    this.assignments.put(block, assignment);
                    this.byFirstId.put(first, assignment);
                    block.collision().holdShapes();
                    return assignment;
                }
            }
            if (this.smallestMiss == Integer.MAX_VALUE) {
                LOG.warn("Boar's {} custom block IDs have no free range for custom block {}, so it treats it, and each block that does not fit until IDs are freed, as a block it does not know{}", this.max, block.name(),
                        this.quarantinedIds == 0 ? "" : ". " + this.quarantinedIds + " of them are held for a time after the last player of their block left");
            }
            this.smallestMiss = block.stateCount();
            return null;
        }

        // Frees the IDs of each block that no palette has had for the quarantine time, and returns whether it freed any
        private boolean freeQuarantined() {
            long now = this.clock.getAsLong();
            boolean freed = false;
            for (Release release; (release = this.releases.peek()) != null; this.releases.poll()) {
                Assignment assignment = release.assignment();
                // A block that a palette took again, or that was released again later
                if (assignment.palettes > 0 || assignment.release != release) {
                    continue;
                }
                if (now - release.time() < this.quarantine) {
                    break;
                }
                int first = assignment.first;
                int end = first + assignment.block.stateCount();
                this.assignments.remove(assignment.block);
                this.byFirstId.remove(first);
                assignment.block.collision().releaseShapes();
                this.quarantinedIds -= end - first;
                Map.Entry<Integer, Integer> before = this.free.lowerEntry(first);
                if (before != null && before.getValue() == first) {
                    first = before.getKey();
                }
                Integer after = this.free.remove(end);
                this.free.put(first, after != null ? after : end);
                freed = true;
            }
            if (freed) {
                this.smallestMiss = Integer.MAX_VALUE;
            }
            return freed;
        }

        private void publish() {
            int[] starts = new int[this.byFirstId.size()];
            CustomBlock[] blocks = new CustomBlock[starts.length];
            CustomBlockType[] types = new CustomBlockType[starts.length];
            int i = 0;
            for (Assignment assignment : this.byFirstId.values()) {
                starts[i] = assignment.first;
                blocks[i] = assignment.block;
                types[i++] = assignment.type;
            }
            this.assigned = new Assigned(starts, blocks, types);
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

        // Freed IDs leave gaps between blocks.
        private record Assigned(int[] starts, CustomBlock[] blocks, CustomBlockType[] types) {
            // The index of the block with this custom ID, or -1
            int blockOf(int id) {
                int i = Arrays.binarySearch(this.starts, id);
                if (i < 0) {
                    i = -i - 2;
                }
                return i >= 0 && id - this.starts[i] < this.blocks[i].stateCount() ? i : -1;
            }
        }

        // A block's IDs, and how many palettes have it
        private static final class Assignment {
            final CustomBlock block;
            final int first;
            final CustomBlockType type;
            int palettes;
            // The latest release, which is in releases
            Release release;

            Assignment(CustomBlock block, int first, CustomBlockType type) {
                this.block = block;
                this.first = first;
                this.type = type;
            }
        }

        private record Release(Assignment assignment, long time) {
        }
    }

    /**
     * One player's mapping of runtime IDs that are not a state with an intermediary ID of their own: it keeps each under an ID from FIRST_KEPT_ID, so that Boar can send it back, and logs it.
     * A server's unknown IDs then reach only its own players. Only the player's event loop uses it, as with Boar's world, so it takes no lock but to release its palette.
     */
    private static final class PlayerIds implements IntUnaryOperator {
        private final BedrockPalette palette;
        // With hashed IDs, the kept runtime IDs by their index past FIRST_KEPT_ID, and that index by runtime ID
        private final IntList keptHashes = new IntArrayList();
        private final Int2ObjectOpenHashMap<Integer> keptHashIndices = new Int2ObjectOpenHashMap<>();
        // The unknown runtime IDs logged so far, which stop at MAX_WARNED_IDS
        private final int[] warnedIds = new int[MAX_WARNED_IDS];
        private int warnedCount;
        private volatile boolean released;

        PlayerIds(BedrockPalette palette) {
            this.palette = palette;
        }

        void release() {
            synchronized (this.palette) {
                if (!this.released) {
                    this.released = true;
                    this.palette.drop();
                }
            }
        }

        int intermediaryId(int runtimeId) {
            int entry = this.palette.entry(runtimeId);
            if (this.palette.isVanilla(entry)) {
                return this.palette.vanilla.ids.toShared()[entry];
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

    // What every palette of one block table shares, by vanilla palette index, with the shared ID of each
    private static final class Vanilla {
        private static final Map<Integer, Vanilla> BY_PROTOCOL = new ConcurrentHashMap<>();

        final BedrockBlockTable table;
        final BedrockBlockUnion.Projection ids;
        final Map<Key, BedrockPalette> cache = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, BedrockPalette> eldest) {
                if (this.size() <= CACHED_PALETTES) {
                    return false;
                }
                eldest.getValue().drop();
                return true;
            }
        };
        final NbtMap[] states;
        final long[] nameHashes;
        final FallingFlowMaterial[] flows;
        final HorseJumpMaterial[] jumps;
        // Every hashed palette shares these, unless a custom state takes a vanilla state's network hash.
        final SimpleBlockDefinition[] hashedDefinitions;
        final boolean fallingFlowSupported;
        final BedrockPalette sorted;
        final BedrockPalette hashed;
        final BedrockPalette unknown;
        final BedrockPalette unreadHashed;

        private Vanilla(BedrockBlockTable table) {
            this.table = table;
            this.ids = BedrockBlockUnion.get().projection(table.protocol());
            int size = table.paletteSize();
            this.states = new NbtMap[size];
            this.nameHashes = new long[size];
            this.flows = new FallingFlowMaterial[size];
            this.jumps = new HorseJumpMaterial[size];
            this.hashedDefinitions = new SimpleBlockDefinition[size];
            // Boar has no falling-flow data for 26.60 (protocol 2225), so its blocks take 26.50's (2193).
            // A state that 26.60 changed takes its block's 26.50 material.
            BedrockBlockTable flowTable = table.protocol() == 2225 ? BedrockBlockTable.forProtocol(2193) : table;
            FallingFlowMaterials.Lookup fallingFlow = FallingFlowMaterials.forProtocol(flowTable.protocol());
            Map<String, FallingFlowMaterial> blockFlows = new HashMap<>();
            this.fallingFlowSupported = fallingFlow.isSupported();
            for (int i = 0; i < size; i++) {
                String name = table.name(i);
                this.states[i] = NbtMap.builder().putString("name", name).putCompound("states", table.states(i)).build();
                this.nameHashes[i] = nameHash(name);
                this.flows[i] = fallingFlow.resolve(name, table.states(i));
                if (this.flows[i] == FallingFlowMaterial.NOT_SEARCHED && flowTable != table) {
                    this.flows[i] = blockFlows.computeIfAbsent(name, block -> blockFlow(fallingFlow, flowTable, block));
                }
                this.jumps[i] = HorseJumpMaterials.resolve(name);
                this.hashedDefinitions[i] = new SimpleBlockDefinition(name, table.networkHash(i), this.states[i]);
            }
            this.sorted = new BedrockPalette(this, false, List.of(), false);
            this.hashed = new BedrockPalette(this, true, List.of(), false);
            this.unknown = new BedrockPalette(this, false, List.of(), true);
            this.unreadHashed = new BedrockPalette(this, true, List.of(), true);
        }

        // The material every state of the block has in the table, or NOT_SEARCHED when the table has no such block or its states do not share one known material
        private static FallingFlowMaterial blockFlow(FallingFlowMaterials.Lookup lookup, BedrockBlockTable table, String name) {
            int first = table.firstIndex(name);
            if (first < 0) {
                return FallingFlowMaterial.NOT_SEARCHED;
            }
            FallingFlowMaterial flow = lookup.resolve(name, table.states(first));
            for (int i = first + 1; i < first + table.stateCount(name); i++) {
                if (lookup.resolve(name, table.states(i)) != flow) {
                    return FallingFlowMaterial.NOT_SEARCHED;
                }
            }
            return flow.isKnown() ? flow : FallingFlowMaterial.NOT_SEARCHED;
        }

        // By protocol, so that a table loaded again shares the cache of the table of its protocol
        static Vanilla of(BedrockBlockTable table) {
            return BY_PROTOCOL.computeIfAbsent(table.protocol(), protocol -> new Vanilla(Objects.requireNonNull(BedrockBlockTable.forProtocol(protocol), "No block table for protocol " + protocol)));
        }

        BedrockPalette of(boolean hashedIds) {
            return hashedIds ? this.hashed : this.sorted;
        }
    }
}
