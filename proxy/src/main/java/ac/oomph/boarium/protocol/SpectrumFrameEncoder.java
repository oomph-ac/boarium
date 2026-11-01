package ac.oomph.boarium.protocol;

import io.airlift.compress.snappy.SnappyCompressor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Writes frames to a downstream server. Accepts a {@link ByteBuf} holding one packet, or a {@link PacketBatch}.
 * Bodies over {@link SpectrumFrame#COMPRESSION_THRESHOLD} bytes are Snappy-compressed, as Spectrum does. An empty batch writes nothing.
 */
public final class SpectrumFrameEncoder extends MessageToByteEncoder<Object> {
    private final SnappyCompressor compressor = new SnappyCompressor();

    @Override
    public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof ByteBuf || msg instanceof PacketBatch;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
        ByteBuf body;
        int flags;
        if (msg instanceof PacketBatch batch) {
            body = batch.content();
            flags = SpectrumFrame.FLAG_BATCH;
            if (!body.isReadable()) {
                return;
            }
        } else {
            body = (ByteBuf) msg;
            flags = 0;
        }

        if (body.readableBytes() <= SpectrumFrame.COMPRESSION_THRESHOLD) {
            out.writeInt(1 + body.readableBytes());
            out.writeByte(flags);
            out.writeBytes(body, body.readerIndex(), body.readableBytes());
            return;
        }

        byte[] input = ByteBufUtil.getBytes(body);
        byte[] compressed = new byte[this.compressor.maxCompressedLength(input.length)];
        int length = this.compressor.compress(input, 0, input.length, compressed, 0, compressed.length);
        out.writeInt(1 + length);
        out.writeByte(flags | SpectrumFrame.FLAG_COMPRESSED);
        out.writeBytes(compressed, 0, length);
    }
}
