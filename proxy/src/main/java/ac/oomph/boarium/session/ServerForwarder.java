package ac.oomph.boarium.session;

import ac.oomph.boarium.client.ClientDisconnect;
import ac.oomph.boarium.server.packet.Flush;
import ac.oomph.boarium.server.packet.Latency;
import ac.oomph.boarium.server.packet.UpdateCache;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Passes what a downstream server sends on to the client, as spectrum-internal's handleServer does.
 * Packets are written without a flush, and the server's Flush sends them, so each of the server's batches reaches the client as one batch at once.
 * As in Spectrum's production proxy, which turns gophertunnel's flush timer off, there is no flush timer.
 * Packets that come after the client was disconnected are dropped.
 * A packet the server did not ask the proxy to decode is passed through as the bytes it sent. A Transfer from the server is dropped.
 */
final class ServerForwarder extends ChannelInboundHandlerAdapter {
    private final BedrockServerSession session;
    private final Channel client;
    private volatile long latency;
    private volatile byte[] cache = new byte[0];

    ServerForwarder(BedrockServerSession session) {
        this.session = session;
        this.client = session.getPeer().getChannel();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (ClientDisconnect.ended(this.session).isDone()) {
            ReferenceCountUtil.release(msg);
        } else if (msg instanceof ByteBuf packet) {
            // Cloudburst writes a set packet buffer as it is, so the wrapper needs no packet ID.
            this.client.write(BedrockPacketWrapper.create(0, 0, 0, null, packet));
        } else if (msg instanceof BedrockPacket packet) {
            this.client.write(BedrockPacketWrapper.create(0, 0, 0, packet, null));
        } else if (msg instanceof Flush) {
            this.client.flush();
        } else if (msg instanceof Latency latency) {
            this.latency = latency.latency();
        } else if (msg instanceof UpdateCache update) {
            this.cache = update.cache();
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    // The latency the server last reported for this player, in milliseconds.
    long latency() {
        return this.latency;
    }

    // What the server last stored for this player with UpdateCache.
    byte[] cache() {
        return this.cache;
    }
}
