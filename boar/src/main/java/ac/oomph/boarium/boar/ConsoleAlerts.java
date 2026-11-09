package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.check.api.Check;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Logs each of Boar's flags as one line: Boar's alert without its colours, and whether boarium's boar_mitigations lets Boar act on the player.
 * Only boar_alert_limit lines a second go out for each player and check; Boar still counts and acts on every flag.
 */
final class ConsoleAlerts implements ViolationListener {
    private static final Logger LOG = LoggerFactory.getLogger("Boar");
    private static final Pattern COLOUR = Pattern.compile("§.");

    final AlertLimiter limiter;

    // limit is boar_alert_limit, where 0 means no limit.
    ConsoleAlerts(int limit) {
        this.limiter = new AlertLimiter(limit, Duration.ofSeconds(1), System::nanoTime);
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
        return Boar.getInstance().getAlertManager().getPrefix() + player.getSession().name() + " failed " + checkName(check);
    }

    // The check's name, with its type and whether it is experimental, as Boar's alerts show them.
    static String checkName(Check check) {
        StringBuilder name = new StringBuilder(check.name());
        if (!check.type().isBlank()) {
            name.append(" (").append(check.type()).append(")");
        }
        if (check.experimental()) {
            name.append(" (Experimental)");
        }
        return name.toString();
    }

    // Text without Minecraft's § colour and format codes, for the console.
    static String plain(String text) {
        return COLOUR.matcher(text).replaceAll("");
    }
}
