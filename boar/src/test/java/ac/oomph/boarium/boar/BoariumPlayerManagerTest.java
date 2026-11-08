package ac.oomph.boarium.boar;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumPlayerManagerTest {

    @Test
    void disablesMitigationsUnlessTheyAreOn() {
        assertTrue(new BoariumPlayerManager(false, 1).shouldDisableMitigations(null));
        assertFalse(new BoariumPlayerManager(true, 1).shouldDisableMitigations(null));
    }

    // UWP (7), WIN32 (8) and XBOX (13), as in Geyser's BedrockPlatform. 0 is what a client data without a DeviceOS reads as, and -1 and 16 are outside Geyser's platforms.
    @Test
    void onlyWindowsAndXboxSeedRandomOffsetsFromWideX() {
        for (int deviceOs = -1; deviceOs <= 16; deviceOs++) {
            boolean wide = deviceOs == 7 || deviceOs == 8 || deviceOs == 13;
            assertEquals(wide, new BoariumPlayerManager.BoariumNetworkSession(null, deviceOs, null).wideRandomOffsetSeed(), "DeviceOS " + deviceOs);
        }
    }

    // A DeviceOS that does not fit in an int is unknown, not its low 32 bits (4294967303 is 2^32 + 7).
    @Test
    void readsOnlyAnIntDeviceOs() {
        assertEquals(7, BoariumPlayerManager.deviceOs("{\"DeviceOS\":7}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, BoariumPlayerManager.deviceOs("{\"DeviceOS\":4294967303}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, BoariumPlayerManager.deviceOs("{}".getBytes(StandardCharsets.UTF_8)));
    }
}
