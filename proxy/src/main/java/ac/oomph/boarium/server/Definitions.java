package ac.oomph.boarium.server;

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

/**
 * Block definitions for a proxy that does not know the server's block palette: any runtime ID is accepted and kept as is.
 */
final class Definitions {
    static final DefinitionRegistry<BlockDefinition> ANY_BLOCK = new DefinitionRegistry<>() {
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

    private record AnyBlock(int getRuntimeId) implements BlockDefinition {
    }
}
