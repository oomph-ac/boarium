package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * A packet of the Spectrum protocol, sent between the proxy and a downstream server next to normal Bedrock packets.
 */
public sealed interface SpectrumPacket permits ConnectionRequest, ConnectionResponse, Flush, Latency, Transfer, UpdateCache {

    int id();

    void encode(ByteBuf buf);
}
