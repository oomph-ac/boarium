package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.boar.mappings.item.Items;
import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Boar's items by Bedrock name, as in an ItemDefinition. No item is a block.
 * Boar names its item references by Java identifier, and each of those is also the item's Bedrock name.
 */
final class BedrockItems implements ItemStackProvider, ItemMappings {
    private static final Logger LOG = LoggerFactory.getLogger(BedrockItems.class);
    private static final List<String> NAMES = readNames();
    // One item for each 1.26.30 name, made at start, so items compare by identity.
    private static final Map<String, BedrockItem> ITEMS = NAMES.stream().collect(Collectors.toUnmodifiableMap(name -> name, BedrockItem::new));
    static final BedrockItem AIR = ITEMS.get("minecraft:air");
    // The item of every name past a session's limit of its server's own items
    static final BedrockItem UNKNOWN = new BedrockItem("boarium:unknown");
    private static final Stack EMPTY = new Stack(AIR, 0);
    static final List<String> REFERENCES = referenceNames();
    static final BedrockItems INSTANCE = new BedrockItems();

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

    private static List<Item> matching(Predicate<String> name) {
        return NAMES.stream().filter(name).<Item>map(ITEMS::get).toList();
    }

    private static List<String> readNames() {
        try (InputStream in = BedrockItems.class.getResourceAsStream("item_names.26_30.txt")) {
            if (in == null) {
                throw new IllegalStateException("item_names.26_30.txt is not on the classpath");
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

    /**
     * The items of one session: the first server's item definitions, and the items of its names that are not 1.26.30 items.
     * Only used on the client's event loop, where Boar runs.
     */
    static final class SessionItems {
        // Far more than a server has items of its own
        static final int OWN_LIMIT = 1024;

        final DefinitionRegistry<ItemDefinition> definitions;
        private final Map<String, BedrockItem> own = new HashMap<>();

        SessionItems(DefinitionRegistry<ItemDefinition> definitions) {
            this.definitions = definitions;
        }

        BedrockItem own(String name) {
            BedrockItem item = this.own.get(name);
            if (item == null) {
                if (this.own.size() >= OWN_LIMIT) {
                    return UNKNOWN;
                }
                item = new BedrockItem(name);
                this.own.put(name, item);
            }
            return item;
        }
    }

    static final class BedrockItem implements Item {
        private final String name;
        private final Stack stack;

        private BedrockItem(String name) {
            this.name = name;
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
            return false;
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
