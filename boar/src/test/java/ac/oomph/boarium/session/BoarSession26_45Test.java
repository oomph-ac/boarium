package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked EveryCheckedVersion, with a 26.45 client, which has the 26.40 blocks. It skips the others.
class BoarSession26_45Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v2169.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(EveryCheckedVersion.class));
    }
}
