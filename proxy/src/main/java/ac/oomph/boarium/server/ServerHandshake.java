package ac.oomph.boarium.server;

import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.ConnectionResponse;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs Spectrum's connection sequence with a downstream server, the way spectrum-internal's server.Conn does.
 * It sends the ConnectionRequest once connected, then reads ConnectionResponse, StartGame, ItemComponent, ChunkRadiusUpdated and PlayStatus in that order.
 * Every other packet, and the last two of those, waits until {@link #spawn()}, which replaces this handler with {@code next} and passes them on in order.
 */
public final class ServerHandshake extends ChannelInboundHandlerAdapter {
    // Spectrum asks every server for this radius during the sequence.
    private static final int CHUNK_RADIUS = 16;

    private enum Step { CONNECTION_RESPONSE, START_GAME, ITEM_COMPONENT, CHUNK_RADIUS_UPDATED, PLAY_STATUS, CONNECTED, SPAWNED }

    private final ServerConn conn;
    private final ConnectionRequest request;
    private final boolean clientCacheSupported;
    private final ChannelHandler next;

    private final Queue<Object> deferred = new ArrayDeque<>();
    private final CompletableFuture<ServerGameData> connected = new CompletableFuture<>();

    private boolean requestSent;
    private Step step = Step.CONNECTION_RESPONSE;
    private ConnectionResponse response;
    private StartGamePacket startGame;
    private List<ItemDefinition> items;
    private int chunkRadius;

    public ServerHandshake(ServerConn conn, ConnectionRequest request, boolean clientCacheSupported, ChannelHandler next) {
        this.conn = conn;
        this.request = request;
        this.clientCacheSupported = clientCacheSupported;
        this.next = next;
    }

    /**
     * Completes, on the connection's event loop, when the server has sent everything the client needs to start the game.
     * Fails if the connection closes first.
     */
    public CompletableFuture<ServerGameData> connected() {
        return this.connected;
    }

    /**
     * Tells the server the player has spawned, then hands the waiting packets to {@code next}.
     * Call it once {@link #connected()} has completed and the client has started the game; before that the returned future fails.
     */
    public CompletableFuture<Void> spawn() {
        CompletableFuture<Void> spawned = new CompletableFuture<>();
        try {
            this.conn.channel().eventLoop().execute(() -> this.spawnNow(spawned));
        } catch (RejectedExecutionException e) {
            spawned.completeExceptionally(e);
        }
        return spawned;
    }

    private void spawnNow(CompletableFuture<Void> spawned) {
        ChannelHandlerContext ctx = this.conn.channel().pipeline().context(this);
        if (this.step != Step.CONNECTED || ctx == null) {
            spawned.completeExceptionally(new IllegalStateException("cannot spawn at step " + this.step + (ctx == null ? " after the connection closed" : "")));
            return;
        }
        this.step = Step.SPAWNED;

        try {
            ClientCacheStatusPacket cacheStatus = new ClientCacheStatusPacket();
            cacheStatus.setSupported(this.clientCacheSupported);
            this.conn.write(cacheStatus);
            SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
            initialized.setRuntimeEntityId(this.response.runtimeId());
            this.conn.write(initialized);

            ctx.pipeline().addAfter(ctx.name(), null, this.next);
            Object msg;
            while ((msg = this.deferred.poll()) != null) {
                ctx.fireChannelRead(msg);
            }
            ctx.pipeline().remove(this);
            spawned.complete(null);
        } catch (Throwable t) {
            // The server was already told the player spawned, so the connection cannot go on.
            this.conn.closeWithError(t);
            spawned.completeExceptionally(t);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            this.sendRequest();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.sendRequest();
        ctx.fireChannelActive();
    }

    private void sendRequest() {
        if (!this.requestSent) {
            this.requestSent = true;
            this.conn.write(this.request);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        switch (this.step) {
            case CONNECTION_RESPONSE -> {
                if (msg instanceof ConnectionResponse response) {
                    this.response = response;
                    this.step = Step.START_GAME;
                    return;
                }
            }
            case START_GAME -> {
                if (msg instanceof StartGamePacket startGame) {
                    this.startGame = startGame;
                    this.step = Step.ITEM_COMPONENT;
                    return;
                }
            }
            case ITEM_COMPONENT -> {
                if (msg instanceof ItemComponentPacket items) {
                    this.items = items.getItems();
                    this.conn.setItemDefinitions(this.items);
                    RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
                    request.setRadius(CHUNK_RADIUS);
                    this.conn.write(request);
                    this.step = Step.CHUNK_RADIUS_UPDATED;
                    return;
                }
            }
            case CHUNK_RADIUS_UPDATED -> {
                if (msg instanceof ChunkRadiusUpdatedPacket radius) {
                    this.deferred.add(radius);
                    this.step = Step.PLAY_STATUS;
                    this.chunkRadius = radius.getRadius();
                    return;
                }
            }
            case PLAY_STATUS -> {
                if (msg instanceof PlayStatusPacket) {
                    this.deferred.add(msg);
                    this.step = Step.CONNECTED;
                    this.connected.complete(new ServerGameData(this.startGame, this.items, this.chunkRadius, this.response.runtimeId(), this.response.uniqueId()));
                    return;
                }
            }
            default -> {
            }
        }
        this.deferred.add(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.failConnected();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.failConnected();
        Object msg;
        while ((msg = this.deferred.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
    }

    private void failConnected() {
        Throwable cause = this.conn.cause();
        this.connected.completeExceptionally(cause != null ? cause : new ClosedChannelException());
    }
}
