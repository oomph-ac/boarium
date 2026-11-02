package ac.oomph.boarium.server;

import ac.oomph.boarium.protocol.Definitions;
import ac.oomph.boarium.protocol.SpectrumFrame;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.SpectrumPackets;
import ac.oomph.boarium.transport.TcpTransport;
import ac.oomph.boarium.transport.Transport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.TickSyncPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConnTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;

    private static ByteBuf encode(BedrockPacket packet) {
        BedrockCodecHelper helper = CODEC.createHelper();
        helper.setBlockDefinitions(Definitions.ANY_BLOCK);
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeUnsignedInt(buf, CODEC.getPacketDefinition(packet.getClass()).getId());
        CODEC.tryEncode(helper, buf, packet);
        return buf;
    }

    private static EmbeddedChannel decoder() {
        BedrockCodecHelper helper = CODEC.createHelper();
        helper.setBlockDefinitions(Definitions.ANY_BLOCK);
        return new EmbeddedChannel(new ServerPacketCodec(CODEC, helper));
    }

    private static SetTimePacket setTime(int time) {
        SetTimePacket packet = new SetTimePacket();
        packet.setTime(time);
        return packet;
    }

    // Collects what reaches the handler after ServerConn's own pipeline.
    private static final class Collector extends ChannelInboundHandlerAdapter {
        final List<Object> received = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            this.received.add(msg);
        }
    }

    @Test
    void decodesSpectrumPacketsWhateverTheFlag() {
        EmbeddedChannel channel = decoder();
        ByteBuf latency = SpectrumPackets.encode(channel.alloc(), new Latency(1, 2));
        channel.writeInbound(new SpectrumFrame(latency, false));

        assertEquals(new Latency(1, 2), channel.readInbound());
        assertEquals(0, latency.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void decodesBedrockPacketWhenAsked() {
        EmbeddedChannel channel = decoder();
        channel.writeInbound(new SpectrumFrame(encode(setTime(6000)), true));

        assertEquals(6000, assertInstanceOf(SetTimePacket.class, channel.readInbound()).getTime());
        assertFalse(channel.finish());
    }

    private static ByteBuf frame(int flags, ByteBuf body) {
        ByteBuf frame = Unpooled.buffer().writeInt(1 + body.readableBytes()).writeByte(flags).writeBytes(body);
        body.release();
        return frame;
    }

    @Test
    void keepsAnyBlockRuntimeId() {
        UpdateBlockPacket update = new UpdateBlockPacket();
        update.setBlockPosition(Vector3i.from(1, 2, 3));
        update.setDefinition(Definitions.ANY_BLOCK.getDefinition(123456));
        update.setDataLayer(0);

        EmbeddedChannel channel = new EmbeddedChannel();
        Collector collector = new Collector();
        new ServerConn(channel, CODEC).initPipeline(collector);
        channel.writeInbound(frame(1, encode(update)));

        UpdateBlockPacket received = assertInstanceOf(UpdateBlockPacket.class, collector.received.getFirst());
        assertEquals(123456, received.getDefinition().getRuntimeId());
        assertEquals(Definitions.ANY_BLOCK.getDefinition(123456), received.getDefinition());
        assertFalse(channel.finish());
    }

    @Test
    void ignoresSubClientBitsOnBedrockPackets() {
        ByteBuf body = encode(setTime(7));
        VarInts.readUnsignedInt(body);
        ByteBuf packet = Unpooled.buffer();
        VarInts.writeUnsignedInt(packet, CODEC.getPacketDefinition(SetTimePacket.class).getId() | 1 << 10 | 1 << 12);
        packet.writeBytes(body);

        EmbeddedChannel channel = decoder();
        channel.writeInbound(new SpectrumFrame(packet, true));

        assertEquals(7, assertInstanceOf(SetTimePacket.class, channel.readInbound()).getTime());
        assertFalse(channel.finish());
    }

    @Test
    void passesEmptyPacketAsBytes() {
        EmbeddedChannel channel = decoder();
        channel.writeInbound(new SpectrumFrame(Unpooled.buffer(), false));

        ByteBuf raw = channel.readInbound();
        assertEquals(0, raw.readableBytes());
        raw.release();
        assertFalse(channel.finish());
    }

    @Test
    void writesBackUnknownPackets() {
        ByteBuf packet = Unpooled.buffer();
        VarInts.writeUnsignedInt(packet, 450);
        packet.writeBytes(new byte[]{1, 2, 3});
        byte[] bytes = ByteBufUtil.getBytes(packet);

        EmbeddedChannel channel = new EmbeddedChannel();
        Collector collector = new Collector();
        ServerConn conn = new ServerConn(channel, CODEC);
        conn.initPipeline(collector);
        channel.writeInbound(frame(1, packet));

        UnknownPacket unknown = assertInstanceOf(UnknownPacket.class, collector.received.getFirst());
        assertTrue(conn.write(unknown).isSuccess());
        assertArrayEquals(bytes, readFrameBody(channel, 0));
        assertFalse(channel.finish());
    }

    @Test
    void passesOtherPacketsAsBytes() {
        EmbeddedChannel channel = decoder();
        ByteBuf packet = encode(setTime(6000));
        byte[] bytes = ByteBufUtil.getBytes(packet);
        channel.writeInbound(new SpectrumFrame(packet, false));

        ByteBuf raw = channel.readInbound();
        assertArrayEquals(bytes, ByteBufUtil.getBytes(raw));
        raw.release();
        assertEquals(0, packet.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void rejectsPacketsMeantForTheServer() {
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(8);
        EmbeddedChannel channel = decoder();

        assertThrows(DecoderException.class, () -> channel.writeInbound(new SpectrumFrame(encode(request), true)));
    }

    // Reads one small, uncompressed frame the conn wrote, checks its flags and returns its body.
    private static byte[] readFrameBody(EmbeddedChannel channel, int flags) {
        ByteBuf frame = channel.readOutbound();
        try {
            assertEquals(frame.readableBytes() - 4, frame.readInt());
            assertEquals(flags, frame.readByte());
            return ByteBufUtil.getBytes(frame);
        } finally {
            frame.release();
        }
    }

    @Test
    void writesPacketsAsFrames() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConn conn = new ServerConn(channel, CODEC);
        conn.initPipeline(new Collector());

        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(8);
        request.setMaxRadius(16);
        conn.write(request);
        conn.write(new Latency(3, 4));
        conn.writeBatch(List.of(Unpooled.wrappedBuffer(new byte[]{1}), Unpooled.wrappedBuffer(new byte[]{2})));

        ByteBuf expected = encode(request);
        assertArrayEquals(ByteBufUtil.getBytes(expected), readFrameBody(channel, 0));
        expected.release();
        assertEquals(new Latency(3, 4), SpectrumPackets.decode(Unpooled.wrappedBuffer(readFrameBody(channel, 0))));
        assertArrayEquals(new byte[]{1, 0, 0, 0, 1, 1, 0, 0, 0, 2}, readFrameBody(channel, 4));
        assertFalse(channel.finish());
    }

    @Test
    void failsTheWriteWhenAPacketCannotBeEncoded() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConn conn = new ServerConn(channel, CODEC);
        conn.initPipeline(new Collector());

        // Protocol 1001 has no TickSyncPacket, so encoding it fails.
        ChannelFuture write = conn.write(new TickSyncPacket());

        assertFalse(write.isSuccess());
        assertInstanceOf(EncoderException.class, write.cause());
        assertInstanceOf(IllegalArgumentException.class, write.cause().getCause());
        assertTrue(channel.isOpen());
        assertNull(channel.readOutbound());
        assertFalse(channel.finish());
    }

    @Test
    void closesOnTheFirstErrorAndKeepsIt() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConn conn = new ServerConn(channel, CODEC);
        Collector collector = new Collector();
        conn.initPipeline(collector);

        channel.writeInbound(Unpooled.buffer().writeInt(0));

        assertFalse(channel.isOpen());
        assertInstanceOf(CorruptedFrameException.class, conn.cause());
        assertTrue(collector.received.isEmpty());
    }

    @Test
    void keepsTheFirstError() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ServerConn conn = new ServerConn(channel, CODEC);
        conn.initPipeline(new Collector());
        RuntimeException first = new RuntimeException("first");

        conn.closeWithError(first);
        conn.closeWithError(new RuntimeException("second"));

        assertEquals(first, conn.cause());
    }

    // A transport whose dial the test completes by hand, on a channel the initializer has already set up.
    private static final class ManualTransport implements Transport {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Promise<Channel> promise = ImmediateEventExecutor.INSTANCE.newPromise();

        @Override
        public Future<Channel> dial(String addr, ChannelInitializer<Channel> initializer) {
            this.channel.pipeline().addLast(initializer);
            return this.promise;
        }
    }

    @Test
    void closesAConnectionThatArrivesAfterTheCallerGaveUp() {
        ManualTransport transport = new ManualTransport();
        transport.promise.setUncancellable();
        CompletableFuture<ServerConn> dial = ServerConn.dial(transport, "server:19132", CODEC, c -> new Collector());

        dial.cancel(false);
        transport.promise.setSuccess(transport.channel);

        assertFalse(transport.channel.isOpen());
    }

    @Test
    void cancelsTheTransportDialWhenTheCallerGivesUp() {
        ManualTransport transport = new ManualTransport();
        CompletableFuture<ServerConn> dial = ServerConn.dial(transport, "server:19132", CODEC, c -> new Collector());

        dial.completeExceptionally(new TimeoutException());

        assertTrue(transport.promise.isCancelled());
    }

    @Test
    void dialsAndReceivesOverTcp() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ByteBuf body = encode(setTime(42));
            ByteBuf frame = Unpooled.buffer().writeInt(1 + body.readableBytes()).writeByte(1).writeBytes(body);
            Channel server = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.writeAndFlush(frame);
                        }
                    })
                    .bind("127.0.0.1", 0)
                    .sync()
                    .channel();

            CompletableFuture<Object> received = new CompletableFuture<>();
            String addr = "127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
            ServerConn conn = ServerConn.dial(new TcpTransport(group, Duration.ofSeconds(5)), addr, CODEC, c -> new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    received.complete(msg);
                }
            }).get(5, TimeUnit.SECONDS);

            assertEquals(42, assertInstanceOf(SetTimePacket.class, received.get(5, TimeUnit.SECONDS)).getTime());
            assertNull(conn.cause());
            conn.close().sync();
            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }
}
