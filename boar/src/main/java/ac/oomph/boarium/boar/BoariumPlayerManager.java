package ac.oomph.boarium.boar;

import ac.boar.anticheat.ack.BatchAcknowledgmentTransport;
import ac.boar.anticheat.ack.BoarAcknowledgmentTransport;
import ac.boar.anticheat.data.block.BoarBlockState;
import ac.boar.anticheat.data.vanilla.Attribute;
import ac.boar.anticheat.data.vanilla.AttributeInstance;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.BoarPlayerManager;
import ac.boar.anticheat.player.accessor.EntityAccessor;
import ac.boar.anticheat.player.accessor.InventoryAccessor;
import ac.boar.anticheat.player.accessor.WorldAccessor;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.boar.mappings.entity.Entity;
import ac.boar.protocol.BoarBatchAcknowledger;
import ac.boar.protocol.BoarConnection;
import ac.oomph.boarium.boar.blocks.BedrockPalette;
import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.session.Session;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.BuildPlatform;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Boar's players, one for each boarium session that reached its StartGame.
 */
final class BoariumPlayerManager extends BoarPlayerManager<Session> {
    private static final Logger LOG = LoggerFactory.getLogger(BoariumPlayerManager.class);
    private static final Set<List<String>> LOGGED_MISSING_ITEMS = ConcurrentHashMap.newKeySet();

    // The block mapping of each player being added, which Boar asks for while it makes the player.
    private final Map<Session, BlockMappingInfo> adding = new ConcurrentHashMap<>();
    // Not final, since the tests share one Boar and change it.
    volatile boolean mitigations;

    BoariumPlayerManager(boolean mitigations) {
        this.mitigations = mitigations;
    }

    // Adds the player of a session whose client was sent this StartGame, which sets the client's block runtime IDs, or returns null when Boar does not know them.
    BoarPlayer add(Session session, StartGamePacket startGame) {
        // Boar's checks of an item the server's list lacks never match; most likely the server runs an older Bedrock version.
        List<String> missing = BedrockItems.missingReferences(session.itemDefinitions());
        if (!missing.isEmpty() && LOGGED_MISSING_ITEMS.add(missing)) {
            LOG.warn("The server of {} lacks Boar's items {}", session.client().identity().displayName(), missing);
        }
        BlockMappingInfo mappingInfo = BedrockPalette.mappingInfo(startGame);
        // Boar would read every block, even air, as a full cube.
        if (BedrockPalette.runtimeIdsUnknown(mappingInfo)) {
            LOG.warn("Boar does not check {}, since it does not know the client's block runtime IDs", session.client().identity().displayName());
            BedrockPalette.release(mappingInfo);
            return null;
        }
        this.adding.put(session, mappingInfo);
        try {
            return this.add(session);
        } catch (Throwable e) {
            // A player that Boar added before it failed keeps its mapping until BoarProcessor.disconnect removes the player.
            if (!this.containsKey(session)) {
                BedrockPalette.release(mappingInfo);
            }
            throw e;
        } finally {
            this.adding.remove(session);
        }
    }

    @Override
    public BoarPlayer add(Session session) {
        BoarPlayer player = super.add(session);
        // The compensated world is made with the player, after its world accessor.
        ((CompensatedWorldAccessor) player.getWorldAccessor()).player = player;
        ((InventoryTracker) player.getInventoryAccessor()).player = player;
        ((PlayerEntity) player.getEntity()).player = player;
        return player;
    }

    @Override
    protected NetworkSession createNetworkSession(Session session) {
        return new BoariumNetworkSession(session, deviceOs(session.client().clientData()), new BedrockItems.SessionItems(session.itemDefinitions(), this.getMappingInfo(session)));
    }

    @Override
    protected BoarConnection getConnection(Session session) {
        return new Connection(this, session);
    }

    @Override
    protected Entity getPlayerEntity(Session session) {
        return new PlayerEntity();
    }

