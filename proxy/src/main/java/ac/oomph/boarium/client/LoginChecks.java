package ac.oomph.boarium.client;

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
 * Login checks beyond Cloudburst's EncryptionUtils, mostly from gophertunnel's login.Parse, with the self-signed token checks from PocketMine-MP. Cloudburst checks the time claims only of the Mojang-signed chain entries and Mojang tokens.
 * Each throws an exception when a check fails.
 */
final class LoginChecks {
    // go-jose's default leeway for the time claims.
    private static final long LEEWAY_SECONDS = 60;
    private static final AlgorithmConstraints ES384 = new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);

    // gophertunnel's name patterns are not anchored, so a name passes when it contains one such character.
    private static final Pattern OFFLINE_NAME_CHARACTER = Pattern.compile("[ \\p{L}]");
    private static final Pattern ONLINE_NAME_CHARACTER = Pattern.compile("[A-Za-z0-9 ]");
    // Control, format and line-separator characters, such as a line break or a mark that turns text around, with which a name could write or disguise lines in the log.
    private static final Pattern OFFLINE_UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    // The same, but for the zero-width joiner and non-joiner, which some scripts' gamertags need.
    private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}[\\p{Cf}&&[^\\u200C\\u200D]]]");
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
            checkTimes(claims, nowSeconds);
            if (chain.size() == 3 && i > 0 && !"Mojang".equals(claims.get("iss"))) {
                throw new IllegalStateException("chain entry " + i + " was not issued by Mojang");
            }
        }

        if (claims.get("extraData") instanceof Map<?, ?> extraData && !(extraData.get("identity") instanceof String identity && UUID_FORM.matcher(identity).matches())) {
            throw new IllegalStateException("identity is not a UUID in 8-4-4-4-12 form");
        }
    }

    /**
     * Checks the signature and time claims of a self-signed token, which Cloudburst skips, and returns the player's UUID, which Cloudburst does not read from it.
     * The token is signed with the key in its own {@code cpk} claim. The UUID comes from {@code leguuid}, as PocketMine-MP reads it, or, when there is no {@code leguuid} claim, from {@code identity}, which gophertunnel's offline clients set.
     * Its audience is not checked, since a client that signs its own token can write any audience.
     */
    static UUID checkSelfSignedToken(String token, long nowSeconds) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(token);
        Map<String, Object> claims = JsonUtil.parseJson(jws.getUnverifiedPayload());
        if (!(claims.get("cpk") instanceof String key)) {
            throw new IllegalStateException("self-signed token has no cpk");
        }
        jws.setAlgorithmConstraints(ES384);
        jws.setKey(EncryptionUtils.parseKey(key));
        if (!jws.verifySignature()) {
            throw new IllegalStateException("self-signed token has a bad signature");
        }
        checkTimes(claims, nowSeconds);

        Object uuid = claims.containsKey("leguuid") ? claims.get("leguuid") : claims.get("identity");
        if (!(uuid instanceof String text && UUID_FORM.matcher(text).matches())) {
            throw new IllegalStateException("self-signed token has no UUID in 8-4-4-4-12 form");
        }
        return UUID.fromString(text);
    }

    private static void checkTimes(Map<String, Object> claims, long nowSeconds) {
        if (nowSeconds >= time(claims, "exp", Long.MAX_VALUE - LEEWAY_SECONDS) + LEEWAY_SECONDS) {
            throw new IllegalStateException("expired");
        }
        if (nowSeconds + LEEWAY_SECONDS < time(claims, "nbf", Long.MIN_VALUE + LEEWAY_SECONDS)) {
            throw new IllegalStateException("not valid yet");
        }
        if (nowSeconds + LEEWAY_SECONDS < time(claims, "iat", Long.MIN_VALUE + LEEWAY_SECONDS)) {
            throw new IllegalStateException("issued in the future");
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
    static void checkIdentity(Identity identity, boolean xboxSigned) {
        String xuid = identity.xuid();
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
        if (identity.uuid() == null || identity.uuid().equals(new UUID(0, 0))) {
            throw new IllegalStateException("identity is not a valid UUID");
        }

        String name = identity.displayName();
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        int limit = xuid.isEmpty() ? 16 : 15;
        if (bytes.length == 0 || bytes.length > limit) {
            throw new IllegalStateException("display name must be 1 to " + limit + " bytes");
        }
        // Before the checks below, whose errors include the name.
        if ((xuid.isEmpty() ? OFFLINE_UNPRINTABLE : UNPRINTABLE).matcher(name).find()) {
            throw new IllegalStateException("display name has a character that is not printable");
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
