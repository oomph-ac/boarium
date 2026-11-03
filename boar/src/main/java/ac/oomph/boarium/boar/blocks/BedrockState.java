package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BlockStateFactory;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.block.BoarBlockStateDelegate;
import ac.boar.anticheat.data.block.VanillaBoarBlockState;
import ac.boar.anticheat.data.block.impl.BedBlockState;
import ac.boar.anticheat.data.block.impl.HoneyBlockState;
import ac.boar.anticheat.data.block.impl.SlimeBlockState;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.math.Box;
import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.Property;
import ac.oomph.boarium.boar.blocks.BedrockProperties.BedrockProperty;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Bedrock block state for Boar, answered from the Bedrock block table.
 *
 * @param intermediaryId the palette index
 */
public record BedrockState(int intermediaryId, Vector3i getPosition, int getLayer) implements BoarBlockStateDelegate {
    private static final BedrockBlockTable TABLE = BedrockBlockTable.get();
    private static final BedrockProperty<Boolean> WATERLOGGED = BedrockProperties.INSTANCE.get("waterlogged");
    private static final int AIR = TABLE.firstIndex("minecraft:air");
    private static final float[] FULL_CUBE = {0, 0, 0, 1, 1, 1};
    private static final float FLOOR_BELL_DROP = 0.1875F;
    // The client builds a piston arm's boxes from the piston's block entity, so the BDS data has none. These are the client's boxes for a fully extended arm (PistonArmBlock::_getCollisions in the 1.26.30 client), by facing_direction; a moving arm is not covered.
    private static final float[][] PISTON_ARM = {
            {0.3125F, 0.75F, 0.3125F, 0.6875F, 1.25F, 0.6875F, 0.375F, 0.25F, 0.375F, 0.625F, 0.75F, 0.625F, 0, 0, 0, 1, 0.25F, 1},
            {0.3125F, -0.25F, 0.3125F, 0.6875F, 0.25F, 0.6875F, 0.375F, 0.25F, 0.375F, 0.625F, 0.75F, 0.625F, 0, 0.75F, 0, 1, 1, 1},
            {0.3125F, 0.3125F, -0.25F, 0.6875F, 0.6875F, 0.25F, 0.375F, 0.375F, 0.25F, 0.625F, 0.625F, 0.75F, 0, 0, 0.75F, 1, 1, 1},
            {0.3125F, 0.3125F, 0.75F, 0.6875F, 0.6875F, 1.25F, 0.375F, 0.375F, 0.25F, 0.625F, 0.625F, 0.75F, 0, 0, 0, 1, 1, 0.25F},
            {-0.25F, 0.3125F, 0.3125F, 0.25F, 0.6875F, 0.6875F, 0.25F, 0.375F, 0.375F, 0.75F, 0.625F, 0.625F, 0.75F, 0, 0, 1, 1, 1},
            {0.75F, 0.3125F, 0.3125F, 1.25F, 0.6875F, 0.6875F, 0.25F, 0.375F, 0.375F, 0.75F, 0.625F, 0.625F, 0, 0, 0, 0.25F, 1, 1}};
    private static final Set<String> PISTON_ARMS = Set.of("minecraft:piston_arm_collision", "minecraft:sticky_piston_arm_collision");

    // The Bedrock names of Java's minecraft:replaceable blocks, through Geyser's mapping, since Bedrock's block states, tags and types have no such flag
    private static final Set<String> REPLACEABLE_NAMES = Set.of(
            "minecraft:air", "minecraft:bubble_column", "minecraft:bush", "minecraft:crimson_roots", "minecraft:deadbush", "minecraft:fern", "minecraft:fire",
            "minecraft:flowing_lava", "minecraft:flowing_water", "minecraft:glow_lichen", "minecraft:hanging_roots", "minecraft:large_fern", "minecraft:lava",
            "minecraft:leaf_litter", "minecraft:nether_sprouts", "minecraft:resin_clump", "minecraft:seagrass", "minecraft:short_dry_grass", "minecraft:short_grass",
            "minecraft:soul_fire", "minecraft:structure_void", "minecraft:tall_dry_grass", "minecraft:tall_grass", "minecraft:vine", "minecraft:warped_roots", "minecraft:water");
    private static final List<Box>[] BOXES = boxes();
    private static final BitSet FACE_STURDY = new BitSet();
    private static final BitSet REPLACEABLE = new BitSet();
    // Which of Boar's state classes each palette entry gets
    private static final byte VANILLA = 0;
    private static final byte HONEY = 1;
    private static final byte SLIME = 2;
    private static final byte BED = 3;
    private static final byte[] CLASSES = new byte[TABLE.paletteSize()];

    public static final BlockStateFactory FACTORY = BedrockState::create;

