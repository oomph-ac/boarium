package ac.oomph.boarium.boar;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.protocol.PacketEvents;
import ac.boar.protocol.api.CloudburstPacketEvent;
import ac.boar.protocol.api.PacketListener;
import ac.oomph.boarium.boar.blocks.BedrockPalette;
import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.session.PacketContext;
import ac.oomph.boarium.session.Processor;
import ac.oomph.boarium.session.Session;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementPredictionSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Hands each player's packets to Boar's packet listeners, the way CubeCraft's Boar platform does on CubeTap.
 * Boar changes packets in place, so every packet it lets through is encoded again.
 */
final class BoarProcessor implements Processor {
    private static final Logger LOG = LoggerFactory.getLogger(BoarProcessor.class);
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    // The packets Boar's listeners read, as CubeCraft's Boar platform picks them.
    private static final List<Class<? extends BedrockPacket>> CLIENT_PACKETS = List.of(
            NetworkStackLatencyPacket.class, ServerboundLoadingScreenPacket.class, MovementPredictionSyncPacket.class, InventoryTransactionPacket.class, ItemStackRequestPacket.class,
            InteractPacket.class, ContainerClosePacket.class, MobEquipmentPacket.class, PlayerAuthInputPacket.class, AnimatePacket.class);
    private static final List<Class<? extends BedrockPacket>> SERVER_PACKETS = List.of(
            NetworkStackLatencyPacket.class, NetworkChunkPublisherUpdatePacket.class, ChunkRadiusUpdatedPacket.class, LevelChunkPacket.class, SubChunkPacket.class, UpdateBlockPacket.class,
            UpdateSubChunkBlocksPacket.class, BlockEntityDataPacket.class, RemoveEntityPacket.class, MoveEntityDeltaPacket.class, MoveEntityAbsolutePacket.class, MovePlayerPacket.class,
            StartGamePacket.class, SetPlayerGameTypePacket.class, UpdatePlayerGameTypePacket.class, UpdateAbilitiesPacket.class, SetEntityDataPacket.class, EntityEventPacket.class,
            UpdateAttributesPacket.class, MobEffectPacket.class, SetEntityMotionPacket.class, MovementEffectPacket.class, CreativeContentPacket.class, CraftingDataPacket.class,
            ContainerOpenPacket.class, ContainerClosePacket.class, UpdateTradePacket.class, InventorySlotPacket.class, InventoryContentPacket.class, ItemStackResponsePacket.class,
            PlayerHotbarPacket.class, SetEntityLinkPacket.class, ChangeDimensionPacket.class, RespawnPacket.class, AddPlayerPacket.class, AddEntityPacket.class);

    private final BoariumPlayerManager players;
    private final Set<Integer> clientDecode;
    private final Set<Integer> serverDecode;

    BoarProcessor(BoariumPlayerManager players, BedrockCodec codec) {
        this.players = players;
        this.clientDecode = ids(codec, CLIENT_PACKETS);
        this.serverDecode = ids(codec, SERVER_PACKETS);
    }

