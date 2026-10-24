package alkaline.renderer.asset;

import alkaline.config.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The launcher's hashed asset objects, which 1.8.9 reads beside its jar (DefaultResourcePack's asset map): the
 * index {@code assets/indexes/1.8.json} names files such as {@code minecraft/sounds.json} and every sound, and
 * each lives under {@code assets/objects/<first two hex digits>/<sha1>}. Paths are served as
 * {@code assets/minecraft/...}, as the jar and resource packs name theirs.
 */
public final class AssetIndexSource implements AssetSource {
    /** The asset index 1.8.9's version manifest names. */
    public static final String INDEX = "1.8";

    private final Path objects;
    private final Map<String, String> hashes;

    private AssetIndexSource(Path objects, Map<String, String> hashes) {
        this.objects = objects;
        this.hashes = hashes;
    }

    /** The index under a launcher directory, or null when it is absent or unreadable. */
    public static AssetIndexSource open(Path gameDirectory) {
        Path assets = gameDirectory.resolve("assets");
        Path index = assets.resolve("indexes").resolve(INDEX + ".json");
        if (!Files.isRegularFile(index)) {
            System.err.println("No asset index at " + index + "; sounds are unavailable.");
            return null;
        }
        try {
            Object root = Json.parse(Files.readString(index, StandardCharsets.UTF_8));
            Map<String, String> hashes = new HashMap<>();
            if (root instanceof Map<?, ?> top && top.get("objects") instanceof Map<?, ?> entries) {
                for (Map.Entry<?, ?> entry : entries.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> object && object.get("hash") instanceof String hash
                            && hash.length() > 2) {
                        hashes.put("assets/" + entry.getKey(), hash);
                    }
                }
            }
            return new AssetIndexSource(assets.resolve("objects"), Map.copyOf(hashes));
        } catch (IOException | RuntimeException failure) {
            System.err.println("Cannot read the asset index " + index + ": " + failure.getMessage());
            return null;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        String hash = hashes.get(Objects.requireNonNull(path, "path"));
        if (hash == null) {
            return null;
        }
        Path file = objects.resolve(hash.substring(0, 2)).resolve(hash);
        return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
    }

    @Override
    public List<String> list(String prefix) {
        return hashes.keySet().stream().filter(path -> path.startsWith(prefix)).sorted().toList();
    }

    @Override
    public String describe() {
        return objects + " (index " + INDEX + ")";
    }
}
