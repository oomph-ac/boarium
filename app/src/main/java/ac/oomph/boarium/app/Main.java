package ac.oomph.boarium.app;

import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.BoariumConfig;
import ac.oomph.boarium.boar.BoariumBoar;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.protocol.BedrockVersions;
import ac.oomph.boarium.session.Processor;
import ac.oomph.boarium.session.SessionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Runs boarium with the config at the path in its first argument, or config.yml, and with Boar when the config turns it on.
 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        Path path = Path.of(args.length > 0 ? args[0] : "config.yml");
        BoariumConfig config;
        Function<ClientLogin, Processor> processors;
        try {
            config = BoariumConfig.load(path);
            processors = processors(config, path);
        } catch (Exception | Error e) {
            // A block table that cannot load comes as an ExceptionInInitializerError, with the reason as its cause.
            Throwable reason = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
            // An Error is a bug rather than a bad setting, so its stack trace is kept.
            if (e instanceof Error) {
                LOG.error("could not start: {}", reason.toString(), reason);
            } else {
                LOG.error("could not start: {}", reason.toString());
            }
            System.exit(1);
            return;
        }
        Boarium boarium;
        try {
            boarium = Boarium.start(config, processors);
        } catch (Exception e) {
            // Netty also throws checked exceptions it does not declare, such as a BindException.
            LOG.error("could not start on {}: {}", config.listen(), e.toString());
            System.exit(1);
            return;
        }
        LOG.info("listening on {}, sending players to {}, for Bedrock {}", boarium.address(), config.server(), BedrockVersions.names());
        // The event loops keep the JVM running until the shutdown hook below closes them.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down");
            boarium.close();
        }));
    }

    private static Function<ClientLogin, Processor> processors(BoariumConfig config, Path configPath) throws IOException {
        if (!config.boarEnabled()) {
            LOG.info("Boar is off");
            return SessionSettings.DEFAULT.processors();
        }
        Path dataFolder = configPath.toAbsolutePath().resolveSibling("boar");
        Function<ClientLogin, Processor> processors = BoariumBoar.start(dataFolder, config.boarMitigations());
        LOG.info("Boar started with its config in {}, mitigations {}", dataFolder, config.boarMitigations() ? "on" : "off");
        return processors;
    }
}
