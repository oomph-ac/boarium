package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.util.Reference;
import ac.boar.anticheat.util.ReferencePopulator;
import ac.boar.mappings.block.Block;
import org.cloudburstmc.math.vector.Vector3i;

/**
 * A Java block for Boar, by its index in the block table.
 */
public record TableBlock(int index) implements Block {
    private static final BlockTable TABLE = BlockTable.get();
    private static final TableBlock[] BLOCKS = new TableBlock[TABLE.blockCount()];

    static {
        for (int i = 0; i < BLOCKS.length; i++) {
            BLOCKS[i] = new TableBlock(i);
        }
    }

    public static TableBlock of(int index) {
        return BLOCKS[index];
    }

    // Boar also names blocks that Java does not have, such as invisible_bedrock; their references stay empty, as with Boar's Geyser adapter.
    public static void populate(ReferencePopulator<Block> populator) {
        for (TableBlock block : BLOCKS) {
            String name = TABLE.blockName(block.index);
            if (populator.get(name) != null) {
                populator.populate(name, block);
            }
        }
        populator.ensurePopulated(false);
    }

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
        return TABLE.destroyTime(this.index);
    }

    @Override
    public BoarBlockState defaultBlockState(Vector3i position, int layer) {
        return BoarBlockState.create(TABLE.defaultState(this.index), position, layer);
    }

    @Override
    public String toString() {
        return TABLE.blockName(this.index);
    }
}
