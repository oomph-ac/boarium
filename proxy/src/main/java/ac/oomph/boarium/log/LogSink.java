package ac.oomph.boarium.log;

/**
 * Receives boarium's log events, for example to forward them to a webhook or a file. Register one with {@link LogSinks#add}.
 * <p>
 * {@link #accept} runs on the thread that logged, which can be a Netty event loop, so it must not block: queue the event and send it from a thread of the sink's own.
 * Logging from inside {@link #accept} only reaches the console, so a sink cannot loop on its own events.
 * A sink that throws is removed, and boarium logs a warning naming it.
 */
@FunctionalInterface
public interface LogSink {
    void accept(LogEvent event);
}
