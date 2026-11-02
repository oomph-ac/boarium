package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A block that a server adds in StartGame's block properties, with its properties, from which {@link #state(int)} gives the states the client makes, in the client's order.
 *
 * @param keys the property names, in the order of the block properties
 * @param values each property's values
 */
record CustomBlock(String name, List<String> keys, List<NbtList<?>> values) {
    // BlockType::createBlockPermutations keeps only 16 bits of property values for a block.
    private static final int MAX_BITS = 16;
    private static final Set<NbtType<?>> VALUE_TYPES = Set.of(NbtType.STRING, NbtType.INT, NbtType.BYTE);

    // Throws IllegalArgumentException when the client could not make the block's states as written.
    static CustomBlock of(BlockPropertyData data) {
        String name = data.getName();
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<NbtList<?>> values = new ArrayList<>();
        int bits = 0;
        Object properties = data.getProperties().get("properties");
        if (properties != null) {
            if (!(properties instanceof NbtList<?> list) || !list.isEmpty() && list.getType() != NbtType.COMPOUND) {
                throw malformed(name, "its properties are not a list of compounds");
            }
            for (Object element : list) {
                NbtMap property = (NbtMap) element;
                if (!(property.get("name") instanceof String key) || key.isEmpty() || !seen.add(key)) {
                    throw malformed(name, "a property has no name, or the name of another property");
                }
                if (!(property.get("enum") instanceof NbtList<?> enumValues) || enumValues.isEmpty() || !VALUE_TYPES.contains(enumValues.getType())) {
                    throw malformed(name, "property " + key + " has no string, int or byte values");
                }
                // As BlockType::addState: the bits that hold the value's index, at least 1.
                bits += Math.max(1, 32 - Integer.numberOfLeadingZeros(enumValues.size() - 1));
                if (bits > MAX_BITS) {
                    throw malformed(name, "its properties need more than the client's " + MAX_BITS + " bits");
                }
                if (new HashSet<>(enumValues).size() != enumValues.size()) {
                    throw malformed(name, "property " + key + " lists a value twice");
                }
                keys.add(key);
                values.add(enumValues);
            }
        }
        return new CustomBlock(name, List.copyOf(keys), List.copyOf(values));
    }

    int stateCount() {
        int count = 1;
        for (NbtList<?> list : this.values) {
            count *= list.size();
        }
        return count;
    }

    // The client lists the states in order of their permutation index, where the first property holds the lowest bits (BlockType::addState, BlockType::createBlockPermutations), so the first property changes fastest.
    // It adds the properties in the order of the block properties (BlockDefinitionGroup::digestServerBlockProperties, registerBlockFromDefinition).
    NbtMap state(int index) {
        Map<String, Object> state = new TreeMap<>();
        int rest = index;
        for (int p = 0; p < this.keys.size(); p++) {
            NbtList<?> list = this.values.get(p);
            state.put(this.keys.get(p), list.get(rest % list.size()));
            rest /= list.size();
        }
        return NbtMap.fromMap(state);
    }

    private static IllegalArgumentException malformed(String name, String reason) {
        return new IllegalArgumentException(name + ": " + reason);
    }

    // FNV-1a 32 of the little-endian NBT compound {name, states}, with the states sorted by key: the runtime ID when StartGame asks for hashed block IDs.
    static int networkHash(String name, NbtMap states) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);
        writeString(out, "");
        out.write(8);
        writeString(out, "name");
        writeString(out, name);
        out.write(10);
        writeString(out, "states");
        for (Map.Entry<String, Object> state : new TreeMap<>(states).entrySet()) {
            switch (state.getValue()) {
                case Byte value -> {
                    out.write(1);
                    writeString(out, state.getKey());
                    out.write(value);
                }
                case Integer value -> {
                    out.write(3);
                    writeString(out, state.getKey());
                    for (int shift = 0; shift < 32; shift += 8) {
                        out.write(value >>> shift);
                    }
                }
                case String value -> {
                    out.write(8);
                    writeString(out, state.getKey());
                    writeString(out, value);
                }
                default -> throw new IllegalArgumentException("State " + state + " has an unsupported type");
            }
        }
        out.write(0);
        out.write(0);

        int hash = 0x811c9dc5;
        for (byte b : out.toByteArray()) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.write(bytes.length >>> 8);
        out.writeBytes(bytes);
    }
}
