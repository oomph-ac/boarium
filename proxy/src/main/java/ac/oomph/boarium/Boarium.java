package ac.oomph.boarium;

import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.client.ResourcePackCatalog;
import ac.oomph.boarium.protocol.BedrockVersions;
import ac.oomph.boarium.session.Processor;
import ac.oomph.boarium.session.Session;
import ac.oomph.boarium.session.SessionSettings;
import ac.oomph.boarium.transport.Addresses;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The proxy: it takes players over RakNet, NetherNet or both, and runs a {@link Session} for each, as spectrum-internal's Spectrum does.
 */
public final class Boarium implements AutoCloseable {
    private static final Duration DIAL_TIMEOUT = Duration.ofSeconds(5);
    // How long a client may take from its first packet to the end of its login, before it starts the game on a server.
    private static final Duration CLIENT_LOGIN_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PONG_INTERVAL = Duration.ofSeconds(5);
    // How long a shutdown waits for players to close their connections after their Disconnect, since closing the listener drops what RakNet has not sent yet.
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(3);
    private static final Logger LOG = LoggerFactory.getLogger(Boarium.class);

    private final BoariumConfig config;
    private final EventLoopGroup group;
    private final ClientListener listener;
    private final ResourcePackCatalog packs;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Set<ClientLogin> joining = ConcurrentHashMap.newKeySet();
    private final ScheduledFuture<?> pongs;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Boarium(BoariumConfig config, SslContext netherNetTls, Path netherNetIdentity, Function<ClientLogin, Processor> processors, EventLoopGroup group) throws InterruptedException, IOException {
        this.config = config;
        this.group = group;
        TcpTransport transport = new TcpTransport(group, DIAL_TIMEOUT);
        SessionSettings settings = new SessionSettings(config.fallbackServer(), SessionSettings.DEFAULT.loginTimeout(), config.latencyInterval(), processors);
        InetSocketAddress listen = listenAddress(config.listen());
        // A listen port of 0 is picked here once, so that every port that follows the listen port is the same free one.
        int picked = listen.getPort() == 0 ? Addresses.freePort(listen.getAddress(), Set.copyOf(List.of(config.rakNet().port(), config.netherNet().port(), config.netherNet().signalingPort()))) : listen.getPort();
        BoariumConfig.RakNet rakNetConfig = config.rakNet();
        ClientListener.RakNetSettings rakNet = rakNetConfig.enabled()
                ? new ClientListener.RakNetSettings(new InetSocketAddress(listen.getAddress(), port(rakNetConfig.port(), picked)), rakNetConfig.maxConnectionsPerAddress(), rakNetConfig.packetLimit(), rakNetConfig.rateLimiting())
                : null;
        ClientListener.NetherNetSettings netherNet = config.netherNet().enabled()
                ? new ClientListener.NetherNetSettings(new InetSocketAddress(listen.getAddress(), port(config.netherNet().signalingPort(), picked)), port(config.netherNet().port(), picked), netherNetTls, netherNetIdentity,
                        config.netherNet().advertiseAddresses(), stunServers(config.netherNet(), listen.getAddress()))
                : null;
        this.packs = new ResourcePackCatalog(config.resourcePacks());
        try {
            this.listener = ClientListener.bind(group, rakNet, netherNet, this.pong(), config.xboxAuth(), CLIENT_LOGIN_TIMEOUT, this.packs, login -> this.join(login, transport, settings));
        } catch (Throwable t) {
            this.packs.close();
            throw t;
        }
        this.pongs = group.scheduleAtFixedRate(() -> this.listener.pong(this.pong()), PONG_INTERVAL.toMillis(), PONG_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Starts listening for players with {@code config}.
     *
     * @param netherNetTls      what NetherNet signaling serves HTTPS with, from {@link NetherNetHttps#load()}; null for plain HTTP
     * @param netherNetIdentity the PEM file of NetherNet's identity key, created on first start and read only while NetherNet is on
     * @param processors        gives each player's session its processor
     */
    public static Boarium start(BoariumConfig config, SslContext netherNetTls, Path netherNetIdentity, Function<ClientLogin, Processor> processors) throws InterruptedException, IOException {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            return new Boarium(config, netherNetTls, netherNetIdentity, processors, group);
        } catch (Throwable t) {
            // Netty also throws checked exceptions it does not declare, such as a BindException; the event loops would otherwise keep the JVM running.
            group.shutdownGracefully();
            throw t;
        }
    }

    // None while advertise_addresses is set, as those already say where players reach this machine, and none on a loopback address, from which no STUN server can be reached.
    static List<String> stunServers(BoariumConfig.NetherNet netherNet, InetAddress listenHost) {
        if (!netherNet.advertiseAddresses().isEmpty() || !netherNet.stunDiscovery()) {
            return List.of();
        }
        if (listenHost.isLoopbackAddress()) {
            LOG.info("STUN discovery is skipped, as boarium listens on the loopback address {}", NetUtil.toAddressString(listenHost));
            return List.of();
        }
        return ClientListener.PUBLIC_STUN_SERVERS;
    }

    private static int port(int port, int picked) {
        return port == 0 ? picked : port;
    }

    // host:port, resolved; as in Go's net.Listen, an empty host means every address of this machine.
    static InetSocketAddress listenAddress(String addr) {
        InetSocketAddress parsed = Addresses.parse(addr);
        if (addr.substring(0, addr.lastIndexOf(':')).isEmpty()) {
            return new InetSocketAddress(parsed.getPort());
        }
        InetSocketAddress resolved = new InetSocketAddress(parsed.getHostString(), parsed.getPort());
        if (resolved.isUnresolved()) {
            throw new IllegalArgumentException("listen: cannot resolve the host in " + addr);
        }
        return resolved;
    }

    private void join(ClientLogin login, TcpTransport transport, SessionSettings settings) {
        String name = login.identity().displayName();
        this.joining.add(login);
        // A client that finished logging in as the shutdown began would miss close()'s Disconnects.
        if (this.closed.get()) {
            this.joining.remove(login);
            ClientDisconnect.disconnect(login.session(), this.config.shutdownMessage());
            return;
        }
        Session.login(login, transport, this.config.server(), settings).whenComplete((session, error) -> {
            this.joining.remove(login);
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                LOG.info("{} could not join {}: {}", name, this.config.server(), cause.toString());
                return;
            }
            this.sessions.add(session);
            LOG.info("{} joined {}", name, this.config.server());
            ClientDisconnect.ended(login.session()).thenRun(() -> {
                this.sessions.remove(session);
                LOG.info("{} left", name);
            });
        });
    }

    private BedrockPong pong() {
        return new BedrockPong()
                .edition("MCPE")
                .motd(this.config.motd())
                .subMotd(this.config.subMotd())
                .playerCount(this.sessions.size())
                .maximumPlayerCount(this.config.maxPlayers())
                .gameType("Survival")
                .protocolVersion(BedrockVersions.LATEST.getProtocolVersion())
                .version(BedrockVersions.SUPPORTED.getLast().names().getLast());
    }

    /**
     * The players who have joined a server and not left.
     */
    public Collection<Session> sessions() {
        return Collections.unmodifiableSet(this.sessions);
    }

    /**
     * Disconnects every player with the shutdown message, including those still joining a server, and stops listening. Later calls do nothing.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.pongs.cancel(false);
        this.packs.close();
        List<ChannelFuture> clientsClosed = new ArrayList<>();
        List<ClientLogin> players = new ArrayList<>(this.joining);
        this.sessions.forEach(session -> players.add(session.client()));
        for (ClientLogin player : players) {
            ClientDisconnect.disconnect(player.session(), this.config.shutdownMessage());
            clientsClosed.add(player.session().getPeer().getChannel().closeFuture());
        }
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        for (ChannelFuture closed : clientsClosed) {
            closed.awaitUninterruptibly(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        this.listener.close().syncUninterruptibly();
        this.group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /**
     * Where RakNet listens, or null when it is off.
     */
    public InetSocketAddress rakNetAddress() {
        return this.listener.rakNetAddress();
    }

    /**
     * Where NetherNet's signaling listens, or null when NetherNet is off or could not start.
     */
    public InetSocketAddress netherNetAddress() {
        return this.listener.netherNetAddress();
    }
}
