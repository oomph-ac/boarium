package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.mappings.block.FallingFlowMaterial;
import ac.boar.mappings.block.FallingFlowMaterials;
import ac.boar.mappings.block.HorseJumpMaterial;
import ac.boar.mappings.block.HorseJumpMaterials;
import it.unimi.dsi.fastutil.ints.IntList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The block table's Bedrock palette with the runtime IDs a client uses: the palette index, or the network hash when StartGame sets blockNetworkIdsHashed.
 * Every client in the same ID mode gets the same palette, since the table has no custom blocks.
 */
public final class TablePalette {
    private static final BlockTable TABLE = BlockTable.get();
    private static volatile TablePalette sorted;
    private static volatile TablePalette hashed;

    private final boolean hashedIds;
    private final SimpleBlockDefinition[] definitions;
    private final BlockMappingInfo mappingInfo;

    private TablePalette(boolean hashedIds) {
        this.hashedIds = hashedIds;
        this.definitions = new SimpleBlockDefinition[TABLE.paletteSize()];
        FallingFlowMaterials.Lookup fallingFlow = FallingFlowMaterials.forProtocol(TABLE.protocol());
        FallingFlowMaterial[] flows = new FallingFlowMaterial[this.definitions.length];
        HorseJumpMaterial[] jumps = new HorseJumpMaterial[this.definitions.length];
        for (int i = 0; i < this.definitions.length; i++) {
            String name = TABLE.paletteName(i);
            NbtMap states = TABLE.paletteStates(i);
            this.definitions[i] = new SimpleBlockDefinition(name, TABLE.runtimeId(i, hashedIds), NbtMap.builder().putString("name", name).putCompound("states", states).build());
            flows[i] = fallingFlow.resolve(name, states);
            jumps[i] = HorseJumpMaterials.resolve(name);
        }

        IntFunction<FallingFlowMaterial> flowOf = fallingFlow.isSupported() ? runtimeId -> {
            int index = TABLE.paletteIndex(runtimeId, hashedIds);
            return index < 0 ? FallingFlowMaterial.NOT_SEARCHED : flows[index];
        } : runtimeId -> FallingFlowMaterial.UNSUPPORTED_PROTOCOL;
        IntFunction<HorseJumpMaterial> jumpOf = runtimeId -> {
            int index = TABLE.paletteIndex(runtimeId, hashedIds);
            return index < 0 ? HorseJumpMaterial.UNKNOWN : jumps[index];
        };
        this.mappingInfo = new BlockMappingInfo(
                this.definition(0),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:water"))),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:lava"))),
                this.definition(TABLE.defaultState(TABLE.block("minecraft:powder_snow"))),
                IntList.of(this.runtimeId(0), this.runtimeId(TABLE.defaultState(TABLE.block("minecraft:cave_air"))), this.runtimeId(TABLE.defaultState(TABLE.block("minecraft:void_air")))),
                definition -> this.isItemFrame(definition.getRuntimeId()),
                runtimeId -> TABLE.toJava(runtimeId, hashedIds),
                this::runtimeId,
                flowOf,
                jumpOf);
    }

    /**
     * The block mapping for a client that was sent this StartGame, or null when the server adds custom blocks, which the table does not have.
     * Blocks the server describes that the palette already has, such as data-driven vanilla blocks, are not custom.
     */
    public static BlockMappingInfo mappingInfo(StartGamePacket startGame) {
        Set<String> names = PaletteNames.NAMES;
        for (BlockPropertyData block : startGame.getBlockProperties()) {
            if (!names.contains(block.getName())) {
                return null;
            }
        }
        return of(startGame.isBlockNetworkIdsHashed()).mappingInfo;
    }

    static synchronized TablePalette of(boolean hashedIds) {
        if (hashedIds) {
            if (hashed == null) {
                hashed = new TablePalette(true);
            }
            return hashed;
        }
        if (sorted == null) {
            sorted = new TablePalette(false);
        }
        return sorted;
    }

    // A player whose mapping did not come from here has the all-air mapping, so every block it has is air.
    static BlockDefinition definition(BoarPlayer player, int state) {
        TablePalette sorted = TablePalette.sorted;
        TablePalette hashed = TablePalette.hashed;
        if (sorted != null && sorted.mappingInfo == player.mappingInfo) {
            return sorted.definition(state);
        }
        if (hashed != null && hashed.mappingInfo == player.mappingInfo) {
            return hashed.definition(state);
        }
        return player.mappingInfo.airDefinition();
    }

    // A state the table does not have is air, as in Geyser.
    BlockDefinition definition(int state) {
        return this.definitions[TABLE.toRuntime(state >= 0 && state < TABLE.stateCount() ? state : 0, false)];
    }

    int runtimeId(int state) {
        return this.definition(state).getRuntimeId();
    }

    private boolean isItemFrame(int runtimeId) {
        int index = TABLE.paletteIndex(runtimeId, this.hashedIds);
        return index >= 0 && (TABLE.paletteName(index).equals("minecraft:frame") || TABLE.paletteName(index).equals("minecraft:glow_frame"));
    }

    private static final class PaletteNames {
        static final Set<String> NAMES = names();

        private static Set<String> names() {
            Set<String> names = new HashSet<>();
            for (int i = 0; i < TABLE.paletteSize(); i++) {
                names.add(TABLE.paletteName(i));
            }
            return Set.copyOf(names);
        }
    }
}
