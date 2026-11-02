package ac.oomph.boarium.client;

import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.ExperimentData;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
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
import java.util.List;
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

    private enum Step { NETWORK_SETTINGS, LOGIN, ENCRYPTING, HANDSHAKE, RESOURCE_PACKS, DONE }

    private final BedrockServerSession session;
    private final BedrockCodec codec;
    private final boolean requireXbox;
    private final Consumer<ClientLogin> onLogin;

    private Step step = Step.NETWORK_SETTINGS;
    private ChainValidationResult.IdentityData identity;
    private byte[] clientData;
    private boolean xboxAuthenticated;
    private boolean clientCacheSupported;

    LoginHandler(BedrockServerSession session, BedrockCodec codec, boolean requireXbox, Consumer<ClientLogin> onLogin) {
        this.session = session;
        this.codec = codec;
        this.requireXbox = requireXbox;
        this.onLogin = onLogin;
    }

    private PacketSignal outOfOrder() {
        this.session.disconnect("disconnectionScreen.internalError.cantConnect");
        return PacketSignal.HANDLED;
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
            this.session.disconnect();
            return PacketSignal.HANDLED;
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

        try {
            if (packet.getAuthPayload().getAuthType() == AuthType.GUEST) {
                this.session.disconnect("disconnectionScreen.notAuthenticated");
                return PacketSignal.HANDLED;
            }

            ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
            if (this.requireXbox && !result.signed()) {
                this.session.disconnect("disconnectionScreen.notAuthenticated");
                return PacketSignal.HANDLED;
            }
            if (packet.getAuthPayload() instanceof CertificateChainPayload chain) {
                LoginChecks.checkChain(chain.getChain(), System.currentTimeMillis() / 1000);
            }
            LoginChecks.checkIdentity(result.identityClaims().extraData, result.signed());

            PublicKey identityKey = result.identityClaims().parsedIdentityPublicKey();
            byte[] clientData = EncryptionUtils.verifyClientData(packet.getClientJwt(), identityKey);
            if (clientData == null) {
                this.session.disconnect("disconnectionScreen.notAuthenticated");
                return PacketSignal.HANDLED;
            }

            this.identity = result.identityClaims().extraData;
            this.clientData = clientData;
            this.xboxAuthenticated = result.signed();

            KeyPair serverKey = EncryptionUtils.createKeyPair();
            byte[] token = EncryptionUtils.generateRandomToken();
            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(EncryptionUtils.createHandshakeJwt(serverKey, token));
            this.session.sendPacketImmediately(handshake);
            this.session.enableEncryption(EncryptionUtils.getSecretKey(serverKey.getPrivate(), identityKey, token));

            // Packets after the Login in its own batch were not encrypted, and a client without the identity key could send the rest of the login there.
            // So the handshake reply only counts once this read is done; packets still in this batch fail as out of order.
            this.step = Step.ENCRYPTING;
            this.session.getPeer().getChannel().eventLoop().execute(() -> {
                if (this.step == Step.ENCRYPTING) {
                    this.step = Step.HANDSHAKE;
                }
            });
        } catch (Exception e) {
            this.session.disconnect("disconnectionScreen.notAuthenticated");
        }
        return PacketSignal.HANDLED;
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
                this.onLogin.accept(new ClientLogin(this.session, this.identity, this.clientData, this.xboxAuthenticated, this.clientCacheSupported));
            }
            default -> this.session.disconnect("disconnectionScreen.resourcePack");
        }
        return PacketSignal.HANDLED;
    }
}
