package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.collision.DripstoneCollision;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Direction;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.Blocks;
import ac.oomph.boarium.boar.TestBoar;
import ac.oomph.boarium.boar.blocks.BedrockProperties.BedrockProperty;
import org.cloudburstmc.math.vector.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected values come from the Bedrock palette and its BDS data (BedrockBlockTable), and from Geyser's Java blocks and their Bedrock names (blocks.26_30.txt of GeyserFixtures).
class BedrockBlockTest {
    private static final Vector3i POS = Vector3i.from(8, 64, 8);

    private static BedrockBlockTable table;
    // The Java block lines of blocks.26_30.txt
    private static List<String[]> javaBlocks;

    @BeforeAll
    static void load() throws Exception {
        TestBoar.start();
        table = BedrockBlockTable.get();
        javaBlocks = GeyserFixtures.blocks();
    }

    // The Bedrock names of a Java block's states, its default state's first
    private static List<String> namesOf(String[] javaBlock) {
        return Arrays.stream(javaBlock.length > 3 ? javaBlock[3].split(",") : new String[]{javaBlock[0]}).map(GeyserFixtures::name).toList();
    }

    private static BedrockBlock block(String name) {
        BedrockBlock block = BedrockBlock.named("minecraft:" + name);
        assertTrue(block != null, name);
        return block;
    }

    // Boar's state of a palette entry, by its shared ID
    private static BoarBlockState state(int index) {
        return BedrockState.FACTORY.create(SharedIds.shared(index), POS, 0);
    }

