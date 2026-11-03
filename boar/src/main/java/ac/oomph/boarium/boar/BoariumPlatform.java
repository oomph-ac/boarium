package ac.oomph.boarium.boar;

import ac.boar.anticheat.BoarLogger;
import ac.boar.anticheat.BoarPlatform;
import ac.boar.anticheat.data.block.BlockStateFactory;
import ac.boar.anticheat.data.effect.EffectProvider;
import ac.boar.anticheat.data.enchantment.EnchantmentProvider;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.player.BoarPlayerManager;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.PropertyProvider;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityType;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.oomph.boarium.boar.blocks.BedrockBlock;
import ac.oomph.boarium.boar.blocks.BedrockBlockMappings;
import ac.oomph.boarium.boar.blocks.BedrockProperties;
import ac.oomph.boarium.boar.blocks.BedrockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * What Boar needs from boarium to start. Block data comes from the Bedrock block table, entity data from {@link BedrockEntities} and item data from {@link BedrockItems}.
 *
 * @param dataFolder where Boar keeps its config
 */
record BoariumPlatform(Path dataFolder, BoarPlayerManager<?> playerManager) implements BoarPlatform {
    private static final Logger LOG = LoggerFactory.getLogger("Boar");

    @Override
    public BoarLogger logger() {
        return new BoarLogger() {
            @Override
            public void info(String message) {
                LOG.info(message);
            }

            @Override
            public void warn(String message) {
                LOG.warn(message);
            }

            @Override
            public void error(String message) {
                LOG.error(message);
            }

            @Override
            public void error(String message, Throwable t) {
                LOG.error(message, t);
            }
        };
    }

    @Override
    public BlockMappings loadBlockMappings() {
        return new BedrockBlockMappings(BedrockItems.ITEM_TO_BLOCK);
    }

    @Override
    public BlockStateFactory blockStateFactory() {
        return BedrockState.FACTORY;
    }

    @Override
    public PropertyProvider propertyProvider() {
        return BedrockProperties.INSTANCE;
    }

    @Override
    public ItemMappings loadItemMappings() {
        return BedrockItems.INSTANCE;
    }

    @Override
    public ItemStackProvider itemStackProvider() {
        return BedrockItems.INSTANCE;
    }

    @Override
    public EffectProvider effectProvider() {
        return BedrockEffects.INSTANCE;
    }

    @Override
    public EnchantmentProvider enchantmentProvider() {
        return BedrockEnchantments.INSTANCE;
    }

    @Override
    public void finalizeBlockMappings(ReferencePopulator<Block> populator) {
        BedrockBlock.populate(populator);
    }

    @Override
    public void finalizeItemMappings(ReferencePopulator<Item> populator) {
        BedrockItems.populate(populator);
    }

    @Override
    public void finalizeEntityMappings(ReferencePopulator<EntityType> typePopulator, ReferencePopulator<EntityDefinition> definitionPopulator) {
        BedrockEntities.populate(typePopulator, definitionPopulator);
    }
}
