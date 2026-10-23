package alkaline.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Named sets of values for one schema, one JSON file each ({@code <name>.json} in the profile directory), of
 * which exactly one is active. The active profile is read and written through {@link #store()}, a single
 * {@link ConfigStore} that moves between files when the profile changes, so readers and listeners never have to
 * re-subscribe. The active name is kept in {@code profiles.json}.
 *
 * <p>Profile operations do disk I/O and wait for the store's I/O thread: call them from the UI thread or a
 * worker, never from the render loop. Listeners hear about list and active-profile changes.
 */
public final class ProfileStore implements AutoCloseable {
    public static final String DEFAULT = "Default";
    private static final String INDEX = "profiles.json";
    private static final String SUFFIX = ".json";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,23}");

    private final Path directory;
    private final ConfigStore store;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<String> names = List.of();
    private volatile String active = DEFAULT;

    public ProfileStore(ConfigSchema schema, Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
        store = new ConfigStore(schema, file(DEFAULT));
    }

    /** Whether {@code name} can name a profile: 1-24 letters, digits, spaces, dashes or underscores. */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.equalsIgnoreCase(INDEX.replace(SUFFIX, ""));
    }

    /** The active profile's values. */
    public ConfigStore store() {
        return store;
    }

    public String active() {
        return active;
    }

    /** Profile names, sorted case-insensitively. */
    public List<String> names() {
        return names;
    }

    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Reads the profile list and loads the active profile (creating {@value #DEFAULT} the first time). */
    public ConfigStore.LoadReport open() throws IOException {
        Files.createDirectories(directory);
        String wanted = readIndex();
        rescan();
        if (names.isEmpty()) {
            wanted = DEFAULT;
        } else if (wanted == null || find(wanted) == null) {
            wanted = names.getFirst();
        } else {
            wanted = find(wanted);
        }
        active = wanted;
        ConfigStore.LoadReport report = store.switchTo(file(wanted));
        if (!Files.exists(file(wanted))) {
            store.writeCopy(file(wanted));
        }
        writeIndex();
        rescan();
        notifyListeners();
        return report;
    }

    /** Makes {@code name} active; its values replace the current ones for every reader. */
    public void activate(String name) throws IOException {
        String existing = require(name);
        if (existing.equals(active)) {
            return;
        }
        store.switchTo(file(existing));
        active = existing;
        writeIndex();
        notifyListeners();
    }

    /**
     * Creates a profile and makes it active: a copy of the active profile's values, or the schema defaults.
     *
     * @throws IllegalArgumentException when the name is invalid or taken
     */
    public void create(String name, boolean copyActive) throws IOException {
        String trimmed = checkNew(name);
        if (copyActive) {
            store.writeCopy(file(trimmed));
        } else {
            Files.deleteIfExists(file(trimmed));
        }
        store.switchTo(file(trimmed));
        if (!Files.exists(file(trimmed))) {
            store.writeCopy(file(trimmed));
        }
        active = trimmed;
        writeIndex();
        rescan();
        notifyListeners();
    }

    public void rename(String from, String to) throws IOException {
        String existing = require(from);
        String target = to == null ? null : to.strip();
        if (existing.equals(target)) {
            return;
        }
        if (!existing.equalsIgnoreCase(target)) {
            checkNew(target);
        } else if (!validName(target)) {
            throw new IllegalArgumentException("Profile names use 1-24 letters, digits, spaces, - or _.");
        }
        if (existing.equals(active)) {
            store.writeCopy(file(target));
            store.switchTo(file(target));
            if (!existing.equalsIgnoreCase(target)) {
                Files.deleteIfExists(file(existing));
            }
            active = target;
            writeIndex();
        } else {
            Path temporary = directory.resolve(existing + ".renaming");
            Files.move(file(existing), temporary);
            Files.move(temporary, file(target));
        }
        rescan();
        notifyListeners();
    }

    /** Deletes a profile; the last one cannot be deleted. Deleting the active one activates another. */
    public void delete(String name) throws IOException {
        String existing = require(name);
        if (names.size() <= 1) {
            throw new IllegalArgumentException("The last profile cannot be deleted.");
        }
        if (existing.equals(active)) {
            String next = names.stream().filter(other -> !other.equals(existing)).findFirst().orElseThrow();
            store.switchTo(file(next));
            active = next;
            writeIndex();
        }
        Files.deleteIfExists(file(existing));
        rescan();
        notifyListeners();
    }

    @Override
    public void close() throws IOException {
        store.close();
    }

    private Path file(String name) {
        return directory.resolve(name + SUFFIX);
    }

    private String checkNew(String name) {
        String trimmed = name == null ? null : name.strip();
        if (!validName(trimmed)) {
            throw new IllegalArgumentException("Profile names use 1-24 letters, digits, spaces, - or _.");
        }
        if (find(trimmed) != null) {
            throw new IllegalArgumentException("A profile named " + trimmed + " already exists.");
        }
        return trimmed;
    }

    private String require(String name) {
        String existing = find(name);
        if (existing == null) {
            throw new IllegalArgumentException("No profile named " + name + ".");
        }
        return existing;
    }

    /** The stored spelling of a name, matched case-insensitively (file systems may not distinguish case). */
    private String find(String name) {
        if (name == null) {
            return null;
        }
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                return existing;
            }
        }
        return null;
    }

    private void rescan() throws IOException {
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path entry : entries) {
                String file = entry.getFileName().toString();
                String name = file.substring(0, file.length() - SUFFIX.length());
                if (!file.equals(INDEX) && validName(name) && Files.isRegularFile(entry)) {
                    found.add(name);
                }
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        names = List.copyOf(found);
    }

    private String readIndex() {
        try {
            String text = Files.readString(directory.resolve(INDEX), StandardCharsets.UTF_8);
            if (text.length() <= Json.MAX_CHARS && Json.parse(text) instanceof Map<?, ?> root
                    && root.get("active") instanceof String name) {
                return name;
            }
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException | IllegalArgumentException unreadable) {
            System.err.println("Ignoring unreadable " + directory.resolve(INDEX) + ": " + unreadable.getMessage());
        }
        return null;
    }

    private void writeIndex() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("active", active);
        Path index = directory.resolve(INDEX);
        Path temporary = index.resolveSibling(INDEX + ".tmp");
        Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
        Files.move(temporary, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    @Override
    public String toString() {
        return "profiles " + names + " (active " + active + ") in " + directory;
    }
}
