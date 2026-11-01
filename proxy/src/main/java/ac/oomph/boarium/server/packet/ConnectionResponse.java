package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.common.util.VarInts;

/**
 * The server's answer to {@link ConnectionRequest}, with the player's entity runtime ID and unique ID on that server.
 */
public record ConnectionResponse(long runtimeId, long uniqueId) implements SpectrumPacket {

    static ConnectionResponse decode(ByteBuf buf) {
        return new ConnectionResponse(VarInts.readUnsignedLong(buf), VarInts.readLong(buf));
    }

    @Override
    public int id() {
        return SpectrumPackets.CONNECTION_RESPONSE;
    }

    @Override
    public void encode(ByteBuf buf) {
        VarInts.writeUnsignedLong(buf, this.runtimeId);
        VarInts.writeLong(buf, this.uniqueId);
    }
}
