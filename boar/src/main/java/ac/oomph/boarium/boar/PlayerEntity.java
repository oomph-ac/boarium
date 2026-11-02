package ac.oomph.boarium.boar;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityDefinitions;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;

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

    @Override
    public void releaseItem() {
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
