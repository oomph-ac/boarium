package ac.oomph.boarium.client;

import ac.oomph.boarium.protocol.BedrockVersions;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscoverySignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real NetherNet logins on loopback, through LAN discovery signaling, which hands the proxy the identity an offer carries.
@Timeout(60)
class NetherNetLoopbackLoginTest {
    private static final BedrockCodec CODEC = BedrockVersions.LATEST;

    private OperatorIdentity serverIdentity;
    private final CompletableFuture<ClientLogin> login = new CompletableFuture<>();
    private final List<Channel> channels = new ArrayList<>();
    private EventLoopGroup group;
    private InetSocketAddress address;

    @BeforeEach
    @Timeout(30)
    void setUp() throws Exception {
        this.serverIdentity = OperatorIdentity.generate("boarium-test");
        this.group = new NioEventLoopGroup(2);
        try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), probe.getLocalPort());
        }
        assertNotEquals(19132, this.address.getPort());

        // The client signs its own identity token, which MINECRAFT_AUTH would refuse.
        NetherNetDiscoverySignaling signaling = new NetherNetDiscoverySignaling(ThreadLocalRandom.current().nextLong(), this.address);
        signaling.setTokenTrust(TokenTrust.ANY);
        signaling.setServerIdentity(this.serverIdentity);
        // Without pong data the server leaves discovery requests unanswered, so a client never finds it.
        signaling.setAdvertisementData(PongData.DEFAULT);
        this.channels.add(new ServerBootstrap()
                .group(this.group)
                .channelFactory(NetherNetChannelFactory.server(signaling))
                .childHandler(new NetherNetInitializer(false, Duration.ofMinutes(1), this.login::complete))
                .bind(this.address)
                .sync()
                .channel());
    }

    @AfterEach
    @Timeout(30)
    void tearDown() {
        this.channels.forEach(channel -> channel.close().syncUninterruptibly());
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void logsInAClientWhoseLoginKeyIsItsSignalingKey() throws Exception {
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.connect(client, client.key);

        client.loginDone.get(30, TimeUnit.SECONDS);
        assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName());
        assertTrue(client.received.stream().anyMatch(p -> p instanceof PlayStatusPacket status && status.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS));
        assertTrue(client.received.stream().noneMatch(ServerToClientHandshakePacket.class::isInstance));
    }

    @Test
    void turnsAwayALoginSignedWithAnotherKeyThanTheSignalingOne() throws Exception {
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.connect(client, EncryptionUtils.createKeyPair());
        this.assertTurnedAway(client);
    }

    @Test
    void turnsAwayALoginWhoseOfferCarriedNoIdentity() throws Exception {
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.connect(client, null);
        this.assertTurnedAway(client);
    }

    private void connect(TestClient client, KeyPair signalingKey) throws Exception {
        OperatorIdentity identity = signalingKey == null ? null : new OperatorIdentity(signalingKey.getPrivate(), signalingKey.getPublic(), null, "boarium-test").forPlayer("", client.name);
        this.channels.add(client.connectNetherNet(this.group, this.address, identity, TokenTrust.pinnedTo(this.serverIdentity.publicKey())));
    }

    private void assertTurnedAway(TestClient client) {
        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertEquals("disconnected: disconnectionScreen.notAuthenticated", error);
        assertFalse(this.login.isDone());
    }
}
