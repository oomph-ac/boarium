package ac.oomph.boarium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumConfigTest {

    @Test
    void writesAndReadsTheDefaultConfig(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("config.yml");
        BoariumConfig config = BoariumConfig.load(path);

        assertTrue(Files.exists(path));
        assertEquals(new BoariumConfig(":19132", true, "127.0.0.1:19133", null, true, "boarium", "boarium", 100, Duration.ofSeconds(3), "boarium closed.", true, false), config);
        // The written file says the same as the defaults for missing keys.
        assertEquals(config, BoariumConfig.parse(new StringReader("")));
    }

    @Test
    void readsEachKey() {
        BoariumConfig config = BoariumConfig.parse(new StringReader("""
                listen: 127.0.0.1:19000
                nethernet: false
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
                """));

        assertEquals(new BoariumConfig("127.0.0.1:19000", false, "lobby:19133", "hub:19133", false, "a", "b", 5, Duration.ofSeconds(1), "bye", false, true), config);
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
        assertNull(BoariumConfig.parse(new StringReader("fallback_server: \"\"")).fallbackServer());
    }
}
