package ac.oomph.boarium;

import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.session.FakeSpectrumServer;
import ac.oomph.boarium.session.SessionSettings;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoariumTest {

    @Test
    void sendsPlayersToTheServerAndDisconnectsThemOnClose() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Boarium boarium = start(server);
        try {
            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, boarium.address());
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

    // A player who joins by address over NetherNet takes the same login and server connection as one over RakNet.
    @Test
    @Timeout(60)
    void sendsPlayersWhoJoinOverNetherNetToTheServer() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Boarium boarium = start(server);
        try {
            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.joinByAddress(group, boarium.address());
            client.loginDone.get(30, TimeUnit.SECONDS);
            String addr = server.request.get(10, TimeUnit.SECONDS).addr();
            assertTrue(addr.matches("[0-9.]+:[0-9]+") && !addr.equals("0.0.0.0:0"), addr);
            spawn(client, server, boarium);

            boarium.close();
            assertEquals("bye", client.await(DisconnectPacket.class).getKickMessage());
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // On a free loopback port, with NetherNet on.
    private static Boarium start(FakeSpectrumServer server) throws InterruptedException {
        return Boarium.start(new BoariumConfig("127.0.0.1:0", true, server.address(), null, false, "boarium", "test", 10, Duration.ofSeconds(3), "bye", false, false),
                SessionSettings.DEFAULT.processors());
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
