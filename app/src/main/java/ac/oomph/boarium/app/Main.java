package ac.oomph.boarium.app;

import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.BoariumConfig;
import ac.oomph.boarium.boar.BoariumBoar;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.flag.WebhookFlagSink;
import ac.oomph.boarium.protocol.BedrockVersions;
import ac.oomph.boarium.session.Processor;
import ac.oomph.boarium.session.SessionSettings;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Runs one proxy process with one or more route configs, or config.yml, sharing Boar across routes.
 */
public final class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        List<Path> paths = args.length == 0 ? List.of(Path.of("config.yml"))
                : java.util.Arrays.stream(args).map(Path::of).toList();
        List<Boarium> routes = new CopyOnWriteArrayList<>();
        try {
            List<BoariumConfig> configs = new ArrayList<>();
            List<SslContext> tls = new ArrayList<>();
            for (Path path : paths) {
                BoariumConfig config = BoariumConfig.load(path);
                configs.add(config);
                tls.add(netherNetTls(config));
            }
            BoariumConfig first = configs.getFirst();
            for (int i = 1; i < configs.size(); i++) {
                BoariumConfig config = configs.get(i);
                // Boar's other settings, its flag webhook included, mean nothing while it is off on every route.
                if (config.boarEnabled() != first.boarEnabled() || first.boarEnabled() && (config.boarMitigations() != first.boarMitigations()
                        || config.boarAlertLimit() != first.boarAlertLimit() || !Objects.equals(config.flagWebhook(), first.flagWebhook()))) {
                    throw new IllegalArgumentException(paths.get(i) + ": routes in one JVM must agree on boar_enabled, and while Boar is on, on boar_mitigations, boar_alert_limit and flag_webhook");
                }
            }
            // Boar is a JVM singleton, and so is the webhook its flags go to; each flag names the server its player was on.
            WebhookFlagSink webhook = flagWebhook(first);
            // Boar is a JVM singleton. All routes use the first config's data folder and processors.
            Function<ClientLogin, Processor> processors = processors(first, paths.getFirst(), webhook);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("shutting down");
                close(routes);
                // After the routes, so that it posts the flags of players who were still on.
                if (webhook != null) {
                    webhook.close();
                }
            }));
            for (int i = 0; i < configs.size(); i++) {
                Path path = paths.get(i).toAbsolutePath();
                BoariumConfig config = configs.get(i);
                // Each route keeps its original identity, independently of the shared Boar settings.
                routes.add(Boarium.start(config, tls.get(i), path.resolveSibling("nethernet").resolve("identity.pem"), processors));
                LOG.info("started, sending players to {}, for Bedrock {}", config.server(), BedrockVersions.names());
                LOG.info("route config: {}", path);
            }
        } catch (Exception | Error e) {
            Throwable reason = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
            if (e instanceof Error) {
                LOG.error("could not start: {}", reason.toString(), reason);
            } else {
                LOG.error("could not start: {}", reason.toString());
            }
            // The shutdown hook, added before the first route starts, closes the routes already started.
            System.exit(1);
        }
    }

    // Closes the routes at once, so that none keeps taking logins while another waits for its players, and one that throws does not keep the rest open.
    private static void close(List<Boarium> routes) {
        List<Thread> closing = new ArrayList<>();
        for (Boarium route : routes) {
            closing.add(Thread.ofPlatform().name("boarium-close").start(() -> {
                try {
                    route.close();
                } catch (Throwable t) {
                    LOG.error("could not close a route", t);
                }
            }));
        }
        for (Thread thread : closing) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // Null for plain HTTP. Never falls back to HTTP: a certificate that cannot be read stops boarium.
    private static SslContext netherNetTls(BoariumConfig config) {
        if (config.netherNet().https() == null) {
            return null;
        }
        if (!config.netherNet().enabled()) {
            LOG.warn("nethernet.enabled is false, so nethernet.https.certificate is not used");
            return null;
        }
        return config.netherNet().https().load();
    }

    // Null when flag_webhook has no URL, or Boar is off.
    private static WebhookFlagSink flagWebhook(BoariumConfig config) {
        BoariumConfig.FlagWebhook settings = config.flagWebhook();
        if (settings == null) {
            return null;
        }
        if (!config.boarEnabled()) {
            LOG.warn("flag_webhook is set, but boar_enabled is false, so there are no flags to post");
            return null;
        }
        LOG.info("posting Boar's flags to {}://{}, {}", settings.url().getScheme(), settings.url().getHost(),
                settings.limit() == 0 ? "every flag" : "at most " + settings.limit() + " a minute for each player and check");
        return new WebhookFlagSink(settings.url(), settings.secret());
    }

    private static Function<ClientLogin, Processor> processors(BoariumConfig config, Path configPath, WebhookFlagSink webhook) throws IOException {
        if (!config.boarEnabled()) {
            LOG.info("Boar is off");
            return SessionSettings.DEFAULT.processors();
        }
        Path dataFolder = configPath.toAbsolutePath().resolveSibling("boar");
        List<BoariumBoar.FlagOutput> flagSinks = webhook == null ? List.of() : List.of(new BoariumBoar.FlagOutput(webhook, config.flagWebhook().limit()));
        Function<ClientLogin, Processor> processors = BoariumBoar.start(dataFolder, config.boarMitigations(), config.boarAlertLimit(), flagSinks);
        LOG.info("Boar started with its config in {}, mitigations {}", dataFolder, config.boarMitigations() ? "on" : "off");
        return processors;
    }
}
