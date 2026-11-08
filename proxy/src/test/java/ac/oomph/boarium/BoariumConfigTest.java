package ac.oomph.boarium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumConfigTest {

    @Test
    void writesAndReadsTheDefaultConfig(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("config.yml");
        BoariumConfig config = BoariumConfig.load(path);

        assertTrue(Files.exists(path));
        assertEquals(new BoariumConfig(":19132", new BoariumConfig.RakNet(false, 19132), new BoariumConfig.NetherNet(true, 19132, 19132, null, List.of(), true), "127.0.0.1:19133", null, true, "boarium", "boarium", 100, Duration.ofSeconds(3),
                "boarium closed.", true, false, 1), config);
        // The written file says the same as the defaults for missing keys.
        assertEquals(config, BoariumConfig.parse(new StringReader("")));
    }

    @Test
    void readsEachKey() {
        BoariumConfig config = BoariumConfig.parse(new StringReader("""
                listen: 127.0.0.1:19000
                raknet:
                  enabled: true
                  port: 19001
                nethernet:
                  enabled: false
                  port: 19002
                  signaling_port: 19003
                server: lobby:19133
                fallback_server: hub:19133
                xbox_auth: false
                motd: a
                sub_motd: b
                max_players: 5
                latency_interval: 1000
                shutdown_message: bye
                boar_enabled: false
                boar_mitigations: true
                boar_alert_limit: 0
                """));

        assertEquals(new BoariumConfig("127.0.0.1:19000", new BoariumConfig.RakNet(true, 19001), new BoariumConfig.NetherNet(false, 19002, 19003, null, List.of(), true), "lobby:19133", "hub:19133", false, "a", "b", 5, Duration.ofSeconds(1), "bye",
                false, true, 0), config);
    }

    @Test
    void readsAPortOfZeroAsTheListenPort() {
        BoariumConfig config = BoariumConfig.parse(new StringReader("""
                listen: 127.0.0.1:19000
                raknet:
                  enabled: true
                  port: 0
                nethernet:
                  port: 19500
                """));
        assertEquals(new BoariumConfig.RakNet(true, 19000), config.rakNet());
        assertEquals(new BoariumConfig.NetherNet(true, 19500, 19000, null, List.of(), true), config.netherNet());
        // An empty section takes the defaults.
        assertEquals(new BoariumConfig.NetherNet(true, 19000, 19000, null, List.of(), true), BoariumConfig.parse(new StringReader("listen: :19000\nnethernet:\n")).netherNet());
        // A listen port of 0 leaves them 0, for boarium to pick one port at start.
        assertEquals(new BoariumConfig.NetherNet(true, 0, 0, null, List.of(), true), BoariumConfig.parse(new StringReader("listen: 127.0.0.1:0")).netherNet());
    }

    @Test
    void readsTheNetherNetHttpsFilesRelativeToTheConfigFolder(@TempDir Path dir) throws Exception {
        Path path = Files.writeString(dir.resolve("config.yml"), """
                nethernet:
                  https:
                    certificate: tls/chain.pem
                    private_key: /etc/boarium/key.pem
                    password: secret
                """);
        assertEquals(new NetherNetHttps(dir.resolve("tls/chain.pem"), Path.of("/etc/boarium/key.pem"), "secret"), BoariumConfig.load(path).netherNet().https());
        // A PKCS12 file takes no private key.
        assertEquals(new NetherNetHttps(Path.of("cert.p12"), null, null), https("certificate: cert.p12").netherNet().https());
    }

    // YAML reads an unquoted 123456 as a number and yes as true; the error must not print them.
    @Test
    void keepsTheNetherNetPasswordOutOfErrorsAndToString() {
        for (String password : new String[]{"123456", "yes"}) {
            String error = assertThrows(IllegalArgumentException.class, () -> https("certificate: a.p12", "password: " + password)).getMessage();
            assertEquals("nethernet.https.password must be a string; put it in quotes", error);
        }
        BoariumConfig config = https("certificate: a.p12", "password: hunter2");
        assertEquals("hunter2", config.netherNet().https().password());
        assertFalse(config.toString().contains("hunter2"), config.toString());
    }

    // The keys before the raknet and nethernet sections, each turned away with the key to use instead.
    @Test
    void namesTheNewKeyForAnOldOne() {
        assertEquals("nethernet is now a section; set nethernet.enabled instead", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet: true"))).getMessage());
        assertEquals("nethernet is now a section; set nethernet.enabled instead", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet: false"))).getMessage());
        assertEquals("nethernet_certificate is now nethernet.https.certificate", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet_certificate: a.pem"))).getMessage());
        assertEquals("nethernet_private_key is now nethernet.https.private_key", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet_private_key: key.pem"))).getMessage());
        assertEquals("nethernet_password is now nethernet.https.password", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet_password: 123456"))).getMessage());
        // An old key that was left empty is still named.
        assertEquals("nethernet_password is now nethernet.https.password", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet_password: \"\""))).getMessage());
        // All of them at once.
        String error = assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet_password: a\nnethernet: true\nnethernet_certificate: a.pem"))).getMessage();
        assertEquals("nethernet is now a section; set nethernet.enabled instead; nethernet_certificate is now nethernet.https.certificate; nethernet_password is now nethernet.https.password", error);
    }

    // A misspelt key would otherwise be left at its default without a word.
    @Test
    void turnsAwayUnknownKeysInTheSections() {
        assertEquals("raknet.enable is not a key; did you mean raknet.enabled?", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("raknet:\n  enable: true"))).getMessage());
        assertEquals("nethernet.signalling_port is not a key; did you mean nethernet.signaling_port?",
                assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  signalling_port: 19000"))).getMessage());
        assertEquals("nethernet.https.cert is not a key; it takes certificate, private_key, password", assertThrows(IllegalArgumentException.class, () -> https("cert: a.pem")).getMessage());
        assertEquals("nethernet.5 is not a key; it takes enabled, port, signaling_port, https, advertise_addresses, stun_discovery", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  5: x"))).getMessage());
        assertEquals("nethernet.advertise_address is not a key; did you mean nethernet.advertise_addresses?",
                assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  advertise_address: [play.example.com]"))).getMessage());
        assertEquals("nethernet.stun_discover is not a key; did you mean nethernet.stun_discovery?", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  stun_discover: false"))).getMessage());
        // Keys outside the sections are not checked.
        assertEquals(":19132", BoariumConfig.parse(new StringReader("lisen: :19000")).listen());
    }

    @Test
    void readsTheAdvertisedAddressesAndStunDiscovery() {
        BoariumConfig.NetherNet flow = BoariumConfig.parse(new StringReader("""
                nethernet:
                  advertise_addresses: [play.example.com, " 1.1.1.1 ", "1.1.1.1:19500", "2606:4700::1111", "[2606:4700::1111]", "[2606:4700::1111]:19133"]
                  stun_discovery: false
                """)).netherNet();
        assertEquals(List.of("play.example.com", "1.1.1.1", "1.1.1.1:19500", "2606:4700::1111", "[2606:4700::1111]", "[2606:4700::1111]:19133"), flow.advertiseAddresses());
        assertFalse(flow.stunDiscovery());
        BoariumConfig.NetherNet block = BoariumConfig.parse(new StringReader("""
                nethernet:
                  advertise_addresses:
                    - play.example.com:19500
                    - 10.0.0.5
                """)).netherNet();
        assertEquals(List.of("play.example.com:19500", "10.0.0.5"), block.advertiseAddresses());
        assertTrue(block.stunDiscovery());
        assertEquals(List.of(), BoariumConfig.parse(new StringReader("nethernet:\n  advertise_addresses:\n")).netherNet().advertiseAddresses());
        // The same entry twice is kept once.
        assertEquals(List.of("play.example.com", "1.1.1.1"), BoariumConfig.parse(new StringReader("nethernet:\n  advertise_addresses: [play.example.com, \" play.example.com\", 1.1.1.1]")).netherNet().advertiseAddresses());
    }

    @Test
    void turnsAwayBadAdvertisedAddresses() {
        assertEquals("nethernet.advertise_addresses must be a list, such as [play.example.com], got the text \"play.example.com\"", advertiseError("play.example.com"));
        assertEquals("nethernet.advertise_addresses takes host names and IPs, each with an optional port, got 19132", advertiseError("[19132]"));
        assertEquals("nethernet.advertise_addresses takes host names and IPs, each with an optional port, got the text \" \"", advertiseError("[\" \"]"));
        assertEquals("nethernet.advertise_addresses takes host names and IPs, each with an optional port, got {[2606:4700::1111]=19133}; put an IPv6 address in quotes", advertiseError("[[2606:4700::1111]:19133]"));
        for (String bad : new String[]{"1.1.1.1:70000", "1.1.1.1:0", "play.example.com:0", "play.example.com:port", "2606:4700::1111:19133", "[2606:4700::1111", "http://play.example.com", "300.1.1.1"}) {
            String error = advertiseError("[\"" + bad + "\"]");
            assertEquals("nethernet.advertise_addresses: Invalid advertised address " + bad + ", expected an IP or host name, optionally with a port", error);
        }
        // Addresses no player can reach, such as the ones set aside for documentation.
        assertEquals("nethernet.advertise_addresses: Advertised address 198.51.100.1 is special purpose, no player can reach it", advertiseError("[198.51.100.1]"));
        assertTrue(advertiseError("[0.0.0.0]").endsWith("is special purpose, no player can reach it"));
        String tooMany = "[" + String.join(", ", IntStream.range(1, 34).mapToObj(i -> "1.1.1." + i).toList()) + "]";
        assertEquals("nethernet.advertise_addresses takes at most 32 addresses, got 33", advertiseError(tooMany));
        assertEquals("nethernet.stun_discovery must be a boolean, got 1", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  stun_discovery: 1"))).getMessage());
    }

    @Test
    void asksPublicStunServersOnlyWithoutAdvertisedAddressesOffLoopback() {
        InetAddress any = new InetSocketAddress(0).getAddress();
        assertEquals(List.of("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"), Boarium.stunServers(BoariumConfig.parse(new StringReader("")).netherNet(), any));
        assertEquals(List.of(), Boarium.stunServers(BoariumConfig.parse(new StringReader("nethernet:\n  stun_discovery: false")).netherNet(), any));
        assertEquals(List.of(), Boarium.stunServers(BoariumConfig.parse(new StringReader("nethernet:\n  advertise_addresses: [play.example.com]")).netherNet(), any));
        assertEquals(List.of(), Boarium.stunServers(BoariumConfig.parse(new StringReader("")).netherNet(), InetAddress.getLoopbackAddress()));
    }

    private static String advertiseError(String value) {
        return assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  advertise_addresses: " + value))).getMessage();
    }

    @Test
    void turnsAwayRakNetAndNetherNetOnOneUdpPort() {
        String error = assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("raknet:\n  enabled: true"))).getMessage();
        assertEquals("raknet.port and nethernet.port are both UDP port 19132, but RakNet and NetherNet each need their own; set nethernet.port to another port", error);
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("listen: :19000\nraknet:\n  enabled: true\n  port: 19500\nnethernet:\n  port: 19500")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("listen: :19000\nraknet:\n  enabled: true\nnethernet:\n  port: 19000")));
        String zero = assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("listen: 127.0.0.1:0\nraknet:\n  enabled: true"))).getMessage();
        assertEquals("raknet.port and nethernet.port both take the listen port of 0, but RakNet and NetherNet each need their own UDP port; set raknet.port or nethernet.port", zero);
        // NetherNet's signaling is on TCP, so it may share RakNet's port number.
        BoariumConfig config = BoariumConfig.parse(new StringReader("listen: :19000\nraknet:\n  enabled: true\nnethernet:\n  port: 19500\n  signaling_port: 19000"));
        assertEquals(19000, config.rakNet().port());
        // With NetherNet off, RakNet may take any port.
        assertEquals(19132, BoariumConfig.parse(new StringReader("raknet:\n  enabled: true\nnethernet:\n  enabled: false")).rakNet().port());
    }

    @Test
    void turnsAwayRakNetAndNetherNetBothOff() {
        String error = assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  enabled: false"))).getMessage();
        assertEquals("raknet.enabled and nethernet.enabled are both false, so no player could join; turn one on", error);
    }

    @Test
    void listensOnEveryAddressForAnEmptyHost() {
        assertTrue(Boarium.listenAddress(":19132").getAddress().isAnyLocalAddress());
        assertEquals(19132, Boarium.listenAddress(":19132").getPort());
        assertTrue(Boarium.listenAddress("127.0.0.1:19132").getAddress().isLoopbackAddress());
    }

    @Test
    void turnsAwayBadValues() {
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("max_players: many")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("max_players: 3000000000")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("max_players: -5")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("latency_interval: 2.9")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("server: lobby")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("fallback_server: hub")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("listen: 19132")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("latency_interval: 0")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("- a list")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_enabled: 1")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_enabled: \"true\"")));
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_mitigations: sometimes")));
        assertEquals("boar_alert_limit must be a whole number from 0 to 2147483647, got -1", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_alert_limit: -1"))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_alert_limit: 1.5")));
        assertEquals("nethernet.https.private_key and nethernet.https.password need nethernet.https.certificate", assertThrows(IllegalArgumentException.class, () -> https("private_key: key.pem")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> https("password: secret"));
        assertEquals("nethernet.https.certificate must be a string, got 5", assertThrows(IllegalArgumentException.class, () -> https("certificate: 5")).getMessage());
        assertEquals("raknet must be a section of keys, got true", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("raknet: true"))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  https: a.pem")));
        assertEquals("raknet.enabled must be a boolean, got 1", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("raknet:\n  enabled: 1"))).getMessage());
        assertEquals("nethernet.port must be a whole number from 0 to 65535, got 65536", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  port: 65536"))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("nethernet:\n  signaling_port: -1")));
        assertEquals("raknet.port must be a whole number from 0 to 65535, got the text \"19264\"", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("raknet:\n  port: \"19264\""))).getMessage());
        assertEquals("boar_enabled must be a boolean, got the text \"true\"", assertThrows(IllegalArgumentException.class, () -> BoariumConfig.parse(new StringReader("boar_enabled: \"true\""))).getMessage());
        assertNull(BoariumConfig.parse(new StringReader("fallback_server: \"\"")).fallbackServer());
    }

    // A config with the given lines in its nethernet.https section.
    private static BoariumConfig https(String... lines) {
        return BoariumConfig.parse(new StringReader("nethernet:\n  https:\n" + String.join("\n", Arrays.stream(lines).map(line -> "    " + line).toList())));
    }
}
