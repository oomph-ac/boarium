package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked EveryCheckedVersion, with a 26.50 client, whose fences, panes, bars and stairs hold their connections and corners in their states. It skips the others.
class BoarSession26_50Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v2193.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(EveryCheckedVersion.class));
    }
}
