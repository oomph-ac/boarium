package ac.oomph.boarium.log;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/** Writes to slf4j-simple's logger of the same name, and sends the same line to the sinks that want its level. */
final class SinkLogger extends LegacyAbstractLogger {
    private final transient Logger console;

    SinkLogger(String name, Logger console) {
        this.name = name;
        this.console = console;
    }

    @Override
    public boolean isTraceEnabled() {
        return this.console.isTraceEnabled() || LogSinks.wants(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return this.console.isDebugEnabled() || LogSinks.wants(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return this.console.isInfoEnabled() || LogSinks.wants(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return this.console.isWarnEnabled() || LogSinks.wants(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return this.console.isErrorEnabled() || LogSinks.wants(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments, Throwable error) {
        String message = MessageFormatter.basicArrayFormat(pattern, arguments);
        // The (String, Throwable) methods leave the message as it is, so braces from the arguments are not filled again.
        switch (level) {
            case ERROR -> this.console.error(message, error);
            case WARN -> this.console.warn(message, error);
            case INFO -> this.console.info(message, error);
            case DEBUG -> this.console.debug(message, error);
            case TRACE -> this.console.trace(message, error);
        }
        LogSinks.publish(level, this.name, message, error);
    }
}
