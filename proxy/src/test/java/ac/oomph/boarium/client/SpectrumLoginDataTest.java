package ac.oomph.boarium.client;

import org.jose4j.json.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectrumLoginDataTest {

    private static String clientData(String json) {
        return new String(SpectrumLoginData.clientData(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void rewritesClientDataAsGophertunnelDoes() throws Exception {
        List<String[]> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(SpectrumLoginDataTest.class.getResourceAsStream("gophertunnel-client-data.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    cases.add(line.split("\t", 2));
                }
            }
        }
        assertFalse(cases.isEmpty());

        for (String[] c : cases) {
            if (c[1].equals("ERROR")) {
                assertThrows(IllegalArgumentException.class, () -> clientData(c[0]), c[0]);
                continue;
            }
            String actual = clientData(c[0]);
            // jose4j and Go escape strings differently, so output with an escape is compared as parsed JSON, with its key order checked on its own.
            if (c[1].contains("\\")) {
                Map<String, Object> expectedJson = JsonUtil.parseJson(c[1]);
                Map<String, Object> actualJson = JsonUtil.parseJson(actual);
                assertEquals(expectedJson, actualJson, c[0]);
                assertEquals(List.copyOf(expectedJson.keySet()), List.copyOf(actualJson.keySet()), c[0]);
            } else {
                assertEquals(c[1], actual, c[0]);
            }
        }
    }

    @Test
    void replacesEachBadUtf8ByteAsGoDoes() {
        // The bad bytes, then how many U+FFFD Go's utf8.DecodeRune makes of them.
        Map<String, Integer> cases = Map.of("e282", 2, "eda080", 3, "f4908080", 4, "c0af", 2, "e08080", 3, "ff", 1);
        cases.forEach((hex, replacements) -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes("{\"CapeData\":\"".getBytes(StandardCharsets.UTF_8));
            body.writeBytes(HexFormat.of().parseHex(hex));
            body.writeBytes("x\"}".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> out = assertDoesNotThrow(() -> JsonUtil.parseJson(new String(SpectrumLoginData.clientData(body.toByteArray()), StandardCharsets.UTF_8)));
            assertEquals("\uFFFD".repeat(replacements) + "x", out.get("CapeData"), hex);
        });
    }

    @Test
    void turnsAwayAnInfiniteNumberInAFieldGophertunnelDoesNotKnow() {
        assertThrows(IllegalArgumentException.class, () -> clientData("{\"Unknown\":[{\"x\":1e400}]}"));
        assertThrows(IllegalArgumentException.class, () -> clientData("{\"Unknown\":-1" + "0".repeat(400) + "}"));
    }

    @Test
    void writesTheIdentityAsGophertunnelDoes() {
        // Go's json.Marshal of gophertunnel's login.IdentityData, which leaves titleId out when it is empty.
        UUID uuid = UUID.fromString("8f6c5b1e-0d2a-4c3b-9e8f-1a2b3c4d5e6f");
        assertEquals("{\"XUID\":\"2535400000000000\",\"identity\":\"8f6c5b1e-0d2a-4c3b-9e8f-1a2b3c4d5e6f\",\"displayName\":\"Tester\"}",
                new String(SpectrumLoginData.identity(new Identity("Tester", uuid, "2535400000000000", "")), StandardCharsets.UTF_8));
        assertEquals("{\"XUID\":\"\",\"identity\":\"8f6c5b1e-0d2a-4c3b-9e8f-1a2b3c4d5e6f\",\"displayName\":\"Tester\",\"titleId\":\"896928775\"}",
                new String(SpectrumLoginData.identity(new Identity("Tester", uuid, "", "896928775")), StandardCharsets.UTF_8));
    }
}
