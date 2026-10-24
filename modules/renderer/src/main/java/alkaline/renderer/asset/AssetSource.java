package alkaline.renderer.asset;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only lookup of Minecraft-style asset paths such as {@code assets/minecraft/textures/gui/widgets.png}.
 * Alkaline never ships Minecraft assets; sources point at the player's own installation or resource packs.
 */
public interface AssetSource extends AutoCloseable {
    /** Opens an asset, or returns null when this source does not contain it. */
    InputStream open(String path) throws IOException;

    /** Human-readable origin for diagnostics. */
    String describe();

    /**
     * Every file path under a directory prefix (for example {@code assets/minecraft/optifine/cit/}), for features
     * that discover files by name (OptiFine's properties). Sources that cannot list return nothing.
     */
    default List<String> list(String prefix) throws IOException {
        return List.of();
    }

    @Override
    default void close() throws IOException {
    }

    /** Every layer's copy of an asset, lowest priority first (vanilla's getAllResources, for sounds.json). */
    default List<byte[]> readAll(String path) throws IOException {
        try (InputStream input = open(path)) {
            return input == null ? List.of() : List.of(input.readAllBytes());
        }
    }

    /** Tries each source in order, so a resource pack can override the vanilla jar. */
    static AssetSource layered(AssetSource... sources) {
        AssetSource[] copy = sources.clone();
        return new AssetSource() {
            @Override
            public InputStream open(String path) throws IOException {
                for (AssetSource source : copy) {
                    InputStream input = source.open(path);
                    if (input != null) {
                        return input;
                    }
                }
                return null;
            }

            @Override
            public List<String> list(String prefix) throws IOException {
                Set<String> paths = new LinkedHashSet<>();
                for (AssetSource source : copy) {
                    paths.addAll(source.list(prefix));
                }
                return new ArrayList<>(paths);
            }

            @Override
            public List<byte[]> readAll(String path) throws IOException {
                List<byte[]> copies = new ArrayList<>();
                for (int index = copy.length - 1; index >= 0; index--) {
                    copies.addAll(copy[index].readAll(path));
                }
                return copies;
            }

            @Override
            public String describe() {
                StringBuilder text = new StringBuilder();
                for (AssetSource source : copy) {
                    if (!text.isEmpty()) {
                        text.append(" > ");
                    }
                    text.append(source.describe());
                }
                return text.toString();
            }

            @Override
            public void close() throws IOException {
                IOException failure = null;
                for (AssetSource source : copy) {
                    try {
                        source.close();
                    } catch (IOException closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        } else {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }
}
