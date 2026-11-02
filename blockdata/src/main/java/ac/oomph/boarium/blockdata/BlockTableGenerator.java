package ac.oomph.boarium.blockdata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Writes boar's block table for Bedrock 1.26.30 from a Geyser standalone jar, with the answers Geyser gives for each Java block state, the Bedrock palette, and the Java block each Bedrock block item places.
 * Geyser's classes are loaded from the jar at run time, so this module builds without it.
 *
 * The table is gzip over big-endian DataOutputStream data, in this order:
 * magic "BBT2", protocol, Geyser build (UTF);
 * strings (count, UTF each);
 * properties (count; per property: name, value count u8, values), where a name or value is a u16 string index;
 * blocks (count; per block: name, state count, Geyser's default state offset, destroy time, category bits, property count u8, property ids u16), in Java order, each block's states following the previous block's;
 * shapes (count; per shape: box count u8, then minX, minY, minZ, maxX, maxY, maxZ as floats per box);
 * Java states (count; per state: shape u16, flags u8);
 * palette entries (count; per entry: name, network hash, state count u8, then per state: key, NBT tag type u8 (1 byte, 3 int, 8 string), value);
 * the palette index of each Java state, as u16;
 * block items (count; per item: Bedrock item name, Java block index u16), by name.
 */
public final class BlockTableGenerator {
    private static final int MAGIC = 0x42425432;
    private static final String PALETTE = "bedrock/block_palette.26_30.nbt";
    private static final String ITEM_PALETTE = "bedrock/runtime_item_states.26_30.json";
    private static final int PROTOCOL = 1001;

    private static final int FACE_STURDY = 1;
    private static final int WATERLOGGED = 2;
    private static final int REPLACEABLE = 4;

    private static final int STAIRS = 1;
    private static final int LEAVES = 1 << 1;
    private static final int SHULKER = 1 << 2;
    private static final int FENCE_GATE = 1 << 3;
    private static final int WALL = 1 << 4;
    private static final int FENCE = 1 << 5;
    private static final int CLIMBABLE = 1 << 6;
    private static final int BED = 1 << 7;
    private static final int CHEST = 1 << 8;
    private static final int DOOR = 1 << 9;
    private static final int TRAPDOOR = 1 << 10;
    private static final int CANDLE = 1 << 11;
    private static final int SIGN = 1 << 12;
    private static final int BUTTON = 1 << 13;
    private static final int BARS = 1 << 14;
    private static final int LANTERN = 1 << 15;
    private static final int ANVIL = 1 << 16;
    private static final int CAULDRON = 1 << 17;

    // Boar's Geyser adapter lists these by hand as the climbable blocks.
    private static final Set<String> CLIMBABLE_NAMES = Set.of("cave_vines", "cave_vines_plant", "ladder", "vine", "twisting_vines", "twisting_vines_plant", "weeping_vines", "weeping_vines_plant");
    // Geyser counts these as waterlogged, besides states with waterlogged=true.
    private static final Set<String> ALWAYS_WATERLOGGED = Set.of("bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass");

    private final URLClassLoader loader;
    private final Map<String, Integer> strings = new LinkedHashMap<>();

    private BlockTableGenerator(URLClassLoader loader) {
        this.loader = loader;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: <path to Geyser.jar> <output file>");
            System.exit(2);
        }
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path out = Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(jar)) {
            System.err.println("No Geyser jar at " + jar);
            System.exit(2);
        }

        byte[] table;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            table = new BlockTableGenerator(loader).build();
        } catch (IllegalStateException e) {
            System.err.println("Block table not written: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (OutputStream file = new GZIPOutputStream(Files.newOutputStream(out))) {
            file.write(table);
        }
        System.out.println("Wrote " + out + " (" + Files.size(out) + " bytes)");
    }

    private record JavaBlock(String name, int firstState, Object handle, int[] counts, String[] keys, String[][] values) {
    }

    private byte[] build() throws Exception {
        String geyserBuild = geyserBuild();
        Class.forName("org.geysermc.geyser.level.block.Blocks", true, loader);
        List<?> states = (List<?>) call(staticField("org.geysermc.geyser.registry.BlockRegistries", "BLOCK_STATES"), "get");
        List<?> blocksNbt = (List<?>) readNbt("mappings/blocks.nbt").get("bedrock_mappings");
        Map<String, Object> collisions = readNbt("mappings/collisions.nbt");
        int[] shapeIndices = (int[]) collisions.get("indices");
        List<?> shapeList = (List<?>) collisions.get("shapes");
        require(blocksNbt.size() == states.size(), "blocks.nbt has " + blocksNbt.size() + " states, Geyser's BLOCK_STATES has " + states.size());
        require(shapeIndices.length == states.size(), "collisions.nbt has " + shapeIndices.length + " states, Geyser's BLOCK_STATES has " + states.size());

        // Java blocks and their properties, from each state's text, for example minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=true]
        List<JavaBlock> blocks = new ArrayList<>();
        int[] blockOfState = new int[states.size()];
        String[] texts = new String[states.size()];
        for (int id = 0; id < states.size(); id++) {
            Object state = states.get(id);
            int reportedId = (int) call(state, "javaId");
            require(reportedId == id, "Java state " + id + " reports ID " + reportedId);
            String text = state.toString();
            Object handle = call(state, "block");
            JavaBlock block = blocks.isEmpty() ? null : blocks.getLast();
            if (block == null || block.handle != handle) {
                block = newBlock(handle, id, text);
                int blockId = (int) call(handle, "javaId");
                require(blockId == blocks.size(), block.name + " has Java block ID " + blockId + ", expected " + blocks.size());
                blocks.add(block);
            }
            blockOfState[id] = blocks.size() - 1;
            texts[id] = text;
        }

        int[] stateCounts = new int[blocks.size()];
        for (int b = 0; b < blocks.size(); b++) {
            JavaBlock block = blocks.get(b);
            int next = b + 1 < blocks.size() ? blocks.get(b + 1).firstState : states.size();
            stateCounts[b] = next - block.firstState;
            readValues(block, Arrays.copyOfRange(texts, block.firstState, next));
        }
        require(blocks.size() == ((List<?>) call(staticField("org.geysermc.geyser.registry.BlockRegistries", "JAVA_BLOCKS"), "get")).size(), "Java block count differs from Geyser's JAVA_BLOCKS");

        List<float[]> shapes = readShapes(shapeList);
        require(Arrays.equals(shapes.get(0), new float[0]), "collisions.nbt shape 0 is not empty");
        require(Arrays.equals(shapes.get(1), new float[]{0, 0, 0, 1, 1, 1}), "collisions.nbt shape 1 is not the full cube");

        Map<Object, String> fieldNames = blockFieldNames();
        BitSet replaceable = replaceableBlocks(blocks.size());
        Map<Pattern, Boolean> remappers = collisionRemappers();

        // Shape and flags of each Java state, as Geyser's BlockCollision and Boar's Geyser adapter give them.
        char[] stateShapes = new char[states.size()];
        byte[] stateFlags = new byte[states.size()];
        for (int id = 0; id < states.size(); id++) {
            JavaBlock block = blocks.get(blockOfState[id]);
            String path = block.name.substring("minecraft:".length());
            int shape = shapeIndices[id];
            boolean sturdy = shape == 1;
            Boolean solid = null;
            for (Map.Entry<Pattern, Boolean> remapper : remappers.entrySet()) {
                if (remapper.getKey().matcher(path).find()) {
                    require(solid == null, path + " matches more than one Geyser collision remapper");
                    solid = remapper.getValue();
                }
            }
            if (solid != null) {
                sturdy = solid;
                if (solid) {
                    shape = 1;
                }
            }
            require(shape < shapes.size(), "Java state " + id + " uses missing shape " + shape);

            int offset = id - block.firstState;
            String waterlogged = value(block, offset, "waterlogged");
            int flags = 0;
            if (sturdy) {
                flags |= FACE_STURDY;
            }
            if ("true".equals(waterlogged) || ALWAYS_WATERLOGGED.contains(path)) {
                flags |= WATERLOGGED;
            }
            if (path.equals("snow") ? "1".equals(value(block, offset, "layers")) : replaceable.get(blockOfState[id])) {
                flags |= REPLACEABLE;
            }
            stateShapes[id] = (char) shape;
            stateFlags[id] = (byte) flags;
        }

        // The Bedrock palette, in runtime ID order, and the palette entry of each Java state.
        List<?> palette = (List<?>) readNbt(PALETTE).get("blocks");
        Map<List<Object>, Integer> paletteIndex = new HashMap<>();
        int[] networkIds = new int[palette.size()];
        int hashMismatches = 0;
        for (int i = 0; i < palette.size(); i++) {
            Map<?, ?> entry = (Map<?, ?>) palette.get(i);
            String name = (String) entry.get("name");
            Map<?, ?> blockStates = (Map<?, ?>) entry.get("states");
            require(paletteIndex.put(List.of(name, blockStates), i) == null, "Duplicate palette entry " + name + " " + blockStates);
            networkIds[i] = (int) entry.get("network_id");
            int expected = name.equals("minecraft:unknown") ? -2 : networkHash(name, blockStates);
            if (expected != networkIds[i]) {
                hashMismatches++;
            }
        }
        require(hashMismatches == 0, hashMismatches + " palette entries have a network_id that is not the FNV-1a 32 hash of their name and states");
        require(Arrays.stream(networkIds).distinct().count() == networkIds.length, "Two palette entries share a network_id");
        require(palette.size() <= 0xFFFF, "Palette has " + palette.size() + " entries, more than a u16 holds");

        Method convert = loader.loadClass("org.geysermc.geyser.registry.populator.conversion.ICanHasStates").getMethod("convertBlock", loader.loadClass("org.cloudburstmc.nbt.NbtMap"));
        Method fromMap = loader.loadClass("org.cloudburstmc.nbt.NbtMap").getMethod("fromMap", Map.class);
        Object emptyMap = staticField("org.cloudburstmc.nbt.NbtMap", "EMPTY");
        char[] javaToRuntime = new char[states.size()];
        for (int id = 0; id < states.size(); id++) {
            Map<?, ?> entry = (Map<?, ?>) blocksNbt.get(id);
            String javaName = blocks.get(blockOfState[id]).name;
            Object identifier = entry.get("bedrock_identifier");
            Object state = entry.get("state");
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("name", "minecraft:" + (identifier != null ? identifier : javaName.substring("minecraft:".length())));
            tag.put("states", state != null ? state : emptyMap);
            Map<?, ?> converted = (Map<?, ?>) convert.invoke(null, fromMap.invoke(null, tag));
            Integer index = paletteIndex.get(List.of(converted.get("name"), converted.get("states")));
            require(index != null, "No palette entry for Java state " + id + " " + states.get(id) + ": " + converted);
            javaToRuntime[id] = (char) (int) index;
        }

        // Every string goes into the table before anything that points at it.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(body);

        Map<String, Integer> propertyIds = new LinkedHashMap<>();
        List<String> propertyNames = new ArrayList<>();
        List<String[]> propertyValues = new ArrayList<>();
        int[][] blockProperties = new int[blocks.size()][];
        for (int b = 0; b < blocks.size(); b++) {
            JavaBlock block = blocks.get(b);
            blockProperties[b] = new int[block.keys.length];
            for (int p = 0; p < block.keys.length; p++) {
                String key = block.keys[p] + "=" + String.join(",", block.values[p]);
                Integer id = propertyIds.get(key);
                if (id == null) {
                    id = propertyIds.size();
                    propertyIds.put(key, id);
                    propertyNames.add(block.keys[p]);
                    propertyValues.add(block.values[p]);
                }
                blockProperties[b][p] = id;
            }
        }

        data.writeInt(propertyNames.size());
        for (int p = 0; p < propertyNames.size(); p++) {
            data.writeShort(string(propertyNames.get(p)));
            require(propertyValues.get(p).length <= 0xFF, propertyNames.get(p) + " has more than 255 values");
            data.writeByte(propertyValues.get(p).length);
            for (String value : propertyValues.get(p)) {
                data.writeShort(string(value));
            }
        }

        data.writeInt(blocks.size());
        for (int b = 0; b < blocks.size(); b++) {
            JavaBlock block = blocks.get(b);
            Object handle = block.handle;
            int defaultOffset = (int) call(call(handle, "defaultBlockState"), "javaId") - block.firstState;
            require(defaultOffset >= 0 && defaultOffset < stateCounts[b], block.name + " has its default state outside its states");
            data.writeShort(string(block.name));
            data.writeInt(stateCounts[b]);
            data.writeInt(defaultOffset);
            data.writeFloat((float) call(handle, "destroyTime"));
            data.writeInt(categories(block, fieldNames.get(handle)));
            data.writeByte(blockProperties[b].length);
            for (int property : blockProperties[b]) {
                data.writeShort(property);
            }
        }

        data.writeInt(shapes.size());
        for (float[] boxes : shapes) {
            data.writeByte(boxes.length / 6);
            for (float value : boxes) {
                data.writeFloat(value);
            }
        }

        data.writeInt(states.size());
        for (int id = 0; id < states.size(); id++) {
            data.writeShort(stateShapes[id]);
            data.writeByte(stateFlags[id]);
        }

        data.writeInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            Map<?, ?> entry = (Map<?, ?>) palette.get(i);
            Map<?, ?> blockStates = (Map<?, ?>) entry.get("states");
            data.writeShort(string((String) entry.get("name")));
            data.writeInt(networkIds[i]);
            data.writeByte(blockStates.size());
            for (Map.Entry<?, ?> state : blockStates.entrySet()) {
                data.writeShort(string((String) state.getKey()));
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
        }

        for (char runtimeId : javaToRuntime) {
            data.writeShort(runtimeId);
        }

        Map<String, Integer> itemBlocks = itemBlocks(blocks, blockOfState, stateCounts);
        require(blocks.size() <= 0xFFFF, "More than 65535 Java blocks");
        data.writeInt(itemBlocks.size());
        for (Map.Entry<String, Integer> item : itemBlocks.entrySet()) {
            data.writeShort(string(item.getKey()));
            data.writeShort(item.getValue());
        }
        data.flush();

        require(strings.size() <= 0xFFFF, "More than 65535 strings");
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(table);
        header.writeInt(MAGIC);
        header.writeInt(PROTOCOL);
        header.writeUTF(geyserBuild);
        header.writeInt(strings.size());
        for (String value : strings.keySet()) {
            header.writeUTF(value);
        }
        body.writeTo(header);
        header.flush();

        System.out.println(states.size() + " Java states in " + blocks.size() + " blocks, " + propertyNames.size() + " properties, " + shapes.size() + " shapes, " + palette.size() + " palette entries, " + itemBlocks.size() + " block items for protocol " + PROTOCOL + ", from Geyser " + geyserBuild);
        return table.toByteArray();
    }

    private record BlockItem(String javaItem, int data, int block) {
    }

    // The Java block of each Bedrock item that places one, by Bedrock item name, from Geyser's item mappings.
    // Boarium's items carry only the Bedrock name, so when several Java items share one, such as the 16 beds told apart by their data value, the one with the lowest data value wins.
    private Map<String, Integer> itemBlocks(List<JavaBlock> blocks, int[] blockOfState, int[] stateCounts) throws Exception {
        Set<String> bedrockItems = new HashSet<>();
        for (Object item : (List<?>) readJson(ITEM_PALETTE, List.class)) {
            bedrockItems.add((String) ((Map<?, ?>) item).get("name"));
        }

        Map<String, List<BlockItem>> byName = new TreeMap<>();
        for (Map.Entry<?, ?> entry : new TreeMap<>((Map<?, ?>) readJson("mappings/items.json", Map.class)).entrySet()) {
            String javaItem = (String) entry.getKey();
            Map<?, ?> item = (Map<?, ?>) entry.getValue();
            boolean isBlock = Boolean.TRUE.equals(item.get("is_block"));
            require(isBlock == item.containsKey("firstBlockRuntimeId") && isBlock == item.containsKey("lastBlockRuntimeId"), javaItem + " has is_block " + isBlock + " but its Java state range does not agree");
            if (!isBlock) {
                continue;
            }
            int first = integer(item.get("firstBlockRuntimeId"), javaItem);
            int last = integer(item.get("lastBlockRuntimeId"), javaItem);
            require(first >= 0 && first <= last && last < blockOfState.length, javaItem + " places Java states " + first + " to " + last + ", outside Geyser's states");
            int block = blockOfState[first];
            require(first == blocks.get(block).firstState && last == first + stateCounts[block] - 1, javaItem + " places Java states " + first + " to " + last + ", which are not all the states of " + blocks.get(block).name);

            String name = (String) item.get("bedrock_identifier");
            require(bedrockItems.contains(name), javaItem + " has the Bedrock name " + name + ", which is not in " + ITEM_PALETTE);
            int data = item.containsKey("bedrock_data") ? integer(item.get("bedrock_data"), javaItem) : 0;
            byName.computeIfAbsent(name, n -> new ArrayList<>()).add(new BlockItem(javaItem, data, block));
        }

        Map<String, Integer> itemBlocks = new TreeMap<>();
        for (Map.Entry<String, List<BlockItem>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<BlockItem> items = entry.getValue();
            // Geyser gives this name to the Java block items that Bedrock does not have, so it stands for no single block.
            if (name.equals("minecraft:unknown")) {
                System.out.println("Skipped " + name + ", the Bedrock name of " + items.stream().map(BlockItem::javaItem).toList());
                continue;
            }
            items.sort(Comparator.comparingInt(BlockItem::data));
            require(items.stream().mapToInt(BlockItem::data).distinct().count() == items.size(), name + " is the Bedrock name of Java items with the same data value: " + items);
            BlockItem chosen = items.getFirst();
            if (items.size() > 1) {
                System.out.println(name + " is the Bedrock name of " + items.size() + " Java items; " + chosen.javaItem + " (data " + chosen.data + ") places " + blocks.get(chosen.block).name);
            }
            itemBlocks.put(name, chosen.block);
        }
        return itemBlocks;
    }

    private static int integer(Object number, String javaItem) {
        double value = (Double) number;
        require(value == (int) value, javaItem + " has the non-integer value " + number);
        return (int) value;
    }

    private JavaBlock newBlock(Object handle, int firstState, String stateText) throws Exception {
        Object[] properties = (Object[]) call(handle, "propertyKeys");
        if (properties == null) {
            properties = new Object[0];
        }
        String[] keys = new String[properties.length];
        for (int p = 0; p < properties.length; p++) {
            keys[p] = (String) call(properties[p], "name");
        }
        int bracket = stateText.indexOf('[');
        String name = bracket < 0 ? stateText : stateText.substring(0, bracket);
        require(name.startsWith("minecraft:"), "Java block " + name + " is not in the minecraft namespace");
        return new JavaBlock(name, firstState, handle, new int[keys.length], keys, new String[keys.length][]);
    }

    // Each property's values in index order, read from the block's states. The states run through every value combination with the last property changing fastest, so values first appear in index order. Value counts come from the states, as Geyser's IntegerProperty.valuesCount is one short.
    private static void readValues(JavaBlock block, String[] texts) {
        String[][] pairs = new String[texts.length][];
        List<LinkedHashSet<String>> seen = new ArrayList<>();
        for (int p = 0; p < block.keys.length; p++) {
            seen.add(new LinkedHashSet<>());
        }
        for (int offset = 0; offset < texts.length; offset++) {
            String text = texts[offset];
            int bracket = text.indexOf('[');
            require((bracket < 0 ? text : text.substring(0, bracket)).equals(block.name), "Java state " + text + " follows block " + block.name);
            String[] parts = bracket < 0 ? new String[0] : text.substring(bracket + 1, text.length() - 1).split(",");
            require(parts.length == block.keys.length, text + " does not list the properties of " + block.name);
            pairs[offset] = new String[parts.length];
            for (int p = 0; p < parts.length; p++) {
                String[] pair = parts[p].split("=", 2);
                require(pair[0].equals(block.keys[p]), text + " lists " + pair[0] + " where " + block.keys[p] + " was expected");
                pairs[offset][p] = pair[1];
                seen.get(p).add(pair[1]);
            }
        }
        int combinations = 1;
        for (int p = 0; p < block.keys.length; p++) {
            block.values[p] = seen.get(p).toArray(String[]::new);
            block.counts[p] = block.values[p].length;
            combinations *= block.counts[p];
        }
        require(combinations == texts.length, block.name + " has " + texts.length + " states, its property values give " + combinations);
        for (int offset = 0; offset < texts.length; offset++) {
            for (int p = 0; p < block.keys.length; p++) {
                require(value(block, offset, block.keys[p]).equals(pairs[offset][p]), texts[offset] + " is not in property order");
            }
        }
    }

    private static String value(JavaBlock block, int offset, String key) {
        int stride = 1;
        for (int p = block.keys.length - 1; p >= 0; p--) {
            if (block.keys[p].equals(key)) {
                return block.values[p][offset / stride % block.counts[p]];
            }
            stride *= block.counts[p];
        }
        return null;
    }

    // The block lists of Boar's Geyser adapter: names from the fields of Geyser's Blocks, and Geyser's block classes.
    private int categories(JavaBlock block, String field) throws Exception {
        int categories = 0;
        if (field != null) {
            if (field.endsWith("_fence")) {
                categories |= FENCE;
            } else if (field.endsWith("_wall")) {
                categories |= WALL;
            } else if (field.endsWith("_fence_gate")) {
                categories |= FENCE_GATE;
            } else if (field.endsWith("shulker_box")) {
                categories |= SHULKER;
            } else if (field.endsWith("_leaves")) {
                categories |= LEAVES;
            } else if (field.endsWith("_stairs")) {
                categories |= STAIRS;
            } else if (field.equals("candle") || field.endsWith("_candle")) {
                categories |= CANDLE;
            } else if (field.endsWith("_sign")) {
                categories |= SIGN;
            }
            if (field.endsWith("_bars") || field.endsWith("_pane")) {
                categories |= BARS;
            }
            if (field.equals("lantern") || field.endsWith("_lantern")) {
                categories |= LANTERN;
            }
            if (field.equals("anvil") || field.endsWith("_anvil")) {
                categories |= ANVIL;
            }
            if (field.equals("cauldron") || field.endsWith("_cauldron")) {
                categories |= CAULDRON;
            }
        }
        if (CLIMBABLE_NAMES.contains(block.name.substring("minecraft:".length()))) {
            categories |= CLIMBABLE;
        }
        String types = "org.geysermc.geyser.level.block.type.";
        if (loader.loadClass(types + "BedBlock").isInstance(block.handle)) {
            categories |= BED;
        }
        if (loader.loadClass(types + "ChestBlock").isInstance(block.handle)) {
            categories |= CHEST;
        }
        if (loader.loadClass(types + "DoorBlock").isInstance(block.handle)) {
            categories |= DOOR;
        }
        if (loader.loadClass(types + "TrapDoorBlock").isInstance(block.handle)) {
            categories |= TRAPDOOR;
        }
        if (loader.loadClass(types + "ButtonBlock").isInstance(block.handle)) {
            categories |= BUTTON;
        }
        return categories;
    }

    private Map<Object, String> blockFieldNames() throws Exception {
        Class<?> blocks = loader.loadClass("org.geysermc.geyser.level.block.Blocks");
        Class<?> blockType = loader.loadClass("org.geysermc.geyser.level.block.type.Block");
        Map<Object, String> names = new IdentityHashMap<>();
        for (Field field : blocks.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && blockType.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                names.put(field.get(null), field.getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    // The Java tag minecraft:replaceable from Geyser's default tags, by Java block ID.
    private BitSet replaceableBlocks(int blockCount) throws Exception {
        Map<?, ?> blockTags = (Map<?, ?>) readNbt("networkTags.nbt").get("minecraft:block");
        int[] ids = (int[]) blockTags.get("minecraft:replaceable");
        require(ids != null, "networkTags.nbt has no minecraft:block minecraft:replaceable tag");
        BitSet set = new BitSet(blockCount);
        for (int id : ids) {
            require(id >= 0 && id < blockCount, "minecraft:replaceable lists missing Java block " + id);
            set.set(id);
        }
        return set;
    }

    // Geyser's collision classes that replace the BlockCollision of the blocks their regex finds, with whether each is Geyser's SolidCollision (a full cube).
    private Map<Pattern, Boolean> collisionRemappers() throws Exception {
        String annotationName = "org.geysermc.geyser.translator.collision.CollisionRemapper";
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> annotation = (Class<? extends Annotation>) loader.loadClass(annotationName);
        Method regex = annotation.getMethod("regex");
        // Geyser's annotation processor lists the annotated classes in a resource named after the annotation.
        List<String> classes;
        try (InputStream in = loader.getResourceAsStream(annotationName)) {
            require(in != null, "Geyser jar has no " + annotationName + " class list");
            classes = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty()).sorted().toList();
        }
        Map<Pattern, Boolean> remappers = new LinkedHashMap<>();
        for (String name : classes) {
            Class<?> type = Class.forName(name, false, loader);
            Annotation remapper = type.getAnnotation(annotation);
            require(remapper != null, name + " is listed as a collision remapper but has no CollisionRemapper annotation");
            remappers.put(Pattern.compile((String) regex.invoke(remapper)), type.getSimpleName().equals("SolidCollision"));
        }
        require(remappers.containsValue(true), "Geyser has no SolidCollision remapper");
        return remappers;
    }

    private String geyserBuild() throws Exception {
        try (InputStream in = loader.getResourceAsStream("git.properties")) {
            require(in != null, "Geyser jar has no git.properties");
            Properties properties = new Properties();
            properties.load(in);
            String build = properties.getProperty("git.build.version");
            require(build != null, "Geyser's git.properties has no git.build.version");
            return build;
        }
    }

    // Each box in collisions.nbt is its middle and size; Geyser sorts a shape's boxes by middle Y.
    private static List<float[]> readShapes(List<?> shapeList) {
        List<float[]> shapes = new ArrayList<>();
        for (Object shape : shapeList) {
            List<double[]> boxes = new ArrayList<>();
            for (Object box : (List<?>) shape) {
                List<?> numbers = (List<?>) box;
                double[] values = new double[6];
                for (int i = 0; i < 6; i++) {
                    values[i] = (double) numbers.get(i);
                }
                boxes.add(values);
            }
            boxes.sort(Comparator.comparingDouble(box -> box[1]));
            require(boxes.size() <= 0xFF, "A collision shape has more than 255 boxes");
            float[] flat = new float[boxes.size() * 6];
            for (int i = 0; i < boxes.size(); i++) {
                double[] box = boxes.get(i);
                for (int axis = 0; axis < 3; axis++) {
                    flat[i * 6 + axis] = (float) (box[axis] - box[axis + 3] / 2);
                    flat[i * 6 + 3 + axis] = (float) (box[axis] + box[axis + 3] / 2);
                }
            }
            shapes.add(flat);
        }
        return shapes;
    }

    // FNV-1a 32 of the little-endian NBT compound {name, states}, with the states sorted by key: the ID a client uses when StartGame asks for hashed block IDs.
    static int networkHash(String name, Map<?, ?> states) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);
        writeLeString(out, "");
        out.write(8);
        writeLeString(out, "name");
        writeLeString(out, name);
        out.write(10);
        writeLeString(out, "states");
        for (Map.Entry<?, ?> state : new TreeMap<>(states).entrySet()) {
            String key = (String) state.getKey();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readNbt(String path) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            require(in != null, "Geyser jar has no " + path);
            Object reader = loader.loadClass("org.cloudburstmc.nbt.NbtUtils").getMethod("createGZIPReader", InputStream.class).invoke(null, in);
            try {
                return (Map<String, Object>) call(reader, "readTag");
            } finally {
                ((AutoCloseable) reader).close();
            }
        }
    }

    // The JSON file at this path in Geyser's jar, read with the jar's Gson into Java maps, lists, strings, booleans and doubles.
    private Object readJson(String path, Class<?> type) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            require(in != null, "Geyser jar has no " + path);
            Class<?> gson = loader.loadClass("com.google.gson.Gson");
            return gson.getMethod("fromJson", String.class, Class.class).invoke(gson.getConstructor().newInstance(), new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        }
    }

    private Object staticField(String className, String field) throws Exception {
        return loader.loadClass(className).getField(field).get(null);
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
