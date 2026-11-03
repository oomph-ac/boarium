package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A block that a server adds in StartGame's block properties, with its traits and properties, from which {@link #state(int)} gives the states the client makes, in the client's order.
 *
 * @param keys the names of the states its traits add, then the property names in the order of the block properties, where a name may be listed twice
 * @param values each key's values, of any NBT type
 * @param traitStates how many of the keys its traits add
 * @param placementFilter whether it or any of its permutations has a minecraft:placement_filter component
 * @param collision each state's collision boxes and the random offset that moves them
 * @param components each state's friction, replaceability, destroy time and random offset
 */
record CustomBlock(String name, List<String> keys, List<NbtList<?>> values, int traitStates, boolean placementFilter, int stateCount, CustomCollision collision, CustomComponents components) {
    // BlockType::createBlockPermutations makes a permutation for each value of the lowest 16 bits.
    private static final int PERMUTATION_BITS = 16;
    // Past 32 bits, the client's 32-bit shifts wrap around, so a later property reads the bits of the first ones, which Boar does not follow.
    private static final int MAX_BITS = 32;
    private static final String PLACEMENT_FILTER = "minecraft:placement_filter";
    // The client's CompoundTag sorts its keys by their UTF-8 bytes.
    private static final Comparator<String> KEY_ORDER = (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    static CustomBlock of(BlockPropertyData data) {
        return of(data, new CustomCollision.Budget());
    }

    static CustomBlock of(BlockPropertyData data, CustomCollision.Budget collisionBudget) {
        return of(data, collisionBudget, new CustomCollision.Budget());
    }

    /**
     * Throws IllegalArgumentException when Boar cannot make the block's states as the client does.
     * The collision and the other components each have a budget of their own, so that a block's friction cannot leave a later block's boxes guessed.
     */
    static CustomBlock of(BlockPropertyData data, CustomCollision.Budget collisionBudget, CustomCollision.Budget componentsBudget) {
        String name = data.getName();
        List<String> keys = new ArrayList<>();
        List<NbtList<?>> values = new ArrayList<>();
        int bits = 0;
        // BlockDefinitionGroup::registerBlockFromDefinition adds the states of the traits before those of the properties, so they take the lowest bits.
        for (CustomTraits.State state : CustomTraits.states(name, data.getProperties())) {
            bits += bits(state.values().size());
            keys.add(state.name());
            values.add(state.values());
        }
        int traitStates = keys.size();
        // As BlockDefinitionGroup::digestServerBlockProperties: it reads properties only from a list of compounds, skips a property without an "enum" list, and names a property without a string "name" "".
        if (data.getProperties().get("properties") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            for (Object element : list) {
                NbtMap property = (NbtMap) element;
                if (!(property.get("enum") instanceof NbtList<?> enumValues)) {
                    continue;
                }
                // The client reads the first value without checking that there is one.
                if (enumValues.isEmpty()) {
                    throw malformed(name, "a property has no values, which the client cannot read");
                }
                bits += bits(enumValues.size());
                if (bits > MAX_BITS) {
                    throw malformed(name, "its states need more than " + MAX_BITS + " bits");
                }
                keys.add(property.get("name") instanceof String key ? key : "");
                values.add(enumValues);
            }
        }
        int[] radices = radices(values);
        int states = 1;
        for (int radix : radices) {
            states *= radix;
        }
        CustomComponents components = CustomComponents.of(keys, values, radices, states, data.getProperties(), componentsBudget);
        CustomCollision collision = CustomCollision.of(keys, values, radices, states, data.getProperties(), collisionBudget, components);
        return new CustomBlock(name, List.copyOf(keys), List.copyOf(values), traitStates, placementFilter(data.getProperties()), states, collision, components);
    }

    private static boolean placementFilter(NbtMap properties) {
        if (properties.get("components") instanceof NbtMap components && components.containsKey(PLACEMENT_FILTER)) {
            return true;
        }
        if (properties.get("permutations") instanceof NbtList<?> permutations) {
            for (Object permutation : permutations) {
                if (permutation instanceof NbtMap map && map.get("components") instanceof NbtMap components && components.containsKey(PLACEMENT_FILTER)) {
                    return true;
                }
            }
        }
        return false;
    }

    // As BlockType::addState: the bits that hold the index of a value, at least 1.
    private static int bits(int size) {
        return Math.max(1, 64 - Long.numberOfLeadingZeros(size - 1L));
    }

    /**
     * How many permutations each key spans in the client's states. BlockType::addState gives each key the bits for its values, the first key the lowest bits.
     * BlockType::createBlockPermutations makes a state for each value of the lowest 16 bits where every key that fits in them is in range.
     * So the first key that does not fit spans every value of its bits below 16, and the keys after it span one.
     */
    static int[] radices(List<NbtList<?>> values) {
        int[] radices = new int[values.size()];
        int start = 0;
        for (int p = 0; p < radices.length; p++) {
            int bits = bits(values.get(p).size());
            radices[p] = start + bits <= PERMUTATION_BITS ? values.get(p).size() : start < PERMUTATION_BITS ? 1 << (PERMUTATION_BITS - start) : 1;
            start += bits;
        }
        return radices;
    }

    // The value of a property in a state, by its place in the property's span. BlockType::getState masks a property of more than 16 bits to nothing, so it reads the first value.
    static Object value(NbtList<?> values, int place) {
        return values.get(bits(values.size()) > PERMUTATION_BITS ? 0 : place);
    }

    // The client lists the states in order of their permutation index, so the first key changes fastest.
    // Of two equal keys, the state shows the last, as Block::buildSerializationId writes the keys in the order of their IDs: those of the traits' built-in states are the lowest, then come the properties in list order.
    NbtMap state(int index) {
        Map<String, Object> state = new TreeMap<>(KEY_ORDER);
        int[] radices = radices(this.values);
        int rest = index;
        for (int p = 0; p < radices.length; p++) {
            state.put(this.keys.get(p), value(this.values.get(p), rest % radices[p]));
            rest /= radices[p];
        }
        return NbtMap.fromMap(state);
    }

    private static IllegalArgumentException malformed(String name, String reason) {
        return new IllegalArgumentException(name + ": " + reason);
    }

    // FNV-1a 32 of the little-endian NBT compound {name, states}, with every compound's keys sorted as in the client's CompoundTag: the runtime ID when StartGame asks for hashed block IDs.
    static int networkHash(String name, NbtMap states) {
        return fnv1a(networkState(name, states));
    }

    static byte[] networkState(String name, NbtMap states) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (NBTOutputStream writer = NbtUtils.createWriterLE(out)) {
            writer.writeTag(sorted(NbtMap.builder().putString("name", name).putCompound("states", states).build()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static int fnv1a(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte b : bytes) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        return hash;
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object tag) {
        if (tag instanceof NbtMap map) {
            Map<String, Object> sorted = new TreeMap<>(KEY_ORDER);
            map.forEach((key, value) -> sorted.put(key, sorted(value)));
            return NbtMap.fromMap(sorted);
        }
        if (tag instanceof NbtList<?> list) {
            return new NbtList<>((NbtType<Object>) list.getType(), list.stream().map(CustomBlock::sorted).toList());
        }
        return tag;
    }
}
