package ac.oomph.boarium.server;

import ac.oomph.boarium.protocol.SpectrumFrame;
import ac.oomph.boarium.protocol.SpectrumFrameDecoder;
import ac.oomph.boarium.server.packet.ConnectionRequest;
import ac.oomph.boarium.server.packet.ConnectionResponse;
import ac.oomph.boarium.server.packet.SpectrumPackets;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipelineException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerHandshakeTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;
    private static final ConnectionRequest REQUEST = new ConnectionRequest("127.0.0.1:50000", new byte[]{'{', '}'}, new byte[]{'{', '}'}, 1001, new byte[0]);

    private static Map<String, byte[]> gophertunnel;

    private EmbeddedChannel channel;
    private ServerConn conn;
    private ServerHandshake handshake;
    private final List<Object> afterSpawn = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        if (gophertunnel == null) {
            gophertunnel = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ServerHandshakeTest.class.getResourceAsStream("gophertunnel-packets.txt"), StandardCharsets.UTF_8))) {
                reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split(" ")).forEach(parts -> gophertunnel.put(parts[0], ByteBufUtil.decodeHexDump(parts[1])));
            }
        }

        this.channel = new EmbeddedChannel();
        this.conn = new ServerConn(this.channel, CODEC);
        this.handshake = new ServerHandshake(this.conn, REQUEST, true, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ServerHandshakeTest.this.afterSpawn.add(msg);
            }
        });
        this.conn.initPipeline(this.handshake);
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    private void receive(String name) {
        this.receive(gophertunnel.get(name));
    }

    private void receive(byte[] packet) {
        // Frame with the decode flag, as spectrum-pm-internal sends these packets.
        this.channel.writeInbound(Unpooled.buffer().writeInt(1 + packet.length).writeByte(1).writeBytes(packet));
    }

    private void receive(ConnectionResponse response) {
        ByteBuf packet = SpectrumPackets.encode(this.channel.alloc(), response);
        this.receive(ByteBufUtil.getBytes(packet));
        packet.release();
    }

    // Reads the next frame the proxy sent, which is small enough to be uncompressed.
    private ByteBuf sentBody() {
        ByteBuf frame = this.channel.readOutbound();
        assertEquals(0, frame.getByte(4));
        return frame.skipBytes(5);
    }

    private BedrockPacket sentBedrockPacket() {
        ByteBuf body = this.sentBody();
        try {
            int id = VarInts.readUnsignedInt(body) & SpectrumPackets.PACKET_ID_MASK;
            BedrockCodecHelper helper = CODEC.createHelper();
            return CODEC.tryDecode(helper, body, id, PacketRecipient.SERVER);
        } finally {
            body.release();
        }
    }

    private void runToConnected() {
        this.receive(new ConnectionResponse(9, -9));
        this.receive("SetTime");
        this.receive("StartGame");
        this.receive("ItemComponent");
        this.receive("ChunkRadiusUpdated");
        this.receive("PlayStatus");
    }

    @Test
    void sendsTheConnectionRequestOnceConnectedOverTcp() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            CompletableFuture<ByteBuf> firstFrame = new CompletableFuture<>();
            Channel server = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new SpectrumFrameDecoder(), new SimpleChannelInboundHandler<SpectrumFrame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, SpectrumFrame frame) {
                                    firstFrame.complete(frame.content().retain());
                                }
                            });
                        }
                    })
                    .bind("127.0.0.1", 0)
                    .sync()
                    .channel();

            String addr = "127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort();
            ServerConn conn = ServerConn.dial(new TcpTransport(group, Duration.ofSeconds(5)), addr, CODEC, c -> new ServerHandshake(c, REQUEST, true, new ChannelInboundHandlerAdapter())).get(5, TimeUnit.SECONDS);

            ByteBuf body = firstFrame.get(5, TimeUnit.SECONDS);
            assertEquals(REQUEST.addr(), assertInstanceOf(ConnectionRequest.class, SpectrumPackets.decode(body)).addr());
            body.release();
            conn.close().sync();
            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void sendsTheConnectionRequestFirst() {
        ByteBuf body = this.sentBody();
        ConnectionRequest sent = assertInstanceOf(ConnectionRequest.class, SpectrumPackets.decode(body));
        assertEquals(REQUEST.addr(), sent.addr());
        assertEquals(REQUEST.protocolId(), sent.protocolId());
        body.release();

        // A later channelActive must not send it again.
        this.channel.pipeline().fireChannelActive();
        assertNull(this.channel.readOutbound());
    }

    @Test
    void runsTheSequenceAndHandsOverAfterSpawn() throws Exception {
        this.sentBody().release();
        this.runToConnected();

        RequestChunkRadiusPacket request = assertInstanceOf(RequestChunkRadiusPacket.class, this.sentBedrockPacket());
        assertEquals(16, request.getRadius());

        ServerGameData data = this.handshake.connected().getNow(null);
        assertEquals("boarium", data.startGame().getLevelName());
        assertEquals(12, data.chunkRadius());
        assertEquals(9, data.runtimeId());
        assertEquals(-9, data.uniqueId());
        assertEquals(2, data.items().size());
        assertTrue(this.afterSpawn.isEmpty());

        this.handshake.spawn();
        this.channel.runPendingTasks();

        assertTrue(assertInstanceOf(ClientCacheStatusPacket.class, this.sentBedrockPacket()).isSupported());
        assertEquals(9, assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, this.sentBedrockPacket()).getRuntimeEntityId());
        assertEquals(3, this.afterSpawn.size());
        assertEquals(42, assertInstanceOf(SetTimePacket.class, this.afterSpawn.get(0)).getTime());
        assertEquals(12, assertInstanceOf(ChunkRadiusUpdatedPacket.class, this.afterSpawn.get(1)).getRadius());
        assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, assertInstanceOf(PlayStatusPacket.class, this.afterSpawn.get(2)).getStatus());
        assertNull(this.channel.pipeline().get(ServerHandshake.class));

        this.receive("SetTime");
        assertEquals(4, this.afterSpawn.size());
        assertFalse(this.channel.finish());
    }

    @Test
    void usesTheServersItemsForLaterPackets() {
        this.runToConnected();
        this.handshake.spawn();
        this.channel.runPendingTasks();

        this.receive("InventoryContentShield");
        InventoryContentPacket inventory = assertInstanceOf(InventoryContentPacket.class, this.afterSpawn.getLast());
        assertEquals("minecraft:shield", inventory.getContents().getFirst().getDefinition().getIdentifier());
    }

    @Test
    void spawnFailsBeforeTheServerIsReady() {
        this.receive(new ConnectionResponse(9, -9));

        var spawn = this.handshake.spawn();
        this.channel.runPendingTasks();

        assertInstanceOf(IllegalStateException.class, spawn.exceptionNow());
        assertFalse(this.handshake.connected().isDone());
    }

    @Test
    void failsAndReleasesWhenTheConnectionCloses() {
        this.receive(new ConnectionResponse(9, -9));
        // A packet without the decode flag waits as a slice of the read buffer, which keeps that buffer alive until it is released.
        byte[] packet = gophertunnel.get("SetTime");
        ByteBuf in = Unpooled.buffer().writeInt(1 + packet.length).writeByte(0).writeBytes(packet);
        this.channel.writeInbound(in);
        assertEquals(1, in.refCnt());

        this.channel.writeInbound(Unpooled.buffer().writeInt(0));

        assertTrue(this.handshake.connected().isCompletedExceptionally());
        assertFalse(this.channel.isOpen());
        assertEquals(0, in.refCnt());
    }

    @Test
    void closesTheConnectionWhenSpawnFails() {
        ChannelInboundHandlerAdapter alreadyUsed = new ChannelInboundHandlerAdapter();
        new EmbeddedChannel(alreadyUsed).finishAndReleaseAll();
        ServerHandshake handshake = new ServerHandshake(this.conn, REQUEST, true, alreadyUsed);
        this.channel.pipeline().replace(this.handshake, null, handshake);
        this.handshake = handshake;
        this.runToConnected();

        var spawn = this.handshake.spawn();
        this.channel.runPendingTasks();

        assertInstanceOf(ChannelPipelineException.class, spawn.exceptionNow());
        assertFalse(this.channel.isOpen());
    }

    @Test
    void spawnFailsAfterTheConnectionCloses() {
        this.runToConnected();
        this.channel.close();

        var spawn = this.handshake.spawn();
        this.channel.runPendingTasks();

        assertInstanceOf(IllegalStateException.class, spawn.exceptionNow());
    }

    @Test
    void waitsForPacketsInOrder() {
        // StartGame before ConnectionResponse is not the step being waited for, so it is kept for later, as spectrum-internal does.
        this.receive("StartGame");
        this.receive(new ConnectionResponse(9, -9));
        this.receive("ItemComponent");
        this.receive("ChunkRadiusUpdated");
        this.receive("PlayStatus");

        assertFalse(this.handshake.connected().isDone());
    }
}
