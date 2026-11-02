package ac.oomph.boarium.session;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * A packet on its way through a {@link Processor}, as spectrum-internal's PacketContext.
 * {@link #packet()} is set when the packet was decoded, and {@link #payload()} when it came as bytes; a packet can have both.
 * A cancelled packet is dropped; a modified one is encoded again from {@link #packet()} instead of passing on as its bytes.
 */
public final class PacketContext {
    private final ByteBuf payload;
    private final BedrockPacket packet;
    private boolean cancelled;
    private boolean modified;

    PacketContext(ByteBuf payload, BedrockPacket packet) {
        this.payload = payload;
        this.packet = packet;
    }

    /**
     * The decoded packet, or null when the packet was not decoded. Only valid during the processor's call; it can hold a slice of the payload, so retain it to keep it.
     */
    public BedrockPacket packet() {
        return this.packet;
    }

    /**
     * The packet's bytes, header and body, or null when the packet came decoded. Only valid during the processor's call.
     */
    public ByteBuf payload() {
        return this.payload;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /**
     * Marks a decoded packet as changed, so that it is encoded again.
     *
     * @throws IllegalStateException if the packet was not decoded
     */
    public void setModified() {
        if (this.packet == null) {
            throw new IllegalStateException("only a decoded packet can be modified");
        }
        this.modified = true;
    }

    public boolean modified() {
        return this.modified;
    }
}
