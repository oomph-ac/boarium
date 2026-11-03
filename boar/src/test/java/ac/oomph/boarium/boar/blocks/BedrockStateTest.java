package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.block.VanillaBoarBlockState;
import ac.boar.anticheat.data.block.impl.BedBlockState;
import ac.boar.anticheat.data.block.impl.HoneyBlockState;
import ac.boar.anticheat.data.block.impl.SlimeBlockState;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.Dimension;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Direction;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.block.ChestType;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.boar.blocks.BedrockProperties.BedrockProperty;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected values come from the Bedrock palette and its BDS boxes (BedrockBlockTable), from facts about Geyser's Java to Bedrock mapping (GeyserFixtures), and from Boar's own BedrockCollision.
class BedrockStateTest {
    private static final String[] KEYS = {"age_1", "age_3", "bell_attachment", "bites", "chest_type", "door_hinge", "drag", "dripstone_thickness", "half", "hanging",
            "has_book", "horizontal_facing", "level", "lit", "open", "respawn_anchor_charges", "slab_type", "vault_state", "vertical_direction", "waterlogged"};
    private static final Vector3i POS = Vector3i.from(8, 64, 8);

    private static BedrockBlockTable table;
    private static Map<Integer, GeyserFixtures.JavaState> geyser;
    // By Boar's property key, then Bedrock block name
    private static final Map<String, Map<String, JavaValues>> JAVA_VALUES = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        TestBoar.start();
        table = BedrockBlockTable.get();
        geyser = GeyserFixtures.javaStates(table);
        for (String[] row : GeyserFixtures.properties()) {
            List<String> keys = row[1].equals("-") ? List.of() : List.of(row[1].split("\\+"));
            Map<String, String> values = new HashMap<>();
            for (String pair : row[2].split(",")) {
                int split = pair.lastIndexOf('=');
                values.put(pair.substring(0, split), pair.substring(split + 1));
            }
            for (String block : row[3].split(",")) {
                JAVA_VALUES.computeIfAbsent(row[0], key -> new HashMap<>()).put(GeyserFixtures.name(block), new JavaValues(keys, values));
            }
        }
    }

    // The Java values of one of Boar's properties on one block, from properties.26_30.txt, by the values of the Bedrock state keys that decide them
    private record JavaValues(List<String> keys, Map<String, String> values) {
        String of(NbtMap states) {
            List<String> parts = new ArrayList<>();
            for (String key : this.keys) {
                parts.add(String.valueOf(states.get(key)));
            }
            return this.values.get(parts.isEmpty() ? "*" : String.join("/", parts));
        }
    }

    // The value Geyser's Java state of the palette entry has for one of Boar's keys, or null
    private static String javaValue(String key, int index) {
        JavaValues values = JAVA_VALUES.getOrDefault(key, Map.of()).get(table.name(index));
        String value = values == null || !geyser.containsKey(index) ? null : values.of(table.states(index));
        return "none".equals(value) ? null : value;
    }

    // The first palette entry with this name and these state values
    private static int find(String name, Object... states) {
        int first = table.firstIndex(name);
        assertTrue(first >= 0, name);
        for (int i = first; i < first + table.stateCount(name); i++) {
            boolean match = true;
            for (int s = 0; s < states.length; s += 2) {
                match &= states[s + 1].equals(table.states(i).get((String) states[s]));
            }
            if (match) {
                return i;
            }
        }
        throw new AssertionError(name + " has no state " + List.of(states));
    }

    private static BoarBlockState state(int index) {
        return BedrockState.FACTORY.create(index, POS, 0);
    }

    private static BedrockState delegate(int index) {
        return new BedrockState(index, POS, 0);
    }

    private static <T extends Comparable<T>> BedrockProperty<T> property(String key) {
        return BedrockProperties.INSTANCE.get(key);
    }

    private static <T extends Comparable<T>> T get(int index, String key) {
        return state(index).get(BedrockProperties.INSTANCE.<T>get(key));
    }

    private static <T extends Comparable<T>> int with(int index, String key, T value) {
        return state(index).with(BedrockProperties.INSTANCE.get(key), value).intermediaryId();
    }

    @Test
    void everyPropertyReadsAndChangesItsBlock() {
        int bamboo = find("minecraft:bamboo", "bamboo_stalk_thickness", "thin", "age_bit", (byte) 0, "bamboo_leaf_size", "no_leaves");
        assertEquals(0, (Integer) get(bamboo, "age_1"));
        assertEquals(find("minecraft:bamboo", "bamboo_stalk_thickness", "thick", "age_bit", (byte) 0, "bamboo_leaf_size", "no_leaves"), with(bamboo, "age_1", 1));

        int berries = find("minecraft:sweet_berry_bush", "growth", 2);
        assertEquals(2, (Integer) get(berries, "age_3"));
        assertEquals(find("minecraft:sweet_berry_bush", "growth", 3), with(berries, "age_3", 3));
        assertEquals(1, (Integer) get(find("minecraft:nether_wart", "age", 1), "age_3"));

        int floorBell = find("minecraft:bell", "attachment", "standing", "direction", 0, "toggle_bit", (byte) 0);
        assertEquals("floor", get(floorBell, "bell_attachment"));
        assertEquals(find("minecraft:bell", "attachment", "hanging", "direction", 0, "toggle_bit", (byte) 0), with(floorBell, "bell_attachment", "ceiling"));
        assertEquals("single_wall", get(find("minecraft:bell", "attachment", "side"), "bell_attachment"));
        assertEquals("double_wall", get(find("minecraft:bell", "attachment", "multiple"), "bell_attachment"));

        int cake = find("minecraft:cake", "bite_counter", 3);
        assertEquals(3, (Integer) get(cake, "bites"));
        assertEquals(find("minecraft:cake", "bite_counter", 6), with(cake, "bites", 6));

        int door = find("minecraft:wooden_door", "door_hinge_bit", (byte) 1, "minecraft:cardinal_direction", "south", "open_bit", (byte) 0, "upper_block_bit", (byte) 1);
        assertEquals("right", get(door, "door_hinge"));
        assertEquals(find("minecraft:wooden_door", "door_hinge_bit", (byte) 0, "minecraft:cardinal_direction", "south", "open_bit", (byte) 0, "upper_block_bit", (byte) 1), with(door, "door_hinge", "left"));

        int bubbles = find("minecraft:bubble_column", "drag_down", (byte) 1);
        assertEquals(true, get(bubbles, "drag"));
        assertEquals(find("minecraft:bubble_column", "drag_down", (byte) 0), with(bubbles, "drag", false));

        int dripstone = find("minecraft:pointed_dripstone", "dripstone_thickness", "merge", "hanging", (byte) 1);
        assertEquals("tip_merge", get(dripstone, "dripstone_thickness"));
        assertEquals(Direction.DOWN, get(dripstone, "vertical_direction"));
        assertEquals(find("minecraft:pointed_dripstone", "dripstone_thickness", "merge", "hanging", (byte) 0), with(dripstone, "vertical_direction", Direction.UP));
        assertEquals(find("minecraft:pointed_dripstone", "dripstone_thickness", "base", "hanging", (byte) 1), with(dripstone, "dripstone_thickness", "base"));

        int trapdoor = find("minecraft:trapdoor", "upside_down_bit", (byte) 1, "open_bit", (byte) 0, "direction", 3);
        assertEquals("top", get(trapdoor, "half"));
        assertEquals(false, get(trapdoor, "open"));
        assertEquals(find("minecraft:trapdoor", "upside_down_bit", (byte) 0, "open_bit", (byte) 0, "direction", 3), with(trapdoor, "half", "bottom"));
        assertEquals(find("minecraft:trapdoor", "upside_down_bit", (byte) 1, "open_bit", (byte) 1, "direction", 3), with(trapdoor, "open", true));
        assertEquals("bottom", get(find("minecraft:oak_stairs", "upside_down_bit", (byte) 0), "half"));

        int lantern = find("minecraft:soul_lantern", "hanging", (byte) 1);
        assertEquals(true, get(lantern, "hanging"));
        assertEquals(find("minecraft:soul_lantern", "hanging", (byte) 0), with(lantern, "hanging", false));

        int anchor = find("minecraft:respawn_anchor", "respawn_anchor_charge", 2);
        assertEquals(2, (Integer) get(anchor, "respawn_anchor_charges"));
        assertEquals(find("minecraft:respawn_anchor", "respawn_anchor_charge", 4), with(anchor, "respawn_anchor_charges", 4));

        int vault = find("minecraft:vault", "vault_state", "unlocking");
        assertEquals("unlocking", get(vault, "vault_state"));
        assertEquals("active", table.states(with(vault, "vault_state", "active")).getString("vault_state"));

        int gate = find("minecraft:fence_gate", "open_bit", (byte) 1);
        assertEquals(true, get(gate, "open"));
        assertEquals(true, get(find("minecraft:barrel", "open_bit", (byte) 1), "open"));
    }

    @Test
    void blocksWithoutThePropertyGiveNull() {
        int stone = table.firstIndex("minecraft:stone");
        for (String key : KEYS) {
            assertNull(get(stone, key), key);
            assertThrows(IllegalArgumentException.class, () -> with(stone, key, null), key);
        }
        // A lever's open_bit is Java's powered, a grindstone's attachment is Java's face, and a hanging sign's hanging is not a lantern's.
        assertNull(get(table.firstIndex("minecraft:lever"), "open"));
        assertNull(get(table.firstIndex("minecraft:grindstone"), "bell_attachment"));
        assertNull(get(table.firstIndex("minecraft:oak_hanging_sign"), "hanging"));
        assertNull(get(table.firstIndex("minecraft:suspicious_sand"), "hanging"));
        assertNull(get(table.firstIndex("minecraft:lantern"), "vertical_direction"));
        assertNull(get(table.firstIndex("minecraft:ender_chest"), "chest_type"));
        assertNull(get(table.firstIndex("minecraft:wooden_door"), "half"));
        assertThrows(IllegalArgumentException.class, () -> BedrockProperties.INSTANCE.get("shape"));
    }

    @Test
    void chestsAreSingleLecternsHaveNoBookAndWaterIsInLayerOne() {
        int chest = table.firstIndex("minecraft:chest");
        assertEquals(ChestType.SINGLE, get(chest, "chest_type"));
        assertEquals(chest, with(chest, "chest_type", ChestType.SINGLE));
        assertThrows(IllegalArgumentException.class, () -> with(chest, "chest_type", ChestType.LEFT));
        assertEquals(ChestType.SINGLE, get(table.firstIndex("minecraft:waxed_copper_chest"), "chest_type"));

        int lectern = table.firstIndex("minecraft:lectern");
        assertEquals(false, get(lectern, "has_book"));
        assertThrows(IllegalArgumentException.class, () -> with(lectern, "has_book", true));

        // Stairs take water and a double slab does not, but Boar's slab merge sets waterlogged=false on it.
        int stairs = table.firstIndex("minecraft:oak_stairs");
        int doubleSlab = table.firstIndex("minecraft:oak_double_slab");
        assertEquals(false, get(stairs, "waterlogged"));
        assertEquals(stairs, with(stairs, "waterlogged", true));
        assertNull(get(doubleSlab, "waterlogged"));
        assertEquals(doubleSlab, with(doubleSlab, "waterlogged", false));
        assertThrows(IllegalArgumentException.class, () -> with(doubleSlab, "waterlogged", true));
        int stone = table.firstIndex("minecraft:stone");
        assertEquals(stone, with(stone, "waterlogged", false));
        assertEquals(-1, BedrockProperties.INSTANCE.<Boolean>get("waterlogged").with(stone, true));

        assertFalse(delegate(stairs).isWaterlogged());
        assertTrue(delegate(table.firstIndex("minecraft:seagrass")).isWaterlogged());
        assertTrue(delegate(table.firstIndex("minecraft:kelp")).isWaterlogged());
        assertEquals(true, get(table.firstIndex("minecraft:seagrass"), "waterlogged"));
        assertTrue(delegate(find("minecraft:sea_pickle", "dead_bit", (byte) 0)).isWaterlogged());
        assertFalse(delegate(find("minecraft:sea_pickle", "dead_bit", (byte) 1)).isWaterlogged());

        // A sea pickle is alive only in water, so waterlogged follows its dead_bit both ways.
        int alive = find("minecraft:sea_pickle", "dead_bit", (byte) 0, "cluster_count", 2);
        int dead = find("minecraft:sea_pickle", "dead_bit", (byte) 1, "cluster_count", 2);
        assertEquals(dead, with(alive, "waterlogged", false));
        assertEquals(alive, with(dead, "waterlogged", true));
        assertEquals(false, get(dead, "waterlogged"));

        // Blocks that always stand in water are waterlogged as Geyser's Java states are.
        for (String name : List.of("minecraft:kelp", "minecraft:seagrass", "minecraft:bubble_column", "minecraft:sea_pickle")) {
            int first = table.firstIndex(name);
            for (int i = first; i < first + table.stateCount(name); i++) {
                if (geyser.containsKey(i)) {
                    assertEquals(geyser.get(i).waterlogged(), delegate(i).isWaterlogged(), name + " " + table.states(i));
                }
            }
        }
    }

    // Each family's direction, with values from Geyser's mapping (Java facing=north stairs are weirdo_direction 3, and so on)
    @Test
    void horizontalFacingOfEachFamily() {
        assertFacing(Direction.NORTH, Direction.EAST, "minecraft:oak_stairs", "weirdo_direction", 3, 0, "upside_down_bit", (byte) 0);
        assertFacing(Direction.WEST, Direction.SOUTH, "minecraft:stone_brick_stairs", "weirdo_direction", 1, 2, "upside_down_bit", (byte) 1);
        assertFacing(Direction.NORTH, Direction.SOUTH, "minecraft:trapdoor", "direction", 3, 2, "upside_down_bit", (byte) 0, "open_bit", (byte) 1);
        assertFacing(Direction.EAST, Direction.WEST, "minecraft:iron_trapdoor", "direction", 0, 1, "upside_down_bit", (byte) 0, "open_bit", (byte) 1);
        assertFacing(Direction.SOUTH, Direction.NORTH, "minecraft:bed", "direction", 0, 2, "head_piece_bit", (byte) 0, "occupied_bit", (byte) 0);
        assertFacing(Direction.WEST, Direction.EAST, "minecraft:bed", "direction", 1, 3, "head_piece_bit", (byte) 1, "occupied_bit", (byte) 0);
        assertFacing(Direction.NORTH, Direction.EAST, "minecraft:bell", "direction", 0, 1, "attachment", "standing", "toggle_bit", (byte) 0);
        assertFacing(Direction.SOUTH, Direction.WEST, "minecraft:bell", "direction", 2, 3, "attachment", "side", "toggle_bit", (byte) 0);
        assertFacing(Direction.NORTH, Direction.EAST, "minecraft:ladder", "facing_direction", 2, 5);
        assertFacing(Direction.SOUTH, Direction.WEST, "minecraft:ladder", "facing_direction", 3, 4);
        assertFacing(Direction.WEST, Direction.NORTH, "minecraft:anvil", "minecraft:cardinal_direction", "west", "north");
        assertFacing(Direction.EAST, Direction.SOUTH, "minecraft:damaged_anvil", "minecraft:cardinal_direction", "east", "south");
        // Doors are a quarter turn apart: Java facing=east is Bedrock south.
        assertFacing(Direction.EAST, Direction.NORTH, "minecraft:wooden_door", "minecraft:cardinal_direction", "south", "east",
                "door_hinge_bit", (byte) 0, "open_bit", (byte) 0, "upper_block_bit", (byte) 0);
        assertFacing(Direction.WEST, Direction.SOUTH, "minecraft:iron_door", "minecraft:cardinal_direction", "north", "west",
                "door_hinge_bit", (byte) 1, "open_bit", (byte) 1, "upper_block_bit", (byte) 1);

        assertNull(get(find("minecraft:ladder", "facing_direction", 0), "horizontal_facing"));
        assertNull(get(table.firstIndex("minecraft:furnace"), "horizontal_facing"));
    }

    private static void assertFacing(Direction facing, Direction turned, String name, String state, Object value, Object turnedValue, Object... others) {
        List<Object> from = new ArrayList<>(List.of(others));
        from.addAll(List.of(state, value));
        List<Object> to = new ArrayList<>(List.of(others));
        to.addAll(List.of(state, turnedValue));
        int index = find(name, from.toArray());
        assertEquals(facing, get(index, "horizontal_facing"), name);
        assertEquals(find(name, to.toArray()), with(index, "horizontal_facing", turned), name);
    }

    // Every palette entry that Geyser maps from a Java state: where both give a property, they agree.
    @Test
    void agreesWithGeysersJavaStates() {
        Map<String, Set<String>> javaOnly = new TreeMap<>();
        int javaValues = 0;
        int compared = 0;
        for (String key : KEYS) {
            // Java's single, left and right chests are one Bedrock state, so Geyser's way back picks one of them.
            if (key.equals("chest_type")) {
                continue;
            }
            BedrockProperty<?> property = property(key);
            for (int i = 0; i < table.paletteSize(); i++) {
                String javaValue = javaValue(key, i);
                if (javaValue == null) {
                    continue;
                }
                javaValues++;
                Object value = property.get(i);
                if (value != null) {
                    assertEquals(javaValue, String.valueOf(value), key + " of " + table.name(i) + " " + table.states(i));
                    compared++;
                } else {
                    javaOnly.computeIfAbsent(key, k -> new TreeSet<>()).add(table.name(i));
                }
            }
        }
        // The palette entries with a Java value, summed over the keys
        assertEquals(14898, javaValues);
        assertTrue(compared > 4000, "compared " + compared);

        // Blocks with Java's property that Boar never reads it on, and whose Bedrock state does not match Java's one to one
        Set<String> lightBlocks = new TreeSet<>();
        for (int level = 0; level < 16; level++) {
            lightBlocks.add("minecraft:light_block_" + level);
        }
        assertEquals(Set.of("minecraft:torchflower_crop"), javaOnly.get("age_1"));
        assertEquals(Set.of("minecraft:beetroot"), javaOnly.get("age_3"));
        assertEquals(lightBlocks, javaOnly.get("level"));
        // Java's double slabs have waterlogged, but Bedrock's cannot take water.
        Set<String> doubleSlabs = javaOnly.get("waterlogged");
        assertEquals(68, doubleSlabs.size());
        assertTrue(doubleSlabs.stream().allMatch(name -> name.contains("double") && name.endsWith("_slab")), doubleSlabs.toString());
        // Facing is bound only on the families Boar reads it on; each of them must agree above, not drop out here.
        for (String name : javaOnly.get("horizontal_facing")) {
            assertFalse(name.endsWith("stairs") || name.endsWith("trapdoor") || name.endsWith("_door") || name.endsWith("anvil")
                    || name.equals("minecraft:bell") || name.equals("minecraft:bed") || name.equals("minecraft:ladder"), name);
        }
        javaOnly.keySet().removeAll(Set.of("age_1", "age_3", "level", "horizontal_facing", "waterlogged"));
        assertEquals(Map.of(), javaOnly);
    }

    @Test
    void slabsAndDoubleSlabs() {
        int bottom = find("minecraft:oak_slab", "minecraft:vertical_half", "bottom");
        int top = find("minecraft:oak_slab", "minecraft:vertical_half", "top");
        int doubled = find("minecraft:oak_double_slab", "minecraft:vertical_half", "bottom");
        assertEquals("bottom", get(bottom, "slab_type"));
        assertEquals("top", get(top, "slab_type"));
        assertEquals("double", get(doubled, "slab_type"));
        assertEquals("double", get(find("minecraft:oak_double_slab", "minecraft:vertical_half", "top"), "slab_type"));
        assertEquals(doubled, with(bottom, "slab_type", "double"));
        assertEquals(doubled, with(top, "slab_type", "double"));
        assertEquals(top, with(doubled, "slab_type", "top"));
        assertEquals(top, with(bottom, "slab_type", "top"));
        assertEquals(find("minecraft:waxed_double_cut_copper_slab", "minecraft:vertical_half", "bottom"), with(find("minecraft:waxed_cut_copper_slab", "minecraft:vertical_half", "top"), "slab_type", "double"));

        // Every Java slab's single and double Bedrock names, from Geyser
        int slabs = 0;
        for (String[] row : GeyserFixtures.blocks()) {
            if (row[0].endsWith("_slab")) {
                String[] names = row[3].split(",");
                int single = find(GeyserFixtures.name(names[0]), "minecraft:vertical_half", "bottom");
                assertEquals(GeyserFixtures.name(names[1]), table.name(with(single, "slab_type", "double")), row[0]);
                slabs++;
            }
        }
        assertEquals(68, slabs);
    }

    @Test
    void waterAndLavaLevels() {
        assertEquals(0, (Integer) get(find("minecraft:water", "liquid_depth", 0), "level"));
        assertEquals(5, (Integer) get(find("minecraft:flowing_water", "liquid_depth", 5), "level"));
        assertEquals(0, (Integer) get(find("minecraft:lava", "liquid_depth", 0), "level"));
        assertEquals(9, (Integer) get(find("minecraft:flowing_lava", "liquid_depth", 9), "level"));
        // A level change keeps the block, as only the depth counts for Boar.
        assertEquals(find("minecraft:water", "liquid_depth", 3), with(find("minecraft:water", "liquid_depth", 0), "level", 3));
        assertEquals(find("minecraft:flowing_lava", "liquid_depth", 0), with(find("minecraft:flowing_lava", "liquid_depth", 9), "level", 0));
        assertNull(get(table.firstIndex("minecraft:cauldron"), "level"));
    }

    @Test
    void campfiresAndOtherLitBlocks() {
        int burning = find("minecraft:campfire", "extinguished", (byte) 0, "minecraft:cardinal_direction", "north");
        int out = find("minecraft:campfire", "extinguished", (byte) 1, "minecraft:cardinal_direction", "north");
        assertEquals(true, get(burning, "lit"));
        assertEquals(false, get(out, "lit"));
        assertEquals(out, with(burning, "lit", false));
        assertEquals(burning, with(out, "lit", true));
        assertEquals(false, get(find("minecraft:soul_campfire", "extinguished", (byte) 1), "lit"));

        int candle = find("minecraft:red_candle", "lit", (byte) 1, "candles", 2);
        assertEquals(true, get(candle, "lit"));
        assertEquals(find("minecraft:red_candle", "lit", (byte) 0, "candles", 2), with(candle, "lit", false));

        int furnace = find("minecraft:furnace", "minecraft:cardinal_direction", "west");
        assertEquals(false, get(furnace, "lit"));
        assertEquals(find("minecraft:lit_furnace", "minecraft:cardinal_direction", "west"), with(furnace, "lit", true));
        assertEquals(furnace, with(furnace, "lit", false));
        assertEquals(true, get(table.firstIndex("minecraft:redstone_torch"), "lit"));
        assertEquals(false, get(table.firstIndex("minecraft:unlit_redstone_torch"), "lit"));
    }

    @Test
    void collisionFaceAndReplacing() {
        assertBoxes(state(table.firstIndex("minecraft:stone")).getCollisionBoxes(), 0, 0, 0, 1, 1, 1);
        assertBoxes(state(find("minecraft:oak_slab", "minecraft:vertical_half", "top")).getCollisionBoxes(), 0, 0.5F, 0, 1, 1, 1);
        assertBoxes(state(find("minecraft:oak_stairs", "upside_down_bit", (byte) 0, "weirdo_direction", 3)).getCollisionBoxes(), 0, 0, 0, 1, 0.5F, 1, 0, 0.5F, 0, 1, 1, 0.5F);
        assertBoxes(state(table.firstIndex("minecraft:honey_block")).getCollisionBoxes(), 0.0625F, 0, 0.0625F, 0.9375F, 1, 0.9375F);
        assertBoxes(state(find("minecraft:snow_layer", "height", 0, "covered_bit", (byte) 0)).getCollisionBoxes());
        assertBoxes(state(find("minecraft:snow_layer", "height", 3, "covered_bit", (byte) 0)).getCollisionBoxes(), 0, 0, 0, 1, 0.375F, 1);

        assertTrue(delegate(table.firstIndex("minecraft:stone")).isFaceSturdy(null));
        assertTrue(delegate(table.firstIndex("minecraft:glass")).isFaceSturdy(null));
        assertTrue(delegate(table.firstIndex("minecraft:oak_leaves")).isFaceSturdy(null));
        assertFalse(delegate(table.firstIndex("minecraft:oak_slab")).isFaceSturdy(null));
        assertFalse(delegate(table.firstIndex("minecraft:soul_sand")).isFaceSturdy(null));
        assertFalse(delegate(table.firstIndex("minecraft:air")).isFaceSturdy(null));

        assertTrue(delegate(table.firstIndex("minecraft:short_grass")).isReplaceable(null));
        assertTrue(delegate(table.firstIndex("minecraft:water")).isReplaceable(null));
        assertTrue(delegate(table.firstIndex("minecraft:light_block_7")).isReplaceable(null));
        assertTrue(delegate(find("minecraft:snow_layer", "height", 0, "covered_bit", (byte) 1)).isReplaceable(null));
        assertFalse(delegate(find("minecraft:snow_layer", "height", 1, "covered_bit", (byte) 0)).isReplaceable(null));
        assertFalse(delegate(table.firstIndex("minecraft:stone")).isReplaceable(null));
        assertFalse(delegate(table.firstIndex("minecraft:oak_slab")).isReplaceable(null));
    }

    // Faces and replacing agree with Geyser's Java state, except the three blocks whose Bedrock box is a full cube and Java's is not.
    @Test
    void faceAndReplacingAgreeWithGeyser() {
        Set<String> sturdyDiffers = new TreeSet<>();
        geyser.forEach((i, java) -> {
            assertEquals(java.replaceable(), delegate(i).isReplaceable(null), table.name(i) + " " + table.states(i));
            if (java.faceSturdy() != delegate(i).isFaceSturdy(null)) {
                sturdyDiffers.add(table.name(i));
            }
        });
        assertEquals(Set.of("minecraft:azalea", "minecraft:dragon_egg", "minecraft:flowering_azalea"), sturdyDiffers);
    }

    // Every replaceable palette entry
    @Test
    void replaceableEntries() {
        Set<String> names = new TreeSet<>(Set.of(
                "air", "bubble_column", "bush", "crimson_roots", "deadbush", "fern", "fire", "flowing_lava", "flowing_water", "glow_lichen", "hanging_roots", "large_fern", "lava",
                "leaf_litter", "nether_sprouts", "resin_clump", "seagrass", "short_dry_grass", "short_grass", "soul_fire", "structure_void", "tall_dry_grass", "tall_grass", "vine",
                "warped_roots", "water"));
        for (int level = 0; level < 16; level++) {
            names.add("light_block_" + level);
        }
        Map<String, Integer> expected = new TreeMap<>();
        for (String name : names) {
            expected.put("minecraft:" + name, table.stateCount("minecraft:" + name));
        }
        // Only the thinnest snow layer, covered or not
        expected.put("minecraft:snow_layer", 2);

        Map<String, Integer> replaceable = new TreeMap<>();
        for (int i = 0; i < table.paletteSize(); i++) {
            if (delegate(i).isReplaceable(null)) {
                replaceable.merge(table.name(i), 1, Integer::sum);
            }
        }
        assertEquals(expected, replaceable);
    }

    @Test
    void factoryPicksBoarsStateClasses() {
        assertInstanceOf(HoneyBlockState.class, state(table.firstIndex("minecraft:honey_block")));
        assertInstanceOf(SlimeBlockState.class, state(table.firstIndex("minecraft:slime")));
        assertInstanceOf(BedBlockState.class, state(table.firstIndex("minecraft:bed") + 5));
        assertInstanceOf(VanillaBoarBlockState.class, state(table.firstIndex("minecraft:stone")));
        // An ID past the vanilla palette keeps its ID and is a full cube of a block that is not air; an ID past those BedrockPalette gives is air.
        for (int id : new int[]{table.paletteSize(), BedrockPalette.UNKNOWN_ID, BedrockPalette.END_ID - 1}) {
            BoarBlockState unknown = state(id);
            assertInstanceOf(VanillaBoarBlockState.class, unknown);
            assertEquals(id, unknown.intermediaryId());
            assertFalse(unknown.isAir());
            assertSame(BedrockState.UNKNOWN_BLOCK, unknown.block());
            assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, flatten(unknown.getCollisionBoxes()));
            assertTrue(unknown.isFaceSturdy(null));
            assertFalse(unknown.isReplaceable(null));
            assertFalse(unknown.isWaterlogged());
            assertNull(unknown.get(BedrockProperties.INSTANCE.<Boolean>get("waterlogged")));
            assertNull(delegate(id).connectionCollisionOverride(null, POS));
        }
        assertEquals(table.firstIndex("minecraft:air"), state(BedrockPalette.END_ID).intermediaryId());
        assertEquals(table.firstIndex("minecraft:air"), state(-1).intermediaryId());
    }

    private static BoarPlayer player() {
        BoarPlayer player = new BoarPlayer(new Offline(), null, null, BedrockPalette.mappingInfo(new StartGamePacket()), null, null, null, Map.of(), true);
        player.compensatedWorld.setDimension(Dimension.OVERWORLD);
        return player;
    }

    private static List<Box> boarCollision(BoarPlayer player, int index) {
        return state(index).findCollision(player, POS, Box.EMPTY, false);
    }

    // The table's boxes at POS
    private static float[] bedrockBoxes(int index) {
        float[] boxes = table.collision(index).clone();
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] += i % 3 == 1 ? POS.getY() : i % 3 == 0 ? POS.getX() : POS.getZ();
        }
        return boxes;
    }

    // Boar lowers a floor bell's box itself, so the final box of every bell is the BDS box.
    @Test
    void bellsEndWithBedrocksBoxThroughBoarsCollision() {
        BoarPlayer player = player();
        int floor = find("minecraft:bell", "attachment", "standing", "direction", 0, "toggle_bit", (byte) 0);
        assertBoxes(state(floor).getCollisionBoxes(), 0, 0, 0.25F, 1, 1, 0.75F);
        assertBoxes(boarCollision(player, floor), 8, 64, 8.25F, 9, 64.8125F, 8.75F);

        int bell = table.firstIndex("minecraft:bell");
        for (int i = bell; i < bell + table.stateCount("minecraft:bell"); i++) {
            assertBoxes(boarCollision(player, i), bedrockBoxes(i));
        }
    }

    // Where Boar's collision picks a box by direction, half or hanging, the Bedrock state ends with the BDS box.
    // The BDS data has one box for every door state, so doors are compared with Java's box for the facing, open and hinge of Geyser's Java state instead, which is 0.005 thicker than Boar's.
    @Test
    void boarsCollisionShapesFollowTheBindings() {
        BoarPlayer player = player();
        int compared = 0;
        for (String name : List.of("minecraft:trapdoor", "minecraft:iron_trapdoor", "minecraft:warped_trapdoor", "minecraft:anvil", "minecraft:chipped_anvil",
                "minecraft:lantern", "minecraft:soul_lantern", "minecraft:bell", "minecraft:wooden_door", "minecraft:iron_door", "minecraft:cherry_door")) {
            int first = table.firstIndex(name);
            for (int i = first; i < first + table.stateCount(name); i++) {
                if (!geyser.containsKey(i) || name.equals("minecraft:bell") && !"floor".equals(get(i, "bell_attachment"))) {
                    continue;
                }
                List<Box> boxes = boarCollision(player, i);
                String state = name + " " + table.states(i);
                if (name.endsWith("_door")) {
                    float[] door = javaDoorBox(Direction.valueOf(javaValue("horizontal_facing", i)), Boolean.parseBoolean(javaValue("open", i)), javaValue("door_hinge", i).equals("right"));
                    for (int v = 0; v < 6; v++) {
                        door[v] += v % 3 == 1 ? POS.getY() : v % 3 == 0 ? POS.getX() : POS.getZ();
                    }
                    assertArrayEquals(door, flatten(boxes), 0.0051F, state);
                } else {
                    assertArrayEquals(bedrockBoxes(i), flatten(boxes), state);
                }
                compared++;
            }
        }
        assertEquals(164, compared);
    }

    // The box of Java's DoorBlock.getShape: 3/16 thick, on the side the door's facing points away from. An open door turns that a quarter, clockwise when the hinge is on the left.
    private static float[] javaDoorBox(Direction facing, boolean open, boolean rightHinge) {
        int turns = !open ? 0 : rightHinge ? 3 : 1;
        Direction named = Direction.HORIZONTAL[(List.of(Direction.HORIZONTAL).indexOf(facing) + turns) % 4];
        float thickness = 0.1875F;
        return switch (named) {
            case EAST -> new float[]{0, 0, 0, thickness, 1, 1};
            case WEST -> new float[]{1 - thickness, 0, 0, 1, 1, 1};
            case SOUTH -> new float[]{0, 0, 0, 1, 1, thickness};
            default -> new float[]{0, 0, 1 - thickness, 1, 1, 1};
        };
    }

    // The BDS data has no piston arm boxes. The arm boxes come from the client; their plate is the plate of the Java piston head that Geyser maps each arm to.
    @Test
    void pistonArmsHaveTheClientsBoxes() {
        int north = find("minecraft:piston_arm_collision", "facing_direction", 3);
        assertEquals(0, table.collision(north).length);
        assertBoxes(delegate(north).getCollisionBoxes(), 0.3125F, 0.3125F, 0.75F, 0.6875F, 0.6875F, 1.25F, 0.375F, 0.375F, 0.25F, 0.625F, 0.625F, 0.75F, 0, 0, 0, 1, 1, 0.25F);
        assertEquals(delegate(north).getCollisionBoxes(), delegate(find("minecraft:sticky_piston_arm_collision", "facing_direction", 3)).getCollisionBoxes());

        // The facing of Geyser's Java piston head for each facing_direction (checked against Geyser at c200cbe)
        Direction[] javaFacings = {Direction.DOWN, Direction.UP, Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};
        for (String name : List.of("minecraft:piston_arm_collision", "minecraft:sticky_piston_arm_collision")) {
            int first = table.firstIndex(name);
            for (int i = first; i < first + table.stateCount(name); i++) {
                List<Box> boxes = delegate(i).getCollisionBoxes();
                assertEquals(3, boxes.size());
                // The plate is the last box: a quarter of the block on the side the head faces.
                Vector3i facing = javaFacings[table.states(i).getInt("facing_direction")].getUnitVector();
                float[] plate = {facing.getX() > 0 ? 0.75F : 0, facing.getY() > 0 ? 0.75F : 0, facing.getZ() > 0 ? 0.75F : 0, facing.getX() < 0 ? 0.25F : 1, facing.getY() < 0 ? 0.25F : 1, facing.getZ() < 0 ? 0.25F : 1};
                assertArrayEquals(plate, flatten(List.of(boxes.get(2))), name + " " + table.states(i));
            }
        }
    }

    // Stairs are closed to water on the side they face (StairLiquidFlow), which is their full height side in the BDS boxes.
    @Test
    void stairsFaceTheirFullHeightSide() {
        Map<Direction, float[]> backs = Map.of(
                Direction.NORTH, new float[]{0, 0.5F, 0, 1, 1, 0.5F},
                Direction.SOUTH, new float[]{0, 0.5F, 0.5F, 1, 1, 1},
                Direction.WEST, new float[]{0, 0.5F, 0, 0.5F, 1, 1},
                Direction.EAST, new float[]{0.5F, 0.5F, 0, 1, 1, 1});
        int first = table.firstIndex("minecraft:oak_stairs");
        for (int i = first; i < first + table.stateCount("minecraft:oak_stairs"); i++) {
            if (table.states(i).getByte("upside_down_bit") == 0) {
                Direction facing = get(i, "horizontal_facing");
                float[] back = backs.get(facing);
                assertArrayEquals(back, Arrays.copyOfRange(table.collision(i), 6, 12), table.states(i).toString());
            }
        }
    }

    private static float[] flatten(List<Box> boxes) {
        float[] flat = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, flat, i * 6, 6);
        }
        return flat;
    }

    private static void assertBoxes(List<Box> boxes, float... expected) {
        assertArrayEquals(expected, flatten(boxes));
    }

    // Boar's collision asks the session only which random offsets the platform uses.
    private record Offline() implements NetworkSession {
        @Override
        public String name() {
            return "Tester";
        }

        @Override
        public UUID uuid() {
            return new UUID(0, 0);
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public boolean requiresPingMagnitude() {
            return false;
        }

        @Override
        public int protocolVersion() {
            return 0;
        }
    }
}
