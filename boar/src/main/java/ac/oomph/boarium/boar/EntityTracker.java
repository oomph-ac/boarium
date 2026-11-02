package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.player.accessor.EntityAccessor;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The entities a player's server has added and not removed, with their definitions, and the one the player rides.
 * Only used on the client's event loop.
 */
final class EntityTracker implements EntityAccessor {
    private static final Logger LOG = LoggerFactory.getLogger(EntityTracker.class);
    // A bound on how many entities a hostile server can make this tracker keep.
    private static final int MAX_ENTITIES = 8192;

    private final String name;
    private final Map<Long, Tracked> byRuntimeId = new HashMap<>();
    private final Map<Long, Long> runtimeIdByUniqueId = new HashMap<>();
    // The unique ID of the entity the player rides, or null.
    private Long vehicle;
    private boolean full;

    EntityTracker(String name) {
        this.name = name;
    }

    void observe(BedrockPacket packet, long playerUniqueId) {
        switch (packet) {
            case AddEntityPacket add -> {
                this.add(add.getRuntimeEntityId(), add.getUniqueEntityId(), add.getIdentifier());
                for (EntityLinkData link : add.getEntityLinks()) {
                    this.link(link, playerUniqueId);
                }
            }
            case AddPlayerPacket add -> this.add(add.getRuntimeEntityId(), add.getUniqueEntityId(), "minecraft:player");
            case RemoveEntityPacket remove -> this.remove(remove.getUniqueEntityId());
            case SetEntityLinkPacket link when link.getEntityLink() != null -> this.link(link.getEntityLink(), playerUniqueId);
            default -> {
            }
        }
    }

    void clear() {
        this.byRuntimeId.clear();
        this.runtimeIdByUniqueId.clear();
        this.vehicle = null;
        this.full = false;
    }

    // The definition of the entity the player rides, or null when it rides none or one with no definition.
    EntityDefinition vehicle() {
        return this.vehicle != null ? this.definitionByUniqueId(this.vehicle) : null;
    }

    private void add(long runtimeId, long uniqueId, String identifier) {
        if (!this.byRuntimeId.containsKey(runtimeId) && this.byRuntimeId.size() >= MAX_ENTITIES) {
            if (!this.full) {
                this.full = true;
                LOG.error("{}'s server has more than {} entities, so Boar will not see the others", this.name, MAX_ENTITIES);
            }
            return;
        }
        // Boar takes an actor for a static seat only when it has no definition. The entry stays, so a definition from an older actor under this runtime ID cannot remain.
        EntityDefinition definition = Boar.getConfig().staticSeatIdentifiers().contains(identifier) ? null : BedrockEntities.definition(identifier);
        Tracked previous = this.byRuntimeId.put(runtimeId, new Tracked(uniqueId, definition));
        if (previous != null && previous.uniqueId() != uniqueId) {
            this.runtimeIdByUniqueId.remove(previous.uniqueId(), runtimeId);
        }
        Long previousRuntimeId = this.runtimeIdByUniqueId.put(uniqueId, runtimeId);
        if (previousRuntimeId != null && previousRuntimeId != runtimeId) {
            this.byRuntimeId.remove(previousRuntimeId);
        }
    }

    private void remove(long uniqueId) {
        Long runtimeId = this.runtimeIdByUniqueId.remove(uniqueId);
        if (runtimeId != null) {
            this.byRuntimeId.remove(runtimeId);
        }
        if (this.vehicle != null && this.vehicle == uniqueId) {
            this.vehicle = null;
        }
    }

    // Bedrock entity links name both entities by unique ID: the vehicle in from, the rider in to.
    private void link(EntityLinkData link, long playerUniqueId) {
        if (link.getTo() != playerUniqueId) {
            return;
        }
        if (link.getType() != EntityLinkData.Type.REMOVE) {
            this.vehicle = link.getFrom();
        } else if (this.vehicle != null && this.vehicle == link.getFrom()) {
            this.vehicle = null;
        }
    }

    private EntityDefinition definitionByUniqueId(long uniqueId) {
        Long runtimeId = this.runtimeIdByUniqueId.get(uniqueId);
        return runtimeId != null ? this.definitionByRuntimeId(runtimeId) : null;
    }

    // Boar does not ask for this.
    @Override
    public Entity entityByRuntimeId(long runtimeId) {
        return null;
    }

    @Override
    public EntityDefinition definitionByRuntimeId(long runtimeId) {
        Tracked tracked = this.byRuntimeId.get(runtimeId);
        return tracked != null ? tracked.definition() : null;
    }

    private record Tracked(long uniqueId, EntityDefinition definition) {
    }
}
