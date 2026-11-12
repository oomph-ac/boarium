package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackCatalogTest {
    @TempDir Path dir;

    // A new generation must change only new logins; a failed publication cannot silently serve the old stack.
    @Test void publishesSnapshotsAndRecoversFromInvalidGeneration() throws Exception {
        Path first = Files.createDirectory(dir.resolve("one"));
        Path second = Files.createDirectory(dir.resolve("two"));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ResourcePacksTest.archive(first.resolve("pack.zip"), a, "[1,0,0]", false);
        ResourcePacksTest.archive(second.resolve("pack.zip"), b, "[2,0,0]", false);
        Path current = Files.createSymbolicLink(dir.resolve("current"), first);
        try (var catalog = new ResourcePackCatalog(new BoariumConfig.ResourcePacks(current, true, false))) {
            ResourcePacks activeLogin = catalog.get();
            publish(second, current);
            catalog.refresh();
            assertEquals(a, activeLogin.packs.getFirst().id);
            assertEquals(b, catalog.get().packs.getFirst().id);
            assertTrue(catalog.get().required);
            assertFalse(catalog.get().includeExempted);
            Path broken = Files.createDirectory(dir.resolve("broken"));
            Files.writeString(broken.resolve("bad.zip"), "not a zip");
            publish(broken, current);
            assertThrows(IOException.class, catalog::refresh);
            assertThrows(IllegalStateException.class, catalog::get);
            assertEquals(a, activeLogin.packs.getFirst().id);
            publish(first, current);
            catalog.refresh();
            assertEquals(a, catalog.get().packs.getFirst().id);
        }
    }

    // An ordinary directory is never reloaded, so one that cannot load at startup must stop boarium, not leave the route waiting for good.
    @Test void anOrdinaryDirectoryThatCannotLoadFailsStartup() throws Exception {
        Path missing = dir.resolve("packs");
        String error = assertThrows(IOException.class, () -> new ResourcePackCatalog(new BoariumConfig.ResourcePacks(missing, false, true))).toString();
        assertTrue(error.contains(missing.toString()), error);
        Files.createDirectory(missing);
        Files.writeString(missing.resolve("bad.zip"), "not a zip");
        assertThrows(IOException.class, () -> new ResourcePackCatalog(new BoariumConfig.ResourcePacks(missing, false, true)));
        Files.delete(missing.resolve("bad.zip"));
        UUID id = UUID.randomUUID();
        ResourcePacksTest.archive(missing.resolve("pack.zip"), id, "[1,0,0]", false);
        try (var catalog = new ResourcePackCatalog(new BoariumConfig.ResourcePacks(missing, true, true))) {
            assertEquals(id, catalog.get().packs.getFirst().id);
        }
    }

    // A symlink may be published after boarium starts; until then its route refuses logins.
    @Test void aSymlinkWithNoGenerationWaitsForOne() throws Exception {
        Path current = Files.createSymbolicLink(dir.resolve("current"), Path.of("missing"));
        try (var catalog = new ResourcePackCatalog(new BoariumConfig.ResourcePacks(current, true, false))) {
            assertThrows(IllegalStateException.class, catalog::get);
            Path generation = Files.createDirectory(dir.resolve("one"));
            UUID id = UUID.randomUUID();
            ResourcePacksTest.archive(generation.resolve("pack.zip"), id, "[1,0,0]", false);
            publish(generation, current);
            catalog.poll();
            assertEquals(id, catalog.get().packs.getFirst().id);
        }
    }

    // Reading a broken generation again costs a read of every archive in it, so it is retried only once the target or its files change.
    @Test void retriesABrokenGenerationOnlyWhenItChanges() throws Exception {
        Path broken = Files.createDirectory(dir.resolve("broken"));
        Files.writeString(broken.resolve("bad.zip"), "not a zip");
        Path current = Files.createSymbolicLink(dir.resolve("current"), broken.getFileName());
        int[] loads = {0};
        try (var catalog = new ResourcePackCatalog(new BoariumConfig.ResourcePacks(current, false, true), config -> {
            loads[0]++;
            return ResourcePacks.load(config);
        }, false)) {
            assertEquals(1, loads[0]);
            for (int i = 0; i < 5; i++) {
                catalog.poll();
            }
            assertEquals(1, loads[0]);
            Files.delete(broken.resolve("bad.zip"));
            UUID id = UUID.randomUUID();
            ResourcePacksTest.archive(broken.resolve("pack.zip"), id, "[1,0,0]", false);
            catalog.poll();
            assertEquals(2, loads[0]);
            assertEquals(id, catalog.get().packs.getFirst().id);
        }
    }

    // An Error escaping the poll would stop the executor from running it again, leaving the old generation served for good.
    @Test void anErrorWhileLoadingDisablesLoginsAndIsRetried() throws Exception {
        Path first = Files.createDirectory(dir.resolve("one"));
        Path second = Files.createDirectory(dir.resolve("two"));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ResourcePacksTest.archive(first.resolve("pack.zip"), a, "[1,0,0]", false);
        ResourcePacksTest.archive(second.resolve("pack.zip"), b, "[2,0,0]", false);
        Path current = Files.createSymbolicLink(dir.resolve("current"), first.getFileName());
        boolean[] outOfMemory = {false};
        try (var catalog = new ResourcePackCatalog(new BoariumConfig.ResourcePacks(current, false, true), config -> {
            if (outOfMemory[0]) {
                outOfMemory[0] = false;
                throw new OutOfMemoryError("Java heap space");
            }
            return ResourcePacks.load(config);
        }, false)) {
            assertEquals(a, catalog.get().packs.getFirst().id);
            outOfMemory[0] = true;
            publish(second, current);
            assertDoesNotThrow(catalog::poll);
            assertThrows(IllegalStateException.class, catalog::get);
            catalog.poll();
            assertEquals(b, catalog.get().packs.getFirst().id);
        }
    }

    // Match the backend's atomic relative-symlink publication protocol.
    private void publish(Path generation, Path current) throws IOException {
        Path next = Files.createSymbolicLink(dir.resolve("next"), generation.getFileName());
        Files.move(next, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
