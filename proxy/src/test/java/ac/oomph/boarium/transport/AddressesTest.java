package ac.oomph.boarium.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressesTest {

    @Test
    void parsesNamedHostUnresolved() {
        InetSocketAddress address = Addresses.parse("lobby.example:19133");
        assertTrue(address.isUnresolved());
        assertEquals("lobby.example", address.getHostString());
        assertEquals(19133, address.getPort());
    }

    @Test
    void parsesBracketedIpv6() {
        InetSocketAddress address = Addresses.parse("[::1]:19132");
        assertEquals("::1", address.getHostString());
        assertEquals(19132, address.getPort());
    }

    @Test
    void emptyHostMeansLoopback() {
        InetSocketAddress address = Addresses.parse(":19132");
        assertEquals(InetAddress.getLoopbackAddress(), address.getAddress());
        assertEquals(19132, address.getPort());
    }

    @Test
    void acceptsPortRange() {
        assertEquals(0, Addresses.parse("a:0").getPort());
        assertEquals(65535, Addresses.parse("a:65535").getPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lobby", "lobby:", "lobby:65536", "lobby:+80", "lobby:-1", "lobby:1x", "lobby:٣", "::1:19132", "lobby:000019132", "lobby:019132", "[::1]", "[[::1]]:80", "lobby]:80", "[a]b]:80", " lobby:80", "lob by:80"})
    void rejectsBadAddresses(String addr) {
        assertThrows(IllegalArgumentException.class, () -> Addresses.parse(addr));
    }
}
