package ac.oomph.boarium.protocol;

import io.airlift.compress.snappy.SnappyDecompressor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Reads the frames a downstream server sends: a big-endian uint32 length, a flags byte, then one packet that is Snappy-compressed when the compressed flag is set.
 * Emits a {@link SpectrumFrame} per frame.
 */
public final class SpectrumFrameDecoder extends ByteToMessageDecoder {
    private final SnappyDecompressor decompressor = new SnappyDecompressor();
    private boolean failed;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (this.failed) {
            in.skipBytes(in.readableBytes());
            return;
        }

        try {
            this.readFrame(in, out);
        } catch (RuntimeException e) {
            // The stream cannot be resynced after a bad frame, so drop it and everything after it.
            this.failed = true;
            in.skipBytes(in.readableBytes());
            throw e;
        }
    }

    private void readFrame(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < Integer.BYTES) {
            return;
        }

        long length = in.getUnsignedInt(in.readerIndex());
        if (length < 1 || length > SpectrumFrame.MAX_LENGTH) {
            throw new CorruptedFrameException("invalid frame length " + length);
        }
        if (in.readableBytes() < Integer.BYTES + length) {
            return;
        }

        in.skipBytes(Integer.BYTES);
        int flags = in.readUnsignedByte();
        ByteBuf body = in.readSlice((int) length - 1);

        // Spectrum servers send one packet per frame; only the proxy sends batches.
        if ((flags & SpectrumFrame.FLAG_BATCH) != 0) {
            throw new CorruptedFrameException("unexpected batch frame from server");
        }

        ByteBuf packet = (flags & SpectrumFrame.FLAG_COMPRESSED) != 0 ? this.decompress(body) : body.retain();
        out.add(new SpectrumFrame(packet, (flags & SpectrumFrame.FLAG_DECODE) != 0));
    }

    private ByteBuf decompress(ByteBuf body) {
        byte[] input = ByteBufUtil.getBytes(body);
        int length = SnappyDecompressor.getUncompressedLength(input, 0);
        if (length < 0 || length > SpectrumFrame.MAX_LENGTH) {
            throw new CorruptedFrameException("invalid decompressed length " + length);
        }

        byte[] output = new byte[length];
        int written = this.decompressor.decompress(input, 0, input.length, output, 0, length);
        if (written != length) {
            throw new CorruptedFrameException("decompressed " + written + " bytes, expected " + length);
        }
        return Unpooled.wrappedBuffer(output);
    }
}
