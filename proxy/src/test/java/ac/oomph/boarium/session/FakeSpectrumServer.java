package ac.oomph.boarium.session;

import ac.oomph.boarium.protocol.SpectrumFrame;
import ac.oomph.boarium.protocol.SpectrumFrameDecoder;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.ConnectionResponse;
import ac.oomph.boarium.server.packet.SpectrumPacket;
import ac.oomph.boarium.server.packet.SpectrumPackets;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A downstream server for tests that runs the Spectrum connection sequence the way spectrum-pm-internal does, with gophertunnel's encoding of each Bedrock packet.
 * Every Bedrock packet it receives after the ConnectionRequest is put in {@link #received}.
 */
final class FakeSpectrumServer {
    static final long RUNTIME_ID = 9;
    static final long UNIQUE_ID = -9;

    private static Map<String, byte[]> gophertunnel;

    final CompletableFuture<ConnectionRequest> request = new CompletableFuture<>();
    final CompletableFuture<Channel> connection = new CompletableFuture<>();
    final BlockingQueue<BedrockPacket> received = new LinkedBlockingQueue<>();
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
                        ch.pipeline().addLast(new SpectrumFrameDecoder(), new Handler());
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
        BedrockPacket packet = this.received.poll(10, TimeUnit.SECONDS);
        if (packet == null) {
            throw new IllegalStateException("no packet from the proxy within 10 seconds");
        }
        return packet;
    }

    // Frames a packet with the decode flag, as spectrum-pm-internal sends the packets of the sequence.
    private static void send(ChannelHandlerContext ctx, byte[] packet) {
        ctx.writeAndFlush(Unpooled.buffer().writeInt(1 + packet.length).writeByte(1).writeBytes(packet));
    }

    private final class Handler extends SimpleChannelInboundHandler<SpectrumFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, SpectrumFrame frame) {
            ByteBuf body = frame.content();
            if (!FakeSpectrumServer.this.request.isDone()) {
                SpectrumPacket packet = SpectrumPackets.decode(body);
                FakeSpectrumServer.this.request.complete((ConnectionRequest) packet);
                if (FakeSpectrumServer.this.answer) {
                    ByteBuf response = SpectrumPackets.encode(ctx.alloc(), new ConnectionResponse(RUNTIME_ID, UNIQUE_ID));
                    send(ctx, ByteBufUtil.getBytes(response));
                    response.release();
                    send(ctx, gophertunnel.get("StartGame"));
                    send(ctx, gophertunnel.get("ItemComponent"));
                }
                return;
            }

            int id = VarInts.readUnsignedInt(body) & SpectrumPackets.PACKET_ID_MASK;
            BedrockCodecHelper helper = FakeSpectrumServer.this.codec.createHelper();
            BedrockPacket packet = FakeSpectrumServer.this.codec.tryDecode(helper, body, id, PacketRecipient.SERVER);
            if (packet instanceof RequestChunkRadiusPacket) {
                send(ctx, gophertunnel.get("ChunkRadiusUpdated"));
                send(ctx, gophertunnel.get("PlayStatus"));
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
