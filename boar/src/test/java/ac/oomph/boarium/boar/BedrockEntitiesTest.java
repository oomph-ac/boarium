package ac.oomph.boarium.boar;

import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Sizes and offsets from Geyser 2.11.3-b1248's jar, read by loading its VanillaEntities: width(), height() and offset() of the Java entity Geyser sends as each Bedrock identifier.
class BedrockEntitiesTest {
    @Test
    void sizesEntitiesAsGeyserDoes() {
        assertDefinition("minecraft:player", 0.6F, 1.8F, 1.62001F);
        assertDefinition("minecraft:boat", 1.6F, 0.6F, 0.375F);
        assertDefinition("minecraft:chest_boat", 1.6F, 0.6F, 0.375F);
        assertDefinition("minecraft:horse", 1.3965F, 1.6F, 0);
        assertDefinition("minecraft:skeleton_horse", 1.3965F, 1.6F, 0);
        assertDefinition("minecraft:camel", 1.7F, 2.375F, 0);
        // Geyser's minecraft:trader_llama, which it sends as a llama
        assertDefinition("minecraft:trader_llama", 0.9F, 1.87F, 0);
        assertDefinition("minecraft:pig", 0.9F, 0.9F, 0);
        assertDefinition("minecraft:strider", 0.9F, 1.7F, 0);
        assertDefinition("minecraft:happy_ghast", 4.0F, 4.0F, 0);
        assertDefinition("minecraft:hopper_minecart", 0.98F, 0.7F, 0.35F);
        assertDefinition("minecraft:shulker", 1.0F, 1.0F, 0);
    }

    @Test
    void givesAnyOtherEntityAPlayersSize() {
        // In Geyser as the zombified piglin, 0.6 by 1.95, but not in the table
        assertDefinition("minecraft:zombie_pigman", 0.6F, 1.8F, 0);
        assertDefinition("cubecraft:pipe_spirit", 0.6F, 1.8F, 0);
        EntityDefinition other = BedrockEntities.definition("cubecraft:pipe_spirit");
        assertFalse(other.type().is(BedrockEntities.definition("minecraft:player").type()));
        assertTrue(other.type().is(BedrockEntities.definition("cubecraft:pipe_spirit").type()));
    }

    @Test
    void leavesEmptyTheReferencesBedrockHasNoEntityFor() {
        ReferencePopulator<EntityType> types = new ReferencePopulator<>("entity_type");
        ReferencePopulator<EntityDefinition> definitions = new ReferencePopulator<>("entity_definition");
        Reference<EntityType> playerType = types.defer("minecraft:player");
        Reference<EntityType> birchBoatType = types.defer("minecraft:birch_boat");
        Reference<EntityDefinition> player = definitions.defer("minecraft:player");
        Reference<EntityDefinition> birchBoat = definitions.defer("minecraft:birch_boat");

        BedrockEntities.populate(types, definitions);

        assertEquals("minecraft:player", player.find().orElseThrow().identifier());
        assertEquals(1.62001F, player.find().orElseThrow().offset());
        assertTrue(player.find().orElseThrow().type().is(playerType));
        assertTrue(BedrockEntities.definition("minecraft:player").type().is(playerType));
        assertTrue(birchBoatType.find().isEmpty());
        assertTrue(birchBoat.find().isEmpty());
        assertFalse(BedrockEntities.definition("minecraft:boat").type().is(birchBoatType));
    }

    private static void assertDefinition(String identifier, float width, float height, float offset) {
        EntityDefinition definition = BedrockEntities.definition(identifier);
        assertEquals(identifier, definition.identifier());
        assertEquals(width, definition.width(), identifier);
        assertEquals(height, definition.height(), identifier);
        assertEquals(offset, definition.offset(), identifier);
    }
}
