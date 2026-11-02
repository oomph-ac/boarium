package ac.oomph.boarium.client;

import io.netty.channel.EventLoopGroup;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientListenerTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;

    private EventLoopGroup group;
    private ClientListener listener;
    private final CompletableFuture<ClientLogin> login = new CompletableFuture<>();

    private void listen(boolean requireXbox) throws Exception {
        this.listen(requireXbox, Duration.ofMinutes(1));
    }

    private void listen(boolean requireXbox, Duration loginTimeout) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival").playerCount(0).maximumPlayerCount(10);
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), CODEC, pong, requireXbox, loginTimeout, this.login::complete);
    }

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(2);
    }

    @AfterEach
    void tearDown() {
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void logsInAnOfflineClient() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.connect(this.group, this.listener.address());

        ClientLogin login = this.login.get(10, TimeUnit.SECONDS);
        assertEquals("Tester", login.identity().displayName);
        assertEquals(client.identity, login.identity().identity);
        assertFalse(login.xboxAuthenticated());
        assertTrue(login.clientCacheSupported());
        assertTrue(new String(login.clientData(), StandardCharsets.UTF_8).contains("\"ThirdPartyName\":\"Tester\""));

        client.loginDone.get(10, TimeUnit.SECONDS);
        assertEquals(PlayStatusPacket.Status.LOGIN_SUCCESS, assertInstanceOf(PlayStatusPacket.class, client.received.stream().filter(p -> p instanceof PlayStatusPacket).findFirst().orElseThrow()).getStatus());
        ResourcePackStackPacket stack = assertInstanceOf(ResourcePackStackPacket.class, client.received.stream().filter(p -> p instanceof ResourcePackStackPacket).findFirst().orElseThrow());
        assertEquals(CODEC.getMinecraftVersion(), stack.getGameVersion());
    }

    @Test
    void turnsOnCloudburstsEncryptionChecksum() throws Exception {
        this.listen(false);

        assertEquals("true", System.getProperty("cloudburst.validateEncryption"));
    }

    @Test
    void disconnectsAClientThatTakesTooLong() throws Exception {
        this.listen(false, Duration.ofMillis(300));
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        // The client never sends its first packet, so the login cannot finish.
        client.sendNetworkSettingsRequest = false;
        client.connect(this.group, this.listener.address());

        assertEquals("disconnectionScreen.timeout", client.await(DisconnectPacket.class).getKickMessage());
    }

    @Test
    void leavesAFinishedLoginConnectedPastTheTimeout() throws Exception {
        this.listen(false, Duration.ofMillis(500));
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.connect(this.group, this.listener.address());
        this.login.get(10, TimeUnit.SECONDS);

        Thread.sleep(1000);
        assertTrue(client.session.get().isConnected());
        assertTrue(client.received.stream().noneMatch(DisconnectPacket.class::isInstance));
    }

    @Test
    void ignoresASecondPlayerOnTheSameConnection() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.sendSubClientPacket = true;
        client.connect(this.group, this.listener.address());

        assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName);
        client.loginDone.get(10, TimeUnit.SECONDS);
        assertTrue(client.received.stream().noneMatch(DisconnectPacket.class::isInstance));
    }

    @Test
    void rejectsALoginTimeoutUnderOneMillisecond() {
        assertThrows(IllegalArgumentException.class, () -> this.listen(false, Duration.ZERO));
    }

    @Test
    void disconnectsAClientThatSendsAPacketThatCannotBeRead() throws Exception {
        this.listen(false, Duration.ofMillis(300));
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.sendBrokenLogin = true;
        client.connect(this.group, this.listener.address());

        assertEquals("disconnectionScreen.internalError.cantConnect", client.await(DisconnectPacket.class).getKickMessage());
        // Wait past the login timeout, which must not send a second Disconnect.
        Thread.sleep(1000);
        assertTrue(client.received.stream().noneMatch(p -> p instanceof DisconnectPacket));
        assertFalse(this.login.isDone());
    }

    @Test
    void answersPings() throws Exception {
        this.listen(false);

        // A RakNet unconnected ping: ID, time, the offline message magic, then the sender's GUID.
        byte[] magic = HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678");
        ByteBuffer ping = ByteBuffer.allocate(33).put((byte) 0x01).putLong(1234).put(magic).putLong(42);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            socket.send(new DatagramPacket(ping.array(), ping.capacity(), this.listener.address()));
            DatagramPacket pong = new DatagramPacket(new byte[1500], 1500);
            socket.receive(pong);

            ByteBuffer in = ByteBuffer.wrap(pong.getData(), 0, pong.getLength());
            assertEquals(0x1c, in.get());
            assertEquals(1234, in.getLong());
            in.getLong();
            in.position(in.position() + magic.length);
            byte[] advertisement = new byte[in.getShort()];
            in.get(advertisement);
            String[] fields = new String(advertisement, StandardCharsets.UTF_8).split(";");
            assertEquals("boarium", fields[1]);
            assertEquals(String.valueOf(CODEC.getProtocolVersion()), fields[2]);
            assertEquals(String.valueOf(this.listener.address().getPort()), fields[10]);
        }
    }

    @Test
    void sendsGophertunnelsNetworkSettingsAndPackStack() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.connect(this.group, this.listener.address());
        client.loginDone.get(10, TimeUnit.SECONDS);

        NetworkSettingsPacket settings = this.first(client, NetworkSettingsPacket.class);
        assertEquals(PacketCompressionAlgorithm.ZLIB, settings.getCompressionAlgorithm());
        assertEquals(256, settings.getCompressionThreshold());
        ResourcePackStackPacket stack = this.first(client, ResourcePackStackPacket.class);
        assertEquals("cameras", stack.getExperiments().getFirst().getName());
        assertEquals(7, stack.getResourcePacks().size());
        assertEquals("0fba4063-dba1-4281-9b89-ff9390653530", stack.getResourcePacks().getLast().getPackId());
    }

    private <T> T first(TestClient client, Class<T> type) {
        return type.cast(client.received.stream().filter(type::isInstance).findFirst().orElseThrow());
    }

    private void assertTurnedAway(TestClient client) throws Exception {
        client.connect(this.group, this.listener.address());
        assertThrows(ExecutionException.class, () -> client.loginDone.get(10, TimeUnit.SECONDS));
        assertFalse(this.login.isDone());
    }

    @Test
    void turnsAwayTheRestOfTheLoginSentWithoutEncryption() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.loginInOneUnencryptedBatch = true;

        this.assertTurnedAway(client);
        client.await(DisconnectPacket.class);
        // Give a handshake time to arrive if the failed login still sent one.
        Thread.sleep(500);
        assertTrue(client.received.stream().noneMatch(p -> p instanceof DisconnectPacket || p instanceof ServerToClientHandshakePacket));
    }

    @Test
    void turnsAwayBadLogins() throws Exception {
        this.listen(false);

        TestClient badChainSignature = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        badChainSignature.chainSigner = EncryptionUtils.createKeyPair();
        this.assertTurnedAway(badChainSignature);

        TestClient badClientData = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        badClientData.clientDataSigner = EncryptionUtils.createKeyPair();
        this.assertTurnedAway(badClientData);

        TestClient expired = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        expired.chainLifetimeSeconds = -3600;
        this.assertTurnedAway(expired);

        TestClient guest = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        guest.authType = AuthType.GUEST;
        this.assertTurnedAway(guest);

        TestClient notYetValid = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        notYetValid.chainClaims.put("nbf", System.currentTimeMillis() / 1000 + 3600);
        this.assertTurnedAway(notYetValid);

        TestClient issuedLater = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        issuedLater.chainClaims.put("iat", System.currentTimeMillis() / 1000 + 3600);
        this.assertTurnedAway(issuedLater);

        TestClient expiryNotANumber = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        expiryNotANumber.chainClaims.put("exp", "never");
        this.assertTurnedAway(expiryNotANumber);

        TestClient offlineWithXuid = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        offlineWithXuid.identityClaims.put("XUID", "2535400000000000");
        this.assertTurnedAway(offlineWithXuid);

        TestClient nilIdentity = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        nilIdentity.identityClaims.put("identity", "00000000-0000-0000-0000-000000000000");
        this.assertTurnedAway(nilIdentity);

        TestClient shortIdentity = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        shortIdentity.identityClaims.put("identity", "1-2-3-4-5");
        this.assertTurnedAway(shortIdentity);

        this.assertTurnedAway(new TestClient("!!!", CODEC, CODEC.getProtocolVersion()));
        // 9 characters but 18 bytes, over the 16-byte limit.
        this.assertTurnedAway(new TestClient("Ééééééééé", CODEC, CODEC.getProtocolVersion()));
        this.assertTurnedAway(new TestClient("9Tester", CODEC, CODEC.getProtocolVersion()));
        this.assertTurnedAway(new TestClient("Tester  Two", CODEC, CODEC.getProtocolVersion()));
        this.assertTurnedAway(new TestClient("TesterWithALongName", CODEC, CODEC.getProtocolVersion()));
    }

    @Test
    void acceptsAChainThatExpiredWithinTheLeeway() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.chainClaims.put("exp", System.currentTimeMillis() / 1000 - 30);
        client.connect(this.group, this.listener.address());

        assertEquals("Tester", this.login.get(10, TimeUnit.SECONDS).identity().displayName);
    }

    @Test
    void turnsAwayAnOfflineClientWhenXboxIsRequired() throws Exception {
        this.listen(true);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.connect(this.group, this.listener.address());

        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> client.loginDone.get(10, TimeUnit.SECONDS)).getCause());
        assertFalse(this.login.isDone());
    }

    @Test
    void tellsANewerClientThatTheServerIsOld() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion() + 1);
        client.connect(this.group, this.listener.address());

        assertThrows(ExecutionException.class, () -> client.loginDone.get(10, TimeUnit.SECONDS));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD, assertInstanceOf(PlayStatusPacket.class, client.received.poll(10, TimeUnit.SECONDS)).getStatus());
    }

    @Test
    void turnsAwayAnotherProtocol() throws Exception {
        this.listen(false);
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion() - 1);
        client.connect(this.group, this.listener.address());

        assertThrows(ExecutionException.class, () -> client.loginDone.get(10, TimeUnit.SECONDS));
        PlayStatusPacket status = assertInstanceOf(PlayStatusPacket.class, client.received.poll(10, TimeUnit.SECONDS));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, status.getStatus());
        // A shown Disconnect message could replace the screen the PlayStatus opens.
        assertTrue(client.await(DisconnectPacket.class).isMessageSkipped());
        assertFalse(this.login.isDone());
    }
}
