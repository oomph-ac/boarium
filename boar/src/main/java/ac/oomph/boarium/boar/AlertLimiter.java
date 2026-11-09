package ac.oomph.boarium.boar;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * At most limit lines of Boar's flags in each window for each player and check. Each player's flags come on its own client event loop, and a player leaves on another thread.
 */
final class AlertLimiter {
    private final int limit;
    private final long window;
    private final LongSupplier clock;
    private final Map<Object, Map<Object, Window>> players = new ConcurrentHashMap<>();

    // limit is 0 for no limit, and clock gives System.nanoTime().
    AlertLimiter(int limit, Duration window, LongSupplier clock) {
        this.limit = limit;
        this.window = window.toNanos();
        this.clock = clock;
    }

    // How many lines of this player and check were held back since the last that went out, or -1 to hold this one back too.
    int pass(Object player, Object check, BooleanSupplier left) {
        if (this.limit == 0) {
            return 0;
        }
        Map<Object, Window> checks = this.players.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
        // A player is forgotten after it has left, so one that left before its entry was made would keep it.
        if (left.getAsBoolean()) {
            this.players.remove(player);
        }
        return checks.computeIfAbsent(check, key -> new Window()).pass(this.limit, this.window, this.clock.getAsLong());
    }

    // Drops the counts of a player, and returns how many lines of each check were held back since its last line, for the checks that had some.
    Map<Object, Integer> forget(Object player) {
        Map<Object, Window> checks = this.players.remove(player);
        Map<Object, Integer> held = new LinkedHashMap<>();
        if (checks != null) {
            checks.forEach((check, window) -> {
                int count = window.held();
                if (count > 0) {
                    held.put(check, count);
                }
            });
        }
        return held;
    }

    boolean tracks(Object player) {
        return this.players.containsKey(player);
    }

    // The lines of one player and check in the window that began with the first line sent in it.
    private static final class Window {
        private boolean begun;
        private long start;
        private int sent;
        private int held;

        synchronized int pass(int limit, long length, long now) {
            if (!this.begun || now - this.start >= length) {
                this.begun = true;
                this.start = now;
                this.sent = 0;
            }
            if (this.sent >= limit) {
                this.held++;
                return -1;
            }
            this.sent++;
            int held = this.held;
            this.held = 0;
            return held;
        }

        synchronized int held() {
            return this.held;
        }
    }
}
