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
 * The Bedrock block palette with each state's collision boxes and block properties from Bedrock Dedicated Server, for the state alone with no world around it, and each block's default state, read from the table the blockdata module generates from Bedrock data.
 * Entries with the same name are next to each other.
 * A palette index is the runtime ID a client uses when the server adds no custom blocks and StartGame does not ask for hashed IDs; with hashed IDs, the client uses the network hash instead.
 */
public final class BedrockBlockTable {
    public static final String RESOURCE = "/ac/oomph/boarium/boar/blocks/bedrock_blocks.26_30.bin.gz";
    private static final int MAGIC = 0x424E4254;
    private static final int FORMAT = 2;
    private static final int JAVA_TABLE_MAGIC = 0x42425432;

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

    private BedrockBlockTable(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic == JAVA_TABLE_MAGIC) {
            throw new IOException("This is the Java block table (BBT2), not the Bedrock one");
        }
        if (magic != MAGIC) {
            throw new IOException("Not a Bedrock block table");
        }
        int format = in.readInt();
        if (format != FORMAT) {
            throw new IOException("Bedrock block table format " + format + ", expected " + FORMAT + ": regenerate it with ./gradlew :blockdata:generateBedrockBlocks");
        }
        protocol = in.readInt();
        sourceVersion = in.readUTF();
        blockVersion = in.readInt();

        String[] strings = new String[count(in, 0xFFFF, "strings")];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = in.readUTF();
        }

        shapes = new float[count(in, 0xFFFF, "shapes")][];
        for (int s = 0; s < shapes.length; s++) {
            shapes[s] = new float[in.readUnsignedByte() * 6];
            for (int i = 0; i < shapes[s].length; i++) {
                shapes[s][i] = in.readFloat();
            }
        }

        int size = count(in, 0xFFFF, "palette entries");
        names = new String[size];
        states = new NbtMap[size];
        hashes = new int[size];
        stateShape = new char[size];
        flags = new byte[size];
        hardness = new float[size];
        friction = new float[size];
        byNameAndStates = new HashMap<>(size * 2);
        firstByName = new HashMap<>();
        countByName = new HashMap<>();
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
            if (byNameAndStates.put(new Key(names[i], states[i]), i) != null) {
                throw new IOException("Palette entry " + i + " " + names[i] + " " + states[i] + " is listed twice");
            }
            if (i == 0 || !names[i].equals(names[i - 1])) {
                if (firstByName.putIfAbsent(names[i], i) != null) {
                    throw new IOException("Palette entry " + i + " " + names[i] + " is apart from the earlier entries with its name");
                }
            }
            countByName.merge(names[i], 1, Integer::sum);
        }

        // One default per name, in palette order, each one of its name's entries
        if (count(in, 0xFFFF, "default states") != firstByName.size()) {
            throw new IOException("Bedrock block table lists default states for a different number of blocks than its palette has");
        }
        defaultByName = new HashMap<>();
        for (int i = 0; i < size; i += countByName.get(names[i])) {
            int index = in.readUnsignedShort();
            if (index < i || index >= i + countByName.get(names[i])) {
                throw new IOException("The default state of " + names[i] + " is palette entry " + index + ", which is not one of its entries");
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
                throw new IOException("Two palette entries have the network hash " + Integer.toUnsignedString(sortedHashes[i]));
            }
        }

        // Reading to the end makes GZIPInputStream check its CRC, so a corrupt table fails here instead of loading with wrong answers.
        if (in.read() != -1) {
            throw new IOException("Bedrock block table has data after its end");
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

    private static final class Shared {
        static final BedrockBlockTable TABLE = load();
    }

    // The table from RESOURCE, loaded once on first use
    public static BedrockBlockTable get() {
        return Shared.TABLE;
    }

    public static BedrockBlockTable load() {
        try (InputStream in = BedrockBlockTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Bedrock block table " + RESOURCE, e);
        }
    }

    public static BedrockBlockTable load(InputStream in) throws IOException {
        try {
            return new BedrockBlockTable(new DataInputStream(new BufferedInputStream(new GZIPInputStream(in))));
        } catch (RuntimeException e) {
            throw new IOException("Corrupt Bedrock block table", e);
        }
    }

    // The Bedrock protocol version of the palette
    public int protocol() {
        return protocol;
    }

    // The BedrockData version the table came from, such as "1.26.33.1"
    public String sourceVersion() {
        return sourceVersion;
    }

    // The version every palette entry carries in its NBT
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
    // They are wrong where the game picks the shape from more than the state: doors (one box for every state), stair corners, the connections of fences, panes, bars and chorus plants, double chests, piston arms, scaffolding and powder snow (no boxes; the last two depend on the entity in them), and bamboo, pointed dripstone and sulfur spikes (offset by position).
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
