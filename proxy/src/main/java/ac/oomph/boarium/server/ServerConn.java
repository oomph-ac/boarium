package ac.oomph.boarium.server;

import ac.oomph.boarium.protocol.PacketBatch;
import ac.oomph.boarium.protocol.SpectrumFrameDecoder;
import ac.oomph.boarium.protocol.SpectrumFrameEncoder;
import ac.oomph.boarium.server.packet.SpectrumPacket;
import ac.oomph.boarium.transport.Transport;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A connection to one downstream server.
 * The handler given to {@link #dial} receives each packet the server sends as a {@link SpectrumPacket}, a decoded {@link BedrockPacket}, or a {@link ByteBuf} holding a packet the server did not ask the proxy to decode, and must release it.
 * The connection closes on the first read error, and {@link #cause()} keeps that error.
 */
public final class ServerConn {
    private static final AttributeKey<ServerConn> KEY = AttributeKey.valueOf(ServerConn.class.getName());

    private final Channel channel;
    private final BedrockCodec codec;
    private final BedrockCodecHelper helper;
    private final AtomicReference<Throwable> cause = new AtomicReference<>();

    ServerConn(Channel channel, BedrockCodec codec) {
        this.channel = channel;
        this.codec = codec;
        this.helper = codec.createHelper();
        this.helper.setBlockDefinitions(Definitions.ANY_BLOCK);
        this.helper.setItemDefinitions(SimpleDefinitionRegistry.<ItemDefinition>builder().build());
    }

    /**
     * Connects to the server at {@code addr}. {@code handler} makes the handler for a new connection, once for each address tried.
     */
    public static CompletableFuture<ServerConn> dial(Transport transport, String addr, BedrockCodec codec, Function<ServerConn, ChannelHandler> handler) {
        Future<Channel> dial = transport.dial(addr, new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                ServerConn conn = new ServerConn(channel, codec);
                channel.attr(KEY).set(conn);
                conn.initPipeline(handler.apply(conn));
            }
        });

        CompletableFuture<ServerConn> result = new CompletableFuture<>();
        result.whenComplete((conn, error) -> {
            if (result.isCancelled() || error != null) {
                dial.cancel(false);
            }
        });
        dial.addListener(done -> {
            if (!dial.isSuccess()) {
                result.completeExceptionally(dial.cause());
                return;
            }

            // The caller may have cancelled or timed out the result first; nobody would own this connection then.
            ServerConn conn = dial.getNow().attr(KEY).get();
            if (!result.complete(conn)) {
                conn.close();
            }
        });
        return result;
    }

    void initPipeline(ChannelHandler handler) {
        this.channel.pipeline().addLast(new SpectrumFrameDecoder(), new SpectrumFrameEncoder(), new ServerPacketCodec(this.codec, this.helper), handler, new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ServerConn.this.closeWithError(cause);
            }
        });
    }

    /**
     * Sends a {@link BedrockPacket} or a {@link SpectrumPacket}, taking ownership of it. An encoding error fails the returned future.
     */
    public ChannelFuture write(Object packet) {
        return this.channel.writeAndFlush(packet);
    }

    /**
     * Sends encoded packets, each one's header and body, as one batch. The caller keeps ownership of the packets.
     */
    public ChannelFuture writeBatch(List<ByteBuf> packets) {
        return this.channel.writeAndFlush(PacketBatch.of(this.channel.alloc(), packets));
    }

    public Channel channel() {
        return this.channel;
    }

    public Throwable cause() {
        return this.cause.get();
    }

    public void closeWithError(Throwable cause) {
        this.cause.compareAndSet(null, cause);
        this.channel.close();
    }

    public ChannelFuture close() {
        return this.channel.close();
    }
}
