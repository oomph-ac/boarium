package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.check.api.Check;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Logs each of Boar's flags as one line: Boar's alert without its colours, and whether boarium's boar_mitigations lets Boar act on the player.
 * Only boar_alert_limit lines a second go out for each player and check; Boar still counts and acts on every flag.
 */
final class ConsoleAlerts implements ViolationListener {
    private static final Logger LOG = LoggerFactory.getLogger("Boar");
    private static final Pattern COLOUR = Pattern.compile("§.");

    final Limiter limiter;

    // limit is boar_alert_limit, where 0 means no limit.
    ConsoleAlerts(int limit) {
        this.limiter = new Limiter(limit, System::nanoTime);
    }

    @Override
    public void onViolation(Violation violation) {
        BoarPlayer player = violation.player();
        int held = this.limiter.pass(player, violation.check(), () -> player.getSession().isClosed());
        if (held < 0) {
            return;
        }
        List<String> details = violation.verbose() == null ? List.of() : violation.verbose().lines().toList();
        LOG.info(line(violation, details.isEmpty() ? "" : details.getFirst()) + (held > 0 ? " (+" + held + " more)" : ""));
        // With debug-mode on, Prediction puts a movement trace after the first line of its details.
        if (details.size() > 1) {
            LOG.debug(plain(String.join(System.lineSeparator(), details.subList(1, details.size()))));
        }
    }

    // Logs the flags held back for a player that has left, which no later line would count.
    void forget(BoarPlayer player) {
        this.limiter.forget(player).forEach((check, held) -> LOG.info(plain(failed(player, (Check) check)) + " (+" + held + " more)"));
    }

    // The text of Boar's AlertViolationListener with the prefix AlertManager adds.
    private static String line(Violation violation, String details) {
        StringBuilder line = new StringBuilder(failed(violation.player(), violation.check()));
        line.append(" x").append(violation.vl());
        if (!details.isBlank()) {
            line.append(' ').append(details);
        }
        line.append(violation.player().disableMitigations() ? " [mitigations off]" : " [mitigations on]");
        return plain(line.toString());
    }

    private static String failed(BoarPlayer player, Check check) {
        StringBuilder line = new StringBuilder(Boar.getInstance().getAlertManager().getPrefix()).append(player.getSession().name()).append(" failed ").append(check.name());
        if (!check.type().isBlank()) {
            line.append(" (").append(check.type()).append(")");
        }
        if (check.experimental()) {
            line.append(" (Experimental)");
        }
        return line.toString();
    }

    // Text without Minecraft's § colour and format codes, for the console.
    static String plain(String text) {
        return COLOUR.matcher(text).replaceAll("");
    }

    // At most limit lines in each window, a second for the console, for each player and check. Each player's flags come on its own client event loop, and a player leaves on another thread.
    static final class Limiter {
        private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

        private final int limit;
        private final long window;
        private final LongSupplier clock;
        private final Map<Object, Map<Object, Window>> players = new ConcurrentHashMap<>();

        Limiter(int limit, LongSupplier clock) {
            this(limit, SECOND, clock);
        }

        Limiter(int limit, long window, LongSupplier clock) {
            this.limit = limit;
            this.window = window;
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

            synchronized int pass(int limit, long window, long now) {
                if (!this.begun || now - this.start >= window) {
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
}
