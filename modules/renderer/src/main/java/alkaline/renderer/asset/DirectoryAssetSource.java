package alkaline.renderer.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Assets in an unpacked resource-pack directory; paths cannot escape the root. */
public final class DirectoryAssetSource implements AssetSource {
    private final Path root;

    public DirectoryAssetSource(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public InputStream open(String assetPath) throws IOException {
        Path resolved = root.resolve(Objects.requireNonNull(assetPath, "assetPath")).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return Files.newInputStream(resolved);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Path start = root.resolve(prefix).normalize();
        if (!start.startsWith(root) || !Files.isDirectory(start)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(start)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace(java.io.File.separatorChar, '/'))
                    .toList();
        }
    }

    @Override
    public String describe() {
        return root.toString();
    }
}
