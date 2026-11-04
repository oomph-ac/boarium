package ac.oomph.boarium.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The Bedrock versions boarium speaks, oldest first, with the game versions of each protocol as Geyser's GameProtocol names them.
 */
public final class BedrockVersions {
    public record Version(BedrockCodec codec, List<String> names) {
    }

    public static final List<Version> SUPPORTED = List.of(
            new Version(Bedrock_v924.CODEC, List.of("26.0", "26.1", "26.2", "26.3")),
            new Version(Bedrock_v944.CODEC, List.of("26.10")),
            new Version(Bedrock_v975.CODEC, List.of("26.20", "26.21", "26.22", "26.23")),
            new Version(Bedrock_v1001.CODEC, List.of("26.30", "26.31", "26.32", "26.33", "26.34")),
            new Version(Bedrock_v2168_hotfix4.CODEC, List.of("26.40", "26.41", "26.42", "26.43", "26.44")),
            new Version(Bedrock_v2169.CODEC, List.of("26.45")),
            new Version(Bedrock_v2193.CODEC, List.of("26.50", "26.51", "26.52")),
            new Version(Bedrock_v2225.CODEC, List.of("26.60")));

    public static final BedrockCodec LATEST = SUPPORTED.getLast().codec();

    private BedrockVersions() {
    }

    /**
     * The codec of {@code protocol}, or null when boarium does not speak it.
     */
    public static BedrockCodec codec(int protocol) {
        for (Version version : SUPPORTED) {
            if (version.codec().getProtocolVersion() == protocol) {
                return version.codec();
            }
        }
        return null;
    }

    /**
     * The status that turns away a client on a protocol boarium does not speak.
     * As in Geyser, the protocol is compared with the latest one only, so a client between two supported protocols is told it is outdated.
     */
    public static PlayStatusPacket.Status refusal(int protocol) {
        return protocol > LATEST.getProtocolVersion() ? PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD : PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD;
    }

    /**
     * Every game version boarium speaks, such as "26.0, 26.1, ..., 26.60".
     */
    public static String names() {
        return SUPPORTED.stream().flatMap(version -> version.names().stream()).collect(Collectors.joining(", "));
    }
}
