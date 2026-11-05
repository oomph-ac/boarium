package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.boar.mappings.item.Items;
import ac.oomph.boarium.boar.blocks.BedrockBlock;
import ac.oomph.boarium.boar.blocks.BedrockBlockTable;
import ac.oomph.boarium.boar.blocks.BedrockPalette;
import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Boar's items by Bedrock name, as in an ItemDefinition. A block item is named after the Bedrock block it places, except the signs and the items in PLACES.
 * A server's own item places a custom block when the client's does.
 * Boar names its item references by Java identifier, and each of those is also the item's Bedrock name.
 */
final class BedrockItems implements ItemStackProvider, ItemMappings {
    private static final Logger LOG = LoggerFactory.getLogger(BedrockItems.class);
    private static final List<String> NAMES = readNames("26_30");
    // The items that place a block of another name, besides the signs
    private static final Map<String, String> PLACES = Map.ofEntries(
            Map.entry("minecraft:banner", "minecraft:standing_banner"),
            Map.entry("minecraft:beetroot_seeds", "minecraft:beetroot"),
            Map.entry("minecraft:carrot", "minecraft:carrots"),
            Map.entry("minecraft:cocoa_beans", "minecraft:cocoa"),
            Map.entry("minecraft:comparator", "minecraft:unpowered_comparator"),
            Map.entry("minecraft:dark_oak_sign", "minecraft:darkoak_standing_sign"),
            Map.entry("minecraft:glow_berries", "minecraft:cave_vines"),
            Map.entry("minecraft:light_block", "minecraft:light_block_15"),
            Map.entry("minecraft:melon_seeds", "minecraft:melon_stem"),
            Map.entry("minecraft:oak_sign", "minecraft:standing_sign"),
            Map.entry("minecraft:pitcher_pod", "minecraft:pitcher_crop"),
            Map.entry("minecraft:potato", "minecraft:potatoes"),
            Map.entry("minecraft:powder_snow_bucket", "minecraft:powder_snow"),
            Map.entry("minecraft:pumpkin_seeds", "minecraft:pumpkin_stem"),
            Map.entry("minecraft:redstone", "minecraft:redstone_wire"),
            Map.entry("minecraft:repeater", "minecraft:unpowered_repeater"),
            Map.entry("minecraft:string", "minecraft:trip_wire"),
            Map.entry("minecraft:sugar_cane", "minecraft:reeds"),
            Map.entry("minecraft:sweet_berries", "minecraft:sweet_berry_bush"),
            Map.entry("minecraft:torchflower_seeds", "minecraft:torchflower_crop"),
            Map.entry("minecraft:wheat_seeds", "minecraft:wheat"));
    // Items named after a block that do not place it: the beetroot and wheat a player eats or crafts with (the crops' own block items are minecraft:item.beetroot and minecraft:item.wheat), and air and the camera, which Geyser's Java mapping gives no block either.
    private static final Set<String> NOT_BLOCKS = Set.of("minecraft:air", "minecraft:beetroot", "minecraft:wheat", "minecraft:camera");
    // One item for each 1.26.30 name, made at start, so items compare by identity.
    private static final Map<String, BedrockItem> ITEMS = NAMES.stream().collect(Collectors.toUnmodifiableMap(name -> name, name -> {
        BedrockBlock block = block(name);
        return new BedrockItem(name, block, block != null);
    }));
    // The block of every item. Only the 1.26.30 block items are its entries, since the blocks of a server's own items differ by session.
    static final Map<Item, Block> ITEM_TO_BLOCK = new ItemToBlock(itemToBlock());
    static final BedrockItem AIR = ITEMS.get("minecraft:air");
    // The item of every name past a session's limit of its server's own items
    static final BedrockItem UNKNOWN = new BedrockItem("boarium:unknown", null, false);
    private static final Stack EMPTY = new Stack(AIR, 0);
    static final List<String> REFERENCES = referenceNames();
    static final BedrockItems INSTANCE = new BedrockItems();
    private static final Map<String, Version> LOADED = new ConcurrentHashMap<>();
    // The items of the names that are not 1.26.30 items
    private static final Map<String, BedrockItem> OTHER_ITEMS = new ConcurrentHashMap<>();

    private final List<Item> bundles = matching(name -> name.equals("minecraft:bundle") || name.endsWith("_bundle"));
    private final List<Item> candles = matching(name -> name.equals("minecraft:candle") || name.endsWith("_candle"));
    private final List<Item> heads = matching(name -> name.equals("minecraft:skeleton_skull") || name.equals("minecraft:wither_skeleton_skull") || name.endsWith("_head"));

    private BedrockItems() {
    }

    static void populate(ReferencePopulator<Item> references) {
        Set<String> unmatched = new TreeSet<>();
        references.populate((key, reference) -> {
            BedrockItem item = ITEMS.get(key);
            if (item != null) {
                reference.populate(item);
            } else {
                unmatched.add(key);
            }
        });
        references.ensurePopulated(false);
        if (!unmatched.isEmpty()) {
            LOG.warn("Boar's item references with no Bedrock 1.26.30 item, left empty: {}", unmatched);
        }
    }

