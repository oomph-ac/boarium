package ac.oomph.boarium.transport;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.Set;

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

    /**
     * A port free on both TCP and UDP at {@code host}, for a listen port of 0. Another program can still take it before it is bound.
     *
     * @param skip ports not to pick, such as ones the config sets for something else
     */
    public static int freePort(InetAddress host, Set<Integer> skip) throws IOException {
        for (int tries = 0; ; tries++) {
            try (ServerSocketChannel tcp = ServerSocketChannel.open().bind(new InetSocketAddress(host, 0))) {
                int port = ((InetSocketAddress) tcp.getLocalAddress()).getPort();
                if (skip.contains(port)) {
                    continue;
                }
                try (DatagramChannel udp = DatagramChannel.open().bind(new InetSocketAddress(host, port))) {
                    return port;
                } catch (BindException e) {
                    if (tries == 20) {
                        throw e;
                    }
                }
            }
        }
    }
}
