package ac.oomph.boarium.log;

import org.slf4j.event.Level;

import java.time.Instant;

/**
 * One log line, with its arguments already filled into the message.
 *
 * @param logger the name of the logger, such as {@code ac.oomph.boarium.Boarium} or {@code Boar}
 * @param thread the name of the thread that logged it
 * @param error the throwable logged with it, or null
 */
public record LogEvent(Instant time, Level level, String logger, String thread, String message, Throwable error) {
}
