package ac.oomph.boarium.client;

import java.util.Objects;

/**
 * Why NetherNet could not start, with the message ready to log.
 */
final class NetherNetFailure extends Exception {
    // A sentence for the operator, put after what the failure means for players; null for none.
    private final String advice;

    NetherNetFailure(String message, String advice, Throwable cause) {
        super(message, cause);
        this.advice = advice;
    }

    // The message, then what it means for players, such as ", so players can only join over RakNet", then the advice.
    String explain(String consequence) {
        return this.getMessage() + consequence + (this.advice == null ? "" : ". " + this.advice);
    }

    // The failure with its cause, as a native library that does not load comes as the cause of an IOException that does not say why.
    static String reason(Throwable failure) {
        Throwable cause = failure.getCause();
        return failure + (cause == null || Objects.equals(cause.getMessage(), failure.getMessage()) ? "" : " (" + cause + ")");
    }
}
