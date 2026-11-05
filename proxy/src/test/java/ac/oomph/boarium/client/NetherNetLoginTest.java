package ac.oomph.boarium.client;

import ac.oomph.boarium.protocol.BedrockVersions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// The login over channels with no real transport under them: the test feeds the pipeline the client's messages and takes what the proxy sends at the pipeline's head.
// Most tests use a NetherNetChildChannel with no WebRTC peer, since Cloudburst checks the signaling identity only on one.
class NetherNetLoginTest {
    private static final BedrockCodec CODEC = BedrockVersions.LATEST;

    private final DefaultEventLoop loop = new DefaultEventLoop();
    private final TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
    private final BlockingQueue<ByteBuf> sent = new LinkedBlockingQueue<>();
    private final CompletableFuture<ClientLogin> login = new CompletableFuture<>();
    private Channel channel;

    @AfterEach
    void tearDown() {
        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
        }
        this.loop.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        this.sent.forEach(ByteBuf::release);
    }

    @Test
    void logsInAClientWhoseLoginKeyIsItsSignalingKeyWithoutTheHandshake() throws Exception {
        this.openChild(this.client.key);
        List<BedrockPacket> answer = this.logIn();
        assertEquals(PlayStatusPacket.Status.LOGIN_SUCCESS, assertInstanceOf(PlayStatusPacket.class, answer.get(0)).getStatus());
        assertInstanceOf(ResourcePacksInfoPacket.class, answer.get(1));
        assertEquals(2, answer.size());
        assertEquals(TransportIdentityBinding.State.ACCEPTED, TransportIdentityBinding.state(this.channel));

        this.send(true, response(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS));
        assertInstanceOf(ResourcePackStackPacket.class, this.receive().getFirst());
        this.send(true, response(ResourcePackClientResponsePacket.Status.COMPLETED));
        assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName());
    }

    @Test
    void turnsAwayALoginSignedWithAnotherKeyThanTheSignalingOne() throws Exception {
        this.openChild(EncryptionUtils.createKeyPair());
        this.assertTurnedAway();
        assertEquals(TransportIdentityBinding.State.REJECTED, TransportIdentityBinding.state(this.channel));
    }

    @Test
    void turnsAwayALoginWhoseSignalingCarriedNoIdentity() throws Exception {
        this.openChild(null);
        this.assertTurnedAway();
    }

    // Not an EmbeddedChannel: its event loop counts every thread as its own, which fails the login's assert that it is checked off the event loop.
    @Test
    void turnsAwayALoginOnAChannelThatIsNotANetherNetChildChannel() throws Exception {
        this.open(new LocalChannel());
        this.assertTurnedAway();
    }

    // signalingKey is the key the client proved at signaling, or null when the signaling carried no identity.
    private void openChild(KeyPair signalingKey) throws Exception {
        NetherNetChildChannel child = new NetherNetChildChannel(null, null, null, new InetSocketAddress(0));
        if (signalingKey != null) {
            JwtClaims claims = new JwtClaims();
            claims.setClaim("cpk", Base64.getEncoder().encodeToString(signalingKey.getPublic().getEncoded()));
            PlayerInfo player = new PlayerInfo("", "Tester", "1", null, null, claims);
            TransportIdentityBinding.install(child, TransportIdentityBinding.forPlayer(player));
        }
        this.open(child);
    }

    private void open(Channel channel) throws Exception {
        this.channel = channel;
        this.channel.pipeline().addLast(new NetherNetInitializer(false, Duration.ofMinutes(1), this.login::complete));
        this.loop.register(this.channel).sync();
        this.loop.submit(() -> {
            this.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    NetherNetLoginTest.this.sent.add((ByteBuf) msg);
                    promise.setSuccess();
                }
            });
            // A child channel turns active once its WebRTC data channels open, which never happens here.
            this.channel.pipeline().fireChannelActive();
        }).sync();
    }

    // Sends network settings and the login, and returns the proxy's answer to the login.
    private List<BedrockPacket> logIn() throws Exception {
        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(CODEC.getProtocolVersion());
        this.send(false, request);
        assertInstanceOf(NetworkSettingsPacket.class, this.receive(false).getFirst());
        this.send(true, this.client.login());
        return this.receive();
    }

    private void assertTurnedAway() throws Exception {
        List<BedrockPacket> answer = this.logIn();
        assertEquals("disconnectionScreen.notAuthenticated", assertInstanceOf(DisconnectPacket.class, answer.getFirst()).getKickMessage());
        assertEquals(1, answer.size());
        assertFalse(this.login.isDone());
    }

    private void send(boolean compressed, BedrockPacket packet) throws Exception {
        ByteBuf batch = NetherNetInitializerTest.batch(packet);
        ByteBuf message = compressed ? compressed(batch) : batch;
        this.loop.submit(() -> this.channel.pipeline().fireChannelRead(message)).sync();
    }

    private List<BedrockPacket> receive() throws Exception {
        return this.receive(true);
    }

    private List<BedrockPacket> receive(boolean compressed) throws Exception {
        ByteBuf message = this.sent.poll(10, TimeUnit.SECONDS);
        assertNotNull(message, "nothing sent within 10 seconds");
        try {
            if (!compressed) {
                return NetherNetInitializerTest.decodeBatch(message.retain());
            }
            assertEquals(NetherNetInitializerTest.ZLIB_HEADER, message.readByte());
            return new ArrayList<>(NetherNetInitializerTest.decodeBatch(NetherNetInitializerTest.inflate(message)));
        } finally {
            message.release();
        }
    }

    private static ByteBuf compressed(ByteBuf batch) {
        return Unpooled.buffer().writeByte(NetherNetInitializerTest.ZLIB_HEADER).writeBytes(NetherNetInitializerTest.deflate(batch));
    }

    private static ResourcePackClientResponsePacket response(ResourcePackClientResponsePacket.Status status) {
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(status);
        return response;
    }
}
