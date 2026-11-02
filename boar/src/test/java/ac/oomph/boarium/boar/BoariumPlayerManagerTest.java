package ac.oomph.boarium.boar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumPlayerManagerTest {

    @Test
    void disablesMitigationsUnlessTheyAreOn() {
        assertTrue(new BoariumPlayerManager(false).shouldDisableMitigations(null));
        assertFalse(new BoariumPlayerManager(true).shouldDisableMitigations(null));
    }
}
