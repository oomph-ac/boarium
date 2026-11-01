package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;

/**
 * The first packet the proxy sends to a server. The server answers with {@link ConnectionResponse}.
 * {@code clientData} and {@code identityData} are the player's login client data and identity data as JSON.
 * {@code cache} is data a server stored for this player with {@link UpdateCache}.
 */
public record ConnectionRequest(String addr, byte[] clientData, byte[] identityData, int protocolId, byte[] cache) implements SpectrumPacket {

    static ConnectionRequest decode(ByteBuf buf) {
        return new ConnectionRequest(Fields.readString(buf), Fields.readBytes(buf), Fields.readBytes(buf), buf.readIntLE(), Fields.readBytes(buf));
    }

    @Override
    public int id() {
        return SpectrumPackets.CONNECTION_REQUEST;
    }

    @Override
    public void encode(ByteBuf buf) {
        Fields.writeString(buf, this.addr);
        Fields.writeBytes(buf, this.clientData);
        Fields.writeBytes(buf, this.identityData);
        buf.writeIntLE(this.protocolId);
        Fields.writeBytes(buf, this.cache);
    }
}
