package ac.oomph.boarium.client;

import org.jose4j.json.JsonUtil;
import org.jose4j.lang.JoseException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The identity and client data JSON a Spectrum ConnectionRequest carries, in the shape of gophertunnel's login.IdentityData and login.ClientData, which is what spectrum-internal sends and what downstream Spectrum plugins read.
 */
public final class SpectrumLoginData {

    private enum Kind { STRING, INT, FLOAT, BOOL, OPTIONAL_BOOL, ANIMATIONS, PIECES, TINTS, COLOURS }

    private record Field(String name, Kind kind, boolean omitEmpty) {
        Field(String name, Kind kind) {
            this(name, kind, false);
        }
    }

    // gophertunnel's login.ClientData fields in declaration order, under their JSON names.
    private static final List<Field> CLIENT_DATA = List.of(
            new Field("AnimatedImageData", Kind.ANIMATIONS), new Field("CapeData", Kind.STRING), new Field("CapeId", Kind.STRING),
            new Field("CapeImageHeight", Kind.INT), new Field("CapeImageWidth", Kind.INT), new Field("CapeOnClassicSkin", Kind.BOOL),
            new Field("ClientRandomId", Kind.INT), new Field("CurrentInputMode", Kind.INT), new Field("DefaultInputMode", Kind.INT),
            new Field("DeviceModel", Kind.STRING), new Field("DeviceOS", Kind.INT), new Field("DeviceId", Kind.STRING),
            new Field("GameVersion", Kind.STRING), new Field("GuiScale", Kind.INT), new Field("IsEditorMode", Kind.BOOL),
            new Field("LanguageCode", Kind.STRING), new Field("PersonaSkin", Kind.BOOL), new Field("PlatformOfflineId", Kind.STRING),
            new Field("PlatformOnlineId", Kind.STRING), new Field("PlatformUserId", Kind.STRING, true), new Field("PremiumSkin", Kind.BOOL),
            new Field("SelfSignedId", Kind.STRING), new Field("ServerAddress", Kind.STRING), new Field("SkinAnimationData", Kind.STRING),
            new Field("SkinData", Kind.STRING), new Field("SkinGeometryData", Kind.STRING), new Field("SkinGeometryDataEngineVersion", Kind.STRING),
            new Field("SkinId", Kind.STRING), new Field("PlayFabId", Kind.STRING), new Field("SkinImageHeight", Kind.INT),
            new Field("SkinImageWidth", Kind.INT), new Field("SkinResourcePatch", Kind.STRING), new Field("SkinColor", Kind.STRING),
            new Field("ArmSize", Kind.STRING), new Field("PersonaPieces", Kind.PIECES), new Field("PieceTintColors", Kind.TINTS),
            new Field("ThirdPartyName", Kind.STRING), new Field("ThirdPartyNameOnly", Kind.OPTIONAL_BOOL, true), new Field("UIProfile", Kind.INT),
            new Field("TrustedSkin", Kind.BOOL), new Field("OverrideSkin", Kind.BOOL), new Field("CompatibleWithClientSideChunkGen", Kind.BOOL),
            new Field("MaxViewDistance", Kind.INT), new Field("MemoryTier", Kind.INT), new Field("PlatformType", Kind.INT),
            new Field("GraphicsMode", Kind.INT), new Field("PartyId", Kind.STRING), new Field("IsPartyLeader", Kind.BOOL));

    // gophertunnel's login.SkinAnimation, login.PersonaPiece and login.PersonaPieceTintColour.
    private static final List<Field> ANIMATION = List.of(new Field("Frames", Kind.FLOAT), new Field("Image", Kind.STRING), new Field("ImageHeight", Kind.INT),
            new Field("ImageWidth", Kind.INT), new Field("Type", Kind.INT), new Field("AnimationExpression", Kind.INT));
    private static final List<Field> PIECE = List.of(new Field("IsDefault", Kind.BOOL), new Field("PackId", Kind.STRING), new Field("PieceId", Kind.STRING),
            new Field("PieceType", Kind.STRING), new Field("ProductId", Kind.STRING));
    private static final List<Field> TINT = List.of(new Field("Colors", Kind.COLOURS), new Field("PieceType", Kind.STRING));

    private SpectrumLoginData() {
    }

