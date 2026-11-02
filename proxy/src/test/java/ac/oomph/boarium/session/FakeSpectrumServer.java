package ac.oomph.boarium.session;

import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.ConnectionResponse;
import ac.oomph.boarium.server.packet.SpectrumPacket;
import ac.oomph.boarium.server.packet.SpectrumPackets;
import io.airlift.compress.snappy.SnappyDecompressor;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A downstream server for tests that runs the Spectrum connection sequence the way spectrum-pm-internal does, with gophertunnel's encoding of each Bedrock packet.
 * After the ConnectionRequest, every Bedrock packet it receives is put in {@link #received}, every batch frame in {@link #batches}, and every Spectrum packet in {@link #spectrum}.
 */
final class FakeSpectrumServer {
    static final long RUNTIME_ID = 9;
    static final long UNIQUE_ID = -9;

    private static final int FLAG_DECODE = 1;
    private static final int FLAG_COMPRESSED = 2;
    private static final int FLAG_BATCH = 4;
    private static Map<String, byte[]> gophertunnel;

    final CompletableFuture<ConnectionRequest> request = new CompletableFuture<>();
    final CompletableFuture<Channel> connection = new CompletableFuture<>();
    final BlockingQueue<BedrockPacket> received = new LinkedBlockingQueue<>();
    final BlockingQueue<List<byte[]>> batches = new LinkedBlockingQueue<>();
    final BlockingQueue<SpectrumPacket> spectrum = new LinkedBlockingQueue<>();
    // When false, the server takes the connection but never answers.
    boolean answer = true;

    private final BedrockCodec codec;
    private final Channel channel;

    FakeSpectrumServer(EventLoopGroup group, BedrockCodec codec) throws Exception {
        this.codec = codec;
        if (gophertunnel == null) {
            gophertunnel = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(FakeSpectrumServer.class.getResourceAsStream("/ac/oomph/boarium/server/gophertunnel-packets.txt"), StandardCharsets.UTF_8))) {
                reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split(" ")).forEach(parts -> gophertunnel.put(parts[0], ByteBufUtil.decodeHexDump(parts[1])));
            }
        }
        this.channel = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        FakeSpectrumServer.this.connection.complete(ch);
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(16 * 1024 * 1024, 0, 4, 0, 4), new Handler());
                    }
                })
                .bind("127.0.0.1", 0)
                .sync()
                .channel();
    }

    String address() {
        return "127.0.0.1:" + ((InetSocketAddress) this.channel.localAddress()).getPort();
    }

    void close() {
        this.channel.close().syncUninterruptibly();
        this.connection.thenAccept(Channel::close);
    }

    BedrockPacket await() throws InterruptedException {
        return poll(this.received);
    }

    static <T> T poll(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(10, TimeUnit.SECONDS);
        if (value == null) {
            throw new IllegalStateException("nothing from the proxy within 10 seconds");
        }
        return value;
    }

    // Sends one of the gophertunnel packets, with the decode flag or as bytes the proxy passes through.
    void send(String name, boolean decode) throws Exception {
        this.send(this.connection.get(10, TimeUnit.SECONDS), gophertunnel.get(name), decode);
    }

    void send(SpectrumPacket packet) throws Exception {
        Channel connection = this.connection.get(10, TimeUnit.SECONDS);
        ByteBuf encoded = SpectrumPackets.encode(connection.alloc(), packet);
        this.send(connection, ByteBufUtil.getBytes(encoded), false);
        encoded.release();
    }

    private void send(Channel connection, byte[] packet, boolean decode) {
        connection.writeAndFlush(Unpooled.buffer().writeInt(1 + packet.length).writeByte(decode ? FLAG_DECODE : 0).writeBytes(packet));
    }

    private final class Handler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            int flags = frame.readUnsignedByte();
            byte[] body = ByteBufUtil.getBytes(frame);
            if ((flags & FLAG_COMPRESSED) != 0) {
                byte[] out = new byte[SnappyDecompressor.getUncompressedLength(body, 0)];
                new SnappyDecompressor().decompress(body, 0, body.length, out, 0, out.length);
                body = out;
            }

            if ((flags & FLAG_BATCH) != 0) {
                ByteBuf batch = Unpooled.wrappedBuffer(body);
                List<byte[]> packets = new ArrayList<>();
                while (batch.isReadable()) {
                    byte[] packet = new byte[batch.readIntLE()];
                    batch.readBytes(packet);
                    packets.add(packet);
                    this.read(ctx, packet);
                }
                FakeSpectrumServer.this.batches.add(packets);
                return;
            }
            this.read(ctx, body);
        }

        private void read(ChannelHandlerContext ctx, byte[] bytes) {
            ByteBuf body = Unpooled.wrappedBuffer(bytes);
            SpectrumPacket spectrumPacket = SpectrumPackets.decode(body.duplicate());
            if (!FakeSpectrumServer.this.request.isDone()) {
                FakeSpectrumServer.this.request.complete((ConnectionRequest) spectrumPacket);
                if (FakeSpectrumServer.this.answer) {
                    ByteBuf response = SpectrumPackets.encode(ctx.alloc(), new ConnectionResponse(RUNTIME_ID, UNIQUE_ID));
                    FakeSpectrumServer.this.send(ctx.channel(), ByteBufUtil.getBytes(response), true);
                    response.release();
                    FakeSpectrumServer.this.send(ctx.channel(), gophertunnel.get("StartGame"), true);
                    FakeSpectrumServer.this.send(ctx.channel(), gophertunnel.get("ItemComponent"), true);
                }
                return;
            }
            if (spectrumPacket != null) {
                FakeSpectrumServer.this.spectrum.add(spectrumPacket);
                return;
            }

            int id = VarInts.readUnsignedInt(body) & SpectrumPackets.PACKET_ID_MASK;
            BedrockCodecHelper helper = FakeSpectrumServer.this.codec.createHelper();
            BedrockPacket packet = FakeSpectrumServer.this.codec.tryDecode(helper, body, id, PacketRecipient.SERVER);
            if (packet instanceof RequestChunkRadiusPacket) {
                FakeSpectrumServer.this.send(ctx.channel(), gophertunnel.get("ChunkRadiusUpdated"), true);
                FakeSpectrumServer.this.send(ctx.channel(), gophertunnel.get("PlayStatus"), true);
            }
            FakeSpectrumServer.this.received.add(packet);
        }

        // The proxy closes its connections with a reset, which needs no logging here.
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!(cause instanceof IOException)) {
                ctx.fireExceptionCaught(cause);
            }
            ctx.close();
        }
    }
}
