package ac.oomph.boarium.boar;

import ac.oomph.boarium.flag.Flag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlagAlertsTest {
    private long now;

    private static Flag flag(int held) {
        return new Flag(Instant.EPOCH, "practice:19133", "Steve", "", UUID.randomUUID(), "Timer", "", false, 1, "", false, held);
    }

    @Test
    void givesEachSinkTheFlagsItsLimitLetsThroughInAMinute() {
        FlagAlerts alerts = new FlagAlerts();
        List<Flag> limited = new ArrayList<>();
        List<Flag> all = new ArrayList<>();
        alerts.add(limited::add, 2, () -> this.now);
        alerts.add(all::add, 0, () -> this.now);
        for (int i = 0; i < 5; i++) {
            alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag);
            this.now += TimeUnit.SECONDS.toNanos(10);
        }
        assertEquals(2, limited.size());
        assertEquals(5, all.size());
        // A minute after the first flag, the next one counts the 3 held back.
        this.now = TimeUnit.MINUTES.toNanos(1);
        alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag);
        assertEquals(3, limited.getLast().heldBack());
        assertEquals(0, all.getLast().heldBack());
        // Each player and check has its own count.
        alerts.send("Steve", "Reach", () -> false, FlagAlertsTest::flag);
        alerts.send("Alex", "Timer", () -> false, FlagAlertsTest::flag);
        assertEquals(5, limited.size());
    }

    @Test
    void forgetsAPlayerThatLeft() {
        FlagAlerts alerts = new FlagAlerts();
        alerts.add(flag -> {}, 1, () -> this.now);
        alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag);
        assertTrue(alerts.tracks("Steve"));
        alerts.forget("Steve");
        assertFalse(alerts.tracks("Steve"));
    }

    @Test
    void aSinkThatThrowsDoesNotStopTheOthersOrTheCaller() {
        FlagAlerts alerts = new FlagAlerts();
        List<Flag> flags = new ArrayList<>();
        alerts.add(flag -> {
            throw new IllegalStateException("broken");
        }, 0);
        alerts.add(flags::add, 0);
        assertDoesNotThrow(() -> alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag));
        assertDoesNotThrow(() -> alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag));
        assertEquals(2, flags.size());
    }

    @Test
    void stopsGivingFlagsToARemovedSink() {
        FlagAlerts alerts = new FlagAlerts();
        List<Flag> flags = new ArrayList<>();
        Runnable remove = alerts.add(flags::add, 0);
        alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag);
        remove.run();
        alerts.send("Steve", "Timer", () -> false, FlagAlertsTest::flag);
        assertEquals(1, flags.size());
    }

    @Test
    void turnsAwayANegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> new FlagAlerts().add(flag -> {}, -1));
        assertThrows(IllegalArgumentException.class, () -> new BoariumBoar.FlagOutput(flag -> {}, -1));
    }
}
