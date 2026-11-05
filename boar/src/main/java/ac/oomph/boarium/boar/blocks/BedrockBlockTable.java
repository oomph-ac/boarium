package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The Bedrock block palette with each state's collision boxes and block properties from Bedrock Dedicated Server, for the state alone with no world around it, and each block's default state, read from the table the blockdata module generates from Bedrock data.
 * Entries with the same name are next to each other.
 * In a protocol's table, a palette index is the runtime ID its clients use when the server adds no custom blocks and StartGame does not ask for hashed IDs; with hashed IDs, the client uses the network hash instead.
 */
public final class BedrockBlockTable {
    public static final String RESOURCE = "/ac/oomph/boarium/boar/blocks/bedrock_blocks.26_30.bin.gz";
    // The game version of each protocol's table, as Geyser names its palettes. Protocol 2169 (26.45) has no Bedrock data of its own, and Geyser gives it the 26.40 palette.
    static final Map<Integer, String> VERSIONS = Map.of(924, "26_0", 944, "26_10", 975, "26_20", 1001, "26_30", 2168, "26_40", 2169, "26_40", 2193, "26_50", 2225, "26_60");
    private static final Map<String, BedrockBlockTable> LOADED = new ConcurrentHashMap<>();
    private static final int MAGIC = 0x424E4254;
    private static final int FORMAT = 2;

    public static final int CAN_CONTAIN_LIQUID_SOURCE = 1;
    public static final int SOLID = 2;

    private static final byte TAG_BYTE = 1;
    private static final byte TAG_INT = 3;
    private static final byte TAG_STRING = 8;

    private final int protocol;
    private final String sourceVersion;
    private final int blockVersion;

    private final float[][] shapes;

    private final String[] names;
    private final NbtMap[] states;
    private final int[] hashes;
    private final char[] stateShape;
    private final byte[] flags;
    private final float[] hardness;
    private final float[] friction;

    private final int[] sortedHashes;
    private final char[] sortedHashIndices;
    private final Map<Key, Integer> byNameAndStates;
    private final Map<String, Integer> firstByName;
    private final Map<String, Integer> countByName;
    private final Map<String, Integer> defaultByName;

