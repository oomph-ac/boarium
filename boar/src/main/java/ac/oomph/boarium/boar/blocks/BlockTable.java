package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Geyser's answers about Java block states and one Bedrock block palette, read from the table the blockdata module generates.
 * A Java state is its Java ID. When the server adds no custom blocks, a palette index is the runtime ID a client uses when StartGame does not ask for hashed IDs; with hashed IDs, the client uses the network hash instead.
 * A property ID stands for one property: a name with its values in index order, since different properties share a name (facing has 4 values on stairs and 6 on pistons).
 */
public final class BlockTable {
    public static final String RESOURCE = "/ac/oomph/boarium/boar/blocks/block_table.26_30.bin.gz";
    private static final int MAGIC = 0x42425431;

    public static final int FACE_STURDY = 1;
    public static final int WATERLOGGED = 2;
    public static final int REPLACEABLE = 4;

    // Categories, as the block lists of Boar's BlockMappings
    public static final int STAIRS = 1;
    public static final int LEAVES = 1 << 1;
    public static final int SHULKER = 1 << 2;
    public static final int FENCE_GATE = 1 << 3;
    public static final int WALL = 1 << 4;
    public static final int FENCE = 1 << 5;
    public static final int CLIMBABLE = 1 << 6;
    public static final int BED = 1 << 7;
    public static final int CHEST = 1 << 8;
    public static final int DOOR = 1 << 9;
    public static final int TRAPDOOR = 1 << 10;
    public static final int CANDLE = 1 << 11;
    public static final int SIGN = 1 << 12;
    public static final int BUTTON = 1 << 13;
    public static final int BARS = 1 << 14;
    public static final int LANTERN = 1 << 15;
    public static final int ANVIL = 1 << 16;
    public static final int CAULDRON = 1 << 17;

    private static final byte TAG_BYTE = 1;
    private static final byte TAG_INT = 3;
    private static final byte TAG_STRING = 8;

    private final int protocol;
    private final String geyserBuild;

    private final String[] propertyNames;
    private final String[][] propertyValues;

    private final String[] blockNames;
    private final int[] blockFirstState;
    private final int[] blockStateCount;
    private final int[] blockDefaultState;
    private final float[] blockDestroyTime;
    private final int[] blockCategories;
    private final int[][] blockProperties;
    // How many states apart two values of each property are: the product of the value counts of the properties after it.
    private final int[][] blockStrides;
    private final Map<String, Integer> blockByName;

    private final float[][] shapes;

    private final char[] stateBlock;
    private final char[] stateShape;
    private final byte[] stateFlags;

    private final String[] paletteNames;
    private final int[] paletteHashes;
    private final int[] paletteStateStart;
    private final String[] paletteStateKeys;
    private final Object[] paletteStateValues;
    private final int[] sortedHashes;
    private final char[] sortedHashIndices;

    private final char[] javaToPalette;
    private final int[] paletteToJava;

    private BlockTable(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a block table");
        }
        protocol = in.readInt();
        geyserBuild = in.readUTF();

        String[] strings = new String[in.readInt()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = in.readUTF();
        }

        int propertyCount = in.readInt();
        propertyNames = new String[propertyCount];
        propertyValues = new String[propertyCount][];
        for (int p = 0; p < propertyCount; p++) {
            propertyNames[p] = strings[in.readUnsignedShort()];
            propertyValues[p] = new String[in.readUnsignedByte()];
            for (int v = 0; v < propertyValues[p].length; v++) {
                propertyValues[p][v] = strings[in.readUnsignedShort()];
            }
        }

