package ac.oomph.boarium.boar;

import ac.boar.anticheat.data.block.BlockStateFactory;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.block.BoarBlockStateDelegate;
import ac.boar.anticheat.data.block.VanillaBoarBlockState;
import ac.boar.anticheat.data.effect.EffectProvider;
import ac.boar.anticheat.data.enchantment.EnchantmentProvider;
import ac.boar.anticheat.data.inventory.BoarItemStack;
import ac.boar.anticheat.data.inventory.ItemStackProvider;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.accessor.EntityAccessor;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Direction;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.Blocks;
import ac.boar.mappings.block.ChestType;
import ac.boar.mappings.block.Property;
import ac.boar.mappings.block.PropertyProvider;
import ac.boar.mappings.entity.Entity;
import ac.boar.mappings.entity.EntityDefinition;
import ac.boar.mappings.entity.EntityDefinitions;
import ac.boar.mappings.entity.EntityType;
import ac.boar.mappings.item.Item;
import ac.boar.mappings.item.ItemMappings;
import ac.oomph.boarium.protocol.Definitions;
import it.unimi.dsi.fastutil.ints.IntList;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

import java.util.List;
import java.util.Map;

/**
 * Stand-ins for the block, item and entity data Boar needs to start, until boarium has the real data.
 * Every block is air without collision, no item is a block, and every entity has a player's size.
 */
final class Placeholders {
    // One runtime ID for air, water, lava and powder snow, since every block is air.
    private static final BlockDefinition AIR = Definitions.ANY_BLOCK.getDefinition(0);

    static final BlockMappingInfo MAPPING_INFO = new BlockMappingInfo(AIR, AIR, AIR, AIR, IntList.of(AIR.getRuntimeId()), definition -> false, runtimeId -> 0, intermediaryId -> AIR.getRuntimeId());

    static final BlockStateFactory BLOCK_STATES = (blockId, pos, layer) -> new VanillaBoarBlockState(new AirState(blockId, pos, layer));

    static final PropertyProvider PROPERTIES = Placeholders::property;

    // Every effect and enchantment counts as one Boar does not use.
    static final EffectProvider EFFECTS = id -> null;
    static final EnchantmentProvider ENCHANTMENTS = id -> null;

    static final ItemStackProvider ITEM_STACKS = new ItemStackProvider() {
        @Override
        public BoarItemStack create(NetworkSession session, ItemData data) {
            return EmptyItemStack.INSTANCE;
        }

        @Override
        public BoarItemStack create(NetworkSession session, Reference<Item> item, int amount) {
            return EmptyItemStack.INSTANCE;
        }
    };

    static final EntityAccessor ENTITIES = new EntityAccessor() {
        @Override
        public Entity entityByRuntimeId(long runtimeId) {
            return null;
        }

        @Override
        public EntityDefinition definitionByRuntimeId(long runtimeId) {
            return null;
        }
    };

    static final InventoryAccessor INVENTORY = new InventoryAccessor() {
        @Override
        public void updateSlot(int slot) {
        }

        @Override
        public int heldItemSlot() {
            return 0;
        }
    };

    static final BlockMappings BLOCK_MAPPINGS = new BlockMappings() {
        public List<Block> getStairsBlocks() { return List.of(); }
        public List<Block> getLeavesBlocks() { return List.of(); }
        public List<Block> getShulkerBlocks() { return List.of(); }
        public List<Block> getFenceGateBlocks() { return List.of(); }
        public List<Block> getWallBlocks() { return List.of(); }
        public List<Block> getFenceBlocks() { return List.of(); }
        public List<Block> getClimbableBlocks() { return List.of(); }
        public List<Block> getBedBlocks() { return List.of(); }
        public List<Block> getChestBlocks() { return List.of(); }
        public List<Block> getDoorBlocks() { return List.of(); }
        public List<Block> getTrapDoorBlocks() { return List.of(); }
        public List<Block> getCandleBlocks() { return List.of(); }
        public List<Block> getSignBlocks() { return List.of(); }
        public List<Block> getButtonBlocks() { return List.of(); }
        public List<Block> getBarsBlocks() { return List.of(); }
        public List<Block> getLanternBlocks() { return List.of(); }
        public List<Block> getAnvilBlocks() { return List.of(); }
        public List<Block> getCauldronBlocks() { return List.of(); }
        public List<Block> getInteractiveBlocks() { return List.of(); }
        public Map<Item, Block> getItemToBlock() { return Map.of(); }
    };

    static final ItemMappings ITEM_MAPPINGS = new ItemMappings() {
        public List<Item> getBundleItems() { return List.of(); }
        public List<Item> getCandleItems() { return List.of(); }
        public List<Item> getHeadItems() { return List.of(); }
    };

    private Placeholders() {
    }

    static void populateBlocks(ReferencePopulator<Block> blocks) {
        blocks.populate((key, block) -> block.populate(new NamedBlock(key)));
        blocks.ensurePopulated(true);
    }

