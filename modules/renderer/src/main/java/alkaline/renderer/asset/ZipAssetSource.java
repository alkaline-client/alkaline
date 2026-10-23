package alkaline.renderer.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Assets inside a client jar or zipped resource pack. */
public final class ZipAssetSource implements AssetSource {
    private final Path path;
    private final ZipFile zip;

    public ZipAssetSource(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        this.zip = new ZipFile(path.toFile());
    }

    @Override
    public InputStream open(String assetPath) throws IOException {
        ZipEntry entry = zip.getEntry(Objects.requireNonNull(assetPath, "assetPath"));
        return entry == null || entry.isDirectory() ? null : zip.getInputStream(entry);
    }

    @Override
    public List<String> list(String prefix) {
        List<String> paths = new ArrayList<>();
        for (java.util.Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                paths.add(entry.getName());
            }
        }
        return paths;
    }

    @Override
    public String describe() {
        return path.toString();
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
