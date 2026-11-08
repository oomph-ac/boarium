package ac.oomph.boarium.client;

import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(60)
class NetherNetIdentityTest {
    private static final String KEEP_IT = ". Only delete that file if it cannot be recovered, as a new identity makes every player confirm the server again";

    @TempDir
    Path folder;

    private Path identity() {
        return this.folder.resolve("nethernet").resolve("identity.pem");
    }

    private void assumePosix() throws Exception {
        assumeTrue(Files.getFileStore(this.folder).supportsFileAttributeView(PosixFileAttributeView.class));
    }

    private static String key(OperatorIdentity identity) {
        return Base64.getEncoder().encodeToString(identity.publicKey().getEncoded());
    }

    private static String keyInFile(Path file) throws Exception {
        return key(OperatorIdentity.fromPem(file.toFile(), "test"));
    }

    @Test
    void createsTheFolderAndFileForItsOwnerOnly() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        OperatorIdentity created = NetherNetIdentity.loadOrCreate(file);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(keyInFile(file), key(created));
        assertEquals(key(created), key(NetherNetIdentity.loadOrCreate(file)));
    }

    @Test
    void leavesAFolderThatIsThereAlone() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        Files.createDirectory(file.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        NetherNetIdentity.loadOrCreate(file);
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(file.getParent()));
    }

    @Test
    void makesAKeyFileOpenToOtherUsersOwnerOnly() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        String key = key(NetherNetIdentity.loadOrCreate(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(key, key(NetherNetIdentity.loadOrCreate(file)));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    void createsOneKeyForThreadsThatStartAtOnce() throws Exception {
        Path file = this.identity();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> keys = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                keys.add(threads.submit(() -> {
                    start.await();
                    return key(NetherNetIdentity.loadOrCreate(file));
                }));
            }
            start.countDown();
            List<String> got = new ArrayList<>();
            for (Future<String> key : keys) {
                got.add(key.get(30, TimeUnit.SECONDS));
            }
            assertEquals(List.of(keyInFile(file)), got.stream().distinct().toList());
        } finally {
            threads.shutdownNow();
        }
    }

    // The JVM's own lock does not reach another process, so the code under test takes a lock on a file too.
    @Test
    void createsOneKeyForProcessesThatStartAtOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            Path file = this.folder.resolve("round" + round).resolve("identity.pem");
            long startAt = System.currentTimeMillis() + 1500;
            List<Process> processes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                processes.add(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Create.class.getName(), file.toString(), String.valueOf(startAt))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start());
            }
            List<String> keys = new ArrayList<>();
            for (Process process : processes) {
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), out);
                keys.add(out);
            }
            assertEquals(List.of(keyInFile(file)), keys.stream().distinct().toList());
        }
    }

    // Run by createsOneKeyForProcessesThatStartAtOnce: loads or creates the identity in args[0] at the time in milliseconds in args[1], and prints its key.
    public static final class Create {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Math.max(0, Long.parseLong(args[1]) - System.currentTimeMillis()));
            try {
                System.out.println(key(NetherNetIdentity.loadOrCreate(Path.of(args[0]))));
            } catch (NetherNetFailure e) {
                System.out.println(e.explain(""));
                System.exit(1);
            }
        }
    }

    @Test
    void refusesAFolderAtThePathAndChangesNothing() throws Exception {
        this.assumePosix();
        Path file = Files.createDirectories(this.identity());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        NetherNetFailure failure = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file));
        assertEquals("NetherNet could not start: its identity file " + file + " is not a file", failure.explain(""));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(file));
    }

    @Test
    void refusesASymbolicLink() throws Exception {
        Path target = this.folder.resolve("secret").resolve("identity.pem");
        String key = key(NetherNetIdentity.loadOrCreate(target));
        Path file = this.identity();
        Files.createDirectories(file.getParent());
        Files.createSymbolicLink(file, target);
        NetherNetFailure failure = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file));
        assertEquals("NetherNet could not start: its identity file " + file + " is a symbolic link, which boarium does not follow; copy the key file there instead", failure.explain(""));
        assertTrue(Files.isSymbolicLink(file));
        assertEquals(key, keyInFile(target));
    }

    @Test
    void failsAndKeepsAFileThatIsNotAKey() throws Exception {
        Path file = this.identity();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not a key");
        String error = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file)).explain("");
        assertTrue(error.startsWith("NetherNet could not start: cannot read its identity file " + file + " as a key: "), error);
        assertTrue(error.endsWith(KEEP_IT), error);
        assertEquals("not a key", Files.readString(file));
    }

    @Test
    void asksForAnEcKeyInPlaceOfAnRsaKey() throws Exception {
        Path file = this.identity();
        Files.createDirectories(file.getParent());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        StringWriter pem = new StringWriter().append("-----BEGIN PRIVATE KEY-----\n");
        pem.append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())).append("\n-----END PRIVATE KEY-----\n");
        Files.writeString(file, pem.toString());
        String error = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file)).explain("");
        assertEquals("NetherNet could not start: cannot read its identity file " + file + " as a key: it must hold a P-384 EC private key with its public key" + KEEP_IT, error);
        assertEquals(pem.toString(), Files.readString(file));
    }

    // A folder boarium may not write to is not the key's fault, so the error does not talk about deleting it.
    @Test
    void failsWithoutAdviceInAFolderItMayNotWriteTo() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        Files.createDirectories(file.getParent());
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(file.getParent()), "the tests run as a user who may write anywhere");
            String error = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file)).explain("");
            assertTrue(error.startsWith("NetherNet could not start: "), error);
            assertFalse(error.contains("delete"), error);
            assertTrue(Files.notExists(file));
        } finally {
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
        }
    }

    // A key copied into a folder boarium may not write to, as a read-only secret mount, still loads.
    @Test
    void loadsAKeyInAFolderItMayNotWriteTo() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        String key = key(NetherNetIdentity.loadOrCreate(file));
        Files.delete(file.resolveSibling(file.getFileName() + ".lock"));
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(file.getParent()), "the tests run as a user who may write anywhere");
            assertEquals(key, key(NetherNetIdentity.loadOrCreate(file)));
        } finally {
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
        }
    }

    // A key boarium may not read wants its permissions fixed, not the file deleted.
    @Test
    void asksToCheckThePermissionsOfAKeyItMayNotRead() throws Exception {
        this.assumePosix();
        Path file = this.identity();
        NetherNetIdentity.loadOrCreate(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(file), "the tests run as a user who may read anything");
            String error = assertThrows(NetherNetFailure.class, () -> NetherNetIdentity.loadOrCreate(file)).explain("");
            assertTrue(error.contains("check its permissions"), error);
            assertFalse(error.contains("delete"), error);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
