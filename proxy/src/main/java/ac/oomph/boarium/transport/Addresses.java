package ac.oomph.boarium.transport;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class Addresses {

    private Addresses() {
    }

    /**
     * Parses {@code host:port}. As in Go's net.Dial, an IPv6 host goes in brackets and an empty host means this machine.
     * A named host is returned unresolved.
     */
    public static InetSocketAddress parse(String addr) {
        int colon = addr.lastIndexOf(':');
        if (colon < 0 || addr.endsWith("]")) {
            throw new IllegalArgumentException("missing port in address " + addr);
        }

        String host = addr.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        } else if (host.contains(":")) {
            throw new IllegalArgumentException("IPv6 host must be in brackets in address " + addr);
        }
        if (host.contains("[") || host.contains("]") || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("invalid host in address " + addr);
        }

        String portText = addr.substring(colon + 1);
        if (!portText.matches("[0-9]{1,5}") || Integer.parseInt(portText) > 0xffff) {
            throw new IllegalArgumentException("invalid port in address " + addr);
        }

        int port = Integer.parseInt(portText);
        if (host.isEmpty()) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }
        return InetSocketAddress.createUnresolved(host, port);
    }
}