    static void populateItems(ReferencePopulator<Item> items) {
        items.populate((key, item) -> item.populate(new NamedItem(key)));
        items.ensurePopulated(true);
    }

    static void populateEntities(ReferencePopulator<EntityType> types, ReferencePopulator<EntityDefinition> definitions) {
        types.populate((key, type) -> {
            NamedEntityType named = new NamedEntityType(key);
            type.populate(named);
            definitions.populate(key, new PlayerSizedDefinition(key, named));
        });
        types.ensurePopulated(true);
        definitions.ensurePopulated(true);
    }

    static Entity playerEntity() {
        return new PlayerEntity();
    }

    // Each of Boar's block properties, with the value it has in a block's default state.
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> Property<T> property(String key) {
        Object value = switch (key) {
            case "age_1", "age_3", "bites", "level", "respawn_anchor_charges" -> 0;
            case "drag", "hanging", "has_book", "lit", "open", "waterlogged" -> false;
            case "bell_attachment" -> "floor";
            case "door_hinge" -> "left";
            case "dripstone_thickness" -> "tip";
            case "half", "slab_type" -> "bottom";
            case "vault_state" -> "inactive";
            case "chest_type" -> ChestType.SINGLE;
            case "horizontal_facing" -> Direction.NORTH;
            case "vertical_direction" -> Direction.UP;
            default -> throw new IllegalArgumentException("unknown block property " + key);
        };
        return new DefaultValue<>((T) value);
    }

    private record DefaultValue<T extends Comparable<T>>(T value) implements Property<T> {
    }

    private record NamedBlock(String key) implements Block {
        @Override
        public boolean is(Block value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<Block> reference) {
            return reference.find().map(this::is).orElse(false);
        }

        @Override
        public float destroyTime() {
            return 0;
        }

        @Override
        public BoarBlockState defaultBlockState(Vector3i position, int layer) {
            return BoarBlockState.create(0, position, layer);
        }
    }

    private record NamedItem(String key) implements Item {
        @Override
        public boolean is(Item value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<Item> reference) {
            return reference.find().map(this::is).orElse(false);
        }

        @Override
        public boolean isBlock() {
            return false;
        }
    }

    private record NamedEntityType(String key) implements EntityType {
        @Override
        public boolean is(EntityType value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityType> reference) {
            return reference.find().map(this::is).orElse(false);
        }
    }

    private record PlayerSizedDefinition(String identifier, EntityType type) implements EntityDefinition {
        @Override
        public boolean is(EntityDefinition value) {
            return this.equals(value);
        }

        @Override
        public boolean is(Reference<EntityDefinition> reference) {
            return reference.find().map(this::is).orElse(false);
        }

        @Override
        public float width() {
            return 0.6F;
        }

        @Override
        public float height() {
            return 1.8F;
        }

        @Override
        public float offset() {
            return 1.62F;
        }
    }

    private record AirState(int intermediaryId, Vector3i getPosition, int getLayer) implements BoarBlockStateDelegate {
        @Override
        public Block block() {
            return Blocks.AIR.find().orElseThrow();
        }

        @Override
        public BlockDefinition definition(BoarPlayer player) {
            return AIR;
        }

        @Override
        public <T extends Comparable<T>> BoarBlockState with(Property<T> property, T value) {
            return new VanillaBoarBlockState(this);
        }

        @Override
        public boolean isWaterlogged() {
            return false;
        }

        @Override
        public boolean is(Block block) {
            return this.block().is(block);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Comparable<T>> T get(Property<T> property) {
            return ((DefaultValue<T>) property).value();
        }

        @Override
        public List<Box> getCollisionBoxes() {
            return List.of();
        }

        @Override
        public boolean isFaceSturdy(BoarPlayer player) {
            return false;
        }

        @Override
        public BoarBlockState applyConnectionShape(BoarPlayer player, BoarBlockState self, Vector3i pos) {
            return self;
        }
    }

    private static final class EmptyItemStack implements BoarItemStack {
        static final EmptyItemStack INSTANCE = new EmptyItemStack();
        private static final Item AIR_ITEM = new NamedItem("minecraft:air");

        @Override
        public Item item() {
            return AIR_ITEM;
        }

        @Override
        public boolean is(Item item) {
            return false;
        }

        @Override
        public boolean is(Reference<Item> item) {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public ItemData toItemData(NetworkSession session) {
            return ItemData.AIR;
        }
    }

    private static final class PlayerEntity implements Entity {
        @Override
        public EntityDefinition definition() {
            return EntityDefinitions.PLAYER.find().orElseThrow();
        }

        @Override
        public Entity vehicle() {
            return null;
        }

        @Override
        public Vector3i bedPosition() {
            return null;
        }

        @Override
        public float bbWidth() {
            return 0.6F;
        }

        @Override
        public float bbHeight() {
            return 1.8F;
        }

        @Override
        public <T> void metadata(EntityDataType<T> type, T value) {
        }

        @Override
        public void refreshMetadata() {
        }

        @Override
        public void refreshAttributesToSelf() {
        }

        @Override
        public void releaseItem() {
        }
    }
}
