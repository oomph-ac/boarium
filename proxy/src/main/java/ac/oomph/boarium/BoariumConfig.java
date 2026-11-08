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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * boarium's settings, read from a YAML file. latency_interval and shutdown_message are spectrum-internal's keys.
 * A port of 0 in the file means the listen port, and is read as that port here, so a port is 0 only when the listen port is.
 */
public record BoariumConfig(String listen, RakNet rakNet, NetherNet netherNet, String server, String fallbackServer, boolean xboxAuth, String motd, String subMotd, int maxPlayers, Duration latencyInterval,
                            String shutdownMessage, boolean boarEnabled, boolean boarMitigations) {

    /**
     * @param port the UDP port
     */
    public record RakNet(boolean enabled, int port) {
    }

    /**
     * @param port          the UDP port NetherNet connections use
     * @param signalingPort the TCP port of NetherNet's signaling
     * @param https         null when signaling serves plain HTTP
     */
    public record NetherNet(boolean enabled, int port, int signalingPort, NetherNetHttps https) {
    }

    static final String DEFAULT_FILE = """
            # Where players connect to boarium: the host, and the port that each port below set to 0 takes. An empty host means every address of this machine; put an IPv6 address in quotes.
            listen: ":19132"
            # RakNet, which the game tries after NetherNet when it joins by address.
            raknet:
              enabled: false
              # The UDP port. 0 means the listen port. It must differ from nethernet's port while both are on.
              port: 0
            # NetherNet, which the game tries first when it joins by address.
            nethernet:
              enabled: true
              # The UDP port NetherNet connections use. 0 means the listen port.
              port: 0
              # The TCP port of NetherNet's signaling, where the game asks to join. 0 means the listen port, which is where the game looks for it.
              signaling_port: 0
              # A certificate for signaling to serve HTTPS with instead of HTTP: a PEM certificate chain with its PEM private key, or a PKCS12 file alone, with private_key left empty. Paths are relative to this file's folder.
              # The game asks its player to trust a server it reaches over plain HTTP.
              https:
                certificate: ""
                private_key: ""
                # The PKCS12 file's password, or the private key's when it is encrypted.
                password: ""
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

    // Keys of an older layout, each with the key that took its place.
    private static final List<Map.Entry<String, String>> MOVED_KEYS = List.of(
            Map.entry("nethernet_certificate", "nethernet.https.certificate"),
            Map.entry("nethernet_private_key", "nethernet.https.private_key"),
            Map.entry("nethernet_password", "nethernet.https.password"));

    /**
     * Reads the config at {@code path}, writing the default one there first if there is none.
     *
     * @throws IllegalArgumentException if the file is not a map of keys, a value has the wrong type or is out of range, or the transports cannot run as set
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
        Map<?, ?> map = root == null ? Map.of() : root instanceof Map<?, ?> m ? m : null;
        if (map == null) {
            throw new IllegalArgumentException("the config is not a map of keys to values");
        }
        Section values = new Section(map, "");
        List<String> moved = new ArrayList<>();
        if (map.get("nethernet") instanceof Boolean) {
            moved.add("nethernet is now a section; set nethernet.enabled instead");
        }
        for (Map.Entry<String, String> key : MOVED_KEYS) {
            if (map.containsKey(key.getKey())) {
                moved.add(key.getKey() + " is now " + key.getValue());
            }
        }
        if (!moved.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", moved));
        }
        String listen = values.address("listen", ":19132");
        int listenPort = Addresses.parse(listen).getPort();
        String server = values.address("server", "127.0.0.1:19133");
        String fallback = values.get("fallback_server", String.class, "");
        if (!fallback.isEmpty()) {
            values.address("fallback_server", "");
        }
        RakNet rakNet = rakNet(values.section("raknet"), listenPort);
        NetherNet netherNet = netherNet(values.section("nethernet"), listenPort, folder);
        if (!rakNet.enabled() && !netherNet.enabled()) {
            throw new IllegalArgumentException("raknet.enabled and nethernet.enabled are both false, so no player could join; turn one on");
        }
        if (rakNet.enabled() && netherNet.enabled() && rakNet.port() == netherNet.port()) {
            if (listenPort == 0 && rakNet.port() == 0) {
                throw new IllegalArgumentException("raknet.port and nethernet.port both take the listen port of 0, but RakNet and NetherNet each need their own UDP port; set raknet.port or nethernet.port");
            }
            throw new IllegalArgumentException("raknet.port and nethernet.port are both UDP port " + rakNet.port() + ", but RakNet and NetherNet each need their own; set nethernet.port to another port");
        }
        return new BoariumConfig(
                listen,
                rakNet,
                netherNet,
                server,
                fallback.isEmpty() ? null : fallback,
                values.get("xbox_auth", Boolean.class, true),
                values.get("motd", String.class, "boarium"),
                values.get("sub_motd", String.class, "boarium"),
                values.integer("max_players", 100, 0, Integer.MAX_VALUE),
                Duration.ofMillis(values.integer("latency_interval", 3000, 1, Integer.MAX_VALUE)),
                values.get("shutdown_message", String.class, "boarium closed."),
                values.get("boar_enabled", Boolean.class, true),
                values.get("boar_mitigations", Boolean.class, false));
    }

    private static RakNet rakNet(Section values, int listenPort) {
        values.allow("enabled", "port");
        return new RakNet(values.get("enabled", Boolean.class, false), values.port("port", listenPort));
    }

    private static NetherNet netherNet(Section values, int listenPort, Path folder) {
        values.allow("enabled", "port", "signaling_port", "https");
        return new NetherNet(values.get("enabled", Boolean.class, true), values.port("port", listenPort), values.port("signaling_port", listenPort), netherNetHttps(values.section("https"), folder));
    }

    private static NetherNetHttps netherNetHttps(Section values, Path folder) {
        values.allow("certificate", "private_key", "password");
        String certificate = values.get("certificate", String.class, "");
        String privateKey = values.get("private_key", String.class, "");
        // Not through get(), whose error would print the password.
        Object value = values.map().get("password");
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(values.name("password") + " must be a string; put it in quotes");
        }
        String password = value == null ? "" : (String) value;
        if (certificate.isEmpty()) {
            if (!privateKey.isEmpty() || !password.isEmpty()) {
                throw new IllegalArgumentException(values.name("private_key") + " and " + values.name("password") + " need " + values.name("certificate"));
            }
            return null;
        }
        return new NetherNetHttps(folder.resolve(certificate), privateKey.isEmpty() ? null : folder.resolve(privateKey), password.isEmpty() ? null : password);
    }

    // The keys of one section of the file, named in errors with the section in front, as in nethernet.https.password.
    private record Section(Map<?, ?> map, String prefix) {

        String name(String key) {
            return this.prefix + key;
        }

        // Turns away a key that is not known, as a misspelt key would otherwise be left at its default without a word.
        void allow(String... known) {
            for (Object key : this.map.keySet()) {
                if (!List.of(known).contains(String.valueOf(key))) {
                    String closest = Arrays.stream(known).min(Comparator.comparingInt(k -> distance(k, String.valueOf(key)))).orElseThrow();
                    String hint = distance(closest, String.valueOf(key)) <= 2 ? "; did you mean " + this.name(closest) + "?" : "; it takes " + String.join(", ", known);
                    throw new IllegalArgumentException(this.name(String.valueOf(key)) + " is not a key" + hint);
                }
            }
        }

        // An absent or empty section reads as one with no keys.
        Section section(String key) {
            Object value = this.map.get(key);
            if (value != null && !(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(this.name(key) + " must be a section of keys, got " + value);
            }
            return new Section(value == null ? Map.of() : (Map<?, ?>) value, this.name(key) + ".");
        }

        // A host:port, checked now so that a bad one fails at start and not at each join.
        String address(String key, String fallback) {
            String addr = this.get(key, String.class, fallback);
            try {
                Addresses.parse(addr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(this.name(key) + ": " + e.getMessage(), e);
            }
            return addr;
        }

        // 0 means the listen port.
        int port(String key, int listenPort) {
            int port = this.integer(key, 0, 0, 0xffff);
            return port == 0 ? listenPort : port;
        }

        // A whole number from min to max; YAML reads numbers past Integer.MAX_VALUE as Long or BigInteger, and decimals as Double.
        int integer(String key, int fallback, int min, int max) {
            Object value = this.map.get(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Integer number) || number < min || number > max) {
                throw new IllegalArgumentException(this.name(key) + " must be a whole number from " + min + " to " + max + ", got " + describe(value));
            }
            return number;
        }

        <T> T get(String key, Class<T> type, T fallback) {
            Object value = this.map.get(key);
            if (value == null) {
                return fallback;
            }
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException(this.name(key) + " must be a " + type.getSimpleName().toLowerCase() + ", got " + describe(value));
            }
            return type.cast(value);
        }

        // A value as it reads in an error, where text in quotes shows it is not the number or boolean it looks like.
        private static String describe(Object value) {
            return value instanceof String text ? "the text \"" + text + "\"" : String.valueOf(value);
        }

        // The Levenshtein distance: how many letters to add, remove or change to turn a into b.
        private static int distance(String a, String b) {
            int[] row = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) {
                row[j] = j;
            }
            for (int i = 1; i <= a.length(); i++) {
                int diagonal = row[0];
                row[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int above = row[j];
                    row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                    diagonal = above;
                }
            }
            return row[b.length()];
        }
    }
}
