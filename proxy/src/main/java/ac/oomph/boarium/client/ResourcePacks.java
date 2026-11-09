package ac.oomph.boarium.client;

import ac.oomph.boarium.BoariumConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

/** A bounded, immutable startup snapshot shared by all logins; chunk replies never read the filesystem. */
public final class ResourcePacks {
    static final int CHUNK_SIZE = 128 * 1024;
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private static final int MAX_MANIFEST = 1024 * 1024;
    public static final ResourcePacks EMPTY = new ResourcePacks(List.of(), false, true);
    final List<Pack> packs;
    final boolean required;
    final boolean includeExempted;

    private ResourcePacks(List<Pack> packs, boolean required, boolean includeExempted) {
        this.packs = List.copyOf(packs);
        this.required = required;
        this.includeExempted = includeExempted;
    }

    /** Loads resource-only ZIP archives in filename order, rejecting invalid or duplicate pack identities. */
    public static ResourcePacks load(BoariumConfig.ResourcePacks config) throws IOException {
        List<Pack> packs = new ArrayList<>();
        var ids = new HashSet<UUID>();
        int remaining = MAX_BYTES;
        if (config.directory() != null) {
            try (var files = Files.list(config.directory())) {
                for (Path path : files.sorted().toList()) {
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".mcpack") && !name.endsWith(".zip")) {
                        continue;
                    }
                    if (packs.size() == 64) {
                        throw new IOException("resource_packs.directory contains more than 64 packs");
                    }
                    Pack pack = read(path, remaining);
                    if (!ids.add(pack.id)) {
                        throw new IOException("duplicate resource pack UUID: " + pack.id);
                    }
                    packs.add(pack);
                    remaining -= pack.bytes.length;
                }
            }
        }
        return new ResourcePacks(packs, config.required(), config.includeExempted());
    }

    // Bound both compressed data and the only ZIP entry we decompress. Never extract archive paths.
    private static Pack read(Path path, int remaining) throws IOException {
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(remaining + 1);
            }
            if (bytes.length > remaining) {
                throw new IOException("resource packs exceed the 256 MiB combined archive limit");
            }
            JsonObject manifest;
            try (var zip = new ZipFile(path.toFile())) {
                var entry = zip.getEntry("manifest.json");
                if (entry == null || zip.getEntry("contents.json") != null) {
                    throw new IOException("expected an unencrypted pack with manifest.json at the archive root");
                }
                try (var input = zip.getInputStream(entry)) {
                    byte[] json = input.readNBytes(MAX_MANIFEST + 1);
                    if (json.length > MAX_MANIFEST) {
                        throw new IOException("manifest.json exceeds 1 MiB");
                    }
                    manifest = new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
            JsonObject header = manifest.getAsJsonObject("header");
            UUID id = UUID.fromString(header.get("uuid").getAsString());
            JsonArray version = header.getAsJsonArray("version");
            if (version.size() != 3) {
                throw new IllegalArgumentException("header.version must have three integers");
            }
            List<String> parts = new ArrayList<>();
            for (JsonElement part : version) {
                String value = part.getAsString();
                if (!value.matches("0|[1-9][0-9]*") || Long.parseLong(value) > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("invalid version component");
                }
                parts.add(value);
            }
            JsonArray modules = manifest.getAsJsonArray("modules");
            if (modules.size() == 0) {
                throw new IllegalArgumentException("pack has no modules");
            }
            for (JsonElement module : modules) {
                if (!module.getAsJsonObject().get("type").getAsString().equals("resources")) {
                    throw new IllegalArgumentException("only resource modules are supported");
                }
            }
            boolean raytracing = false;
            if (manifest.has("capabilities")) {
                for (JsonElement capability : manifest.getAsJsonArray("capabilities")) {
                    raytracing |= capability.getAsString().equals("raytraced");
                }
            }
            return new Pack(id, String.join(".", parts), bytes, MessageDigest.getInstance("SHA-256").digest(bytes), raytracing);
        } catch (RuntimeException | NoSuchAlgorithmException e) {
            throw new IOException("invalid resource pack " + path + ": " + e.getMessage(), e);
        }
    }

    /** One immutable archive; replies use independent buffers so releasing them cannot affect another client. */
    static final class Pack {
        final UUID id;
        final String version;
        private final byte[] bytes;
        private final byte[] hash;
        private final boolean raytracing;

        private Pack(UUID id, String version, byte[] bytes, byte[] hash, boolean raytracing) {
            this.id = id;
            this.version = version;
            this.bytes = bytes;
            this.hash = hash;
            this.raytracing = raytracing;
        }

        // Clients use UUID_version in SEND_PACKS, and separate fields in chunk requests.
        String key() {
            return this.id + "_" + this.version;
        }

        ResourcePacksInfoPacket.Entry info() {
            return new ResourcePacksInfoPacket.Entry(this.id, this.version, this.bytes.length, "", "", "", false, this.raytracing, false, "");
        }

        ResourcePackDataInfoPacket dataInfo() {
            ResourcePackDataInfoPacket packet = new ResourcePackDataInfoPacket();
            packet.setPackId(this.id);
            packet.setPackVersion(this.version);
            packet.setMaxChunkSize(CHUNK_SIZE);
            packet.setChunkCount((this.bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
            packet.setCompressedPackSize(this.bytes.length);
            packet.setHash(this.hash.clone());
            packet.setType(ResourcePackType.RESOURCES);
            return packet;
        }

        // Validate before multiplication, including negative and overflowing client indices.
        ResourcePackChunkDataPacket chunk(int index) {
            if (index < 0 || index >= (this.bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE) {
                throw new IllegalArgumentException("resource pack chunk index out of range");
            }
            int offset = index * CHUNK_SIZE;
            ResourcePackChunkDataPacket packet = new ResourcePackChunkDataPacket();
            packet.setPackId(this.id);
            packet.setPackVersion(this.version);
            packet.setChunkIndex(index);
            packet.setProgress(offset);
            packet.setData(Unpooled.copiedBuffer(this.bytes, offset, Math.min(CHUNK_SIZE, this.bytes.length - offset)));
            return packet;
        }
    }
}
