package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import ac.oomph.boarium.protocol.BedrockVersions;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackExchangeTest {
    @TempDir Path dir;
    final NioEventLoopGroup group = new NioEventLoopGroup(2);
    final CompletableFuture<ClientLogin> login = new CompletableFuture<>();
    ClientListener listener;
    Channel channel;
    TestClient client;
    UUID id = UUID.randomUUID();

    // Open a real RakNet session with manual responses at the pack boundary.
    void open(boolean packs, boolean required) throws Exception {
        open(packs, required, Duration.ofSeconds(10));
    }
    void open(boolean packs, boolean required, Duration timeout) throws Exception {
        if (packs) ResourcePacksTest.archive(dir.resolve("pack.zip"), id, "[1,0,0]", false);
        var catalogue = ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,required,false));
        listener = ClientListener.bind(group, new ClientListener.RakNetSettings(new InetSocketAddress("127.0.0.1",0)), null, new BedrockPong(), false, timeout, catalogue, login::complete);
        var codec = BedrockVersions.LATEST;
        client = new TestClient("PackTest",codec,codec.getProtocolVersion()); client.manualPacks = true;
        channel = client.connect(group, listener.rakNetAddress());
        client.await(ResourcePacksInfoPacket.class);
    }
    void response(ResourcePackClientResponsePacket.Status status, String... ids) throws Exception {
        var packet = new ResourcePackClientResponsePacket(); packet.setStatus(status);
        packet.getPackIds().addAll(java.util.List.of(ids));
        client.session.get(5,TimeUnit.SECONDS).sendPacketImmediately(packet);
    }
    @AfterEach void close() {
        if (channel != null) channel.close().syncUninterruptibly();
        if (listener != null) listener.close().syncUninterruptibly();
        group.shutdownGracefully(0,0,TimeUnit.SECONDS).syncUninterruptibly();
    }
    @Test void skipsEmptyListsAndDuplicateChunksThenFinishes() throws Exception {
        open(true,true);
        response(ResourcePackClientResponsePacket.Status.SEND_PACKS);
        response(ResourcePackClientResponsePacket.Status.SEND_PACKS,id+"_1.0.0");
        var info = client.await(ResourcePackDataInfoPacket.class);
        response(ResourcePackClientResponsePacket.Status.SEND_PACKS);
        // A full batch of duplicates must still produce exactly one reply.
        for (int i=0;i<100;i++) {
            var request = new ResourcePackChunkRequestPacket(); request.setPackId(id); request.setPackVersion("1.0.0"); request.setChunkIndex(0);
            client.session.get().sendPacketImmediately(request);
        }
        client.await(ResourcePackChunkDataPacket.class);
        response(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        // Awaiting the stack is an ordering barrier for all earlier chunk requests.
        int extras = 0;
        while (true) {
            var packet = client.received.poll(5,TimeUnit.SECONDS); assertNotNull(packet);
            if (packet instanceof ResourcePackStackPacket) break;
            if (packet instanceof ResourcePackChunkDataPacket) extras++;
            assertFalse(packet instanceof DisconnectPacket);
        }
        assertEquals(0,extras); assertTrue(info.getChunkCount()>1);
        response(ResourcePackClientResponsePacket.Status.COMPLETED);
        assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void permitsCachedPacks() throws Exception {
        open(true,true); response(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        assertEquals(1,client.await(ResourcePackStackPacket.class).getResourcePacks().size());
        response(ResourcePackClientResponsePacket.Status.COMPLETED); assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void optionalRefusalOmitsCustomPacks() throws Exception {
        open(true,false); response(ResourcePackClientResponsePacket.Status.REFUSED);
        assertTrue(client.await(ResourcePackStackPacket.class).getResourcePacks().isEmpty());
        response(ResourcePackClientResponsePacket.Status.COMPLETED); assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void requiredRefusalDisconnects() throws Exception {
        open(true,true); response(ResourcePackClientResponsePacket.Status.REFUSED);
        assertEquals("disconnectionScreen.resourcePack",client.await(DisconnectPacket.class).getKickMessage());
    }
    @Test void emptyCatalogueRetainsEarlyCompletion() throws Exception {
        open(false,false); response(ResourcePackClientResponsePacket.Status.COMPLETED); assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void emptyCatalogueRetainsRefusal() throws Exception {
        open(false,false); response(ResourcePackClientResponsePacket.Status.REFUSED);
        assertEquals("disconnectionScreen.resourcePack",client.await(DisconnectPacket.class).getKickMessage());
    }

    @Test void downloadsEveryChunkWithoutChangingArchiveBytes() throws Exception {
        open(true,true);
        var chunks = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
        client.onPackChunk = packet -> {
            byte[] bytes = new byte[packet.getData().readableBytes()];
            packet.getData().getBytes(packet.getData().readerIndex(),bytes); chunks.add(bytes);
        };
        response(ResourcePackClientResponsePacket.Status.SEND_PACKS,id+"_1.0.0");
        var info = client.await(ResourcePackDataInfoPacket.class);
        var output = new java.io.ByteArrayOutputStream();
        for (int i=0;i<info.getChunkCount();i++) {
            var request = new ResourcePackChunkRequestPacket(); request.setPackId(id); request.setPackVersion("1.0.0"); request.setChunkIndex(i);
            client.session.get().sendPacketImmediately(request);
            byte[] bytes = chunks.poll(5,TimeUnit.SECONDS); assertNotNull(bytes); output.write(bytes);
        }
        assertArrayEquals(java.nio.file.Files.readAllBytes(dir.resolve("pack.zip")),output.toByteArray());
        response(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS); client.await(ResourcePackStackPacket.class);
        response(ResourcePackClientResponsePacket.Status.COMPLETED); assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void emptyCatalogueRetainsRepeatedStack() throws Exception {
        open(false,false);
        for (int i=0;i<2;i++) { response(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS); client.await(ResourcePackStackPacket.class); }
        response(ResourcePackClientResponsePacket.Status.COMPLETED);
        assertNotNull(login.get(5,TimeUnit.SECONDS));
    }
    @Test void rejectsInvalidChunkIndices() throws Exception {
        open(true,true); response(ResourcePackClientResponsePacket.Status.SEND_PACKS,id+"_1.0.0"); client.await(ResourcePackDataInfoPacket.class);
        var request = new ResourcePackChunkRequestPacket(); request.setPackId(id); request.setPackVersion("1.0.0"); request.setChunkIndex(Integer.MAX_VALUE);
        client.session.get().sendPacketImmediately(request);
        assertEquals("disconnectionScreen.resourcePack",client.await(DisconnectPacket.class).getKickMessage());
    }
    @Test void offeringPacksDoesNotExtendTheLoginDeadline() throws Exception {
        open(true,false,Duration.ofSeconds(2));
        response(ResourcePackClientResponsePacket.Status.SEND_PACKS);
        assertEquals("disconnectionScreen.timeout",client.await(DisconnectPacket.class).getKickMessage());
    }
}
