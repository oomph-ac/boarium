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
import ac.oomph.boarium.boar.blocks.TableProperties.TableProperty;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Java block state for Boar, answered from the block table.
 *
 * @param intermediaryId the Java state ID
 */
public record TableBlockState(int intermediaryId, Vector3i getPosition, int getLayer) implements BoarBlockStateDelegate {
    private static final BlockTable TABLE = BlockTable.get();
    private static final int HONEY = TABLE.block("minecraft:honey_block");
    private static final int SLIME = TABLE.block("minecraft:slime_block");
    private static final boolean[] BEDS = new boolean[TABLE.blockCount()];
    private static final List<Box>[] BOXES = boxes();

    public static final BlockStateFactory FACTORY = TableBlockState::create;

    static {
        for (int block = 0; block < BEDS.length; block++) {
            BEDS[block] = TABLE.blockName(block).contains("_bed");
        }
    }

    // Honey, slime and beds get Boar's own state classes, as in Boar's Geyser adapter (beds by "_bed" in the name). Like Geyser, an ID the table does not have is air.
    private static BoarBlockState create(int id, Vector3i position, int layer) {
        int state = id >= 0 && id < TABLE.stateCount() ? id : 0;
        TableBlockState delegate = new TableBlockState(state, position, layer);
        int block = TABLE.blockOf(state);
        if (block == HONEY) {
            return new HoneyBlockState(delegate);
        } else if (block == SLIME) {
            return new SlimeBlockState(delegate);
        } else if (BEDS[block]) {
            return new BedBlockState(delegate);
        }
        return new VanillaBoarBlockState(delegate);
    }

    // One list for each state; states with the same shape share it.
    @SuppressWarnings("unchecked")
    private static List<Box>[] boxes() {
        List<Box>[] boxes = new List[TABLE.stateCount()];
        Map<float[], List<Box>> shapes = new IdentityHashMap<>();
        for (int state = 0; state < boxes.length; state++) {
            boxes[state] = shapes.computeIfAbsent(TABLE.collision(state), shape -> {
                List<Box> list = new ArrayList<>(shape.length / 6);
                for (int i = 0; i < shape.length; i += 6) {
                    list.add(new Box(shape[i], shape[i + 1], shape[i + 2], shape[i + 3], shape[i + 4], shape[i + 5]));
                }
                return List.copyOf(list);
            });
        }
        return boxes;
    }

    @Override
    public Block block() {
        return TableBlock.of(TABLE.blockOf(this.intermediaryId));
    }

    @Override
    public BlockDefinition definition(BoarPlayer player) {
        return TablePalette.definition(player, this.intermediaryId);
    }

    @Override
    public <T extends Comparable<T>> BoarBlockState with(Property<T> property, T value) {
        TableProperty<T> bound = (TableProperty<T>) property;
        int changed = TABLE.with(this.intermediaryId, bound.id(), bound.format().apply(value));
        if (changed < 0) {
            throw new IllegalArgumentException(TABLE.name(this.intermediaryId) + " has no " + bound.key() + " " + value);
        }
        return BoarBlockState.create(changed, this.getPosition, this.getLayer);
    }

    @Override
    public boolean isWaterlogged() {
        return TABLE.isWaterlogged(this.intermediaryId);
    }

    @Override
    public boolean is(Block block) {
        return this.block().is(block);
    }

    // Null when the block does not have the property, as with Geyser.
    @Override
    public <T extends Comparable<T>> T get(Property<T> property) {
        TableProperty<T> bound = (TableProperty<T>) property;
        String value = TABLE.value(this.intermediaryId, bound.id());
        return value == null ? null : bound.parse().apply(value);
    }

    @Override
    public List<Box> getCollisionBoxes() {
        return BOXES[this.intermediaryId];
    }

    @Override
    public boolean isFaceSturdy(BoarPlayer player) {
        return TABLE.isFaceSturdy(this.intermediaryId);
    }

    @Override
    public boolean isReplaceable(BoarPlayer player) {
        return TABLE.isReplaceable(this.intermediaryId);
    }

    // Not done yet: Boar's Geyser adapter reshapes fences, bars, chests and stairs here from their neighbours, and builds bars and walls in connectionCollisionOverride. Until then each keeps the shape of its own state.
    @Override
    public BoarBlockState applyConnectionShape(BoarPlayer player, BoarBlockState self, Vector3i pos) {
        return self;
    }
}
