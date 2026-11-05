package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked EveryCheckedVersion, with a 26.40 client, whose block runtime IDs are not 1.26.30's since its new blocks sort among them. It skips the others.
class BoarSession26_40Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v2168_hotfix4.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(EveryCheckedVersion.class));
    }
}
