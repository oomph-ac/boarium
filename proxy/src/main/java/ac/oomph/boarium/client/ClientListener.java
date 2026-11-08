package ac.oomph.boarium.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.AdvertisedAddresses;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.IceUdpMuxListener;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Accepts Bedrock clients over RakNet, NetherNet or both, and logs them in. Clients on a protocol that {@link ac.oomph.boarium.protocol.BedrockVersions} lacks are turned away.
 */
public final class ClientListener {
    private static final String VALIDATE_ENCRYPTION = "cloudburst.validateEncryption";
    private static final Logger LOG = LoggerFactory.getLogger(ClientListener.class);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    // Null when RakNet is off.
    private final Channel rakNet;
    // Null when NetherNet is off or could not start.
    private final NetherNet netherNet;
    private final boolean requireXbox;

    private record NetherNet(Channel channel, NetherNetHTTPServerSignaling signaling, InetSocketAddress address, IceUdpMuxListener udpPort) {
    }

    /**
     * The public STUN servers that find this machine's public address, two operators' so that one blocked where boarium runs does not stop it.
     */
    public static final List<String> PUBLIC_STUN_SERVERS = List.of("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302");

    /**
     * Where NetherNet listens: signaling on TCP at {@code signaling}, and connections on UDP {@code port} of the same host.
     *
     * @param tls                what the signaling serves HTTPS with, refusing plain HTTP; null for plain HTTP
     * @param identity           the PEM file of the key the signaling signs its answers with, created on first start
     * @param advertiseAddresses where joining players are told to reach the UDP port, as host names or IPs with an optional port that defaults to {@code port}; empty for this machine's own addresses
     * @param stunServers        the {@code stun:host:port} servers that find this machine's public address for joining players; must be empty while advertiseAddresses is not
     */
    public record NetherNetSettings(InetSocketAddress signaling, int port, SslContext tls, Path identity, List<String> advertiseAddresses, List<String> stunServers) {

        /**
         * Advertises this machine's own addresses, without STUN.
         */
        public NetherNetSettings(InetSocketAddress signaling, int port, SslContext tls, Path identity) {
            this(signaling, port, tls, identity, List.of(), List.of());
        }
    }

    private ClientListener(Channel rakNet, NetherNet netherNet, boolean requireXbox) {
        this.rakNet = rakNet;
        this.netherNet = netherNet;
        this.requireXbox = requireXbox;
    }

