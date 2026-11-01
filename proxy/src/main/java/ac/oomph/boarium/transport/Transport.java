package ac.oomph.boarium.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.util.concurrent.Future;

/**
 * Opens byte streams to downstream servers.
 */
public interface Transport {

    /**
     * Connects to {@code addr}, a {@code host:port} string. {@code initializer} sets up the new channel's pipeline before it connects.
     * It runs once for each address tried, so it must make new handlers each time.
     *
     * @throws IllegalArgumentException if {@code addr} is not a valid {@code host:port}
     */
    Future<Channel> dial(String addr, ChannelInitializer<Channel> initializer);
}
