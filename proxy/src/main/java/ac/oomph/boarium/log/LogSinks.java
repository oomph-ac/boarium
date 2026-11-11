package ac.oomph.boarium.log;

import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sinks that get boarium's log events. A sink gets the events at its level and above, including ones below the console's level, such as DEBUG lines the console hides.
 * <p>
 * Events reach sinks only when boarium's logging provider, {@link SinkServiceProvider}, is the one SLF4J uses. boarium's {@code Main} selects it.
 */
public final class LogSinks {
    // Compared by identity, so that closing one of two registrations of the same sink removes only that one.
    private static final class Entry {
        private final Level level;
        private final LogSink sink;

        Entry(Level level, LogSink sink) {
            this.level = level;
            this.sink = sink;
        }

        Level level() {
            return this.level;
        }

        LogSink sink() {
            return this.sink;
        }
    }

    private static final List<Entry> SINKS = new CopyOnWriteArrayList<>();
    // The lowest level any sink takes, as Level.toInt(), so that loggers can skip building events nobody wants.
    private static volatile int threshold = Integer.MAX_VALUE;
    // Set while a sink runs on this thread, so that what it logs goes only to the console.
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean WARNED_INACTIVE = new AtomicBoolean();

    private LogSinks() {
    }

    /** Removes the sink it was returned for. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Sends the events at {@code level} and above to {@code sink} until the returned registration is closed.
     */
    public static Registration add(Level level, LogSink sink) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sink, "sink");
        if (!SinkServiceProvider.active() && WARNED_INACTIVE.compareAndSet(false, true)) {
            LoggerFactory.getLogger(LogSinks.class).warn("SLF4J does not use boarium's logging provider, so log sinks get no events. Start the JVM with -D{}={}",
                    SinkServiceProvider.PROPERTY, SinkServiceProvider.class.getName());
        }
        Entry entry = new Entry(level, sink);
        synchronized (SINKS) {
            SINKS.add(entry);
            updateThreshold();
        }
        return () -> {
            remove(entry);
        };
    }

    private static boolean remove(Entry entry) {
        synchronized (SINKS) {
            boolean removed = SINKS.remove(entry);
            if (removed) {
                updateThreshold();
            }
            return removed;
        }
    }

    // Under the lock, so that a sink added while another is removed cannot be left out of the threshold.
    private static void updateThreshold() {
        threshold = SINKS.stream().mapToInt(e -> e.level().toInt()).min().orElse(Integer.MAX_VALUE);
    }

    static boolean wants(Level level) {
        return level.toInt() >= threshold;
    }

    static void publish(Level level, String logger, String message, Throwable error) {
        if (!wants(level) || PUBLISHING.get()) {
            return;
        }
        LogEvent event = new LogEvent(Instant.now(), level, logger, Thread.currentThread().getName(), message, error);
        PUBLISHING.set(true);
        try {
            for (Entry entry : SINKS) {
                if (level.toInt() < entry.level().toInt()) {
                    continue;
                }
                try {
                    entry.sink().accept(event);
                } catch (RuntimeException e) {
                    // A broken sink would otherwise throw into every caller that logs.
                    // Only the thread that removes it warns, when several catch it throwing at once.
                    if (remove(entry)) {
                        LoggerFactory.getLogger(LogSinks.class).warn("removed log sink {}, which threw", entry.sink(), e);
                    }
                }
            }
        } finally {
            PUBLISHING.set(false);
        }
    }
}
