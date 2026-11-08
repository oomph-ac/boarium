package ac.oomph.boarium.client;

import ac.oomph.boarium.NetherNetHttps;
import ac.oomph.boarium.TestCertificate;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The listener's NetherNet side: joins by address over HTTP or HTTPS signaling on loopback, next to RakNet on the same port.
@Timeout(60)
class ClientListenerNetherNetTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;
    // The QA network and live tests hold these, so a test must never bind them.
    private static final Set<Integer> TAKEN_PORTS = Set.of(19132, 19133, 19142, 19143);

    private EventLoopGroup group;
    private ClientListener listener;
    private final CompletableFuture<ClientLogin> login = new CompletableFuture<>();
    private final List<Channel> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(2);
    }

    @AfterEach
    @Timeout(30)
    void tearDown() {
        this.clients.forEach(channel -> channel.close().syncUninterruptibly());
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    // Port 0 lets the system pick a free port.
    private InetSocketAddress listen(boolean netherNet) throws Exception {
        return this.listen(0, netherNet, false);
    }

    private InetSocketAddress listen(int port, boolean netherNet) throws Exception {
        return this.listen(port, netherNet, false);
    }

    private InetSocketAddress listen(int port, boolean netherNet, boolean requireXbox) throws Exception {
        return this.listen(port, netherNet, requireXbox, null);
    }

    private InetSocketAddress listen(int port, boolean netherNet, boolean requireXbox, SslContext tls) throws Exception {
        assertFalse(TAKEN_PORTS.contains(port), String.valueOf(port));
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival").playerCount(3).maximumPlayerCount(10);
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", port), pong, requireXbox, netherNet, tls, Duration.ofMinutes(1), this.login::complete);
        InetSocketAddress address = this.listener.address();
        assertFalse(TAKEN_PORTS.contains(address.getPort()), address.toString());
        return address;
    }

    @Test
    void logsInAClientThatJoinsByAddressOverHttp() throws Exception {
        InetSocketAddress address = this.listen(true);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address));

        client.loginDone.get(30, TimeUnit.SECONDS);
        ClientLogin login = this.login.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", login.identity().displayName());
        assertTrue(client.received.stream().anyMatch(p -> p instanceof PlayStatusPacket status && status.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS));
        assertTrue(login.session().getPeer() instanceof NetherNetPeer);
    }

    @Test
    void logsInAClientThatJoinsByAddressOverHttps(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        InetSocketAddress address = this.listen(0, true, false, new NetherNetHttps(certificate.certificate(), certificate.privateKey(), null).load());
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        HttpSignalingSettings https = HttpSignalingSettings.DEFAULT.withScheme(HttpSignalingSettings.Scheme.HTTPS).withSslContext(certificate.trusted());
        this.clients.add(client.joinByAddress(this.group, address, https));

        client.loginDone.get(30, TimeUnit.SECONDS);
        ClientLogin login = this.login.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", login.identity().displayName());
        assertTrue(login.session().getPeer() instanceof NetherNetPeer);
    }

    @Test
    void turnsAwayPlainHttpWhenServingHttps(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        InetSocketAddress address = this.listen(0, true, false, new NetherNetHttps(certificate.pkcs12(), null, certificate.password()).load());
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("HTTP 426"), error);
        assertFalse(this.login.isDone());
    }

    @Test
    void answersTheStatusRequestWithThePong() throws Exception {
        InetSocketAddress address = this.listen(true);
        NetherNetHTTPClientSignaling.Probe probe = NetherNetHTTPClientSignaling.probe(address, HttpSignalingSettings.DEFAULT.withScheme(HttpSignalingSettings.Scheme.HTTP)).get(10, TimeUnit.SECONDS);
        assertEquals("boarium", probe.motd().serverName());
        assertEquals(3, probe.motd().playerCount());
        assertEquals(10, probe.motd().maxPlayerCount());
    }

    @Test
    void stillLogsInOverRakNetOnTheSamePort() throws Exception {
        InetSocketAddress address = this.listen(true);
        // NetherNet's signaling holds the TCP side of the port.
        try (Socket socket = new Socket()) {
            socket.connect(address, 5000);
        }
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.connect(this.group, address));

        client.loginDone.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName());
        assertFalse(this.login.get().session().getPeer() instanceof NetherNetPeer);
    }

    @Test
    void stillLogsInOverRakNetWhenTheTcpPortIsTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            InetSocketAddress address = this.listen(taken.getLocalPort(), true);
            TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
            this.clients.add(client.connect(this.group, address));

            client.loginDone.get(10, TimeUnit.SECONDS);
            assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName());
        }
    }

    @Test
    void listensOnNoTcpPortWithNetherNetOff() throws Exception {
        InetSocketAddress address = this.listen(false);
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(address, 5000));
        }
    }

    @Test
    void turnsAwayAJoinWhoseOfferCarriesNoIdentity() throws Exception {
        InetSocketAddress address = this.listen(true);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address, null, TestClient.PLAIN_HTTP));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        // The signaling refuses the offer before any login.
        assertTrue(error.endsWith("HTTP 401"), error);
        assertFalse(this.login.isDone());
    }

    @Test
    void turnsAwayAJoinWithAnIdentityNotFromXboxWhenXboxIsRequired() throws Exception {
        InetSocketAddress address = this.listen(0, true, true);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        // The client signs its own identity token.
        this.clients.add(client.joinByAddress(this.group, address));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("HTTP 4"), error);
        assertFalse(this.login.isDone());
    }
}
