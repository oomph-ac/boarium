package ac.oomph.boarium.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

/**
 * Cloudburst's batch decoder with the checks gophertunnel's packet.Decoder makes on a client's batches, so a batch that Spectrum would refuse is refused before any of its packets are handled.
 */
final class ClientBatchDecoder extends BedrockBatchDecoder {
    // gophertunnel's maximumInBatch.
    static final int MAX_PACKETS = 812;

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        CompressionAlgorithm agreed = ctx.pipeline().get(CompressionCodec.class).getStrategy().getDefaultCompression().getAlgorithm();
        if (msg.getAlgorithm() != agreed && msg.getAlgorithm() != PacketCompressionAlgorithm.NONE) {
            throw new CorruptedFrameException("batch compressed with " + msg.getAlgorithm() + ", expected " + agreed);
        }

        if (msg.getUncompressed() != null) {
            ByteBuf buffer = msg.getUncompressed().slice();
            int packets = 0;
            while (buffer.isReadable()) {
                int length = VarInts.readUnsignedInt(buffer);
                if (length == 0) {
                    throw new CorruptedFrameException("batch holds an empty packet");
                }
                if (++packets > MAX_PACKETS) {
                    throw new CorruptedFrameException("batch holds more than " + MAX_PACKETS + " packets");
                }
                // Throws when the batch ends inside this packet.
                buffer.skipBytes(length);
            }
        }
        super.decode(ctx, msg, out);
    }
}
