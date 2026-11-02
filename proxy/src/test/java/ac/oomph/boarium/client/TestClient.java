package ac.oomph.boarium.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Bedrock client for tests that logs in with an offline identity it signs itself, the way a client without Xbox Live does.
 * Every packet it receives is also put in {@link #received}.
 */
final class TestClient {
    final String name;
    final UUID identity = UUID.randomUUID();
    final KeyPair key = EncryptionUtils.createKeyPair();
    final BlockingQueue<BedrockPacket> received = new LinkedBlockingQueue<>();
    final CompletableFuture<BedrockClientSession> session = new CompletableFuture<>();
    final CompletableFuture<Void> loginDone = new CompletableFuture<>();

    // Knobs for tests of logins the proxy must turn away.
    KeyPair chainSigner = this.key;
    KeyPair clientDataSigner = this.key;
    long chainLifetimeSeconds = 3600;
    AuthType authType = AuthType.SELF_SIGNED;
    final Map<String, Object> chainClaims = new HashMap<>();
    final Map<String, Object> identityClaims = new HashMap<>();
    boolean loginInOneUnencryptedBatch;

    private final BedrockCodec codec;
    private final int protocolVersion;

    TestClient(String name, BedrockCodec codec, int protocolVersion) {
        this.name = name;
        this.codec = codec;
        this.protocolVersion = protocolVersion;
    }

    /**
     * Waits for the next received packet of {@code type}, dropping the packets before it.
     */
    <T extends BedrockPacket> T await(Class<T> type) throws InterruptedException {
        while (true) {
            BedrockPacket packet = this.received.poll(10, TimeUnit.SECONDS);
            if (packet == null) {
                throw new IllegalStateException("no " + type.getSimpleName() + " within 10 seconds");
            }
            if (type.isInstance(packet)) {
                return type.cast(packet);
            }
        }
    }

    void connect(EventLoopGroup group, InetSocketAddress address) {
        new Bootstrap()
                .group(group)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                // Bedrock clients use RakNet protocol 11.
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession session) {
                        session.setCodec(TestClient.this.codec);
                        session.setPacketHandler(new Handler(session));
                        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
                        request.setProtocolVersion(TestClient.this.protocolVersion);
                        session.sendPacketImmediately(request);
                        TestClient.this.session.complete(session);
                    }
                })
                .connect(address);
    }

    private String sign(Map<String, Object> claims, KeyPair signer) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader(HeaderParameterNames.X509_URL, Base64.getEncoder().encodeToString(this.key.getPublic().getEncoded()));
        jws.setPayload(JsonUtil.toJson(claims));
        jws.setKey(signer.getPrivate());
        return jws.getCompactSerialization();
    }

    private LoginPacket login() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> extraData = new HashMap<>(Map.of("displayName", this.name, "identity", this.identity.toString(), "XUID", ""));
        extraData.putAll(this.identityClaims);
        Map<String, Object> claims = new HashMap<>(Map.of(
                "extraData", extraData,
                "identityPublicKey", Base64.getEncoder().encodeToString(this.key.getPublic().getEncoded()),
                "nbf", now - this.chainLifetimeSeconds,
                "exp", now + this.chainLifetimeSeconds));
        claims.putAll(this.chainClaims);
        String chain = this.sign(claims, this.chainSigner);
        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(this.protocolVersion);
        login.setAuthPayload(new CertificateChainPayload(List.of(chain), this.authType));
        login.setClientJwt(this.sign(Map.of("ThirdPartyName", this.name, "DeviceOS", 7, "GameVersion", this.codec.getMinecraftVersion()), this.clientDataSigner));
        return login;
    }

    private final class Handler implements BedrockPacketHandler {
        private final BedrockClientSession session;

        Handler(BedrockClientSession session) {
            this.session = session;
        }

        @Override
        public PacketSignal handlePacket(BedrockPacket packet) {
            TestClient.this.received.add(packet);
            return BedrockPacketHandler.super.handlePacket(packet);
        }

        @Override
        public PacketSignal handle(NetworkSettingsPacket packet) {
            this.session.setCompression(packet.getCompressionAlgorithm());
            try {
                if (TestClient.this.loginInOneUnencryptedBatch) {
                    ResourcePackClientResponsePacket havePacks = new ResourcePackClientResponsePacket();
                    havePacks.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
                    ResourcePackClientResponsePacket completed = new ResourcePackClientResponsePacket();
                    completed.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
                    this.session.getPeer().sendPacketsImmediately(0, 0, TestClient.this.login(), new ClientToServerHandshakePacket(), havePacks, completed);
                } else {
                    this.session.sendPacketImmediately(TestClient.this.login());
                }
            } catch (Exception e) {
                TestClient.this.loginDone.completeExceptionally(e);
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ServerToClientHandshakePacket packet) {
            try {
                JsonWebSignature jws = new JsonWebSignature();
                jws.setCompactSerialization(packet.getJwt());
                byte[] salt = Base64.getDecoder().decode((String) JsonUtil.parseJson(jws.getUnverifiedPayload()).get("salt"));
                this.session.enableEncryption(EncryptionUtils.getSecretKey(TestClient.this.key.getPrivate(), EncryptionUtils.parseKey(jws.getHeader(HeaderParameterNames.X509_URL)), salt));
                this.session.sendPacketImmediately(new ClientToServerHandshakePacket());
            } catch (Exception e) {
                TestClient.this.loginDone.completeExceptionally(e);
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePacksInfoPacket packet) {
            ClientCacheStatusPacket cache = new ClientCacheStatusPacket();
            cache.setSupported(true);
            this.session.sendPacket(cache);
            ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
            response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
            this.session.sendPacket(response);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackStackPacket packet) {
            ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
            response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
            this.session.sendPacket(response);
            TestClient.this.loginDone.complete(null);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(DisconnectPacket packet) {
            TestClient.this.loginDone.completeExceptionally(new IllegalStateException("disconnected: " + packet.getKickMessage()));
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            TestClient.this.loginDone.completeExceptionally(new IllegalStateException("disconnected: " + reason));
        }
    }
}
