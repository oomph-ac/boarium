package ac.oomph.boarium.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.ILoggerFactory;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LogSinksTest {
    // A provider of its own: Gradle's test JVM starts SLF4J before boarium's Main could select it. MainTest checks the selection in the installed boarium.
    private static final ILoggerFactory LOGGERS = provider();
    // A logger of its own, so that lines other tests log meanwhile are left out.
    private final String name = "LogSinksTest." + UUID.randomUUID();
    private final Logger log = LOGGERS.getLogger(this.name);

    private static ILoggerFactory provider() {
        SinkServiceProvider provider = new SinkServiceProvider();
        provider.initialize();
        return provider.getLoggerFactory();
    }
    private final List<LogSinks.Registration> registrations = new ArrayList<>();

    @AfterEach
    void removeSinks() {
        this.registrations.forEach(LogSinks.Registration::close);
    }

    private List<LogEvent> sink(Level level) {
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        this.registrations.add(LogSinks.add(level, event -> {
            if (event.logger().equals(this.name)) {
                events.add(event);
            }
        }));
        return events;
    }

    @Test
    void sendsFilledInLinesAtTheSinksLevelAndAbove() {
        List<LogEvent> events = this.sink(Level.INFO);
        IllegalStateException error = new IllegalStateException("boom");
        this.log.debug("hidden {}", 1);
        this.log.info("{} joined {}", "Steve", "127.0.0.1:19133");
        this.log.warn("could not post", error);

        assertEquals(2, events.size());
        LogEvent joined = events.getFirst();
        assertEquals(Level.INFO, joined.level());
        assertEquals("Steve joined 127.0.0.1:19133", joined.message());
        assertEquals(Thread.currentThread().getName(), joined.thread());
        assertNull(joined.error());
        assertNotNull(joined.time());
        assertEquals(Level.WARN, events.get(1).level());
        assertSame(error, events.get(1).error());
    }

    @Test
    void aDebugSinkGetsLinesTheConsoleHides() {
        assertFalse(LOGGERS.getLogger("ac.oomph.boarium.Hidden").isDebugEnabled(), "the console shows INFO and above by default");
        List<LogEvent> events = this.sink(Level.DEBUG);
        assertTrue(this.log.isDebugEnabled());
        this.log.debug("trace {}", 1);
        this.log.atDebug().setMessage("fluent {}").addArgument(2).log();
        assertEquals(List.of("trace 1", "fluent 2"), events.stream().map(LogEvent::message).toList());
    }

    @Test
    void theConsoleLineIsUnchangedAndBracesInArgumentsAreKept() {
        List<LogEvent> events = this.sink(Level.INFO);
        PrintStream err = System.err;
        var console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));
        try {
            this.log.info("player {} said {}", "Alex", "{}");
        } finally {
            System.setErr(err);
        }
        assertEquals("player Alex said {}", events.getFirst().message());
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("INFO " + this.name + " - player Alex said {}"), console.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aClosedRegistrationGetsNothingMore() {
        List<LogEvent> events = this.sink(Level.INFO);
        this.log.info("one");
        this.registrations.removeFirst().close();
        this.log.info("two");
        assertEquals(List.of("one"), events.stream().map(LogEvent::message).toList());
        assertFalse(LogSinks.wants(Level.TRACE));
    }

    @Test
    void closingOneOfTwoRegistrationsOfASinkKeepsTheOther() {
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        LogSink sink = event -> {
            if (event.logger().equals(this.name)) {
                events.add(event);
            }
        };
        LogSinks.Registration first = LogSinks.add(Level.INFO, sink);
        this.registrations.add(LogSinks.add(Level.INFO, sink));
        first.close();
        this.log.info("once");
        assertEquals(List.of("once"), events.stream().map(LogEvent::message).toList());
    }

    @Test
    void aThrowingSinkIsRemovedAndOthersStillGetLines() {
        List<LogEvent> events = this.sink(Level.INFO);
        int[] calls = {0};
        this.registrations.add(LogSinks.add(Level.INFO, event -> {
            calls[0]++;
            throw new IllegalStateException("broken sink");
        }));
        assertDoesNotThrow(() -> this.log.info("one"));
        this.log.info("two");
        assertEquals(1, calls[0]);
        assertEquals(List.of("one", "two"), events.stream().map(LogEvent::message).toList());
    }

    @Test
    void whatASinkLogsGoesOnlyToTheConsole() {
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        this.registrations.add(LogSinks.add(Level.INFO, event -> {
            if (event.logger().equals(this.name)) {
                events.add(event);
                this.log.info("from the sink");
            }
        }));
        this.log.info("outside");
        assertEquals(List.of("outside"), events.stream().map(LogEvent::message).toList());
    }
}
