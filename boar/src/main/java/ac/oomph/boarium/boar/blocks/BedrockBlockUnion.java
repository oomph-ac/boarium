package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * One block table with every block state of every protocol's table, so a block state has one shared ID (its index in {@link #table()}) on every client version, and the mapping between shared IDs and each table's own palette indexes.
 * Entries with the same name are next to each other. Each name's default state is the one of the newest table that has the name.
 * The table is a resource that {@link #main} writes from the protocols' tables, so that a server loads only the tables of the versions its clients use.
 */
public final class BedrockBlockUnion {
    static final String RESOURCE = "/ac/oomph/boarium/boar/blocks/bedrock_blocks.union.bin.gz";

    private static final class Holder {
        static final BedrockBlockUnion UNION = new BedrockBlockUnion(BedrockBlockTable.load(RESOURCE));
    }

    private final BedrockBlockTable table;
    // By game version, as BedrockBlockTable.version gives it
    private final Map<String, Projection> byVersion = new ConcurrentHashMap<>();

    private record Key(String name, NbtMap states) {
    }

    private record Source(BedrockBlockTable table, int index) {
    }

    private record Type(Class<?> type, String version) {
    }

    // A table's shared ID of each of its palette indexes, and the closest palette index for each shared ID, or -1 where it has no block of that name
    record Projection(int[] toShared, int[] fromShared) {
    }

    BedrockBlockUnion(BedrockBlockTable table) {
        this.table = table;
    }

    // Writes the table that build makes to the file in args[0]: ./gradlew :boar:generateBedrockBlockUnion
    public static void main(String[] args) throws IOException {
        try (OutputStream out = Files.newOutputStream(Path.of(args[0]))) {
            build().write(out);
        }
        System.out.println("Wrote " + args[0] + " (" + Files.size(Path.of(args[0])) + " bytes)");
    }

    // The shared table, from the table of every protocol
    static BedrockBlockTable build() {
        List<BedrockBlockTable> newestFirst = BedrockBlockTable.VERSIONS.keySet().stream().sorted(Comparator.reverseOrder()).map(BedrockBlockTable::forProtocol).distinct().toList();

        // The newest table's names first, in its order, then names only older tables have; each name's states the same way.
        Map<String, List<Key>> keysByName = new LinkedHashMap<>();
        Map<Key, Source> sources = new HashMap<>();
        Map<String, Map<String, Type>> typesByName = new HashMap<>();
        for (BedrockBlockTable source : newestFirst) {
            for (int i = 0; i < source.paletteSize(); i++) {
                Key key = new Key(source.name(i), source.states(i));
                Source first = sources.putIfAbsent(key, new Source(source, i));
                if (first == null) {
                    keysByName.computeIfAbsent(key.name(), name -> new ArrayList<>()).add(key);
                    Map<String, Type> types = typesByName.computeIfAbsent(key.name(), name -> new HashMap<>());
                    key.states().forEach((state, value) -> {
                        Type type = types.putIfAbsent(state, new Type(value.getClass(), source.sourceVersion()));
                        if (type != null && type.type() != value.getClass()) {
                            throw new IllegalStateException(key.name() + " has the state " + state + " as " + type.type().getSimpleName() + " in the block table of " + type.version() + " and as " + value.getClass().getSimpleName() + " in that of " + source.sourceVersion());
                        }
                    });
                } else {
                    requireSameData(key, first, source, i);
                }
            }
        }

        int size = sources.size();
        String[] names = new String[size];
        NbtMap[] states = new NbtMap[size];
        int[] hashes = new int[size];
        float[][] shapes = new float[size][];
        char[] stateShape = new char[size];
        byte[] flags = new byte[size];
        float[] hardness = new float[size];
        float[] friction = new float[size];
        int[] defaults = new int[keysByName.size()];
        Map<Key, Integer> ids = new HashMap<>(size * 2);
        int id = 0;
        int block = 0;
        for (List<Key> keys : keysByName.values()) {
            for (Key key : keys) {
                Source source = sources.get(key);
                names[id] = key.name();
                states[id] = key.states();
                hashes[id] = source.table().networkHash(source.index());
                shapes[id] = source.table().collision(source.index());
                stateShape[id] = (char) id;
                flags[id] = (byte) source.table().flags(source.index());
                hardness[id] = source.table().hardness(source.index());
                friction[id] = source.table().friction(source.index());
                ids.put(key, id++);
            }
            // The first key of a name came from the newest table with that name.
            BedrockBlockTable newest = sources.get(keys.getFirst()).table();
            String name = keys.getFirst().name();
            defaults[block++] = ids.get(new Key(name, newest.states(newest.defaultIndex(name))));
        }
        String sourceVersions = newestFirst.reversed().stream().map(BedrockBlockTable::sourceVersion).collect(Collectors.joining(", "));
        // The tables have different block versions and protocols, so the union has neither.
        return new BedrockBlockTable(-1, sourceVersions, -1, shapes, names, states, hashes, stateShape, flags, hardness, friction, defaults);
    }

    // Throws when the shared table lacks a state of own, which means RESOURCE is older than own.
    private Projection project(BedrockBlockTable own) {
        int[] toShared = new int[own.paletteSize()];
        for (int i = 0; i < toShared.length; i++) {
            toShared[i] = this.table.index(own.name(i), own.states(i));
            if (toShared[i] < 0 || this.table.networkHash(toShared[i]) != own.networkHash(i)) {
                throw new IllegalStateException("The shared block table does not have " + own.name(i) + " " + own.states(i) + " of " + own.sourceVersion() + ": regenerate it with ./gradlew :boar:generateBedrockBlockUnion");
            }
        }
        int[] fromShared = new int[this.table.paletteSize()];
        for (int shared = 0; shared < fromShared.length; shared += this.table.stateCount(this.table.name(shared))) {
            String name = this.table.name(shared);
            Map<String, Set<Object>> values = values(own, name);
            for (int i = shared; i < shared + this.table.stateCount(name); i++) {
                fromShared[i] = project(own, name, this.table.states(i), values);
            }
        }
        return new Projection(toShared, fromShared);
    }

    // Two tables that list the same state must agree on what it is.
    private static void requireSameData(Key key, Source first, BedrockBlockTable other, int index) {
        BedrockBlockTable table = first.table();
        int i = first.index();
        if (table.networkHash(i) != other.networkHash(index)
                || !Arrays.equals(table.collision(i), other.collision(index))
                || table.flags(i) != other.flags(index)
                || Float.compare(table.hardness(i), other.hardness(index)) != 0
                || Float.compare(table.friction(i), other.friction(index)) != 0) {
            throw new IllegalStateException(key.name() + " " + key.states() + " has different data in the block tables of " + table.sourceVersion() + " and " + other.sourceVersion());
        }
    }

    // Each state key of a name in own, with the values own has for it
    static Map<String, Set<Object>> values(BedrockBlockTable own, String name) {
        Map<String, Set<Object>> values = new HashMap<>();
        int first = own.firstIndex(name);
        for (int i = first; i < first + own.stateCount(name); i++) {
            own.states(i).forEach((key, value) -> values.computeIfAbsent(key, k -> new HashSet<>()).add(value));
        }
        return values;
    }

    // The entry of own closest to a state: for each key own has, the state's value if own has that value for the name, else the value of own's default state. Own's default state when no entry has that mix, and -1 when own has no block with the name.
    // Versions can have different keys for a name: a 26.30 fence has no states, and from 26.50 it has four connection states.
    static int project(BedrockBlockTable own, String name, NbtMap states, Map<String, Set<Object>> values) {
        int fallback = own.defaultIndex(name);
        if (fallback < 0) {
            return -1;
        }
        NbtMap defaultStates = own.states(fallback);
        NbtMapBuilder projected = defaultStates.toBuilder();
        for (String key : defaultStates.keySet()) {
            Object value = states.get(key);
            if (value != null && values.get(key).contains(value)) {
                projected.put(key, value);
            }
        }
        int index = own.index(name, projected.build());
        return index < 0 ? fallback : index;
    }

    // Loaded on first use from RESOURCE
    public static BedrockBlockUnion get() {
        return Holder.UNION;
    }

    public BedrockBlockTable table() {
        return table;
    }

    // The shared ID of a palette index of a protocol's table
    public int toShared(int protocol, int index) {
        return get(projection(protocol).toShared(), index, "palette index");
    }

    // The palette index of a protocol's table closest to a shared ID's state, or -1 when that table has no block with its name
    public int fromShared(int protocol, int shared) {
        return get(projection(protocol).fromShared(), shared, "shared ID");
    }

    // Throws rather than giving -1, which fromShared already gives for a block a version lacks.
    private static int get(int[] ids, int index, String what) {
        if (index < 0 || index >= ids.length) {
            throw new IllegalArgumentException("No " + what + " " + index + " among " + ids.length);
        }
        return ids[index];
    }

    // Built on first use for each table, and loads that table
    Projection projection(int protocol) {
        String version = BedrockBlockTable.version(protocol);
        if (version == null) {
            throw new IllegalArgumentException("No block table for protocol " + protocol);
        }
        return this.byVersion.computeIfAbsent(version, v -> this.project(BedrockBlockTable.forProtocol(protocol)));
    }
}
