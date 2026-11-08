package ac.oomph.boarium.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.PromiseCombiner;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Accepts Bedrock clients over RakNet, and over NetherNet when asked, and logs them in. Clients on a protocol that {@link ac.oomph.boarium.protocol.BedrockVersions} lacks are turned away.
 */
public final class ClientListener {
    private static final String VALIDATE_ENCRYPTION = "cloudburst.validateEncryption";
    private static final Logger LOG = LoggerFactory.getLogger(ClientListener.class);

    private final Channel channel;
    // Null when NetherNet is off or could not start.
    private final NetherNet netherNet;
    private final boolean requireXbox;

    private record NetherNet(Channel channel, NetherNetHTTPServerSignaling signaling) {
    }

    private ClientListener(Channel channel, NetherNet netherNet, boolean requireXbox) {
        this.channel = channel;
        this.netherNet = netherNet;
        this.requireXbox = requireXbox;
    }

    /**
     * Binds the listener for RakNet only.
     *
     * @see #bind(EventLoopGroup, InetSocketAddress, BedrockPong, boolean, boolean, Duration, Consumer)
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockPong pong, boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) throws InterruptedException {
        return bind(group, address, pong, requireXbox, false, loginTimeout, onLogin);
    }

    /**
     * Binds the listener and advertises {@code pong}, with its ports set to the port that was bound.
     * Fails if Mojang's login settings cannot be fetched, since no client could log in then.
     *
     * @param requireXbox  whether clients must have an identity signed by Xbox Live
     * @param netherNet    whether clients may also join over NetherNet, through plain HTTP signaling on the TCP side of the bound port; if that cannot start, the listener logs why and takes RakNet clients only
     * @param loginTimeout how long a client may take to log in before it is disconnected
     * @param onLogin      runs on the client's event loop once a client has logged in
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockPong pong, boolean requireXbox, boolean netherNet, Duration loginTimeout, Consumer<ClientLogin> onLogin) throws InterruptedException {
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

        InetSocketAddress bound = (InetSocketAddress) bind.channel().localAddress();
        NetherNet listening = netherNet ? bindNetherNet(group, bound, requireXbox, new NetherNetInitializer(requireXbox, loginTimeout, onLogin)) : null;
        ClientListener listener = new ClientListener(bind.channel(), listening, requireXbox);
        listener.pong(pong);
        return listener;
    }

    // Returns null when NetherNet cannot start, such as when its native library does not load on this platform or the TCP port is taken.
    private static NetherNet bindNetherNet(EventLoopGroup group, InetSocketAddress address, boolean requireXbox, NetherNetInitializer initializer) throws InterruptedException {
        Throwable failure;
        try {
            NetherNetHTTPServerSignaling signaling = new NetherNetHTTPServerSignaling.Builder()
                    .setIdentity(OperatorIdentity.generate("boarium"))
                    // ANY skips the token's signature, as xbox_auth off does for logins.
                    .setTokenTrust(requireXbox ? TokenTrust.MINECRAFT_AUTH : TokenTrust.ANY)
                    // RakNet holds the UDP side of the port, so each NetherNet connection takes a UDP port of its own.
                    .setIceOnLocalPort(false)
                    .build();
            ChannelFuture bind = new ServerBootstrap()
                    .group(group)
                    .channelFactory(NetherNetChannelFactory.server(signaling))
                    .childHandler(initializer)
                    .bind(address)
                    .await();
            if (bind.isSuccess()) {
                LOG.info("players can also join over NetherNet, through HTTP signaling on TCP port {}", address.getPort());
                return new NetherNet(bind.channel(), signaling);
            }
            bind.channel().close();
            failure = bind.cause();
        } catch (JoseException | RuntimeException | LinkageError e) {
            failure = e;
        }
        // A native library that does not load comes as the cause of an IOException that does not say why.
        LOG.error("NetherNet could not start on TCP port {}, so players can only join over RakNet: {}{}", address.getPort(), failure, failure.getCause() == null ? "" : " (" + failure.getCause() + ")");
        return null;
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
        if (this.netherNet != null) {
            this.netherNet.signaling().setAdvertisementData(new PongData.Builder()
                    .setServerName(pong.motd())
                    .setLevelName(pong.subMotd())
                    .setProtocol(pong.protocolVersion())
                    .setVersion(pong.version())
                    .setPlayerCount(pong.playerCount())
                    .setMaxPlayerCount(pong.maximumPlayerCount())
                    // As BDS sets it, telling a client signed in to no Xbox account whether it may join.
                    .setSelfSignedAuth(!this.requireXbox)
                    .build());
        }
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) this.channel.localAddress();
    }

    // Completes once both listeners have closed, so that their ports are free again.
    public Future<Void> close() {
        ChannelFuture rakNet = this.channel.close();
        if (this.netherNet == null) {
            return rakNet;
        }
        PromiseCombiner closed = new PromiseCombiner(ImmediateEventExecutor.INSTANCE);
        closed.addAll(rakNet, this.netherNet.channel().close());
        Promise<Void> done = ImmediateEventExecutor.INSTANCE.newPromise();
        closed.finish(done);
        return done;
    }
}
