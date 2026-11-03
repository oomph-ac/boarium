package ac.oomph.boarium.boar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumPlayerManagerTest {

    @Test
    void disablesMitigationsWhenTheyAreOff() {
        assertTrue(new BoariumPlayerManager(false).shouldDisableMitigations(null));
    }
}
