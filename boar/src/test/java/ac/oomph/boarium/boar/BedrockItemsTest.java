package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.boar.mappings.item.Items;
import ac.oomph.boarium.boar.blocks.BedrockBlock;
import ac.oomph.boarium.boar.blocks.BedrockBlockTable;
import ac.oomph.boarium.boar.blocks.BedrockBlockUnion;
import ac.oomph.boarium.boar.blocks.BedrockPalette;
import ac.oomph.boarium.boar.blocks.BedrockState;
import ac.oomph.boarium.boar.blocks.GeyserFixtures;
import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        BoarItemStack bow = BoarItemStack.of(session(List.of()), stack(BOW, 1));
        assertTrue(bow.is(Items.BOW));
        assertSame(Items.BOW.find().orElseThrow(), bow.item());
        assertFalse(bow.isEmpty());
        // The stack of a known item is shared.
        assertSame(bow, BoarItemStack.of(session(List.of()), stack(BOW, 64)));

        BoarItemStack stone = BoarItemStack.of(session(List.of()), stack(STONE, 1));
        assertEquals("minecraft:stone", stone.item().toString());
        assertFalse(stone.is(Items.BOW));
    }

    @Test
    void mapsEachBlockItemToTheBedrockBlockItPlaces() {
        assertPlaces("minecraft:stone", "minecraft:stone");
        assertPlaces("minecraft:wooden_door", "minecraft:wooden_door");
        assertPlaces("minecraft:bed", "minecraft:bed");
        assertPlaces("minecraft:oak_sign", "minecraft:standing_sign");
        assertPlaces("minecraft:dark_oak_sign", "minecraft:darkoak_standing_sign");
        assertPlaces("minecraft:cherry_sign", "minecraft:cherry_standing_sign");
        assertPlaces("minecraft:cherry_hanging_sign", "minecraft:cherry_hanging_sign");
        assertPlaces("minecraft:banner", "minecraft:standing_banner");
        assertPlaces("minecraft:powder_snow_bucket", "minecraft:powder_snow");
        assertPlaces("minecraft:wheat_seeds", "minecraft:wheat");
        assertPlaces("minecraft:repeater", "minecraft:unpowered_repeater");
        assertPlaces("minecraft:light_block", "minecraft:light_block_15");
        // A furnace item places an unlit furnace, Bedrock Dedicated Server's default.
        assertPlaces("minecraft:furnace", "minecraft:furnace");
        BoarBlockState furnace = BlockMappings.get().getItemToBlock().get(item("minecraft:furnace")).defaultBlockState(Vector3i.ZERO, 0);
        assertEquals("minecraft:furnace", BedrockBlockUnion.get().table().name(furnace.intermediaryId()));
        // Bedrock only
        assertPlaces("minecraft:border_block", "minecraft:border_block");
        assertPlaces("minecraft:frame", "minecraft:frame");

        assertNotABlock("minecraft:bow");
        assertNotABlock("minecraft:air");
        // The food and the crop item, not the crop blocks of the same names
        assertNotABlock("minecraft:beetroot");
        assertNotABlock("minecraft:wheat");
        assertNotABlock("minecraft:item.wheat");
        // A double slab item places a full block, which the slab's default state is not.
        assertNotABlock("minecraft:oak_double_slab");
        assertNotABlock("minecraft:waxed_double_cut_copper_slab");
    }

    // Where an item is named after a block, the state Boar places has the boxes of that block's default state.
    @Test
    void placesTheBoxesOfTheBlockAnItemIsNamedAfter() {
        BedrockBlockTable table = BedrockBlockTable.get();
        int compared = 0;
        for (Map.Entry<Item, Block> entry : BlockMappings.get().getItemToBlock().entrySet()) {
            int own = table.defaultIndex(entry.getKey().toString());
            if (own >= 0) {
                List<Box> placed = entry.getValue().defaultBlockState(Vector3i.ZERO, 0).getCollisionBoxes();
                assertEquals(BedrockState.FACTORY.create(BedrockBlockUnion.get().toShared(table.protocol(), own), Vector3i.ZERO, 0).getCollisionBoxes(), placed, entry.getKey().toString());
                compared++;
            }
        }
        assertEquals(1283, compared);
    }

    // The block items Geyser maps to a Java block each place the Bedrock block of that Java block's default state (items.26_30.txt). The other block items are Bedrock blocks Java does not have, or other names of a block, such as minecraft:lit_furnace.
    @Test
    void placesTheBlocksGeyserMapsTo() {
        Map<Item, Block> blocks = BlockMappings.get().getItemToBlock();
        Set<String> geyserItems = new TreeSet<>();
        for (String[] row : GeyserFixtures.items()) {
            String name = GeyserFixtures.name(row[0]);
            assertSame(BedrockBlock.named(GeyserFixtures.name(row[row.length - 1])), blocks.get(item(name)), name);
            geyserItems.add(name);
        }
        Set<String> others = new TreeSet<>();
        blocks.forEach((item, block) -> {
            if (!geyserItems.contains(item.toString())) {
                assertSame(BedrockBlock.named(item.toString()), block, item.toString());
                others.add(item.toString());
            }
        });
        assertEquals(1022, geyserItems.size());
        assertEquals(292, others.size());
        assertTrue(others.containsAll(List.of("minecraft:lit_furnace", "minecraft:wall_sign", "minecraft:hard_glass", "minecraft:frame", "minecraft:water")));
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

    // A 26.60 client's poplar sign is the item every version shares, and places the shared poplar standing sign; a 1.26.30 client has no poplar sign, so there it is the server's own item.
    @Test
    void readsTheItemsOfTheSessionsVersion() {
        ItemDefinition poplarSign = new SimpleItemDefinition("minecraft:poplar_sign", 2000, false);
        BoariumPlayerManager.BoariumNetworkSession v26_60 = session(2225, List.of(poplarSign), new StartGamePacket());
        Item sign = BoarItemStack.of(v26_60, stack(poplarSign, 1)).item();
        assertSame(BedrockItems.forProtocol(2225).items.get("minecraft:poplar_sign"), sign);
        assertSame(BedrockItems.forProtocol(2168).items.get("minecraft:poplar_sign"), sign);
        assertTrue(sign.isBlock());
        assertSame(BedrockBlock.named("minecraft:poplar_standing_sign"), BlockMappings.get().getItemToBlock().get(sign));

        Item own = BoarItemStack.of(session(List.of(poplarSign)), stack(poplarSign, 1)).item();
        assertNotSame(sign, own);
        assertNull(BlockMappings.get().getItemToBlock().get(own));
    }

    // The client's block item of a custom block has the block's name and version NONE, as Geyser and dragonfly send it, and a data-driven item places the block its minecraft:block_placer names.
    @Test
    void placesTheCustomBlocksOfAServersOwnItems() {
        StartGamePacket startGame = startGame(customBlock("cubecraft:item_marble"), customBlock("minecraft:item_marble"));
        NbtMap marblePlacer = NbtMap.builder().putString("block", "cubecraft:item_marble").putList("use_on", NbtType.COMPOUND).build();
        BoariumPlayerManager.BoariumNetworkSession session = session(List.of(
                new SimpleItemDefinition("cubecraft:item_marble", -600, ItemVersion.NONE, false, null),
                blockPlacer("cubecraft:chisel", 2101, marblePlacer),
                blockPlacer("cubecraft:picky_chisel", 2102, marblePlacer.toBuilder().putList("use_on", NbtType.STRING, "minecraft:stone").build()),
                blockPlacer("cubecraft:odd_chisel", 2103, marblePlacer.toBuilder().putString("use_on", "minecraft:stone").build()),
                blockPlacer("cubecraft:stone_chisel", 2104, NbtMap.builder().putString("block", "minecraft:stone").build()),
                blockPlacer("cubecraft:missing_chisel", 2105, NbtMap.builder().putString("block", "cubecraft:no_such_block").build()),
                new SimpleItemDefinition("cubecraft:no_such_block", -601, ItemVersion.NONE, false, null),
                new SimpleItemDefinition("minecraft:item_marble", -602, ItemVersion.NONE, false, null)), startGame);
        Block marble = placed(session, "cubecraft:item_marble");
        assertNotNull(marble);
        assertEquals("cubecraft:item_marble", marble.toString());
        assertTrue(marble.defaultBlockState(Vector3i.ZERO, 0).is(marble));
        assertTrue(marble.defaultBlockState(Vector3i.ZERO, 0).intermediaryId() >= BedrockBlockUnion.get().table().paletteSize());
        assertSame(marble, placed(session, "cubecraft:chisel"));
        // The client places these blocks, so Boar counts the items down, but it does not place them: only on the blocks use_on lists, a vanilla block, a custom block in the minecraft namespace.
        assertPlacesUnknownBlock(session, "cubecraft:picky_chisel");
        assertPlacesUnknownBlock(session, "cubecraft:odd_chisel");
        assertPlacesUnknownBlock(session, "cubecraft:stone_chisel");
        assertPlacesUnknownBlock(session, "minecraft:item_marble");
        // The client places no block that its palette lacks.
        assertPlacesNothing(session, "cubecraft:missing_chisel");
        assertPlacesNothing(session, "cubecraft:no_such_block");

        // Another session of the same server has its own items, which place the same block.
        BoariumPlayerManager.BoariumNetworkSession other = session(List.of(new SimpleItemDefinition("cubecraft:item_marble", -600, ItemVersion.NONE, false, null)), startGame);
        assertSame(marble, placed(other, "cubecraft:item_marble"));
        // The client makes a data-driven or legacy item of the block's name another item, and the block's item gets another name.
        assertPlacesNothing(session(List.of(blockPlacer("cubecraft:item_marble", 2106, NbtMap.EMPTY)), startGame), "cubecraft:item_marble");
        assertPlacesNothing(session(List.of(new SimpleItemDefinition("cubecraft:item_marble", -600, ItemVersion.DATA_DRIVEN, true, NbtMap.EMPTY)), startGame), "cubecraft:item_marble");
        assertPlacesNothing(session(List.of(new SimpleItemDefinition("cubecraft:item_marble", -600, false)), startGame), "cubecraft:item_marble");
        // Unless its minecraft:block_placer replaces the block's item
        NbtMap replacing = NbtMap.builder().putString("block", "cubecraft:item_marble").putBoolean("replace_block_item", true).build();
        assertSame(marble, placed(session(List.of(blockPlacer("cubecraft:item_marble", 2107, replacing)), startGame), "cubecraft:item_marble"));
    }

    // The client sets the states of a custom block's traits as it places it, which Boar does not, so Boar places the block only when those states do not change its collision.
    @Test
    void placesACustomBlockWithTraitsOnlyWhenTheyKeepItsCollision() {
        NbtMap cardinal = NbtMap.builder().putString("name", "minecraft:placement_direction").putCompound("enabled_states", NbtMap.builder().putByte("cardinal_direction", (byte) 1).build()).build();
        BlockPropertyData log = withTraits(customBlock("cubecraft:item_log"), cardinal);
        NbtMap open = NbtMap.builder().putString("condition", "q.block_state('minecraft:cardinal_direction') == 'east'")
                .putCompound("components", NbtMap.builder().putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build()).build()).build();
        BlockPropertyData gate = new BlockPropertyData("cubecraft:item_gate", withTraits(customBlock("cubecraft:item_gate"), cardinal).getProperties().toBuilder()
                .putList("permutations", NbtType.COMPOUND, open).putInt("molangVersion", 12).build());
        BoariumPlayerManager.BoariumNetworkSession session = session(List.of(
                new SimpleItemDefinition("cubecraft:item_log", -600, ItemVersion.NONE, false, null),
                new SimpleItemDefinition("cubecraft:item_gate", -601, ItemVersion.NONE, false, null),
                blockPlacer("cubecraft:gate_placer", 2108, NbtMap.builder().putString("block", "cubecraft:item_gate").build())), startGame(log, gate));
        assertNotNull(placed(session, "cubecraft:item_log"));
        assertPlacesUnknownBlock(session, "cubecraft:item_gate");
        assertPlacesUnknownBlock(session, "cubecraft:gate_placer");
    }

    @Test
    void placesNoCustomBlockWithAnItemPastTheSessionsLimit() {
        List<ItemDefinition> items = new ArrayList<>();
        for (int i = 0; i < BedrockItems.SessionItems.OWN_LIMIT; i++) {
            items.add(new SimpleItemDefinition("cubecraft:filler_" + i, 3000 + i, true));
        }
        items.add(new SimpleItemDefinition("cubecraft:item_marble", -600, ItemVersion.NONE, false, null));
        BoariumPlayerManager.BoariumNetworkSession session = session(items, startGame(customBlock("cubecraft:item_marble")));
        for (int i = 0; i < BedrockItems.SessionItems.OWN_LIMIT; i++) {
            placed(session, "cubecraft:filler_" + i);
        }
        assertPlacesNothing(session, "cubecraft:item_marble");
        assertSame(BedrockItems.UNKNOWN, BoarItemStack.of(session, stack(items.getLast(), 1)).item());
    }

    @Test
    void readsAirAndNoItemsAsEmpty() {
        assertTrue(BoarItemStack.of(session(List.of()), ItemData.AIR).isEmpty());
        // A server's own air, as in its item list
        assertTrue(BoarItemStack.of(session(List.of()), stack(new SimpleItemDefinition("minecraft:air", -158, false), 1)).isEmpty());
        assertTrue(BoarItemStack.of(session(List.of()), stack(BOW, 0)).isEmpty());
        assertFalse(BoarItemStack.of(session(List.of()), ItemData.AIR).is(Items.BOW));
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
        return session(items, new StartGamePacket());
    }

    // A session whose client was sent this StartGame
    private static BoariumPlayerManager.BoariumNetworkSession session(List<ItemDefinition> items, StartGamePacket startGame) {
        return session(1001, items, startGame);
    }

    // A session of a client on this protocol
    private static BoariumPlayerManager.BoariumNetworkSession session(int protocol, List<ItemDefinition> items, StartGamePacket startGame) {
        BedrockItems.SessionItems sessionItems = new BedrockItems.SessionItems(BedrockItems.forProtocol(protocol), Definitions.items(items), BedrockPalette.mappingInfo(BedrockBlockTable.forProtocol(protocol), startGame));
        return new BoariumPlayerManager.BoariumNetworkSession(null, 0, sessionItems);
    }

    private static StartGamePacket startGame(BlockPropertyData... blocks) {
        StartGamePacket startGame = new StartGamePacket();
        startGame.getBlockProperties().addAll(List.of(blocks));
        return startGame;
    }

    // A custom block of one state
    private static BlockPropertyData customBlock(String name) {
        return new BlockPropertyData(name, NbtMap.builder().putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 0).build()).build());
    }

    // A data-driven item with this minecraft:block_placer component, as Geyser sends one (CustomItemRegistryPopulator)
    private static ItemDefinition blockPlacer(String name, int runtimeId, NbtMap placer) {
        NbtMap components = NbtMap.builder().putCompound("components", NbtMap.builder().putCompound("minecraft:block_placer", placer).build()).build();
        return new SimpleItemDefinition(name, runtimeId, ItemVersion.DATA_DRIVEN, true, components);
    }

    private static BlockPropertyData withTraits(BlockPropertyData block, NbtMap... traits) {
        return new BlockPropertyData(block.getName(), block.getProperties().toBuilder().putList("traits", NbtType.COMPOUND, traits).build());
    }

    private static Item ownItem(BoariumPlayerManager.BoariumNetworkSession session, String name) {
        return BoarItemStack.of(session, stack(session.items().definitions.getDefinition(name), 1)).item();
    }

    // The block Boar places with a session's own item of this name, or null
    private static Block placed(BoariumPlayerManager.BoariumNetworkSession session, String name) {
        Item item = ownItem(session, name);
        Block block = BlockMappings.get().getItemToBlock().get(item);
        if (block != null) {
            assertTrue(item.isBlock(), name);
        }
        return block;
    }

    // A block item without a block, whose placement Boar counts but does not make
    private static void assertPlacesUnknownBlock(BoariumPlayerManager.BoariumNetworkSession session, String name) {
        assertTrue(ownItem(session, name).isBlock(), name);
        assertNull(placed(session, name), name);
    }

    private static void assertPlacesNothing(BoariumPlayerManager.BoariumNetworkSession session, String name) {
        assertFalse(ownItem(session, name).isBlock(), name);
        assertNull(placed(session, name), name);
    }

    private static Item item(String name) {
        return BoarItemStack.of(session(List.of()), stack(new SimpleItemDefinition(name, 0, false), 1)).item();
    }

    private static void assertPlaces(String name, String block) {
        Item item = item(name);
        assertTrue(item.isBlock(), name);
        assertSame(BedrockBlock.named(block), BlockMappings.get().getItemToBlock().get(item), name);
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
