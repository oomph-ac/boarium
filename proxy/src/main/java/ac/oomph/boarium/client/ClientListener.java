package ac.oomph.boarium.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Accepts Bedrock clients over RakNet and logs them in. Clients on another protocol than {@code codec}'s are turned away.
 */
public final class ClientListener {
    private final Channel channel;

    private ClientListener(Channel channel) {
        this.channel = channel;
    }

    /**
     * Binds the listener and advertises {@code pong}, with its ports set to the port that was bound.
     *
     * @param requireXbox whether clients must have an identity signed by Xbox Live
     * @param onLogin     runs on the client's event loop once a client has logged in
     */
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockCodec codec, BedrockPong pong, boolean requireXbox, Consumer<ClientLogin> onLogin) throws InterruptedException {
        ChannelFuture bind = new ServerBootstrap()
                .group(group)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    protected void initSession(BedrockServerSession session) {
                        session.setPacketHandler(new LoginHandler(session, codec, requireXbox, onLogin));
                    }
                })
                .bind(address)
                .sync();

        int port = ((InetSocketAddress) bind.channel().localAddress()).getPort();
        bind.channel().config().setOption(RakChannelOption.RAK_ADVERTISEMENT, pong.ipv4Port(port).ipv6Port(port).toByteBuf());
        return new ClientListener(bind.channel());
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) this.channel.localAddress();
    }

    public ChannelFuture close() {
        return this.channel.close();
    }
}