    // The first palette entry with this name and these state values
    private static int find(String name, Object... states) {
        int first = table.firstIndex(name);
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

    // Boar's block keys, from the fields of its Blocks class
    private static List<String> boarKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        for (Field field : Blocks.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && field.get(null) instanceof Reference.Deferred<?> deferred) {
                keys.add(deferred.key());
            }
        }
        return keys;
    }

    private static Map<String, Reference<Block>> populated() throws Exception {
        ReferencePopulator<Block> populator = new ReferencePopulator<>("block");
        Map<String, Reference<Block>> references = new LinkedHashMap<>();
        for (String key : boarKeys()) {
            references.put(key, populator.defer(key));
        }
        BedrockBlock.populate(populator);
        return references;
    }

    @Test
    void everyBoarReferenceIsResolvedOrKnowinglyAbsent() throws Exception {
        Map<String, Reference<Block>> references = populated();
        assertEquals(53, references.size());
        Map<String, String> renamed = Map.of("cobweb", "web", "lily_pad", "waterlily", "note_block", "noteblock", "slime_block", "slime", "snow", "snow_layer");
        Set<String> absent = new TreeSet<>();
        references.forEach((key, reference) -> {
            String name = key.substring("minecraft:".length());
            if (reference.find().isEmpty()) {
                absent.add(name);
            } else {
                assertSame(block(renamed.getOrDefault(name, name)), reference.find().orElseThrow(), key);
            }
        });
        assertEquals(Set.of("cave_air", "void_air"), absent);

        // Bedrock's snow is the full block; Boar's snow is the layer.
        assertNotSame(block("snow"), references.get("minecraft:snow").find().orElseThrow());
        // Java has no invisible bedrock, so only this platform resolves this key.
        assertSame(block("invisible_bedrock"), references.get("minecraft:invisible_bedrock").find().orElseThrow());
        assertSame(block("water"), references.get("minecraft:water").find().orElseThrow());
        assertTrue(state(find("minecraft:flowing_water", "liquid_depth", 3)).is(references.get("minecraft:water")));
        assertTrue(state(table.firstIndex("minecraft:lit_furnace")).is(references.get("minecraft:furnace")));
        assertTrue(state(table.firstIndex("minecraft:lit_blast_furnace")).is(references.get("minecraft:blast_furnace")));
        assertTrue(state(table.firstIndex("minecraft:lit_redstone_ore")).is(references.get("minecraft:redstone_ore")));
        assertFalse(state(table.firstIndex("minecraft:lit_deepslate_redstone_ore")).is(references.get("minecraft:redstone_ore")));
    }

    @Test
    void aliasesShareOneBlock() {
        Map<String, List<String>> expected = Map.of(
                "water", List.of("water", "flowing_water"),
                "lava", List.of("lava", "flowing_lava"),
                "oak_slab", List.of("oak_slab", "oak_double_slab"),
                "cut_copper_slab", List.of("cut_copper_slab", "double_cut_copper_slab"),
                "furnace", List.of("furnace", "lit_furnace"),
                "redstone_ore", List.of("redstone_ore", "lit_redstone_ore"),
                "redstone_lamp", List.of("redstone_lamp", "lit_redstone_lamp"),
                "redstone_torch", List.of("redstone_torch", "unlit_redstone_torch"),
                "unpowered_comparator", List.of("unpowered_comparator", "powered_comparator"),
                "cave_vines", List.of("cave_vines", "cave_vines_body_with_berries", "cave_vines_head_with_berries"));
        expected.forEach((main, names) -> {
            for (String name : names) {
                assertSame(block(main), block(name), name);
            }
            assertEquals(names.stream().map(name -> "minecraft:" + name).toList(), block(main).names());
        });
        assertEquals(16, block("light_block_0").names().size());
        assertEquals("minecraft:light_block_15", block("light_block_0").name());

        BoarBlockState flowing = state(find("minecraft:flowing_water", "liquid_depth", 8));
        assertSame(block("water"), flowing.block());
        assertTrue(flowing.is(block("water")));
        assertFalse(flowing.is(block("lava")));
        assertTrue(state(table.firstIndex("minecraft:oak_double_slab")).is(block("oak_slab")));
        assertFalse(state(table.firstIndex("minecraft:oak_double_slab")).is(block("spruce_slab")));

        // These stay apart, as Java's blocks are.
        assertNotSame(block("pumpkin"), block("lit_pumpkin"));
        assertNotSame(block("snow"), block("snow_layer"));
        assertNotSame(block("piston_arm_collision"), block("sticky_piston_arm_collision"));
    }

    // Every Java block's Bedrock names are one block here, and every block with more than one name is one Java block or two Java blocks that share a name, except the piston arms.
    @Test
    void aliasesAgreeWithGeyser() {
        Map<String, Set<String>> javaGroups = new TreeMap<>();
        for (String[] javaBlock : javaBlocks) {
            List<String> names = namesOf(javaBlock);
            if (names.size() > 1 && !javaBlock[0].equals("piston_head")) {
                Set<BedrockBlock> blocks = new HashSet<>();
                names.forEach(name -> blocks.add(BedrockBlock.named(name)));
                assertEquals(1, blocks.size(), javaBlock[0] + " " + names);
                javaGroups.computeIfAbsent(blocks.iterator().next().name(), key -> new TreeSet<>()).addAll(names);
            }
        }
        Map<String, Set<String>> groups = new TreeMap<>();
        for (BedrockBlock block : BedrockBlock.all()) {
            if (block.names().size() > 1 && table.firstIndex(block.name()) >= 0) {
                groups.put(block.name(), new TreeSet<>(block.names()));
            }
        }
        assertEquals(javaGroups, groups);
    }

    @Test
    void everyPaletteEntryHasItsBlock() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < table.paletteSize(); i++) {
            BedrockBlock block = BedrockBlock.of(SharedIds.shared(i));
            assertTrue(block.names().contains(table.name(i)), table.name(i));
            assertSame(block, state(i).block());
            assertTrue(state(i).is(block));
            // A block's states all have the same hardness, so one destroy time fits.
            assertEquals(block.destroyTime(), table.hardness(i), table.name(i));
            names.add(table.name(i));
        }
        // Boar's blocks also have the names that only other versions' tables have.
        assertEquals(names.size(), BedrockBlock.all().stream().flatMap(block -> block.names().stream()).filter(name -> table.firstIndex(name) >= 0).count());
    }

    @Test
    void destroyTimeIsBedrockHardness() {
        assertEquals(1.5F, block("stone").destroyTime());
        assertEquals(0.5F, block("dirt").destroyTime());
        assertEquals(3.5F, block("lit_furnace").destroyTime());
        assertEquals(-1, block("bedrock").destroyTime());
        assertEquals(-1, block("barrier").destroyTime());
        assertEquals(100, block("flowing_water").destroyTime());
        assertEquals(35, block("obsidian").destroyTime());

        // Bedrock's hardness is Java's destroy time, except for these.
        Map<String, String> differs = new TreeMap<>();
        for (String[] javaBlock : javaBlocks) {
            BedrockBlock block = BedrockBlock.named(namesOf(javaBlock).getFirst());
            float destroyTime = Float.parseFloat(javaBlock[1]);
            if (block.destroyTime() != destroyTime) {
                differs.put(GeyserFixtures.name(javaBlock[0]), block.destroyTime() + " " + destroyTime);
            }
        }
        assertEquals(Map.of(
                "minecraft:air", "-1.0 0.0",
                "minecraft:cave_air", "-1.0 0.0",
                "minecraft:void_air", "-1.0 0.0",
                "minecraft:obsidian", "35.0 50.0",
                "minecraft:crying_obsidian", "35.0 50.0",
                "minecraft:test_block", "0.0 -1.0",
                "minecraft:test_instance_block", "0.0 -1.0"), differs);
    }

    // Each block's default state is the one Bedrock Dedicated Server gives (AllayMC's block_types.json); a group takes its main name's.
    @Test
    void defaultStateIsBdsDefault() {
        BoarBlockState water = block("flowing_water").defaultBlockState(POS, 1);
        assertEquals(find("minecraft:water", "liquid_depth", 0), SharedIds.own(water.intermediaryId()));
        assertEquals(POS, water.getPosition());
        assertEquals(1, water.getLayer());
        assertEquals(find("minecraft:oak_slab", "minecraft:vertical_half", "bottom"), SharedIds.own(block("oak_double_slab").defaultBlockState(POS, 0).intermediaryId()));
        assertEquals(find("minecraft:furnace", "minecraft:cardinal_direction", "south"), SharedIds.own(block("lit_furnace").defaultBlockState(POS, 0).intermediaryId()));
        assertEquals(find("minecraft:snow_layer", "height", 0, "covered_bit", (byte) 0), SharedIds.own(block("snow_layer").defaultBlockState(POS, 0).intermediaryId()));
        assertEquals(find("minecraft:light_block_15"), SharedIds.own(block("light_block_3").defaultBlockState(POS, 0).intermediaryId()));

        BoarBlockState dripleaf = block("big_dripleaf").defaultBlockState(POS, 0);
        assertEquals(find("minecraft:big_dripleaf", "big_dripleaf_head", (byte) 1, "big_dripleaf_tilt", "none", "minecraft:cardinal_direction", "south"), SharedIds.own(dripleaf.intermediaryId()));
        assertEquals(1, dripleaf.getCollisionBoxes().size());
        Box leaf = dripleaf.getCollisionBoxes().getFirst();
        assertArrayEquals(new float[]{0, 0.6875F, 0, 1, 0.9375F, 1}, new float[]{leaf.minX, leaf.minY, leaf.minZ, leaf.maxX, leaf.maxY, leaf.maxZ});
        for (String name : List.of("small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster")) {
            assertEquals(find("minecraft:" + name, "minecraft:block_face", "up"), SharedIds.own(block(name).defaultBlockState(POS, 0).intermediaryId()), name);
        }
        for (String name : List.of("pointed_dripstone", "sulfur_spike")) {
            assertEquals(find("minecraft:" + name, "dripstone_thickness", "tip", "hanging", (byte) 1), SharedIds.own(block(name).defaultBlockState(POS, 0).intermediaryId()), name);
        }
        assertEquals(find("minecraft:chest", "minecraft:cardinal_direction", "north"), SharedIds.own(block("chest").defaultBlockState(POS, 0).intermediaryId()));
    }

    @Test
    void sulfurSpikeTakesTheDripstonePath() throws Exception {
        Reference<Block> dripstone = populated().get("minecraft:pointed_dripstone");
        BedrockProperty<String> thickness = BedrockProperties.INSTANCE.get("dripstone_thickness");
        BedrockProperty<Direction> direction = BedrockProperties.INSTANCE.get("vertical_direction");
        int first = table.firstIndex("minecraft:sulfur_spike");
        assertEquals(table.stateCount("minecraft:pointed_dripstone"), table.stateCount("minecraft:sulfur_spike"));
        for (int spike = first; spike < first + table.stateCount("minecraft:sulfur_spike"); spike++) {
            int drip = table.index("minecraft:pointed_dripstone", table.states(spike));
            assertArrayEquals(table.collision(drip), table.collision(spike));
            BoarBlockState state = state(spike);
            // Boar's findCollision picks the dripstone box with is(Blocks.POINTED_DRIPSTONE) and reads these two properties.
            assertTrue(state.is(dripstone));
            assertEquals(thickness.get(SharedIds.shared(drip)), state.get(thickness));
            assertEquals(direction.get(SharedIds.shared(drip)), state.get(direction));
            // The dump's boxes are at 0, 0, 0 with the offset added, cut to 6 decimals.
            Box box = DripstoneCollision.getCollisionBox(0, 0, 0, true, state.get(thickness), state.get(direction) == Direction.DOWN);
            assertArrayEquals(table.collision(spike), new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 1.0E-6F, table.states(spike).toString());
        }
        // The spike stays its own block.
        assertSame(block("sulfur_spike"), state(first).block());
        assertNotSame(block("pointed_dripstone"), state(first).block());
        assertFalse(state(table.firstIndex("minecraft:pointed_dripstone")).is(block("sulfur_spike")));
    }

    // The names that 1.26.30 has, which Geyser's lists are of
    private static Set<String> bedrockNames(List<Block> blocks) {
        Set<String> names = new TreeSet<>();
        for (Block block : blocks) {
            ((BedrockBlock) block).names().stream().filter(name -> table.firstIndex(name) >= 0).forEach(names::add);
        }
        return names;
    }

    // The Bedrock names of every state of the Java blocks in each of Boar's Java block lists
    private static Map<String, Set<String>> javaNames() {
        Map<String, Set<String>> lists = new HashMap<>();
        for (String[] javaBlock : javaBlocks) {
            if (!javaBlock[2].equals("-")) {
                for (String list : javaBlock[2].split(",")) {
                    lists.computeIfAbsent(list, key -> new TreeSet<>()).addAll(namesOf(javaBlock));
                }
            }
        }
        return lists;
    }

    @Test
    void categoriesAgreeWithGeyser() {
        BlockMappings bedrock = new BedrockBlockMappings(Map.of());
        Map<String, Set<String>> java = javaNames();
        Map<String, Function<BlockMappings, List<Block>>> lists = new LinkedHashMap<>();
        lists.put("stairs", BlockMappings::getStairsBlocks);
        lists.put("leaves", BlockMappings::getLeavesBlocks);
        lists.put("shulker", BlockMappings::getShulkerBlocks);
        lists.put("fence_gate", BlockMappings::getFenceGateBlocks);
        lists.put("wall", BlockMappings::getWallBlocks);
        lists.put("fence", BlockMappings::getFenceBlocks);
        lists.put("climbable", BlockMappings::getClimbableBlocks);
        lists.put("bed", BlockMappings::getBedBlocks);
        lists.put("chest", BlockMappings::getChestBlocks);
        lists.put("door", BlockMappings::getDoorBlocks);
        lists.put("trapdoor", BlockMappings::getTrapDoorBlocks);
        lists.put("candle", BlockMappings::getCandleBlocks);
        lists.put("sign", BlockMappings::getSignBlocks);
        lists.put("button", BlockMappings::getButtonBlocks);
        lists.put("bars", BlockMappings::getBarsBlocks);
        lists.put("lantern", BlockMappings::getLanternBlocks);
        lists.put("anvil", BlockMappings::getAnvilBlocks);
        lists.put("cauldron", BlockMappings::getCauldronBlocks);
        lists.put("interactive", BlockMappings::getInteractiveBlocks);

        Set<String> hardPanes = new TreeSet<>();
        for (BedrockBlock block : BedrockBlock.all()) {
            if (block.name().startsWith("minecraft:hard_") && block.name().endsWith("glass_pane")) {
                hardPanes.add(block.name());
            }
        }
        assertEquals(17, hardPanes.size());

        Map<String, Integer> sizes = new LinkedHashMap<>();
        lists.forEach((list, get) -> {
            Set<String> ours = bedrockNames(get.apply(bedrock));
            Set<String> theirs = java.getOrDefault(list, Set.of());
            Set<String> onlyOurs = new TreeSet<>(ours);
            onlyOurs.removeAll(theirs);
            Set<String> onlyTheirs = new TreeSet<>(theirs);
            onlyTheirs.removeAll(ours);
            // Bedrock's hard glass panes are not in Java, and Java's list wrongly counts the jack o'lantern as a lantern.
            assertEquals(list.equals("bars") ? hardPanes : Set.of(), onlyOurs, list);
            assertEquals(list.equals("lantern") ? Set.of("minecraft:lit_pumpkin") : Set.of(), onlyTheirs, list);
            sizes.put(list, (int) get.apply(bedrock).stream().filter(block -> table.firstIndex(((BedrockBlock) block).name()) >= 0).count());
        });
        assertEquals(64, sizes.get("stairs"));
        assertEquals(43, sizes.get("bars"));
        assertEquals(11, sizes.get("lantern"));
        assertEquals(1, sizes.get("bed"));
        assertEquals(List.of(block("cauldron")), bedrock.getCauldronBlocks());
    }

    @Test
    void interactiveBlocks() {
        List<Block> interactive = new BedrockBlockMappings(Map.of()).getInteractiveBlocks();
        for (String name : List.of("wooden_door", "iron_door", "trapdoor", "acacia_trapdoor", "fence_gate", "birch_fence_gate", "bed", "standing_sign", "wall_sign", "acacia_standing_sign",
                "oak_hanging_sign", "wooden_button", "lit_furnace", "lit_smoker", "noteblock", "powered_comparator", "unpowered_repeater", "daylight_detector_inverted",
                "stonecutter_block", "flower_pot", "cauldron", "undyed_shulker_box", "white_candle_cake", "chest", "ender_chest", "copper_chest", "crafting_table", "lever", "anvil")) {
            assertTrue(interactive.contains(block(name)), name);
        }
        for (String name : List.of("stone", "stonecutter", "oak_slab", "glass_pane", "pumpkin", "water", "deprecated_anvil", "lit_pumpkin")) {
            assertFalse(interactive.contains(block(name)), name);
        }
    }
}
