package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A route's resource packs for new logins.
 * <p>
 * An ordinary directory loads once, and a missing or invalid one fails startup, since its real path never changes and nothing would reload it.
 * A directory symlink publishes immutable generations: it is checked every second off packet threads, and while its target is missing or invalid, new logins on the route are refused.
 */
public final class ResourcePackCatalog implements Supplier<ResourcePacks>, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ResourcePackCatalog.class);
    private final BoariumConfig.ResourcePacks config;
    private final Loader loader;
    private final ScheduledExecutorService watcher;
    private volatile ResourcePacks snapshot;
    private Path generation;
    // What the target looked like when its load last failed; the poll retries only once it changes.
    private Object failedState;
    private String lastError;

    /**
     * @throws IOException if the directory is not a symlink and cannot be loaded
     */
    public ResourcePackCatalog(BoariumConfig.ResourcePacks config) throws IOException {
        this(config, ResourcePacks::load, true);
    }

    interface Loader {
        ResourcePacks load(BoariumConfig.ResourcePacks config) throws IOException;
    }

    // Tests pass a loader that counts reads or fails, and poll by hand without the watcher.
    ResourcePackCatalog(BoariumConfig.ResourcePacks config, Loader loader, boolean watch) throws IOException {
        this.config = config;
        this.loader = loader;
        boolean symlink = config.directory() != null && Files.isSymbolicLink(config.directory());
        if (!symlink) {
            this.refresh();
            this.watcher = null;
            return;
        }
        this.poll();
        if (!watch) {
            this.watcher = null;
            return;
        }
        this.watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "boarium-pack-catalog");
            thread.setDaemon(true);
            return thread;
        });
        this.watcher.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Returns the immutable catalogue for a new login.
     *
     * @throws IllegalStateException while the symlink's target is missing or invalid
     */
    @Override
    public ResourcePacks get() {
        ResourcePacks packs = this.snapshot;
        if (packs == null) {
            throw new IllegalStateException("resource pack generation is unavailable: " + this.config.directory());
        }
        return packs;
    }

    // Resolve once and read the resolved directory, so a symlink swap during loading cannot mix two generations.
    // Any failure, an Error included, clears the snapshot, so the route never keeps serving a generation it could not replace.
    synchronized void refresh() throws IOException {
        try {
            Path next = this.config.directory() == null ? null : this.config.directory().toRealPath();
            if (this.snapshot != null && Objects.equals(next, this.generation)) {
                return;
            }
            ResourcePacks loaded = this.loader.load(new BoariumConfig.ResourcePacks(next, this.config.required(), this.config.includeExempted()));
            this.generation = next;
            this.snapshot = loaded;
        } catch (Throwable e) {
            this.snapshot = null;
            this.generation = null;
            throw e;
        }
    }

    // Runs on the watcher. It must never throw, or the executor would stop scheduling it. A failure is logged once, until the error changes.
    synchronized void poll() {
        try {
            Object state = this.state();
            // An unchanged target that failed would fail the same way, and reading it again costs a read of every archive.
            if (this.snapshot == null && state.equals(this.failedState)) {
                return;
            }
            ResourcePacks previous = this.snapshot;
            try {
                this.refresh();
            } catch (IOException | RuntimeException e) {
                this.failedState = state;
                throw e;
            }
            // An Error, such as running out of memory while two generations are held, is retried on the next poll, as the same files may load then.
            this.failedState = null;
            if (previous != this.snapshot) {
                LOG.info("resource packs published for {} from {}", this.config.directory(), this.generation);
            }
            this.lastError = null;
        } catch (Throwable e) {
            String error = e.toString();
            if (!error.equals(this.lastError)) {
                LOG.error("new logins disabled for resource packs {}: {}", this.config.directory(), error);
                this.lastError = error;
            }
        }
    }

    // The resolved target, and the name, size and modification time of each file in it. Listing reads no archive.
    private Object state() {
        Path directory = this.config.directory();
        Path target;
        try {
            target = directory.toRealPath();
        } catch (IOException e) {
            String link;
            try {
                link = Files.readSymbolicLink(directory).toString();
            } catch (IOException | UnsupportedOperationException ignored) {
                link = "";
            }
            return List.of("unresolved", link, e.getClass().getName());
        }
        try (var files = Files.list(target)) {
            return List.of(target, files.sorted().map(file -> {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    return file.getFileName() + ":" + attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
                } catch (IOException e) {
                    return file.getFileName() + ":" + e.getClass().getName();
                }
            }).toList());
        } catch (IOException | RuntimeException e) {
            return List.of(target, e.getClass().getName());
        }
    }

    /** Stops filesystem polling; snapshots already held by a login remain valid. */
    @Override
    public void close() {
        if (this.watcher != null) {
            this.watcher.shutdownNow();
        }
    }
}
