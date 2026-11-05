package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.util.math.Direction;
import ac.boar.mappings.block.ChestType;
import ac.boar.mappings.block.Property;
import ac.boar.mappings.block.PropertyProvider;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static ac.boar.anticheat.util.math.Direction.EAST;
import static ac.boar.anticheat.util.math.Direction.NORTH;
import static ac.boar.anticheat.util.math.Direction.SOUTH;
import static ac.boar.anticheat.util.math.Direction.WEST;

/**
 * Boar's block properties, each bound to the Bedrock states that carry its meaning, family by family, with the Java values Boar expects.
 * Blocks outside the bound families do not have the property, even where Java gives them one that Boar never reads, such as the facing of a furnace.
 */
public final class BedrockProperties implements PropertyProvider {
    private static final Byte OFF = 0;
    private static final Byte ON = 1;

    public static final BedrockProperties INSTANCE = new BedrockProperties(BedrockBlockUnion.get().table());

    private final Map<String, BedrockProperty<?>> bound = new HashMap<>();

    BedrockProperties(BedrockBlockTable table) {
        Predicate<String> all = name -> true;

        this.bind(table, "age_1", new StateFamily<>(named("minecraft:bamboo"), "bamboo_stalk_thickness", Map.of(0, "thin", 1, "thick")));
        this.bind(table, "age_3", new NumberFamily(named("minecraft:sweet_berry_bush"), "growth"), new NumberFamily(named("minecraft:nether_wart", "minecraft:frosted_ice"), "age"));
        this.bind(table, "bell_attachment", new StateFamily<>(named("minecraft:bell"), "attachment", Map.of("floor", "standing", "ceiling", "hanging", "single_wall", "side", "double_wall", "multiple")));
        this.bind(table, "bites", new NumberFamily(all, "bite_counter"));
        // The state never says whether a chest is paired: Bedrock pairs chests in their block entities.
        this.bind(table, "chest_type", new ConstantFamily<>(name -> name.endsWith("chest") && !name.equals("minecraft:ender_chest"), ChestType.SINGLE));
        this.bind(table, "door_hinge", new StateFamily<>(all, "door_hinge_bit", Map.of("left", OFF, "right", ON)));
        this.bind(table, "drag", new StateFamily<>(all, "drag_down", bits()));
        this.bind(table, "dripstone_thickness", new StateFamily<>(all, "dripstone_thickness", Map.of("tip_merge", "merge", "tip", "tip", "frustum", "frustum", "middle", "middle", "base", "base")));
        this.bind(table, "half", new StateFamily<>(all, "upside_down_bit", Map.of("top", ON, "bottom", OFF)));
        this.bind(table, "hanging", new StateFamily<>(name -> name.endsWith("lantern") || name.equals("minecraft:mangrove_propagule"), "hanging", bits()));
        // The book is in the lectern's block entity, not in its state.
        this.bind(table, "has_book", new ConstantFamily<>(named("minecraft:lectern"), false));
        this.bind(table, "horizontal_facing",
                new StateFamily<>(all, "weirdo_direction", Map.of(EAST, 0, WEST, 1, SOUTH, 2, NORTH, 3)),
                new StateFamily<>(name -> name.endsWith("trapdoor"), "direction", Map.of(EAST, 0, WEST, 1, SOUTH, 2, NORTH, 3)),
                new StateFamily<>(named("minecraft:bed"), "direction", Map.of(SOUTH, 0, WEST, 1, NORTH, 2, EAST, 3)),
                new StateFamily<>(named("minecraft:bell"), "direction", Map.of(NORTH, 0, EAST, 1, SOUTH, 2, WEST, 3)),
                new StateFamily<>(named("minecraft:ladder"), "facing_direction", Map.of(NORTH, 2, SOUTH, 3, WEST, 4, EAST, 5)),
                new StateFamily<>(named("minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil"), "minecraft:cardinal_direction", Map.of(NORTH, "north", EAST, "east", SOUTH, "south", WEST, "west")),
                // A Bedrock door's direction is a quarter turn clockwise from Java's facing.
                new StateFamily<>(name -> name.endsWith("_door"), "minecraft:cardinal_direction", Map.of(NORTH, "east", EAST, "south", SOUTH, "west", WEST, "north")));
        this.bind(table, "level", new NumberFamily(all, "liquid_depth"));
        this.bind(table, "lit",
                new StateFamily<>(all, "lit", bits()),
                new StateFamily<>(all, "extinguished", Map.of(true, OFF, false, ON)),
                new RenamedFamily(Map.of(
                        "minecraft:furnace", "minecraft:lit_furnace",
                        "minecraft:blast_furnace", "minecraft:lit_blast_furnace",
                        "minecraft:smoker", "minecraft:lit_smoker",
                        "minecraft:redstone_ore", "minecraft:lit_redstone_ore",
                        "minecraft:deepslate_redstone_ore", "minecraft:lit_deepslate_redstone_ore",
                        "minecraft:redstone_lamp", "minecraft:lit_redstone_lamp",
                        "minecraft:unlit_redstone_torch", "minecraft:redstone_torch")));
        // A lever's open_bit is Java's powered.
        this.bind(table, "open", new StateFamily<>(name -> !name.equals("minecraft:lever"), "open_bit", bits()));
        this.bind(table, "respawn_anchor_charges", new NumberFamily(all, "respawn_anchor_charge"));
        this.bind(table, "slab_type", new SlabFamily(doubleSlabs(table)));
        this.bind(table, "vault_state", new StateFamily<>(all, "vault_state", Map.of("inactive", "inactive", "active", "active", "unlocking", "unlocking", "ejecting", "ejecting")));
        this.bind(table, "vertical_direction", new StateFamily<>(named("minecraft:pointed_dripstone", "minecraft:sulfur_spike"), "hanging", Map.of(Direction.UP, OFF, Direction.DOWN, ON)));
        this.bind(table, "waterlogged", new WaterFamily());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> BedrockProperty<T> get(String key) {
        BedrockProperty<?> property = this.bound.get(key);
        if (property == null) {
            throw new IllegalArgumentException("Unknown block property " + key);
        }
        return (BedrockProperty<T>) property;
    }

    @SafeVarargs
    private <T extends Comparable<T>> void bind(BedrockBlockTable table, String key, Family<T>... families) {
        this.bound.put(key, new BedrockProperty<>(key, table, List.of(families)));
    }

    private static Predicate<String> named(String... names) {
        return Set.of(names)::contains;
    }

    private static Map<Boolean, Object> bits() {
        return Map.of(true, ON, false, OFF);
    }

    // Each single slab's double slab, such as oak_slab -> oak_double_slab and cut_copper_slab -> double_cut_copper_slab
    static Map<String, String> doubleSlabs(BedrockBlockTable table) {
        Map<String, String> doubles = new HashMap<>();
        for (int i = 0; i < table.paletteSize(); i += table.stateCount(table.name(i))) {
            String name = table.name(i);
            if (!name.endsWith("_slab") || name.contains("double")) {
                continue;
            }
            String doubled = name.endsWith("cut_copper_slab") ? name.replace("cut_copper_slab", "double_cut_copper_slab") : name.replace("_slab", "_double_slab");
            if (table.firstIndex(doubled) < 0) {
                throw new IllegalStateException("The Bedrock palette has " + name + " but not " + doubled);
            }
            doubles.put(name, doubled);
        }
        return doubles;
    }

    /**
     * One of Boar's properties, answered from the first family that covers the block.
     */
    public static final class BedrockProperty<T extends Comparable<T>> implements Property<T> {
        private final String key;
        private final BedrockBlockTable table;
        private final List<Family<T>> families;
        // The value of each palette entry, or null where the entry does not have the property
        private final Object[] values;

        private BedrockProperty(String key, BedrockBlockTable table, List<Family<T>> families) {
            this.key = key;
            this.table = table;
            this.families = families;
            this.values = new Object[table.paletteSize()];
            for (int i = 0; i < this.values.length; i++) {
                Family<T> family = this.family(i);
                this.values[i] = family == null ? null : family.read(table, i);
            }
        }

        public String key() {
            return this.key;
        }

        // Null when the palette entry does not have the property
        @SuppressWarnings("unchecked")
        public T get(int index) {
            return index >= 0 && index < this.values.length ? (T) this.values[index] : null;
        }

        // The palette index of the entry with this value instead, or -1 if the block cannot take that value
        public int with(int index, T value) {
            Family<T> family = index >= 0 && index < this.values.length ? this.family(index) : null;
            return family == null || value == null ? -1 : family.with(this.table, index, value);
        }

        private Family<T> family(int index) {
            for (Family<T> family : this.families) {
                if (family.covers(this.table, index)) {
                    return family;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return this.key;
        }
    }

    private interface Family<T> {
        boolean covers(BedrockBlockTable table, int index);

        // The value of a covered entry, or null if the entry does not have the property after all
        T read(BedrockBlockTable table, int index);

        // The palette index of a covered entry with this value instead, or -1
        int with(BedrockBlockTable table, int index, T value);
    }

    // The blocks with one state whose values match Boar's one to one. Map values have the palette's NBT types (Byte, Integer or String).
    private record StateFamily<T>(Predicate<String> names, String state, Map<T, Object> values, Map<Object, T> inverse) implements Family<T> {
        StateFamily(Predicate<String> names, String state, Map<T, Object> values) {
            this(names, state, values, values.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey)));
        }

        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return this.names.test(table.name(index)) && table.states(index).containsKey(this.state);
        }

        @Override
        public T read(BedrockBlockTable table, int index) {
            return this.inverse.get(table.states(index).get(this.state));
        }

        @Override
        public int with(BedrockBlockTable table, int index, T value) {
            Object bedrock = this.values.get(value);
            return bedrock == null ? -1 : table.index(table.name(index), put(table.states(index), this.state, bedrock));
        }
    }

