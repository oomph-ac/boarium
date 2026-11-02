package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.effect.Effect;
import ac.boar.anticheat.data.effect.EffectProvider;

/**
 * Boar's effects by Bedrock effect ID, as in MobEffectPacket. Effects Boar does not have give null.
 */
final class BedrockEffects implements EffectProvider {
    static final BedrockEffects INSTANCE = new BedrockEffects();

    private BedrockEffects() {
    }

    @Override
    public Effect byId(int id) {
        return switch (id) {
            case 1 -> Effect.SPEED;
            case 2 -> Effect.SLOWNESS;
            case 8 -> Effect.JUMP_BOOST;
            case 24 -> Effect.LEVITATION;
            case 26 -> Effect.CONDUIT_POWER;
            case 27 -> Effect.SLOW_FALLING;
            case 28 -> Effect.BAD_OMEN;
            case 29 -> Effect.HERO_OF_THE_VILLAGE;
            case 30 -> Effect.DARKNESS;
            case 31 -> Effect.TRIAL_OMEN;
            case 32 -> Effect.WIND_CHARGED;
            case 33 -> Effect.WEAVING;
            case 34 -> Effect.OOZING;
            case 35 -> Effect.INFESTED;
            case 36 -> Effect.RAID_OMEN;
            default -> null;
        };
    }
}
