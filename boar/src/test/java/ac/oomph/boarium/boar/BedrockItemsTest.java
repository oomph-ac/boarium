package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.util.Reference;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.boar.mappings.item.Items;
import ac.oomph.boarium.boar.blocks.BlockTable;
import ac.oomph.boarium.boar.blocks.TableBlock;
import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockItemsTest {
    // Runtime IDs of Bedrock 1.26.30, from bedrock/runtime_item_states.26_30.json in Geyser 2.11.3-b1248's jar.
    private static final ItemDefinition STONE = new SimpleItemDefinition("minecraft:stone", 1, false);
    private static final ItemDefinition BOW = new SimpleItemDefinition("minecraft:bow", 331, false);
    private static final ItemDefinition BUCKET = new SimpleItemDefinition("minecraft:bucket", 392, false);
    private static final ItemDefinition WATER_BUCKET = new SimpleItemDefinition("minecraft:water_bucket", 394, false);

    // Boar fills its item references when it starts.
    @BeforeAll
    static void startBoar() throws Exception {
        TestBoar.start();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fillsEveryItemReferenceWithTheItemOfItsName() throws Exception {
        int references = 0;
        for (Field field : Items.class.getFields()) {
            if (field.getType() == Reference.class) {
                Reference.Deferred<Item> reference = (Reference.Deferred<Item>) field.get(null);
                assertEquals(reference.key(), reference.find().orElseThrow(() -> new AssertionError(reference.key() + " is empty")).toString());
                references++;
            }
        }
        assertEquals(references, BedrockItems.REFERENCES.size());
        assertTrue(BedrockItems.REFERENCES.contains("minecraft:elytra"));
    }

    @Test
    void listsTheBundlesCandlesAndHeads() {
        ItemMappings mappings = ItemMappings.get();
        List<String> colors = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
        assertNames(mappings.getBundleItems(), "minecraft:bundle", colors.stream().map(color -> "minecraft:" + color + "_bundle"));
        assertNames(mappings.getCandleItems(), "minecraft:candle", colors.stream().map(color -> "minecraft:" + color + "_candle"));
        assertNames(mappings.getHeadItems(), "minecraft:skeleton_skull", Stream.of("minecraft:wither_skeleton_skull", "minecraft:zombie_head", "minecraft:player_head", "minecraft:creeper_head", "minecraft:dragon_head", "minecraft:piglin_head"));
    }

    @Test
    void readsTheItemOfAStackByName() {
        BoarItemStack bow = BoarItemStack.of(null, stack(BOW, 1));
        assertTrue(bow.is(Items.BOW));
        assertSame(Items.BOW.find().orElseThrow(), bow.item());
        assertFalse(bow.isEmpty());
        // The stack of a known item is shared.
        assertSame(bow, BoarItemStack.of(null, stack(BOW, 64)));

        BoarItemStack stone = BoarItemStack.of(null, stack(STONE, 1));
        assertEquals("minecraft:stone", stone.item().toString());
        assertFalse(stone.is(Items.BOW));
    }

    // Expected blocks come from mappings/items.json in the same Geyser jar: the Java item with the Bedrock name, and the block of its firstBlockRuntimeId.
    @Test
    void mapsEachBlockItemToTheJavaBlockItPlaces() {
        assertPlaces("minecraft:stone", "minecraft:stone", 1);
        assertPlaces("minecraft:oak_planks", "minecraft:oak_planks", 15);
        assertPlaces("minecraft:wooden_door", "minecraft:oak_door", 5655);
        assertPlaces("minecraft:oak_sign", "minecraft:oak_sign", 5335);
        // The 16 Java beds and the 16 Java banners share a Bedrock name; the one with data value 0 wins.
        assertPlaces("minecraft:bed", "minecraft:white_bed", 1931);
        assertPlaces("minecraft:banner", "minecraft:black_banner", 13167);
        assertPlaces("minecraft:powder_snow_bucket", "minecraft:powder_snow", 27162);
        assertPlaces("minecraft:wheat_seeds", "minecraft:wheat", 5311);

        assertNotABlock("minecraft:bow");
        // Geyser's name for test_block and test_instance_block
        assertNotABlock("minecraft:unknown");
        // Bedrock only
        assertNotABlock("minecraft:border_block");
        assertEquals(1022, BlockMappings.get().getItemToBlock().size());
    }

    @Test
    void readsAServersOwnItemsUpToTheSessionsLimit() {
        BoariumPlayerManager.BoariumNetworkSession session = session(List.of());
        BoarItemStack wand = BoarItemStack.of(session, stack(new SimpleItemDefinition("cubecraft:wand", 2000, true), 1));
        assertFalse(wand.isEmpty());
        assertEquals("cubecraft:wand", wand.item().toString());
        assertFalse(wand.item().isBlock());
        assertSame(wand.item(), BoarItemStack.of(session, stack(new SimpleItemDefinition("cubecraft:wand", 2000, true), 1)).item());
        // Another session has its own item of the name.
        assertFalse(wand.item().is(BoarItemStack.of(session(List.of()), stack(new SimpleItemDefinition("cubecraft:wand", 2000, true), 1)).item()));

        for (int i = 1; i < BedrockItems.SessionItems.OWN_LIMIT; i++) {
            BoarItemStack.of(session, stack(new SimpleItemDefinition("cubecraft:item_" + i, 3000 + i, true), 1));
        }
        BoarItemStack over = BoarItemStack.of(session, stack(new SimpleItemDefinition("cubecraft:one_too_many", 9000, true), 1));
        assertSame(BedrockItems.UNKNOWN, over.item());
        assertFalse(over.isEmpty());
        assertSame(wand.item(), BoarItemStack.of(session, stack(new SimpleItemDefinition("cubecraft:wand", 2000, true), 1)).item());
    }

    @Test
    void readsAirAndNoItemsAsEmpty() {
        assertTrue(BoarItemStack.of(null, ItemData.AIR).isEmpty());
        // A server's own air, as in its item list
        assertTrue(BoarItemStack.of(null, stack(new SimpleItemDefinition("minecraft:air", -158, false), 1)).isEmpty());
        assertTrue(BoarItemStack.of(null, stack(BOW, 0)).isEmpty());
        assertFalse(BoarItemStack.of(null, ItemData.AIR).is(Items.BOW));
    }

    @Test
    void givesABucketTheServersRuntimeId() {
        BoariumPlayerManager.BoariumNetworkSession session = session(List.of(STONE, BUCKET, WATER_BUCKET));
        BoarItemStack water = BoarItemStack.of(session, Items.WATER_BUCKET, 1);
        assertTrue(water.is(Items.WATER_BUCKET));
        ItemData data = water.toItemData(session);
        assertSame(WATER_BUCKET, data.getDefinition());
        assertEquals(1, data.getCount());
        assertEquals(0, data.getDamage());
        assertEquals(0, data.getBlockDefinition().getRuntimeId());
        // Boar reads its own bucket again as a slot of the player's inventory.
        assertTrue(BoarItemStack.of(session, data).is(Items.WATER_BUCKET));

        // A server without lava buckets
        assertSame(ItemData.AIR, BoarItemStack.of(session, Items.LAVA_BUCKET, 1).toItemData(session));
    }

    @Test
    void findsTheItemReferencesAServerLacks() {
        List<String> missing = BedrockItems.missingReferences(Definitions.items(List.of(STONE, BOW, BUCKET, WATER_BUCKET)));
        assertTrue(missing.contains("minecraft:elytra"));
        assertFalse(missing.contains("minecraft:bow"));
        assertFalse(missing.contains("minecraft:water_bucket"));
        assertEquals(BedrockItems.REFERENCES.size() - 3, missing.size());
    }

    private static BoariumPlayerManager.BoariumNetworkSession session(List<ItemDefinition> items) {
        return new BoariumPlayerManager.BoariumNetworkSession(null, false, new BedrockItems.SessionItems(Definitions.items(items)));
    }

    private static Item item(String name) {
        return BoarItemStack.of(null, stack(new SimpleItemDefinition(name, 0, false), 1)).item();
    }

    private static void assertPlaces(String name, String block, int firstState) {
        Item item = item(name);
        assertTrue(item.isBlock(), name);
        Block placed = BlockMappings.get().getItemToBlock().get(item);
        assertEquals(block, placed.toString());
        assertEquals(firstState, BlockTable.get().firstState(((TableBlock) placed).index()));
    }

    private static void assertNotABlock(String name) {
        Item item = item(name);
        assertFalse(item.isBlock(), name);
        assertNull(BlockMappings.get().getItemToBlock().get(item));
    }

    private static ItemData stack(ItemDefinition definition, int count) {
        return ItemData.builder().definition(definition).count(count).build();
    }

    private static void assertNames(List<Item> items, String first, Stream<String> others) {
        List<String> expected = Stream.concat(Stream.of(first), others).sorted().toList();
        assertEquals(expected, items.stream().map(Item::toString).sorted().toList());
    }
}
