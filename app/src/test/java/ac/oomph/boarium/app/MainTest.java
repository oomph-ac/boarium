package ac.oomph.boarium.app;

import ac.oomph.boarium.client.TestClient;
import ac.oomph.boarium.session.FakeSpectrumServer;
import ac.oomph.boarium.transport.Addresses;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The tests run the installed start script, which is a shell script.
@DisabledOnOs(OS.WINDOWS)
class MainTest {
    private static final Pattern RAKNET = Pattern.compile("RakNet on UDP 127\\.0\\.0\\.1:(\\d+)$");
    private static final Pattern NETHERNET = Pattern.compile("NetherNet signaling on TCP 127\\.0\\.0\\.1:(\\d+) over HTTP, NetherNet on UDP 127\\.0\\.0\\.1:(\\d+)$");
    // The QA network and live tests hold these, so a test must never bind them.
    private static final Set<Integer> TAKEN_PORTS = Set.of(19132, 19133, 19142, 19143);
    private static final String RAKNET_ON = "raknet:\n  enabled: true\n";
    // libdatachannel loads its native library from the file this property names, when it is set.
    private static final String MISSING_NATIVE = "-Dlibdatachannel.native.datachannel-java.path=/nonexistent/missing.dylib";

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> output = new CopyOnWriteArrayList<>();
    private Thread reader;

    // Runs the installed boarium with Boar on, joins it, and stops it as a service manager would.
    @Test
    void runsBoarAndShutsDown(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        int netherNetPort = freePort();
        Process process = this.start(dir, server.address(), "", RAKNET_ON + "nethernet:\n  port: " + netherNetPort + "\n");
        try {
            this.awaitLine("Boar started with its config in " + dir.resolve("boar") + ", mitigations on");
            Matcher rakNet = this.awaitMatch(RAKNET);
            Matcher netherNet = this.awaitMatch(NETHERNET);
            assertEquals(rakNet.group(1), netherNet.group(1));
            assertEquals(netherNetPort, Integer.parseInt(netherNet.group(2)));
            this.awaitLine("started, sending players to " + server.address());
            assertTrue(Files.exists(dir.resolve("boar").resolve("config.yml")));
            assertTrue(Files.exists(dir.resolve("nethernet").resolve("identity.pem")));

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(rakNet.group(1))));
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

    // Both public routes share one process and one Boar, but retain their own backend, identity and pack policy.
    @Test
    void servesTwoRoutesInOneProcess(@TempDir Path dir) throws Exception {
        this.servesTwoRoutes(dir, false);
    }

    @Test
    void isolatesMissingPacksAtStartupAndRecovers(@TempDir Path dir) throws Exception {
        this.servesTwoRoutes(dir, true);
    }

