package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.block.Block;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Bedrock block for Boar: one Bedrock block name, or the names Bedrock splits one block into by state, such as water and flowing_water or furnace and lit_furnace.
 * The first name is the block's main name, and that name's default state from Bedrock Dedicated Server is the block's default state.
 */
public final class BedrockBlock implements Block {
    private static final BedrockBlockTable TABLE = BedrockBlockTable.get();

    // The other Bedrock names of a block, by its main name, as Geyser groups them from Java blocks; slabs get their double slabs from the palette. The two piston arms stay apart, as on Bedrock.
    private static final Map<String, List<String>> OTHER_NAMES = Map.ofEntries(
            Map.entry("minecraft:water", List.of("minecraft:flowing_water")),
            Map.entry("minecraft:lava", List.of("minecraft:flowing_lava")),
            Map.entry("minecraft:furnace", List.of("minecraft:lit_furnace")),
            Map.entry("minecraft:blast_furnace", List.of("minecraft:lit_blast_furnace")),
            Map.entry("minecraft:smoker", List.of("minecraft:lit_smoker")),
            Map.entry("minecraft:redstone_ore", List.of("minecraft:lit_redstone_ore")),
            Map.entry("minecraft:deepslate_redstone_ore", List.of("minecraft:lit_deepslate_redstone_ore")),
            Map.entry("minecraft:redstone_lamp", List.of("minecraft:lit_redstone_lamp")),
            Map.entry("minecraft:redstone_torch", List.of("minecraft:unlit_redstone_torch")),
            Map.entry("minecraft:unpowered_repeater", List.of("minecraft:powered_repeater")),
            Map.entry("minecraft:unpowered_comparator", List.of("minecraft:powered_comparator")),
            Map.entry("minecraft:daylight_detector", List.of("minecraft:daylight_detector_inverted")),
            Map.entry("minecraft:cave_vines", List.of("minecraft:cave_vines_body_with_berries", "minecraft:cave_vines_head_with_berries")),
            // Java's light block defaults to level 15.
            Map.entry("minecraft:light_block_15", lightBlocks()));

    // Boar's Java-named keys, and the Bedrock block each one means
    private static final Map<String, String> BOAR_NAMES = Map.of(
            "minecraft:cobweb", "minecraft:web",
            "minecraft:lily_pad", "minecraft:waterlily",
            "minecraft:note_block", "minecraft:noteblock",
            "minecraft:slime_block", "minecraft:slime",
            "minecraft:snow", "minecraft:snow_layer");

    private static final BedrockBlock[] BY_INDEX = new BedrockBlock[TABLE.paletteSize()];
    private static final Map<String, BedrockBlock> BY_NAME = new HashMap<>();
    private static final List<BedrockBlock> ALL;

    static {
        Map<String, List<String>> groups = new LinkedHashMap<>(OTHER_NAMES);
        BedrockProperties.doubleSlabs(TABLE).forEach((slab, doubled) -> groups.put(slab, List.of(doubled)));
        Map<String, String> mainNames = new HashMap<>();
        groups.forEach((main, others) -> others.forEach(other -> mainNames.put(other, main)));

        List<BedrockBlock> all = new ArrayList<>();
        for (int i = 0; i < TABLE.paletteSize(); i += TABLE.stateCount(TABLE.name(i))) {
            String main = mainNames.getOrDefault(TABLE.name(i), TABLE.name(i));
            if (!BY_NAME.containsKey(main)) {
                List<String> names = new ArrayList<>();
                names.add(main);
                names.addAll(groups.getOrDefault(main, List.of()));
                BedrockBlock block = new BedrockBlock(names);
                all.add(block);
                for (String name : names) {
                    BY_NAME.put(name, block);
                }
            }
            BedrockBlock block = BY_NAME.get(main);
            for (int state = i; state < i + TABLE.stateCount(TABLE.name(i)); state++) {
                BY_INDEX[state] = block;
            }
        }
        ALL = List.copyOf(all);

        // Boar gives pointed dripstone its offset box by name, and a sulfur spike has the same states and boxes.
        BY_NAME.get("minecraft:sulfur_spike").lookalike = BY_NAME.get("minecraft:pointed_dripstone");
    }

    private final List<String> names;
    private final int defaultIndex;
    // Another block that Boar's checks treat this one as, or null
    private BedrockBlock lookalike;

    private BedrockBlock(List<String> names) {
        this.names = List.copyOf(names);
        this.defaultIndex = TABLE.defaultIndex(names.getFirst());
        for (String name : names) {
            if (TABLE.firstIndex(name) < 0) {
                throw new IllegalStateException("The Bedrock palette has no " + name);
            }
        }
    }

    private static List<String> lightBlocks() {
        List<String> names = new ArrayList<>();
        for (int level = 0; level < 15; level++) {
            names.add("minecraft:light_block_" + level);
        }
        return names;
    }

    // The block of a palette index
    public static BedrockBlock of(int index) {
        return BY_INDEX[index];
    }

    // The block with this Bedrock name, such as "minecraft:flowing_water", or null
    public static BedrockBlock named(String name) {
        return BY_NAME.get(name);
    }

    public static List<BedrockBlock> all() {
        return ALL;
    }

    // Boar also names cave_air and void_air, which Bedrock does not have; their references stay empty.
    public static void populate(ReferencePopulator<Block> populator) {
        Set<String> keys = new HashSet<>(BY_NAME.keySet());
        keys.addAll(BOAR_NAMES.keySet());
        for (String key : keys) {
            if (populator.get(key) != null) {
                populator.populate(key, BY_NAME.get(BOAR_NAMES.getOrDefault(key, key)));
            }
        }
        populator.ensurePopulated(false);
    }

    public String name() {
        return this.names.getFirst();
    }

    public List<String> names() {
        return this.names;
    }

    @Override
    public boolean is(Block value) {
        return value != null && (value == this || value == this.lookalike);
    }

    @Override
    public boolean is(Reference<Block> reference) {
        return reference.find().map(this::is).orElse(false);
    }

    // Bedrock's hardness. It differs from Java's destroy time for air, obsidian, crying obsidian and Java's test blocks.
    @Override
    public float destroyTime() {
        return TABLE.hardness(this.defaultIndex);
    }

    @Override
    public BoarBlockState defaultBlockState(Vector3i position, int layer) {
        return BedrockState.FACTORY.create(this.defaultIndex, position, layer);
    }

    public int defaultIndex() {
        return this.defaultIndex;
    }

    @Override
    public String toString() {
        return this.name();
    }
}
