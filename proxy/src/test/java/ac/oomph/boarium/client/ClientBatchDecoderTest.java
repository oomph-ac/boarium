package ac.oomph.boarium.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Batches whose bad packet Cloudburst would also refuse, but only after passing on the packets before it.
class ClientBatchDecoderTest {

    private static void assertRefused(ByteBuf batch) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(CompressionCodec.NAME, new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
        channel.pipeline().addLast(new ClientBatchDecoder());

        assertThrows(DecoderException.class, () -> channel.writeInbound(BedrockBatchWrapper.newInstance(batch, null)));
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    private static ByteBuf onePacketThen() {
        ByteBuf batch = Unpooled.buffer();
        VarInts.writeUnsignedInt(batch, 2);
        batch.writeByte(0x81).writeByte(0x01);
        return batch;
    }

    @Test
    void refusesABatchWithAnEmptyPacket() {
        ByteBuf batch = onePacketThen();
        VarInts.writeUnsignedInt(batch, 0);
        assertRefused(batch);
    }

    @Test
    void refusesABatchThatEndsInsideAPacket() {
        ByteBuf batch = onePacketThen();
        VarInts.writeUnsignedInt(batch, 5);
        batch.writeByte(1).writeByte(2);
        assertRefused(batch);
    }
}
