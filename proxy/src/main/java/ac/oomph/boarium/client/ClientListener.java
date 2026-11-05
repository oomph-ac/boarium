package ac.oomph.boarium.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Accepts Bedrock clients over RakNet and logs them in. Clients on a protocol that {@link ac.oomph.boarium.protocol.BedrockVersions} lacks are turned away.
 */
public final class ClientListener {
    private static final String VALIDATE_ENCRYPTION = "cloudburst.validateEncryption";

    private final Channel channel;

    private ClientListener(Channel channel) {
        this.channel = channel;
    }

    /**
     * Binds the listener and advertises {@code pong}, with its ports set to the port that was bound.
     * Fails if Mojang's login settings cannot be fetched, since no client could log in then.
     *
     * @param requireXbox  whether clients must have an identity signed by Xbox Live
     * @param loginTimeout how long a client may take to log in before it is disconnected
     * @param onLogin      runs on the client's event loop once a client has logged in
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockPong pong, boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) throws InterruptedException {
        if (loginTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("login timeout must be at least 1 ms, got " + loginTimeout);
        }
        // Turns on Cloudburst's check of the checksum at the end of each encrypted batch unless it is set already, as gophertunnel always checks it.
        // Cloudburst reads it once, when the first encrypted session starts in this JVM.
        if (System.getProperty(VALIDATE_ENCRYPTION) == null) {
            System.setProperty(VALIDATE_ENCRYPTION, "true");
        }
        // EncryptionUtils fetches Mojang's login settings over HTTPS when it is first loaded, so that fetch happens here. Mojang's signing keys are fetched later, during token logins.
        try {
            EncryptionUtils.getMojangPublicKey();
        } catch (Error e) {
            throw new IllegalStateException("could not load Mojang's login settings", e);
        }

        ChannelFuture bind = new ServerBootstrap()
                .group(group)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .childHandler(new ClientInitializer(requireXbox, loginTimeout, onLogin))
                .bind(address)
                .sync();

        ClientListener listener = new ClientListener(bind.channel());
        listener.pong(pong);
        return listener;
    }

    /**
     * Sets what the listener answers server list pings with, with the ports set to the one it listens on.
     */
    public void pong(BedrockPong pong) {
        int port = this.address().getPort();
        ByteBuf advertisement = pong.ipv4Port(port).ipv6Port(port).toByteBuf();
        try {
            // RakNet keeps a copy.
            this.channel.config().setOption(RakChannelOption.RAK_ADVERTISEMENT, advertisement);
        } finally {
            advertisement.release();
        }
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) this.channel.localAddress();
    }

    public ChannelFuture close() {
        return this.channel.close();
    }
}
