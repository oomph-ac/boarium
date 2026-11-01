package ac.oomph.boarium.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpTransportTest {
    private static final ChannelInitializer<Channel> NOTHING = new ChannelInitializer<>() {
        @Override
        protected void initChannel(Channel ch) {
        }
    };

    private EventLoopGroup group;

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(1);
    }

    @AfterEach
    void tearDown() {
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private Channel echoServer(String host) {
        return new ServerBootstrap()
                .group(this.group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ctx.writeAndFlush(msg);
                    }
                })
                .bind(host, 0)
                .syncUninterruptibly()
                .channel();
    }

    private static int port(Channel server) {
        return ((InetSocketAddress) server.localAddress()).getPort();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private TcpTransport transport() {
        return new TcpTransport(this.group, Duration.ofSeconds(5));
    }

    @Test
    void dialsWithInitializerAndSocketOptions() throws Exception {
        Channel server = this.echoServer("127.0.0.1");
        CompletableFuture<byte[]> echoed = new CompletableFuture<>();

        Future<Channel> dial = this.transport().dial("127.0.0.1:" + port(server), new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                        echoed.complete(ByteBufUtil.getBytes(msg));
                    }
                });
            }
        });
        Channel channel = dial.get(5, TimeUnit.SECONDS);
        channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));

        assertArrayEquals(new byte[]{1, 2, 3}, echoed.get(5, TimeUnit.SECONDS));
        assertTrue(channel.config().getOption(ChannelOption.TCP_NODELAY));
        assertEquals(0, channel.config().getOption(ChannelOption.SO_LINGER));
        assertEquals(5000, channel.config().getOption(ChannelOption.CONNECT_TIMEOUT_MILLIS));
        // Linux reports double the buffer size it was given.
        assertTrue(channel.config().getOption(ChannelOption.SO_RCVBUF) >= 512 * 1024);
        assertTrue(channel.config().getOption(ChannelOption.SO_SNDBUF) >= 512 * 1024);
        channel.close().sync();
        server.close().sync();
    }

    @Test
    void resolvesNamedHost() throws Exception {
        Channel server = this.echoServer("localhost");

        Channel channel = this.transport().dial("localhost:" + port(server), NOTHING).get(5, TimeUnit.SECONDS);
        assertTrue(channel.isActive());
        channel.close().sync();
        server.close().sync();
    }

    @Test
    void triesNextAddressWhenOneFails() throws Exception {
        Channel server = this.echoServer("127.0.0.1");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<InetSocketAddress> addresses = List.of(new InetSocketAddress(loopback, closedPort()), new InetSocketAddress(loopback, port(server)));

        Promise<Channel> promise = this.group.next().newPromise();
        this.transport().connect(addresses, NOTHING, promise);

        Channel channel = promise.get(5, TimeUnit.SECONDS);
        assertEquals(port(server), ((InetSocketAddress) channel.remoteAddress()).getPort());
        channel.close().sync();
        server.close().sync();
    }

    @Test
    void reportsTheFirstAddressFailure() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int first = closedPort();
        int second = closedPort();
        while (second == first) {
            second = closedPort();
        }
        List<InetSocketAddress> addresses = List.of(new InetSocketAddress(loopback, first), new InetSocketAddress(loopback, second));

        Promise<Channel> promise = this.group.next().newPromise();
        this.transport().connect(addresses, NOTHING, promise);

        assertTrue(promise.await(5, TimeUnit.SECONDS));
        assertTrue(promise.cause().getMessage().endsWith(":" + first), promise.cause().getMessage());
    }

    @Test
    void closesTheChannelWhenTheDialIsCancelledFirst() throws Exception {
        Channel server = this.echoServer("127.0.0.1");
        Promise<Channel> promise = this.group.next().newPromise();
        CompletableFuture<Channel> attempted = new CompletableFuture<>();

        this.transport().connect(List.of((InetSocketAddress) server.localAddress()), new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                promise.cancel(false);
                attempted.complete(ch);
            }
        }, promise);

        Channel channel = attempted.get(5, TimeUnit.SECONDS);
        assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
        assertTrue(promise.isCancelled());
        server.close().sync();
    }

    @Test
    void closesAPendingConnectWhenTheDialIsCancelled() throws Exception {
        Promise<Channel> promise = this.group.next().newPromise();
        CompletableFuture<Channel> attempted = new CompletableFuture<>();

        // 10.255.255.1 does not answer, so the connect stays pending until the 30 second timeout.
        new TcpTransport(this.group, Duration.ofSeconds(30)).connect(List.of(new InetSocketAddress("10.255.255.1", 9)), new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                attempted.complete(ch);
            }
        }, promise);

        Channel channel = attempted.get(5, TimeUnit.SECONDS);
        promise.cancel(false);
        assertTrue(channel.closeFuture().await(2, TimeUnit.SECONDS));
    }

    @Test
    void makesNoAttemptAfterCancel() {
        Promise<Channel> promise = this.group.next().newPromise();
        promise.cancel(false);
        AtomicInteger attempts = new AtomicInteger();

        this.transport().connect(List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)), new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                attempts.incrementAndGet();
            }
        }, promise);

        // The group has one thread, so this task runs after any channel set-up queued before it.
        this.group.submit(() -> {}).syncUninterruptibly();
        assertEquals(0, attempts.get());
    }

    @Test
    void failsWhenNothingListens() throws Exception {
        Future<Channel> dial = this.transport().dial("127.0.0.1:" + closedPort(), NOTHING);

        assertTrue(dial.await(5, TimeUnit.SECONDS));
        assertInstanceOf(ConnectException.class, dial.cause());
    }

    @Test
    void failsForUnknownHost() throws Exception {
        Future<Channel> dial = this.transport().dial("boarium-test.invalid:19132", NOTHING);

        assertTrue(dial.await(10, TimeUnit.SECONDS));
        assertInstanceOf(UnknownHostException.class, dial.cause());
    }

    @Test
    void rejectsBadArgumentsBeforeDialing() {
        assertThrows(IllegalArgumentException.class, () -> this.transport().dial("lobby", NOTHING));
        assertThrows(NullPointerException.class, () -> this.transport().dial("localhost:19132", null));
    }

    @Test
    void rejectsConnectTimeoutUnderOneMillisecond() {
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport(this.group, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport(this.group, Duration.ofSeconds(-1)));
        assertDoesNotThrow(() -> new TcpTransport(this.group, Duration.ofMillis(1)));
    }
}
