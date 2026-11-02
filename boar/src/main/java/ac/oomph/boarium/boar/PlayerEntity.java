package ac.oomph.boarium.boar;

import ac.boar.anticheat.compensated.CompensatedInventory;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityDefinitions;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;

/**
 * The player's own entity, as Boar sees it.
 */
final class PlayerEntity implements Entity {
    // Boar makes the player after its entity.
    BoarPlayer player;

    @Override
    public EntityDefinition definition() {
        return EntityDefinitions.PLAYER.find().orElseThrow();
    }

    @Override
    public Entity vehicle() {
        EntityDefinition vehicle = ((EntityTracker) this.player.getEntityAccessor()).vehicle();
        return vehicle != null ? new Vehicle(vehicle) : null;
    }

    @Override
    public Vector3i bedPosition() {
        return this.player.bedPosition;
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

    // Boar asks for this when the server says the player uses an item but the client never started that use. It stops the use on the server with the transaction a real client sends when it lets go of an item.
    @Override
    public void releaseItem() {
        // Boar calls this even with mitigations off, and a release can fire a bow on the server.
        if (this.player.disableMitigations()) {
            return;
        }
        CompensatedInventory inventory = this.player.compensatedInventory;
        InventoryTransactionPacket release = new InventoryTransactionPacket();
        release.setTransactionType(InventoryTransactionType.ITEM_RELEASE);
        // Release, not consume
        release.setActionType(0);
        release.setHotbarSlot(inventory.heldItemSlot);
        release.setItemInHand(inventory.inventoryContainer.getHeldItemData());
        // The head position from the client's input before the one Boar is handling
        release.setHeadPosition(Vector3f.from(this.player.unvalidatedPosition.x, this.player.unvalidatedNativeOriginY, this.player.unvalidatedPosition.z));
        this.player.injectClientPacket(release);
    }

    private record Vehicle(EntityDefinition definition) implements Entity {
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
            return this.definition.width();
        }

        @Override
        public float bbHeight() {
            return this.definition.height();
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
