package ac.oomph.boarium.server.packet;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.nio.charset.StandardCharsets;

// Byte slices and strings as gophertunnel writes them: a varuint32 length, then the bytes.
final class Fields {

    private Fields() {
    }

    static byte[] readBytes(ByteBuf buf) {
        int length = VarInts.readUnsignedInt(buf);
        if (length < 0 || length > buf.readableBytes()) {
            throw new CorruptedFrameException("byte slice length " + Integer.toUnsignedString(length) + " exceeds the " + buf.readableBytes() + " bytes left");
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    static void writeBytes(ByteBuf buf, byte[] bytes) {
        VarInts.writeUnsignedInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    static String readString(ByteBuf buf) {
        return new String(readBytes(buf), StandardCharsets.UTF_8);
    }

    static void writeString(ByteBuf buf, String value) {
        writeBytes(buf, value.getBytes(StandardCharsets.UTF_8));
    }
}
