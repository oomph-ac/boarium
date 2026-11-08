package ac.oomph.boarium.client;

import ac.oomph.boarium.protocol.Definitions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.JigsawStructureDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGameStartTest {
    private static final BedrockCodec CODEC = Bedrock_v1001.CODEC;

    private EventLoopGroup group;
    private ClientListener listener;
    private TestClient client;
    private StartGamePacket startGame;
    private ItemComponentPacket items;
    private final CompletableFuture<CompletableFuture<Void>> spawned = new CompletableFuture<>();
    private final CompletableFuture<ClientLogin> login = new CompletableFuture<>();

    // The server tests' gophertunnel packets, decoded as a downstream server's StartGame and ItemComponent would be.
    private static BedrockPacket gophertunnel(String name) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ClientGameStartTest.class.getResourceAsStream("/ac/oomph/boarium/server/gophertunnel-packets.txt"), StandardCharsets.UTF_8))) {
            String hex = reader.lines().filter(line -> line.startsWith(name + " ")).findFirst().orElseThrow().split(" ")[1];
            ByteBuf buf = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
            BedrockCodecHelper helper = CODEC.createHelper();
            helper.setBlockDefinitions(Definitions.ANY_BLOCK);
            helper.setItemDefinitions(Definitions.items(List.of()));
            return CODEC.tryDecode(helper, buf, VarInts.readUnsignedInt(buf), PacketRecipient.CLIENT);
        }
    }

    private void start(int chunkRadius) throws Exception {
        this.startGame = (StartGamePacket) gophertunnel("StartGame");
        this.items = (ItemComponentPacket) gophertunnel("ItemComponent");
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1), login -> {
            this.login.complete(login);
            this.spawned.complete(ClientGameStart.start(login.session(), this.startGame, this.items.getItems(), chunkRadius));
        });
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.client.connect(this.group, this.listener.rakNetAddress());
        this.client.loginDone.get(10, TimeUnit.SECONDS);
    }

    private void request(int radius) throws Exception {
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setRadius(radius);
        this.client.session.get().sendPacket(request);
    }

    private void initialize(long runtimeId) throws Exception {
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(runtimeId);
        this.client.session.get().sendPacket(initialized);
    }

    @BeforeEach
    void setUp() {
        this.group = new NioEventLoopGroup(2);
    }

    @AfterEach
    void tearDown() {
        if (this.listener != null) {
            this.listener.close().syncUninterruptibly();
        }
        this.group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void runsGophertunnelsSpawnSequence() throws Exception {
        this.start(12);

        JigsawStructureDataPacket jigsaws = this.client.await(JigsawStructureDataPacket.class);
        NbtMap emptyStructures = NbtMap.builder()
                .putList("processors", NbtType.COMPOUND, List.of())
                .putList("template_pools", NbtType.COMPOUND, List.of())
                .putList("jigsaws", NbtType.COMPOUND, List.of())
                .putList("structure_sets", NbtType.COMPOUND, List.of())
                .build();
        assertEquals(emptyStructures, jigsaws.getJigsawStructureDataTag());
        this.client.await(VoxelShapesPacket.class);
        assertEquals("boarium", this.client.await(StartGamePacket.class).getLevelName());
        assertEquals(2, this.client.await(ItemComponentPacket.class).getItems().size());

        this.request(8);
        assertEquals(12, this.client.await(ChunkRadiusUpdatedPacket.class).getRadius());
        this.client.await(BiomeDefinitionListPacket.class);
        assertEquals(PlayStatusPacket.Status.PLAYER_SPAWN, this.client.await(PlayStatusPacket.class).getStatus());
        this.client.await(CreativeContentPacket.class);

        this.initialize(this.startGame.getRuntimeEntityId());
        this.spawned.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    }

    @Test
    void givesTheRequestedRadiusWhenNoneIsSet() throws Exception {
        this.start(0);
        this.client.await(ItemComponentPacket.class);

        this.request(8);
        assertEquals(8, this.client.await(ChunkRadiusUpdatedPacket.class).getRadius());
    }

    @Test
    void decodesTheClientsPacketsWithTheServersItems() throws Exception {
        this.start(12);
        this.client.await(ItemComponentPacket.class);

        BedrockCodecHelper helper = this.login.get(10, TimeUnit.SECONDS).session().getPeer().getCodecHelper();
        assertEquals("minecraft:shield", helper.getItemDefinitions().getDefinition(387).getIdentifier());
        assertEquals(123, helper.getBlockDefinitions().getDefinition(123).getRuntimeId());
    }

    @Test
    void answersOnlyTheFirstChunkRadiusRequest() throws Exception {
        this.start(12);
        this.client.await(ItemComponentPacket.class);

        this.request(8);
        this.request(8);
        this.client.await(CreativeContentPacket.class);
        this.initialize(this.startGame.getRuntimeEntityId());
        this.spawned.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        assertTrue(this.client.received.stream().noneMatch(ChunkRadiusUpdatedPacket.class::isInstance));
    }

    @Test
    void failsOnARadiusBelowOne() throws Exception {
        this.start(12);
        this.client.await(ItemComponentPacket.class);

        this.request(0);

        CompletableFuture<Void> spawned = this.spawned.get(10, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> spawned.get(10, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void failsWhenTheClientLeaves() throws Exception {
        this.start(12);
        this.client.await(ItemComponentPacket.class);

        this.client.session.get().disconnect();

        CompletableFuture<Void> spawned = this.spawned.get(10, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> spawned.get(10, TimeUnit.SECONDS));
    }

    @Test
    void failsOnAnotherRuntimeId() throws Exception {
        this.start(12);
        this.client.await(ItemComponentPacket.class);

        this.initialize(this.startGame.getRuntimeEntityId() + 1);

        CompletableFuture<Void> spawned = this.spawned.get(10, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> spawned.get(10, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void sendsNothingToAClientThatWasDisconnected() throws Exception {
        this.startGame = (StartGamePacket) gophertunnel("StartGame");
        this.items = (ItemComponentPacket) gophertunnel("ItemComponent");
        BedrockPong pong = new BedrockPong().edition("MCPE").motd("boarium").subMotd("test").protocolVersion(CODEC.getProtocolVersion()).version(CODEC.getMinecraftVersion()).gameType("Survival");
        this.listener = ClientListener.bind(this.group, new InetSocketAddress("127.0.0.1", 0), pong, false, Duration.ofMinutes(1), login -> {
            // Cloudburst still counts the session as connected right after this.
            ClientDisconnect.disconnect(login.session(), "disconnectionScreen.internalError.cantConnect");
            this.spawned.complete(ClientGameStart.start(login.session(), this.startGame, this.items.getItems(), 0));
        });
        this.client = new TestClient("Tester", CODEC, CODEC.getProtocolVersion());
        this.client.connect(this.group, this.listener.rakNetAddress());

        CompletableFuture<Void> spawned = this.spawned.get(10, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> spawned.get(10, TimeUnit.SECONDS));
        this.client.await(DisconnectPacket.class);
        Thread.sleep(500);
        assertTrue(this.client.received.stream().noneMatch(p -> p instanceof StartGamePacket));
    }
}
