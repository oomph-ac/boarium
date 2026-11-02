package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.enchantment.Enchantment;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Bedrock IDs from BedrockEnchantment.getByBedrockId(int) in Geyser 2.11.3-b1248.
class BedrockEnchantmentsTest {
    private static final Map<Integer, Enchantment> BY_ID = Map.of(
            7, Enchantment.DEPTH_STRIDER,
            30, Enchantment.RIPTIDE,
            36, Enchantment.SOUL_SPEED,
            37, Enchantment.SWIFT_SNEAK);

    @Test
    void mapsEveryBoarEnchantmentByItsBedrockId() {
        BY_ID.forEach((id, enchantment) -> assertEquals(enchantment, BedrockEnchantments.INSTANCE.byId(id), "id " + id));
        assertEquals(EnumSet.allOf(Enchantment.class), EnumSet.copyOf(BY_ID.values()));
    }

    @Test
    void givesNullForAnEnchantmentBoarDoesNotHave() {
        // Protection and feather falling, which Boar does not have
        assertNull(BedrockEnchantments.INSTANCE.byId(0));
        assertNull(BedrockEnchantments.INSTANCE.byId(2));
        assertNull(BedrockEnchantments.INSTANCE.byId(-1));
        assertNull(BedrockEnchantments.INSTANCE.byId(1000));
    }
}
