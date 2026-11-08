package ac.oomph.boarium.client;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every client here connects from 127.0.0.1, so all of them count against the limits of one IP.
class ClientListenerRateLimitTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;
    // A RakNet unconnected ping: ID, time, the offline message magic, then the sender's GUID.
    private static final byte[] PING = ByteBuffer.allocate(33).put((byte) 0x01).putLong(1234).put(HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678")).putLong(42).array();

    private EventLoopGroup group;
    private ClientListener listener;
    private final List<Channel> clients = new ArrayList<>();

    private void listen(int maxConnectionsPerAddress, int packetLimit, boolean rateLimiting) throws Exception {
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival").playerCount(0).maximumPlayerCount(10);
        ClientListener.RakNetSettings rakNet = new ClientListener.RakNetSettings(new InetSocketAddress("127.0.0.1", 0), maxConnectionsPerAddress, packetLimit, rateLimiting);
        this.listener = ClientListener.bind(this.group, rakNet, null, pong, false, Duration.ofMinutes(1), login -> {
        });
    }

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(2);
    }

    @AfterEach
    void tearDown() {
        this.clients.forEach(channel -> channel.close().syncUninterruptibly());
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void refusesAConnectionOverTheLimitOfOneAddress() throws Exception {
        this.listen(2, 120, true);

        this.connect().loginDone.get(10, TimeUnit.SECONDS);
        this.connect().loginDone.get(10, TimeUnit.SECONDS);
        assertRefused(this.connect());
    }

    @Test
    void refusesAFourthNewConnectionFromOneAddressWithinFourSeconds() throws Exception {
        this.listen(10, 120, true);
        long start = System.nanoTime();

        for (int i = 0; i < 3; i++) {
            this.connect().loginDone.get(10, TimeUnit.SECONDS);
        }
        assertRefused(this.connect());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed < 4000, "the fourth connection came " + elapsed + " ms after the first, after the window had closed");

        // The window starts at the first connection RakNet lets in, a little after start, so the client keeps asking for a while once the window should have ended.
        Thread.sleep(4000 - elapsed);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                this.connect().loginDone.get(10, TimeUnit.SECONDS);
                return;
            } catch (ExecutionException e) {
                if (System.nanoTime() - deadline > 0) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    @Test
    void ignoresAClientOverThePacketLimit() throws Exception {
        this.listen(10, 5, true);

        try (DatagramSocket flooding = new DatagramSocket(); DatagramSocket other = new DatagramSocket()) {
            this.flood(flooding);
            assertFalse(this.answersPing(flooding));
            assertTrue(this.answersPing(other));
        }
    }

    @Test
    void letsAnyNumberOfClientsConnectWithoutRateLimiting() throws Exception {
        this.listen(1, 5, false);

        // More than both the limit of open connections and the limit of new ones allow.
        for (int i = 0; i < 4; i++) {
            this.connect().loginDone.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void answersAFloodingClientWithoutRateLimiting() throws Exception {
        this.listen(1, 5, false);

        try (DatagramSocket flooding = new DatagramSocket()) {
            this.flood(flooding);
            assertTrue(this.answersPing(flooding));
        }
    }

    // A client that gives up the first time RakNet refuses it, since it would otherwise ask again each second, and could get in once the window has closed.
    private TestClient connect() {
        TestClient client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        client.maxConnectionAttempts = 1;
        this.clients.add(client.connect(this.group, this.listener.rakNetAddress()));
        return client;
    }

    private static void assertRefused(TestClient client) {
        String error = assertThrows(ExecutionException.class, () -> client.loginDone.get(10, TimeUnit.SECONDS)).getCause().getMessage();
        assertTrue(error.contains("Already connected"), error);
    }

    // Far more pings than the limit allows in the 10 ms that RakNet counts them over.
    private void flood(DatagramSocket socket) throws IOException {
        for (int i = 0; i < 200; i++) {
            socket.send(new DatagramPacket(PING, PING.length, this.listener.rakNetAddress()));
        }
    }

    // Whether a new ping gets an answer, after dropping the answers to earlier pings.
    private boolean answersPing(DatagramSocket socket) throws IOException {
        DatagramPacket pong = new DatagramPacket(new byte[1500], 1500);
        socket.setSoTimeout(200);
        try {
            while (true) {
                socket.receive(pong);
            }
        } catch (SocketTimeoutException drained) {
            // No earlier answer is left.
        }
        socket.setSoTimeout(1000);
        socket.send(new DatagramPacket(PING, PING.length, this.listener.rakNetAddress()));
        try {
            socket.receive(pong);
            return pong.getData()[0] == 0x1c;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }
}
