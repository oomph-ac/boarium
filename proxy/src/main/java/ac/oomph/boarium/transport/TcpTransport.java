package ac.oomph.boarium.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Opens one TCP connection per dial, with Nagle off, linger 0 (close sends a reset) and 512 KiB socket buffers.
 * Host names are looked up off the event loop, and each address is tried in turn, as Go's net.Dial does.
 */
public final class TcpTransport implements Transport {
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final Executor RESOLVER = Executors.newVirtualThreadPerTaskExecutor();

    private final EventLoopGroup group;
    private final Bootstrap bootstrap;

    /**
     * @param connectTimeout the limit for each connection attempt, at least one millisecond
     */
    public TcpTransport(EventLoopGroup group, Duration connectTimeout) {
        if (connectTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("connect timeout must be at least 1 ms, got " + connectTimeout);
        }

        this.group = group;
        this.bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_LINGER, 0)
                .option(ChannelOption.SO_RCVBUF, BUFFER_SIZE)
                .option(ChannelOption.SO_SNDBUF, BUFFER_SIZE);
    }

    @Override
    public Future<Channel> dial(String addr, ChannelInitializer<Channel> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        InetSocketAddress address = Addresses.parse(addr);
        Promise<Channel> promise = this.group.next().newPromise();
        if (!address.isUnresolved()) {
            this.connect(List.of(address), initializer, promise);
            return promise;
        }

        RESOLVER.execute(() -> {
            try {
                List<InetSocketAddress> addresses = new ArrayList<>();
                for (InetAddress resolved : InetAddress.getAllByName(address.getHostString())) {
                    addresses.add(new InetSocketAddress(resolved, address.getPort()));
                }
                this.connect(addresses, initializer, promise);
            } catch (Throwable t) {
                promise.tryFailure(t);
            }
        });
        return promise;
    }

    void connect(List<InetSocketAddress> addresses, ChannelInitializer<Channel> initializer, Promise<Channel> promise) {
        this.attempt(addresses, 0, null, initializer, promise);
    }

    // Like Go's net.Dial, a dial that fails on every address reports the first address's error.
    private void attempt(List<InetSocketAddress> addresses, int index, Throwable firstFailure, ChannelInitializer<Channel> initializer, Promise<Channel> promise) {
        if (promise.isDone()) {
            return;
        }

        ChannelFuture attempt = this.bootstrap.clone().handler(initializer).connect(addresses.get(index));
        promise.addListener(done -> {
            if (done.isCancelled()) {
                attempt.channel().close();
            }
        });
        attempt.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                if (!promise.trySuccess(future.channel())) {
                    future.channel().close();
                }
                return;
            }

            Throwable failure = firstFailure != null ? firstFailure : future.cause();
            if (index + 1 < addresses.size()) {
                this.attempt(addresses, index + 1, failure, initializer, promise);
            } else {
                promise.tryFailure(failure);
            }
        });
    }
}
