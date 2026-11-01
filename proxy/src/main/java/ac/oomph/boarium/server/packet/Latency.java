package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * Measures latency through the proxy. The proxy sends the client's ping and the time it sent the packet, in milliseconds.
 * The server answers with the total latency between it and the client.
 */
public record Latency(long latency, long timestamp) implements SpectrumPacket {

    static Latency decode(ByteBuf buf) {
        return new Latency(buf.readLongLE(), buf.readLongLE());
    }

    @Override
    public int id() {
        return SpectrumPackets.LATENCY;
    }

    @Override
    public void encode(ByteBuf buf) {
        buf.writeLongLE(this.latency);
        buf.writeLongLE(this.timestamp);
    }
}
