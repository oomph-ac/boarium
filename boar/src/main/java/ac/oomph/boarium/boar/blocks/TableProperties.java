package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.util.math.Direction;
import ac.boar.mappings.block.ChestType;
import ac.boar.mappings.block.Property;
import ac.boar.mappings.block.PropertyProvider;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Boar's 20 block properties, each bound to the block table property that Geyser's property of the same meaning has.
 * The table holds values as strings; they are converted to the types Boar declares (Integer, Boolean, String, ChestType, Direction).
 */
public final class TableProperties implements PropertyProvider {
    private static final String[] BOOLEAN = {"true", "false"};

    public static final TableProperties INSTANCE = new TableProperties();

    private final Map<String, TableProperty<?>> bound = new HashMap<>();

    private TableProperties() {
        BlockTable table = BlockTable.get();
        this.number(table, "age_1", "age", 1);
        this.number(table, "age_3", "age", 3);
        this.number(table, "bites", "bites", 6);
        this.number(table, "level", "level", 15);
        this.number(table, "respawn_anchor_charges", "charges", 4);
        for (String key : new String[]{"drag", "hanging", "has_book", "lit", "open", "waterlogged"}) {
            this.bind(table, key, key, BOOLEAN, Boolean::valueOf, String::valueOf);
        }
        this.text(table, "bell_attachment", "attachment", "floor", "ceiling", "single_wall", "double_wall");
        this.text(table, "door_hinge", "hinge", "left", "right");
        this.text(table, "dripstone_thickness", "thickness", "tip_merge", "tip", "frustum", "middle", "base");
        this.text(table, "half", "half", "top", "bottom");
        this.text(table, "slab_type", "type", "top", "bottom", "double");
        this.text(table, "vault_state", "vault_state", "inactive", "active", "unlocking", "ejecting");
        this.constant(table, "chest_type", "type", ChestType.class, "single", "left", "right");
        this.constant(table, "horizontal_facing", "facing", Direction.class, "north", "south", "west", "east");
        this.constant(table, "vertical_direction", "vertical_direction", Direction.class, "up", "down");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> Property<T> get(String key) {
        TableProperty<?> property = this.bound.get(key);
        if (property == null) {
            throw new IllegalArgumentException("Unknown block property " + key);
        }
        return (Property<T>) property;
    }

    // A property with the values 0 to max
    private void number(BlockTable table, String key, String name, int max) {
        String[] values = new String[max + 1];
        Arrays.setAll(values, String::valueOf);
        this.bind(table, key, name, values, Integer::valueOf, String::valueOf);
    }

    private void text(BlockTable table, String key, String name, String... values) {
        this.bind(table, key, name, values, Function.identity(), Function.identity());
    }

    // A property whose values are the names of Boar's enum constants in lower case
    private <E extends Enum<E>> void constant(BlockTable table, String key, String name, Class<E> type, String... values) {
        this.bind(table, key, name, values, value -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT)), value -> value.name().toLowerCase(Locale.ROOT));
    }

    private <T extends Comparable<T>> void bind(BlockTable table, String key, String name, String[] values, Function<String, T> parse, Function<T, String> format) {
        int id = table.property(name, values);
        if (id < 0) {
            throw new IllegalStateException("The block table has no property " + name + Arrays.toString(values) + " for Boar's " + key);
        }
        this.bound.put(key, new TableProperty<>(key, id, parse, format));
    }

    /**
     * One of Boar's properties.
     *
     * @param id the block table's property ID
     * @param parse turns the table's value into Boar's
     * @param format turns Boar's value into the table's
     */
    record TableProperty<T extends Comparable<T>>(String key, int id, Function<String, T> parse, Function<T, String> format) implements Property<T> {
    }
}
