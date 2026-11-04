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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BedrockVersionsTest {
    private static final List<BedrockCodec> CODECS = List.of(
            Bedrock_v924.CODEC, Bedrock_v944.CODEC, Bedrock_v975.CODEC, Bedrock_v1001.CODEC,
            Bedrock_v2168_hotfix4.CODEC, Bedrock_v2169.CODEC, Bedrock_v2193.CODEC, Bedrock_v2225.CODEC);

    @Test
    void listsTheEightCodecsInProtocolOrder() {
        assertEquals(CODECS, BedrockVersions.SUPPORTED.stream().map(BedrockVersions.Version::codec).toList());
        assertSame(Bedrock_v2225.CODEC, BedrockVersions.LATEST);
        assertEquals(List.of(924, 944, 975, 1001, 2168, 2169, 2193, 2225), CODECS.stream().map(BedrockCodec::getProtocolVersion).toList());
    }

    @Test
    void picksTheCodecOfEachSupportedProtocol() {
        for (BedrockCodec codec : CODECS) {
            assertSame(codec, BedrockVersions.codec(codec.getProtocolVersion()));
        }
    }

    @Test
    void tellsAClientBelowTheLowestThatItIsOld() {
        assertNull(BedrockVersions.codec(923));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, BedrockVersions.refusal(923));
    }

    @Test
    void tellsAClientAboveTheHighestThatTheServerIsOld() {
        assertNull(BedrockVersions.codec(2226));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD, BedrockVersions.refusal(2226));
    }

    @Test
    void tellsAClientBetweenTwoSupportedProtocolsThatItIsOld() {
        for (int protocol : new int[]{925, 1000, 1002, 2167, 2224}) {
            assertNull(BedrockVersions.codec(protocol));
            assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, BedrockVersions.refusal(protocol));
        }
    }

    @Test
    void namesEveryGameVersion() {
        assertEquals("26.0, 26.1, 26.2, 26.3, 26.10, 26.20, 26.21, 26.22, 26.23, 26.30, 26.31, 26.32, 26.33, 26.34, 26.40, 26.41, 26.42, 26.43, 26.44, 26.45, 26.50, 26.51, 26.52, 26.60", BedrockVersions.names());
    }
}
