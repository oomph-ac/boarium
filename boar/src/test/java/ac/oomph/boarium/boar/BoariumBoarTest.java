package ac.oomph.boarium.boar;

import ac.oomph.boarium.discord.DiscordLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumBoarTest {

    @Test
    void writesAndReadsTheDefaultConfig(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("boar");

        assertFalse(BoariumBoar.readConfig(folder).disableMitigations());
        assertTrue(Files.exists(folder.resolve("config.yml")));
    }

    @Test
    void readsTheConfigThere(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yml"), "disable-mitigations: true\n");

        assertTrue(BoariumBoar.readConfig(dir).disableMitigations());
    }

    @Test
    void turnsAwayAConfigItCannotRead(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, "disabled-checks: [oops\n");
        assertTrue(assertThrows(IOException.class, () -> BoariumBoar.readConfig(dir)).getMessage().startsWith("cannot read Boar's config " + config + ": "));

        Files.writeString(config, "max-latency-wait: soon\n");
        assertThrows(IOException.class, () -> BoariumBoar.readConfig(dir));
    }

    @Test
    void turnsAwayAFileForTheFolder(@TempDir Path dir) throws Exception {
        Path folder = Files.writeString(dir.resolve("boar"), "");

        assertEquals("Boar's data folder " + folder + " is a file", assertThrows(IOException.class, () -> BoariumBoar.readConfig(folder)).getMessage());
    }

    // Checked before Boar starts, which it can only do once in a JVM.
    @Test
    void turnsAwayANegativeAlertLimit(@TempDir Path dir) {
        assertEquals("alertLimit must be 0 or more, got -1", assertThrows(IllegalArgumentException.class, () -> BoariumBoar.start(dir, false, -1, DiscordLog.NONE)).getMessage());
    }
}
