package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.ack.BoarDefaultAcknowledgments;
import ac.boar.anticheat.check.api.BoarDefaultChecks;
import ac.boar.protocol.BoarDefaultPacketListeners;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.session.Processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Runs the Boar anticheat on boarium's players.
 */
public final class BoariumBoar {
    private static boolean started;

    private BoariumBoar() {
    }

    /**
     * Starts Boar, which can run once in a JVM, with its config in {@code dataFolder}.
     *
     * @return the processors for {@link ac.oomph.boarium.session.SessionSettings}, which give each player to Boar
     * @throws IllegalStateException if Boar has started already
     * @throws IOException if Boar's default config cannot be written
     */
    public static synchronized Function<ClientLogin, Processor> start(Path dataFolder) throws IOException {
        if (started) {
            throw new IllegalStateException("Boar has started already");
        }
        // Boar writes its default config from the jar the platform's class is in, which is not Boar's here.
        Path config = dataFolder.resolve("config.yml");
        if (Files.notExists(config)) {
            Files.createDirectories(dataFolder);
            try (InputStream defaults = Boar.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Boar's default config.yml is not on the classpath");
                }
                Files.copy(defaults, config);
            }
        }
        started = true;
        BoariumPlayerManager players = new BoariumPlayerManager();
        Boar.getInstance().init(new BoariumPlatform(dataFolder, players));
        BoarDefaultChecks.registerAll(Boar.getInstance().getCheckRegistry());
        BoarDefaultAcknowledgments.registerAll(Boar.getInstance().getAcknowledgmentRegistry());
        BoarDefaultPacketListeners.registerAll();
        BoarProcessor processor = new BoarProcessor(players);
        return login -> processor;
    }
}
