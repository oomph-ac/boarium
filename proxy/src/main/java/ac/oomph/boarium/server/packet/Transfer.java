package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * Sent by a server to move the player to the server at {@code addr}.
 */
public record Transfer(String addr) implements SpectrumPacket {

    static Transfer decode(ByteBuf buf) {
        return new Transfer(Fields.readString(buf));
    }

    @Override
    public int id() {
        return SpectrumPackets.TRANSFER;
    }

    @Override
    public void encode(ByteBuf buf) {
        Fields.writeString(buf, this.addr);
    }
}
