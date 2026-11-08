package ac.oomph.boarium.client;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RakNetThrottleTest {
    private static final String OPEN = "RakNet turned away 10.0.0.1: it has 1 connections open, the raknet.max_connections_per_address limit, which players on one IP share. "
            + "Logged at most once a minute per IP.";

    private final AtomicLong now = new AtomicLong();
    private final List<String> warnings = new ArrayList<>();

    private RakNetThrottle throttle(int maxConnections) {
        return new RakNetThrottle(maxConnections, this.now::get, this.warnings::add);
    }

    @Test
    void logsAnIpOverTheLimitOfOpenConnectionsOnceAMinute() {
        RakNetThrottle throttle = this.throttle(1);
        assertTrue(throttle.accept(client("10.0.0.1", 1)));

        assertFalse(throttle.accept(client("10.0.0.1", 2)));
        assertEquals(List.of(OPEN), this.warnings);
        this.now.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertFalse(throttle.accept(client("10.0.0.1", 3)));
        assertEquals(1, this.warnings.size());

        this.now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertFalse(throttle.accept(client("10.0.0.1", 4)));
        assertEquals(List.of(OPEN, OPEN), this.warnings);
    }

    @Test
    void logsEachIpOnItsOwn() {
        RakNetThrottle throttle = this.throttle(1);
        assertTrue(throttle.accept(client("10.0.0.1", 1)));
        assertTrue(throttle.accept(client("10.0.0.2", 1)));

        assertFalse(throttle.accept(client("10.0.0.1", 2)));
        assertFalse(throttle.accept(client("10.0.0.2", 2)));
        assertEquals(2, this.warnings.size());
        assertTrue(this.warnings.get(1).startsWith("RakNet turned away 10.0.0.2: it has 1 connections open"), this.warnings.get(1));
    }

    @Test
    void namesTheLimitOnNewConnections() {
        RakNetThrottle throttle = this.throttle(10);
        for (int port = 1; port <= 3; port++) {
            assertTrue(throttle.accept(client("10.0.0.1", port)));
        }

        assertFalse(throttle.accept(client("10.0.0.1", 4)));
        assertEquals(List.of("RakNet turned away 10.0.0.1: it opened 3 connections in 4 seconds; it can try again after that. Logged at most once a minute per IP."), this.warnings);
    }

    // A closed connection frees its place, so a connection after it is turned away for the window and not for open connections.
    @Test
    void countsOnlyOpenConnections() {
        RakNetThrottle throttle = this.throttle(1);
        assertTrue(throttle.accept(client("10.0.0.1", 1)));
        throttle.closed(client("10.0.0.1", 1));
        assertTrue(throttle.accept(client("10.0.0.1", 2)));
        throttle.closed(client("10.0.0.1", 2));
        assertTrue(throttle.accept(client("10.0.0.1", 3)));
        throttle.closed(client("10.0.0.1", 3));

        assertFalse(throttle.accept(client("10.0.0.1", 4)));
        assertTrue(this.warnings.getFirst().contains("it opened 3 connections in 4 seconds"), this.warnings.getFirst());
    }

    private static InetSocketAddress client(String ip, int port) {
        return new InetSocketAddress(ip, port);
    }
}
