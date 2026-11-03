package ac.oomph.boarium.blockdata;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes boar's Bedrock block table from the Bedrock data in blockdata/data/bedrock (see its README): the block palette, the dump of every block state from Bedrock Dedicated Server, and each block's default state.
 * They are joined by network hash, the FNV-1a 32 hash of a palette entry's name and states, which the dump lists as blockStateHash and the block types as defaultBlockStateHash.
 *
 * The table is gzip over big-endian DataOutputStream data, in this order:
 * magic "BNBT", format version, protocol, source version (UTF), block version;
 * strings (count, UTF each);
 * shapes (count; per shape: box count u8, then minX, minY, minZ, maxX, maxY, maxZ as floats per box), in order of first use by palette index, each with the dump's boxes in the dump's order;
 * palette entries in runtime ID order (count; per entry: name, network hash, state count u8, then per state: key, NBT tag type u8 (1 byte, 3 int, 8 string), value; then shape u16, flags u8, hardness and friction as floats);
 * default states (count; per block name, in palette order: the palette index of its default state, u16).
 * A name, key or string value is a u16 string index.
 */
public final class BedrockBlockGenerator {
    private static final int MAGIC = 0x424E4254;
    private static final int FORMAT = 2;
    private static final int PROTOCOL = 1001;
    // The BedrockData folder the files come from
    private static final String SOURCE_VERSION = "1.26.33.1";

    private static final int CAN_CONTAIN_LIQUID_SOURCE = 1;
    private static final int SOLID = 2;

    // The printed numbers readFloat did not read as printed, with the float it read instead
    private static final Map<String, Float> RESTORED = new TreeMap<>();

    private final Map<String, Integer> strings = new LinkedHashMap<>();

