package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * Sent by a server at the end of its own batch, so the proxy flushes to the client now instead of batching the packets again.
 */
public record Flush() implements SpectrumPacket {

    @Override
    public int id() {
        return SpectrumPackets.FLUSH;
    }

    @Override
    public void encode(ByteBuf buf) {
    }
}