    @Override
    protected BlockMappingInfo getMappingInfo(Session session) {
        BlockMappingInfo mappingInfo = this.adding.get(session);
        if (mappingInfo == null) {
            throw new IllegalStateException("A player is added only with its StartGame");
        }
        return mappingInfo;
    }

    @Override
    protected WorldAccessor createWorldAccessor(Session session) {
        return new CompensatedWorldAccessor();
    }

    @Override
    protected EntityAccessor createEntityAccessor(Session session) {
        return new EntityTracker(session.client().identity().displayName());
    }

    @Override
    protected InventoryAccessor createInventoryAccessor(Session session) {
        return new InventoryTracker(session);
    }

    @Override
    protected Map<String, AttributeInstance> createDefaultAttributes() {
        // A player's base movement speed.
        return Map.of(Attribute.MOVEMENT.getIdentifier(), new AttributeInstance(0.1F));
    }

    @Override
    protected BoarAcknowledgmentTransport createAckTransport(BoarPlayer player) {
        return new AckTransport(player);
    }

    @Override
    protected ScheduledFuture<?> beginTicking(Session session, Runnable ticker) {
        Runnable guarded = () -> {
            try {
                ticker.run();
            } catch (Throwable e) {
                // A task that throws would never run again.
                LOG.error("Boar's tick failed for {}", session.client().identity().displayName(), e);
            }
        };
        return channel(session).eventLoop().scheduleAtFixedRate(guarded, 50, 50, TimeUnit.MILLISECONDS);
    }

    // Boar's listeners see the player's packets through the session's processor, so only Boar's acknowledgments go in the pipeline, at the end of each batch the client is sent.
    @Override
    protected void installHandlers(BoarPlayer player, Channel channel) {
        channel.pipeline().addAfter(BedrockPacketCodec.NAME, BoarBatchAcknowledger.NAME, new BoarBatchAcknowledger(player));
        Session session = ((BoariumNetworkSession) player.getSession()).session();
        // A packet Boar replays must skip its listeners, and the session's own sends skip the processor.
        player.setPacketInjector(session::sendToServer);
    }

    // boarium's boar_mitigations decides, in place of disable-mitigations in Boar's own config.
    @Override
    protected boolean shouldDisableMitigations(Session session) {
        return !this.mitigations;
    }

    // The client data's DeviceOS, or 0 (unknown) when it has none or it does not fit in an int.
    static int deviceOs(byte[] clientData) {
        try {
            return JsonUtil.parseJson(new String(clientData, StandardCharsets.UTF_8)).get("DeviceOS") instanceof Number os && os.doubleValue() == os.intValue() ? os.intValue() : 0;
        } catch (JoseException e) {
            return 0;
        }
    }

    private static Channel channel(Session session) {
        return session.client().session().getPeer().getChannel();
    }

    record BoariumNetworkSession(Session session, int deviceOs, BedrockItems.SessionItems items) implements NetworkSession {
        @Override
        public String name() {
            return this.session.client().identity().displayName();
        }

        @Override
        public UUID uuid() {
            return this.session.client().identity().uuid();
        }

        @Override
        public void sendMessage(String message) {
            TextPacket text = new TextPacket();
            text.setType(TextPacket.Type.RAW);
            text.setSourceName("");
            text.setMessage(message);
            text.setXuid("");
            text.setPlatformChatId("");
            text.setFilteredMessage("");
            this.session.sendToClient(text, true);
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isClosed() {
            return ClientDisconnect.ended(this.session.client().session()).isDone();
        }

        @Override
        public void disconnect(String reason) {
            ClientDisconnect.disconnect(this.session.client().session(), reason);
        }

        // A PlayStation answers Boar's NetworkStackLatency with the timestamp times a thousand, not a million.
        @Override
        public boolean requiresPingMagnitude() {
            return this.deviceOs == BuildPlatform.SONY.getId();
        }

        // Windows seeds random offsets from 64-bit X; Xbox is untested and assumed to match it, as in Boar.
        @Override
        public boolean wideRandomOffsetSeed() {
            return this.deviceOs == BuildPlatform.UWP.getId() || this.deviceOs == BuildPlatform.WIN32.getId() || this.deviceOs == BuildPlatform.XBOX.getId();
        }

        // Boar asks this when the client stops answering its NetworkStackLatency packets; the client's answers sent before it spawned never reach Boar, so a slow login would look like that.
        @Override
        public void disconnectFromServer(String reason) {
            LOG.warn("Boar would have sent {} off its server: {}", this.name(), reason);
        }

        @Override
        public int protocolVersion() {
            return this.session.codec().getProtocolVersion();
        }
    }

