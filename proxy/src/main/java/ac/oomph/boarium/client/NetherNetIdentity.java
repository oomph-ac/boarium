package ac.oomph.boarium.client;

import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Set;

/**
 * The key NetherNet's signaling signs its answers with, kept in a PEM file and created there on first start.
 */
final class NetherNetIdentity {
    private static final String DOMAIN = "boarium";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final String KEEP_IT = "Only delete that file if it cannot be recovered, as a new identity makes every player confirm the server again";
    private static final Logger LOG = LoggerFactory.getLogger(NetherNetIdentity.class);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    private NetherNetIdentity() {
    }

    // Synchronized, and creates the key only under a lock on a file next to it, so that two boariums starting at once, in one JVM or two, cannot each create a key of their own.
    static synchronized OperatorIdentity loadOrCreate(Path file) throws NetherNetFailure {
        Path folder = file.toAbsolutePath().getParent();
        boolean posix;
        try {
            if (Files.notExists(folder)) {
                Files.createDirectories(folder, permissions(folder.getFileSystem().supportedFileAttributeViews().contains("posix"), "rwx------"));
            }
            posix = Files.getFileStore(folder).supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (IOException e) {
            throw new NetherNetFailure("NetherNet could not start: cannot create the folder of its identity file " + file + ": " + e, null, e);
        }
        // The lock only guards creating the key, so a key already there is loaded without it, as from a folder boarium may not write to.
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return loadOrCreate(file, posix, false);
        }
        Path lockFile = folder.resolve(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), permissions(posix, "rw-------")); FileLock lock = lock(channel, lockFile)) {
            return loadOrCreate(file, posix, true);
        } catch (IOException e) {
            throw new NetherNetFailure("NetherNet could not start: cannot lock its identity file with " + lockFile + ": " + e, null, e);
        }
    }

    // Waits a while rather than forever, so that a process that stopped while holding the lock cannot stall the start without a word.
    private static FileLock lock(FileChannel channel, Path lockFile) throws IOException, NetherNetFailure {
        long deadline = System.nanoTime() + LOCK_WAIT.toNanos();
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new NetherNetFailure("NetherNet could not start: another process has held " + lockFile + " for " + LOCK_WAIT.toSeconds() + " seconds, as another boarium may be starting with this config folder", null, null);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NetherNetFailure("NetherNet could not start: interrupted while waiting for " + lockFile, null, e);
            }
        }
    }

    private static OperatorIdentity loadOrCreate(Path file, boolean posix, boolean mayCreate) throws NetherNetFailure {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            if (e instanceof NoSuchFileException && mayCreate) {
                return create(file, posix);
            }
            throw new NetherNetFailure("NetherNet could not start: cannot read its identity file " + file + ": " + e, null, e);
        }
        if (attributes.isSymbolicLink()) {
            throw new NetherNetFailure("NetherNet could not start: its identity file " + file + " is a symbolic link, which boarium does not follow; copy the key file there instead", null, null);
        }
        if (!attributes.isRegularFile()) {
            throw new NetherNetFailure("NetherNet could not start: its identity file " + file + " is not a file", null, null);
        }
        if (posix) {
            ownerOnly(file);
        }
        try {
            return OperatorIdentity.fromPem(file.toFile(), DOMAIN);
        } catch (GeneralSecurityException e) {
            // The library reads every key as EC, so it says an RSA key "does not carry its public key".
            String why = String.valueOf(e.getMessage()).contains("does not carry its public key") ? "it must hold a P-384 EC private key with its public key" : NetherNetFailure.reason(e);
            throw new NetherNetFailure("NetherNet could not start: cannot read its identity file " + file + " as a key: " + why, KEEP_IT, e);
        } catch (AccessDeniedException e) {
            throw new NetherNetFailure("NetherNet could not start: cannot read its identity file " + file + ", so check its permissions: " + NetherNetFailure.reason(e), null, e);
        } catch (IOException e) {
            throw new NetherNetFailure("NetherNet could not start: cannot read its identity file " + file + ": " + NetherNetFailure.reason(e), KEEP_IT, e);
        } catch (JoseException | RuntimeException | LinkageError e) {
            throw new NetherNetFailure("NetherNet could not start: cannot load its identity from " + file + ": " + NetherNetFailure.reason(e), null, e);
        }
    }

    private static OperatorIdentity create(Path file, boolean posix) throws NetherNetFailure {
        try {
            return OperatorIdentity.fromPemOrCreate(file.toFile(), DOMAIN);
        } catch (FileAlreadyExistsException e) {
            // A program other than boarium created it after the check, so it is loaded as any file that was there.
            return loadOrCreate(file, posix, false);
        } catch (GeneralSecurityException | JoseException | IOException | RuntimeException | LinkageError e) {
            throw new NetherNetFailure("NetherNet could not start: cannot create its identity file " + file + ": " + NetherNetFailure.reason(e), null, e);
        }
    }

    // Tightens the file here so that the change is logged; the library would do it without a word.
    // A file boarium may not change, as a secret mounted read-only, is still loaded.
    private static void ownerOnly(Path file) throws NetherNetFailure {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new NetherNetFailure("NetherNet could not start: cannot read the permissions of its identity file " + file + ": " + e, null, e);
        }
        if (OWNER_ONLY.containsAll(permissions)) {
            return;
        }
        boolean openToOthers = permissions.stream().anyMatch(permission -> !permission.name().startsWith("OWNER_"));
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
            if (openToOthers) {
                LOG.warn("NetherNet's identity file {} was open to other users, so boarium made it readable by its owner only", file);
            }
        } catch (IOException e) {
            if (openToOthers) {
                LOG.warn("NetherNet's identity file {} is open to other users, and boarium cannot change that: {}", file, e.toString());
            }
        }
    }

    // The POSIX permissions to create a file or folder with, or none where the file system has no POSIX permissions.
    private static FileAttribute<?>[] permissions(boolean posix, String permissions) {
        return posix ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))} : new FileAttribute<?>[0];
    }
}
