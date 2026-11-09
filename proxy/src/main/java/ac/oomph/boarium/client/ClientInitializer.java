package ac.oomph.boarium.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockServerInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds a client's pipeline over RakNet, and the parts of it that every transport shares: boarium's batch checks, the error handler, and the login.
 */
class ClientInitializer extends BedrockServerInitializer {
    static final AttributeKey<BedrockServerSession> SESSION = AttributeKey.valueOf(ClientInitializer.class.getName() + ".session");

    private final boolean requireXbox;
    private final Supplier<ResourcePacks> packs;
    private final Duration loginTimeout;
    private final Consumer<ClientLogin> onLogin;

    ClientInitializer(boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) {
        this(requireXbox, loginTimeout, ResourcePacks.EMPTY, onLogin);
    }

    // Pin one immutable snapshot for each login, without file I/O on the event loop.
    ClientInitializer(boolean requireXbox, Duration loginTimeout, ResourcePacks packs, Consumer<ClientLogin> onLogin) {
        this(requireXbox, loginTimeout, () -> packs, onLogin);
    }

    ClientInitializer(boolean requireXbox, Duration loginTimeout, Supplier<ResourcePacks> packs, Consumer<ClientLogin> onLogin) {
        this.requireXbox = requireXbox;
        this.packs = packs;
        this.loginTimeout = loginTimeout;
        this.onLogin = onLogin;
    }

    @Override
    protected void postInitChannel(Channel channel) {
        channel.pipeline().replace(BedrockBatchDecoder.NAME, BedrockBatchDecoder.NAME, new ClientBatchDecoder());
        // Any error while handling the client's packets ends the connection, with a disconnect screen once the client has a session.
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                BedrockServerSession session = ctx.channel().attr(SESSION).get();
                if (session != null && session.isConnected() && session.getPacketHandler() instanceof LoginHandler login) {
                    login.failOnError();
                } else if (session != null && session.isConnected()) {
                    ClientDisconnect.disconnect(session, "disconnectionScreen.internalError.cantConnect");
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
        session.setPacketHandler(new LoginHandler(session, this.requireXbox, this.loginTimeout, this.packs.get(), this.onLogin));
    }
}