    // The names of Boar's item references that are not in a server's item list
    static List<String> missingReferences(DefinitionRegistry<ItemDefinition> definitions) {
        return REFERENCES.stream().filter(name -> definitions.getDefinition(name) == null).toList();
    }

    // Runs for every slot Boar reads, so it gives the item's shared stack, which keeps the item but not the count or NBT.
    @Override
    public BoarItemStack create(NetworkSession session, ItemData data) {
        ItemDefinition definition = data.getDefinition();
        if (definition == null || data.getCount() <= 0) {
            return EMPTY;
        }
        BedrockItem item = ITEMS.get(definition.getIdentifier());
        if (item == null) {
            item = ((BoariumPlayerManager.BoariumNetworkSession) session).items().own(definition.getIdentifier());
        }
        return item == AIR ? EMPTY : item.stack;
    }

    @Override
    public BoarItemStack create(NetworkSession session, Reference<Item> reference, int amount) {
        BedrockItem item = (BedrockItem) reference.find().orElse(AIR);
        return item == AIR || amount <= 0 ? EMPTY : new Stack(item, amount);
    }

    @Override
    public List<Item> getBundleItems() {
        return this.bundles;
    }

    @Override
    public List<Item> getCandleItems() {
        return this.candles;
    }

    @Override
    public List<Item> getHeadItems() {
        return this.heads;
    }

    // The items of a protocol's clients, loaded once on first use, or null for a protocol with none
    static Version forProtocol(int protocol) {
        String version = BedrockBlockTable.version(protocol);
        return version == null ? null : LOADED.computeIfAbsent(version, v -> new Version(v, BedrockBlockTable.forProtocol(protocol)));
    }

    // The block an item places, or null
    private static BedrockBlock block(String name) {
        String block = placedBlock(name, BedrockBlockTable.get());
        return block == null ? null : BedrockBlock.named(block);
    }

    // The name of the block an item places, or null when the item places none or the table has no such block
    private static String placedBlock(String name, BedrockBlockTable table) {
        String block = name;
        // A double slab item places a full block, but its block is the slab, whose default is the bottom half.
        if (NOT_BLOCKS.contains(name) || name.endsWith("_slab") && name.contains("double")) {
            return null;
        } else if (PLACES.containsKey(name)) {
            block = PLACES.get(name);
        } else if (name.endsWith("_sign") && table.firstIndex(name) < 0) {
            // A sign item places the standing sign; the wall sign is another block.
            block = name.replace("_sign", "_standing_sign");
        }
        return table.firstIndex(block) >= 0 ? block : null;
    }

    private static Map<Item, Block> itemToBlock() {
        Map<Item, Block> blocks = new HashMap<>();
        for (BedrockItem item : ITEMS.values()) {
            if (item.block != null) {
                blocks.put(item, item.block);
            }
        }
        return Map.copyOf(blocks);
    }

    private static List<Item> matching(Predicate<String> name) {
        return NAMES.stream().filter(name).<Item>map(ITEMS::get).toList();
    }

