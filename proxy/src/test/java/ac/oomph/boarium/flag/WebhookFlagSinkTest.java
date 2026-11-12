package ac.oomph.boarium.flag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WebhookFlagSinkTest {
    private record Post(String authorization, String contentType, JsonObject body) {
    }

    private HttpServer server;
    private final BlockingQueue<Post> posts = new LinkedBlockingQueue<>();
    // The status each post is answered with; 200 unless a test sets it.
    private volatile IntSupplier status = () -> 200;
    private volatile String retryAfter;
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private WebhookFlagSink sink;

    @BeforeEach
    void start() throws Exception {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/flags", exchange -> {
            try {
                this.hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            this.posts.add(new Post(exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"),
                    new JsonParser().parse(body).getAsJsonObject()));
            if (this.retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", this.retryAfter);
            }
            exchange.sendResponseHeaders(this.status.getAsInt(), -1);
            exchange.close();
        });
        this.server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        this.server.start();
    }

    @AfterEach
    void stop() {
        this.hold.countDown();
        if (this.sink != null) {
            this.sink.close();
        }
        this.server.stop(0);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/flags");
    }

    private WebhookFlagSink sink(String secret) {
        this.sink = new WebhookFlagSink(this.url(), secret, WebhookFlagSink.Format.JSON, Duration.ofMillis(10));
        return this.sink;
    }

    static Flag flag(String player, int vl) {
        return new Flag(Instant.parse("2026-11-11T12:00:00Z"), "practice:19133", player, "2535400000000001", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Bad Packet", "B", false, vl, "claimedPitch=100.0, wrappedPitch=90.0", false, 3);
    }

    private Post await() throws InterruptedException {
        Post post = this.posts.poll(10, TimeUnit.SECONDS);
        assertNotNull(post, "no post within 10 seconds");
        return post;
    }

    @Test
    void postsEachFieldAsJsonWithTheSecret() throws Exception {
        this.sink("s3cret").accept(flag("Steve", 1));
        Post post = this.await();
        assertEquals("Bearer s3cret", post.authorization());
        assertEquals("application/json", post.contentType());
        assertEquals(0, post.body().get("dropped").getAsInt());
        JsonObject flag = post.body().getAsJsonArray("flags").get(0).getAsJsonObject();
        assertEquals("2026-11-11T12:00:00Z", flag.get("time").getAsString());
        assertEquals("practice:19133", flag.get("server").getAsString());
        assertEquals("Steve", flag.get("player").getAsString());
        assertEquals("2535400000000001", flag.get("xuid").getAsString());
        assertEquals("00000000-0000-0000-0000-000000000001", flag.get("uuid").getAsString());
        assertEquals("Bad Packet", flag.get("check").getAsString());
        assertEquals("B", flag.get("type").getAsString());
        assertFalse(flag.get("experimental").getAsBoolean());
        assertEquals(1, flag.get("vl").getAsInt());
        assertEquals("claimedPitch=100.0, wrappedPitch=90.0", flag.get("details").getAsString());
        assertFalse(flag.get("mitigations").getAsBoolean());
        assertEquals(3, flag.get("held_back").getAsInt());
    }

    @Test
    void sendsNoAuthorizationWithoutASecret() throws Exception {
        this.sink(null).accept(flag("Steve", 1));
        assertNull(this.await().authorization());
    }

    // Flags that come while a post is on its way go together in the next one.
    @Test
    void batchesFlagsThatComeDuringAPost() throws Exception {
        this.hold = new CountDownLatch(1);
        WebhookFlagSink sink = this.sink(null);
        sink.accept(flag("Steve", 1));
        Thread.sleep(200);
        for (int i = 2; i <= 6; i++) {
            sink.accept(flag("Steve", i));
        }
        this.hold.countDown();
        assertEquals(1, this.await().body().getAsJsonArray("flags").size());
        assertEquals(5, this.await().body().getAsJsonArray("flags").size());
    }

    @Test
    void triesAgainAfterAServerErrorOrA429() throws Exception {
        AtomicInteger answers = new AtomicInteger();
        this.status = () -> switch (answers.getAndIncrement()) {
            case 0 -> 503;
            case 1 -> 429;
            default -> 204;
        };
        this.retryAfter = "0";
        this.sink(null).accept(flag("Steve", 1));
        for (int i = 0; i < 3; i++) {
            assertEquals("Steve", this.await().body().getAsJsonArray("flags").get(0).getAsJsonObject().get("player").getAsString());
        }
        assertNull(this.posts.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void dropsAPostTheEndpointTurnsAwayAndPostsTheNext() throws Exception {
        AtomicInteger answers = new AtomicInteger();
        this.status = () -> answers.getAndIncrement() == 0 ? 400 : 200;
        WebhookFlagSink sink = this.sink(null);
        sink.accept(flag("Steve", 1));
        assertEquals(1, this.await().body().getAsJsonArray("flags").get(0).getAsJsonObject().get("vl").getAsInt());
        sink.accept(flag("Steve", 2));
        assertEquals(2, this.await().body().getAsJsonArray("flags").get(0).getAsJsonObject().get("vl").getAsInt());
        assertNull(this.posts.poll(300, TimeUnit.MILLISECONDS));
    }

    // A full queue drops flags without blocking the player's thread, and the next post counts them.
    @Test
    void countsTheFlagsAFullQueueDrops() throws Exception {
        this.hold = new CountDownLatch(1);
        WebhookFlagSink sink = this.sink(null);
        sink.accept(flag("Steve", 0));
        Thread.sleep(200);
        long start = System.nanoTime();
        for (int i = 1; i <= WebhookFlagSink.QUEUE + 25; i++) {
            sink.accept(flag("Steve", i));
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        this.hold.countDown();
        assertEquals(0, this.await().body().get("dropped").getAsInt());
        int flags = 0;
        int dropped = 0;
        while (flags < WebhookFlagSink.QUEUE) {
            JsonObject body = this.await().body();
            flags += body.getAsJsonArray("flags").size();
            dropped += body.get("dropped").getAsInt();
            assertTrue(body.getAsJsonArray("flags").size() <= WebhookFlagSink.BATCH);
        }
        assertEquals(WebhookFlagSink.QUEUE, flags);
        assertEquals(25, dropped);
    }

    @Test
    void closePostsTheFlagsStillQueued() throws Exception {
        WebhookFlagSink sink = this.sink(null);
        for (int i = 1; i <= 3; i++) {
            sink.accept(flag("Steve", i));
        }
        sink.close();
        int flags = 0;
        while (flags < 3) {
            flags += this.await().body().getAsJsonArray("flags").size();
        }
        // A closed sink takes no more flags.
        sink.accept(flag("Steve", 4));
        assertNull(this.posts.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void keepsTheUrlOutOfToString() {
        WebhookFlagSink sink = new WebhookFlagSink(URI.create("https://hooks.example.com/token-abc"), null, WebhookFlagSink.Format.JSON);
        try {
            assertEquals("WebhookFlagSink[https://hooks.example.com/…]", sink.toString());
        } finally {
            sink.close();
        }
    }

    // A Discord webhook takes a message in content; values are escaped so that a name cannot format it or mention anyone.
    @Test
    void writesDiscordMessagesWithEscapedValuesAndNoMentions() {
        Flag flag = new Flag(Instant.EPOCH, "practice:19133", "__Evil*_@everyone", "", UUID.randomUUID(), "Reach", "A", true, 3, "distance=4.5 > 3", true, 2);
        var messages = WebhookFlagSink.discordMessages(java.util.List.of(flag), 4);
        assertEquals(1, messages.size());
        assertEquals(5, messages.getFirst().flags());
        JsonObject body = new JsonParser().parse(messages.getFirst().body()).getAsJsonObject();
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals("(4 flags dropped while the webhook was slow)\n**\\_\\_Evil\\*\\_@everyone** failed Reach (A) (Experimental) x3 distance=4.5 \\> 3 on practice\\:19133 [mitigations on] (+2 more)",
                body.get("content").getAsString());
    }

    @Test
    void splitsDiscordMessagesAtTheirLimit() {
        var batch = new java.util.ArrayList<Flag>();
        for (int i = 0; i < WebhookFlagSink.BATCH; i++) {
            batch.add(flag("Steve", i));
        }
        var messages = WebhookFlagSink.discordMessages(batch, 0);
        assertTrue(messages.size() > 1);
        int flags = 0;
        for (var message : messages) {
            String content = new JsonParser().parse(message.body()).getAsJsonObject().get("content").getAsString();
            assertTrue(content.length() <= WebhookFlagSink.DISCORD_LIMIT, String.valueOf(content.length()));
            assertEquals(content.lines().count(), message.flags());
            flags += message.flags();
        }
        assertEquals(WebhookFlagSink.BATCH, flags);
    }

    @Test
    void postsDiscordMessagesToTheWebhook() throws Exception {
        this.sink = new WebhookFlagSink(this.url(), null, WebhookFlagSink.Format.DISCORD, Duration.ofMillis(10));
        this.sink.accept(flag("Steve", 1));
        JsonObject body = this.await().body();
        assertTrue(body.get("content").getAsString().startsWith("**Steve** failed Bad Packet (B) x1"), body.toString());
    }
}
