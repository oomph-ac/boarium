package ac.oomph.boarium.client;

import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identity checks gophertunnel's login.Parse makes that Cloudburst's EncryptionUtils does not, one of them stricter than gophertunnel's. Each throws IllegalStateException when a check fails.
 */
final class LoginChecks {
    // go-jose's default leeway for the time claims.
    private static final long LEEWAY_SECONDS = 60;
    private static final AlgorithmConstraints ES384 = new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);

    // gophertunnel's name patterns are not anchored, so a name passes when it contains one such character.
    private static final Pattern OFFLINE_NAME_CHARACTER = Pattern.compile("[ \\p{L}]");
    private static final Pattern ONLINE_NAME_CHARACTER = Pattern.compile("[A-Za-z0-9 ]");
    private static final Pattern UUID_FORM = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private LoginChecks() {
    }

    /**
     * Checks the time claims of every entry, the issuer of the Mojang-signed entries of a three-entry chain, and the signature of a one-entry chain, which is signed with the key in its own x5u header.
     * The identity in the last entry must be a UUID in its 8-4-4-4-12 form, because Cloudburst's parser also accepts shorter forms.
     */
    static void checkChain(List<String> chain, long nowSeconds) throws Exception {
        Map<String, Object> claims = Map.of();
        for (int i = 0; i < chain.size(); i++) {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(chain.get(i));
            if (chain.size() == 1) {
                jws.setAlgorithmConstraints(ES384);
                jws.setKey(EncryptionUtils.parseKey(jws.getHeader(HeaderParameterNames.X509_URL)));
                if (!jws.verifySignature()) {
                    throw new IllegalStateException("self-signed chain has a bad signature");
                }
            }

            claims = JsonUtil.parseJson(jws.getUnverifiedPayload());
            if (nowSeconds >= time(claims, "exp", Long.MAX_VALUE - LEEWAY_SECONDS) + LEEWAY_SECONDS) {
                throw new IllegalStateException("chain expired");
            }
            if (nowSeconds + LEEWAY_SECONDS < time(claims, "nbf", Long.MIN_VALUE + LEEWAY_SECONDS)) {
                throw new IllegalStateException("chain not valid yet");
            }
            if (nowSeconds + LEEWAY_SECONDS < time(claims, "iat", Long.MIN_VALUE + LEEWAY_SECONDS)) {
                throw new IllegalStateException("chain issued in the future");
            }
            if (chain.size() == 3 && i > 0 && !"Mojang".equals(claims.get("iss"))) {
                throw new IllegalStateException("chain entry " + i + " was not issued by Mojang");
            }
        }

        if (claims.get("extraData") instanceof Map<?, ?> extraData && !(extraData.get("identity") instanceof String identity && UUID_FORM.matcher(identity).matches())) {
            throw new IllegalStateException("identity is not a UUID in 8-4-4-4-12 form");
        }
    }

    // A missing claim gives the fallback; a claim that is not a number fails, as go-jose cannot read it either.
    private static long time(Map<String, Object> claims, String name, long fallback) {
        Object value = claims.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException(name + " is not a number");
    }

    /**
     * gophertunnel's IdentityData.Validate, plus a rule that an identity has an XUID exactly when Xbox Live signed it (gophertunnel enforces this only for three-entry chains).
     * Lengths and the first and last character are counted in UTF-8 bytes, as Go does.
     */
    static void checkIdentity(ChainValidationResult.IdentityData identity, boolean xboxSigned) {
        String xuid = identity.xuid == null ? "" : identity.xuid;
        if (xboxSigned == xuid.isEmpty()) {
            throw new IllegalStateException(xboxSigned ? "Xbox signed identity has no XUID" : "identity not signed by Xbox has an XUID");
        }
        if (!xuid.isEmpty()) {
            try {
                Long.parseLong(xuid);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("XUID is not a number: " + xuid);
            }
        }
        if (identity.identity == null || identity.identity.equals(new UUID(0, 0))) {
            throw new IllegalStateException("identity is not a valid UUID");
        }

        String name = identity.displayName == null ? "" : identity.displayName;
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        int limit = xuid.isEmpty() ? 16 : 15;
        if (bytes.length == 0 || bytes.length > limit) {
            throw new IllegalStateException("display name must be 1 to " + limit + " bytes");
        }
        if (bytes[0] == ' ' || bytes[bytes.length - 1] == ' ' || (bytes[0] >= '0' && bytes[0] <= '9') || name.contains("  ")) {
            throw new IllegalStateException("display name has a bad space or starts with a number: " + name);
        }
        Pattern characters = xuid.isEmpty() ? OFFLINE_NAME_CHARACTER : ONLINE_NAME_CHARACTER;
        if (!characters.matcher(name).find()) {
            throw new IllegalStateException("display name has no allowed character: " + name);
        }
    }
}