    static {
        for (int i = 0; i < TABLE.paletteSize(); i++) {
            String name = TABLE.name(i);
            // Sturdy means one full-cube box, as in Boar's Geyser adapter (SolidCollision). Bedrock's isSolid is not used, since it is false for glass, leaves and ice.
            FACE_STURDY.set(i, Arrays.equals(TABLE.collision(i), FULL_CUBE));
            // A snow layer of height 0 is Java's single layer.
            boolean replaceable = name.equals("minecraft:snow_layer") ? TABLE.states(i).getInt("height") == 0 : REPLACEABLE_NAMES.contains(name) || name.startsWith("minecraft:light_block_");
            REPLACEABLE.set(i, replaceable);
            CLASSES[i] = switch (name) {
                case "minecraft:honey_block" -> HONEY;
                case "minecraft:slime" -> SLIME;
                case "minecraft:bed" -> BED;
                default -> VANILLA;
            };
        }
    }

    // Like Geyser, an ID the palette does not have is air.
    public BedrockState {
        intermediaryId = intermediaryId >= 0 && intermediaryId < TABLE.paletteSize() ? intermediaryId : AIR;
    }

    // Honey, slime and beds get Boar's own state classes.
    private static BoarBlockState create(int id, Vector3i position, int layer) {
        BedrockState delegate = new BedrockState(id, position, layer);
        return switch (CLASSES[delegate.intermediaryId]) {
            case HONEY -> new HoneyBlockState(delegate);
            case SLIME -> new SlimeBlockState(delegate);
            case BED -> new BedBlockState(delegate);
            default -> new VanillaBoarBlockState(delegate);
        };
    }

    // One list for each palette entry; entries with the same shape share it. The boxes keep Bedrock Dedicated Server's order, since Boar pushes a player out of them one after another.
    @SuppressWarnings("unchecked")
    private static List<Box>[] boxes() {
        BedrockProperty<String> bell = BedrockProperties.INSTANCE.get("bell_attachment");
        List<Box>[] boxes = new List[TABLE.paletteSize()];
        Map<float[], List<Box>> shapes = new IdentityHashMap<>();
        for (int i = 0; i < boxes.length; i++) {
            float[] shape = PISTON_ARMS.contains(TABLE.name(i)) ? PISTON_ARM[TABLE.states(i).getInt("facing_direction")] : TABLE.collision(i);
            // Boar lowers the top of a floor bell by FLOOR_BELL_DROP to turn Java's box into Bedrock's, but the table's box is already Bedrock's, so it is raised by as much.
            boxes[i] = "floor".equals(bell.get(i)) ? toBoxes(shape, FLOOR_BELL_DROP) : shapes.computeIfAbsent(shape, box -> toBoxes(box, 0));
        }
        return boxes;
    }

    private static List<Box> toBoxes(float[] shape, float raise) {
        List<Box> list = new ArrayList<>(shape.length / 6);
        for (int i = 0; i < shape.length; i += 6) {
            list.add(new Box(shape[i], shape[i + 1], shape[i + 2], shape[i + 3], shape[i + 4] + raise, shape[i + 5]));
        }
        return List.copyOf(list);
    }

    @Override
    public Block block() {
        return BedrockBlock.of(this.intermediaryId);
    }

    @Override
    public BlockDefinition definition(BoarPlayer player) {
        throw new UnsupportedOperationException("Bedrock states are not mapped to runtime IDs yet");
    }

    @Override
    public boolean is(Block block) {
        return BedrockBlock.of(this.intermediaryId).is(block);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> BoarBlockState with(Property<T> property, T value) {
        BedrockProperty<T> bound = (BedrockProperty<T>) property;
        int changed = bound.with(this.intermediaryId, value);
        if (changed < 0) {
            throw new IllegalArgumentException(TABLE.name(this.intermediaryId) + " has no " + bound.key() + " " + value);
        }
        return create(changed, this.getPosition, this.getLayer);
    }

    // Null when the block does not have the property, as with Geyser.
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> T get(Property<T> property) {
        return ((BedrockProperty<T>) property).get(this.intermediaryId);
    }

    @Override
    public boolean isWaterlogged() {
        return Boolean.TRUE.equals(WATERLOGGED.get(this.intermediaryId));
    }

    @Override
    public List<Box> getCollisionBoxes() {
        return BOXES[this.intermediaryId];
    }

    @Override
    public boolean isFaceSturdy(BoarPlayer player) {
        return FACE_STURDY.get(this.intermediaryId);
    }

    @Override
    public boolean isReplaceable(BoarPlayer player) {
        return REPLACEABLE.get(this.intermediaryId);
    }

    // The palette's boxes, without the shapes that neighbours give fences, panes, bars and stair corners
    @Override
    public BoarBlockState applyConnectionShape(BoarPlayer player, BoarBlockState self, Vector3i pos) {
        return self;
    }
}
