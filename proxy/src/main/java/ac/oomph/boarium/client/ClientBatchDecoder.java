package ac.oomph.boarium.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cloudburst's batch decoder with the checks gophertunnel's packet.Decoder makes on a client's batches, so a batch that Spectrum would refuse is refused before any of its packets are handled.
 */
public final class ClientBatchDecoder extends BedrockBatchDecoder {
    // gophertunnel's maximumInBatch.
    static final int MAX_PACKETS = 812;
    // A packet's header holds its ID in the low 10 bits, then 2 bits each for the sender and target player on the connection.
    private static final int ID_MASK = 0x3ff;

    private int spawnPacketId = -1;
    private boolean holding;
    private final List<ByteBuf> held = new ArrayList<>();
    private Consumer<List<ByteBuf>> forward;

    ClientBatchDecoder() {
    }

    /**
     * Keeps the packets the client sends after its SetLocalPlayerAsInitialized, including the rest of that packet's batch, until {@link #forwardBatches} hands them on; a second player's packets are dropped.
     * The packets before it, and that packet, are decoded as before. Call it on the client's event loop.
     */
    public static void holdAfterSpawn(BedrockServerSession session) {
        ClientBatchDecoder decoder = session.getPeer().getChannel().pipeline().get(ClientBatchDecoder.class);
        decoder.spawnPacketId = session.getCodec().getPacketDefinition(SetLocalPlayerAsInitializedPacket.class).getId();
    }

    /**
     * Hands each of the client's batches to {@code forward} as its packets (header and body) instead of decoding them, which lets a batch go on to the server as one. The packets {@link #holdAfterSpawn} kept go first, together as one batch.
     * Packets for a second player on the same connection are dropped. The packets are only valid during the call. Call it on the client's event loop.
     */
    public static void forwardBatches(BedrockServerSession session, Consumer<List<ByteBuf>> forward) {
        ClientBatchDecoder decoder = session.getPeer().getChannel().pipeline().get(ClientBatchDecoder.class);
        decoder.forward = forward;
        decoder.holding = false;
        if (!decoder.held.isEmpty()) {
            try {
                forward.accept(decoder.held);
            } finally {
                decoder.releaseHeld();
            }
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        CompressionAlgorithm agreed = ctx.pipeline().get(CompressionCodec.class).getStrategy().getDefaultCompression().getAlgorithm();
        if (msg.getAlgorithm() != agreed && msg.getAlgorithm() != PacketCompressionAlgorithm.NONE) {
            throw new CorruptedFrameException("batch compressed with " + msg.getAlgorithm() + ", expected " + agreed);
        }

        if (msg.getUncompressed() == null) {
            super.decode(ctx, msg, out);
            return;
        }
        ByteBuf buffer = msg.getUncompressed().slice();
        List<ByteBuf> packets = new ArrayList<>();
        while (buffer.isReadable()) {
            int length = VarInts.readUnsignedInt(buffer);
            if (length == 0) {
                throw new CorruptedFrameException("batch holds an empty packet");
            }
            if (packets.size() == MAX_PACKETS) {
                throw new CorruptedFrameException("batch holds more than " + MAX_PACKETS + " packets");
            }
            // Throws when the batch ends inside this packet.
            packets.add(buffer.readSlice(length));
        }

        if (this.forward != null) {
            List<ByteBuf> primary = packets.stream().filter(ClientBatchDecoder::isPrimary).toList();
            if (!primary.isEmpty()) {
                this.forward.accept(primary);
            }
            return;
        }
        for (ByteBuf packet : packets) {
            if (this.holding) {
                if (isPrimary(packet)) {
                    this.held.add(packet.retainedSlice());
                }
                continue;
            }
            // As BedrockBatchDecoder passes on each packet.
            out.add(packet.retainedSlice());
            if (this.spawnPacketId != -1 && isPrimary(packet) && (VarInts.readUnsignedInt(packet.duplicate()) & ID_MASK) == this.spawnPacketId) {
                this.holding = true;
            }
        }
    }

    private static boolean isPrimary(ByteBuf packet) {
        int header = VarInts.readUnsignedInt(packet.duplicate());
        return (header >> 10 & 3) == 0 && (header >> 12 & 3) == 0;
    }

    // BedrockBatchDecoder is shared between channels, but this decoder keeps state for one.
    @Override
    public boolean isSharable() {
        return false;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.releaseHeld();
    }

    private void releaseHeld() {
        this.held.forEach(ByteBuf::release);
        this.held.clear();
    }
}
