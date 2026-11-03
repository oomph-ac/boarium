package ac.oomph.boarium.boar.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserFixturesTest {
    private static String failure(Runnable read) {
        return assertThrows(IllegalStateException.class, read::run).getMessage();
    }

    @Test
    void readsTheFiles() {
        assertEquals(1196, GeyserFixtures.blocks().size());
        assertEquals(1022, GeyserFixtures.items().size());
        assertEquals(57, GeyserFixtures.properties().size());
        assertEquals(11553, GeyserFixtures.javaStates(BedrockBlockTable.get()).size());
    }

    @Test
    void rejectsLinesWithOtherColumns() {
        List<String> blocks = List.of("# header", "stone 1.5 - stone garbage");
        assertTrue(failure(() -> GeyserFixtures.parse("blocks", blocks, 1, 3, 4)).startsWith("blocks:2 has 5 columns"));
        assertTrue(failure(() -> GeyserFixtures.parse("items", List.of("acacia_button bogus acacia_button"), 1, 1, 2)).startsWith("items:1 has 3 columns"));
        assertTrue(failure(() -> GeyserFixtures.parse("blocks", List.of("stone  1.5 -"), 1, 3, 4)).startsWith("blocks:1 has an empty column"));
        assertTrue(failure(() -> GeyserFixtures.parse("items", List.of("stone "), 1, 1, 2)).startsWith("items:1 has an empty column"));
    }

    @Test
    void rejectsADroppedLine() {
        assertTrue(failure(() -> GeyserFixtures.parse("items", List.of("stone"), 2, 1, 2)).contains("has 1 lines, expected 2"));
        List<String[]> states = GeyserFixtures.parse("states", List.of("stone * s../1111/1111"), 1, 3, Integer.MAX_VALUE);
        assertTrue(failure(() -> GeyserFixtures.javaStates(BedrockBlockTable.get(), states, 2)).contains("maps 1 palette entries, expected 2"));
    }

    @Test
    void rejectsBadStates() {
        BedrockBlockTable table = BedrockBlockTable.get();
        for (String line : List.of("acacia_button * xyz/00000/0000junk/extra", "stone * s../11110/1111", "stone * S../1111/1111", "stone 2 s../1111/1111", "stone 11 s../1111/1111",
                "oak_double_slab 11 s../1111/1111 s../1111/1111 s../1111/1111", "oak_double_slab 11 s../1111/1111 s../1111/1111 junk", "not_a_block * s../1111/1111")) {
            List<String[]> rows = GeyserFixtures.parse("states", List.of(line), 1, 3, Integer.MAX_VALUE);
            assertThrows(IllegalStateException.class, () -> GeyserFixtures.javaStates(table, rows, 1), line);
        }
    }
}
