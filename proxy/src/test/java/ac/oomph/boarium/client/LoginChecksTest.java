package ac.oomph.boarium.client;

import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Checks that a login from the test client cannot reach: the rules for identities signed by Xbox Live, and the cpk check, since Cloudburst also turns away a login without a cpk.
class LoginChecksTest {

    private static Identity identity(String name, String xuid) {
        return new Identity(name, UUID.randomUUID(), xuid, "");
    }

    @Test
    void acceptsAnXboxIdentity() {
        assertDoesNotThrow(() -> LoginChecks.checkIdentity(identity("Gamer Tag 15", "2535400000000000"), true));
    }

    @Test
    void appliesTheOnlineRules() {
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("SixteenCharsLong", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer", "25354abc"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("ééé", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer", ""), true));
    }

    // gophertunnel's offline rule only asks for one letter, so without this a name could carry a line break into the log.
    @Test
    void turnsAwayANameWithACharacterThatIsNotPrintable() {
        assertDoesNotThrow(() -> LoginChecks.checkIdentity(identity("Steve Two", ""), false));
        for (String name : new String[]{"Steve\nFake", "Steve\rX", "A\u0000B", "A\u200EB", "A\u2028B", "A\u2029B"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity(name, ""), false), name);
            assertEquals("display name has a character that is not printable", e.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer\nX", "2535400000000000"), true));
        // Some scripts' gamertags need a joiner, which is an invisible format character, so an Xbox name may hold one, but no other.
        assertDoesNotThrow(() -> LoginChecks.checkIdentity(identity("Gamer\u200DX", "2535400000000000"), true));
        assertDoesNotThrow(() -> LoginChecks.checkIdentity(identity("Gamer\u200CX", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer\u202EX", "2535400000000000"), true));
        assertThrows(IllegalStateException.class, () -> LoginChecks.checkIdentity(identity("Gamer\u200DX", ""), false));
    }

    @Test
    void turnsAwayASelfSignedTokenWithoutAKey() throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setPayload(JsonUtil.toJson(Map.of("xname", "Tester", "leguuid", UUID.randomUUID().toString())));
        jws.setKey(EncryptionUtils.createKeyPair().getPrivate());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LoginChecks.checkSelfSignedToken(jws.getCompactSerialization(), System.currentTimeMillis() / 1000));
        assertEquals("self-signed token has no cpk", e.getMessage());
    }
}
