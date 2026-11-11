package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePacksTest {
    @TempDir Path dir;

    // Original generated fixtures: no game assets are included.
    static Path archive(Path path, UUID id, String version, boolean contents) throws IOException {
        String manifest = "{\"format_version\":3,\"header\":{\"uuid\":\"" + id + "\",\"version\":" + version + "},\"modules\":[{\"type\":\"resources\"}]}";
        return archive(path, manifest, contents ? "{\"content\":[]}".getBytes(StandardCharsets.UTF_8) : null);
    }

    static Path archive(Path path, String manifest, byte[] contents) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (contents != null) {
                zip.putNextEntry(new ZipEntry("contents.json"));
                zip.write(contents);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("fixture.bin"));
            byte[] bytes = new byte[ResourcePacks.CHUNK_SIZE * 2 + 37];
            new java.util.Random(1).nextBytes(bytes);
            zip.write(bytes);
            zip.closeEntry();
        }
        return path;
    }

    @Test void acceptsBothVersionsAndPlainContentsAndPreservesOrder() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        archive(dir.resolve("b.zip"), b, "\"1.2.3\"", true);
        Path first = archive(dir.resolve("a.mcpack"), a, "[1,2,3]", false);
        var packs = ResourcePacks.load(new BoariumConfig.ResourcePacks(dir, true, false));
        assertEquals(java.util.List.of(a,b), packs.packs.stream().map(p -> p.id).toList());
        var pack = packs.packs.getFirst();
        assertEquals("1.2.3", pack.version);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(first)), pack.dataInfo().getHash());
        var output = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < pack.chunkCount(); i++) {
            var packet = pack.chunk(i);
            try {
                assertTrue(packet.getData().isReadOnly());
                assertEquals((long)i * ResourcePacks.CHUNK_SIZE, packet.getProgress());
                byte[] chunk = new byte[packet.getData().readableBytes()];
                packet.getData().readBytes(chunk); output.write(chunk);
            } finally { packet.release(); }
        }
        assertArrayEquals(Files.readAllBytes(first), output.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> pack.chunk(-1));
        assertThrows(IllegalArgumentException.class, () -> pack.chunk(Integer.MAX_VALUE));
    }

    @Test void namesInvalidArchivesAndDirectories() throws Exception {
        Path bad = dir.resolve("broken.zip"); Files.writeString(bad, "not zip");
        assertTrue(assertThrows(IOException.class, () -> ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true))).getMessage().contains(bad.toString()));
        Files.delete(bad); Files.createDirectory(bad);
        assertTrue(assertThrows(IOException.class, () -> ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true))).getMessage().contains(bad.toString()));
    }

    @Test void rejectsDuplicatesAndInvalidVersions() throws Exception {
        UUID id = UUID.randomUUID();
        archive(dir.resolve("a.zip"), id, "[1,0,0]", false);
        Path second = archive(dir.resolve("b.zip"), id, "[1,0,0]", false);
        assertTrue(assertThrows(IOException.class, () -> ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true))).getMessage().contains(second.toString()));
        Files.delete(second); Files.delete(dir.resolve("a.zip"));
        archive(second, id, "\"1.no.0\"", false);
        assertTrue(assertThrows(IOException.class, () -> ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true))).getMessage().contains(second.toString()));
    }

    String load() {
        return assertThrows(IOException.class, () -> ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true))).getMessage();
    }

    @Test void rejectsMarketplaceEncryptedContents() throws Exception {
        UUID id = UUID.randomUUID();
        String manifest = "{\"header\":{\"uuid\":\"" + id + "\",\"version\":[1,0,0]},\"modules\":[{\"type\":\"resources\"}]}";
        // Version 0, then 0x9BCFB9FC as a little-endian uint32, then encrypted bytes.
        byte[] encrypted = new byte[0x100];
        encrypted[4] = (byte) 0xFC; encrypted[5] = (byte) 0xB9; encrypted[6] = (byte) 0xCF; encrypted[7] = (byte) 0x9B;
        Path path = archive(dir.resolve("encrypted.zip"), manifest, encrypted);
        String message = load();
        assertTrue(message.contains(path.toString()), message);
        assertTrue(message.contains("encrypted"), message);
        // The same bytes in the wrong order, and a contents.json too short to hold the magic, are not the marker.
        Files.delete(path);
        encrypted[4] = (byte) 0x9B; encrypted[5] = (byte) 0xCF; encrypted[6] = (byte) 0xB9; encrypted[7] = (byte) 0xFC;
        archive(path, manifest, encrypted);
        assertEquals(1, ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true)).packs.size());
        Files.delete(path);
        archive(path, manifest, new byte[] {0, 0, 0, 0, (byte) 0xFC, (byte) 0xB9, (byte) 0xCF});
        assertEquals(1, ResourcePacks.load(new BoariumConfig.ResourcePacks(dir,false,true)).packs.size());
    }

    @Test void namesMissingManifestFields() throws Exception {
        UUID id = UUID.randomUUID();
        var cases = java.util.Map.of(
                "{\"modules\":[{\"type\":\"resources\"}]}", "missing header",
                "{\"header\":{\"version\":[1,0,0]},\"modules\":[{\"type\":\"resources\"}]}", "missing header.uuid",
                "{\"header\":{\"uuid\":\"" + id + "\"},\"modules\":[{\"type\":\"resources\"}]}", "missing header.version",
                "{\"header\":{\"uuid\":\"" + id + "\",\"version\":[1,0,0]}}", "missing modules",
                "{\"header\":{\"uuid\":\"" + id + "\",\"version\":[1,0,0]},\"modules\":[{}]}", "missing modules[].type");
        Path path = dir.resolve("pack.zip");
        for (var entry : cases.entrySet()) {
            archive(path, entry.getKey(), null);
            String message = load();
            assertTrue(message.contains(path.toString()) && message.contains(entry.getValue()), message);
            Files.delete(path);
        }
    }
}
