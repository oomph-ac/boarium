package ac.oomph.boarium;

import ac.oomph.boarium.transport.Addresses;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * boarium's settings, read from a YAML file. latency_interval and shutdown_message are spectrum-internal's keys.
 *
 * @param netherNetHttps null when NetherNet signaling serves plain HTTP
 */
public record BoariumConfig(String listen, boolean netherNet, NetherNetHttps netherNetHttps, String server, String fallbackServer, boolean xboxAuth, String motd, String subMotd, int maxPlayers, Duration latencyInterval,
                            String shutdownMessage, boolean boarEnabled, boolean boarMitigations) {

    static final String DEFAULT_FILE = """
            # Where players connect to boarium. An empty host means every address of this machine; put an IPv6 address in quotes.
            listen: ":19132"
            # Whether players can also join over NetherNet, which the game tries before RakNet when it joins by address. It takes the TCP side of the listen port, and each NetherNet player another UDP port, so turn it off when only the listen port is open.
            nethernet: true
            # A certificate for NetherNet to serve HTTPS with instead of HTTP: a PEM certificate chain with its PEM private key, or a PKCS12 file alone, with nethernet_private_key left empty. Paths are relative to this file's folder.
            # The game asks its player to trust a server it reaches over plain HTTP.
            nethernet_certificate: ""
            nethernet_private_key: ""
            # The PKCS12 file's password, or the private key's when it is encrypted.
            nethernet_password: ""
            # The Spectrum server players join first.
            server: 127.0.0.1:19133
            # Where players go when their server is lost. Leave it empty for none.
            fallback_server: ""
            # Whether players must sign in with Xbox Live.
            xbox_auth: true
            # What the server list shows.
            motd: boarium
            sub_motd: boarium
            max_players: 100
            # How often, in milliseconds, each player's latency is sent to its server.
            latency_interval: 3000
            # What players see when boarium shuts down.
            shutdown_message: boarium closed.
            # Whether the Boar anticheat checks players. Boar keeps its own config in boar/config.yml, next to this file.
            boar_enabled: true
            # Whether Boar may change what players do: set them back, cancel or undo their actions, make them let go of an item they are using, or kick them.
            # Leave it off until Boar has been tested on a real network.
            boar_mitigations: false
            """;

    /**
     * Reads the config at {@code path}, writing the default one there first if there is none.
     *
     * @throws IllegalArgumentException if the file is not a map of keys, or a value has the wrong type or is out of range
     */
    public static BoariumConfig load(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.writeString(path, DEFAULT_FILE, StandardCharsets.UTF_8);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader, path.toAbsolutePath().getParent());
        }
    }

    static BoariumConfig parse(Reader reader) {
        return parse(reader, Path.of(""));
    }

    // folder is what relative paths in the config are relative to.
    static BoariumConfig parse(Reader reader, Path folder) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        Map<?, ?> values = root == null ? Map.of() : root instanceof Map<?, ?> map ? map : null;
        if (values == null) {
            throw new IllegalArgumentException("the config is not a map of keys to values");
        }
        String listen = address(values, "listen", ":19132");
        String server = address(values, "server", "127.0.0.1:19133");
        String fallback = get(values, "fallback_server", String.class, "");
        if (!fallback.isEmpty()) {
            address(values, "fallback_server", "");
        }
        return new BoariumConfig(
                listen,
                get(values, "nethernet", Boolean.class, true),
                netherNetHttps(values, folder),
                server,
                fallback.isEmpty() ? null : fallback,
                get(values, "xbox_auth", Boolean.class, true),
                get(values, "motd", String.class, "boarium"),
                get(values, "sub_motd", String.class, "boarium"),
                integer(values, "max_players", 100, 0),
                Duration.ofMillis(integer(values, "latency_interval", 3000, 1)),
                get(values, "shutdown_message", String.class, "boarium closed."),
                get(values, "boar_enabled", Boolean.class, true),
                get(values, "boar_mitigations", Boolean.class, false));
    }

    // A host:port, checked now so that a bad one fails at start and not at each join.
    private static String address(Map<?, ?> values, String key, String fallback) {
        String addr = get(values, key, String.class, fallback);
        try {
            Addresses.parse(addr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage(), e);
        }
        return addr;
    }

    private static NetherNetHttps netherNetHttps(Map<?, ?> values, Path folder) {
        String certificate = get(values, "nethernet_certificate", String.class, "");
        String privateKey = get(values, "nethernet_private_key", String.class, "");
        // Not through get(), whose error would print the password.
        Object value = values.get("nethernet_password");
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("nethernet_password must be a string; put it in quotes");
        }
        String password = value == null ? "" : (String) value;
        if (certificate.isEmpty()) {
            if (!privateKey.isEmpty() || !password.isEmpty()) {
                throw new IllegalArgumentException("nethernet_private_key and nethernet_password need nethernet_certificate");
            }
            return null;
        }
        return new NetherNetHttps(folder.resolve(certificate), privateKey.isEmpty() ? null : folder.resolve(privateKey), password.isEmpty() ? null : password);
    }

    // A whole number from min up to Integer.MAX_VALUE; YAML reads larger ones as Long or BigInteger, and decimals as Double.
    private static int integer(Map<?, ?> values, String key, int fallback, int min) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Integer number) || number < min) {
            throw new IllegalArgumentException(key + " must be a whole number from " + min + " to " + Integer.MAX_VALUE + ", got " + value);
        }
        return number;
    }

    private static <T> T get(Map<?, ?> values, String key, Class<T> type, T fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(key + " must be a " + type.getSimpleName().toLowerCase() + ", got " + value);
        }
        return type.cast(value);
    }
}
