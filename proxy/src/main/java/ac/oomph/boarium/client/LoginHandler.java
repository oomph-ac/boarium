package ac.oomph.boarium.client;

import io.netty.channel.EventLoop;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Logs a client in the way gophertunnel's listener does, which Spectrum uses: network settings, login, encryption, then an empty resource pack exchange.
 * Login packets that arrive out of that order disconnect the client.
 */
final class LoginHandler implements BedrockPacketHandler {
    private static final int COMPRESSION_THRESHOLD = 256;
    // gophertunnel's listener always lists these vanilla packs in ResourcePackStack (exemptedPacks in its conn.go).
    private static final List<ResourcePackStackPacket.Entry> EXEMPTED_PACKS = List.of(
            new ResourcePackStackPacket.Entry("b41c2785-c512-4a49-af56-3a87afd47c57", "1.21.30", ""),
            new ResourcePackStackPacket.Entry("a4df0cb3-17be-4163-88d7-fcf7002b935d", "1.21.20", ""),
            new ResourcePackStackPacket.Entry("d19adffe-a2e1-4b02-8436-ca4583368c89", "1.21.10", ""),
            new ResourcePackStackPacket.Entry("85d5603d-2824-4b21-8044-34f441f4fce1", "1.21.0", ""),
            new ResourcePackStackPacket.Entry("e977cd13-0a11-4618-96fb-03dfe9c43608", "1.20.60", ""),
            new ResourcePackStackPacket.Entry("0674721c-a0aa-41a1-9ba8-1ed33ea3e7ed", "1.20.50", ""),
            new ResourcePackStackPacket.Entry("0fba4063-dba1-4281-9b89-ff9390653530", "1.0.0", ""));

    private enum Step { NETWORK_SETTINGS, LOGIN, VERIFYING, HANDSHAKE, RESOURCE_PACKS, DONE, FAILED }

    private static final Executor VERIFIER = Executors.newVirtualThreadPerTaskExecutor();

    private final BedrockServerSession session;
    private final BedrockCodec codec;
    private final boolean requireXbox;
    private final Consumer<ClientLogin> onLogin;

    private Step step = Step.NETWORK_SETTINGS;
    private Identity identity;
    private byte[] clientData;
    private boolean xboxAuthenticated;
    private boolean clientCacheSupported;
    private final ScheduledFuture<?> timeout;

