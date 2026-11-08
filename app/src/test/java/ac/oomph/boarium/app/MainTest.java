package ac.oomph.boarium.app;

import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.session.FakeSpectrumServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The tests run the installed start script, which is a shell script.
@DisabledOnOs(OS.WINDOWS)
class MainTest {
    private static final Pattern LISTENING = Pattern.compile("listening on \\S*:(\\d+),");

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> output = new CopyOnWriteArrayList<>();
    private Thread reader;

    // Runs the installed boarium with Boar on, joins it, and stops it as a service manager would.
    @Test
    void runsBoarAndShutsDown(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = this.start(dir, server.address());
        try {
            this.awaitLine("Boar started with its config in " + dir.resolve("boar") + ", mitigations off");
            this.awaitLine("players can also join over NetherNet");
            Matcher listening = LISTENING.matcher(this.awaitLine("listening on "));
            assertTrue(listening.find());
            assertTrue(Files.exists(dir.resolve("boar").resolve("config.yml")));

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(listening.group(1))));
            client.loginDone.get(10, TimeUnit.SECONDS);
            StartGamePacket startGame = client.await(StartGamePacket.class);
            RequestChunkRadiusPacket radius = new RequestChunkRadiusPacket();
            radius.setRadius(8);
            client.session.get().sendPacket(radius);
            client.await(PlayStatusPacket.class);
            SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
            initialized.setRuntimeEntityId(startGame.getRuntimeEntityId());
            client.session.get().sendPacket(initialized);
            this.awaitLine("Tester joined");
            // Only Boar sends the client NetworkStackLatency here; the fake server does not.
            assertTrue(client.await(NetworkStackLatencyPacket.class).isFromServer());

            // Process.destroy() would also close the output, which loses what the shutdown logs.
            process.toHandle().destroy();
            assertEquals("boarium closed.", client.await(DisconnectPacket.class).getKickMessage());
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            // 128 + SIGTERM's 15
            assertEquals(143, process.exitValue());
            this.awaitLine("shutting down");
            assertTrue(this.output.stream().noneMatch(line -> line.contains(" ERROR ") || line.contains("Exception")), String.join("\n", this.output));
        } finally {
            process.destroyForcibly();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void stopsWhenBoarCannotStart(@TempDir Path dir) throws Exception {
        Path folder = Files.writeString(dir.resolve("boar"), "");
        Process process = this.start(dir, "127.0.0.1:1");
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            this.awaitLine("could not start: java.io.IOException: Boar's data folder " + folder + " is a file");
        } finally {
            process.destroyForcibly();
        }
    }

    // A NetherNet native library that cannot load leaves boarium taking players over RakNet alone.
    @Test
    void takesRakNetPlayersWhenNetherNetCannotLoad(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        // libdatachannel loads its native library from the file this property names, when it is set.
        Process process = this.start(dir, server.address(), "-Dlibdatachannel.native.datachannel-java.path=" + dir.resolve("missing.dylib"));
        try {
            String error = this.awaitLine("NetherNet could not start");
            assertTrue(error.contains(" ERROR ") && error.contains("UnsatisfiedLinkError"), error);
            Matcher listening = LISTENING.matcher(this.awaitLine("listening on "));
            assertTrue(listening.find());
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", Integer.parseInt(listening.group(1)));
            try (Socket socket = new Socket()) {
                assertThrows(ConnectException.class, () -> socket.connect(address, 5000));
            }

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, address);
            client.loginDone.get(10, TimeUnit.SECONDS);
            assertEquals(1, this.output.stream().filter(line -> line.contains(" ERROR ")).count(), String.join("\n", this.output));
        } finally {
            process.destroyForcibly();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private Process start(Path dir, String server) throws Exception {
        return this.start(dir, server, "");
    }

    // Starts the installed boarium in dir, on a free loopback port, with Boar on and javaOpts given to its JVM.
    private Process start(Path dir, String server, String javaOpts) throws Exception {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, "listen: 127.0.0.1:0\nserver: " + server + "\nxbox_auth: false\n");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("boarium.install"), "bin", "boarium").toString(), config.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", javaOpts);
        Process process = builder.start();
        this.reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                in.lines().forEach(line -> {
                    this.output.add(line);
                    this.lines.add(line);
                });
            } catch (Exception e) {
                // The process has ended.
            }
        });
        this.reader.setDaemon(true);
        this.reader.start();
        return process;
    }

    private String awaitLine(String part) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            String line = this.lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new IllegalStateException("boarium did not log \"" + part + "\" within 30 seconds:\n" + String.join("\n", this.output));
            }
            if (line.contains(part)) {
                return line;
            }
        }
    }
}
