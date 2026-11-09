package ac.oomph.boarium.boar;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import ac.oomph.boarium.discord.DiscordEvent;
import ac.oomph.boarium.discord.DiscordLog;

import java.time.Duration;
import java.util.Map;

/**
 * Posts Boar's flags to Discord, at most discord.flag.limit a minute for each player and check, since Discord takes only a few posts a second.
 */
final class DiscordAlerts implements ViolationListener {
    private final DiscordLog discord;
    final AlertLimiter limiter;

    DiscordAlerts(DiscordLog discord) {
        this.discord = discord;
        this.limiter = new AlertLimiter(discord.flagLimit(), Duration.ofMinutes(1), System::nanoTime);
    }

    @Override
    public void onViolation(Violation violation) {
        BoarPlayer player = violation.player();
        int held = this.limiter.pass(player, violation.check(), () -> player.getSession().isClosed());
        if (held < 0) {
            return;
        }
        String details = violation.verbose() == null ? "" : violation.verbose().lines().findFirst().orElse("");
        this.discord.post(DiscordEvent.FLAG, Map.of(
                "player", player.getSession().name(),
                "xuid", BoariumPlayerManager.xuid(player),
                "check", ConsoleAlerts.plain(ConsoleAlerts.checkName(violation.check())),
                "vl", String.valueOf(violation.vl()),
                "details", ConsoleAlerts.plain(details).strip(),
                "mitigations", player.disableMitigations() ? "off" : "on",
                "more", held > 0 ? "(+" + held + " more)" : ""));
    }

    // Drops the counts of a player that has left, without posting the flags held back, which would only add to Discord's rate limit.
    void forget(BoarPlayer player) {
        this.limiter.forget(player);
    }
}
