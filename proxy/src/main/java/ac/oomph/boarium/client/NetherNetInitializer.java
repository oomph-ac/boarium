/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package ac.oomph.boarium.client;

import io.netty.channel.Channel;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Builds a client's pipeline over NetherNet: the RakNet one with NetherNet's framing, and compression and packet header set as for RakNet protocol 11.
 * Adapted from Geyser's NetherNetChannelInitialiser.
 */
final class NetherNetInitializer extends ClientInitializer {
    NetherNetInitializer(boolean requireXbox, Duration loginTimeout, Consumer<ClientLogin> onLogin) {
        this(requireXbox, loginTimeout, ResourcePacks.EMPTY, onLogin);
    }

    // Pass the same startup snapshot to each login, without file I/O on the event loop.
    NetherNetInitializer(boolean requireXbox, Duration loginTimeout, ResourcePacks packs, Consumer<ClientLogin> onLogin) {
        super(requireXbox, loginTimeout, packs, onLogin);
    }

    // Replaces BedrockServerInitializer's, which sets the direction and then builds the RakNet framing, so the direction is set here.
    @Override
    protected void preInitChannel(Channel channel) {
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);
        channel.pipeline()
                .addLast(NetherNetFrameCodec.NAME, NetherNetFrameCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(BedrockChannelInitializer.getCompression(PacketCompressionAlgorithm.ZLIB, NetherNetPeer.RAK_VERSION, true), false));
    }

    // The packet header Cloudburst picks for RakNet protocol 11.
    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new NetherNetPeer(channel, this::createSession);
    }
}