    private static BedrockBlockTable read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a Bedrock block table");
        }
        int format = in.readInt();
        if (format != FORMAT) {
            throw new IOException("Bedrock block table format " + format + ", expected " + FORMAT + ": regenerate it with ./gradlew :blockdata:generateBedrockBlocks");
        }
        int protocol = in.readInt();
        String sourceVersion = in.readUTF();
        int blockVersion = in.readInt();

        String[] strings = new String[count(in, 0xFFFF, "strings")];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = in.readUTF();
        }

        float[][] shapes = new float[count(in, 0xFFFF, "shapes")][];
        for (int s = 0; s < shapes.length; s++) {
            shapes[s] = new float[in.readUnsignedByte() * 6];
            for (int i = 0; i < shapes[s].length; i++) {
                shapes[s][i] = in.readFloat();
            }
        }

        int size = count(in, 0xFFFF, "palette entries");
        String[] names = new String[size];
        NbtMap[] states = new NbtMap[size];
        int[] hashes = new int[size];
        char[] stateShape = new char[size];
        byte[] flags = new byte[size];
        float[] hardness = new float[size];
        float[] friction = new float[size];
        int blocks = 0;
        for (int i = 0; i < size; i++) {
            names[i] = string(in, strings);
            hashes[i] = in.readInt();
            int count = in.readUnsignedByte();
            NbtMapBuilder builder = NbtMap.builder();
            for (int s = 0; s < count; s++) {
                String key = string(in, strings);
                Object value = switch (in.readByte()) {
                    case TAG_BYTE -> in.readByte();
                    case TAG_INT -> in.readInt();
                    case TAG_STRING -> string(in, strings);
                    default -> throw new IOException("Palette entry " + i + " has an unknown NBT tag type");
                };
                if (builder.put(key, value) != null) {
                    throw new IOException("Palette entry " + i + " lists the state " + key + " twice");
                }
            }
            states[i] = builder.build();
            stateShape[i] = (char) in.readUnsignedShort();
            if (stateShape[i] >= shapes.length) {
                throw new IOException("Palette entry " + i + " uses missing shape " + (int) stateShape[i]);
            }
            flags[i] = in.readByte();
            if ((flags[i] & ~(CAN_CONTAIN_LIQUID_SOURCE | SOLID)) != 0) {
                throw new IOException("Palette entry " + i + " has unknown flags " + flags[i]);
            }
            hardness[i] = in.readFloat();
            friction[i] = in.readFloat();
            if (i == 0 || !names[i].equals(names[i - 1])) {
                blocks++;
            }
        }

        // One default per name, in palette order
        if (count(in, 0xFFFF, "default states") != blocks) {
            throw new IOException("Bedrock block table lists default states for a different number of blocks than its palette has");
        }
        int[] defaults = new int[blocks];
        for (int b = 0; b < blocks; b++) {
            defaults[b] = in.readUnsignedShort();
        }

        // Reading to the end makes GZIPInputStream check its CRC, so a corrupt table fails here instead of loading with wrong answers.
        if (in.read() != -1) {
            throw new IOException("Bedrock block table has data after its end");
        }
        return new BedrockBlockTable(protocol, sourceVersion, blockVersion, shapes, names, states, hashes, stateShape, flags, hardness, friction, defaults);
    }

    // Takes the arrays without copying them. defaults has the palette index of each name's default state, in palette order.
    BedrockBlockTable(int protocol, String sourceVersion, int blockVersion, float[][] shapes, String[] names, NbtMap[] states, int[] hashes, char[] stateShape, byte[] flags, float[] hardness, float[] friction, int[] defaults) {
        this.protocol = protocol;
        this.sourceVersion = sourceVersion;
        this.blockVersion = blockVersion;
        this.shapes = shapes;
        this.names = names;
        this.states = states;
        this.hashes = hashes;
        this.stateShape = stateShape;
        this.flags = flags;
        this.hardness = hardness;
        this.friction = friction;

        int size = names.length;
        if (size > 0xFFFF) {
            throw new IllegalArgumentException("Bedrock block table has " + size + " palette entries, more than its char indexes hold");
        }
        byNameAndStates = new HashMap<>(size * 2);
        firstByName = new HashMap<>();
        countByName = new HashMap<>();
        for (int i = 0; i < size; i++) {
            if (byNameAndStates.put(new Key(names[i], states[i]), i) != null) {
                throw new IllegalArgumentException("Palette entry " + i + " " + names[i] + " " + states[i] + " is listed twice");
            }
            if (i == 0 || !names[i].equals(names[i - 1])) {
                if (firstByName.putIfAbsent(names[i], i) != null) {
                    throw new IllegalArgumentException("Palette entry " + i + " " + names[i] + " is apart from the earlier entries with its name");
                }
            }
            countByName.merge(names[i], 1, Integer::sum);
        }

        if (defaults.length != firstByName.size()) {
            throw new IllegalArgumentException("Bedrock block table lists default states for a different number of blocks than its palette has");
        }
        defaultByName = new HashMap<>();
        for (int i = 0, b = 0; i < size; i += countByName.get(names[i]), b++) {
            int index = defaults[b];
            if (index < i || index >= i + countByName.get(names[i])) {
                throw new IllegalArgumentException("The default state of " + names[i] + " is palette entry " + index + ", which is not one of its entries");
            }
            defaultByName.put(names[i], index);
        }

        long[] byHash = new long[size];
        for (int i = 0; i < size; i++) {
            byHash[i] = (long) hashes[i] << 32 | i;
        }
        Arrays.sort(byHash);
        sortedHashes = new int[size];
        sortedHashIndices = new char[size];
        for (int i = 0; i < size; i++) {
            sortedHashes[i] = (int) (byHash[i] >> 32);
            sortedHashIndices[i] = (char) byHash[i];
            if (i > 0 && sortedHashes[i] == sortedHashes[i - 1]) {
                throw new IllegalArgumentException("Two palette entries have the network hash " + Integer.toUnsignedString(sortedHashes[i]));
            }
        }
    }

    private static int count(DataInputStream in, int max, String what) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) {
            throw new IOException("Bedrock block table lists " + count + " " + what);
        }
        return count;
    }

    private static String string(DataInputStream in, String[] strings) throws IOException {
        int index = in.readUnsignedShort();
        if (index >= strings.length) {
            throw new IOException("Bedrock block table points at missing string " + index);
        }
        return strings[index];
    }

    private record Key(String name, NbtMap states) {
    }

    private static String resource(String version) {
        return "/ac/oomph/boarium/boar/blocks/bedrock_blocks." + version + ".bin.gz";
    }

    // The table from RESOURCE, loaded once on first use
    public static BedrockBlockTable get() {
        return LOADED.computeIfAbsent(RESOURCE, BedrockBlockTable::load);
    }

    // The table of a protocol's client, loaded once on first use, or null for a protocol with none
    public static BedrockBlockTable forProtocol(int protocol) {
        String version = VERSIONS.get(protocol);
        return version == null ? null : LOADED.computeIfAbsent(resource(version), BedrockBlockTable::load);
    }

    // The game version of a protocol's table, such as "26_30", or null for a protocol with none
    public static String version(int protocol) {
        return VERSIONS.get(protocol);
    }

    public static BedrockBlockTable load() {
        return load(RESOURCE);
    }

    static BedrockBlockTable load(String resource) {
        try (InputStream in = BedrockBlockTable.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Bedrock block table " + resource, e);
        }
    }

    public static BedrockBlockTable load(InputStream in) throws IOException {
        try {
            return read(new DataInputStream(new BufferedInputStream(new GZIPInputStream(in))));
        } catch (RuntimeException e) {
            throw new IOException("Corrupt Bedrock block table", e);
        }
    }

    // Writes the table as gzip in the format load reads, as the blockdata module writes it
    void write(OutputStream out) throws IOException {
        Map<String, Integer> strings = new LinkedHashMap<>();
        List<float[]> shapeList = new ArrayList<>();
        Map<String, Integer> shapeIds = new HashMap<>();
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyBytes);
        int[] entryShapes = new int[this.names.length];
        for (int i = 0; i < entryShapes.length; i++) {
            float[] shape = this.collision(i);
            entryShapes[i] = shapeIds.computeIfAbsent(Arrays.toString(shape), key -> {
                shapeList.add(shape);
                return shapeList.size() - 1;
            });
        }
        body.writeInt(shapeList.size());
        for (float[] shape : shapeList) {
            body.writeByte(shape.length / 6);
            for (float value : shape) {
                body.writeFloat(value);
            }
        }
        body.writeInt(this.names.length);
        for (int i = 0; i < this.names.length; i++) {
            body.writeShort(index(strings, this.names[i]));
            body.writeInt(this.hashes[i]);
            body.writeByte(this.states[i].size());
            for (Map.Entry<String, Object> state : this.states[i].entrySet()) {
                body.writeShort(index(strings, state.getKey()));
                switch (state.getValue()) {
                    case Byte value -> {
                        body.writeByte(TAG_BYTE);
                        body.writeByte(value);
                    }
                    case Integer value -> {
                        body.writeByte(TAG_INT);
                        body.writeInt(value);
                    }
                    case String value -> {
                        body.writeByte(TAG_STRING);
                        body.writeShort(index(strings, value));
                    }
                    default -> throw new IOException("Palette entry " + i + " has a state of type " + state.getValue().getClass().getSimpleName());
                }
            }
            body.writeShort(entryShapes[i]);
            body.writeByte(this.flags[i]);
            body.writeFloat(this.hardness[i]);
            body.writeFloat(this.friction[i]);
        }
        body.writeInt(this.firstByName.size());
        for (int i = 0; i < this.names.length; i += this.countByName.get(this.names[i])) {
            body.writeShort(this.defaultByName.get(this.names[i]));
        }

        DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out));
        data.writeInt(MAGIC);
        data.writeInt(FORMAT);
        data.writeInt(this.protocol);
        data.writeUTF(this.sourceVersion);
        data.writeInt(this.blockVersion);
        data.writeInt(strings.size());
        for (String value : strings.keySet()) {
            data.writeUTF(value);
        }
        bodyBytes.writeTo(data);
        data.close();
    }

    private static int index(Map<String, Integer> strings, String value) {
        return strings.computeIfAbsent(value, key -> strings.size());
    }

    // The Bedrock protocol version of the palette, or -1 for BedrockBlockUnion's table
    public int protocol() {
        return protocol;
    }

    // The BedrockData version the table came from, such as "1.26.33.1", or a comma list of them for BedrockBlockUnion's table
    public String sourceVersion() {
        return sourceVersion;
    }

    // The version every palette entry carries in its NBT, or -1 for BedrockBlockUnion's table
    public int blockVersion() {
        return blockVersion;
    }

    public int paletteSize() {
        return names.length;
    }

    // The Bedrock name of a palette entry, such as "minecraft:stone"
    public String name(int index) {
        return names[index];
    }

    public NbtMap states(int index) {
        return states[index];
    }

    // FNV-1a 32 of the name and states (-2 for minecraft:unknown), which is the runtime ID with hashed IDs
    public int networkHash(int index) {
        return hashes[index];
    }

    // The palette index of a network hash, or -1
    public int indexOfHash(int networkHash) {
        int i = Arrays.binarySearch(sortedHashes, networkHash);
        return i < 0 ? -1 : sortedHashIndices[i];
    }

    // The palette index of a name and its complete states, or -1. Each value must have the NBT type of the palette's (Byte or Integer), so build the states from states(i).toBuilder().
    public int index(String name, NbtMap states) {
        Integer index = byNameAndStates.get(new Key(name, states));
        return index == null ? -1 : index;
    }

    // The palette index of the first entry with this name, such as "minecraft:stone", or -1
    public int firstIndex(String name) {
        Integer first = firstByName.get(name);
        return first == null ? -1 : first;
    }

    // The palette index of the default state of the block with this name, as Bedrock Dedicated Server gives it, or -1
    public int defaultIndex(String name) {
        Integer index = defaultByName.get(name);
        return index == null ? -1 : index;
    }

    // How many entries have this name, or 0
    public int stateCount(String name) {
        return countByName.getOrDefault(name, 0);
    }

    // The collision boxes as minX, minY, minZ, maxX, maxY, maxZ per box, in Bedrock Dedicated Server's order. The array is shared: do not change it.
    // They are wrong where the game picks the shape from more than the state: doors (one box for every state), double chests, piston arms, scaffolding and powder snow (no boxes; the last two depend on the entity in them), and bamboo, pointed dripstone and sulfur spikes (offset by position); before 26.50 also stair corners and the connections of fences, panes and bars, and before 26.60 those of chorus plants.
    public float[] collision(int index) {
        return shapes[stateShape[index]];
    }

    public int flags(int index) {
        return flags[index];
    }

    public boolean canContainLiquidSource(int index) {
        return (flags[index] & CAN_CONTAIN_LIQUID_SOURCE) != 0;
    }

    public boolean isSolid(int index) {
        return (flags[index] & SOLID) != 0;
    }

    // -1 for blocks such as bedrock and barrier that mining never breaks; liquids have 100 instead
    public float hardness(int index) {
        return hardness[index];
    }

    public float friction(int index) {
        return friction[index];
    }
}
