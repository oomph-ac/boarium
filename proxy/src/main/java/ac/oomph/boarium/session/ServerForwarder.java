package ac.oomph.boarium.session;

import ac.oomph.boarium.server.ServerConn;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.Transfer;
import ac.oomph.boarium.server.packet.UpdateCache;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Passes what one downstream server sends on to the client, as spectrum-internal's handleServer does.
 * Packets are written without a flush, and the server's Flush sends them, so each of the server's batches reaches the client as one batch at once.
 * As in Spectrum's production proxy, which turns gophertunnel's flush timer off, there is no flush timer.
 * Packets are dropped once the client was disconnected, and when the server is not the session's current one, such as the one a transfer left.
 * The server's first Transfer ends what is passed on from it, as spectrum-internal stops reading a server there.
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
        if (this.transferred || !this.session.forwardsFrom(this.server)) {
            ReferenceCountUtil.release(msg);
        } else if (msg instanceof ByteBuf packet) {
            // Cloudburst writes a set packet buffer as it is, so the wrapper needs no packet ID.
            this.session.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, null, packet));
        } else if (msg instanceof BedrockPacket packet) {
            this.session.tracker().observe(packet);
            this.session.clientChannel().write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        } else if (msg instanceof Flush) {
            this.session.clientChannel().flush();
        } else if (msg instanceof Latency latency) {
            this.session.serverLatency(latency.latency());
        } else if (msg instanceof UpdateCache update) {
            this.session.cache(update.cache());
        } else if (msg instanceof Transfer transfer) {
            this.transferred = true;
            this.session.transfer(transfer.addr());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }
}
