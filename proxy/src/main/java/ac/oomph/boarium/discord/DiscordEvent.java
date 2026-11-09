package ac.oomph.boarium.discord;

import java.util.List;

/**
 * What boarium can post to Discord, each with its section under discord in the config and the placeholders its message takes.
 */
public enum DiscordEvent {
    START("start", true, "boarium started, sending players to {server}", "server"),
    STOP("stop", true, "boarium stopped"),
    JOIN("join", false, "{player} joined {server}", "player", "xuid", "server"),
    LEAVE("leave", false, "{player} left", "player", "xuid"),
    FLAG("flag", true, "**{player}** failed {check} x{vl} {details} {more}", "player", "xuid", "check", "vl", "details", "mitigations", "more"),
    KICK("kick", true, "**{player}** was kicked by Boar: {reason}", "player", "xuid", "reason");

    private final String key;
    private final boolean enabledByDefault;
    private final String defaultMessage;
    private final List<String> placeholders;

    DiscordEvent(String key, boolean enabledByDefault, String defaultMessage, String... placeholders) {
        this.key = key;
        this.enabledByDefault = enabledByDefault;
        this.defaultMessage = defaultMessage;
        this.placeholders = List.of(placeholders);
    }

    public String key() {
        return this.key;
    }

    public boolean enabledByDefault() {
        return this.enabledByDefault;
    }

    public String defaultMessage() {
        return this.defaultMessage;
    }

    public List<String> placeholders() {
        return this.placeholders;
    }
}