    public static byte[] identity(Identity identity) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("XUID", identity.xuid());
        json.put("identity", identity.uuid().toString());
        json.put("displayName", text(identity.displayName()));
        if (!identity.titleId().isEmpty()) {
            json.put("titleId", text(identity.titleId()));
        }
        return JsonUtil.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Rewrites the client's own client data JSON the way gophertunnel's login.Parse reads it into login.ClientData and spectrum-internal writes it back.
     * Keys must match exactly, as in go-jose's JSON decoder, which gophertunnel uses; fields gophertunnel does not know are dropped, and missing or null ones get Go's zero value.
     * Strings may be escaped differently from Go's, but they read back the same.
     *
     * @throws IllegalArgumentException where gophertunnel would fail: the body is not a JSON object, a field has the wrong JSON type, or a number anywhere is too large for a float64.
     * It also fails a body of null, which Go reads as all zero values.
     * jose4j's parser accepts some JSON that Go's refuses, such as a number with a leading zero.
     */
    public static byte[] clientData(byte[] raw) {
        Map<String, Object> parsed;
        try {
            parsed = JsonUtil.parseJson(utf8(raw));
        } catch (JoseException e) {
            throw new IllegalArgumentException("client data is not a JSON object", e);
        }
        checkFinite(parsed);
        Map<String, Object> data = object(parsed, CLIENT_DATA);
        // login.Parse puts brackets around an IPv6 address the game sent without them.
        String address = (String) data.get("ServerAddress");
        if (address.chars().filter(c -> c == ':').count() > 1 && address.charAt(0) != '[') {
            int port = address.lastIndexOf(':');
            data.put("ServerAddress", "[" + address.substring(0, port) + "]" + address.substring(port));
        }
        return JsonUtil.toJson(data).getBytes(StandardCharsets.UTF_8);
    }

