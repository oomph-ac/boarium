package ac.oomph.boarium.boar.blocks;

import ac.boar.mappings.block.Block;
import ac.boar.mappings.block.BlockMappings;
import ac.boar.mappings.block.InteractiveBlocks;
import ac.boar.mappings.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Boar's block lists, from Bedrock block names (without "minecraft:"), with the lists of Boar's Geyser adapter as the model.
 * A block is in a list when any of its names is.
 */
public final class BedrockBlockMappings implements BlockMappings {
    private static final Set<String> CLIMBABLE = Set.of("ladder", "vine", "cave_vines", "twisting_vines", "weeping_vines");
    // Not deprecated_anvil: an old anvil no player can get; its palette boxes are already right.
    private static final Set<String> ANVILS = Set.of("anvil", "chipped_anvil", "damaged_anvil");
    // Boar's interactive list holds Java names; these are the Bedrock names it misses. Java's stonecutter is Bedrock's stonecutter_block; Bedrock's stonecutter is an old block Java does not have.
    private static final Set<String> INTERACTIVE = Set.of("bed", "fence_gate", "trapdoor", "noteblock", "unpowered_comparator", "unpowered_repeater", "stonecutter_block");

    private final List<Block> stairs = blocks(name -> name.endsWith("_stairs"));
    private final List<Block> leaves = blocks(name -> name.endsWith("_leaves") || name.equals("azalea_leaves_flowered"));
    private final List<Block> shulker = blocks(name -> name.endsWith("shulker_box"));
    private final List<Block> fenceGate = blocks(name -> name.equals("fence_gate") || name.endsWith("_fence_gate"));
    private final List<Block> wall = blocks(name -> name.endsWith("_wall"));
    private final List<Block> fence = blocks(name -> name.endsWith("_fence"));
    private final List<Block> climbable = blocks(CLIMBABLE::contains);
    private final List<Block> bed = blocks(name -> name.equals("bed"));
    // Geyser's ChestBlock class, which the ender chest is not
    private final List<Block> chest = blocks(name -> name.endsWith("chest") && !name.equals("ender_chest"));
    private final List<Block> door = blocks(name -> name.endsWith("_door"));
    private final List<Block> trapDoor = blocks(name -> name.endsWith("trapdoor"));
    private final List<Block> candle = blocks(name -> name.equals("candle") || name.endsWith("_candle"));
    private final List<Block> sign = blocks(name -> name.endsWith("_sign"));
    private final List<Block> button = blocks(name -> name.endsWith("_button"));
    private final List<Block> bars = blocks(name -> name.endsWith("_bars") || name.endsWith("_pane"));
    // Bedrock calls the jack o'lantern lit_pumpkin, so it is not here as it is in Java's list.
    private final List<Block> lantern = blocks(name -> name.equals("lantern") || name.endsWith("_lantern"));
    private final List<Block> anvil = blocks(ANVILS::contains);
    private final List<Block> cauldron = blocks(name -> name.equals("cauldron"));
    private final List<Block> interactive = blocks(name -> InteractiveBlocks.isInteractive(name) && !name.equals("stonecutter") || INTERACTIVE.contains(name));

    private static List<Block> blocks(Predicate<String> test) {
        List<Block> blocks = new ArrayList<>();
        for (BedrockBlock block : BedrockBlock.all()) {
            if (block.names().stream().anyMatch(name -> test.test(name.substring("minecraft:".length())))) {
                blocks.add(block);
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

    // Empty: block items are not mapped to Bedrock blocks yet.
    @Override
    public Map<Item, Block> getItemToBlock() {
        return Map.of();
    }
}
