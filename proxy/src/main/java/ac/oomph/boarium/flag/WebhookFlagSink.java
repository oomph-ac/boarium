package ac.oomph.boarium.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Posts Boar's flags to an HTTP endpoint as JSON, from a thread of its own, so a slow or unreachable endpoint never holds up a player.
 * <p>
 * Each post is {@code {"flags": [...], "dropped": N}}, with the flags that came while the last post was on its way, up to {@link #BATCH}.
 * {@code dropped} counts the flags lost since the last post because the queue was full. A post is tried {@link #ATTEMPTS} times when the endpoint
 * cannot be reached, answers 429 or a server error, and is then dropped; any other answer outside 2xx drops it at once.
 * boarium logs a warning for a dropped post at most once a minute, without the URL, which may hold a token.
 */
public final class WebhookFlagSink implements FlagSink, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookFlagSink.class);
    static final int QUEUE = 1000;
    static final int BATCH = 100;
    static final int ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(1);
    private static final long WARNING_INTERVAL = TimeUnit.MINUTES.toNanos(1);
    // How long close waits for the flags still queued.
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final URI url;
    private final String secret;
    private final Format format;
    private final Duration retryDelay;
    private final HttpClient http;
    private final BlockingQueue<Flag> queue = new ArrayBlockingQueue<>(QUEUE);
    private final AtomicInteger dropped = new AtomicInteger();
    private final Thread sender;
    private volatile boolean closed;
    private long lastWarning = System.nanoTime() - WARNING_INTERVAL;
    private int unwarned;

    /** What a post's body is. */
    public enum Format {
        /** {@code {"flags": [...], "dropped": N}}, for an endpoint of your own. */
        JSON,
        /** Discord's webhook message: one line per flag in {@code content}, in as many messages as Discord's 2000 characters need, mentioning no one. */
        DISCORD
    }

    /**
     * @param secret sent as {@code Authorization: Bearer <secret>}, or null for none
     */
    public WebhookFlagSink(URI url, String secret, Format format) {
        this(url, secret, format, Duration.ofSeconds(1));
    }

    // Tests shorten the wait between attempts.
    WebhookFlagSink(URI url, String secret, Format format, Duration retryDelay) {
        this.url = url;
        this.secret = secret;
        this.format = format;
        this.retryDelay = retryDelay;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        this.sender = Thread.ofVirtual().name("boarium-flag-webhook").start(this::run);
    }

    @Override
    public void accept(Flag flag) {
        if (this.closed || !this.queue.offer(flag)) {
            this.dropped.incrementAndGet();
        }
    }

    /** Stops taking flags, and waits up to 5 seconds for those still queued to be posted. */
    @Override
    public void close() {
        this.closed = true;
        try {
            if (!this.sender.join(CLOSE_WAIT)) {
                this.sender.interrupt();
            }
        } catch (InterruptedException e) {
            this.sender.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            while (!this.closed || !this.queue.isEmpty()) {
                Flag first = this.queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Flag> batch = new ArrayList<>(BATCH);
                batch.add(first);
                this.queue.drainTo(batch, BATCH - 1);
                this.post(batch, this.dropped.getAndSet(0));
            }
        } catch (InterruptedException e) {
            // close() ran out of time.
        }
    }

    private void post(List<Flag> batch, int dropped) throws InterruptedException {
        if (this.format == Format.JSON) {
            this.send(body(batch, dropped), batch.size() + dropped);
            return;
        }
        for (DiscordMessage message : discordMessages(batch, dropped)) {
            this.send(message.body(), message.flags());
        }
    }

    private void send(String body, int flags) throws InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(this.url)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "boarium")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (this.secret != null) {
            request.header("Authorization", "Bearer " + this.secret);
        }
        String failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Duration wait = this.retryDelay.multipliedBy(1L << (attempt - 1));
            try {
                HttpResponse<Void> response = this.http.send(request.build(), HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return;
                }
                failure = "the endpoint answered " + status;
                if (status != 429 && status / 100 != 5) {
                    break;
                }
                if (status == 429) {
                    wait = retryAfter(response).orElse(wait);
                }
            } catch (IOException e) {
                failure = e.toString();
            }
            if (attempt < ATTEMPTS) {
                Thread.sleep(wait.toMillis());
            }
        }
        this.warn(flags, failure);
    }

    // At most one warning a minute, which counts the flags lost since the last one.
    private void warn(int lost, String failure) {
        this.unwarned += lost;
        long now = System.nanoTime();
        if (now - this.lastWarning >= WARNING_INTERVAL) {
            LOG.warn("could not post {} of Boar's flags to flag_webhook: {}", this.unwarned, failure);
            this.lastWarning = now;
            this.unwarned = 0;
        }
    }

    // Discord's Retry-After can have a fraction of a second.
    private static java.util.Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                double seconds = Double.parseDouble(value.trim());
                long millis = (long) Math.ceil(Math.max(0, Math.min(seconds, MAX_RETRY_AFTER.toSeconds())) * 1000);
                return java.util.Optional.of(Duration.ofMillis(millis));
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    static String body(List<Flag> batch, int dropped) {
        JsonArray flags = new JsonArray();
        for (Flag flag : batch) {
            JsonObject json = new JsonObject();
            json.addProperty("time", flag.time().toString());
            json.addProperty("server", flag.server());
            json.addProperty("player", flag.player());
            json.addProperty("xuid", flag.xuid());
            json.addProperty("uuid", flag.uuid().toString());
            json.addProperty("check", flag.check());
            json.addProperty("type", flag.type());
            json.addProperty("experimental", flag.experimental());
            json.addProperty("vl", flag.vl());
            json.addProperty("details", flag.details());
            json.addProperty("mitigations", flag.mitigations());
            json.addProperty("held_back", flag.heldBack());
            flags.add(json);
        }
        JsonObject body = new JsonObject();
        body.add("flags", flags);
        body.addProperty("dropped", dropped);
        return body.toString();
    }

    static final int DISCORD_LIMIT = 2000;
    // Discord's markdown, and the characters that start a quote, heading or list at the start of a line.
    private static final java.util.regex.Pattern MARKDOWN = java.util.regex.Pattern.compile("([\\\\*_~`|>#\\-\\[\\]()<:])");

    // A message's body, and how many flags it carries, which a failed post counts as lost.
    record DiscordMessage(String body, int flags) {
    }

    // One line per flag, in messages of at most DISCORD_LIMIT characters. Filled-in values are escaped, so that a player's name cannot format the message or mention anyone.
    static List<DiscordMessage> discordMessages(List<Flag> batch, int dropped) {
        List<String> lines = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        if (dropped > 0) {
            lines.add("(" + dropped + " flags dropped while the webhook was slow)");
            counts.add(dropped);
        }
        for (Flag flag : batch) {
            StringBuilder line = new StringBuilder("**").append(escape(flag.player())).append("** failed ").append(escape(flag.check()));
            if (!flag.type().isBlank()) {
                line.append(" (").append(escape(flag.type())).append(')');
            }
            if (flag.experimental()) {
                line.append(" (Experimental)");
            }
            line.append(" x").append(flag.vl());
            if (!flag.details().isBlank()) {
                line.append(' ').append(escape(flag.details()));
            }
            line.append(" on ").append(escape(flag.server())).append(flag.mitigations() ? " [mitigations on]" : " [mitigations off]");
            if (flag.heldBack() > 0) {
                line.append(" (+").append(flag.heldBack()).append(" more)");
            }
            String text = line.toString();
            lines.add(text.length() > DISCORD_LIMIT ? text.substring(0, DISCORD_LIMIT - 1) + "…" : text);
            counts.add(1);
        }
        List<DiscordMessage> messages = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        int flags = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!content.isEmpty() && content.length() + 1 + line.length() > DISCORD_LIMIT) {
                messages.add(new DiscordMessage(discordBody(content.toString()), flags));
                content.setLength(0);
                flags = 0;
            }
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(line);
            flags += counts.get(i);
        }
        if (!content.isEmpty()) {
            messages.add(new DiscordMessage(discordBody(content.toString()), flags));
        }
        return messages;
    }

    private static String escape(String text) {
        return MARKDOWN.matcher(text.replaceAll("\\s+", " ")).replaceAll("\\\\$1");
    }

    private static String discordBody(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        return body.toString();
    }

    // The URL may hold a token.
    @Override
    public String toString() {
        return "WebhookFlagSink[" + this.url.getScheme() + "://" + this.url.getHost() + "/…]";
    }
}
