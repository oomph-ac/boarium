package ac.oomph.boarium.boar;

import ac.boar.anticheat.player.accessor.InventoryAccessor;

/**
 * Stand-ins for the inventory data Boar needs to start, until boarium has the real data.
 */
final class Placeholders {
    static final InventoryAccessor INVENTORY = new InventoryAccessor() {
        @Override
        public void updateSlot(int slot) {
        }

        @Override
        public int heldItemSlot() {
            return 0;
        }
    };

    private Placeholders() {
    }
}