    // Go's decoder turns each byte that does not start a valid UTF-8 sequence into U+FFFD, where Java's decoder can turn a few such bytes into one.
    private static String utf8(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length);
        int i = 0;
        while (i < raw.length) {
            int b = raw[i] & 0xff;
            int length = b < 0x80 ? 1 : b >= 0xc2 && b <= 0xdf ? 2 : b >= 0xe0 && b <= 0xef ? 3 : b >= 0xf0 && b <= 0xf4 ? 4 : 0;
            int codePoint = length == 1 ? b : length == 2 ? b & 0x1f : length == 3 ? b & 0x0f : b & 0x07;
            boolean valid = length > 0 && i + length <= raw.length;
            for (int k = 1; valid && k < length; k++) {
                int next = raw[i + k] & 0xff;
                valid = (next & 0xc0) == 0x80;
                codePoint = codePoint << 6 | next & 0x3f;
            }
            // The shortest form only, no UTF-16 surrogates, and nothing past U+10FFFF.
            valid = valid
                    && (length < 3 || codePoint >= (length == 3 ? 0x800 : 0x10000))
                    && !(codePoint >= 0xd800 && codePoint <= 0xdfff)
                    && codePoint <= 0x10ffff;
            if (valid) {
                out.appendCodePoint(codePoint);
                i += length;
            } else {
                out.append('\uFFFD');
                i++;
            }
        }
        return out.toString();
    }

    // gophertunnel also decodes the whole body into a map, which fails on a number too large for a float64 even in a field it does not know; jose4j reads such a number as infinity, or as a BigInteger when it is an integer.
    private static void checkFinite(Object root) {
        ArrayDeque<Object> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (value instanceof Map<?, ?> map) {
                map.values().forEach(v -> { if (v != null) pending.push(v); });
            } else if (value instanceof List<?> list) {
                list.forEach(v -> { if (v != null) pending.push(v); });
            } else if (value instanceof Double number && number.isInfinite() || value instanceof BigInteger big && Double.isInfinite(big.doubleValue())) {
                throw new IllegalArgumentException("client data holds a number too large for a 64-bit float");
            }
        }
    }

    private static Map<String, Object> object(Map<?, ?> raw, List<Field> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : fields) {
            Object converted = convert(field, raw.get(field.name()));
            if (field.omitEmpty() && (converted == null || "".equals(converted))) {
                continue;
            }
            out.put(field.name(), converted);
        }
        return out;
    }

    private static Object convert(Field field, Object value) {
        return switch (field.kind()) {
            case STRING -> value == null ? "" : text(expect(field, value, String.class));
            case BOOL -> value == null ? Boolean.FALSE : expect(field, value, Boolean.class);
            case OPTIONAL_BOOL -> value == null ? null : expect(field, value, Boolean.class);
            case INT -> value == null ? 0L : integer(field, value);
            case FLOAT -> value == null ? new GoFloat(0) : decimal(field, value);
            case ANIMATIONS -> list(field, value, item -> object(item, ANIMATION));
            case PIECES -> list(field, value, item -> object(item, PIECE));
            case TINTS -> list(field, value, item -> object(item, TINT));
            case COLOURS -> colours(field, value);
        };
    }

    private static <T> T expect(Field field, Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(field.name() + " is not a JSON " + type.getSimpleName().toLowerCase());
        }
        return type.cast(value);
    }

    // Go's decoder turns an unpaired UTF-16 surrogate into U+FFFD, which writing the string as UTF-8 would otherwise turn into '?'.
    private static String text(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                out.append(c).append(value.charAt(++i));
            } else {
                out.append(Character.isSurrogate(c) ? '\uFFFD' : c);
            }
        }
        return out.toString();
    }

    // Go only decodes an integer literal that fits in 64 bits into an int; jose4j reads those as Long, and larger ones and other numbers as other types.
    private static long integer(Field field, Object value) {
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(field.name() + " is not a 64-bit integer");
        }
        return number;
    }

    // Go fails a number too large for a float64, which jose4j reads as infinity.
    private static GoFloat decimal(Field field, Object value) {
        double number = expect(field, value, Number.class).doubleValue();
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException(field.name() + " does not fit in a 64-bit float");
        }
        return new GoFloat(number);
    }

    /**
     * A float64 that jose4j writes the way Go's encoding/json does, since jose4j writes a number with its toString: plainly when 1e-6 <= |value| < 1e21, and otherwise with an exponent, such as 1e-7 or 1.5e+21.
     */
    private static final class GoFloat extends Number {
        private final double value;

        GoFloat(double value) {
            this.value = value;
        }

        @Override
        public String toString() {
            if (this.value == 0) {
                return 1 / this.value < 0 ? "-0" : "0";
            }
            String sign = this.value < 0 ? "-" : "";
            double abs = Math.abs(this.value);
            BigDecimal digits = shortest(abs);
            if (abs >= 1e-6 && abs < 1e21) {
                return sign + digits.toPlainString();
            }
            String mantissa = digits.unscaledValue().toString();
            int exponent = mantissa.length() - 1 - digits.scale();
            String fraction = mantissa.length() > 1 ? "." + mantissa.substring(1) : "";
            return sign + mantissa.charAt(0) + fraction + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }

        // The shortest decimal that reads back as the value, and the closest one when two of that length do, as Go's strconv picks; Double.toString keeps at least two digits.
        // Both neighbours at each length are tried, since below a power of two the doubles are twice as dense, so the nearest decimal can miss while the one on the other side reads back.
        private static BigDecimal shortest(double abs) {
            BigDecimal exact = new BigDecimal(abs);
            for (int precision = 1; ; precision++) {
                BigDecimal down = exact.round(new MathContext(precision, RoundingMode.FLOOR));
                BigDecimal up = exact.round(new MathContext(precision, RoundingMode.CEILING));
                boolean downReadsBack = down.doubleValue() == abs;
                boolean upReadsBack = up.doubleValue() == abs;
                if (downReadsBack && upReadsBack) {
                    return exact.round(new MathContext(precision, RoundingMode.HALF_EVEN)).stripTrailingZeros();
                }
                if (downReadsBack || upReadsBack) {
                    return (downReadsBack ? down : up).stripTrailingZeros();
                }
            }
        }

        @Override
        public double doubleValue() {
            return this.value;
        }

        @Override
        public float floatValue() {
            return (float) this.value;
        }

        @Override
        public long longValue() {
            return (long) this.value;
        }

        @Override
        public int intValue() {
            return (int) this.value;
        }
    }

    // A missing or null list stays null, as Go writes a nil slice, and a null item is an object with every field at its zero value.
    private static List<Object> list(Field field, Object value, Function<Map<?, ?>, Object> item) {
        if (value == null) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        for (Object element : expect(field, value, List.class)) {
            out.add(item.apply(element == null ? Map.of() : expect(field, element, Map.class)));
        }
        return out;
    }

    // Colors is a Go [4]string: always four strings, with missing and null ones empty and any past the fourth dropped.
    private static List<String> colours(Field field, Object value) {
        List<String> out = new ArrayList<>(List.of("", "", "", ""));
        if (value != null) {
            List<?> raw = expect(field, value, List.class);
            for (int i = 0; i < Math.min(4, raw.size()); i++) {
                if (raw.get(i) != null) {
                    out.set(i, text(expect(field, raw.get(i), String.class)));
                }
            }
        }
        return out;
    }
}