    private static Set<Integer> ids(BedrockCodec codec, List<Class<? extends BedrockPacket>> packets) {
        return packets.stream().map(packet -> codec.getPacketDefinition(packet).getId()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<Integer> clientDecode() {
        return this.clientDecode;
    }

    @Override
    public Set<Integer> serverDecode() {
        return this.serverDecode;
    }

    // Boar reads chunks only when they come whole.
    @Override
    public boolean clientCache() {
        return false;
    }

    @Override
    public void startGame(Session session, StartGamePacket startGame) {
        // A client that has left had its disconnect call already, so nothing would remove its player.
        if (ClientDisconnect.ended(session.client().session()).isDone()) {
            return;
        }
        BoarPlayer player;
        try {
            player = this.players.add(session, startGame);
        } catch (Throwable e) {
            LOG.error("Boar could not add {}", session.client().identity().displayName(), e);
            return;
        }
        if (player != null) {
            passes(player, startGame, false);
        }
    }

    // Boar skips its movement checks while the player is in a loading screen, and the client's end of the join screen may never reach it, so it ends at spawn.
    @Override
    public void spawned(Session session) {
        BoarPlayer player = this.players.get(session);
        if (player != null && player.inLoadingScreen && player.currentLoadingScreen == null) {
            player.inLoadingScreen = false;
            player.sinceLoadingScreen = 0;
        }
    }

    @Override
    public void client(Session session, List<PacketContext> batch) {
        BoarPlayer player = this.players.get(session);
        if (player == null) {
            return;
        }
        for (PacketContext context : batch) {
            this.handle(player, context, true);
        }
    }

    @Override
    public void server(Session session, PacketContext packet) {
        BoarPlayer player = this.players.get(session);
        if (player != null) {
            ((BoariumPlayerManager.AckTransport) player.getAckTransport()).serverBatchOpen = true;
            // Boar asks for an entity's definition while it handles the entity's AddEntity, so the tracker sees each packet before Boar.
            if (packet.packet() != null) {
                ((EntityTracker) player.getEntityAccessor()).observe(packet.packet(), player.uniqueEntityId);
                ((InventoryTracker) player.getInventoryAccessor()).observe(packet.packet());
            }
            this.handle(player, packet, false);
        }
    }

    // A server can be left mid-batch, with no Flush to come. Its entities, the player's ride and its items end with it.
    @Override
    public void preTransfer(Session session, String origin, String target) {
        this.flush(session);
        BoarPlayer player = this.players.get(session);
        if (player != null) {
            ((EntityTracker) player.getEntityAccessor()).clear();
            ((InventoryTracker) player.getInventoryAccessor()).clear();
        }
    }

    @Override
    public void flush(Session session) {
        BoarPlayer player = this.players.get(session);
        if (player != null) {
            ((BoariumPlayerManager.AckTransport) player.getAckTransport()).serverBatchOpen = false;
        }
    }

    private void handle(BoarPlayer player, PacketContext context, boolean received) {
        if (context.packet() == null) {
            return;
        }
        if (passes(player, context.packet(), received)) {
            context.setModified();
        } else {
            context.cancel();
        }
    }

    @Override
    public void disconnect(Session session) {
        BoarPlayer player = this.players.remove(session);
        if (player != null) {
            this.players.alerts.forget(player);
            this.players.flags.forget(player);
            if (player.future != null) {
                player.future.cancel(false);
            }
            BedrockPalette.release(player.mappingInfo);
        }
    }

    /**
     * Shows a packet to each of Boar's listeners, as one the client sent when {@code received} is set, or one sent to the client otherwise.
     * A listener that throws is logged and skipped, so Boar's errors never cost a player its connection.
     *
     * @return false when a listener cancelled the packet
     */
    static boolean passes(BoarPlayer player, BedrockPacket packet, boolean received) {
        CloudburstPacketEvent event = new CloudburstPacketEvent(player, packet);
        for (PacketListener listener : PacketEvents.getApi().getListeners()) {
            try {
                if (received) {
                    listener.onPacketReceived(event);
                } else {
                    listener.onPacketSend(event);
                }
            } catch (Throwable e) {
                // A listener that fails on a packet tends to fail on every one like it, so its stack trace is logged once.
                String failure = listener.getClass().getName() + " " + packet.getClass().getName();
                if (LOGGED_FAILURES.add(failure)) {
                    LOG.error("Boar's {} failed on {} for {}", listener.getClass().getSimpleName(), packet.getClass().getSimpleName(), player.getSession().name(), e);
                } else {
                    LOG.debug("Boar's {} failed on {} for {}: {}", listener.getClass().getSimpleName(), packet.getClass().getSimpleName(), player.getSession().name(), e.toString());
                }
            }
        }
        if (event.isCancelled()) {
            return false;
        }
        for (Runnable task : event.getPostTasks()) {
            try {
                task.run();
            } catch (Throwable e) {
                LOG.error("A Boar task failed after {} for {}", packet.getClass().getSimpleName(), player.getSession().name(), e);
            }
        }
        return true;
    }
}
