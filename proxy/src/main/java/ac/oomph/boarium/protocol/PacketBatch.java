package ac.oomph.boarium.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.DefaultByteBufHolder;

import java.util.List;

/**
 * Several Bedrock packets sent to a downstream server in one Spectrum frame.
 * The body is each packet prefixed by its length as a little-endian uint32, as Spectrum's {@code Conn.WriteBatch} writes it.
 */
public final class PacketBatch extends DefaultByteBufHolder {

    private PacketBatch(ByteBuf body) {
        super(body);
    }

    /**
     * Copies the packets into a new batch. The caller keeps ownership of the packets.
     */
    public static PacketBatch of(ByteBufAllocator alloc, List<ByteBuf> packets) {
        int size = 0;
        for (ByteBuf packet : packets) {
            size += Integer.BYTES + packet.readableBytes();
        }

        ByteBuf body = alloc.buffer(size);
        for (ByteBuf packet : packets) {
            body.writeIntLE(packet.readableBytes());
            body.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
        }
        return new PacketBatch(body);
    }
}
