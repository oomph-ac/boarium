package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked EveryCheckedVersion, with a 26.20 client, whose block runtime IDs are not 1.26.30's since it lacks the sulfur spike and has one potent sulfur state. It skips the others.
class BoarSession26_20Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v975.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(EveryCheckedVersion.class));
    }
}
