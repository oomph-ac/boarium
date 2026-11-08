package ac.oomph.boarium.client;

import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerThrottle;
import org.cloudburstmc.netty.channel.raknet.config.RakServerThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Geyser's limits on the RakNet connections of each IP, which logs the IPs it turns away, since nothing else would tell why their players cannot join.
 */
final class RakNetThrottle implements RakServerThrottle {
    private static final Logger LOG = LoggerFactory.getLogger(RakNetThrottle.class);
    // How many new connections each IP may open in each window, as Geyser allows.
    static final int NEW_CONNECTIONS_PER_WINDOW = 3;
    static final Duration NEW_CONNECTION_WINDOW = Duration.ofSeconds(4);
    // Each IP is logged at most once in this time, so that a flood cannot fill the log.
    static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final RakServerThrottle limits;
    private final int maxConnections;
    // The connections each IP has open, counted as the library counts them, to tell which limit turned one away.
    private final Map<InetAddress, Integer> open = new ConcurrentHashMap<>();
    // When each IP was last logged, in nanoTime.
    private final Map<InetAddress, Long> warned = new ConcurrentHashMap<>();
    // When warned was last cleared of IPs logged longer ago than WARN_INTERVAL, in nanoTime.
    private volatile long cleared;
    private final LongSupplier nanoTime;
    private final Consumer<String> warn;

    RakNetThrottle(int maxConnections) {
        this(maxConnections, System::nanoTime, LOG::warn);
    }

    RakNetThrottle(int maxConnections, LongSupplier nanoTime, Consumer<String> warn) {
        this.limits = new DefaultRakServerThrottle(maxConnections, NEW_CONNECTION_WINDOW.toMillis(), NEW_CONNECTIONS_PER_WINDOW);
        this.maxConnections = maxConnections;
        this.nanoTime = nanoTime;
        this.warn = warn;
    }

    @Override
    public boolean accept(InetSocketAddress address) {
        InetAddress ip = address.getAddress();
        if (this.limits.accept(address)) {
            this.open.merge(ip, 1, Integer::sum);
            return true;
        }
        this.turnedAway(ip);
        return false;
    }

    @Override
    public void closed(InetSocketAddress address) {
        this.limits.closed(address);
        this.open.computeIfPresent(address.getAddress(), (ip, count) -> count > 1 ? count - 1 : null);
    }

    private void turnedAway(InetAddress ip) {
        long now = this.nanoTime.getAsLong();
        long interval = WARN_INTERVAL.toNanos();
        Long last = this.warned.get(ip);
        if (last != null && now - last < interval) {
            return;
        }
        if (now - this.cleared >= interval) {
            this.cleared = now;
            this.warned.values().removeIf(time -> now - time >= interval);
        }
        this.warned.put(ip, now);
        Integer open = this.open.get(ip);
        String reason = open != null && open >= this.maxConnections
                ? "it has " + this.maxConnections + " connections open, the raknet.max_connections_per_address limit, which players on one IP share"
                : "it opened " + NEW_CONNECTIONS_PER_WINDOW + " connections in " + NEW_CONNECTION_WINDOW.toSeconds() + " seconds; it can try again after that";
        this.warn.accept("RakNet turned away " + ip.getHostAddress() + ": " + reason + ". Logged at most once a minute per IP.");
    }
}
