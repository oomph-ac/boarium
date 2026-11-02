package ac.oomph.boarium.server;

import ac.oomph.boarium.protocol.SpectrumFrame;
import ac.oomph.boarium.server.packet.SpectrumPacket;
import ac.oomph.boarium.server.packet.SpectrumPackets;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.List;

/**
 * Turns each frame from a downstream server into a {@link SpectrumPacket}, a decoded {@link BedrockPacket} when the server set the decode flag, or else a {@link ByteBuf} with the packet's bytes.
 * Encodes outgoing Spectrum and Bedrock packets with their header here in the pipeline, so the codec helper is only used on the channel's event loop.
 */
final class ServerPacketCodec extends MessageToMessageCodec<SpectrumFrame, Object> {
    private final BedrockCodec codec;
    private final BedrockCodecHelper helper;

    ServerPacketCodec(BedrockCodec codec, BedrockCodecHelper helper) {
        this.codec = codec;
        this.helper = helper;
    }

    @Override
    public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof SpectrumPacket || msg instanceof BedrockPacket;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        if (msg instanceof SpectrumPacket packet) {
            out.add(SpectrumPackets.encode(ctx.alloc(), packet));
            return;
        }

        BedrockPacket packet = (BedrockPacket) msg;
        ByteBuf buf = ctx.alloc().buffer();
        try {
            VarInts.writeUnsignedInt(buf, this.packetId(packet));
            this.codec.tryEncode(this.helper, buf, packet);
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
        out.add(buf);
    }

    private int packetId(BedrockPacket packet) {
        if (packet instanceof UnknownPacket unknown) {
            return unknown.getPacketId();
        }

        BedrockPacketDefinition<? extends BedrockPacket> definition = this.codec.getPacketDefinition(packet.getClass());
        if (definition == null) {
            throw new IllegalArgumentException(packet.getClass().getSimpleName() + " is not in protocol " + this.codec.getProtocolVersion());
        }
        return definition.getId();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, SpectrumFrame frame, List<Object> out) {
        ByteBuf packet = frame.content();
        SpectrumPacket spectrumPacket = packet.isReadable() ? SpectrumPackets.decode(packet) : null;
        if (spectrumPacket != null) {
            out.add(spectrumPacket);
        } else if (frame.decode()) {
            ByteBuf in = packet.duplicate();
            int id = VarInts.readUnsignedInt(in) & SpectrumPackets.PACKET_ID_MASK;
            out.add(this.codec.tryDecode(this.helper, in, id, PacketRecipient.CLIENT));
        } else {
            out.add(packet.retain());
        }
    }
}
