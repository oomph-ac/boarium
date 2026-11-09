package ac.oomph.boarium.discord;

import ac.oomph.boarium.BoariumConfig;
import com.sun.net.httpserver.HttpServer;
import org.jose4j.json.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class DiscordLogTest {
    private final FakeDiscord discord = new FakeDiscord();

    @AfterEach
    void stop() {
        this.discord.release();
        this.discord.server.stop(0);
    }

    @Test
    void postsEachLineAsJsonThatPingsNobody() throws Exception {
        try (DiscordLog log = this.start("boarium", DiscordEvent.JOIN, DiscordEvent.LEAVE)) {
            assertTrue(log.posts(DiscordEvent.JOIN));
            assertFalse(log.posts(DiscordEvent.FLAG));
            log.post(DiscordEvent.JOIN, Map.of("player", "Steve", "server", "lobby"));
            Map<String, Object> body = this.discord.next();
            assertEquals("Steve joined lobby", body.get("content"));
            assertEquals("boarium", body.get("username"));
            assertEquals(Map.of("parse", List.of()), body.get("allowed_mentions"));
            log.post(DiscordEvent.FLAG, Map.of("player", "Steve"));
            log.post(DiscordEvent.LEAVE, Map.of("player", "Steve"));
            assertEquals("Steve left", this.discord.next().get("content"));
        }
    }

    @Test
    void leavesTheWebhooksOwnNameWithoutAUsername() throws Exception {
        try (DiscordLog log = this.start(null, DiscordEvent.STOP)) {
            log.post(DiscordEvent.STOP, Map.of());
            Map<String, Object> body = this.discord.next();
            assertEquals("boarium stopped", body.get("content"));
            assertFalse(body.containsKey("username"));
        }
    }

    @Test
    void postsTheLinesQueuedDuringAPostTogether() throws Exception {
        this.discord.hold();
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "p0"));
            assertEquals("p0 left", this.discord.next().get("content"));
            for (int i = 1; i < 50; i++) {
                log.post(DiscordEvent.LEAVE, Map.of("player", "p" + i));
            }
            this.discord.release();
            List<String> lines = new ArrayList<>();
            while (lines.size() < 49) {
                String content = (String) this.discord.next().get("content");
                assertTrue(content.length() <= 2000);
                lines.addAll(Arrays.asList(content.split("\n")));
            }
            assertEquals(49, lines.size());
            for (int i = 0; i < 49; i++) {
                assertEquals("p" + (i + 1) + " left", lines.get(i));
            }
            assertEquals(2, this.discord.requests());
        }
    }

    @Test
    void splitsLinesThatDoNotFitInOneMessage() throws Exception {
        this.discord.hold();
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "first"));
            assertEquals("first left", this.discord.next().get("content"));
            String name = "x".repeat(700);
            for (int i = 0; i < 3; i++) {
                log.post(DiscordEvent.LEAVE, Map.of("player", name));
            }
            // Longer than a message can be, so it is cut.
            log.post(DiscordEvent.LEAVE, Map.of("player", "y".repeat(3000)));
            this.discord.release();
            assertEquals((name + " left\n").repeat(2).strip(), this.discord.next().get("content"));
            assertEquals(name + " left", this.discord.next().get("content"));
            String cut = (String) this.discord.next().get("content");
            assertEquals(DiscordWebhook.MAX_LENGTH, cut.length());
            assertTrue(cut.endsWith("y…"));
        }
    }

    @Test
    void cutsALongLineBetweenCharacters() throws Exception {
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "😀".repeat(1500)));
            String cut = (String) this.discord.next().get("content");
            assertTrue(cut.endsWith("😀…"), cut.substring(cut.length() - 4));
        }
    }

    @Test
    void readsDiscordsWaitInSeconds() {
        assertEquals(300, DiscordWebhook.wait(Optional.of("0.3")));
        assertEquals(60_000, DiscordWebhook.wait(Optional.of("1e300")));
        assertEquals(1000, DiscordWebhook.wait(Optional.of("-1")));
        assertEquals(1000, DiscordWebhook.wait(Optional.of("soon")));
        assertEquals(1000, DiscordWebhook.wait(Optional.empty()));
    }

    @Test
    void waitsOutDiscordsRateLimit() throws Exception {
        this.discord.answer(429, "Retry-After", "0.3");
        try (DiscordLog log = this.start("boarium", DiscordEvent.STOP)) {
            log.post(DiscordEvent.STOP, Map.of());
            this.discord.next();
            long limited = System.nanoTime();
            assertEquals("boarium stopped", this.discord.next().get("content"));
            assertTrue(System.nanoTime() - limited >= TimeUnit.MILLISECONDS.toNanos(250));
        }
    }

    @Test
    void waitsWhenNoPostsAreLeftInTheBucket() throws Exception {
        this.discord.answer(204, "X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "0.3");
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "a"));
            this.discord.next();
            long emptied = System.nanoTime();
            log.post(DiscordEvent.LEAVE, Map.of("player", "b"));
            assertEquals("b left", this.discord.next().get("content"));
            assertTrue(System.nanoTime() - emptied >= TimeUnit.MILLISECONDS.toNanos(250));
        }
    }

    @Test
    void triesAgainAfterAServerError() throws Exception {
        this.discord.answer(502);
        try (DiscordLog log = this.start("boarium", DiscordEvent.STOP)) {
            log.post(DiscordEvent.STOP, Map.of());
            this.discord.next();
            assertEquals("boarium stopped", this.discord.next().get("content"));
        }
    }

    @Test
    void dropsAPostThatDiscordTurnsAwayAndGoesOn() throws Exception {
        this.discord.answer(400);
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "a"));
            assertEquals("a left", this.discord.next().get("content"));
            log.post(DiscordEvent.LEAVE, Map.of("player", "b"));
            assertEquals("b left", this.discord.next().get("content"));
            assertNull(this.discord.bodies.poll(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void stopsPostingToAWebhookThatIsGone() throws Exception {
        for (int status : new int[]{401, 403, 404}) {
            this.discord.hold();
            this.discord.answer(status);
            try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
                log.post(DiscordEvent.LEAVE, Map.of("player", "a"));
                assertEquals("a left", this.discord.next().get("content"));
                log.post(DiscordEvent.LEAVE, Map.of("player", "b"));
                this.discord.release();
                assertNull(this.discord.bodies.poll(1500, TimeUnit.MILLISECONDS), "after " + status);
                log.post(DiscordEvent.LEAVE, Map.of("player", "c"));
                assertNull(this.discord.bodies.poll(500, TimeUnit.MILLISECONDS), "after " + status);
            }
        }
    }

    @Test
    void countsTheLinesDroppedFromAFullQueue() throws Exception {
        this.discord.hold();
        try (DiscordLog log = this.start("boarium", DiscordEvent.LEAVE)) {
            log.post(DiscordEvent.LEAVE, Map.of("player", "first"));
            this.discord.next();
            for (int i = 0; i < DiscordWebhook.QUEUE_SIZE + 7; i++) {
                log.post(DiscordEvent.LEAVE, Map.of("player", "p"));
            }
            this.discord.release();
            String content = (String) this.discord.next().get("content");
            assertTrue(content.length() <= 2000);
            assertTrue(content.endsWith("\n(7 lines dropped, as they came faster than Discord takes them)"), content);
        }
    }

    @Test
    void postsWhatIsQueuedWhenClosedAndNothingAfter() throws Exception {
        this.discord.hold();
        DiscordLog log = this.start("boarium", DiscordEvent.LEAVE);
        log.post(DiscordEvent.LEAVE, Map.of("player", "a"));
        this.discord.next();
        log.post(DiscordEvent.LEAVE, Map.of("player", "b"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            this.discord.release();
        });
        log.close();
        assertEquals("b left", this.discord.next().get("content"));
        log.post(DiscordEvent.LEAVE, Map.of("player", "c"));
        assertNull(this.discord.bodies.poll(300, TimeUnit.MILLISECONDS));
        log.close();
    }

    @Test
    void stopsWaitingForDiscordWhenClosed() {
        this.discord.hold();
        DiscordLog log = this.start("boarium", DiscordEvent.LEAVE);
        log.post(DiscordEvent.LEAVE, Map.of("player", "a"));
        long start = System.nanoTime();
        log.close();
        // close() waits at most 5 seconds.
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(7));
        this.discord.release();
    }

    @Test
    void closesAtOnceWhenNothingIsQueued() {
        DiscordLog log = this.start("boarium", DiscordEvent.LEAVE);
        long start = System.nanoTime();
        log.close();
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void postsNothingWithoutAWebhook() {
        DiscordLog log = DiscordLog.start(new BoariumConfig.Discord("boarium", Map.of(), 2));
        assertEquals(DiscordLog.NONE, log);
        for (DiscordEvent event : DiscordEvent.values()) {
            assertFalse(log.posts(event));
        }
        log.post(DiscordEvent.STOP, Map.of());
        log.close();
    }

    private DiscordLog start(String username, DiscordEvent... events) {
        Map<DiscordEvent, BoariumConfig.Discord.Post> posts = new EnumMap<>(DiscordEvent.class);
        for (DiscordEvent event : events) {
            posts.put(event, new BoariumConfig.Discord.Post(this.discord.uri, MessageTemplate.parse(event.defaultMessage(), event.placeholders())));
        }
        return DiscordLog.start(new BoariumConfig.Discord(username, posts, 2));
    }

    // A webhook on this machine that records each post's JSON body and answers 204, or each queued answer first.
    private static final class FakeDiscord {
        final BlockingQueue<Map<String, Object>> bodies = new LinkedBlockingQueue<>();
        private final BlockingQueue<String[]> answers = new LinkedBlockingQueue<>();
        private final HttpServer server;
        private final URI uri;
        private volatile CountDownLatch held = new CountDownLatch(0);
        private int requests;

        FakeDiscord() {
            try {
                this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.server.createContext("/api/webhooks/1/token", exchange -> {
                synchronized (this) {
                    this.requests++;
                }
                try {
                    this.bodies.add(JsonUtil.parseJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    this.held.await();
                } catch (Exception e) {
                    throw new IOException(e);
                }
                String[] answer = this.answers.poll();
                int status = answer == null ? 204 : Integer.parseInt(answer[0]);
                for (int i = 1; answer != null && i < answer.length; i += 2) {
                    exchange.getResponseHeaders().add(answer[i], answer[i + 1]);
                }
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            // Each post on its own thread, so that a held one does not stop the server from stopping.
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.start();
            this.uri = URI.create("http://" + this.server.getAddress().getAddress().getHostAddress() + ":" + this.server.getAddress().getPort() + "/api/webhooks/1/token");
        }

        // The next post's body; each post is answered only after release() while held.
        Map<String, Object> next() throws InterruptedException {
            Map<String, Object> body = this.bodies.poll(10, TimeUnit.SECONDS);
            if (body == null) {
                throw new AssertionError("no post came");
            }
            return body;
        }

        void hold() {
            this.held = new CountDownLatch(1);
        }

        void release() {
            this.held.countDown();
        }

        // The answer to the next post: a status, then header names and values.
        void answer(int status, String... headers) {
            String[] answer = new String[headers.length + 1];
            answer[0] = String.valueOf(status);
            System.arraycopy(headers, 0, answer, 1, headers.length);
            this.answers.add(answer);
        }

        synchronized int requests() {
            return this.requests;
        }
    }
}
