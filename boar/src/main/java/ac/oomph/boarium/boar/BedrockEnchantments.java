package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.enchantment.Enchantment;
import ac.boar.anticheat.data.enchantment.EnchantmentProvider;

/**
 * Boar's enchantments by Bedrock enchantment ID, as in the id of each entry in an item's ench NBT list. Enchantments Boar does not have give null.
 */
final class BedrockEnchantments implements EnchantmentProvider {
    static final BedrockEnchantments INSTANCE = new BedrockEnchantments();

    private BedrockEnchantments() {
    }

    @Override
    public Enchantment byId(int id) {
        return switch (id) {
            case 7 -> Enchantment.DEPTH_STRIDER;
            case 30 -> Enchantment.RIPTIDE;
            case 36 -> Enchantment.SOUL_SPEED;
            case 37 -> Enchantment.SWIFT_SNEAK;
            default -> null;
        };
    }
}
