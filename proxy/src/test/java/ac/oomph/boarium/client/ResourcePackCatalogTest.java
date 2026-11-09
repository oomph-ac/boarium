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
            assertThrows(NullPointerException.class, catalog::get);
            assertEquals(a, activeLogin.packs.getFirst().id);
            publish(first, current);
            catalog.refresh();
            assertEquals(a, catalog.get().packs.getFirst().id);
        }
    }

    // Match the backend's atomic relative-symlink publication protocol.
    private void publish(Path generation, Path current) throws IOException {
        Path next = Files.createSymbolicLink(dir.resolve("next"), generation.getFileName());
        Files.move(next, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
