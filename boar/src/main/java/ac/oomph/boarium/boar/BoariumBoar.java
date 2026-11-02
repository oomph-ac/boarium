package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.ack.BoarDefaultAcknowledgments;
import ac.boar.anticheat.check.api.BoarDefaultChecks;
import ac.boar.anticheat.config.Config;
import ac.boar.protocol.BoarDefaultPacketListeners;
import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.boar.blocks.BlockTable;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.session.Processor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Runs the Boar anticheat on boarium's players.
 */
public final class BoariumBoar {
    private static final Logger LOG = LoggerFactory.getLogger(BoariumBoar.class);
    private static boolean started;

    private BoariumBoar() {
    }

    /**
     * Starts Boar, which can run once in a JVM, with its config in {@code dataFolder}.
     *
     * @param mitigations whether Boar may change what players do, such as setting them back
     * @return the processors for {@link ac.oomph.boarium.session.SessionSettings}, which give each player to Boar
     * @throws IllegalStateException if Boar has started already
     * @throws IOException if Boar's config cannot be read, or its default cannot be written
     */
    public static synchronized Function<ClientLogin, Processor> start(Path dataFolder, boolean mitigations) throws IOException {
        if (started) {
            throw new IllegalStateException("Boar has started already");
        }
        readConfig(dataFolder);
        // A block table that cannot load fails here with its cause; later uses would only see NoClassDefFoundError.
        BlockTable table = BlockTable.get();
        if (table.protocol() != Boarium.CODEC.getProtocolVersion()) {
            throw new IllegalStateException("The block table is for protocol " + table.protocol() + ", but boarium speaks " + Boarium.CODEC.getProtocolVersion());
        }
        started = true;
        BoariumPlayerManager players = new BoariumPlayerManager(mitigations);
        Boar.getInstance().init(new BoariumPlatform(dataFolder, players));
        BoarDefaultChecks.registerAll(Boar.getInstance().getCheckRegistry());
        BoarDefaultAcknowledgments.registerAll(Boar.getInstance().getAcknowledgmentRegistry());
        BoarDefaultPacketListeners.registerAll();
        BoarProcessor processor = new BoarProcessor(players);
        // Boar's default disable-mitigations: false disagrees with boarium's default, so only a true is worth a warning.
        if (Boar.getConfig().disableMitigations() && mitigations) {
            LOG.warn("Boar's config says disable-mitigations: true, but boarium's boar_mitigations: true decides");
        }
        return login -> processor;
    }

    // Boar's config in dataFolder, written from Boar's default first if there is none. Boar would replace a config it cannot read with its defaults, and only log that.
    static Config readConfig(Path dataFolder) throws IOException {
        try {
            Files.createDirectories(dataFolder);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("Boar's data folder " + dataFolder + " is a file", e);
        }
        Path path = dataFolder.resolve("config.yml");
        if (Files.notExists(path)) {
            // Boar writes its default config from the jar the platform's class is in, which is not Boar's here.
            try (InputStream defaults = Boar.class.getResourceAsStream("/config.yml")) {
                if (defaults == null) {
                    throw new IOException("Boar's default config.yml is not on the classpath");
                }
                Files.copy(defaults, path);
            }
        }
        try {
            // Read as Boar's ConfigLoader reads it.
            return new ObjectMapper(new YAMLFactory())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .readValue(path.toFile(), Config.class);
        } catch (IOException | RuntimeException e) {
            throw new IOException("cannot read Boar's config " + path + ": " + e.getMessage(), e);
        }
    }
}
