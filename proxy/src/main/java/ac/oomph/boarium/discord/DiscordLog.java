package ac.oomph.boarium.discord;

import ac.oomph.boarium.BoariumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Posts boarium's events to the Discord webhooks that the config's discord section sets.
 * Each webhook posts on its own thread, so a slow or unreachable Discord never holds up a player.
 */
public final class DiscordLog implements AutoCloseable {
    public static final DiscordLog NONE = new DiscordLog(null, Map.of(), 0);
    private static final Logger LOG = LoggerFactory.getLogger(DiscordLog.class);
    // How long close() waits for the queued lines to be posted.
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Map<DiscordEvent, Route> routes;
    private final int flagLimit;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DiscordLog(HttpClient client, Map<DiscordEvent, Route> routes, int flagLimit) {
        this.client = client;
        this.routes = routes;
        this.flagLimit = flagLimit;
    }

    /**
     * Starts a thread for each webhook in {@code config}, or returns {@link #NONE} when no event posts.
     */
    public static DiscordLog start(BoariumConfig.Discord config) {
        if (config.posts().isEmpty()) {
            return NONE;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Map<URI, List<String>> events = new LinkedHashMap<>();
        config.posts().forEach((event, post) -> events.computeIfAbsent(post.webhook(), uri -> new ArrayList<>()).add(event.key()));
        Map<URI, DiscordWebhook> webhooks = new LinkedHashMap<>();
        events.forEach((uri, keys) -> webhooks.put(uri, new DiscordWebhook(client, uri, String.join(", ", keys), config.username())));
        Map<DiscordEvent, Route> routes = new EnumMap<>(DiscordEvent.class);
        config.posts().forEach((event, post) -> routes.put(event, new Route(webhooks.get(post.webhook()), post.message())));
        webhooks.values().forEach(DiscordWebhook::start);
        LOG.info("Posting {} to Discord", config.posts().keySet().stream().map(DiscordEvent::key).collect(Collectors.joining(", ")));
        return new DiscordLog(client, routes, config.flagLimit());
    }

    /**
     * Whether {@code event} is posted.
     */
    public boolean posts(DiscordEvent event) {
        return this.routes.containsKey(event);
    }

    /**
     * How many of Boar's flags a minute, for each player and check, are posted, or 0 for all.
     */
    public int flagLimit() {
        return this.flagLimit;
    }

    /**
     * Queues the message of {@code event} with {@code values} filled in, unless the event is not posted or this log is closed. Never blocks.
     */
    public void post(DiscordEvent event, Map<String, String> values) {
        Route route = this.routes.get(event);
        if (route != null && !this.closed.get()) {
            route.webhook().post(route.message().fill(values));
        }
    }

    /**
     * Posts what is queued, waiting at most 5 seconds, and posts nothing after. Later calls do nothing.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        List<DiscordWebhook> webhooks = this.routes.values().stream().map(Route::webhook).distinct().toList();
        webhooks.forEach(DiscordWebhook::stop);
        try {
            for (DiscordWebhook webhook : webhooks) {
                webhook.await(deadline);
            }
        } catch (InterruptedException e) {
            webhooks.forEach(DiscordWebhook::interrupt);
            Thread.currentThread().interrupt();
        }
        if (this.client != null) {
            this.client.shutdownNow();
        }
    }

    private record Route(DiscordWebhook webhook, MessageTemplate message) {
    }
}
