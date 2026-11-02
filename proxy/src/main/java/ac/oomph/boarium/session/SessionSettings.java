package ac.oomph.boarium.session;

import java.time.Duration;

/**
 * How sessions behave.
 *
 * @param fallbackServer  the server to move a player to when its server is lost, or null for none
 * @param loginTimeout    how long a login may take, from the client's login to its spawn
 * @param latencyInterval how often the client's latency is sent to its server
 */
public record SessionSettings(String fallbackServer, Duration loginTimeout, Duration latencyInterval) {
    // spectrum-internal's login timeout and default latency_interval.
    public static final SessionSettings DEFAULT = new SessionSettings(null, Duration.ofMinutes(1), Duration.ofSeconds(3));
}
