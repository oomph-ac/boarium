package ac.oomph.boarium.protocol;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.List;

/**
 * Definition registries for Cloudburst codec helpers in a proxy that does not know the server's block palette.
 */
public final class Definitions {
    // Any runtime ID is accepted and kept as is.
    public static final DefinitionRegistry<BlockDefinition> ANY_BLOCK = new DefinitionRegistry<>() {
        @Override
        public BlockDefinition getDefinition(int runtimeId) {
            return new AnyBlock(runtimeId);
        }

        @Override
        public boolean isRegistered(BlockDefinition definition) {
            return true;
        }
    };

    private Definitions() {
    }

    public static DefinitionRegistry<ItemDefinition> items(List<ItemDefinition> items) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> registry = SimpleDefinitionRegistry.builder();
        items.forEach(registry::add);
        return registry.build();
    }

    private record AnyBlock(int getRuntimeId) implements BlockDefinition {
    }
}
