package ac.oomph.boarium.session;

import ac.oomph.boarium.server.ServerConn;
import ac.oomph.boarium.server.packet.Transfer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.RejectedExecutionException;

/**
 * Hands what one downstream server sends to its session on the client's event loop, as spectrum-internal's handleServer reads a server.
 * The server's first Transfer ends what is passed on from it, as spectrum-internal stops reading a server there; this is decided here, on the server's event loop, so it follows the order the server sent its packets.
 */
final class ServerForwarder extends ChannelInboundHandlerAdapter {
    private final Session session;
    private final ServerConn server;
    // Only used on the server's event loop.
    private boolean transferred;

    ServerForwarder(Session session, ServerConn server) {
        this.session = session;
        this.server = server;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (this.transferred) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof Transfer) {
            this.transferred = true;
        }
        try {
            this.session.clientChannel().eventLoop().execute(() -> this.session.fromServer(this.server, msg));
        } catch (RejectedExecutionException e) {
            // The client's event loop has shut down, and the client with it.
            ReferenceCountUtil.release(msg);
        }
    }
}
