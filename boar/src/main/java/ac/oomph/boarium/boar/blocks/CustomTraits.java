package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block states that a custom block's traits add, as the 1.26.30 client builds them from StartGame's block properties.
 * The client has 4 traits, and each adds built-in states such as minecraft:cardinal_direction, whose values the client names itself.
 */
final class CustomTraits {
    private static final String PLACEMENT_DIRECTION = "minecraft:placement_direction";
    private static final String PLACEMENT_POSITION = "minecraft:placement_position";
    private static final String CONNECTION = "minecraft:connection";
    private static final String MULTI_BLOCK = "minecraft:multi_block";

    // Each built-in state's values by index, as the client writes them in a state's NBT and gives them to q.block_state (BlockStateGroup::registerExistingBlockState).
    private static final NbtList<String> CARDINAL_DIRECTION = strings("south", "west", "north", "east");
    private static final NbtList<String> CORNER = strings("none", "inner_left", "inner_right", "outer_left", "outer_right");
    private static final NbtList<String> FACING = strings("down", "up", "north", "south", "west", "east");
    // The client writes this state with the 6 names of facing_direction, and "down" for the 10 values past them.
    private static final NbtList<String> SIXTEEN_WAY_ROTATION = strings("down", "up", "north", "south", "west", "east", "down", "down", "down", "down", "down", "down", "down", "down", "down", "down");
    private static final NbtList<String> VERTICAL_HALF = strings("bottom", "top");
    private static final NbtList<Byte> BOOL = new NbtList<>(NbtType.BYTE, (byte) 0, (byte) 1);
    // The client keeps each case of a trait's name as another trait. Boar reads no more than this many of one block, which a real block never comes near.
    static final int MAX_TRAITS = 16;

    private CustomTraits() {
    }

    record State(String name, NbtList<?> values) {
    }

    /**
     * The states that a block's traits add, in the order of the client's BlockType::addState calls, which come before those of the block's properties.
     * Throws IllegalArgumentException when Boar cannot make them as the client does, or past its limits.
     */
    static List<State> states(String block, NbtMap properties) {
        Map<String, NbtMap> traits = traits(block, properties);
        List<State> states = new ArrayList<>();
        for (String name : order(new ArrayList<>(traits.keySet()))) {
            NbtMap trait = traits.get(name);
            // Each trait's applyToBlockType
            switch (lowerCase(name)) {
                case PLACEMENT_DIRECTION -> {
                    boolean corner = enabled(trait, "corner_and_cardinal_direction");
                    if (corner || enabled(trait, "cardinal_direction")) {
                        add(states, "minecraft:cardinal_direction", CARDINAL_DIRECTION);
                    }
                    if (corner) {
                        add(states, "minecraft:corner", CORNER);
                    }
                    if (enabled(trait, "facing_direction")) {
                        add(states, "minecraft:facing_direction", FACING);
                    }
                    if (enabled(trait, "sixteen_way_rotation")) {
                        add(states, "minecraft:sixteen_way_rotation", SIXTEEN_WAY_ROTATION);
                    }
                }
                case PLACEMENT_POSITION -> {
                    if (enabled(trait, "block_face")) {
                        add(states, "minecraft:block_face", FACING);
                    }
                    if (enabled(trait, "vertical_half")) {
                        add(states, "minecraft:vertical_half", VERTICAL_HALF);
                    }
                }
                // Connection::initializeFromNetwork reads enabled_states as an int, of which bit 0 enables the 4 states.
                case CONNECTION -> {
                    if (trait.get("enabled_states") instanceof Integer bits && (bits & 1) != 0) {
                        for (String side : List.of("north", "east", "south", "west")) {
                            add(states, "minecraft:connection_" + side, BOOL);
                        }
                    }
                }
                case MULTI_BLOCK -> {
                    // Without an enabled_states compound, the client keeps the trait's defaults: multi_block on, with 2 parts.
                    boolean defaults = !(trait.get("enabled_states") instanceof NbtMap);
                    if (defaults || enabled(trait, "multi_block")) {
                        int parts = defaults ? 2 : trait.get("part_count") instanceof Byte count ? count & 0xFF : 0;
                        // BlockType::addState asserts on a state without values, then gives it 64 bits.
                        if (parts == 0) {
                            throw new IllegalArgumentException(block + ": its minecraft:multi_block trait has a part_count of 0 or none, which the client cannot read");
                        }
                        add(states, "minecraft:multi_block_part", multiBlockParts(parts));
                    }
                }
                default -> throw new IllegalStateException(name);
            }
        }
        return states;
    }

