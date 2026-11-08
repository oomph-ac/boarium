package ac.oomph.boarium.boar;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleAlertsTest {
    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    private long now;

    @Test
    void passesTheLimitInASecondAndCountsTheRestOnTheNextLine() {
        ConsoleAlerts.Limiter limiter = new ConsoleAlerts.Limiter(2, () -> this.now);
        assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        this.now += 300 * MILLI;
        assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        for (int i = 0; i < 5; i++) {
            this.now += 100 * MILLI;
            assertEquals(-1, limiter.pass("Steve", "Timer", () -> false));
        }
        // 1 second after the first line
        this.now = 1000 * MILLI;
        assertEquals(5, limiter.pass("Steve", "Timer", () -> false));
        assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        assertEquals(-1, limiter.pass("Steve", "Timer", () -> false));
    }

    @Test
    void passesEveryLineWithALimitOf0() {
        ConsoleAlerts.Limiter limiter = new ConsoleAlerts.Limiter(0, () -> this.now);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        }
        assertFalse(limiter.tracks("Steve"));
    }

    @Test
    void limitsEachPlayerAndCheckOnItsOwn() {
        ConsoleAlerts.Limiter limiter = new ConsoleAlerts.Limiter(1, () -> this.now);
        assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        assertEquals(-1, limiter.pass("Steve", "Timer", () -> false));
        assertEquals(0, limiter.pass("Steve", "Reach", () -> false));
        assertEquals(0, limiter.pass("Alex", "Timer", () -> false));
        assertEquals(-1, limiter.pass("Alex", "Timer", () -> false));
        this.now += 1000 * MILLI;
        assertEquals(1, limiter.pass("Steve", "Timer", () -> false));
        assertEquals(0, limiter.pass("Steve", "Reach", () -> false));
        assertEquals(1, limiter.pass("Alex", "Timer", () -> false));
    }

    @Test
    void forgetsAPlayerThatLeft() {
        ConsoleAlerts.Limiter limiter = new ConsoleAlerts.Limiter(1, () -> this.now);
        limiter.pass("Steve", "Timer", () -> false);
        limiter.pass("Steve", "Timer", () -> false);
        limiter.pass("Steve", "Timer", () -> false);
        limiter.pass("Steve", "Reach", () -> false);
        assertTrue(limiter.tracks("Steve"));
        // Only the checks with lines held back
        assertEquals(Map.of("Timer", 2), limiter.forget("Steve"));
        assertFalse(limiter.tracks("Steve"));
        assertEquals(Map.of(), limiter.forget("Steve"));
        // A flag of a player that has left may come after the player was forgotten, so it goes out without leaving an entry.
        assertEquals(0, limiter.pass("Alex", "Timer", () -> true));
        assertFalse(limiter.tracks("Alex"));
    }

    @Test
    void removesColourCodes() {
        assertEquals("Boar > Steve failed Timer", ConsoleAlerts.plain("§3Boar §7>§r §3Steve§7 failed§6 Timer"));
    }
}
