package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.Items;
import ac.oomph.boarium.boar.blocks.BedrockBlock;
import ac.oomph.boarium.boar.blocks.BedrockBlockTable;
import ac.oomph.boarium.protocol.BedrockVersions;
import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The items of each protocol. Expected values were read with jq from the runtime_item_states.<version>.json files in Geyser 2.11.0-b1205's core jar (26_0 to 26_20), 2.11.3-b1248's (26_30) and 2.12.1-b1284's (26_40 to 26_60).
class BedrockItemVersionsTest {
    /**
     * @param added a block item that is new in this version, or one in every version when none is
     * @param addedBlock the block that added places
     * @param notABlock an item that places no block, new in this version, or one in every version when none is
     * @param removed a name of the version before that this one does not have, or null
     */
    record Version(int protocol, int size, String added, String addedBlock, String notABlock, String removed) {
        @Override
        public String toString() {
            return String.valueOf(this.protocol);
        }
    }

    static List<Version> versions() {
        return List.of(
                new Version(924, 1905, "minecraft:stone", "minecraft:stone", "minecraft:bow", null),
                new Version(944, 1906, "minecraft:golden_dandelion", "minecraft:golden_dandelion", "minecraft:bow", null),
                new Version(975, 1941, "minecraft:chiseled_cinnabar", "minecraft:chiseled_cinnabar", "minecraft:sulfur_cube_bucket", null),
                new Version(1001, 1933, "minecraft:sulfur_spike", "minecraft:sulfur_spike", "minecraft:music_disc_bounce", "minecraft:item.acacia_door"),
                new Version(2168, 1976, "minecraft:poplar_sign", "minecraft:poplar_standing_sign", "minecraft:black_cushion", null),
                new Version(2193, 2076, "minecraft:black_wool_stairs", "minecraft:black_wool_stairs", "minecraft:photo_item", null),
                new Version(2225, 2080, "minecraft:icicle", "minecraft:icicle", "minecraft:frostbite_spawn_egg", null));
    }

    // Boar fills its item references when it starts.
    @BeforeAll
    static void startBoar() throws Exception {
        TestBoar.start();
    }

    @Test
    void everyProtocolHasItems() {
        for (BedrockVersions.Version version : BedrockVersions.SUPPORTED) {
            assertNotNull(BedrockItems.forProtocol(version.codec().getProtocolVersion()), version.names().toString());
        }
        assertSame(BedrockItems.forProtocol(2168), BedrockItems.forProtocol(2169));
        assertSame(BedrockItems.forProtocol(1001), BedrockItems.forProtocol(1001));
        assertNull(BedrockItems.forProtocol(1000));
    }

    @ParameterizedTest
    @MethodSource("versions")
    void mapsTheNamesOfItsVersion(Version version) {
        BedrockItems.Version items = BedrockItems.forProtocol(version.protocol());
        assertEquals(version.size(), items.names.size());
        assertEquals(version.size(), items.items.size());
        assertEquals(version.addedBlock(), items.blocks.get(items.items.get(version.added())));
        assertTrue(items.items.get(version.added()).isBlock());
        assertFalse(items.items.get(version.notABlock()).isBlock());
        assertNull(items.blocks.get(items.items.get(version.notABlock())));
        if (version.removed() != null) {
            assertNull(items.items.get(version.removed()));
        }
        assertEquals(List.of(), items.missingReferences());
    }

    // Each block item's block is in its own version's table, and the shared item says it is a block item in every version that has it.
    @ParameterizedTest
    @MethodSource("versions")
    void blockItemsPlaceBlocksOfTheirOwnTable(Version version) {
        BedrockItems.Version items = BedrockItems.forProtocol(version.protocol());
        BedrockBlockTable table = BedrockBlockTable.forProtocol(version.protocol());
        for (BedrockItems.BedrockItem item : items.items.values()) {
            String block = items.blocks.get(item);
            assertEquals(block != null, item.isBlock(), item.toString());
            assertTrue(block == null || table.firstIndex(block) >= 0, item.toString());
        }
        // A sulfur spike is a block from 26.30, and the 26.20 table has none.
        assertEquals(version.protocol() >= 1001, table.firstIndex("minecraft:sulfur_spike") >= 0);
    }

    // Boar's item-to-block map gives each block item the shared block of the name it places in its version, also for the items that 1.26.30 does not have.
    @ParameterizedTest
    @MethodSource("versions")
    void blockItemsPlaceTheirSharedBlock(Version version) {
        BedrockItems.Version items = BedrockItems.forProtocol(version.protocol());
        Map<Item, Block> itemToBlock = BlockMappings.get().getItemToBlock();
        items.blocks.forEach((item, block) -> assertSame(BedrockBlock.named(block), itemToBlock.get(item), item.toString()));
    }

    // Boar's references are filled once, so every version that has a name gives the same item.
    @Test
    void versionsShareTheItemOfAName() {
        Item bow = Items.BOW.find().orElseThrow();
        for (Version version : versions()) {
            assertSame(bow, BedrockItems.forProtocol(version.protocol()).items.get("minecraft:bow"), version.toString());
        }
        assertSame(BedrockItems.forProtocol(2168).items.get("minecraft:poplar_sign"), BedrockItems.forProtocol(2225).items.get("minecraft:poplar_sign"));
        assertSame(BedrockItems.forProtocol(924).items.get("minecraft:item.acacia_door"), BedrockItems.forProtocol(975).items.get("minecraft:item.acacia_door"));
    }

    // The 1.26.30 items are the ones Boar reads, with the blocks of Boar's item-to-block map.
    @Test
    void version1_26_30IsBoarsItems() {
        BedrockItems.Version items = BedrockItems.forProtocol(1001);
        BoariumPlayerManager.BoariumNetworkSession session = new BoariumPlayerManager.BoariumNetworkSession(null, 0, new BedrockItems.SessionItems(items, Definitions.items(List.of()), null));
        for (String name : items.names) {
            assertSame(BoarItemStack.of(session, ItemData.builder().definition(new SimpleItemDefinition(name, 0, false)).count(1).build()).item(), items.items.get(name), name);
        }
        Map<Item, Block> itemToBlock = BlockMappings.get().getItemToBlock();
        assertEquals(itemToBlock.size(), items.blocks.size());
        items.blocks.forEach((item, block) -> assertSame(BedrockBlock.named(block), itemToBlock.get(item), item.toString()));
    }
}
