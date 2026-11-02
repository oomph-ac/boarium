package ac.oomph.boarium.client;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * Disconnects a client at most once, since Cloudburst keeps a session connected for seconds after its Disconnect and would send a second one.
 * Each method takes the connection's primary session, as the state belongs to the whole connection.
 */
public final class ClientDisconnect {
    private static final AttributeKey<CompletableFuture<Void>> ENDED = AttributeKey.valueOf(ClientDisconnect.class.getName());

    private ClientDisconnect() {
    }

    /**
     * Sends the client a Disconnect with {@code reason}, or one it does not show when {@code reason} is null, unless it was already disconnected or its connection closed.
     * The work runs on the client's event loop.
     */
    public static void disconnect(BedrockServerSession session, String reason) {
        Channel channel = session.getPeer().getChannel();
        try {
            channel.eventLoop().execute(() -> {
                CompletableFuture<Void> ended = ended(session);
                if (ended.isDone()) {
                    return;
                }
                if (session.isConnected()) {
                    session.disconnect(reason, false);
                }
                ended.complete(null);
            });
        } catch (RejectedExecutionException e) {
            // The event loop has shut down, and the connection with it.
            ended(session).complete(null);
        }
    }

    /**
     * Completes once the client is disconnected or its connection closes, on the client's event loop unless that loop has shut down.
     */
    public static CompletableFuture<Void> ended(BedrockServerSession session) {
        Channel channel = session.getPeer().getChannel();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        CompletableFuture<Void> existing = channel.attr(ENDED).setIfAbsent(ended);
        if (existing != null) {
            return existing;
        }
        channel.closeFuture().addListener(closed -> ended.complete(null));
        return ended;
    }
}
