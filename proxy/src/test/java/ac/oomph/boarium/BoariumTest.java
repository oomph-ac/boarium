package ac.oomph.boarium;

import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.session.FakeSpectrumServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class BoariumTest {

    @Test
    void sendsPlayersToTheServerAndDisconnectsThemOnClose() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Boarium.CODEC);
        Boarium boarium = Boarium.start(new BoariumConfig("127.0.0.1:0", server.address(), null, false, "boarium", "test", 10, Duration.ofSeconds(3), "bye"));
        try {
            TestClient client = new TestClient("Tester", Boarium.CODEC, Boarium.CODEC.getProtocolVersion());
            client.connect(group, boarium.address());
            client.loginDone.get(10, TimeUnit.SECONDS);
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

            boarium.close();
            assertEquals("bye", client.await(DisconnectPacket.class).getKickMessage());
        } finally {
            boarium.close();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
