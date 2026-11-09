package ac.oomph.boarium.boar;

import ac.boar.anticheat.check.api.Check;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleAlertsTest {

    @Test
    void removesColourCodes() {
        assertEquals("Boar > Steve failed Timer", ConsoleAlerts.plain("§3Boar §7>§r §3Steve§7 failed§6 Timer"));
    }

    @Test
    void namesACheckWithItsTypeAndWhetherItIsExperimental() {
        assertEquals("Timer", ConsoleAlerts.checkName(check("Timer", "", false)));
        assertEquals("Reach (A) (Experimental)", ConsoleAlerts.checkName(check("Reach", "A", true)));
    }

    private static Check check(String name, String type, boolean experimental) {
        return new Check() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String type() {
                return type;
            }

            @Override
            public boolean experimental() {
                return experimental;
            }

            @Override
            public void fail() {
            }

            @Override
            public void fail(String verbose) {
            }
        };
    }
}
