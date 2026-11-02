package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientLogin;

import java.time.Duration;
import java.util.function.Function;

/**
 * How sessions behave.
 *
 * @param fallbackServer  the server to move a player to when its server is lost, or null for none
 * @param loginTimeout    how long a login may take, from the client's login to its spawn
 * @param latencyInterval how often the client's latency is sent to its server
 * @param processors      gives each player's session its processor
 */
public record SessionSettings(String fallbackServer, Duration loginTimeout, Duration latencyInterval, Function<ClientLogin, Processor> processors) {
    // spectrum-internal's login timeout and default latency_interval.
    public static final SessionSettings DEFAULT = new SessionSettings(null, Duration.ofMinutes(1), Duration.ofSeconds(3), login -> Processor.NONE);
}
