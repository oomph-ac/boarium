package ac.oomph.boarium.client;

import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// The rules for identities signed by Xbox Live, which an offline test client cannot reach through a login.
class LoginChecksTest {

    private static ChainValidationResult.IdentityData identity(String name, String xuid) throws Exception {
        Map<String, Object> claims = Map.of(
                "extraData", Map.of("displayName", name, "identity", UUID.randomUUID().toString(), "XUID", xuid),
                "identityPublicKey", "unused");
        return new ChainValidationResult(true, claims).identityClaims().extraData;
    }

    @Test
    void acceptsAnXboxIdentity() {
        assertDoesNotThrow(() -> LoginChecks.checkIdentity(identity("Gamer Tag 15", "2535400000000000"), true));
    }

    @Test
    void appliesTheOnlineRules() throws Exception {
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("SixteenCharsLong", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer", "25354abc"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("ééé", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer", ""), true));
    }
}