    /**
     * Binds the listener for RakNet only.
     *
     * @see #bind(EventLoopGroup, InetSocketAddress, NetherNetSettings, BedrockPong, boolean, Duration, Consumer)
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockPong pong, boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) throws InterruptedException {
        return bind(group, address, null, pong, requireXbox, loginTimeout, onLogin);
    }

    /**
     * Binds the listener and advertises {@code pong}, with its ports set to RakNet's.
     * Fails if Mojang's login settings cannot be fetched, since no client could log in then.
     * If NetherNet cannot start, such as when its native library does not load, a port is taken or its identity file cannot be read, the listener logs why and takes RakNet clients only, or fails when RakNet is off.
     *
     * @param rakNet       where RakNet listens; null for no RakNet
     * @param netherNet    where NetherNet listens; null for no NetherNet. Its signaling port must not be 0.
     * @param requireXbox  whether clients must have an identity signed by Xbox Live
     * @param loginTimeout how long a client may take to log in before it is disconnected
     * @param onLogin      runs on the client's event loop once a client has logged in
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress rakNet, NetherNetSettings netherNet, BedrockPong pong, boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin)
            throws InterruptedException {
        if (loginTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("login timeout must be at least 1 ms, got " + loginTimeout);
        }
        if (rakNet == null && netherNet == null) {
            throw new IllegalArgumentException("neither RakNet nor NetherNet is on");
        }
        // The signaling cannot tell which port it took for 0, so no client could be told where to join.
        if (netherNet != null && netherNet.signaling().getPort() == 0) {
            throw new IllegalArgumentException("NetherNet's signaling port must not be 0");
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

        Channel rakNetChannel = null;
        if (rakNet != null) {
            rakNetChannel = new ServerBootstrap()
                    .group(group)
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                    .childHandler(new ClientInitializer(requireXbox, loginTimeout, onLogin))
                    .bind(rakNet)
                    .sync()
                    .channel();
            LOG.info("RakNet on UDP {}", text((InetSocketAddress) rakNetChannel.localAddress()));
        }
        NetherNet netherNetListening = null;
        if (netherNet != null) {
            try {
                netherNetListening = bindNetherNet(group, netherNet, requireXbox, new NetherNetInitializer(requireXbox, loginTimeout, onLogin));
            } catch (NetherNetFailure e) {
                if (rakNetChannel == null) {
                    throw new IllegalStateException(e.explain("; RakNet is off, so no player could join"), e.getCause());
                }
                LOG.error("{}", e.explain(", so players can only join over RakNet"));
            }
        }
        ClientListener listener = new ClientListener(rakNetChannel, netherNetListening, requireXbox);
        listener.pong(pong);
        return listener;
    }

    private static NetherNet bindNetherNet(EventLoopGroup group, NetherNetSettings settings, boolean requireXbox, NetherNetInitializer initializer) throws InterruptedException, NetherNetFailure {
        OperatorIdentity identity = NetherNetIdentity.loadOrCreate(settings.identity());
        List<String> advertised = resolve(settings.advertiseAddresses(), settings.port());
        InetSocketAddress signalingAddress = settings.signaling();
        InetSocketAddress udp = new InetSocketAddress(signalingAddress.getAddress(), settings.port());
        Throwable failure;
        IceUdpMuxListener udpPort = null;
        try {
            udpPort = holdUdpPort(udp);
            List<String> stunServers = settings.stunServers();
            Signaling signaling = bindSignaling(group, settings, udp, identity, requireXbox, initializer, advertised, stunServers);
            // The signaling starts STUN discovery as it binds, and STUN failing to start must not keep players out.
            if (!stunServers.isEmpty() && isStunStartFailure(signaling.bind().cause())) {
                LOG.warn("STUN discovery could not start, so NetherNet advertises this machine's own addresses only: {}", NetherNetFailure.reason(signaling.bind().cause()));
                stunServers = List.of();
                awaitClosed(signaling.signaling());
                signaling = bindSignaling(group, settings, udp, identity, requireXbox, initializer, advertised, stunServers);
            }
            ChannelFuture bind = signaling.bind();
            if (bind.isSuccess()) {
                LOG.info("NetherNet signaling on TCP {} over {}, NetherNet on UDP {}", text(signalingAddress), settings.tls() == null ? "HTTP" : "HTTPS", text(udp));
                LOG.info("NetherNet advertises {}", advertised(settings.advertiseAddresses(), advertised, stunServers));
                return new NetherNet(bind.channel(), signaling.signaling(), signalingAddress, udpPort);
            }
            failure = bind.cause();
        } catch (IOException | RuntimeException | LinkageError e) {
            failure = e;
        }
        if (udpPort != null) {
            udpPort.close();
        }
        String where = "signaling on TCP " + text(signalingAddress) + " and connections on UDP " + text(udp);
        throw new NetherNetFailure("NetherNet could not start with " + where + ": " + NetherNetFailure.reason(failure), null, failure);
    }

    private record Signaling(NetherNetHTTPServerSignaling signaling, ChannelFuture bind) {
    }

    // Looks each host name up now, so that one that does not resolve is named in the error; the signaling gets the addresses as IPs.
    private static List<String> resolve(List<String> advertised, int port) throws NetherNetFailure {
        List<String> resolved = new ArrayList<>();
        try {
            for (InetSocketAddress endpoint : AdvertisedAddresses.parse(advertised, port)) {
                if (!endpoint.isUnresolved()) {
                    resolved.add(NetUtil.toSocketAddressString(endpoint));
                    continue;
                }
                try {
                    for (InetAddress address : InetAddress.getAllByName(endpoint.getHostString())) {
                        resolved.add(NetUtil.toSocketAddressString(new InetSocketAddress(address, endpoint.getPort())));
                    }
                } catch (UnknownHostException e) {
                    throw new NetherNetFailure("NetherNet could not start: nethernet.advertise_addresses: cannot resolve " + endpoint.getHostString(), null, e);
                }
            }
            // Host names can stand for addresses the entries alone did not show, so the library's checks run again on the IPs.
            List<String> distinct = resolved.stream().distinct().toList();
            if (distinct.size() > AdvertisedAddresses.MAX_ENDPOINTS) {
                throw new IllegalArgumentException("its host names stand for " + distinct.size() + " addresses, more than the " + AdvertisedAddresses.MAX_ENDPOINTS + " the signaling takes");
            }
            AdvertisedAddresses.parse(distinct, port);
            return distinct;
        } catch (IllegalArgumentException e) {
            throw new NetherNetFailure("NetherNet could not start: nethernet.advertise_addresses: " + e.getMessage(), null, e);
        }
    }

    // How the signaling fails its bind when it cannot open its STUN monitors.
    static boolean isStunStartFailure(Throwable failure) {
        return failure instanceof ConnectException && failure.getMessage() != null && failure.getMessage().startsWith("Could not start STUN discovery");
    }

    // A signaling whose bind failed closes its TCP listener on that listener's own thread, so the port is only free again once that is done.
    private static void awaitClosed(NetherNetHTTPServerSignaling signaling) throws InterruptedException {
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        while (signaling.isActive() && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
    }

    // Binds the signaling, closing its channel again if the bind fails.
    private static Signaling bindSignaling(EventLoopGroup group, NetherNetSettings settings, InetSocketAddress udp, OperatorIdentity identity, boolean requireXbox, NetherNetInitializer initializer,
                                           List<String> advertised, List<String> stunServers) throws InterruptedException {
        // On the signaling's port number, ICE takes the UDP side of the signaling's own address; on another, it is told where to bind.
        boolean sharedPort = settings.port() == settings.signaling().getPort();
        NetherNetHTTPServerSignaling signaling = new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(identity)
                // ANY skips the token's signature, as xbox_auth off does for logins.
                .setTokenTrust(requireXbox ? TokenTrust.MINECRAFT_AUTH : TokenTrust.ANY)
                .setIceOnLocalPort(sharedPort)
                .setSslContext(settings.tls())
                .setAdvertisedAddresses(advertised)
                .setStunServers(stunServers)
                .build();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channelFactory(NetherNetChannelFactory.server(signaling))
                .childHandler(initializer);
        if (!sharedPort) {
            bootstrap.option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, udp);
        }
        ChannelFuture bind = bootstrap.bind(settings.signaling()).await();
        if (!bind.isSuccess()) {
            bind.channel().close().await();
        }
        return new Signaling(signaling, bind);
    }

    // What joining players are told to reach NetherNet's UDP port at, for the log: as configured, then as the IPs and ports that they stand for.
    private static String advertised(List<String> configured, List<String> resolved, List<String> stunServers) {
        if (!configured.isEmpty()) {
            return String.join(", ", configured) + " (" + String.join(", ", resolved) + ")";
        }
        return "this machine's addresses, " + (stunServers.isEmpty() ? "without STUN discovery" : "with the public address that STUN discovery finds through " + String.join(", ", stunServers));
    }

    // The ICE agent opens its UDP port only while a player is connected, so between players another program could take it, and every join would then fail.
    // A listener on the port holds it while NetherNet runs.
    private static IceUdpMuxListener holdUdpPort(InetSocketAddress udp) throws IOException {
        IceUdpMuxListener listener;
        try {
            listener = new IceUdpMuxListener(udp.getAddress(), udp.getPort(), Runnable::run, request -> CompletableFuture.completedFuture(null));
        } catch (IllegalStateException e) {
            throw new IOException("cannot bind UDP " + text(udp) + ", which another program may hold: " + e.getMessage(), e);
        }
        try {
            // With no keys, it drops every request that no connection on the port answers, before any reaches the handler.
            listener.setAdmission(new byte[0], Map.of());
        } catch (RuntimeException e) {
            listener.close();
            throw e;
        }
        return listener;
    }

    // host:port, with * for every address of this machine.
    private static String text(InetSocketAddress address) {
        return address.getAddress().isAnyLocalAddress() ? "*:" + address.getPort() : NetUtil.toSocketAddressString(address);
    }

    /**
     * Sets what the listener answers server list pings with, with the ports set to RakNet's.
     */
    public void pong(BedrockPong pong) {
        if (this.rakNet != null) {
            int port = this.rakNetAddress().getPort();
            ByteBuf advertisement = pong.ipv4Port(port).ipv6Port(port).toByteBuf();
            try {
                // RakNet keeps a copy.
                this.rakNet.config().setOption(RakChannelOption.RAK_ADVERTISEMENT, advertisement);
            } finally {
                advertisement.release();
            }
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

    /**
     * Where RakNet listens, or null when it is off.
     */
    public InetSocketAddress rakNetAddress() {
        return this.rakNet == null ? null : (InetSocketAddress) this.rakNet.localAddress();
    }

    /**
     * Where NetherNet's signaling listens, or null when NetherNet is off or could not start.
     */
    public InetSocketAddress netherNetAddress() {
        return this.netherNet == null ? null : this.netherNet.address();
    }

    // Completes once both listeners have closed, so that their TCP ports are free again; the UDP port frees soon after.
    public Future<Void> close() {
        Future<?> rakNet = this.rakNet == null ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null) : this.rakNet.close();
        Future<?> netherNet = this.netherNet == null ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null) : this.netherNet.channel().close();
        Promise<Void> done = ImmediateEventExecutor.INSTANCE.newPromise();
        // PromiseCombiner is not thread-safe, and the two channels close on different event loops, so this waits for one close, then the other.
        rakNet.addListener(closedRakNet -> netherNet.addListener(closedNetherNet -> {
            if (this.netherNet != null) {
                this.netherNet.udpPort().close();
            }
            done.trySuccess(null);
        }));
        return done;
    }
}
