package ac.oomph.boarium.boar.blocks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the facts about Geyser's Java to Bedrock mapping kept in the test resources under ac/oomph/boarium/boar/geyser.
 * Each file's header says what it holds and how it was made. A file that does not have the lines and columns it was written with is an error, so a dropped or damaged line fails the tests that read it.
 */
public final class GeyserFixtures {
    private static final Pattern FACT = Pattern.compile("[s.][r.][w.]/[01]{4}/[01]{4}");
    private static final Pattern MAPPED = Pattern.compile("\\*|[01]+");

    private GeyserFixtures() {
    }

    // The lines of blocks.26_30.txt: Java block name, destroy time, block lists, and the Bedrock names when they are not just the Java name
    public static List<String[]> blocks() {
        return rows("blocks.26_30.txt", 1196, 3, 4);
    }

    // The lines of items.26_30.txt: item name, and the block name when it is not the item's
    public static List<String[]> items() {
        return rows("items.26_30.txt", 1022, 1, 2);
    }

    // The lines of properties.26_30.txt: Boar's property key, the Bedrock state keys, the values, and the blocks
    static List<String[]> properties() {
        return rows("properties.26_30.txt", 57, 4, 4);
    }

    // The files write names without minecraft:.
    public static String name(String name) {
        return "minecraft:" + name;
    }

    private static List<String[]> rows(String file, int rows, int minColumns, int maxColumns) {
        String path = "/ac/oomph/boarium/boar/geyser/" + file;
        try (InputStream in = GeyserFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            return parse(file, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList(), rows, minColumns, maxColumns);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The space-separated columns of each line that is not a comment
    static List<String[]> parse(String file, List<String> lines, int rows, int minColumns, int maxColumns) {
        List<String[]> parsed = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split(" ", -1);
            if (columns.length < minColumns || columns.length > maxColumns) {
                throw new IllegalStateException(file + ":" + (n + 1) + " has " + columns.length + " columns, expected " + minColumns + " to " + maxColumns + ": " + line);
            }
            for (String column : columns) {
                if (column.isEmpty()) {
                    throw new IllegalStateException(file + ":" + (n + 1) + " has an empty column: " + line);
                }
            }
            parsed.add(columns);
        }
        if (parsed.size() != rows) {
            throw new IllegalStateException(file + " has " + parsed.size() + " lines, expected " + rows + ": a line was dropped or added");
        }
        return parsed;
    }

    /**
     * What states.26_30.txt says about the Java state Geyser maps to a palette entry.
     *
     * @param fenceJoins whether an oak fence joins the state when it is north, east, south and west of the fence, as 1 or 0 each
     * @param paneJoins the same for a glass pane
     */
    record JavaState(boolean faceSturdy, boolean replaceable, boolean waterlogged, String fenceJoins, String paneJoins) {
        static JavaState parse(String fact) {
            if (!FACT.matcher(fact).matches()) {
                throw new IllegalStateException("Not a fact of states.26_30.txt: " + fact);
            }
            String[] parts = fact.split("/");
            return new JavaState(parts[0].charAt(0) == 's', parts[0].charAt(1) == 'r', parts[0].charAt(2) == 'w', parts[1], parts[2]);
        }
    }

    // The palette entries that Geyser maps a Java state to, by palette index
    static Map<Integer, JavaState> javaStates(BedrockBlockTable table) {
        return javaStates(table, rows("states.26_30.txt", 1174, 3, Integer.MAX_VALUE), 11553);
    }

    static Map<Integer, JavaState> javaStates(BedrockBlockTable table, List<String[]> rows, int entries) {
        Map<Integer, JavaState> states = new HashMap<>();
        for (String[] row : rows) {
            String name = name(row[0]);
            int first = table.firstIndex(name);
            int count = table.stateCount(name);
            if (first < 0) {
                throw new IllegalStateException("The palette has no " + name);
            }
            if (!MAPPED.matcher(row[1]).matches() || !row[1].equals("*") && row[1].length() != count) {
                throw new IllegalStateException(name + " has " + count + " states, states.26_30.txt lists " + row[1]);
            }
            int fact = 2;
            for (int s = 0; s < count; s++) {
                if (row[1].equals("*") || row[1].charAt(s) == '1') {
                    if (row.length != 3 && fact == row.length) {
                        throw new IllegalStateException(name + " has more mapped states than states.26_30.txt lists facts");
                    }
                    states.put(first + s, JavaState.parse(row[row.length == 3 ? 2 : fact++]));
                }
            }
            if (row.length != 3 && fact != row.length) {
                throw new IllegalStateException(name + " has " + (fact - 2) + " mapped states, states.26_30.txt lists " + (row.length - 2) + " facts");
            }
        }
        if (states.size() != entries) {
            throw new IllegalStateException("states.26_30.txt maps " + states.size() + " palette entries, expected " + entries + ": a line was dropped or added");
        }
        return states;
    }
}
