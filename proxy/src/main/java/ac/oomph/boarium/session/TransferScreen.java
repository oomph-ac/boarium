package ac.oomph.boarium.session;

import ac.oomph.boarium.server.ServerGameData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.GameRulesChangedPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDifficultyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;

import java.util.function.Consumer;

/**
 * What the client is sent around a transfer, as spectrum-internal's Session.TransferContext and its Dimension animation send it.
 * The player is frozen while the next server connects, then sees a dimension change screen while it spawns there, with the old world cleared away and that server's world settings sent.
 */
final class TransferScreen {
    private static final int OVERWORLD = 0;
    private static final int NETHER = 1;
    private static final int END = 2;

    private TransferScreen() {
    }

    // Stops the player from moving while the next server connects, as spectrum-internal's sendMetadata(true) does.
    static void freeze(Consumer<BedrockPacket> send, long runtimeId) {
        SetEntityDataPacket data = new SetEntityDataPacket();
        data.setRuntimeEntityId(runtimeId);
        data.getMetadata().setFlag(EntityFlag.NO_AI, true);
        data.getMetadata().setFlag(EntityFlag.BREATHING, true);
        data.getMetadata().setFlag(EntityFlag.HAS_GRAVITY, true);
        send.accept(data);
    }

    // Starts the dimension change screen, into a dimension other than the client's first one, as Spectrum's Dimension animation picks it.
    static void play(Consumer<BedrockPacket> send, int clientDimension, ServerGameData next) {
        changeDimension(send, clientDimension == NETHER ? END : NETHER, next.startGame().getPlayerPosition());
    }

    /**
     * Gives the client the next server's world: empty chunks around the player in that server's dimension, removal of what {@code tracker} recorded, then the player's position and the world's settings.
     */
    static void resetWorld(Consumer<BedrockPacket> send, Tracker tracker, long clientRuntimeId, ServerGameData next) {
        StartGamePacket startGame = next.startGame();
        Vector3f position = startGame.getPlayerPosition();
        int chunkX = (int) position.getX() >> 4;
        int chunkZ = (int) position.getZ() >> 4;
        for (int x = chunkX - 4; x <= chunkX + 4; x++) {
            for (int z = chunkZ - 4; z <= chunkZ + 4; z++) {
                LevelChunkPacket chunk = new LevelChunkPacket();
                chunk.setDimension(startGame.getDimensionId());
                chunk.setChunkX(x);
                chunk.setChunkZ(z);
                chunk.setSubChunksLength(1);
                chunk.setData(emptyChunk(startGame.getDimensionId()));
                send.accept(chunk);
            }
        }
        tracker.clear(send, clientRuntimeId);

        MovePlayerPacket move = new MovePlayerPacket();
        move.setRuntimeEntityId(next.runtimeId());
        move.setPosition(position);
        move.setRotation(Vector3f.from(startGame.getRotation().getX(), startGame.getRotation().getY(), startGame.getRotation().getY()));
        // gophertunnel's MoveModeReset.
        move.setMode(MovePlayerPacket.Mode.RESPAWN);
        send.accept(move);
        LevelEventPacket rain = new LevelEventPacket();
        rain.setType(LevelEvent.STOP_RAINING);
        rain.setPosition(Vector3f.ZERO);
        rain.setData(10_000);
        send.accept(rain);
        LevelEventPacket thunder = new LevelEventPacket();
        thunder.setType(LevelEvent.STOP_THUNDERSTORM);
        thunder.setPosition(Vector3f.ZERO);
        send.accept(thunder);
        SetDifficultyPacket difficulty = new SetDifficultyPacket();
        difficulty.setDifficulty(startGame.getDifficulty());
        send.accept(difficulty);
        SetPlayerGameTypePacket gameType = new SetPlayerGameTypePacket();
        gameType.setGamemode(startGame.getPlayerGameType().ordinal());
        send.accept(gameType);
        GameRulesChangedPacket rules = new GameRulesChangedPacket();
        rules.getGameRules().addAll(startGame.getGamerules());
        send.accept(rules);
    }

    // Ends the dimension change screen in the overworld and spawns the player, as spectrum-internal's Dimension animation does whatever the next server's dimension.
    static void clear(Consumer<BedrockPacket> send, ServerGameData next) {
        spawn(send);
        changeDimension(send, OVERWORLD, next.startGame().getPlayerPosition());
        spawn(send);
    }

    private static void changeDimension(Consumer<BedrockPacket> send, int dimension, Vector3f position) {
        ChangeDimensionPacket change = new ChangeDimensionPacket();
        change.setDimension(dimension);
        change.setPosition(position);
        send.accept(change);
        StopSoundPacket sound = new StopSoundPacket();
        sound.setSoundName("");
        sound.setStoppingAllSound(true);
        send.accept(sound);
        PlayerActionPacket done = new PlayerActionPacket();
        done.setAction(PlayerActionType.DIMENSION_CHANGE_SUCCESS);
        done.setBlockPosition(Vector3i.ZERO);
        done.setResultPosition(Vector3i.ZERO);
        send.accept(done);
    }

    private static void spawn(Consumer<BedrockPacket> send) {
        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        send.accept(status);
    }

    /**
     * spectrum-internal's empty chunk for a dimension: one sub-chunk with no block storages, then biome sections that hold only biome 0 (24 for the end, 16 for the nether and 8 otherwise, as spectrum-internal has them), then a border block count of 0.
     */
    static ByteBuf emptyChunk(int dimension) {
        int sections = switch (dimension) {
            case END -> 24;
            case NETHER -> 16;
            default -> 8;
        };
        ByteBuf chunk = Unpooled.buffer(2 + sections * 515 + 1);
        chunk.writeByte(8).writeByte(0);
        for (int i = 0; i < sections; i++) {
            // One bit per biome, read as a network palette, then a palette of one entry, biome 0.
            chunk.writeByte(1 << 1 | 1);
            chunk.writeZero(512);
            chunk.writeByte(1).writeByte(0);
        }
        chunk.writeByte(0);
        return chunk;
    }
}
