package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.effect.EffectProvider;
import ac.boar.anticheat.data.enchantment.EnchantmentProvider;
import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.player.accessor.EntityAccessor;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityDefinitions;
import ac.boar.mappings.entity.EntityType;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

import java.util.List;

/**
 * Stand-ins for the item and entity data Boar needs to start, until boarium has the real data.
 * No item is a block, and every entity has a player's size.
 */
final class Placeholders {
    // Every effect and enchantment counts as one Boar does not use.
    static final EffectProvider EFFECTS = id -> null;
    static final EnchantmentProvider ENCHANTMENTS = id -> null;

    static final ItemStackProvider ITEM_STACKS = new ItemStackProvider() {
        @Override
        public BoarItemStack create(NetworkSession session, ItemData data) {
            return EmptyItemStack.INSTANCE;
        }

        @Override
        public BoarItemStack create(NetworkSession session, Reference<Item> item, int amount) {
            return EmptyItemStack.INSTANCE;
        }
    };

    static final EntityAccessor ENTITIES = new EntityAccessor() {
        @Override
        public Entity entityByRuntimeId(long runtimeId) {
            return null;
        }

        @Override
        public EntityDefinition definitionByRuntimeId(long runtimeId) {
            return null;
        }
    };

    static final InventoryAccessor INVENTORY = new InventoryAccessor() {
        @Override
        public void updateSlot(int slot) {
        }

        @Override
        public int heldItemSlot() {
            return 0;
        }
    };

    static final ItemMappings ITEM_MAPPINGS = new ItemMappings() {
        public List<Item> getBundleItems() { return List.of(); }
        public List<Item> getCandleItems() { return List.of(); }
        public List<Item> getHeadItems() { return List.of(); }
    };

    private Placeholders() {
    }

    static void populateItems(ReferencePopulator<Item> items) {
        items.populate((key, item) -> item.populate(new NamedItem(key)));
        items.ensurePopulated(true);
    }

    static void populateEntities(ReferencePopulator<EntityType> types, ReferencePopulator<EntityDefinition> definitions) {
        types.populate((key, type) -> {
            NamedEntityType named = new NamedEntityType(key);
            type.populate(named);
            definitions.populate(key, new PlayerSizedDefinition(key, named));
        });
        types.ensurePopulated(true);
        definitions.ensurePopulated(true);
    }

    static Entity playerEntity() {
        return new PlayerEntity();
    }

    private record NamedItem(String key) implements Item {
        @Override
        public boolean is(Item value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<Item> reference) {
            return reference.find().map(this::is).orElse(false);
        }

        @Override
        public boolean isBlock() {
            return false;
        }
    }

    private record NamedEntityType(String key) implements EntityType {
        @Override
        public boolean is(EntityType value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityType> reference) {
            return reference.find().map(this::is).orElse(false);
        }
    }

    private record PlayerSizedDefinition(String identifier, EntityType type) implements EntityDefinition {
        @Override
        public boolean is(EntityDefinition value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityDefinition> reference) {
            return reference.find().map(this::is).orElse(false);
        }

        @Override
        public float width() {
            return 0.6F;
        }

        @Override
        public float height() {
            return 1.8F;
        }

        @Override
        public float offset() {
            return 1.62F;
        }
    }

    private static final class EmptyItemStack implements BoarItemStack {
        static final EmptyItemStack INSTANCE = new EmptyItemStack();
        private static final Item AIR_ITEM = new NamedItem("minecraft:air");

        @Override
        public Item item() {
            return AIR_ITEM;
        }

        @Override
        public boolean is(Item item) {
            return false;
        }

        @Override
        public boolean is(Reference<Item> item) {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemData toItemData(NetworkSession session) {
            return ItemData.AIR;
        }
    }

    private static final class PlayerEntity implements Entity {
        @Override
        public EntityDefinition definition() {
            return EntityDefinitions.PLAYER.find().orElseThrow();
        }

        @Override
        public Entity vehicle() {
            return null;
        }

        @Override
        public Vector3i bedPosition() {
            return null;
        }

        @Override
        public float bbWidth() {
            return 0.6F;
        }

        @Override
        public float bbHeight() {
            return 1.8F;
        }

        @Override
        public <T> void metadata(EntityDataType<T> type, T value) {
        }

        @Override
        public void refreshMetadata() {
        }

        @Override
        public void refreshAttributesToSelf() {
        }

        @Override
        public void releaseItem() {
        }
    }
}
