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
import ac.oomph.boarium.boar.blocks.TableBlock;
import ac.oomph.boarium.boar.blocks.TableBlockMappings;
import ac.oomph.boarium.boar.blocks.TableBlockState;
import ac.oomph.boarium.boar.blocks.TableProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * What Boar needs from boarium to start. Block data comes from the block table and entity data from {@link BedrockEntities}; the item data are {@link Placeholders} for now.
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
        return new TableBlockMappings();
    }

    @Override
    public BlockStateFactory blockStateFactory() {
        return TableBlockState.FACTORY;
    }

    @Override
    public PropertyProvider propertyProvider() {
        return TableProperties.INSTANCE;
    }

    @Override
    public ItemMappings loadItemMappings() {
        return Placeholders.ITEM_MAPPINGS;
    }

    @Override
    public ItemStackProvider itemStackProvider() {
        return Placeholders.ITEM_STACKS;
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
        TableBlock.populate(populator);
    }

    @Override
    public void finalizeItemMappings(ReferencePopulator<Item> populator) {
        Placeholders.populateItems(populator);
    }

    @Override
    public void finalizeEntityMappings(ReferencePopulator<EntityType> typePopulator, ReferencePopulator<EntityDefinition> definitionPopulator) {
        BedrockEntities.populate(typePopulator, definitionPopulator);
    }
}
