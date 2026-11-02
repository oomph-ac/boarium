package ac.oomph.boarium.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Accepts Bedrock clients over RakNet and logs them in. Clients on another protocol than {@code codec}'s are turned away.
 */
public final class ClientListener {
    private static final String VALIDATE_ENCRYPTION = "cloudburst.validateEncryption";
    private static final AttributeKey<BedrockServerSession> SESSION = AttributeKey.valueOf(ClientListener.class.getName() + ".session");

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
    public static ClientListener bind(EventLoopGroup group, InetSocketAddress address, BedrockCodec codec, BedrockPong pong, boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) throws InterruptedException {
        if (loginTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("login timeout must be at least 1 ms, got " + loginTimeout);
        }
        // Cloudburst only checks the checksum at the end of each encrypted batch when this is set; gophertunnel always checks it.
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
                .childHandler(new BedrockServerInitializer() {
                    @Override
                    protected void postInitChannel(Channel channel) {
                        // Any error while handling the client's packets ends the connection, with a disconnect screen once the client has a session.
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                BedrockServerSession session = ctx.channel().attr(SESSION).get();
                                if (session != null && session.isConnected() && session.getPacketHandler() instanceof LoginHandler login) {
                                    login.failOnError();
                                } else if (session != null && session.isConnected()) {
                                    session.disconnect("disconnectionScreen.internalError.cantConnect");
                                } else {
                                    ctx.close();
                                }
                            }
                        });
                    }

                    @Override
                    protected void initSession(BedrockServerSession session) {
                        // boarium has no split-screen support, so packets for a second player on the same connection are dropped.
                        // Cloudburst would send a sub-client's Disconnect to the primary player, so nothing is sent.
                        if (session.isSubClient()) {
                            session.setPacketHandler(new BedrockPacketHandler() {
                            });
                            return;
                        }
                        session.getPeer().getChannel().attr(SESSION).set(session);
                        session.setPacketHandler(new LoginHandler(session, codec, requireXbox, loginTimeout, onLogin));
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
