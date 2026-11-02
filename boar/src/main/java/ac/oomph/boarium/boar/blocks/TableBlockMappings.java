package ac.oomph.boarium.boar.blocks;

import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.InteractiveBlocks;
import ac.boar.mappings.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Boar's block lists, from the block table's categories, and the block each block item places.
 */
public final class TableBlockMappings implements BlockMappings {
    private final BlockTable table = BlockTable.get();
    private final Map<Item, Block> itemToBlock;
    private final List<Block> stairs = this.category(BlockTable.STAIRS);
    private final List<Block> leaves = this.category(BlockTable.LEAVES);
    private final List<Block> shulker = this.category(BlockTable.SHULKER);
    private final List<Block> fenceGate = this.category(BlockTable.FENCE_GATE);
    private final List<Block> wall = this.category(BlockTable.WALL);
    private final List<Block> fence = this.category(BlockTable.FENCE);
    private final List<Block> climbable = this.category(BlockTable.CLIMBABLE);
    private final List<Block> bed = this.category(BlockTable.BED);
    private final List<Block> chest = this.category(BlockTable.CHEST);
    private final List<Block> door = this.category(BlockTable.DOOR);
    private final List<Block> trapDoor = this.category(BlockTable.TRAPDOOR);
    private final List<Block> candle = this.category(BlockTable.CANDLE);
    private final List<Block> sign = this.category(BlockTable.SIGN);
    private final List<Block> button = this.category(BlockTable.BUTTON);
    private final List<Block> bars = this.category(BlockTable.BARS);
    private final List<Block> lantern = this.category(BlockTable.LANTERN);
    private final List<Block> anvil = this.category(BlockTable.ANVIL);
    private final List<Block> cauldron = this.category(BlockTable.CAULDRON);
    // Boar's check takes the name without "minecraft:".
    private final List<Block> interactive = this.blocks(block -> InteractiveBlocks.isInteractive(this.table.blockName(block).substring("minecraft:".length())));

    public TableBlockMappings(Map<Item, Block> itemToBlock) {
        this.itemToBlock = itemToBlock;
    }

    private List<Block> category(int bit) {
        return this.blocks(block -> (this.table.categories(block) & bit) != 0);
    }

    private List<Block> blocks(IntPredicate test) {
        List<Block> blocks = new ArrayList<>();
        for (int block = 0; block < this.table.blockCount(); block++) {
            if (test.test(block)) {
                blocks.add(TableBlock.of(block));
            }
        }
        return List.copyOf(blocks);
    }

    @Override
    public List<Block> getStairsBlocks() {
        return this.stairs;
    }

    @Override
    public List<Block> getLeavesBlocks() {
        return this.leaves;
    }

    @Override
    public List<Block> getShulkerBlocks() {
        return this.shulker;
    }

    @Override
    public List<Block> getFenceGateBlocks() {
        return this.fenceGate;
    }

    @Override
    public List<Block> getWallBlocks() {
        return this.wall;
    }

    @Override
    public List<Block> getFenceBlocks() {
        return this.fence;
    }

    @Override
    public List<Block> getClimbableBlocks() {
        return this.climbable;
    }

    @Override
    public List<Block> getBedBlocks() {
        return this.bed;
    }

    @Override
    public List<Block> getChestBlocks() {
        return this.chest;
    }

    @Override
    public List<Block> getDoorBlocks() {
        return this.door;
    }

    @Override
    public List<Block> getTrapDoorBlocks() {
        return this.trapDoor;
    }

    @Override
    public List<Block> getCandleBlocks() {
        return this.candle;
    }

    @Override
    public List<Block> getSignBlocks() {
        return this.sign;
    }

    @Override
    public List<Block> getButtonBlocks() {
        return this.button;
    }

    @Override
    public List<Block> getBarsBlocks() {
        return this.bars;
    }

    @Override
    public List<Block> getLanternBlocks() {
        return this.lantern;
    }

    @Override
    public List<Block> getAnvilBlocks() {
        return this.anvil;
    }

    @Override
    public List<Block> getCauldronBlocks() {
        return this.cauldron;
    }

    @Override
    public List<Block> getInteractiveBlocks() {
        return this.interactive;
    }

    @Override
    public Map<Item, Block> getItemToBlock() {
        return this.itemToBlock;
    }
}