        int blockCount = in.readInt();
        blockNames = new String[blockCount];
        blockFirstState = new int[blockCount];
        blockStateCount = new int[blockCount];
        blockDefaultState = new int[blockCount];
        blockDestroyTime = new float[blockCount];
        blockCategories = new int[blockCount];
        blockProperties = new int[blockCount][];
        blockStrides = new int[blockCount][];
        blockByName = new HashMap<>(blockCount * 2);
        int nextState = 0;
        for (int b = 0; b < blockCount; b++) {
            blockNames[b] = strings[in.readUnsignedShort()];
            blockFirstState[b] = nextState;
            blockStateCount[b] = in.readInt();
            blockDefaultState[b] = nextState + in.readInt();
            blockDestroyTime[b] = in.readFloat();
            blockCategories[b] = in.readInt();
            int[] properties = new int[in.readUnsignedByte()];
            int[] strides = new int[properties.length];
            for (int p = 0; p < properties.length; p++) {
                properties[p] = in.readUnsignedShort();
            }
            int stride = 1;
            for (int p = properties.length - 1; p >= 0; p--) {
                strides[p] = stride;
                stride *= propertyValues[properties[p]].length;
            }
            blockProperties[b] = properties;
            blockStrides[b] = strides;
            blockByName.put(blockNames[b], b);
            nextState += blockStateCount[b];
        }

        shapes = new float[in.readInt()][];
        for (int s = 0; s < shapes.length; s++) {
            shapes[s] = new float[in.readUnsignedByte() * 6];
            for (int i = 0; i < shapes[s].length; i++) {
                shapes[s][i] = in.readFloat();
            }
        }

        int stateCount = in.readInt();
        if (stateCount != nextState) {
            throw new IOException("Block table lists " + stateCount + " states, its blocks hold " + nextState);
        }
        stateBlock = new char[stateCount];
        stateShape = new char[stateCount];
        stateFlags = new byte[stateCount];
        for (int b = 0; b < blockCount; b++) {
            Arrays.fill(stateBlock, blockFirstState[b], blockFirstState[b] + blockStateCount[b], (char) b);
        }
        for (int id = 0; id < stateCount; id++) {
            stateShape[id] = (char) in.readUnsignedShort();
            stateFlags[id] = in.readByte();
            if (stateShape[id] >= shapes.length) {
                throw new IOException("Java state " + id + " uses missing shape " + (int) stateShape[id]);
            }
        }

        int paletteCount = in.readInt();
        paletteNames = new String[paletteCount];
        paletteHashes = new int[paletteCount];
        paletteStateStart = new int[paletteCount + 1];
        String[] keys = new String[paletteCount * 4];
        Object[] values = new Object[keys.length];
        int next = 0;
        for (int i = 0; i < paletteCount; i++) {
            paletteNames[i] = strings[in.readUnsignedShort()];
            paletteHashes[i] = in.readInt();
            paletteStateStart[i] = next;
            int count = in.readUnsignedByte();
            if (next + count > keys.length) {
                keys = Arrays.copyOf(keys, Math.max(keys.length * 2, next + count));
                values = Arrays.copyOf(values, keys.length);
            }
            for (int s = 0; s < count; s++, next++) {
                keys[next] = strings[in.readUnsignedShort()];
                values[next] = switch (in.readByte()) {
                    case TAG_BYTE -> in.readByte();
                    case TAG_INT -> in.readInt();
                    case TAG_STRING -> strings[in.readUnsignedShort()];
                    default -> throw new IOException("Palette entry " + i + " has an unknown NBT tag type");
                };
            }
        }
        paletteStateStart[paletteCount] = next;
        paletteStateKeys = Arrays.copyOf(keys, next);
        paletteStateValues = Arrays.copyOf(values, next);

        long[] byHash = new long[paletteCount];
        for (int i = 0; i < paletteCount; i++) {
            byHash[i] = (long) paletteHashes[i] << 32 | i;
        }
        Arrays.sort(byHash);
        sortedHashes = new int[paletteCount];
        sortedHashIndices = new char[paletteCount];
        for (int i = 0; i < paletteCount; i++) {
            sortedHashes[i] = (int) (byHash[i] >> 32);
            sortedHashIndices[i] = (char) byHash[i];
        }

        // Several Java states share a palette entry; as in Boar's Geyser adapter, the highest Java ID wins, and an entry without one maps to air (0).
        javaToPalette = new char[stateCount];
        paletteToJava = new int[paletteCount];
        for (int id = 0; id < stateCount; id++) {
            javaToPalette[id] = (char) in.readUnsignedShort();
            paletteToJava[javaToPalette[id]] = id;
        }