    // As BlockDefinitionGroup::digestServerBlockProperties: it reads traits only from a list of compounds, skips a trait whose name BlockTraitFactory::createTrait does not know, and keeps the first trait of a name as sent.
    private static Map<String, NbtMap> traits(String block, NbtMap properties) {
        Map<String, NbtMap> traits = new LinkedHashMap<>();
        if (properties.get("traits") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            for (Object element : list) {
                NbtMap trait = (NbtMap) element;
                String name = trait.get("name") instanceof String string ? string : "";
                if (List.of(PLACEMENT_DIRECTION, PLACEMENT_POSITION, CONNECTION, MULTI_BLOCK).contains(lowerCase(name))) {
                    traits.putIfAbsent(name, trait);
                    if (traits.size() > MAX_TRAITS) {
                        throw new IllegalArgumentException(block + ": it has more than " + MAX_TRAITS + " traits");
                    }
                }
            }
        }
        return traits;
    }

    // As Util::toLower, with which BlockTraitFactory::createTrait finds a trait: only A to Z change.
    private static String lowerCase(String name) {
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] += 'a' - 'A';
            }
        }
        return new String(chars);
    }

    // The trait's flag of this name in its enabled_states compound, which the client reads only from a byte
    private static boolean enabled(NbtMap trait, String state) {
        return trait.get("enabled_states") instanceof NbtMap states && states.get(state) instanceof Byte flag && flag != 0;
    }

    // BlockType::addState keeps the first of a built-in state, so a second trait of the same kind adds only the states the first did not.
    private static void add(List<State> states, String name, NbtList<?> values) {
        if (states.stream().noneMatch(state -> state.name().equals(name))) {
            states.add(new State(name, values));
        }
    }

    // BlockStateVariant<MultiBlockPart>::_toNBT writes an index below 4 as itself, and 0 for the others.
    private static NbtList<Integer> multiBlockParts(int count) {
        List<Integer> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            parts.add(i < 4 ? i : 0);
        }
        return new NbtList<>(NbtType.INT, parts);
    }

    private static NbtList<String> strings(String... values) {
        return new NbtList<>(NbtType.STRING, values);
    }

    /**
     * The order in which the client applies traits of these names as sent: the order of a libc++ unordered_map filled in this order in digestServerBlockProperties, then copied by BlockDefinition's copy constructor, whose copy BlockDefinitionGroup::registerBlockFromDefinition walks.
     * With 3 or more traits, it can differ from the list order.
     */
    static List<String> order(List<String> names) {
        HashTable sent = new HashTable();
        names.forEach(sent::insert);
        HashTable copy = new HashTable();
        copy.rehash(sent.buckets.length);
        sent.keys().forEach(copy::insert);
        return copy.keys();
    }

    // The parts of libc++'s __hash_table that decide its order, for a max_load_factor of 1 and tables that only grow
    private static final class HashTable {
        // The node before the first, which the bucket of the first node points to
        private final Node head = new Node(null, 0);
        // The node before each bucket's first node, or null
        private Node[] buckets = new Node[0];
        private int size;

        // __emplace_unique_key_args
        void insert(String key) {
            long hash = hash(key);
            if (this.buckets.length > 0 && this.buckets[constrain(hash, this.buckets.length)] != null) {
                int bucket = constrain(hash, this.buckets.length);
                for (Node node = this.buckets[bucket].next; node != null && (node.hash == hash || constrain(node.hash, this.buckets.length) == bucket); node = node.next) {
                    if (node.hash == hash && node.key.equals(key)) {
                        return;
                    }
                }
            }
            if (this.size + 1 > this.buckets.length) {
                int count = this.buckets.length;
                this.rehash(Math.max(2 * count + (count > 2 && Long.bitCount(count) == 1 ? 0 : 1), this.size + 1));
            }
            Node node = new Node(key, hash);
            int bucket = constrain(hash, this.buckets.length);
            Node before = this.buckets[bucket];
            if (before == null) {
                node.next = this.head.next;
                this.head.next = node;
                this.buckets[bucket] = this.head;
                if (node.next != null) {
                    this.buckets[constrain(node.next.hash, this.buckets.length)] = node;
                }
            } else {
                node.next = before.next;
                before.next = node;
            }
            this.size++;
        }

        // __rehash_unique
        void rehash(int count) {
            if (count == 1) {
                count = 2;
            } else if (Long.bitCount(count) > 1) {
                count = nextPrime(count);
            }
            if (count > this.buckets.length) {
                this.reorder(count);
            }
        }

        // __do_rehash: a node whose bucket already has nodes earlier in the list moves to the front of them.
        private void reorder(int count) {
            this.buckets = new Node[count];
            Node before = this.head;
            Node node = before.next;
            if (node == null) {
                return;
            }
            int previousBucket = constrain(node.hash, count);
            this.buckets[previousBucket] = before;
            for (before = node, node = node.next; node != null; node = before.next) {
                int bucket = constrain(node.hash, count);
                if (bucket == previousBucket) {
                    before = node;
                } else if (this.buckets[bucket] == null) {
                    this.buckets[bucket] = before;
                    before = node;
                    previousBucket = bucket;
                } else {
                    before.next = node.next;
                    node.next = this.buckets[bucket].next;
                    this.buckets[bucket].next = node;
                }
            }
        }

        List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Node node = this.head.next; node != null; node = node.next) {
                keys.add(node.key);
            }
            return Collections.unmodifiableList(keys);
        }

        private static int constrain(long hash, int count) {
            return (int) (Long.bitCount(count) == 1 ? hash & (count - 1) : Long.remainderUnsigned(hash, count));
        }

        private static int nextPrime(int n) {
            for (int candidate = n; ; candidate++) {
                boolean prime = candidate > 1;
                for (int d = 2; prime && d * d <= candidate; d++) {
                    prime = candidate % d != 0;
                }
                if (prime) {
                    return candidate;
                }
            }
        }

        private static final class Node {
            final String key;
            final long hash;
            Node next;

            Node(String key, long hash) {
                this.key = key;
                this.hash = hash;
            }
        }
    }

    private static final long K0 = 0xc3a5c85c97cb3127L;
    private static final long K1 = 0xb492b66fbe98f273L;
    private static final long K2 = 0x9ae16a3b2f90404fL;
    private static final long K3 = 0xc949d7c7509e6557L;
    private static final long MUL = 0x9ddfea08eb382d69L;

    // libc++'s std::hash<std::string> on 64 bits, __murmur2_or_cityhash::__hash_len_17_to_32, which covers the trait names: they are 20 to 29 bytes long.
    static long hash(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        if (length < 17 || length > 32) {
            throw new IllegalArgumentException(key);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long a = buffer.getLong(0) * K1;
        long b = buffer.getLong(8);
        long c = buffer.getLong(length - 8) * K2;
        long d = buffer.getLong(length - 16) * K0;
        long u = Long.rotateRight(a - b, 43) + Long.rotateRight(c, 30) + d;
        long v = a + Long.rotateRight(b ^ K3, 20) - c + length;
        long x = (u ^ v) * MUL;
        x ^= x >>> 47;
        long y = (v ^ x) * MUL;
        y ^= y >>> 47;
        return y * MUL;
    }
}
