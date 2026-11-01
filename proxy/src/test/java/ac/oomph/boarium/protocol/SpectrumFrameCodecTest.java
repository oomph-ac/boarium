package ac.oomph.boarium.protocol;

import io.airlift.compress.snappy.SnappyCompressor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumFrameCodecTest {
    // Written by golang/snappy and Spectrum's frame layout: 1000 bytes of i % 7, flags decode | compressed.
    private static final String GO_COMPRESSED_FRAME = "0000003b03e8071800010203040506fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700fe0700820700";

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 7);
        }
        return bytes;
    }

    private static ByteBuf frame(int flags, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(1 + body.length);
        buf.writeByte(flags);
        buf.writeBytes(body);
        return buf;
    }

    private static byte[] readFrame(EmbeddedChannel channel, boolean expectDecode) {
        SpectrumFrame frame = channel.readInbound();
        try {
            assertEquals(expectDecode, frame.decode());
            return ByteBufUtil.getBytes(frame.content());
        } finally {
            frame.release();
        }
    }

    private static byte[] readOutbound(EmbeddedChannel channel) {
        ByteBuf buf = channel.readOutbound();
        try {
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void decodesGoCompressedFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameDecoder());
        channel.writeInbound(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(GO_COMPRESSED_FRAME)));

        assertArrayEquals(pattern(1000), readFrame(channel, true));
        assertFalse(channel.finish());
    }

    @Test
    void decodesUncompressedFrameSplitAcrossReads() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameDecoder());
        ByteBuf whole = frame(0, new byte[]{9, 8, 7});

        channel.writeInbound(whole.readRetainedSlice(2));
        channel.writeInbound(whole.readRetainedSlice(3));
        assertNull(channel.readInbound());
        channel.writeInbound(whole);

        assertArrayEquals(new byte[]{9, 8, 7}, readFrame(channel, false));
        assertFalse(channel.finish());
    }

    @Test
    void decodesSeveralFramesFromOneRead() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameDecoder());
        channel.writeInbound(Unpooled.wrappedBuffer(frame(1, new byte[]{1}), frame(0, new byte[]{2, 2})));

        assertArrayEquals(new byte[]{1}, readFrame(channel, true));
        assertArrayEquals(new byte[]{2, 2}, readFrame(channel, false));
        assertFalse(channel.finish());
    }

    private static DecoderException assertRejected(ByteBuf input) {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameDecoder());
        DecoderException error = assertThrows(DecoderException.class, () -> channel.writeInbound(input));
        assertEquals(0, input.refCnt());
        assertFalse(channel.finish());
        return error;
    }

    @Test
    void rejectsBadLengthsBeforeBufferingTheBody() {
        assertInstanceOf(CorruptedFrameException.class, assertRejected(Unpooled.buffer().writeInt(0)));
        assertInstanceOf(CorruptedFrameException.class, assertRejected(Unpooled.buffer().writeInt(SpectrumFrame.MAX_LENGTH + 1)));
        assertInstanceOf(CorruptedFrameException.class, assertRejected(Unpooled.buffer().writeInt(-1)));
    }

    @Test
    void dropsBytesInTheSameReadAfterABadFrame() {
        ByteBuf input = frame(SpectrumFrame.FLAG_BATCH, new byte[]{1}).writeInt(0);
        assertRejected(input);
    }

    @Test
    void ignoresLaterReadsAfterABadFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameDecoder());
        assertThrows(DecoderException.class, () -> channel.writeInbound(Unpooled.buffer().writeInt(0).writeByte(7)));

        ByteBuf good = frame(SpectrumFrame.FLAG_DECODE, new byte[]{42});
        channel.writeInbound(good);
        assertNull(channel.readInbound());
        assertEquals(0, good.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void rejectsBatchFrameFromServer() {
        assertRejected(frame(SpectrumFrame.FLAG_BATCH, new byte[]{1}));
    }

    @Test
    void rejectsCorruptSnappy() {
        assertRejected(frame(SpectrumFrame.FLAG_COMPRESSED, new byte[]{10, (byte) 0xff, 1}));
    }

    @Test
    void rejectsDecompressedLengthOverCap() {
        byte[] packet = new byte[SpectrumFrame.MAX_LENGTH + 1];
        SnappyCompressor compressor = new SnappyCompressor();
        byte[] compressed = new byte[compressor.maxCompressedLength(packet.length)];
        int length = compressor.compress(packet, 0, packet.length, compressed, 0, compressed.length);
        assertRejected(frame(SpectrumFrame.FLAG_COMPRESSED, Arrays.copyOf(compressed, length)));
    }

    @Test
    void writesSmallPacketUncompressed() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[]{4, 5}));

        assertArrayEquals(new byte[]{0, 0, 0, 3, 0, 4, 5}, readOutbound(channel));
        assertFalse(channel.finish());
    }

    @Test
    void writesPacketAtThresholdUncompressed() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        channel.writeOutbound(Unpooled.wrappedBuffer(pattern(SpectrumFrame.COMPRESSION_THRESHOLD)));
        byte[] written = readOutbound(channel);

        assertEquals(0, written[4]);
        assertEquals(5 + SpectrumFrame.COMPRESSION_THRESHOLD, written.length);
        assertFalse(channel.finish());
    }

    @Test
    void writesFromTheReaderIndex() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[]{9, 9, 4, 5}).skipBytes(2));
        assertArrayEquals(new byte[]{0, 0, 0, 3, 0, 4, 5}, readOutbound(channel));

        ByteBuf packet = Unpooled.wrappedBuffer(new byte[]{9, 7}).skipBytes(1);
        channel.writeOutbound(PacketBatch.of(channel.alloc(), List.of(packet)));
        assertArrayEquals(new byte[]{0, 0, 0, 6, SpectrumFrame.FLAG_BATCH, 1, 0, 0, 0, 7}, readOutbound(channel));
        assertEquals(1, packet.readerIndex());
        assertFalse(channel.finish());
    }

    @Test
    void compressesPacketOverThreshold() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        byte[] packet = pattern(SpectrumFrame.COMPRESSION_THRESHOLD + 1);
        channel.writeOutbound(Unpooled.wrappedBuffer(packet));
        byte[] written = readOutbound(channel);

        assertEquals(SpectrumFrame.FLAG_COMPRESSED, written[4]);
        assertTrue(written.length < packet.length);

        EmbeddedChannel decoder = new EmbeddedChannel(new SpectrumFrameDecoder());
        decoder.writeInbound(Unpooled.wrappedBuffer(written));
        assertArrayEquals(packet, readFrame(decoder, false));
        assertFalse(channel.finish());
        assertFalse(decoder.finish());
    }

    @Test
    void writesBatchWithLittleEndianLengths() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        ByteBuf first = Unpooled.wrappedBuffer(new byte[]{1});
        ByteBuf second = Unpooled.wrappedBuffer(new byte[]{2, 3});
        channel.writeOutbound(PacketBatch.of(channel.alloc(), List.of(first, second)));

        assertEquals(1, first.refCnt());
        assertEquals(0, first.readerIndex());
        assertArrayEquals(new byte[]{0, 0, 0, 12, SpectrumFrame.FLAG_BATCH, 1, 0, 0, 0, 1, 2, 0, 0, 0, 2, 3}, readOutbound(channel));
        assertFalse(channel.finish());
    }

    @Test
    void compressesBatchOnlyOverThreshold() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        ByteBuf atThreshold = Unpooled.wrappedBuffer(pattern(SpectrumFrame.COMPRESSION_THRESHOLD - Integer.BYTES));
        channel.writeOutbound(PacketBatch.of(channel.alloc(), List.of(atThreshold)));
        assertEquals(SpectrumFrame.FLAG_BATCH, readOutbound(channel)[4]);

        ByteBuf overThreshold = Unpooled.wrappedBuffer(pattern(SpectrumFrame.COMPRESSION_THRESHOLD - Integer.BYTES + 1));
        channel.writeOutbound(PacketBatch.of(channel.alloc(), List.of(overThreshold)));
        assertEquals(SpectrumFrame.FLAG_BATCH | SpectrumFrame.FLAG_COMPRESSED, readOutbound(channel)[4]);
        assertFalse(channel.finish());
    }

    @Test
    void writesNothingForEmptyBatch() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        PacketBatch batch = PacketBatch.of(channel.alloc(), List.of());
        channel.writeOutbound(batch);

        assertEquals(0, readOutbound(channel).length);
        assertEquals(0, batch.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void passesOtherMessagesThrough() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpectrumFrameEncoder());
        channel.writeOutbound("not a frame");

        assertEquals("not a frame", channel.readOutbound());
        assertFalse(channel.finish());
    }
}
