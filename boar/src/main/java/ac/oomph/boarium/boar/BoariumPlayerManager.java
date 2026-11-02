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
import ac.oomph.boarium.Boarium;
import ac.oomph.boarium.boar.blocks.TablePalette;
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

    // Adds the player of a session whose client was sent this StartGame, which sets the client's block runtime IDs.
    BoarPlayer add(Session session, StartGamePacket startGame) {
        // Boar's checks of an item the server's list lacks never match; most likely the server runs an older Bedrock version.
        List<String> missing = BedrockItems.missingReferences(session.itemDefinitions());
        if (!missing.isEmpty() && LOGGED_MISSING_ITEMS.add(missing)) {
            LOG.warn("The server of {} lacks Boar's items {}", session.client().identity().displayName(), missing);
        }
        this.adding.put(session, TablePalette.mappingInfo(startGame));
        try {
            return this.add(session);
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
        return new BoariumNetworkSession(session, deviceOs(session) == BuildPlatform.SONY.getId(), new BedrockItems.SessionItems(session.itemDefinitions()));
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

    // Boar must not change what players do until its mitigations are tested on boarium.
    @Override
    protected boolean shouldDisableMitigations(Session session) {
        return true;
    }

    // The client data's DeviceOS, or 0 when it has none.
    private static int deviceOs(Session session) {
        try {
            return JsonUtil.parseJson(new String(session.client().clientData(), StandardCharsets.UTF_8)).get("DeviceOS") instanceof Number os ? os.intValue() : 0;
        } catch (JoseException e) {
            return 0;
        }
    }

    private static Channel channel(Session session) {
        return session.client().session().getPeer().getChannel();
    }

    record BoariumNetworkSession(Session session, boolean playStation, BedrockItems.SessionItems items) implements NetworkSession {
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
            return this.playStation;
        }

        // Boar asks this when the client stops answering its NetworkStackLatency packets; the client's answers sent before it spawned never reach Boar, so a slow login would look like that.
        @Override
        public void disconnectFromServer(String reason) {
            LOG.warn("Boar would have sent {} off its server: {}", this.name(), reason);
        }

        @Override
        public int protocolVersion() {
            return Boarium.CODEC.getProtocolVersion();
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
            if (player != null && BoarProcessor.passes(player, packet, false)) {
                this.session.sendToClient(packet, immediately);
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

    // Boar's acknowledgments, whose keepalive flushes the client: it waits while a server batch is half written, which only the server's Flush ends.
    static final class AckTransport extends BatchAcknowledgmentTransport {
        // Only used on the client's event loop.
        boolean serverBatchOpen;

        AckTransport(BoarPlayer player) {
            super(player);
        }

        @Override
        public void keepalive() {
            if (!this.serverBatchOpen) {
                super.keepalive();
            }
        }
    }
}