        // Reading to the end makes GZIPInputStream check its CRC, so a corrupt table fails here instead of loading with wrong answers.
        if (in.read() != -1) {
            throw new IOException("Block table has data after its end");
        }
    }

    private static final class Shared {
        static final BlockTable TABLE = load();
    }

    // The table from RESOURCE, loaded once on first use
    public static BlockTable get() {
        return Shared.TABLE;
    }

    public static BlockTable load() {
        try (InputStream in = BlockTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read block table " + RESOURCE, e);
        }
    }

    public static BlockTable load(InputStream in) throws IOException {
        try {
            return new BlockTable(new DataInputStream(new BufferedInputStream(new GZIPInputStream(in))));
        } catch (RuntimeException e) {
            throw new IOException("Corrupt block table", e);
        }
    }

    // The Bedrock protocol version of the palette
    public int protocol() {
        return protocol;
    }

    // The Geyser build the table came from, such as "2.11.3-b1248 (git-master-751d2aa)"
    public String geyserBuild() {
        return geyserBuild;
    }

    public int stateCount() {
        return stateBlock.length;
    }

    public int blockCount() {
        return blockNames.length;
    }

    // The block index of a Java block name such as "minecraft:stone", or -1
    public int block(String name) {
        Integer block = blockByName.get(name);
        return block == null ? -1 : block;
    }

    public int blockOf(int state) {
        return stateBlock[state];
    }

    public String blockName(int block) {
        return blockNames[block];
    }

    public int firstState(int block) {
        return blockFirstState[block];
    }

    public int blockStateCount(int block) {
        return blockStateCount[block];
    }

    // Geyser's default state of the block
    public int defaultState(int block) {
        return blockDefaultState[block];
    }

    public float destroyTime(int block) {
        return blockDestroyTime[block];
    }

    public int categories(int block) {
        return blockCategories[block];
    }

    // The block's property names, in Geyser's order
    public String[] propertyNames(int block) {
        int[] properties = blockProperties[block];
        String[] names = new String[properties.length];
        for (int p = 0; p < properties.length; p++) {
            names[p] = propertyNames[properties[p]];
        }
        return names;
    }

    // The values of a property of the block in index order, or null if the block does not have it
    public String[] propertyValues(int block, String property) {
        int p = propertyPosition(block, property);
        return p < 0 ? null : propertyValues[blockProperties[block][p]].clone();
    }

    public String name(int state) {
        return blockNames[stateBlock[state]];
    }

    // The ID of the property with this name and these values in index order, or -1 if no block has it
    public int property(String name, String... values) {
        for (int p = 0; p < propertyNames.length; p++) {
            if (propertyNames[p].equals(name) && Arrays.equals(propertyValues[p], values)) {
                return p;
            }
        }
        return -1;
    }

    // The state's value of its block's property with this name, such as "north" for facing, or null if its block has none
    public String value(int state, String property) {
        int block = stateBlock[state];
        return valueAt(state, block, propertyPosition(block, property));
    }

    // The state's value of a property ID, or null if its block does not have that property
    public String value(int state, int property) {
        int block = stateBlock[state];
        return valueAt(state, block, propertyPosition(block, property));
    }

    // The state with one property changed, by name, or -1 if its block does not have the property or the value
    public int with(int state, String property, String value) {
        int block = stateBlock[state];
        return withAt(state, block, propertyPosition(block, property), value);
    }

    // The state with one property changed, by property ID, or -1 if its block does not have that property or the value
    public int with(int state, int property, String value) {
        int block = stateBlock[state];
        return withAt(state, block, propertyPosition(block, property), value);
    }

    // The index of the state's value of a property ID, in that property's values, or -1 if its block does not have that property
    public int valueIndex(int state, int property) {
        int block = stateBlock[state];
        int position = propertyPosition(block, property);
        if (position < 0) {
            return -1;
        }
        return (state - blockFirstState[block]) / blockStrides[block][position] % propertyValues[blockProperties[block][position]].length;
    }

    // The state with one property ID changed to the value at this index, or -1 if its block does not have that property or the index
    public int withIndex(int state, int property, int index) {
        int block = stateBlock[state];
        return withIndexAt(state, block, propertyPosition(block, property), index);
    }

    private String valueAt(int state, int block, int position) {
        if (position < 0) {
            return null;
        }
        String[] values = propertyValues[blockProperties[block][position]];
        return values[(state - blockFirstState[block]) / blockStrides[block][position] % values.length];
    }

    private int withAt(int state, int block, int position, String value) {
        if (position < 0) {
            return -1;
        }
        return withIndexAt(state, block, position, Arrays.asList(propertyValues[blockProperties[block][position]]).indexOf(value));
    }

    private int withIndexAt(int state, int block, int position, int target) {
        if (position < 0) {
            return -1;
        }
        int count = propertyValues[blockProperties[block][position]].length;
        if (target < 0 || target >= count) {
            return -1;
        }
        int stride = blockStrides[block][position];
        int current = (state - blockFirstState[block]) / stride % count;
        return state + (target - current) * stride;
    }

    private int propertyPosition(int block, String property) {
        int[] properties = blockProperties[block];
        for (int p = 0; p < properties.length; p++) {
            if (propertyNames[properties[p]].equals(property)) {
                return p;
            }
        }
        return -1;
    }

    private int propertyPosition(int block, int property) {
        int[] properties = blockProperties[block];
        for (int p = 0; p < properties.length; p++) {
            if (properties[p] == property) {
                return p;
            }
        }
        return -1;
    }

    // The state's collision boxes as minX, minY, minZ, maxX, maxY, maxZ per box, by middle Y, lowest first. The array is shared: do not change it.
    public float[] collision(int state) {
        return shapes[stateShape[state]];
    }

    public int flags(int state) {
        return stateFlags[state];
    }

    public boolean isFaceSturdy(int state) {
        return (stateFlags[state] & FACE_STURDY) != 0;
    }

    public boolean isWaterlogged(int state) {
        return (stateFlags[state] & WATERLOGGED) != 0;
    }

    public boolean isReplaceable(int state) {
        return (stateFlags[state] & REPLACEABLE) != 0;
    }

    public int paletteSize() {
        return paletteNames.length;
    }

    // The palette index of a runtime ID, or -1 if the palette does not define it
    public int paletteIndex(int runtimeId, boolean hashed) {
        if (hashed) {
            int i = Arrays.binarySearch(sortedHashes, runtimeId);
            return i < 0 ? -1 : sortedHashIndices[i];
        }
        return runtimeId >= 0 && runtimeId < paletteNames.length ? runtimeId : -1;
    }

    public int runtimeId(int paletteIndex, boolean hashed) {
        return hashed ? paletteHashes[paletteIndex] : paletteIndex;
    }

    // The Bedrock name of a palette entry, such as "minecraft:stone"
    public String paletteName(int paletteIndex) {
        return paletteNames[paletteIndex];
    }

    public NbtMap paletteStates(int paletteIndex) {
        int start = paletteStateStart[paletteIndex];
        int end = paletteStateStart[paletteIndex + 1];
        if (start == end) {
            return NbtMap.EMPTY;
        }
        NbtMapBuilder builder = NbtMap.builder();
        for (int i = start; i < end; i++) {
            builder.put(paletteStateKeys[i], paletteStateValues[i]);
        }
        return builder.build();
    }

    // The palette's network_id: FNV-1a 32 of the name and states (-2 for minecraft:unknown), which is the runtime ID with hashed IDs
    public int networkHash(int paletteIndex) {
        return paletteHashes[paletteIndex];
    }

    public int toRuntime(int state, boolean hashed) {
        return runtimeId(javaToPalette[state], hashed);
    }

    // The Java state of a runtime ID; air (0) when the palette does not define the ID or no Java state uses it
    public int toJava(int runtimeId, boolean hashed) {
        int index = paletteIndex(runtimeId, hashed);
        return index < 0 ? 0 : paletteToJava[index];
    }
}
