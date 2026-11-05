package ac.oomph.boarium.session;

import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The tests of BoarSessionTest marked On26_50, with a 26.50 client, whose fences, panes, bars and stairs hold their connections and corners in their states.
// Boar checks 26.50 players only in the tests. This class skips the others.
class BoarSession26_50Test extends BoarSessionTest {
    @Override
    BedrockCodec codec() {
        return Bedrock_v2193.CODEC;
    }

    @BeforeEach
    void onlyTheMarkedTests(TestInfo test) {
        assumeTrue(test.getTestMethod().orElseThrow().isAnnotationPresent(On26_50.class));
        TestBoar.check(this.codec().getProtocolVersion(), true);
    }

    @AfterEach
    void stopChecking() {
        TestBoar.check(this.codec().getProtocolVersion(), false);
    }
}
