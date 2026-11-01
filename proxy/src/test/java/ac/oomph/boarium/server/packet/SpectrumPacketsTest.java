package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectrumPacketsTest {
    // Each hex string is what spectrum-internal's server/packet types write for the same values, header included.
    private static final String CONNECTION_REQUEST = "f4030f3132372e302e302e313a3139313332077b2261223a317d077b2262223a327de903000002dead";
    private static final String CONNECTION_RESPONSE = "f50380808080802009";
    private static final String FLUSH = "f603";
    private static final String LATENCY = "f7032a000000000000007ba8da769b010000";
    private static final String TRANSFER = "f8030b6c6f6262793a3139313333";
    private static final String TRANSFER_NON_ASCII = "f8030f636166c3a9f09f99823a3139313333";
    private static final String UPDATE_CACHE = "f90308c3a9206361636865";

    private static ByteBuf hex(String hex) {
        return Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
    }

    private static String encode(SpectrumPacket packet) {
        ByteBuf buf = SpectrumPackets.encode(ByteBufAllocator.DEFAULT, packet);
        try {
            return ByteBufUtil.hexDump(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void connectionRequestMatchesGo() {
        ConnectionRequest request = new ConnectionRequest("127.0.0.1:19132", "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "{\"b\":2}".getBytes(StandardCharsets.UTF_8), 1001, new byte[]{(byte) 0xde, (byte) 0xad});
        assertEquals(CONNECTION_REQUEST, encode(request));

        ConnectionRequest decoded = assertInstanceOf(ConnectionRequest.class, SpectrumPackets.decode(hex(CONNECTION_REQUEST)));
        assertEquals(request.addr(), decoded.addr());
        assertArrayEquals(request.clientData(), decoded.clientData());
        assertArrayEquals(request.identityData(), decoded.identityData());
        assertEquals(request.protocolId(), decoded.protocolId());
        assertArrayEquals(request.cache(), decoded.cache());
    }

    @Test
    void connectionResponseMatchesGo() {
        ConnectionResponse response = new ConnectionResponse(1L << 40, -5);
        assertEquals(CONNECTION_RESPONSE, encode(response));
        assertEquals(response, SpectrumPackets.decode(hex(CONNECTION_RESPONSE)));
    }

    @Test
    void flushMatchesGo() {
        assertEquals(FLUSH, encode(new Flush()));
        assertEquals(new Flush(), SpectrumPackets.decode(hex(FLUSH)));
    }

    @Test
    void latencyMatchesGo() {
        Latency latency = new Latency(42, 1767225600123L);
        assertEquals(LATENCY, encode(latency));
        assertEquals(latency, SpectrumPackets.decode(hex(LATENCY)));
    }

    @Test
    void transferMatchesGo() {
        Transfer transfer = new Transfer("lobby:19133");
        assertEquals(TRANSFER, encode(transfer));
        assertEquals(transfer, SpectrumPackets.decode(hex(TRANSFER)));
    }

    @Test
    void nonAsciiStringMatchesGo() {
        Transfer transfer = new Transfer("café🙂:19133");
        assertEquals(TRANSFER_NON_ASCII, encode(transfer));
        assertEquals(transfer, SpectrumPackets.decode(hex(TRANSFER_NON_ASCII)));
    }

    @Test
    void updateCacheMatchesGo() {
        byte[] cache = "é cache".getBytes(StandardCharsets.UTF_8);
        assertEquals(UPDATE_CACHE, encode(new UpdateCache(cache)));
        assertArrayEquals(cache, assertInstanceOf(UpdateCache.class, SpectrumPackets.decode(hex(UPDATE_CACHE))).cache());
    }

    @Test
    void returnsNullForOtherPacketsWithoutMovingTheReaderIndex() {
        // 0x0b is StartGame.
        ByteBuf startGame = Unpooled.wrappedBuffer(new byte[]{0x0b, 1, 2});
        assertNull(SpectrumPackets.decode(startGame));
        assertEquals(0, startGame.readerIndex());

        ByteBuf transfer = hex(TRANSFER);
        SpectrumPackets.decode(transfer);
        assertEquals(0, transfer.readerIndex());
    }

    @Test
    void ignoresSubClientBitsInTheHeader() {
        // ID 502 with sender and target sub-client IDs 1: 502 | 1 << 10 | 1 << 12 = 0x15f6.
        assertEquals(new Flush(), SpectrumPackets.decode(Unpooled.wrappedBuffer(new byte[]{(byte) 0xf6, 0x2b})));
    }

    @Test
    void rejectsByteSliceLongerThanThePacket() {
        // UpdateCache claiming 9 bytes of cache with 8 left.
        assertThrows(CorruptedFrameException.class, () -> SpectrumPackets.decode(hex("f90309c3a9206361636865")));
    }

    @Test
    void rejectsByteSliceLengthOverIntMax() {
        // UpdateCache claiming 2^32 - 1 bytes of cache.
        assertThrows(CorruptedFrameException.class, () -> SpectrumPackets.decode(hex("f903ffffffff0f")));
    }

    @Test
    void rejectsTruncatedFixedFields() {
        assertThrows(IndexOutOfBoundsException.class, () -> SpectrumPackets.decode(hex(LATENCY.substring(0, LATENCY.length() - 2))));
    }
}
