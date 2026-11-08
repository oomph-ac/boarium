package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked EveryCheckedVersion, with a 26.60 client, which has the 26.50 fences and stairs and a chorus plant that holds its connections in its state. It skips the others.
class BoarSession26_60Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v2225.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(EveryCheckedVersion.class));
    }
}