    // Exercise ordinary startup and a route whose pack publication arrives after the process binds.
    private void servesTwoRoutes(Path dir, boolean delayedPacks) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer practice = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        FakeSpectrumServer hcf = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = null;
        try {
            Path first = routeConfig(dir.resolve("practice"), practice.address(), false);
            Path second = routeConfig(dir.resolve("hcf"), hcf.address(), true);
            if (delayedPacks) {
                Files.move(second.resolveSibling("packs"), second.resolveSibling("generation"));
                Files.createSymbolicLink(second.resolveSibling("packs"), Path.of("missing"));
            }
            process = this.launch(dir, "", first, second);
            Matcher p = this.awaitMatch(NETHERNET);
            this.awaitLine("started, sending players to " + practice.address());
            Matcher h = this.awaitMatch(NETHERNET);
            this.awaitLine("started, sending players to " + hcf.address());
            assertEquals(1, this.output.stream().filter(line -> line.contains("Boar started with its config")).count());
            TestClient a = new TestClient("Practice", Bedrock_v1001.CODEC, 1001);
            TestClient b = new TestClient("HCF", Bedrock_v1001.CODEC, 1001);
            a.joinByAddress(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(p.group(1))));
            var pa = a.await(org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket.class);
            a.await(StartGamePacket.class);
            if (delayedPacks) {
                // Before the HCF route has a generation, ResourcePackCatalog.get throws in ClientInitializer.initSession, which refuses the login.
                TestClient early = new TestClient("Early", Bedrock_v1001.CODEC, 1001);
                early.joinByAddress(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(h.group(1))));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> early.loginDone.get(20, TimeUnit.SECONDS));
                assertTrue(this.output.stream().noneMatch(line -> line.contains("Failed to initialize a channel")), String.join("\n", this.output));
                Path next = Files.createSymbolicLink(second.resolveSibling("next"), Path.of("generation"));
                Files.move(next, second.resolveSibling("packs"), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                this.awaitLine("resource packs published for " + second.resolveSibling("packs"));
            }
            b.joinByAddress(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(h.group(1))));
            var pb = b.await(org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket.class);
            org.junit.jupiter.api.Assertions.assertFalse(pa.isForcedToAccept());
            assertTrue(pb.isForcedToAccept());
            assertEquals(1, pa.getResourcePackInfos().size());
            assertEquals(1, pb.getResourcePackInfos().size());
            org.junit.jupiter.api.Assertions.assertNotEquals(pa.getResourcePackInfos().getFirst().getPackId(), pb.getResourcePackInfos().getFirst().getPackId());
            b.await(StartGamePacket.class);
            assertTrue(practice.request.isDone());
            assertTrue(hcf.request.isDone());
            assertTrue(Files.exists(first.resolveSibling("nethernet/identity.pem")));
            assertTrue(Files.exists(second.resolveSibling("nethernet/identity.pem")));
            process.toHandle().destroy();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        } finally {
            if (process != null) process.destroyForcibly();
            practice.close(); hcf.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // Create a distinct original fixture and route config, without redistributing game content.
    private static Path routeConfig(Path dir, String backend, boolean required) throws Exception {
        Files.createDirectories(dir.resolve("packs"));
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve("packs/fixture.zip")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
            zip.write(("{\"header\":{\"uuid\":\"" + java.util.UUID.randomUUID() + "\",\"version\":[1,0,0]},\"modules\":[{\"type\":\"resources\"}]}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Files.writeString(dir.resolve("config.yml"), "listen: 127.0.0.1:0\nserver: " + backend
                + "\nxbox_auth: false\nresource_packs:\n  directory: packs\n  required: " + required + "\n  include_exempted: false\n");
    }

    // Boar's settings mean nothing while it is off on every route, so they need not agree then.
    @Test
    void letsRoutesDifferOnBoarSettingsWhileBoarIsOff(@TempDir Path dir) throws Exception {
        Path first = routeConfig(dir.resolve("practice"), "127.0.0.1:1", false);
        Path second = routeConfig(dir.resolve("hcf"), "127.0.0.1:1", true);
        Files.writeString(first, "boar_enabled: false\nboar_mitigations: false\nboar_alert_limit: 3\n", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(second, "boar_enabled: false\nboar_mitigations: true\n", java.nio.file.StandardOpenOption.APPEND);
        Process process = this.launch(dir, "", first, second);
        try {
            this.awaitLine("route config: " + first.toAbsolutePath());
            this.awaitLine("route config: " + second.toAbsolutePath());
            assertTrue(this.output.stream().noneMatch(line -> line.contains("must agree")), String.join("\n", this.output));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void rejectsConflictingBoarSettingsBeforeBinding(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.yml"), "boar_mitigations: false\n");
        Path b = Files.writeString(dir.resolve("b.yml"), "boar_mitigations: true\n");
        Process process = this.launch(dir, "", a, b);
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            this.awaitLine("routes in one JVM must agree");
            assertTrue(this.output.stream().noneMatch(line -> line.contains("NetherNet signaling on")));
        } finally { process.destroyForcibly(); }
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

    // With RakNet on, a NetherNet native library that cannot load leaves boarium taking players over RakNet alone.
    @Test
    void takesRakNetPlayersWhenNetherNetCannotLoad(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = this.start(dir, server.address(), MISSING_NATIVE, RAKNET_ON + "nethernet:\n  port: " + freePort() + "\n");
        try {
            Matcher rakNet = this.awaitMatch(RAKNET);
            String error = this.awaitLine("NetherNet could not start");
            assertTrue(error.contains(" ERROR ") && error.contains("UnsatisfiedLinkError") && error.endsWith(", so players can only join over RakNet"), error);
            this.awaitLine("started, sending players to ");
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", Integer.parseInt(rakNet.group(1)));
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

    // .invalid never resolves (RFC 6761), and resolvers may refuse it without asking a DNS server.
    @Test
    void takesRakNetPlayersWhenAnAdvertisedNameDoesNotResolve(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = this.start(dir, server.address(), "", RAKNET_ON + "nethernet:\n  port: " + freePort() + "\n  advertise_addresses: [x.invalid]\n");
        try {
            Matcher rakNet = this.awaitMatch(RAKNET);
            String error = this.awaitLine("NetherNet could not start");
            assertTrue(error.contains(" ERROR ") && error.endsWith("NetherNet could not start: nethernet.advertise_addresses: cannot resolve x.invalid, so players can only join over RakNet"), error);
            this.awaitLine("started, sending players to ");

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(rakNet.group(1))));
            client.loginDone.get(10, TimeUnit.SECONDS);
            assertEquals(1, this.output.stream().filter(line -> line.contains(" ERROR ")).count(), String.join("\n", this.output));
        } finally {
            process.destroyForcibly();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // NetherNet alone, as the default config has it, takes players by address on one port.
    @Test
    void takesNetherNetPlayersAloneByDefault(@TempDir Path dir) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = this.start(dir, server.address());
        try {
            Matcher netherNet = this.awaitMatch(NETHERNET);
            assertEquals(netherNet.group(1), netherNet.group(2));
            this.awaitLine("started, sending players to ");
            // On loopback, as the tests listen, no public STUN server is asked.
            assertTrue(this.output.stream().anyMatch(line -> line.endsWith("STUN discovery is skipped, as boarium listens on the loopback address 127.0.0.1")), String.join("\n", this.output));
            assertTrue(this.output.stream().anyMatch(line -> line.endsWith("NetherNet advertises this machine's addresses, without STUN discovery")), String.join("\n", this.output));
            assertTrue(this.output.stream().noneMatch(line -> line.contains("RakNet on UDP")), String.join("\n", this.output));

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.joinByAddress(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(netherNet.group(1))));
            client.loginDone.get(30, TimeUnit.SECONDS);
            // From the server, through boarium.
            client.await(StartGamePacket.class);
        } finally {
            process.destroyForcibly();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // With RakNet off, as by default, no player could join without NetherNet.
    @Test
    void stopsWhenNetherNetCannotLoadAndRakNetIsOff(@TempDir Path dir) throws Exception {
        Process process = this.start(dir, "127.0.0.1:1", MISSING_NATIVE, "");
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            String error = this.awaitLine("could not start: java.lang.IllegalStateException: NetherNet could not start with signaling on TCP 127.0.0.1:");
            assertTrue(error.contains("UnsatisfiedLinkError") && error.endsWith("; RakNet is off, so no player could join"), error);
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void stopsWhenTheNetherNetIdentityCannotBeReadAndRakNetIsOff(@TempDir Path dir) throws Exception {
        Path identity = brokenIdentity(dir);
        Process process = this.start(dir, "127.0.0.1:1");
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            String error = this.awaitLine("could not start: java.lang.IllegalStateException: NetherNet could not start: cannot read its identity file " + identity + " as a key: ");
            assertTrue(error.endsWith("; RakNet is off, so no player could join. Only delete that file if it cannot be recovered, as a new identity makes every player confirm the server again"), error);
            assertEquals("not a key", Files.readString(identity));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void takesRakNetPlayersWhenTheNetherNetIdentityCannotBeRead(@TempDir Path dir) throws Exception {
        Path identity = brokenIdentity(dir);
        EventLoopGroup group = new NioEventLoopGroup(2);
        FakeSpectrumServer server = new FakeSpectrumServer(group, Bedrock_v1001.CODEC);
        Process process = this.start(dir, server.address(), "", RAKNET_ON + "nethernet:\n  port: " + freePort() + "\n");
        try {
            Matcher rakNet = this.awaitMatch(RAKNET);
            String error = this.awaitLine("NetherNet could not start: cannot read its identity file " + identity + " as a key: ");
            assertTrue(error.contains(" ERROR ") && error.endsWith(", so players can only join over RakNet. Only delete that file if it cannot be recovered, as a new identity makes every player confirm the server again"), error);
            this.awaitLine("started, sending players to ");

            TestClient client = new TestClient("Tester", Bedrock_v1001.CODEC, Bedrock_v1001.CODEC.getProtocolVersion());
            client.connect(group, new InetSocketAddress("127.0.0.1", Integer.parseInt(rakNet.group(1))));
            client.loginDone.get(10, TimeUnit.SECONDS);
            assertEquals(1, this.output.stream().filter(line -> line.contains(" ERROR ")).count(), String.join("\n", this.output));
            assertEquals("not a key", Files.readString(identity));
        } finally {
            process.destroyForcibly();
            server.close();
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    // boarium makes a key file that other users may read owner-only, and says so.
    @Test
    void warnsThatItMadeTheNetherNetIdentityOwnerOnly(@TempDir Path dir) throws Exception {
        Path identity = dir.resolve("nethernet").resolve("identity.pem");
        OperatorIdentity.fromPemOrCreate(identity.toFile(), "test");
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-r--r--"));
        Process process = this.start(dir, "127.0.0.1:1");
        try {
            String warning = this.awaitLine("NetherNet's identity file " + identity + " was open to other users, so boarium made it readable by its owner only");
            assertTrue(warning.contains(" WARN "), warning);
            this.awaitMatch(NETHERNET);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(identity));
        } finally {
            process.destroyForcibly();
        }
    }

    private static Path brokenIdentity(Path dir) throws Exception {
        return Files.writeString(Files.createDirectory(dir.resolve("nethernet")).resolve("identity.pem"), "not a key");
    }

    @Test
    void stopsOnAKeyOfTheOldLayout(@TempDir Path dir) throws Exception {
        Process process = this.start(dir, "127.0.0.1:1", "", "nethernet: false\n");
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            this.awaitLine("could not start: java.lang.IllegalArgumentException: nethernet is now a section; set nethernet.enabled instead");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void stopsWhenTheNetherNetCertificateCannotBeRead(@TempDir Path dir) throws Exception {
        Process process = this.start(dir, "127.0.0.1:1", "", "nethernet:\n  https:\n    certificate: missing.pem\n    private_key: key.pem\n");
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "boarium did not stop");
            this.reader.join(5000);
            assertEquals(1, process.exitValue());
            this.awaitLine("could not start: java.lang.IllegalArgumentException: cannot read the NetherNet HTTPS file " + dir.resolve("missing.pem"));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void warnsThatTheNetherNetCertificateIsUnusedWithNetherNetOff(@TempDir Path dir) throws Exception {
        Process process = this.start(dir, "127.0.0.1:1", "", RAKNET_ON + "nethernet:\n  enabled: false\n  https:\n    certificate: missing.pem\n");
        try {
            assertTrue(this.awaitLine("nethernet.enabled is false, so nethernet.https.certificate is not used").contains(" WARN "));
            this.awaitLine("started, sending players to ");
        } finally {
            process.destroyForcibly();
        }
    }

    private Process start(Path dir, String server) throws Exception {
        return this.start(dir, server, "", "");
    }

    // A loopback port free on TCP and UDP, for a NetherNet port next to RakNet on the listen port.
    private static int freePort() throws Exception {
        return Addresses.freePort(InetAddress.getLoopbackAddress(), TAKEN_PORTS);
    }

    // Starts the installed boarium in dir, on a free loopback port, with Boar on, javaOpts given to its JVM, and extraConfig added to its config.
    private Process start(Path dir, String server, String javaOpts, String extraConfig) throws Exception {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, "listen: 127.0.0.1:0\nserver: " + server + "\nxbox_auth: false\n" + extraConfig);
        return this.launch(dir, javaOpts, config);
    }

    // Start the installed binary with all route paths in one JVM and collect its output.
    private Process launch(Path dir, String javaOpts, Path... configs) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("boarium.install"), "bin", "boarium").toString());
        for (Path config : configs) command.add(config.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
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

    private Matcher awaitMatch(Pattern pattern) throws InterruptedException {
        Matcher matcher = pattern.matcher(this.await(line -> pattern.matcher(line).find(), pattern.pattern()));
        assertTrue(matcher.find());
        return matcher;
    }

    private String awaitLine(String part) throws InterruptedException {
        return this.await(line -> line.contains(part), part);
    }

    private String await(Predicate<String> wanted, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            String line = this.lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new IllegalStateException("boarium did not log \"" + what + "\" within 30 seconds:\n" + String.join("\n", this.output));
            }
            if (wanted.test(line)) {
                return line;
            }
        }
    }
}
