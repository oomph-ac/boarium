package ac.oomph.boarium.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.simple.SimpleServiceProvider;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The SLF4J provider that boarium logs through: slf4j-simple's console, which still reads its {@code org.slf4j.simpleLogger.*} settings, with each line also sent to {@link LogSinks}.
 * <p>
 * It is not listed in META-INF/services, since SLF4J would then find two providers and pick either. {@link #PROPERTY} selects it instead.
 */
public final class SinkServiceProvider implements SLF4JServiceProvider {
    /** The system property that tells SLF4J which provider to use. It must be set before anything logs. */
    public static final String PROPERTY = "slf4j.provider";

    private final SimpleServiceProvider console = new SimpleServiceProvider();
    private SinkLoggerFactory loggers;

    // SLF4J reports at INFO that it loads a provider named by PROPERTY, which would add a line to every start.
    private static final String VERBOSITY = "slf4j.internal.verbosity";

    /** Selects this provider, unless the JVM was started with another. */
    public static void select() {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, SinkServiceProvider.class.getName());
            if (System.getProperty(VERBOSITY) == null) {
                System.setProperty(VERBOSITY, "WARN");
            }
        }
    }

    static boolean active() {
        return LoggerFactory.getILoggerFactory() instanceof SinkLoggerFactory;
    }

    @Override
    public void initialize() {
        this.console.initialize();
        this.loggers = new SinkLoggerFactory(this.console.getLoggerFactory());
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return this.loggers;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return this.console.getMarkerFactory();
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return this.console.getMDCAdapter();
    }

    @Override
    public String getRequestedApiVersion() {
        return this.console.getRequestedApiVersion();
    }
}
