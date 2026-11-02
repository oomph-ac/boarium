package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.effect.Effect;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Bedrock IDs from EffectType.getBedrockId() in Geyser 2.11.3-b1248; gophertunnel's packet.Effect* constants agree for IDs 1 to 27.
class BedrockEffectsTest {
    private static final Map<Integer, Effect> BY_ID = Map.ofEntries(
            Map.entry(1, Effect.SPEED),
            Map.entry(2, Effect.SLOWNESS),
            Map.entry(8, Effect.JUMP_BOOST),
            Map.entry(24, Effect.LEVITATION),
            Map.entry(26, Effect.CONDUIT_POWER),
            Map.entry(27, Effect.SLOW_FALLING),
            Map.entry(28, Effect.BAD_OMEN),
            Map.entry(29, Effect.HERO_OF_THE_VILLAGE),
            Map.entry(30, Effect.DARKNESS),
            Map.entry(31, Effect.TRIAL_OMEN),
            Map.entry(32, Effect.WIND_CHARGED),
            Map.entry(33, Effect.WEAVING),
            Map.entry(34, Effect.OOZING),
            Map.entry(35, Effect.INFESTED),
            Map.entry(36, Effect.RAID_OMEN));

    @Test
    void mapsEveryBoarEffectByItsBedrockId() {
        BY_ID.forEach((id, effect) -> assertEquals(effect, BedrockEffects.INSTANCE.byId(id), "id " + id));
        assertEquals(EnumSet.allOf(Effect.class), EnumSet.copyOf(BY_ID.values()));
    }

    @Test
    void givesNullForAnEffectBoarDoesNotHave() {
        // Haste, which Boar does not have
        assertNull(BedrockEffects.INSTANCE.byId(3));
        // Breath of the nautilus, the highest Bedrock ID in Geyser 2.11.3
        assertNull(BedrockEffects.INSTANCE.byId(37));
        assertNull(BedrockEffects.INSTANCE.byId(0));
        assertNull(BedrockEffects.INSTANCE.byId(-1));
        assertNull(BedrockEffects.INSTANCE.byId(1000));
    }
}
