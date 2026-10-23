package alkaline.renderer.asset;

import alkaline.config.Json;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Resource packs as vanilla 1.8.9 finds them (ResourcePackRepository): folders and zip files with a
 * {@code pack.mcmeta}, in one or more pack folders (Alkaline's own and the Minecraft installation's), each with a
 * name, description and {@code pack.png} icon. Selected packs layer over the vanilla assets, the first one winning.
 */
public final class ResourcePacks {
    /** The pack format 1.8.9 reads; others still load, as vanilla allows after a warning. */
    public static final int FORMAT = 1;

    /**
     * One pack.
     *
     * @param id          {@code <folder label>/<file name>}, stable across restarts
     * @param description the pack's description, formatting codes stripped
     * @param icon        pack.png, or null
     */
    public record Pack(String id, Path path, String name, String description, RgbaImage icon, int format) {
        public boolean compatible() {
            return format == FORMAT;
        }
    }

    /** A folder packs are read from, labeled for pack ids. */
    public record Folder(String label, Path path) {
    }

    private ResourcePacks() {
    }

    /** Every pack in the folders, by name. Unreadable entries are skipped. */
    public static List<Pack> scan(List<Folder> folders) {
        List<Pack> packs = new ArrayList<>();
        for (Folder folder : folders) {
            if (!Files.isDirectory(folder.path())) {
                continue;
            }
            try (Stream<Path> entries = Files.list(folder.path())) {
                for (Path entry : entries.sorted().toList()) {
                    Pack pack = read(folder.label(), entry);
                    if (pack != null) {
                        packs.add(pack);
                    }
                }
            } catch (IOException failure) {
                System.err.println("Cannot list resource packs in " + folder.path() + ": " + failure.getMessage());
            }
        }
        packs.sort(Comparator.comparing(pack -> pack.name().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(packs);
    }

    private static Pack read(String label, Path entry) {
        boolean directory = Files.isDirectory(entry);
        if (!directory && !entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            return null;
        }
        try (AssetSource source = open(entry)) {
            Map<?, ?> pack = null;
            try (InputStream input = source.open("pack.mcmeta")) {
                if (input == null) {
                    return null;
                }
                Object parsed = Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> root && root.get("pack") instanceof Map<?, ?> section) {
                    pack = section;
                }
            }
            int format = pack != null && pack.get("pack_format") instanceof Number number ? number.intValue() : 0;
            String description = pack == null ? "" : text(pack.get("description"));
            RgbaImage icon = null;
            try (InputStream input = source.open("pack.png")) {
                if (input != null) {
                    icon = ImageDecoder.decode(input);
                }
            } catch (IOException | RuntimeException unreadable) {
                icon = null;
            }
            String file = entry.getFileName().toString();
            // File names often carry § color codes for vanilla's list; the plain name reads better.
            String name = (directory ? file : file.substring(0, file.length() - 4)).replaceAll("§.", "").trim();
            return new Pack(label + "/" + file, entry, name, description, icon, format);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Resource pack " + entry + " skipped: " + failure.getMessage());
            return null;
        }
    }

    /** A pack's files: a folder or a zip. */
    public static AssetSource open(Path pack) throws IOException {
        return Files.isDirectory(pack) ? new DirectoryAssetSource(pack) : new ZipAssetSource(pack);
    }

    /**
     * The selected packs over the vanilla assets, first pack winning. Closing the result closes the packs but not
     * {@code vanilla}, which outlives every selection. Packs that cannot be opened are left out.
     */
    public static AssetSource layer(List<Pack> selected, AssetSource vanilla) {
        Objects.requireNonNull(vanilla, "vanilla");
        List<AssetSource> sources = new ArrayList<>();
        for (Pack pack : selected) {
            try {
                sources.add(open(pack.path()));
            } catch (IOException failure) {
                System.err.println("Resource pack " + pack.path() + " unavailable: " + failure.getMessage());
            }
        }
        if (sources.isEmpty()) {
            return kept(vanilla);
        }
        sources.add(kept(vanilla));
        return AssetSource.layered(sources.toArray(AssetSource[]::new));
    }

    /** A view of a source whose close does nothing. */
    private static AssetSource kept(AssetSource source) {
        return new AssetSource() {
            @Override
            public InputStream open(String path) throws IOException {
                return source.open(path);
            }

            @Override
            public List<String> list(String prefix) throws IOException {
                return source.list(prefix);
            }

            @Override
            public String describe() {
                return source.describe();
            }
        };
    }

    /** A description as plain text: a string or a chat component, with § formatting codes removed. */
    private static String text(Object value) {
        StringBuilder text = new StringBuilder();
        append(value, text);
        return text.toString().replaceAll("§.", "").trim();
    }

    private static void append(Object value, StringBuilder text) {
        if (value instanceof String string) {
            text.append(string);
        } else if (value instanceof Map<?, ?> component) {
            append(component.get("text"), text);
            append(component.get("extra"), text);
        } else if (value instanceof List<?> list) {
            for (Object part : list) {
                append(part, text);
            }
        }
    }
}