    // Boar's own packets to the client go past its listeners first, as the server's do, since it keeps track of the NetworkStackLatency packets it sends.
    private record Connection(BoariumPlayerManager players, Session session) implements BoarConnection {
        @Override
        public void sendPacket(BedrockPacket packet) {
            this.send(packet, false);
        }

        @Override
        public void sendPacketImmediately(BedrockPacket packet) {
            this.send(packet, true);
        }

        private void send(BedrockPacket packet, boolean immediately) {
            if (!this.getChannel().eventLoop().inEventLoop()) {
                try {
                    this.getChannel().eventLoop().execute(() -> this.send(packet, immediately));
                } catch (RejectedExecutionException e) {
                    ReferenceCountUtil.release(packet);
                }
                return;
            }
            BoarPlayer player = this.players.get(this.session);
            // Boar resends layer 1 as water only under a state it thinks is waterlogged, but a Bedrock block keeps its water in layer 1 and not in its state, so the resend takes what Boar's world holds in layer 1 instead.
            if (player != null && packet instanceof UpdateBlockPacket update && update.getDataLayer() == 1) {
                update.setDefinition(player.compensatedWorld.getBlockState(update.getBlockPosition(), 1).definition(player));
            }
            // Boar has no runtime ID for a block of UNKNOWN_ID, so the client keeps the block it has there.
            boolean unknownBlock = packet instanceof UpdateBlockPacket update && BedrockPalette.isUnknown(update.getDefinition());
            if (player != null && !unknownBlock && BoarProcessor.passes(player, packet, false)) {
                this.session.sendToClient(packet, immediately);
                if (!immediately) {
                    ((AckTransport) player.getAckTransport()).flushLater();
                }
            } else {
                ReferenceCountUtil.release(packet);
            }
        }

        @Override
        public Channel getChannel() {
            return channel(this.session);
        }
    }

    // Boar's own compensated world, which exists once the player does.
    private static final class CompensatedWorldAccessor implements WorldAccessor {
        private BoarPlayer player;

        @Override
        public BoarBlockState blockStateAt(Vector3i position, int layer) {
            return this.player.compensatedWorld.getBlockState(position, layer);
        }
    }

    // Boar's acknowledgments, and the flushes of its own packets to the client. Neither flushes a half-written server batch, which only the server's Flush or a transfer ends.
    static final class AckTransport extends BatchAcknowledgmentTransport {
        // Only used on the client's event loop.
        boolean serverBatchOpen;
        private boolean flushQueued;

        AckTransport(BoarPlayer player) {
            super(player);
        }

        @Override
        public void keepalive() {
            if (!this.serverBatchOpen) {
                super.keepalive();
            }
        }

        // Flushes Boar's own packets to the client once the task that wrote them is done. They would otherwise wait for the server's next Flush, since Boar skips its keepalive while an acknowledgment is pending.
        void flushLater() {
            if (this.serverBatchOpen || this.flushQueued) {
                return;
            }
            Channel channel = this.player.getConnection().getChannel();
            try {
                channel.eventLoop().execute(() -> {
                    this.flushQueued = false;
                    if (!this.serverBatchOpen) {
                        channel.flush();
                    }
                });
                this.flushQueued = true;
            } catch (RejectedExecutionException e) {
                // The client has left.
            }
        }
    }
}
