package ac.oomph.boarium.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class SinkLoggerFactory implements ILoggerFactory {
    private final ILoggerFactory console;
    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    SinkLoggerFactory(ILoggerFactory console) {
        this.console = console;
    }

    @Override
    public Logger getLogger(String name) {
        return this.loggers.computeIfAbsent(name, n -> new SinkLogger(n, this.console.getLogger(n)));
    }
}
