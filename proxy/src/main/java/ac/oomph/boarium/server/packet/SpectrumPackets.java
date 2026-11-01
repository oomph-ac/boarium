package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.cloudburstmc.protocol.common.util.VarInts;

/**
 * Reads and writes Spectrum packets with the Bedrock packet header: a varuint32 holding the packet ID in its low 10 bits.
 */
public final class SpectrumPackets {
    public static final int CONNECTION_REQUEST = 500;
    public static final int CONNECTION_RESPONSE = 501;
    public static final int FLUSH = 502;
    public static final int LATENCY = 503;
    public static final int TRANSFER = 504;
    public static final int UPDATE_CACHE = 505;

    private static final int PACKET_ID_MASK = 0x3ff;

    private SpectrumPackets() {
    }

    public static ByteBuf encode(ByteBufAllocator alloc, SpectrumPacket packet) {
        ByteBuf buf = alloc.buffer();
        try {
            VarInts.writeUnsignedInt(buf, packet.id());
            packet.encode(buf);
            return buf;
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
    }

    /**
     * Returns the Spectrum packet in {@code buf}, or null if it holds another packet. Does not move the reader index of {@code buf}.
     */
    public static SpectrumPacket decode(ByteBuf buf) {
        ByteBuf in = buf.duplicate();
        return switch (VarInts.readUnsignedInt(in) & PACKET_ID_MASK) {
            case CONNECTION_REQUEST -> ConnectionRequest.decode(in);
            case CONNECTION_RESPONSE -> ConnectionResponse.decode(in);
            case FLUSH -> new Flush();
            case LATENCY -> Latency.decode(in);
            case TRANSFER -> Transfer.decode(in);
            case UPDATE_CACHE -> UpdateCache.decode(in);
            default -> null;
        };
    }
}
