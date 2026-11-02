package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

import java.util.List;

/**
 * Stand-ins for the item and inventory data Boar needs to start, until boarium has the real data.
 * No item is a block.
 */
final class Placeholders {
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
}
