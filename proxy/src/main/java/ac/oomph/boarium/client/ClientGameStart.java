package ac.oomph.boarium.client;

import ac.oomph.boarium.protocol.Definitions;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitions;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.JigsawStructureDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Starts the game for a logged-in client, following gophertunnel's Conn.StartGame: empty jigsaw structures and voxel shapes, StartGame and the item registry, then the answer to the client's first RequestChunkRadius.
 * The client's SetLocalPlayerAsInitialized with the runtime ID from StartGame ends it. Other packets from the client are dropped until then.
 * The server's StartGame is sent as it is; gophertunnel instead builds its own and sets some fields, such as MultiPlayerGame and GameVersion.
 */
public final class ClientGameStart implements BedrockPacketHandler {
    private static final NbtMap EMPTY_JIGSAW_STRUCTURES = NbtMap.builder()
            .putList("processors", NbtType.COMPOUND, List.of())
            .putList("template_pools", NbtType.COMPOUND, List.of())
            .putList("jigsaws", NbtType.COMPOUND, List.of())
            .putList("structure_sets", NbtType.COMPOUND, List.of())
            .build();

    private final BedrockServerSession session;
    private final long runtimeId;
    private final int chunkRadius;
    private final CompletableFuture<Void> spawned = new CompletableFuture<>();
    private boolean chunkRadiusSent;

    private ClientGameStart(BedrockServerSession session, long runtimeId, int chunkRadius) {
        this.session = session;
        this.runtimeId = runtimeId;
        this.chunkRadius = chunkRadius;
    }

    /**
     * Sends {@code startGame} and {@code items} to the client and waits for it to spawn.
     *
     * @param chunkRadius the radius to answer the client's request with, or 0 to give it the radius it asked for
     * @return completes on the client's event loop once it has spawned. Fails if the client asked for a chunk radius below 1, sent another runtime ID or disconnected, and never completes if the client stops answering, so callers set a timeout and disconnect the client when it fails.
     */
    public static CompletableFuture<Void> start(BedrockServerSession session, StartGamePacket startGame, List<ItemDefinition> items, int chunkRadius) {
        ClientGameStart handler = new ClientGameStart(session, startGame.getRuntimeEntityId(), chunkRadius);
        session.getPeer().getChannel().eventLoop().execute(() -> {
            if (!session.isConnected() || ClientDisconnect.ended(session).isDone()) {
                handler.spawned.completeExceptionally(new ClosedChannelException());
                return;
            }
            session.getPeer().getCodecHelper().setItemDefinitions(Definitions.items(items));
            session.getPeer().getCodecHelper().setBlockDefinitions(Definitions.ANY_BLOCK);
            session.setPacketHandler(handler);

            JigsawStructureDataPacket jigsaws = new JigsawStructureDataPacket();
            jigsaws.setJigsawStructureDataTag(EMPTY_JIGSAW_STRUCTURES);
            session.sendPacketImmediately(jigsaws);
            VoxelShapesPacket shapes = new VoxelShapesPacket();
            shapes.setShapes(new ArrayList<>());
            shapes.setNameMap(new HashMap<>());
            session.sendPacketImmediately(shapes);
            session.sendPacketImmediately(startGame);
            ItemComponentPacket registry = new ItemComponentPacket();
            registry.getItems().addAll(items);
            session.sendPacketImmediately(registry);
        });
        return handler.spawned;
    }

    @Override
    public PacketSignal handle(RequestChunkRadiusPacket packet) {
        if (this.chunkRadiusSent) {
            return PacketSignal.HANDLED;
        }
        if (packet.getRadius() < 1) {
            this.fail("chunk radius " + packet.getRadius() + " is below 1");
            return PacketSignal.HANDLED;
        }
        this.chunkRadiusSent = true;

        ChunkRadiusUpdatedPacket radius = new ChunkRadiusUpdatedPacket();
        radius.setRadius(this.chunkRadius != 0 ? this.chunkRadius : packet.getRadius());
        this.session.sendPacketImmediately(radius);
        BiomeDefinitionListPacket biomes = new BiomeDefinitionListPacket();
        biomes.setBiomes(new BiomeDefinitions(Map.of()));
        this.session.sendPacketImmediately(biomes);
        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        this.session.sendPacketImmediately(status);
        this.session.sendPacketImmediately(new CreativeContentPacket());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(SetLocalPlayerAsInitializedPacket packet) {
        if (packet.getRuntimeEntityId() != this.runtimeId) {
            this.fail("runtime ID " + packet.getRuntimeEntityId() + " does not match StartGame's " + this.runtimeId);
        } else {
            this.spawned.complete(null);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        this.spawned.completeExceptionally(new IllegalStateException("client disconnected: " + reason));
    }

    private void fail(String reason) {
        this.spawned.completeExceptionally(new IllegalStateException(reason));
    }
}
