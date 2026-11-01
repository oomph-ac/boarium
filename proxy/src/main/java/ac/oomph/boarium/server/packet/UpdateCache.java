package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * Sent by a server to replace the player's cache data.
 */
public record UpdateCache(byte[] cache) implements SpectrumPacket {

    static UpdateCache decode(ByteBuf buf) {
        return new UpdateCache(Fields.readBytes(buf));
    }

    @Override
    public int id() {
        return SpectrumPackets.UPDATE_CACHE;
    }

    @Override
    public void encode(ByteBuf buf) {
        Fields.writeBytes(buf, this.cache);
    }
}
