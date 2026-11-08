package ac.oomph.boarium.client;

import ac.oomph.boarium.NetherNetHttps;
import ac.oomph.boarium.TestCertificate;
import ac.oomph.boarium.transport.Addresses;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.BindException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The listener's NetherNet side: joins by address over HTTP or HTTPS signaling on loopback, alone or next to RakNet.
@Timeout(60)
class ClientListenerNetherNetTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    // The QA network and live tests hold these, so a test must never bind them.
    private static final Set<Integer> TAKEN_PORTS = Set.of(19132, 19133, 19142, 19143);

    private EventLoopGroup group;
    private ClientListener listener;
    private final BlockingQueue<ClientLogin> logins = new LinkedBlockingQueue<>();
    private final List<Channel> clients = new ArrayList<>();
    private final Set<Integer> picked = new HashSet<>();
    @TempDir
    Path folder;
    private Path identity;

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(2);
        this.identity = this.folder.resolve("nethernet").resolve("identity.pem");
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

    // A loopback port free on TCP and UDP, and not one this test picked before.
    private int freePort() throws Exception {
        Set<Integer> skip = new HashSet<>(TAKEN_PORTS);
        skip.addAll(this.picked);
        int port = Addresses.freePort(LOOPBACK, skip);
        this.picked.add(port);
        return port;
    }

    // NetherNet alone, with its connections on the signaling's port.
    private InetSocketAddress listen() throws Exception {
        return this.listen(false, null);
    }

    private InetSocketAddress listen(boolean requireXbox, SslContext tls) throws Exception {
        int port = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, tls, this.identity), requireXbox);
        return this.listener.netherNetAddress();
    }

    private void listen(InetSocketAddress rakNet, ClientListener.NetherNetSettings netherNet, boolean requireXbox) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival").playerCount(3).maximumPlayerCount(10);
        this.listener = ClientListener.bind(this.group, rakNet, netherNet, pong, requireXbox, Duration.ofMinutes(1), this.logins::add);
    }

    private ClientLogin login() throws InterruptedException {
        ClientLogin login = this.logins.poll(10, TimeUnit.SECONDS);
        assertNotNull(login, "no login");
        return login;
    }

    // The UDP port a NetherNet join's connection runs over, as the client and boarium each see it.
    private static void assertMediaPort(int port, Channel client, ClientLogin login) {
        assertEquals(port, ((NetherNetChannel) client).selectedPath().remote().getPort());
        assertEquals(port, ((NetherNetChannel) login.session().getPeer().getChannel()).selectedPath().local().getPort());
    }

    @Test
    void logsInAClientThatJoinsByAddressOverHttp() throws Exception {
        InetSocketAddress address = this.listen();
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        Channel channel = client.joinByAddress(this.group, address);
        this.clients.add(channel);

        client.loginDone.get(30, TimeUnit.SECONDS);
        ClientLogin login = this.login();
        assertEquals("Tester", login.identity().displayName());
        assertTrue(client.received.stream().anyMatch(p -> p instanceof PlayStatusPacket status && status.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS));
        assertTrue(login.session().getPeer() instanceof NetherNetPeer);
        assertMediaPort(address.getPort(), channel, login);
    }

    @Test
    void logsInAClientThatJoinsByAddressOverHttps(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        InetSocketAddress address = this.listen(false, new NetherNetHttps(certificate.certificate(), certificate.privateKey(), null).load());
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        HttpSignalingSettings https = HttpSignalingSettings.DEFAULT.withScheme(HttpSignalingSettings.Scheme.HTTPS).withSslContext(certificate.trusted());
        this.clients.add(client.joinByAddress(this.group, address, https));

        client.loginDone.get(30, TimeUnit.SECONDS);
        ClientLogin login = this.login();
        assertEquals("Tester", login.identity().displayName());
        assertTrue(login.session().getPeer() instanceof NetherNetPeer);
    }

    @Test
    void turnsAwayPlainHttpWhenServingHttps(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        InetSocketAddress address = this.listen(false, new NetherNetHttps(certificate.pkcs12(), null, certificate.password()).load());
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("HTTP 426"), error);
        assertTrue(this.logins.isEmpty());
    }

    @Test
    void answersTheStatusRequestWithThePong() throws Exception {
        InetSocketAddress address = this.listen();
        NetherNetHTTPClientSignaling.Probe probe = NetherNetHTTPClientSignaling.probe(address, HttpSignalingSettings.DEFAULT.withScheme(HttpSignalingSettings.Scheme.HTTP)).get(10, TimeUnit.SECONDS);
        assertEquals("boarium", probe.motd().serverName());
        assertEquals(3, probe.motd().playerCount());
        assertEquals(10, probe.motd().maxPlayerCount());
    }

    @Test
    void answersNoRakNetPingWithRakNetOff() throws Exception {
        InetSocketAddress address = this.listen();
        assertNull(this.listener.rakNetAddress());
        // A RakNet unconnected ping: ID, time, the offline message magic, then the sender's GUID.
        byte[] magic = HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678");
        ByteBuffer ping = ByteBuffer.allocate(33).put((byte) 0x01).putLong(1234).put(magic).putLong(42);
        try (DatagramSocket socket = new DatagramSocket(0, LOOPBACK)) {
            socket.setSoTimeout(2000);
            socket.send(new DatagramPacket(ping.array(), ping.capacity(), address));
            assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[1500], 1500)));
        }
    }

    // RakNet on the listen port, and NetherNet's signaling on the TCP side of it with its connections on a UDP port of their own.
    @Test
    void logsInOverRakNetAndNetherNetOnTheirOwnUdpPorts() throws Exception {
        int port = this.freePort();
        int netherNetPort = this.freePort();
        this.listen(new InetSocketAddress(LOOPBACK, port), new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), netherNetPort, null, this.identity), false);
        assertEquals(port, this.listener.rakNetAddress().getPort());
        assertEquals(port, this.listener.netherNetAddress().getPort());

        TestClient rakNetClient = new TestClient("RakNet", CODEC, CODEC.getProtocolVersion());
        this.clients.add(rakNetClient.connect(this.group, this.listener.rakNetAddress()));
        rakNetClient.loginDone.get(10, TimeUnit.SECONDS);
        ClientLogin rakNetLogin = this.login();
        assertEquals("RakNet", rakNetLogin.identity().displayName());
        assertFalse(rakNetLogin.session().getPeer() instanceof NetherNetPeer);

        TestClient netherNetClient = new TestClient("NetherNet", CODEC, CODEC.getProtocolVersion());
        Channel channel = netherNetClient.joinByAddress(this.group, this.listener.netherNetAddress());
        this.clients.add(channel);
        netherNetClient.loginDone.get(30, TimeUnit.SECONDS);
        ClientLogin netherNetLogin = this.login();
        assertEquals("NetherNet", netherNetLogin.identity().displayName());
        assertMediaPort(netherNetPort, channel, netherNetLogin);
    }

    @Test
    void takesSignalingOnItsOwnTcpPort() throws Exception {
        int signalingPort = this.freePort();
        int netherNetPort = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, signalingPort), netherNetPort, null, this.identity), false);
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(LOOPBACK, netherNetPort), 5000));
        }

        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        Channel channel = client.joinByAddress(this.group, new InetSocketAddress(LOOPBACK, signalingPort));
        this.clients.add(channel);
        client.loginDone.get(30, TimeUnit.SECONDS);
        assertMediaPort(netherNetPort, channel, this.login());
    }

    // The ICE agent alone opens the UDP port only while a player is connected, so another program could take it between players.
    @Test
    void holdsTheUdpPortBetweenPlayers() throws Exception {
        int signalingPort = this.freePort();
        int netherNetPort = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, signalingPort), netherNetPort, null, this.identity), false);
        assertUdpPortHeld(new InetSocketAddress(LOOPBACK, netherNetPort));

        for (String name : new String[]{"First", "Second"}) {
            TestClient client = new TestClient(name, CODEC, CODEC.getProtocolVersion());
            Channel channel = client.joinByAddress(this.group, new InetSocketAddress(LOOPBACK, signalingPort));
            client.loginDone.get(30, TimeUnit.SECONDS);
            ClientLogin login = this.login();
            assertMediaPort(netherNetPort, channel, login);
            channel.close().syncUninterruptibly();
            login.session().getPeer().getChannel().closeFuture().await(10, TimeUnit.SECONDS);
        }
        assertUdpPortHeld(new InetSocketAddress(LOOPBACK, netherNetPort));

        this.listener.close().syncUninterruptibly();
        this.listener = null;
        new DatagramSocket(new InetSocketAddress(LOOPBACK, netherNetPort)).close();
    }

    // A listen address with no host: every address of this machine.
    @Test
    void logsInOnEveryAddress() throws Exception {
        int port = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(port), port, null, this.identity), false);
        assertUdpPortHeld(new InetSocketAddress(port));
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        Channel channel = client.joinByAddress(this.group, new InetSocketAddress(LOOPBACK, port));
        this.clients.add(channel);
        client.loginDone.get(30, TimeUnit.SECONDS);
        assertMediaPort(port, channel, this.login());
    }

    private static void assertUdpPortHeld(InetSocketAddress address) {
        assertThrows(BindException.class, () -> new DatagramSocket(address).close());
    }

    @Test
    void stillLogsInOverRakNetWhenTheTcpPortIsTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, LOOPBACK)) {
            InetSocketAddress address = new InetSocketAddress(LOOPBACK, taken.getLocalPort());
            this.listen(address, new ClientListener.NetherNetSettings(address, this.freePort(), null, this.identity), false);
            assertNull(this.listener.netherNetAddress());
            TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
            this.clients.add(client.connect(this.group, address));

            client.loginDone.get(10, TimeUnit.SECONDS);
            assertEquals("Tester", this.login().identity().displayName());
        }
    }

    @Test
    void failsWhenNetherNetCannotStartAndRakNetIsOff() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, LOOPBACK)) {
            int port = taken.getLocalPort();
            String error = assertThrows(IllegalStateException.class, () -> this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity), false)).getMessage();
            assertTrue(error.startsWith("NetherNet could not start with signaling on TCP 127.0.0.1:" + port + " and connections on UDP 127.0.0.1:" + port + ": "), error);
            assertTrue(error.endsWith("; RakNet is off, so no player could join"), error);
        }
        int signalingPort = this.freePort();
        try (DatagramSocket taken = new DatagramSocket(0, LOOPBACK)) {
            int port = taken.getLocalPort();
            String error = assertThrows(IllegalStateException.class, () -> this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, signalingPort), port, null, this.identity), false)).getMessage();
            assertTrue(error.contains("cannot bind UDP 127.0.0.1:" + port + ", which another program may hold"), error);
        }
        // Nothing is left listening.
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(LOOPBACK, signalingPort), 5000));
        }
    }

    // For a server behind NAT, the answer tells a joining player to reach the UDP port at the configured addresses, on NetherNet's port where an address names none.
    // Loopback addresses, so that the client's checks of them never leave this machine.
    @Test
    void advertisesTheConfiguredAddresses() throws Exception {
        int port = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity, List.of("127.0.0.2", "127.0.0.3:40000"), List.of()), false);
        AnswerKeeper signaling = new AnswerKeeper();
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, this.listener.netherNetAddress(), signaling));

        String answer = signaling.answer.get(30, TimeUnit.SECONDS);
        assertTrue(answer.contains(" 127.0.0.2 " + port + " typ srflx "), answer);
        assertTrue(answer.contains(" 127.0.0.3 40000 typ srflx "), answer);
        client.loginDone.get(30, TimeUnit.SECONDS);
        assertEquals("Tester", this.login().identity().displayName());
    }

    // Plain HTTP signaling that keeps the server's SDP answer.
    private static final class AnswerKeeper extends NetherNetHTTPClientSignaling {
        final CompletableFuture<String> answer = new CompletableFuture<>();

        AnswerKeeper() {
            super(TestClient.PLAIN_HTTP);
        }

        @Override
        public void setSignalHandler(String connectionId, NetherNetSignaling.SignalHandler handler) {
            super.setSignalHandler(connectionId, signal -> {
                // CONNECTRESPONSE, the connection ID, then the answer.
                this.answer.complete(signal.split(" ", 3)[2]);
                handler.onSignal(signal);
            });
        }
    }

    // .invalid never resolves (RFC 6761), and resolvers may refuse it without asking a DNS server.
    @Test
    void failsWhenAnAdvertisedNameDoesNotResolveAndRakNetIsOff() throws Exception {
        int port = this.freePort();
        ClientListener.NetherNetSettings settings = new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity, List.of("x.invalid"), List.of());
        String error = assertThrows(IllegalStateException.class, () -> this.listen(null, settings, false)).getMessage();
        assertEquals("NetherNet could not start: nethernet.advertise_addresses: cannot resolve x.invalid; RakNet is off, so no player could join", error);
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(LOOPBACK, port), 5000));
        }
    }

    @Test
    void stillLogsInOverRakNetWhenAnAdvertisedNameDoesNotResolve() throws Exception {
        InetSocketAddress address = new InetSocketAddress(LOOPBACK, this.freePort());
        this.listen(address, new ClientListener.NetherNetSettings(address, this.freePort(), null, this.identity, List.of("x.invalid"), List.of()), false);
        assertNull(this.listener.netherNetAddress());
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.connect(this.group, address));
        client.loginDone.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", this.login().identity().displayName());
    }

    // A STUN server on a local port where nothing answers, in place of the public ones.
    @Test
    void logsInWhenNoStunServerAnswers() throws Exception {
        int port = this.freePort();
        int stunPort = this.freePort();
        this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity, List.of(), List.of("stun:127.0.0.1:" + stunPort)), false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        Channel channel = client.joinByAddress(this.group, this.listener.netherNetAddress());
        this.clients.add(channel);
        client.loginDone.get(30, TimeUnit.SECONDS);
        assertMediaPort(port, channel, this.login());
    }

    // A failure that is not STUN's stops NetherNet with STUN on, as with it off.
    @Test
    void failsWithStunOnWhenTheTcpPortIsTaken() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, LOOPBACK)) {
            int port = taken.getLocalPort();
            ClientListener.NetherNetSettings settings = new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity, List.of(), List.of("stun:127.0.0.1:" + this.freePort()));
            String error = assertThrows(IllegalStateException.class, () -> this.listen(null, settings, false)).getMessage();
            assertTrue(error.startsWith("NetherNet could not start with signaling on TCP 127.0.0.1:" + port + " and connections on UDP 127.0.0.1:" + port + ": "), error);
            assertTrue(error.endsWith("; RakNet is off, so no player could join"), error);
        }
    }

    // The library's failure when its STUN monitors cannot open, the only one after which boarium binds again without STUN.
    @Test
    void tellsTheStunStartFailureFromOthers() {
        assertTrue(ClientListener.isStunStartFailure(new ConnectException("Could not start STUN discovery on udp/19500: failed")));
        assertFalse(ClientListener.isStunStartFailure(new ConnectException("Failed to bind HTTP signaling to /127.0.0.1:19500: Address already in use")));
        assertFalse(ClientListener.isStunStartFailure(new BindException("Could not start STUN discovery")));
        assertFalse(ClientListener.isStunStartFailure(null));
    }

    @Test
    void listensOnNoTcpPortWithNetherNetOff() throws Exception {
        this.listen(new InetSocketAddress(LOOPBACK, 0), null, false);
        assertNull(this.listener.netherNetAddress());
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(this.listener.rakNetAddress(), 5000));
        }
    }

    // The game pins the key the signaling signs its answers with, so a player who trusted the server is not asked again after a restart.
    @Test
    void keepsItsIdentityAcrossStarts() throws Exception {
        InetSocketAddress address = this.listen();
        String pem = Files.readString(this.identity);
        if (Files.getFileStore(this.folder).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(this.identity));
        }
        PublicKey key = OperatorIdentity.fromPem(this.identity.toFile(), "test").publicKey();
        this.joinPinnedTo(address, key);

        // A client that pinned another key is turned away.
        TestClient stranger = new TestClient("Stranger", CODEC, CODEC.getProtocolVersion());
        this.clients.add(stranger.joinByAddress(this.group, address, TokenTrust.pinnedTo(OperatorIdentity.generate("other").publicKey())));
        String error = assertThrows(ExecutionException.class, () -> stranger.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("The token was not signed by a pinned key"), error);

        this.listener.close().syncUninterruptibly();
        this.joinPinnedTo(this.listen(), key);
        assertEquals(pem, Files.readString(this.identity));
    }

    private void joinPinnedTo(InetSocketAddress address, PublicKey key) throws Exception {
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address, TokenTrust.pinnedTo(key)));
        client.loginDone.get(30, TimeUnit.SECONDS);
        assertEquals("Tester", this.login().identity().displayName());
    }

    @Test
    void failsAndKeepsAnIdentityFileItCannotRead() throws Exception {
        Files.createDirectories(this.identity.getParent());
        Files.writeString(this.identity, "not a key");
        int port = this.freePort();
        String error = assertThrows(IllegalStateException.class, () -> this.listen(null, new ClientListener.NetherNetSettings(new InetSocketAddress(LOOPBACK, port), port, null, this.identity), false)).getMessage();
        assertTrue(error.startsWith("NetherNet could not start: cannot read its identity file " + this.identity + " as a key: "), error);
        assertTrue(error.endsWith("; RakNet is off, so no player could join. Only delete that file if it cannot be recovered, as a new identity makes every player confirm the server again"), error);
        assertEquals("not a key", Files.readString(this.identity));
        try (Socket socket = new Socket()) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(LOOPBACK, port), 5000));
        }
    }

    // A file boarium's user may not read, such as one copied in as root.
    @Test
    void logsInOverRakNetAndKeepsAnIdentityFileItMayNotRead() throws Exception {
        Files.createDirectories(this.identity.getParent());
        Files.writeString(this.identity, "a key");
        assumeTrue(Files.getFileStore(this.folder).supportsFileAttributeView(PosixFileAttributeView.class));
        Files.setPosixFilePermissions(this.identity, Set.of());
        assumeFalse(Files.isReadable(this.identity), "the tests run as a user who may read any file");
        InetSocketAddress address = new InetSocketAddress(LOOPBACK, this.freePort());
        this.listen(address, new ClientListener.NetherNetSettings(address, this.freePort(), null, this.identity), false);
        assertNull(this.listener.netherNetAddress());

        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.connect(this.group, this.listener.rakNetAddress()));
        client.loginDone.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", this.login().identity().displayName());
        assertEquals(Set.of(), Files.getPosixFilePermissions(this.identity));
        Files.setPosixFilePermissions(this.identity, PosixFilePermissions.fromString("rw-------"));
        assertEquals("a key", Files.readString(this.identity));
    }

    @Test
    void turnsAwayAJoinWhoseOfferCarriesNoIdentity() throws Exception {
        InetSocketAddress address = this.listen();
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.clients.add(client.joinByAddress(this.group, address, null, TestClient.PLAIN_HTTP));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        // The signaling refuses the offer before any login.
        assertTrue(error.endsWith("HTTP 401"), error);
        assertTrue(this.logins.isEmpty());
    }

    @Test
    void turnsAwayAJoinWithAnIdentityNotFromXboxWhenXboxIsRequired() throws Exception {
        InetSocketAddress address = this.listen(true, null);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        // The client signs its own identity token.
        this.clients.add(client.joinByAddress(this.group, address));

        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(30, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("HTTP 4"), error);
        assertTrue(this.logins.isEmpty());
    }
}
