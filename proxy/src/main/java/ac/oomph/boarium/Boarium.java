package ac.oomph.boarium;

import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.client.ClientListener;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.protocol.BedrockVersions;
import ac.oomph.boarium.session.Processor;
import ac.oomph.boarium.session.Session;
import ac.oomph.boarium.session.SessionSettings;
import ac.oomph.boarium.transport.Addresses;
import ac.oomph.boarium.transport.TcpTransport;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
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
 * The proxy: it takes players on its listen address and runs a {@link Session} for each, as spectrum-internal's Spectrum does.
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
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Set<ClientLogin> joining = ConcurrentHashMap.newKeySet();
    private final ScheduledFuture<?> pongs;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Boarium(BoariumConfig config, Function<ClientLogin, Processor> processors, EventLoopGroup group) throws InterruptedException {
        this.config = config;
        this.group = group;
        TcpTransport transport = new TcpTransport(group, DIAL_TIMEOUT);
        SessionSettings settings = new SessionSettings(config.fallbackServer(), SessionSettings.DEFAULT.loginTimeout(), config.latencyInterval(), processors);
        this.listener = ClientListener.bind(group, listenAddress(config.listen()), this.pong(), config.xboxAuth(), CLIENT_LOGIN_TIMEOUT,
                login -> this.join(login, transport, settings));
        this.pongs = group.scheduleAtFixedRate(() -> this.listener.pong(this.pong()), PONG_INTERVAL.toMillis(), PONG_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Starts listening for players with {@code config}.
     *
     * @param processors gives each player's session its processor
     */
    public static Boarium start(BoariumConfig config, Function<ClientLogin, Processor> processors) throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            return new Boarium(config, processors, group);
        } catch (Throwable t) {
            // Netty also throws checked exceptions it does not declare, such as a BindException; the event loops would otherwise keep the JVM running.
            group.shutdownGracefully();
            throw t;
        }
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
     * Where players connect.
     */
    public InetSocketAddress address() {
        return this.listener.address();
    }
}
