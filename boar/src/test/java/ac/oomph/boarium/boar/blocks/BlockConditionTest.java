package ac.oomph.boarium.boar.blocks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BlockConditionTest {
    private static final List<String> KEYS = List.of("open", "age", "color");

    // The condition's result for a state with open, age and color, with a molangVersion of 10, or null when Boar cannot tell
    private static Boolean test(String condition, byte open, int age, String color) {
        return test(condition, 10, open, age, color);
    }

    private static Boolean test(String condition, int molangVersion, Object open, int age, String color) {
        BlockCondition parsed = BlockCondition.parse(condition, KEYS, molangVersion);
        return parsed == null ? null : parsed.test(new Object[]{open, age, color});
    }

    @Test
    void comparesBoolIntAndStringStates() {
        assertEquals(true, test("q.block_state('open') == true", (byte) 1, 0, "red"));
        assertEquals(false, test("q.block_state('open') == true", (byte) 0, 0, "red"));
        assertEquals(true, test("query.block_state('open') == 1", (byte) 1, 0, "red"));
        assertEquals(true, test("q.block_state('age') == 3", (byte) 0, 3, "red"));
        assertEquals(true, test("q.block_state('age') != 3", (byte) 0, 2, "red"));
        assertEquals(true, test("q.block_state('color') == 'red'", (byte) 0, 0, "red"));
        assertEquals(false, test("q.block_state('color') == 'red'", (byte) 0, 0, "blue"));
        // Molang names ignore case, and a bare state is true when it is not 0.
        assertEquals(true, test("Q.Block_State('open')", (byte) 1, 0, "red"));
        assertEquals(false, test("!q.block_state('open')", (byte) 1, 0, "red"));
    }

    // The client keeps the first property of a name in one place and the last in another.
    @Test
    void doesNotReadAPropertyListedTwice() {
        List<String> keys = List.of("open", "open", "age");
        assertNull(BlockCondition.parse("q.block_state('open') == true", keys, 10));
        assertEquals(true, BlockCondition.parse("q.block_state('age') == 3", keys, 10).test(new Object[]{(byte) 0, (byte) 1, 3}));
    }

    @Test
    void combinesWithPrecedence() {
        // ! binds tighter than ==, which binds tighter than &&, which binds tighter than ||.
        assertEquals(true, test("!q.block_state('open') == true", (byte) 0, 0, "red"));
        assertEquals(true, test("q.block_state('age') == 1 || q.block_state('age') == 2 && q.block_state('open')", (byte) 0, 1, "red"));
        assertEquals(false, test("(q.block_state('age') == 1 || q.block_state('age') == 2) && q.block_state('open')", (byte) 0, 1, "red"));
        assertEquals(true, test(" ( q.block_state('color') != 'red' ) && !(q.block_state('age') == 0) ", (byte) 0, 4, "blue"));
        // The client cuts the value to an integer.
        assertEquals(false, test("0.5", (byte) 0, 0, "red"));
        assertEquals(true, test("1.5", (byte) 0, 0, "red"));
    }

    @Test
    void anythingElseIsUnknown() {
        assertNull(test("q.block_state('open') > 0", (byte) 1, 0, "red"));
        assertNull(test("q.block_state('missing') == 1", (byte) 1, 0, "red"));
        assertNull(test("q.cardinal_facing == 2", (byte) 1, 0, "red"));
        assertNull(test("math.abs(1)", (byte) 1, 0, "red"));
        assertNull(test("q.block_state(\"open\") == 1", (byte) 1, 0, "red"));
        assertNull(test("q.block_state('open') == 1;", (byte) 1, 0, "red"));
        assertNull(test("(q.block_state('open') == 1", (byte) 1, 0, "red"));
        assertNull(test("q.block_state('open') = 1", (byte) 1, 0, "red"));
        assertNull(test("", (byte) 1, 0, "red"));
        assertNull(test("-1", (byte) 1, 0, "red"));
        // A string compared with a number, or a string where a number belongs
        assertNull(test("q.block_state('color') == 1", (byte) 1, 0, "red"));
        assertNull(test("q.block_state('color')", (byte) 1, 0, "red"));
        assertNull(test("q.block_state('color') && true", (byte) 1, 0, "red"));
        // Once the left side decides && or ||, the right side does not matter.
        assertEquals(false, test("false && q.block_state('color')", (byte) 1, 0, "red"));
        assertEquals(true, test("true || q.block_state('color')", (byte) 1, 0, "red"));
    }

    // Geyser 2.12 sends molangVersion 1 and writes q.block_property, which the client reads as q.block_state up to version 9.
    @Test
    void blockPropertyIsTheOlderNameOfBlockState() {
        // BlockMappingsReader_v1, for a block of Geyser's custom mappings, whose properties are strings
        assertEquals(true, test("q.block_property('open') == 'true' && q.block_property('color') == 'red'", 1, "true", 0, "red"));
        assertEquals(false, test("q.block_property('open') == 'true' && q.block_property('color') == 'red'", 1, "false", 0, "red"));
        // BuiltInMappings, with bool, int and string properties
        assertEquals(true, test("!query.block_property('open')&&query.block_property('age')==2&&query.block_property('color')=='blue'", 1, (byte) 0, 2, "blue"));
        assertEquals(false, test("!query.block_property('open')&&query.block_property('age')==2&&query.block_property('color')=='blue'", 1, (byte) 1, 2, "blue"));
        // Up to version 9 the client still resolves it, and from version 10 it does not.
        assertEquals(true, test("q.block_property('open') == 'true'", 9, "true", 0, "red"));
        assertEquals(true, test("q.block_property('open') == 'true'", 0, "true", 0, "red"));
        assertNull(test("q.block_property('open') == 'true'", 10, "true", 0, "red"));
        assertEquals(true, test("q.block_state('open') == 'true'", 0, "true", 0, "red"));
    }

    // Before version 6, the client groups && and || another way, so Boar does not read a condition that mixes them.
    @Test
    void mixedAndAndOrNeedVersionSix() {
        String mixed = "q.block_state('age') == 1 || q.block_state('age') == 2 && q.block_state('open')";
        assertEquals(true, test(mixed, 6, (byte) 0, 1, "red"));
        assertNull(test(mixed, 5, (byte) 0, 1, "red"));
        assertNull(test(mixed, 0, (byte) 0, 1, "red"));
        assertEquals(true, test("q.block_state('age') == 1 || q.block_state('age') == 2", 1, (byte) 0, 1, "red"));
        assertEquals(true, test("q.block_state('age') == 1 && q.block_state('open') == 0", 1, (byte) 0, 1, "red"));
    }

    @Test
    void hostileConditionsAreRejectedQuickly() {
        assertNull(BlockCondition.parse("1 == 1 && ".repeat(200) + "1", KEYS, 10));
        assertNull(BlockCondition.parse("(".repeat(100_000) + "1" + ")".repeat(100_000), KEYS, 10));
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertNull(BlockCondition.parse("!".repeat(1000) + "1", KEYS, 10)));
        // Under the length cap, nesting past the depth cap is still unknown, without overflowing the stack.
        assertNull(BlockCondition.parse("(".repeat(500) + "1" + ")".repeat(500), KEYS, 10));
        assertEquals(true, BlockCondition.parse("(".repeat(50) + "1" + ")".repeat(50), KEYS, 10).test(new Object[3]));
    }
}
