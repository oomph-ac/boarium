package ac.oomph.boarium.client;

import ac.oomph.boarium.protocol.BedrockVersions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A NetherNet channel hands the pipeline each message as a plain ByteBuf and takes one back, which an EmbeddedChannel stands in for.
class NetherNetInitializerTest {
    private static final BedrockCodec CODEC = BedrockVersions.LATEST;
    static final byte ZLIB_HEADER = 0x00;
    private static final BedrockCodecHelper HELPER = CODEC.createHelper();

    private final EmbeddedChannel channel = new EmbeddedChannel(new NetherNetInitializer(false, Duration.ofMinutes(1), login -> {
    }));

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void decodesAClientBatchBeforeAndAfterCompression() throws DataFormatException {
        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(CODEC.getProtocolVersion());
        this.channel.writeInbound(batch(request));

        // LoginHandler answers the request and then turns on compression, so the answer itself goes out uncompressed.
        NetworkSettingsPacket settings = assertInstanceOf(NetworkSettingsPacket.class, decodeBatch(this.channel.readOutbound()).getFirst());
        assertEquals(PacketCompressionAlgorithm.ZLIB, settings.getCompressionAlgorithm());

        List<BedrockPacket> received = new ArrayList<>();
        this.session().setPacketHandler(new BedrockPacketHandler() {
            @Override
            public PacketSignal handlePacket(BedrockPacket packet) {
                received.add(packet);
                return PacketSignal.HANDLED;
            }
        });
        ClientCacheStatusPacket status = new ClientCacheStatusPacket();
        status.setSupported(true);
        this.channel.writeInbound(Unpooled.buffer().writeByte(ZLIB_HEADER).writeBytes(deflate(batch(status))));

        assertEquals(1, received.size());
        assertTrue(assertInstanceOf(ClientCacheStatusPacket.class, received.getFirst()).isSupported());
        assertNull(this.channel.readOutbound());
    }

    @Test
    void encodesAServerBatchAsOneMessageBeforeAndAfterCompression() throws DataFormatException {
        this.session().setCodec(CODEC);
        PlayStatusPacket success = new PlayStatusPacket();
        success.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        PlayStatusPacket spawn = new PlayStatusPacket();
        spawn.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        ByteBuf expected = batch(success, spawn);

        this.writeAsSession(success, spawn);
        ByteBuf plain = this.channel.readOutbound();
        assertTrue(ByteBufUtil.equals(expected, plain));
        plain.release();
        assertNull(this.channel.readOutbound());

        this.session().setCompression(PacketCompressionAlgorithm.ZLIB);
        this.writeAsSession(success, spawn);
        ByteBuf compressed = this.channel.readOutbound();
        assertEquals(ZLIB_HEADER, compressed.readByte());
        assertTrue(ByteBufUtil.equals(expected, inflate(compressed)));
        compressed.release();
        assertNull(this.channel.readOutbound());
    }

    @Test
    void setsCompressionAsOnRakNet11() {
        BedrockPeer peer = this.session().getPeer();
        assertInstanceOf(NetherNetPeer.class, peer);
        assertEquals(11, peer.getRakVersion());
        assertEquals(PacketCompressionAlgorithm.NONE, peer.getCompressionStrategy().getDefaultCompression().getAlgorithm());

        this.session().setCodec(CODEC);
        peer.setCompression(PacketCompressionAlgorithm.SNAPPY);
        assertEquals(PacketCompressionAlgorithm.SNAPPY, peer.getCompressionStrategy().getDefaultCompression().getAlgorithm());
    }

    private BedrockServerSession session() {
        return this.channel.attr(ClientInitializer.SESSION).get();
    }

    // Writes the packets as Session does, already encoded, then flushes once.
    private void writeAsSession(BedrockPacket... packets) {
        for (BedrockPacket packet : packets) {
            this.channel.write(BedrockPacketWrapper.create(0, 0, 0, null, encode(packet)));
        }
        this.channel.flush();
    }

    private static ByteBuf encode(BedrockPacket packet) {
        ByteBuf buf = Unpooled.buffer();
        VarInts.writeUnsignedInt(buf, CODEC.getPacketDefinition(packet.getClass()).getId());
        CODEC.tryEncode(HELPER, buf, packet);
        return buf;
    }

    static ByteBuf batch(BedrockPacket... packets) {
        ByteBuf batch = Unpooled.buffer();
        for (BedrockPacket packet : packets) {
            ByteBuf encoded = encode(packet);
            VarInts.writeUnsignedInt(batch, encoded.readableBytes());
            batch.writeBytes(encoded);
            encoded.release();
        }
        return batch;
    }

    static List<BedrockPacket> decodeBatch(ByteBuf batch) {
        List<BedrockPacket> packets = new ArrayList<>();
        while (batch.isReadable()) {
            ByteBuf packet = batch.readSlice(VarInts.readUnsignedInt(batch));
            packets.add(CODEC.tryDecode(HELPER, packet, VarInts.readUnsignedInt(packet), PacketRecipient.CLIENT));
        }
        batch.release();
        return packets;
    }

    // Cloudburst's ZLIB compression is raw deflate, without zlib's header.
    static byte[] deflate(ByteBuf in) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(ByteBufUtil.getBytes(in));
        deflater.finish();
        byte[] out = new byte[in.readableBytes() + 64];
        int length = deflater.deflate(out);
        deflater.end();
        return Arrays.copyOf(out, length);
    }

    static ByteBuf inflate(ByteBuf in) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(ByteBufUtil.getBytes(in));
        ByteBuf out = Unpooled.buffer();
        byte[] chunk = new byte[1024];
        while (!inflater.finished()) {
            int length = inflater.inflate(chunk);
            if (length == 0 && inflater.needsInput()) {
                break;
            }
            out.writeBytes(chunk, 0, length);
        }
        inflater.end();
        return out;
    }
}
