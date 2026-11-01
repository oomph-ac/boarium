package ac.oomph.boarium.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * One Bedrock packet (header and body) read from a downstream server, already decompressed.
 * {@code decode} is set when the server asks the proxy to decode the packet instead of passing the bytes through.
 */
public final class SpectrumFrame extends DefaultByteBufHolder {
    static final int FLAG_DECODE = 1;
    static final int FLAG_COMPRESSED = 1 << 1;
    static final int FLAG_BATCH = 1 << 2;

    static final int COMPRESSION_THRESHOLD = 256;
    static final int MAX_LENGTH = 16 * 1024 * 1024;

    private final boolean decode;

    public SpectrumFrame(ByteBuf packet, boolean decode) {
        super(packet);
        this.decode = decode;
    }

    public boolean decode() {
        return this.decode;
    }
}
