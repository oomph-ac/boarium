package ac.oomph.boarium.discord;

import org.jose4j.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One webhook's queue of lines, which its own thread posts in order, as many to a Discord message as fit, waiting out Discord's rate limits.
 */
final class DiscordWebhook {
    private static final Logger LOG = LoggerFactory.getLogger(DiscordLog.class);
    // Discord's limit on a message's length, less room for the line that counts dropped lines.
    static final int MAX_LENGTH = 2000 - 100;
    static final int QUEUE_SIZE = 1000;
    private static final int ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long WARN_INTERVAL = TimeUnit.MINUTES.toNanos(1);

    private final HttpClient client;
    private final URI uri;
    private final String name;
    private final String username;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private final AtomicInteger dropped = new AtomicInteger();
    private final Thread thread;
    private volatile boolean closing;
    // Set when Discord says the webhook does not exist or its token is wrong.
    private volatile boolean gone;
    private long lastWarning;
    private int failedSinceWarning;

    // name says which webhook this is in logs, which never show its URL, since anyone who has it can post to its channel.
    DiscordWebhook(HttpClient client, URI uri, String name, String username) {
        this.client = client;
        this.uri = uri;
        this.name = name;
        this.username = username;
        this.thread = Thread.ofVirtual().name("discord-webhook").unstarted(this::run);
    }

    void start() {
        this.thread.start();
    }

    // Never blocks: a line that does not fit in the queue is dropped and counted.
    void post(String line) {
        if (this.gone) {
            return;
        }
        if (!this.lines.offer(line.length() > MAX_LENGTH ? cut(line) + "…" : line)) {
            this.dropped.incrementAndGet();
        }
    }

    // The start of a line that is too long, without half of an emoji.
    private static String cut(String line) {
        int end = MAX_LENGTH - 1;
        return line.substring(0, Character.isHighSurrogate(line.charAt(end - 1)) ? end - 1 : end);
    }

    // Lets the thread end once the queue is empty.
    void stop() {
        this.closing = true;
    }

    // Waits for the queue to be posted, until deadline, a System.nanoTime(), and then stops the thread.
    void await(long deadline) throws InterruptedException {
        if (!this.thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))) {
            this.interrupt();
        }
    }

    void interrupt() {
        this.thread.interrupt();
    }

    private void run() {
        try {
            while (!this.gone && (!this.closing || !this.lines.isEmpty())) {
                String first = this.lines.poll(100, TimeUnit.MILLISECONDS);
                if (first != null) {
                    try {
                        this.send(this.batch(first));
                    } catch (RuntimeException e) {
                        this.warn(e.toString());
                    }
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    private String batch(String first) {
        StringBuilder content = new StringBuilder(first);
        for (String next = this.lines.peek(); next != null && content.length() + 1 + next.length() <= MAX_LENGTH; next = this.lines.peek()) {
            content.append('\n').append(this.lines.poll());
        }
        int dropped = this.dropped.getAndSet(0);
        if (dropped > 0) {
            content.append("\n(").append(dropped).append(dropped == 1 ? " line" : " lines").append(" dropped, as they came faster than Discord takes them)");
        }
        return content.toString();
    }

    private void send(String content) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(this.uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "boarium")
                .POST(HttpRequest.BodyPublishers.ofString(this.json(content)))
                .build();
        int failures = 0;
        while (true) {
            HttpResponse<Void> response;
            try {
                response = this.client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                if (++failures == ATTEMPTS) {
                    String cause = e.getCause() == null || e.getCause().toString().equals(e.toString()) ? "" : ", " + e.getCause();
                    this.warn("could not reach Discord: " + e + cause);
                    return;
                }
                Thread.sleep(backoff(failures));
                continue;
            }
            int status = response.statusCode();
            if (status == 429) {
                // At least 100 ms, so that a Retry-After of 0 does not become a busy loop.
                Thread.sleep(Math.max(100, wait(response.headers().firstValue("Retry-After"))));
                continue;
            }
            if (status >= 500 && ++failures < ATTEMPTS) {
                Thread.sleep(backoff(failures));
                continue;
            }
            if (status == 401 || status == 403 || status == 404) {
                // Discord bans for hours an address that keeps sending it bad requests.
                this.gone = true;
                this.lines.clear();
                LOG.warn("Discord answered {} to a post of {}, so the webhook was deleted or its URL is wrong; boarium posts nothing more to it until it restarts", status, this.name);
                return;
            }
            if (status >= 300) {
                this.warn("Discord answered " + status);
                return;
            }
            if (response.headers().firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent()) {
                Thread.sleep(wait(response.headers().firstValue("X-RateLimit-Reset-After")));
            }
            return;
        }
    }

    // 1 second after the first failure, then twice as long after each.
    private static long backoff(int failures) {
        return 1000L << (failures - 1);
    }

    private String json(String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (this.username != null) {
            body.put("username", this.username);
        }
        // Nobody is pinged, whatever a player's name or a message says.
        body.put("allowed_mentions", Map.of("parse", List.of()));
        return JsonUtil.toJson(body);
    }

    // The milliseconds that a header of Discord's, in seconds with an optional fraction, says to wait, at most a minute, or 1 second when it is missing or not a number.
    static long wait(Optional<String> header) {
        return header.map(seconds -> {
            try {
                double millis = Double.parseDouble(seconds) * 1000;
                return Double.isFinite(millis) && millis >= 0 ? (long) Math.ceil(Math.min(millis, 60_000)) : 1000L;
            } catch (NumberFormatException e) {
                return 1000L;
            }
        }).orElse(1000L);
    }

    // At most one warning a minute, so that an unreachable Discord does not fill the console.
    private void warn(String reason) {
        long now = System.nanoTime();
        if (this.failedSinceWarning > 0 && now - this.lastWarning < WARN_INTERVAL) {
            this.failedSinceWarning++;
            return;
        }
        int earlier = this.failedSinceWarning - 1;
        LOG.warn("Dropped a Discord post of {}: {}{}", this.name, reason, earlier > 0 ? " (" + earlier + " more dropped since the last warning)" : "");
        this.lastWarning = now;
        this.failedSinceWarning = 1;
    }
}
