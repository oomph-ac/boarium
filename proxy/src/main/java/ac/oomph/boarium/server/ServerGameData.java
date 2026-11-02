package ac.oomph.boarium.server;

import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.List;

/**
 * What a downstream server sent during the connection sequence. {@code runtimeId} and {@code uniqueId} come from its ConnectionResponse.
 */
public record ServerGameData(StartGamePacket startGame, List<ItemDefinition> items, int chunkRadius, long runtimeId, long uniqueId) {
}
