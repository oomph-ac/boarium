package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.util.Reference;
import ac.boar.mappings.block.Block;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.List;

/**
 * Boar's block of a custom block with custom IDs, which every palette with that block shares, as it shares the custom IDs.
 */
final class CustomBlockType implements Block {
    // Bounds the states compared for one block, which a real block's traits never come near.
    static final int MAX_COMPARED_STATES = 4096;

    private final CustomBlock block;
    private final int firstId;
    private final String unplaced;

    CustomBlockType(CustomBlock block, int firstId) {
        this.block = block;
        this.firstId = firstId;
        this.unplaced = unplaced(block);
    }

    /**
     * Why Boar does not place the block when the client does, or null. BlockType::getPlacementBlock starts from BlockType::tryGetStateFromLegacyData, which gives the default state of a block outside the minecraft namespace whatever the item's data value, and then its placement_direction and placement_position traits set their states.
     * BlockSource::updateConnectionsAt later sets a block's connection states when a block next to it changes. Boar keeps the default state, so it places a block with traits only when every combination of their states' values has the default state's boxes.
     */
    private static String unplaced(CustomBlock block) {
        int colon = block.name().indexOf(':');
        if (colon < 0 || block.name().substring(0, colon).equals("minecraft")) {
            return "Boar does not know which state its item's data value picks";
        }
        if (block.placementFilter()) {
            return "Boar does not check its minecraft:placement_filter";
        }
        List<String> traitStates = block.keys().subList(0, block.traitStates());
        // The client's BlockMultiBlockComponent::onPlace goes over the other parts (BlockMultiBlockComponent::forEachPart).
        if (traitStates.contains("minecraft:multi_block_part")) {
            return "Boar does not follow its minecraft:multi_block parts";
        }
        // The traits' states take the lowest bits, so with the properties at their first values they span the first states.
        int[] radices = CustomBlock.radices(block.values());
        long span = 1;
        for (int p = 0; p < traitStates.size(); p++) {
            span *= radices[p];
        }
        boolean sameBoxes = span <= MAX_COMPARED_STATES;
        for (int state = 1; sameBoxes && state < span; state++) {
            sameBoxes = block.collision().sameBoxes(state, 0);
        }
        return sameBoxes ? null : "its traits add " + traitStates + ", which change its collision";
    }

    @Override
    public boolean is(Block value) {
        return value == this;
    }

    @Override
    public boolean is(Reference<Block> reference) {
        return reference.find().map(this::is).orElse(false);
    }

    @Override
    public float destroyTime() {
        return this.block.components().destroyTime();
    }

    // Permutation 0, where every state has its first value: BlockType::createBlockPermutations makes it the default state.
    @Override
    public BoarBlockState defaultBlockState(Vector3i position, int layer) {
        return BedrockState.FACTORY.create(this.firstId, position, layer);
    }

    // The states the block's traits add, which Boar's placement leaves at their first value
    List<String> unsetStates() {
        return this.block.keys().subList(0, this.block.traitStates());
    }

    String unplaced() {
        return this.unplaced;
    }

    @Override
    public String toString() {
        return this.block.name();
    }
}
