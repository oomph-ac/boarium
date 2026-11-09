package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Publishes complete pack generations to new logins without interrupting another route or an active download. */
public final class ResourcePackCatalog implements Supplier<ResourcePacks>, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ResourcePackCatalog.class);
    private final BoariumConfig.ResourcePacks config;
    private final ScheduledExecutorService watcher;
    private volatile ResourcePacks snapshot;
    private Path generation;
    private String lastError;

    /** Attempts the initial generation, then watches symlinks off packet threads; unavailable packs disable only this route's logins. */
    public ResourcePackCatalog(BoariumConfig.ResourcePacks config) throws IOException {
        this.config = config;
        this.poll();
        this.watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "boarium-pack-catalog");
            thread.setDaemon(true);
            return thread;
        });
        if (config.directory() != null) {
            this.watcher.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** Returns the immutable catalogue for a new login; an invalid publication closes new connections on this route. */
    @Override
    public ResourcePacks get() {
        return Objects.requireNonNull(this.snapshot, "resource pack generation is unavailable: " + this.config.directory());
    }

    // Resolve once and read the resolved directory, so a symlink swap during loading cannot mix two generations.
    synchronized void refresh() throws IOException {
        try {
            Path next = this.config.directory() == null ? null : this.config.directory().toRealPath();
            if (this.snapshot != null && Objects.equals(next, this.generation)) {
                return;
            }
            ResourcePacks loaded = ResourcePacks.load(new BoariumConfig.ResourcePacks(next, this.config.required(), this.config.includeExempted()));
            this.generation = next;
            this.snapshot = loaded;
        } catch (IOException | RuntimeException e) {
            this.snapshot = null;
            throw e;
        }
    }

    // Log a failure once, retry on the next poll, and never silently offer an obsolete stack to a restarted backend.
    private void poll() {
        try {
            ResourcePacks previous = this.snapshot;
            this.refresh();
            if (previous != this.snapshot) {
                LOG.info("resource packs published for {} from {}", this.config.directory(), this.generation);
            }
            this.lastError = null;
        } catch (IOException | RuntimeException e) {
            String error = e.toString();
            if (!error.equals(this.lastError)) {
                LOG.error("new logins disabled for resource packs {}: {}", this.config.directory(), error);
                this.lastError = error;
            }
        }
    }

    /** Stops filesystem polling; snapshots already held by a login remain valid. */
    @Override
    public void close() {
        this.watcher.shutdownNow();
    }
}
