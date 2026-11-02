package ac.oomph.boarium.boar;

import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entity definitions by Bedrock identifier, as in AddEntityPacket.
 * Boar sizes an entity from its WIDTH, HEIGHT and SCALE metadata when the server sends them, so the sizes here are only a fallback, and only the player's offset is read.
 * The table holds the player, the boats, minecarts and mounts, and the shulker, with the sizes and offsets of Geyser 2.11.3-b1248's VanillaEntities; any other identifier gets a player's size.
 */
final class BedrockEntities {
    private static final Logger LOG = LoggerFactory.getLogger(BedrockEntities.class);

    private static final Map<String, Definition> TABLE = Map.ofEntries(
            entry("minecraft:player", 0.6F, 1.8F, 1.62001F),
            entry("minecraft:boat", 1.6F, 0.6F, 0.375F),
            entry("minecraft:chest_boat", 1.6F, 0.6F, 0.375F),
            entry("minecraft:horse", 1.3965F, 1.6F, 0),
            entry("minecraft:donkey", 1.3965F, 1.6F, 0),
            entry("minecraft:mule", 1.3965F, 1.6F, 0),
            entry("minecraft:skeleton_horse", 1.3965F, 1.6F, 0),
            entry("minecraft:zombie_horse", 1.3965F, 1.6F, 0),
            entry("minecraft:camel", 1.7F, 2.375F, 0),
            entry("minecraft:camel_husk", 1.7F, 2.375F, 0),
            entry("minecraft:llama", 0.9F, 1.87F, 0),
            // Geyser sends a Java trader llama as a Bedrock llama, so its size is in Geyser's table under the Java name only.
            entry("minecraft:trader_llama", 0.9F, 1.87F, 0),
            entry("minecraft:pig", 0.9F, 0.9F, 0),
            entry("minecraft:strider", 0.9F, 1.7F, 0),
            entry("minecraft:happy_ghast", 4.0F, 4.0F, 0),
            entry("minecraft:minecart", 0.98F, 0.7F, 0.35F),
            entry("minecraft:chest_minecart", 0.98F, 0.7F, 0.35F),
            entry("minecraft:hopper_minecart", 0.98F, 0.7F, 0.35F),
            entry("minecraft:tnt_minecart", 0.98F, 0.7F, 0.35F),
            entry("minecraft:command_block_minecart", 0.98F, 0.7F, 0.35F),
            entry("minecraft:shulker", 1.0F, 1.0F, 0));

    private BedrockEntities() {
    }

    private static Map.Entry<String, Definition> entry(String identifier, float width, float height, float offset) {
        return Map.entry(identifier, new Definition(identifier, new Type(identifier), width, height, offset));
    }

    static EntityDefinition definition(String identifier) {
        Definition definition = TABLE.get(identifier);
        return definition != null ? definition : new Definition(identifier, new Type(identifier), 0.6F, 1.8F, 0);
    }

    // Boar names its references by Java identifier; the ones Bedrock has no entity of, such as minecraft:birch_boat (a Bedrock minecraft:boat of any wood), stay empty.
    static void populate(ReferencePopulator<EntityType> types, ReferencePopulator<EntityDefinition> definitions) {
        Set<String> unmatched = new TreeSet<>();
        types.populate((key, type) -> {
            Definition definition = TABLE.get(key);
            if (definition != null) {
                type.populate(definition.type());
            } else {
                unmatched.add(key);
            }
        });
        definitions.populate((key, reference) -> {
            Definition definition = TABLE.get(key);
            if (definition != null) {
                reference.populate(definition);
            } else {
                unmatched.add(key);
            }
        });
        types.ensurePopulated(false);
        definitions.ensurePopulated(false);
        if (!unmatched.isEmpty()) {
            LOG.info("Boar's entity references with no Bedrock entity, left empty: {}", unmatched);
        }
    }

    private record Type(String identifier) implements EntityType {
        @Override
        public boolean is(EntityType value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityType> reference) {
            return reference.find().map(this::is).orElse(false);
        }
    }

    private record Definition(String identifier, EntityType type, float width, float height, float offset) implements EntityDefinition {
        @Override
        public boolean is(EntityDefinition value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityDefinition> reference) {
            return reference.find().map(this::is).orElse(false);
        }
    }
}
