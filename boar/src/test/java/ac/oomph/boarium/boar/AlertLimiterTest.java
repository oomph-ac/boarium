package ac.oomph.boarium.boar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertLimiterTest {
    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    private long now;

    @Test
    void passesTheLimitInASecondAndCountsTheRestOnTheNextLine() {
        AlertLimiter limiter = new AlertLimiter(2, Duration.ofSeconds(1), () -> this.now);
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
        AlertLimiter limiter = new AlertLimiter(0, Duration.ofSeconds(1), () -> this.now);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        }
        assertFalse(limiter.tracks("Steve"));
    }

    @Test
    void limitsEachPlayerAndCheckOnItsOwn() {
        AlertLimiter limiter = new AlertLimiter(1, Duration.ofSeconds(1), () -> this.now);
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
        AlertLimiter limiter = new AlertLimiter(1, Duration.ofSeconds(1), () -> this.now);
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
    void takesItsWindowFromTheCaller() {
        AlertLimiter limiter = new AlertLimiter(1, Duration.ofMinutes(1), () -> this.now);
        assertEquals(0, limiter.pass("Steve", "Timer", () -> false));
        this.now += 59_999 * MILLI;
        assertEquals(-1, limiter.pass("Steve", "Timer", () -> false));
        this.now += MILLI;
        assertEquals(1, limiter.pass("Steve", "Timer", () -> false));
    }
}