    private BedrockBlockGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: <Bedrock data folder> <output file>");
            System.exit(2);
        }
        Path data = Path.of(args[0]).toAbsolutePath();
        Path out = Path.of(args[1]).toAbsolutePath();

        byte[] table;
        try {
            table = new BedrockBlockGenerator().build(data.resolve("block_palette.nbt"), data.resolve("block_states.json.gz"), data.resolve("block_types.json.gz"));
        } catch (IllegalStateException e) {
            System.err.println("Bedrock block table not written: " + e.getMessage());
            System.exit(1);
            return;
        }

        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(gzip)) {
            zip.write(table);
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, gzip.toByteArray());
        System.out.println("Wrote " + out + " (" + Files.size(out) + " bytes)");
    }

    private record DumpState(String name, int hash, float[] boxes, boolean canContainLiquidSource, float hardness, float friction, boolean solid) {
    }

    private byte[] build(Path palettePath, Path dumpPath, Path typesPath) throws IOException {
        List<NbtMap> palette = readPalette(palettePath);
        List<DumpState> dump = readDump(dumpPath);
        Map<String, Integer> defaultHashes = readDefaults(typesPath);
        require(dump.size() == palette.size(), "The dump has " + dump.size() + " states, the palette " + palette.size());
        require(palette.size() <= 0xFFFF, "Palette has " + palette.size() + " entries, more than a u16 holds");

        int blockVersion = palette.getFirst().getInt("version");
        int[] hashes = new int[palette.size()];
        Map<Integer, Integer> byHash = new HashMap<>();
        long previousNameHash = 0;
        for (int i = 0; i < palette.size(); i++) {
            NbtMap entry = palette.get(i);
            String name = entry.getString("name");
            require(entry.containsKey("version", NbtType.INT), "Palette entry " + i + " " + name + " has no int version");
            require(entry.getInt("version") == blockVersion, "Palette entry " + i + " " + name + " has block version " + entry.getInt("version") + ", entry 0 has " + blockVersion);
            // The client sorts the palette by this hash and keeps the order of states with the same name, so the file must be in that order already.
            long nameHash = nameHash(name);
            require(i == 0 || Long.compareUnsigned(previousNameHash, nameHash) <= 0, "Palette entry " + i + " " + name + " is out of runtime ID order");
            previousNameHash = nameHash;
            // Bedrock gives minecraft:unknown the network hash -2 instead of its FNV-1a hash.
            hashes[i] = name.equals("minecraft:unknown") ? -2 : networkHash(name, entry.getCompound("states"));
            Integer other = byHash.put(hashes[i], i);
            require(other == null, "Palette entries " + other + " and " + i + " share the network hash " + Integer.toUnsignedString(hashes[i]));
        }

        DumpState[] joined = new DumpState[palette.size()];
        for (DumpState state : dump) {
            Integer i = byHash.get(state.hash);
            require(i != null, "Dump state " + state.name + " has the network hash " + Integer.toUnsignedString(state.hash) + ", which no palette entry has");
            require(joined[i] == null, "Two dump states have the network hash " + Integer.toUnsignedString(state.hash));
            require(state.name.equals(palette.get(i).getString("name")), "Dump state " + state.name + " has the network hash of palette entry " + i + " " + palette.get(i).getString("name"));
            joined[i] = state;
        }

        List<Integer> defaults = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.get(i).getString("name");
            if (i > 0 && name.equals(palette.get(i - 1).getString("name"))) {
                continue;
            }
            Integer hash = defaultHashes.get(name);
            require(hash != null, "The block types have no default state for " + name);
            Integer index = byHash.get(hash);
            require(index != null && palette.get(index).getString("name").equals(name), "The default state of " + name + " is not one of its palette entries");
            defaults.add(index);
        }
        require(defaults.size() == defaultHashes.size(), "The block types list " + defaultHashes.size() + " blocks, the palette " + defaults.size());

        Map<List<Float>, Integer> shapeIds = new HashMap<>();
        List<float[]> shapes = new ArrayList<>();
        int[] stateShapes = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            float[] boxes = joined[i].boxes;
            List<Float> key = new ArrayList<>(boxes.length);
            for (float value : boxes) {
                key.add(value);
            }
            stateShapes[i] = shapeIds.computeIfAbsent(key, k -> {
                shapes.add(boxes);
                return shapes.size() - 1;
            });
        }
        require(shapes.size() <= 0xFFFF, "More than 65535 shapes");

        // Every string goes into the table before anything that points at it.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);

        data.writeInt(shapes.size());
        for (float[] boxes : shapes) {
            require(boxes.length / 6 <= 0xFF, "A shape has more than 255 boxes");
            data.writeByte(boxes.length / 6);
            for (float value : boxes) {
                data.writeFloat(value);
            }
        }

        data.writeInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            NbtMap entry = palette.get(i);
            NbtMap states = entry.getCompound("states");
            require(states.size() <= 0xFF, entry.getString("name") + " has more than 255 states");
            data.writeShort(string(entry.getString("name")));
            data.writeInt(hashes[i]);
            data.writeByte(states.size());
            for (Map.Entry<String, Object> state : states.entrySet()) {
                data.writeShort(string(state.getKey()));
                switch (state.getValue()) {
                    case Byte value -> {
                        data.writeByte(1);
                        data.writeByte(value);
                    }
                    case Integer value -> {
                        data.writeByte(3);
                        data.writeInt(value);
                    }
                    case String value -> {
                        data.writeByte(8);
                        data.writeShort(string(value));
                    }
                    default -> throw new IllegalStateException("Palette state " + state + " has an unsupported type");
                }
            }
            DumpState state = joined[i];
            data.writeShort(stateShapes[i]);
            data.writeByte((state.canContainLiquidSource ? CAN_CONTAIN_LIQUID_SOURCE : 0) | (state.solid ? SOLID : 0));
            data.writeFloat(state.hardness);
            data.writeFloat(state.friction);
        }

        data.writeInt(defaults.size());
        for (int index : defaults) {
            data.writeShort(index);
        }
        data.flush();

        require(strings.size() <= 0xFFFF, "More than 65535 strings");
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(table);
        header.writeInt(MAGIC);
        header.writeInt(FORMAT);
        header.writeInt(PROTOCOL);
        header.writeUTF(SOURCE_VERSION);
        header.writeInt(blockVersion);
        header.writeInt(strings.size());
        for (String value : strings.keySet()) {
            header.writeUTF(value);
        }
        body.writeTo(header);
        header.flush();

        RESTORED.forEach((text, value) -> System.out.println("Read " + text + " as " + value));
        System.out.println(palette.size() + " palette entries, " + shapes.size() + " shapes, block version " + blockVersion + " for protocol " + PROTOCOL + ", from BedrockData " + SOURCE_VERSION);
        return table.toByteArray();
    }

    private static List<NbtMap> readPalette(Path path) throws IOException {
        try (NBTInputStream in = NbtUtils.createGZIPReader(Files.newInputStream(path))) {
            Object root = in.readTag();
            require(root instanceof NbtMap map && map.containsKey("blocks", NbtType.LIST), path + " is not a block palette");
            return ((NbtMap) root).getList("blocks", NbtType.COMPOUND);
        }
    }

    // The network hash of each block's default state, by name
    private static Map<String, Integer> readDefaults(Path path) throws IOException {
        Map<String, Integer> defaults = new HashMap<>();
        try (JsonReader json = new JsonReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                Long hash = null;
                json.beginObject();
                while (json.hasNext()) {
                    if (json.nextName().equals("defaultBlockStateHash")) {
                        hash = json.nextLong();
                    } else {
                        json.skipValue();
                    }
                }
                json.endObject();
                require(hash != null && hash >= 0 && hash <= 0xFFFFFFFFL, "Block type " + name + " has no 32-bit defaultBlockStateHash");
                require(defaults.put(name, (int) (long) hash) == null, "Block type " + name + " is listed twice");
            }
            json.endObject();
            require(json.peek() == JsonToken.END_DOCUMENT, path + " has data after its object");
        }
        return defaults;
    }

    private static List<DumpState> readDump(Path path) throws IOException {
        List<DumpState> states = new ArrayList<>();
        try (JsonReader json = new JsonReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            json.beginArray();
            while (json.hasNext()) {
                String name = null;
                Long hash = null;
                float[] boxes = null;
                Boolean canContainLiquidSource = null;
                Float hardness = null;
                Float friction = null;
                Boolean solid = null;
                json.beginObject();
                while (json.hasNext()) {
                    switch (json.nextName()) {
                        case "name" -> name = json.nextString();
                        case "blockStateHash" -> hash = json.nextLong();
                        case "collisionShape" -> boxes = readBoxes(json);
                        case "canContainLiquidSource" -> canContainLiquidSource = json.nextBoolean();
                        case "hardness" -> hardness = readFloat(json);
                        case "friction" -> friction = readFloat(json);
                        case "isSolid" -> solid = json.nextBoolean();
                        default -> json.skipValue();
                    }
                }
                json.endObject();
                int n = states.size();
                require(name != null && hash != null && boxes != null && canContainLiquidSource != null && hardness != null && friction != null && solid != null, "Dump state " + n + " " + name + " lacks a field");
                require(hash >= 0 && hash <= 0xFFFFFFFFL, "Dump state " + n + " " + name + " has the hash " + hash + ", outside 32 bits");
                states.add(new DumpState(name, (int) (long) hash, boxes, canContainLiquidSource, hardness, friction, solid));
            }
            json.endArray();
            require(json.peek() == JsonToken.END_DOCUMENT, path + " has data after its array");
        }
        return states;
    }

    // The dump cuts floats to 6 decimals, so 0.8175 prints as 0.817499.
    // When adding one millionth (away from zero) gives a shorter number whose float cuts back to the printed text, that float is used; otherwise the printed number stays, as for 1/240 steps such as 0.754166.
    private static float readFloat(JsonReader json) throws IOException {
        require(json.peek() == JsonToken.NUMBER, "Expected a number at " + json);
        String text = json.nextString();
        float value = Float.parseFloat(text);
        require(Float.isFinite(value), "A number is not finite at " + json);
        BigDecimal printed = new BigDecimal(text);
        if (printed.scale() == 6) {
            BigDecimal shorter = printed.add(BigDecimal.valueOf(printed.signum(), 6)).stripTrailingZeros();
            float restored = Float.parseFloat(shorter.toPlainString());
            if (shorter.scale() < 6 && new BigDecimal(restored).setScale(6, RoundingMode.DOWN).compareTo(printed) == 0) {
                RESTORED.put(text, restored);
                return restored;
            }
        }
        return value;
    }

    private static float[] readBoxes(JsonReader json) throws IOException {
        List<Float> values = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            json.beginArray();
            for (int i = 0; i < 6; i++) {
                values.add(readFloat(json));
            }
            json.endArray();
        }
        json.endArray();
        float[] boxes = new float[values.size()];
        for (int i = 0; i < boxes.length; i++) {
            boxes[i] = values.get(i);
        }
        return boxes;
    }

    // FNV-1 64 (multiply, then XOR) of the UTF-8 name, as the client's HashedString
    private static long nameHash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash *= 0x100000001b3L;
            hash ^= b & 0xFF;
        }
        return hash;
    }

    // FNV-1a 32 of the little-endian NBT compound {name, states}, with the states sorted by key: the ID a client uses when StartGame asks for hashed block IDs.
    private static int networkHash(String name, NbtMap states) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);
        writeLeString(out, "");
        out.write(8);
        writeLeString(out, "name");
        writeLeString(out, name);
        out.write(10);
        writeLeString(out, "states");
        for (Map.Entry<String, Object> state : new TreeMap<>(states).entrySet()) {
            String key = state.getKey();
            switch (state.getValue()) {
                case Byte value -> {
                    out.write(1);
                    writeLeString(out, key);
                    out.write(value);
                }
                case Integer value -> {
                    out.write(3);
                    writeLeString(out, key);
                    for (int shift = 0; shift < 32; shift += 8) {
                        out.write(value >>> shift);
                    }
                }
                case String value -> {
                    out.write(8);
                    writeLeString(out, key);
                    writeLeString(out, value);
                }
                default -> throw new IllegalStateException("Palette state " + state + " has an unsupported type");
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

    private static void writeLeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.write(bytes.length >>> 8);
        out.writeBytes(bytes);
    }

    private int string(String value) {
        return strings.computeIfAbsent(value, v -> strings.size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
