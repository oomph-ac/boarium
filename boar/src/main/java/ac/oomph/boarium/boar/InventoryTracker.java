package ac.oomph.boarium.boar;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.oomph.boarium.session.Session;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;

import java.util.Arrays;
import java.util.List;

/**
 * The items the server sent for the player's inventory that Boar may not have applied yet, which Boar resends to the client in place of its older copies.
 * Only used on the client's event loop.
 */
final class InventoryTracker implements InventoryAccessor {
    // The slots of the player's inventory; slots 0 to 8 are the hotbar.
    private static final int SIZE = 36;

    private final Session session;
    // The server's last item for each slot, until Boar's copy has its stack network ID; null when there is none.
    private final ItemData[] serverItems = new ItemData[SIZE];
    // Boar makes the player after its accessors.
    BoarPlayer player;

    InventoryTracker(Session session) {
        this.session = session;
    }

    void observe(BedrockPacket packet) {
        switch (packet) {
            case InventoryContentPacket content when content.getContainerId() == ContainerId.INVENTORY -> {
                List<ItemData> items = content.getContents();
                for (int slot = 0; slot < Math.min(items.size(), SIZE); slot++) {
                    this.serverItems[slot] = items.get(slot);
                }
            }
            case InventorySlotPacket slot when slot.getContainerId() == ContainerId.INVENTORY && slot.getSlot() >= 0 && slot.getSlot() < SIZE -> {
                this.serverItems[slot.getSlot()] = slot.getItem();
            }
            // A server can change slots with only its response to the client's request, which Boar's copy follows.
            case ItemStackResponsePacket ignored -> this.clear();
            default -> {
            }
        }
    }

    void clear() {
        Arrays.fill(this.serverItems, null);
    }

    @Override
    public int heldItemSlot() {
        return this.player.compensatedInventory.heldItemSlot;
    }

    // Boar applies the server's item once the client acknowledges it, so until then its copy is older than the client's.
    // The packet skips Boar's listeners, which see the server's own packet for this item.
    @Override
    public void updateSlot(int slot) {
        ItemData boarItem = this.player.compensatedInventory.inventoryContainer.get(slot).getData();
        ItemData serverItem = this.serverItems[slot];
        // Once Boar has the server's stack, its copy also counts what the client has done with it since, such as the blocks placed. A server can change an item's damage or NBT and keep its net ID, so those must match too.
        if (serverItem != null && serverItem.getNetId() == boarItem.getNetId() && serverItem.equals(boarItem, false, true, true)) {
            this.serverItems[slot] = null;
            serverItem = null;
        }
        InventorySlotPacket packet = new InventorySlotPacket();
        packet.setContainerId(ContainerId.INVENTORY);
        packet.setSlot(slot);
        packet.setItem(serverItem != null ? serverItem : boarItem);
        // Unflushed, like the blocks Boar restores.
        this.session.sendToClient(packet, false);
    }
}