    // The blocks with a whole number state that is Boar's value
    private record NumberFamily(Predicate<String> names, String state) implements Family<Integer> {
        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return this.names.test(table.name(index)) && table.states(index).containsKey(this.state);
        }

        @Override
        public Integer read(BedrockBlockTable table, int index) {
            return (Integer) table.states(index).get(this.state);
        }

        @Override
        public int with(BedrockBlockTable table, int index, Integer value) {
            return table.index(table.name(index), put(table.states(index), this.state, value));
        }
    }

    // Blocks whose state cannot show the property, so they always have one value
    private record ConstantFamily<T>(Predicate<String> names, T value) implements Family<T> {
        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return this.names.test(table.name(index));
        }

        @Override
        public T read(BedrockBlockTable table, int index) {
            return this.value;
        }

        @Override
        public int with(BedrockBlockTable table, int index, T value) {
            return this.value.equals(value) ? index : -1;
        }
    }

    // Blocks that Bedrock lights by changing the name, such as furnace and lit_furnace
    private record RenamedFamily(Map<String, String> toLit, Map<String, String> toUnlit) implements Family<Boolean> {
        RenamedFamily(Map<String, String> toLit) {
            this(toLit, toLit.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey)));
        }

        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return this.toLit.containsKey(table.name(index)) || this.toUnlit.containsKey(table.name(index));
        }

        @Override
        public Boolean read(BedrockBlockTable table, int index) {
            return this.toUnlit.containsKey(table.name(index));
        }

        @Override
        public int with(BedrockBlockTable table, int index, Boolean value) {
            String name = table.name(index);
            String renamed = value.equals(this.read(table, index)) ? name : value ? this.toLit.get(name) : this.toUnlit.get(name);
            return table.index(renamed, table.states(index));
        }
    }

    // A single slab is top or bottom by its vertical half; a double slab is another block, whose vertical half is set to bottom as Geyser does.
    private record SlabFamily(Map<String, String> doubles, Map<String, String> singles) implements Family<String> {
        private static final String HALF = "minecraft:vertical_half";

        SlabFamily(Map<String, String> doubles) {
            this(doubles, doubles.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey)));
        }

        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return this.doubles.containsKey(table.name(index)) || this.singles.containsKey(table.name(index));
        }

        @Override
        public String read(BedrockBlockTable table, int index) {
            return this.singles.containsKey(table.name(index)) ? "double" : table.states(index).getString(HALF);
        }

        @Override
        public int with(BedrockBlockTable table, int index, String type) {
            String name = table.name(index);
            if (type.equals(this.read(table, index))) {
                return index;
            }
            String single = this.singles.getOrDefault(name, name);
            return switch (type) {
                case "double" -> table.index(this.doubles.get(single), put(table.states(index), HALF, "bottom"));
                case "top", "bottom" -> table.index(single, put(table.states(index), HALF, type));
                default -> -1;
            };
        }
    }

    // Bedrock keeps a block's water in layer 1, which a state cannot see, so only blocks that always stand in water are waterlogged, as Geyser maps them from Java.
    // Every block that can take water in layer 1 has the property.
    private record WaterFamily() implements Family<Boolean> {
        private static final Set<String> IN_WATER = Set.of("minecraft:bubble_column", "minecraft:kelp", "minecraft:seagrass");

        @Override
        public boolean covers(BedrockBlockTable table, int index) {
            return true;
        }

        @Override
        public Boolean read(BedrockBlockTable table, int index) {
            if (!table.canContainLiquidSource(index)) {
                return null;
            }
            // A sea pickle is alive only in water.
            String name = table.name(index);
            return IN_WATER.contains(name) || name.equals("minecraft:sea_pickle") && table.states(index).getByte("dead_bit") == 0;
        }

        @Override
        public int with(BedrockBlockTable table, int index, Boolean waterlogged) {
            if (table.name(index).equals("minecraft:sea_pickle")) {
                return table.index(table.name(index), put(table.states(index), "dead_bit", waterlogged ? OFF : ON));
            }
            // Setting false keeps any block, since Boar sets it on a slab it has just made double.
            return !waterlogged || table.canContainLiquidSource(index) ? index : -1;
        }
    }

    private static NbtMap put(NbtMap states, String state, Object value) {
        NbtMapBuilder builder = states.toBuilder();
        builder.put(state, value);
        return builder.build();
    }
}