    LoginHandler(BedrockServerSession session, BedrockCodec codec, boolean requireXbox, Duration timeout, Consumer<ClientLogin> onLogin) {
        this.session = session;
        this.codec = codec;
        this.requireXbox = requireXbox;
        this.onLogin = onLogin;
        if (timeout.toMillis() < 1) {
            throw new IllegalArgumentException("login timeout must be at least 1 ms, got " + timeout);
        }
        this.timeout = session.getPeer().getChannel().eventLoop().schedule(() -> {
            if (this.step != Step.FAILED && this.session.isConnected()) {
                this.fail("disconnectionScreen.timeout");
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        this.timeout.cancel(false);
    }

    // Disconnecting takes Cloudburst a few seconds, so the step makes the rest of this handler ignore the client until then.
    // A null reason sends a Disconnect the client does not show.
    private PacketSignal fail(String reason) {
        this.step = Step.FAILED;
        this.timeout.cancel(false);
        ClientDisconnect.disconnect(this.session, reason);
        return PacketSignal.HANDLED;
    }

    private PacketSignal outOfOrder() {
        return this.step == Step.FAILED ? PacketSignal.HANDLED : this.fail("disconnectionScreen.internalError.cantConnect");
    }

    // Called on the event loop when handling a packet from the client fails.
    void failOnError() {
        this.outOfOrder();
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        if (this.step != Step.NETWORK_SETTINGS) {
            return this.outOfOrder();
        }

        int protocol = packet.getProtocolVersion();
        if (protocol != this.codec.getProtocolVersion()) {
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(protocol > this.codec.getProtocolVersion() ? PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD : PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            this.session.setCodec(this.codec);
            this.session.sendPacketImmediately(status);
            // The PlayStatus already shows the client why, as gophertunnel does.
            return this.fail(null);
        }

        this.session.setCodec(this.codec);
        NetworkSettingsPacket settings = new NetworkSettingsPacket();
        settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        settings.setCompressionThreshold(COMPRESSION_THRESHOLD);
        this.session.sendPacketImmediately(settings);
        this.session.setCompression(PacketCompressionAlgorithm.ZLIB);
        this.step = Step.LOGIN;
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        if (this.step != Step.LOGIN) {
            return this.outOfOrder();
        }

        // The checks can fetch Mojang's keys over HTTPS, so they run off the event loop.
        // Login packets that arrive meanwhile, including any sent in the Login's own unencrypted batch, fail as out of order, so the handshake reply can only come after encryption is on.
        this.step = Step.VERIFYING;
        EventLoop loop = this.session.getPeer().getChannel().eventLoop();
        VERIFIER.execute(() -> {
            Verified verified;
            try {
                verified = this.verify(packet);
            } catch (Throwable t) {
                verified = null;
            }
            Verified result = verified;
            try {
                loop.execute(() -> this.finishLogin(result));
            } catch (RejectedExecutionException e) {
                // The event loop has shut down, and the connection with it.
            }
        });
        return PacketSignal.HANDLED;
    }

    private record Verified(Identity identity, byte[] clientData, boolean xboxSigned, PublicKey identityKey) {
    }

    private Verified verify(LoginPacket packet) throws Exception {
        assert !this.session.getPeer().getChannel().eventLoop().inEventLoop() : "login checks must not block the event loop";
        if (packet.getAuthPayload().getAuthType() == AuthType.GUEST) {
            throw new IllegalStateException("guest accounts cannot log in");
        }

        ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
        if (this.requireXbox && !result.signed()) {
            throw new IllegalStateException("not signed by Xbox Live");
        }
        long now = System.currentTimeMillis() / 1000;
        ChainValidationResult.IdentityData data = result.identityClaims().extraData;
        UUID uuid = data.identity;
        if (packet.getAuthPayload() instanceof CertificateChainPayload chain) {
            LoginChecks.checkChain(chain.getChain(), now);
        } else if (packet.getAuthPayload() instanceof TokenPayload token && token.getAuthType() == AuthType.SELF_SIGNED) {
            uuid = LoginChecks.checkSelfSignedToken(token.getToken(), now);
        }
        Identity identity = new Identity(Objects.requireNonNullElse(data.displayName, ""), uuid, Objects.requireNonNullElse(data.xuid, ""), Objects.requireNonNullElse(data.titleId, ""));
        LoginChecks.checkIdentity(identity, result.signed());

        PublicKey identityKey = result.identityClaims().parsedIdentityPublicKey();
        byte[] clientData = EncryptionUtils.verifyClientData(packet.getClientJwt(), identityKey);
        if (clientData == null) {
            throw new IllegalStateException("client data is not signed by the identity key");
        }
        return new Verified(identity, clientData, result.signed(), identityKey);
    }

    private void finishLogin(Verified verified) {
        assert this.session.getPeer().getChannel().eventLoop().inEventLoop() : "the session is only changed on its event loop";
        if (this.step != Step.VERIFYING || !this.session.isConnected()) {
            return;
        }
        if (verified == null) {
            this.fail("disconnectionScreen.notAuthenticated");
            return;
        }

        try {
            KeyPair serverKey = EncryptionUtils.createKeyPair();
            byte[] token = EncryptionUtils.generateRandomToken();
            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(EncryptionUtils.createHandshakeJwt(serverKey, token));
            this.session.sendPacketImmediately(handshake);
            this.session.enableEncryption(EncryptionUtils.getSecretKey(serverKey.getPrivate(), verified.identityKey(), token));
        } catch (Exception e) {
            this.fail("disconnectionScreen.internalError.cantConnect");
            return;
        }
        this.identity = verified.identity();
        this.clientData = verified.clientData();
        this.xboxAuthenticated = verified.xboxSigned();
        this.step = Step.HANDSHAKE;
    }

    @Override
    public PacketSignal handle(ClientToServerHandshakePacket packet) {
        if (this.step != Step.HANDSHAKE) {
            return this.outOfOrder();
        }

        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        this.session.sendPacket(status);

        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.setWorldTemplateId(new UUID(0, 0));
        info.setWorldTemplateVersion("");
        this.session.sendPacket(info);
        this.step = Step.RESOURCE_PACKS;
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheStatusPacket packet) {
        if (this.step == Step.FAILED) {
            return PacketSignal.HANDLED;
        }
        this.clientCacheSupported = packet.isSupported();
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        if (this.step != Step.RESOURCE_PACKS) {
            return this.outOfOrder();
        }

        switch (packet.getStatus()) {
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setGameVersion(this.codec.getMinecraftVersion());
                stack.getExperiments().add(new ExperimentData("cameras", true));
                stack.getResourcePacks().addAll(EXEMPTED_PACKS);
                this.session.sendPacket(stack);
            }
            case COMPLETED -> {
                this.step = Step.DONE;
                this.timeout.cancel(false);
                this.onLogin.accept(new ClientLogin(this.session, this.identity, this.clientData, this.xboxAuthenticated, this.clientCacheSupported));
            }
            default -> this.fail("disconnectionScreen.resourcePack");
        }
        return PacketSignal.HANDLED;
    }
}
