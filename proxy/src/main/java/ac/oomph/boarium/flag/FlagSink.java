package ac.oomph.boarium.flag;

/**
 * Receives Boar's flags, for example to post them to a webhook. {@link WebhookFlagSink} posts them as JSON.
 * <p>
 * {@link #accept} runs on the player's network thread, so it must not block: queue the flag and send it from a thread of the sink's own.
 */
@FunctionalInterface
public interface FlagSink {
    void accept(Flag flag);
}
