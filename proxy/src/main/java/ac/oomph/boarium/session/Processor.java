package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.List;
import java.util.Set;

/**
 * Sees and changes a player's traffic, as spectrum-internal's Processor does. Each method runs on the client's event loop, and does nothing by default.
 * Packets a processor sends itself, through {@link Session#sendToClient} and {@link Session#sendToServer}, do not come back to it.
 */
public interface Processor {
    Processor NONE = new Processor() {
    };

    /**
     * The IDs of the client packets to decode before {@link #client}, as spectrum-internal's client_decode; other client packets pass as their bytes.
     */
    default Set<Integer> clientDecode() {
        return Set.of();
    }

    /**
     * The IDs of the server packets to decode before {@link #server}. Packets the server marks for decoding are always decoded.
     */
    default Set<Integer> serverDecode() {
        return Set.of();
    }

    /**
     * Called once, before the client is sent the StartGame of the first server, which the processor can change.
     */
    default void startGame(Session session, StartGamePacket startGame) {
    }

    /**
     * Called with each batch the client sends, before it goes on to the server.
     */
    default void client(Session session, List<PacketContext> batch) {
    }

    /**
     * Called with each packet the server sends, before it goes on to the client.
     */
    default void server(Session session, PacketContext packet) {
    }

    /**
     * Called when the server asks for the client's packets to be flushed, before they are.
     */
    default void flush(Session session) {
    }

    /**
     * Called when a transfer starts; {@code origin} is null when the player has no server.
     */
    default void preTransfer(Session session, String origin, String target) {
    }

    /**
     * Called once the player has spawned on the transfer's target; {@code origin} is null when the player had no server.
     */
    default void postTransfer(Session session, String origin, String target) {
    }

    /**
     * Called when a transfer fails while the client is still there; {@code origin} is null when the player had no server.
     */
    default void transferFailure(Session session, String origin, String target) {
    }

    /**
     * Called once the client has been disconnected or its connection closed.
     */
    default void disconnect(Session session) {
    }
}
