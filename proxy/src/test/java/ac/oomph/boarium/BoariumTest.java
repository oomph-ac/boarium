package ac.oomph.boarium;

import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.session.FakeSpectrumServer;
import ac.oomph.boarium.session.SessionSettings;
import ac.oomph.boarium.transport.Addresses;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    // The QA network and live tests hold these, so a test must never bind them.
    private static final Set<Integer> TAKEN_PORTS = Set.of(19132, 19133, 19142, 19143);

    @Test
    void sendsPlayersToTheServerAndDisconnectsThemOnClose() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Boarium boarium = startBoth(server);
        try {
            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, boarium.rakNetAddress());
            client.loginDone.get(10, TimeUnit.SECONDS);
            spawn(client, server, boarium);

            boarium.close();
            assertEquals("bye", client.await(DisconnectPacket.class).getKickMessage());
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // A player who joins by address over NetherNet takes the same login and server connection as one over RakNet, and with both on, both can join.
    @Test
    @Timeout(60)
    void sendsPlayersWhoJoinOverNetherNetToTheServer() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Boarium boarium = startBoth(server);
        try {
            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.joinByAddress(group, boarium.netherNetAddress());
            client.loginDone.get(30, TimeUnit.SECONDS);
            String addr = server.request.get(10, TimeUnit.SECONDS).addr();
            assertTrue(addr.matches("[0-9.]+:[0-9]+") && !addr.equals("0.0.0.0:0"), addr);
            spawn(client, server, boarium);

            TestClient rakNet = new TestClient("RakNet", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            rakNet.connect(group, boarium.rakNetAddress());
            // The fake server takes one player, so this one only logs in.
            rakNet.loginDone.get(10, TimeUnit.SECONDS);

            boarium.close();
            assertEquals("bye", client.await(DisconnectPacket.class).getKickMessage());
            assertEquals("bye", rakNet.await(DisconnectPacket.class).getKickMessage());
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // The default transports, NetherNet alone, on a listen port of 0: boarium picks one port for NetherNet's signaling and connections.
    @Test
    @Timeout(60)
    void takesNetherNetAloneByDefault() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Boarium boarium = start(BoariumConfig.parse(new StringReader(config(server, "listen: 127.0.0.1:0"))), null);
        try {
            assertNull(boarium.rakNetAddress());
            InetSocketAddress address = boarium.netherNetAddress();
            assertTrue(address.getPort() != 0 && !TAKEN_PORTS.contains(address.getPort()), address.toString());
            // A RakNet unconnected ping: ID, time, the offline message magic, then the sender's GUID.
            ByteBuffer ping = ByteBuffer.allocate(33).put((byte) 0x01).putLong(1234).put(HexFormat.of().parseHex("00ffff00fefefefefdfdfdfd12345678")).putLong(42);
            try (DatagramSocket socket = new DatagramSocket(0, LOOPBACK)) {
                socket.setSoTimeout(2000);
                socket.send(new DatagramPacket(ping.array(), ping.capacity(), address));
                assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[1500], 1500)));
            }

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            Channel channel = client.joinByAddress(group, address);
            client.loginDone.get(30, TimeUnit.SECONDS);
            assertEquals(address.getPort(), ((NetherNetChannel) channel).selectedPath().remote().getPort());
            spawn(client, server, boarium);
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // The certificate under nethernet.https, read relative to the config's folder, as Main reads it.
    @Test
    @Timeout(60)
    void servesNetherNetSignalingOverHttpsFromTheConfig(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(Files.createDirectory(dir.resolve("tls")));
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Path path = Files.writeString(dir.resolve("config.yml"), config(server, """
                listen: 127.0.0.1:0
                nethernet:
                  https:
                    certificate: tls/%s
                    password: "%s"
                """.formatted(certificate.pkcs12().getFileName(), certificate.password())));
        BoariumConfig config = BoariumConfig.load(path);
        Boarium boarium = start(config, config.netherNet().https().load());
        try {
            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            HttpSignalingSettings https = HttpSignalingSettings.DEFAULT.withScheme(HttpSignalingSettings.Scheme.HTTPS).withSslContext(certificate.trusted());
            client.joinByAddress(group, boarium.netherNetAddress(), https);
            client.loginDone.get(30, TimeUnit.SECONDS);
            spawn(client, server, boarium);
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // RakNet on a free loopback port, and NetherNet's signaling on its TCP side with connections on another free UDP port.
    private static Boarium startBoth(FakeSpectrumServer server) throws Exception {
        int port = freePort();
        Set<Integer> skip = new HashSet<>(TAKEN_PORTS);
        skip.add(port);
        int netherNetPort = Addresses.freePort(LOOPBACK, skip);
        return start(BoariumConfig.parse(new StringReader(config(server, """
                listen: 127.0.0.1:%d
                raknet:
                  enabled: true
                nethernet:
                  port: %d
                """.formatted(port, netherNetPort)))), null);
    }

    private static Boarium start(BoariumConfig config, SslContext netherNetTls) throws Exception {
        return Boarium.start(config, netherNetTls, SessionSettings.DEFAULT.processors());
    }

    // The test's config, with the keys in transports.
    private static String config(FakeSpectrumServer server, String transports) {
        return transports + "\nserver: " + server.address() + "\nxbox_auth: false\nsub_motd: test\nmax_players: 10\nshutdown_message: bye\nboar_enabled: false\n";
    }

    private static int freePort() throws Exception {
        return Addresses.freePort(LOOPBACK, TAKEN_PORTS);
    }

    // Plays the client's part of the game start and waits for its session.
    private static void spawn(TestClient client, FakeSpectrumServer server, Boarium boarium) throws Exception {
        StartGamePacket startGame = client.await(StartGamePacket.class);
        RequestChunkRadiusPacket radius = new RequestChunkRadiusPacket();
        radius.setRadius(8);
        client.session.get().sendPacket(radius);
        client.await(PlayStatusPacket.class);
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
        client.session.get().sendPacket(initialized);

        assertInstanceOf(RequestChunkRadiusPacket.class, server.await());
        assertInstanceOf(ClientCacheStatusPacket.class, server.await());
        assertInstanceOf(SetLocalPlayerAsInitializedPacket.class, server.await());
        long deadline = System.currentTimeMillis() + 10_000;
        while (boarium.sessions().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, boarium.sessions().size());
    }
}