    private static List<String> readNames(String version) {
        String resource = "item_names." + version + ".txt";
        try (InputStream in = BedrockItems.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().filter(line -> !line.startsWith("#")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> referenceNames() {
        List<String> names = new ArrayList<>();
        for (Field field : Items.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Reference.class) {
                try {
                    names.add(((Reference.Deferred<?>) field.get(null)).key());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return List.copyOf(names);
    }

    private static final class ItemToBlock extends AbstractMap<Item, Block> {
        private final Map<Item, Block> vanilla;

        ItemToBlock(Map<Item, Block> vanilla) {
            this.vanilla = vanilla;
        }

        @Override
        public Block get(Object key) {
            return key instanceof BedrockItem item ? item.block : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return this.get(key) != null;
        }

        @Override
        public Set<Entry<Item, Block>> entrySet() {
            return this.vanilla.entrySet();
        }
    }

    /**
     * The items of one version's item names, and the name of the block each block item places in that version's block table.
     * Every version shares one item per name, since Boar's item references are filled once and items compare by identity, so a 1.26.30 name gives its 1.26.30 item.
     */
    static final class Version {
        final List<String> names;
        final Map<String, BedrockItem> items;
        // The name of the block each block item places
        final Map<BedrockItem, String> blocks;

        private Version(String version, BedrockBlockTable table) {
            this.names = readNames(version);
            Map<String, BedrockItem> items = new HashMap<>();
            Map<BedrockItem, String> blocks = new HashMap<>();
            for (String name : this.names) {
                String block = placedBlock(name, table);
                // A name that is not a 1.26.30 item has no Boar block, since Boar's blocks are the 1.26.30 table's
                BedrockItem item = ITEMS.containsKey(name) ? ITEMS.get(name) : OTHER_ITEMS.computeIfAbsent(name, n -> new BedrockItem(n, null, block != null));
                if (item.isBlock() != (block != null)) {
                    throw new IllegalStateException(name + " is a block item in one version and not in another, here " + version);
                }
                items.put(name, item);
                if (block != null) {
                    blocks.put(item, block);
                }
            }
            this.items = Map.copyOf(items);
            this.blocks = Map.copyOf(blocks);
        }

        // The names of Boar's item references that have no item in this version
        List<String> missingReferences() {
            return REFERENCES.stream().filter(name -> !this.items.containsKey(name)).toList();
        }
    }

    /**
     * The items of one session: the first server's item definitions, and the items of its names that are not 1.26.30 items.
     * Only used on the client's event loop, where Boar runs.
     */
    static final class SessionItems {
        // Far more than a server has items of its own
        static final int OWN_LIMIT = 1024;

        final DefinitionRegistry<ItemDefinition> definitions;
        // The client's block mapping, whose palette has the session's custom blocks
        private final BlockMappingInfo blocks;
        private final Map<String, BedrockItem> own = new HashMap<>();

        SessionItems(DefinitionRegistry<ItemDefinition> definitions, BlockMappingInfo blocks) {
            this.definitions = definitions;
            this.blocks = blocks;
        }

        BedrockItem own(String name) {
            BedrockItem item = this.own.get(name);
            if (item == null) {
                if (this.own.size() >= OWN_LIMIT) {
                    return UNKNOWN;
                }
                item = this.ownItem(name);
                this.own.put(name, item);
            }
            return item;
        }

        /**
         * The item of a name the server added. ItemRegistry::initClient makes a ComponentItem of a data-driven item, which places the block its minecraft:block_placer names.
         * It makes no item of version NONE, so ItemRegistry::finishedRegistration makes the block item of a custom block of that name. A legacy item is a plain item of that name, so the block item gets another name.
         * An item that places a block Boar does not place is a block item without a block, which Boar counts down without placing anything.
         */
        private BedrockItem ownItem(String name) {
            ItemDefinition definition = this.definitions.getDefinition(name);
            ItemVersion version = definition == null ? null : definition.getVersion();
            if (version == ItemVersion.NONE && BedrockPalette.hasBlock(this.blocks, name)) {
                return new BedrockItem(name, BedrockPalette.placedBlock(this.blocks, name), true);
            }
            if (version != ItemVersion.DATA_DRIVEN
                    || !(blockPlacer(definition.getComponentData()) instanceof NbtMap placer)
                    || !(placer.get("block") instanceof String block)
                    || !BedrockPalette.hasBlock(this.blocks, block)) {
                return new BedrockItem(name, null, false);
            }
            // PlanterItemComponent::canUseOn allows any clicked block when use_on is empty, and only the blocks it lists otherwise, which Boar does not check.
            boolean anyBlock = !placer.containsKey("use_on") || placer.get("use_on") instanceof NbtList<?> useOn && useOn.isEmpty();
            return new BedrockItem(name, anyBlock ? BedrockPalette.placedBlock(this.blocks, block) : null, true);
        }

        private static Object blockPlacer(NbtMap data) {
            return data != null && data.get("components") instanceof NbtMap components ? components.get("minecraft:block_placer") : null;
        }
    }

    static final class BedrockItem implements Item {
        private final String name;
        // The block Boar places with it, or null
        private final Block block;
        private final boolean placesBlock;
        private final Stack stack;

        private BedrockItem(String name, Block block, boolean placesBlock) {
            this.name = name;
            this.block = block;
            this.placesBlock = placesBlock;
            this.stack = new Stack(this, 1);
        }

        @Override
        public boolean is(Item value) {
            return value == this;
        }

        @Override
        public boolean is(Reference<Item> reference) {
            return reference.find().orElse(null) == this;
        }

        @Override
        public boolean isBlock() {
            return this.placesBlock;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private record Stack(BedrockItem item, int amount) implements BoarItemStack {
        @Override
        public boolean is(Item item) {
            return item == this.item;
        }

        @Override
        public boolean is(Reference<Item> reference) {
            return this.item.is(reference);
        }

        @Override
        public boolean isEmpty() {
            return this.amount <= 0;
        }

        // The item with the client's runtime ID, from the first server's item list, or air when that list has no such item.
        @Override
        public ItemData toItemData(NetworkSession session) {
            ItemDefinition definition = this.isEmpty() ? null : ((BoariumPlayerManager.BoariumNetworkSession) session).items().definitions.getDefinition(this.item.name);
            if (definition == null) {
                return ItemData.AIR;
            }
            return ItemData.builder().definition(definition).count(this.amount).blockDefinition(Definitions.ANY_BLOCK.getDefinition(0)).build();
        }
    }
}
