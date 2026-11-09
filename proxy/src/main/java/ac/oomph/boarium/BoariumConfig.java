package ac.oomph.boarium;

import ac.oomph.boarium.discord.DiscordEvent;
import ac.oomph.boarium.discord.MessageTemplate;
import ac.oomph.boarium.transport.Addresses;
import org.cloudburstmc.netty.channel.nethernet.signaling.AdvertisedAddresses;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * boarium's settings, read from a YAML file. latency_interval and shutdown_message are spectrum-internal's keys.
 * A port of 0 in the file means the listen port, and is read as that port here, so a port is 0 only when the listen port is.
 */
public record BoariumConfig(String listen, RakNet rakNet, NetherNet netherNet, String server, String fallbackServer, boolean xboxAuth, String motd, String subMotd, int maxPlayers, Duration latencyInterval,
                            String shutdownMessage, boolean boarEnabled, boolean boarMitigations, int boarAlertLimit, Discord discord) {

    /**
     * @param port                     the UDP port
     * @param maxConnectionsPerAddress how many connections one IP may have open at once
     * @param packetLimit              how many UDP packets one IP and port may send in each 10 ms
     * @param rateLimiting             whether maxConnectionsPerAddress and packetLimit apply; while it is false, they are the defaults whatever the file says
     */
    public record RakNet(boolean enabled, int port, int maxConnectionsPerAddress, int packetLimit, boolean rateLimiting) {
    }

    /**
     * @param port               the UDP port NetherNet connections use
     * @param signalingPort      the TCP port of NetherNet's signaling
     * @param https              null when signaling serves plain HTTP
     * @param advertiseAddresses the host names and IPs, each with an optional port, that joining players are told to reach the UDP port at; empty for this machine's own
     * @param stunDiscovery      whether to find this machine's public address through public STUN servers while advertiseAddresses is empty
     */
    public record NetherNet(boolean enabled, int port, int signalingPort, NetherNetHttps https, List<String> advertiseAddresses, boolean stunDiscovery) {
    }

    /**
     * @param username  the name that posts show, or null for the webhook's own
     * @param posts     the events that are posted; an event that is off or has no webhook is not in it
     * @param flagLimit how many of Boar's flags a minute, for each player and check, are posted, or 0 for all
     */
    public record Discord(String username, Map<DiscordEvent, Post> posts, int flagLimit) {
        public record Post(URI webhook, MessageTemplate message) {
            // Without the URL's path, which holds the webhook's token.
            @Override
            public String toString() {
                return "Post[webhook=" + this.webhook.getHost() + ", message=" + this.message + "]";
            }
        }
    }

    static final String DEFAULT_FILE = """
            # Where players connect to boarium: the host, and the port that each port below set to 0 takes. An empty host means every address of this machine; put an IPv6 address in quotes.
            listen: ":19132"
            # RakNet, which the game tries after NetherNet when it joins by address.
            raknet:
              enabled: false
              # The UDP port. 0 means the listen port. It must differ from nethernet's port while both are on.
              port: 0
              # How many RakNet connections one IP may have open at once. RakNet does not let in more, and logs the IP.
              # Each IP may also open only 3 new connections in 4 seconds; a client over that can try again after them.
              max_connections_per_address: 10
              # How many UDP packets one client, an IP and port, may send to RakNet in each 10 milliseconds. A client that sends more is ignored for 10 seconds.
              # Clients send packets in bursts, so a low limit cuts off normal players.
              packet_limit: 120
              # Whether the two limits above apply. Turn it off when players reach RakNet through a proxy, which gives them all its IP.
              rate_limiting: true
            # NetherNet, which the game tries first when it joins by address.
            nethernet:
              enabled: true
              # The UDP port NetherNet connections use. 0 means the listen port.
              port: 0
              # The TCP port of NetherNet's signaling, where the game asks to join. 0 means the listen port, which is where the game looks for it.
              signaling_port: 0
              # Where joining players are told to reach NetherNet's UDP port, for a server behind NAT or port forwarding: host names or IPs, each with an optional port, which is nethernet's port when left out.
              # For example [play.example.com, "play.example.com:19133"], or your public IP; put an IPv6 address with a port in brackets and quotes, as "[IPv6]:19133".
              # A host name is looked up when NetherNet starts, and NetherNet does not start if it does not resolve.
              # Players also get this machine's own addresses, such as its LAN address, unless the list names one of them.
              # Empty means this machine's own addresses, plus the public address that stun_discovery finds.
              advertise_addresses: []
              # Whether to find this machine's public address through Cloudflare's and Google's public STUN servers while advertise_addresses is empty and listen is not a loopback address.
              # boarium contacts them when NetherNet starts, and keeps asking while it runs.
              stun_discovery: true
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
            # When it is off, Boar only logs what it finds.
            boar_mitigations: true
            # How many of Boar's flags a second, for each player and check, boarium logs to its console. 0 logs every flag.
            boar_alert_limit: 1
            # Discord webhooks that boarium posts its logs to. Nothing is posted while every webhook_url is empty.
            discord:
              # The webhook to post to. In Discord, open the channel's settings, then Integrations, Webhooks, New Webhook, and Copy Webhook URL.
              # Keep it secret: anyone who has it can post to the channel.
              webhook_url: ""
              # The name that posts show. Empty keeps the webhook's own name.
              username: boarium
              # Each event posts its message while enabled is true, to its own webhook_url, or to the one above when that is empty.
              # A message may use Discord's formatting, such as **bold**, and the placeholders listed above it, which are filled in as written, without formatting. Keep a message in quotes.
              start:
                enabled: true
                webhook_url: ""
                # {server}: the Spectrum server players join first.
                message: "boarium started, sending players to {server}"
              stop:
                enabled: true
                webhook_url: ""
                message: "boarium stopped"
              join:
                enabled: false
                webhook_url: ""
                # {player}, {xuid}: the player's name and Xbox user ID, which is empty when Xbox Live did not sign the player in. {server}: the server the player joined.
                message: "{player} joined {server}"
              leave:
                enabled: false
                webhook_url: ""
                # {player}, {xuid}
                message: "{player} left"
              # Boar's flags.
              flag:
                enabled: true
                webhook_url: ""
                # {player}, {xuid}, {check}: the check's name and type, {vl}: the player's violation level, {details}: what Boar found, {mitigations}: on or off, as boar_mitigations is,
                # {more}: (+N more) when flags of this player and check were held back by the limit below since the last post, and empty otherwise.
                message: "**{player}** failed {check} x{vl} {details} {more}"
                # How many flags a minute, for each player and check, are posted. 0 posts every flag.
                limit: 2
              # Players that Boar kicks, which it does only while boar_mitigations is true.
              kick:
                enabled: true
                webhook_url: ""
                # {player}, {xuid}, {reason}: the reason Boar gave.
                message: "**{player}** was kicked by Boar: {reason}"
            """;

    // The keys each section of the file takes, by the section's name; "" is the top of the file.
    private static final Map<String, List<String>> SECTIONS = Map.ofEntries(
            Map.entry("", List.of("listen", "raknet", "nethernet", "server", "fallback_server", "xbox_auth", "motd", "sub_motd", "max_players", "latency_interval", "shutdown_message", "boar_enabled", "boar_mitigations",
                    "boar_alert_limit", "discord")),
            Map.entry("raknet", List.of("enabled", "port", "max_connections_per_address", "packet_limit", "rate_limiting")),
            Map.entry("nethernet", List.of("enabled", "port", "signaling_port", "https", "advertise_addresses", "stun_discovery")),
            Map.entry("nethernet.https", List.of("certificate", "private_key", "password")),
            Map.entry("discord", List.of("webhook_url", "username", "start", "stop", "join", "leave", "flag", "kick")),
            Map.entry("discord.start", List.of("enabled", "webhook_url", "message")),
            Map.entry("discord.stop", List.of("enabled", "webhook_url", "message")),
            Map.entry("discord.join", List.of("enabled", "webhook_url", "message")),
            Map.entry("discord.leave", List.of("enabled", "webhook_url", "message")),
            Map.entry("discord.flag", List.of("enabled", "webhook_url", "message", "limit")),
            Map.entry("discord.kick", List.of("enabled", "webhook_url", "message")));

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
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (DuplicateKeyException e) {
            // SnakeYAML names the key only in its message, as "found duplicate key <key>", and its message spans several lines.
            String key = e.getProblem().substring("found duplicate key ".length());
            throw new IllegalArgumentException(key + " is set twice; the second is on line " + (e.getProblemMark().getLine() + 1), e);
        }
        Map<?, ?> map = root == null ? Map.of() : root instanceof Map<?, ?> m ? m : null;
        if (map == null) {
            throw new IllegalArgumentException("the config is not a map of keys to values");
        }
        // Every bad key in one error, before any value is read, so that one restart fixes them all.
        List<String> errors = new ArrayList<>();
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
            errors.add(String.join("; ", moved));
        }
        checkKeys(map, "", errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        Section values = new Section(map, "");
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
                values.get("boar_mitigations", Boolean.class, true),
                values.integer("boar_alert_limit", 1, 0, Integer.MAX_VALUE),
                discord(values.section("discord")));
    }

    // Adds a line to errors naming each key of section that it does not take, as a misspelt key would otherwise be left at its default without a word, then does the same for the sections inside it.
    private static void checkKeys(Map<?, ?> map, String section, List<String> errors) {
        List<String> keys = SECTIONS.get(section);
        List<String> unknown = new ArrayList<>();
        for (Object key : map.keySet()) {
            String name = String.valueOf(key);
            boolean moved = section.isEmpty() && MOVED_KEYS.stream().anyMatch(entry -> entry.getKey().equals(name));
            if (!keys.contains(name) && !moved) {
                String closest = keys.stream().min(Comparator.comparingInt(k -> distance(k, name))).orElseThrow();
                if (Section.isUrl(name)) {
                    unknown.add("a URL");
                } else {
                    unknown.add(distance(closest, name) <= Math.min(2, name.length() / 2) ? name + " (did you mean " + closest + "?)" : name);
                }
            }
        }
        if (!unknown.isEmpty()) {
            String place = section.isEmpty() ? "boarium" : section;
            errors.add("unknown key" + (unknown.size() > 1 ? "s" : "") + (section.isEmpty() ? "" : " in " + section) + ": " + String.join(", ", unknown) + "; " + place + " takes: " + String.join(", ", keys));
        }
        for (String key : keys) {
            String child = section.isEmpty() ? key : section + "." + key;
            if (SECTIONS.containsKey(child) && map.get(key) instanceof Map<?, ?> values) {
                checkKeys(values, child, errors);
            }
        }
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

    private static RakNet rakNet(Section values, int listenPort) {
        boolean enabled = values.get("enabled", Boolean.class, false);
        int port = values.port("port", listenPort);
        boolean rateLimiting = values.get("rate_limiting", Boolean.class, true);
        // The limits are unused while rate_limiting is false, so they are not checked then either.
        if (!rateLimiting) {
            return new RakNet(enabled, port, 10, 120, false);
        }
        return new RakNet(enabled, port, rakNetLimit(values, "max_connections_per_address", 10), rakNetLimit(values, "packet_limit", 120), true);
    }

    // 0 is turned away with its own error, since someone who sets it most likely wants the limit off, which rate_limiting does.
    private static int rakNetLimit(Section values, String key, int fallback) {
        if (Integer.valueOf(0).equals(values.value(key))) {
            throw new IllegalArgumentException(values.name(key) + " must be at least 1; set " + values.name("rate_limiting") + " to false to turn RakNet's limits off");
        }
        return values.integer(key, fallback, 1, Integer.MAX_VALUE);
    }

    private static NetherNet netherNet(Section values, int listenPort, Path folder) {
        int port = values.port("port", listenPort);
        return new NetherNet(values.get("enabled", Boolean.class, true), port, values.port("signaling_port", listenPort), netherNetHttps(values.section("https"), folder), advertiseAddresses(values, port),
                values.get("stun_discovery", Boolean.class, true));
    }

    // Checked here with the NetherNet library's own rules, so that a bad entry stops boarium at start; host names are only looked up when NetherNet starts.
    private static List<String> advertiseAddresses(Section values, int port) {
        String key = "advertise_addresses";
        Object value = values.value(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(values.name(key) + " must be a list, such as [play.example.com], got " + Section.describe(value));
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String address) || address.isBlank()) {
                // YAML reads an unquoted [IPv6]:port in a list as a map.
                String hint = entry instanceof Map<?, ?> ? "; put an IPv6 address in quotes" : "";
                throw new IllegalArgumentException(values.name(key) + " takes host names and IPs, each with an optional port, got " + Section.describe(entry) + hint);
            }
            addresses.add(address.trim());
        }
        int count;
        try {
            count = AdvertisedAddresses.parse(addresses, port).size();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(values.name(key) + ": " + e.getMessage(), e);
        }
        if (count > AdvertisedAddresses.MAX_ENDPOINTS) {
            throw new IllegalArgumentException(values.name(key) + " takes at most " + AdvertisedAddresses.MAX_ENDPOINTS + " addresses, got " + count);
        }
        return List.copyOf(addresses);
    }

    private static Discord discord(Section values) {
        URI webhook = webhookUrl(values);
        String username = values.get("username", String.class, "boarium").strip();
        // Discord turns away a webhook post whose name is too long or has these words in it.
        String lower = username.toLowerCase(Locale.ROOT);
        if (username.length() > 80 || lower.contains("discord") || lower.contains("clyde")) {
            throw new IllegalArgumentException(values.name("username") + " must be at most 80 characters, without the words discord and clyde, which Discord does not allow");
        }
        Map<DiscordEvent, Discord.Post> posts = new EnumMap<>(DiscordEvent.class);
        for (DiscordEvent event : DiscordEvent.values()) {
            Section section = values.section(event.key());
            URI own = webhookUrl(section);
            String text = section.get("message", String.class, event.defaultMessage());
            if (text.isBlank()) {
                throw new IllegalArgumentException(section.name("message") + " is empty; write the message to post, as an event that is off may be turned on later");
            }
            MessageTemplate message;
            try {
                message = MessageTemplate.parse(text, event.placeholders());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(section.name("message") + ": " + e.getMessage(), e);
            }
            if (section.get("enabled", Boolean.class, event.enabledByDefault()) && (own != null || webhook != null)) {
                posts.put(event, new Discord.Post(own != null ? own : webhook, message));
            }
        }
        return new Discord(username.isEmpty() ? null : username, posts, values.section("flag").integer("limit", 2, 0, Integer.MAX_VALUE));
    }

    // The webhook_url of a section, or null when it is empty.
    private static URI webhookUrl(Section values) {
        Object value = values.value("webhook_url");
        if (value == null || "".equals(value)) {
            return null;
        }
        String problem = values.name("webhook_url") + " must be an https:// webhook URL, as Discord's Copy Webhook URL gives";
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(problem);
        }
        try {
            URI uri = new URI(text.strip());
            // Not http, which would send the webhook's token in the clear.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException(problem);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(problem);
        }
    }

    private static NetherNetHttps netherNetHttps(Section values, Path folder) {
        String certificate = values.get("certificate", String.class, "");
        String privateKey = values.get("private_key", String.class, "");
        // Not through get(), whose error would print the password.
        Object value = values.value("password");
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

    // One section of the file, named as in SECTIONS; errors put that name in front of a key, as in nethernet.https.password.
    private record Section(Map<?, ?> map, String section) {

        String name(String key) {
            return this.section.isEmpty() ? key : this.section + "." + key;
        }

        // Every read goes through here, so that reading a key that checkKeys would turn away fails every parse.
        Object value(String key) {
            List<String> keys = SECTIONS.get(this.section);
            if (keys == null || !keys.contains(key)) {
                throw new IllegalStateException(this.name(key) + " is read but is not in SECTIONS");
            }
            return this.map.get(key);
        }

        // An absent or empty section reads as one with no keys.
        Section section(String key) {
            Object value = this.value(key);
            if (value != null && !(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(this.name(key) + " must be a section of keys, got " + describe(value));
            }
            return new Section(value == null ? Map.of() : (Map<?, ?>) value, this.name(key));
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
            Object value = this.value(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Integer number) || number < min || number > max) {
                throw new IllegalArgumentException(this.name(key) + " must be a whole number from " + min + " to " + max + ", got " + describe(value));
            }
            return number;
        }

        <T> T get(String key, Class<T> type, T fallback) {
            Object value = this.value(key);
            if (value == null) {
                return fallback;
            }
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException(this.name(key) + " must be a " + type.getSimpleName().toLowerCase() + ", got " + describe(value));
            }
            return type.cast(value);
        }

        // A value as it reads in an error, where text in quotes shows it is not the number or boolean it looks like.
        static String describe(Object value) {
            String text = String.valueOf(value);
            if (isUrl(text)) {
                return "a URL";
            }
            return value instanceof String ? "the text \"" + text + "\"" : text;
        }

        // Errors leave URLs out, since a Discord webhook's URL lets anyone who has it post to its channel.
        static boolean isUrl(String text) {
            return text.contains("://") || text.contains("/webhooks/");
        }
    }
}
