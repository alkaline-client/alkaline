package alkaline.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the current {@link ConfigSnapshot} and its JSON file. Reads are a single volatile load and are safe from
 * any thread, including the render loop. Writes are copy-on-write, notify listeners on the writing thread,
 * and schedule a debounced atomic save on a dedicated I/O thread so disk access never blocks rendering.
 *
 * <p>File format: {@code {"schema": <version>, "settings": {"<id>": <value>, ...}}}. Unknown keys are kept
 * and written back. A file from a newer schema is loaded best-effort and never overwritten.
 */
public final class ConfigStore implements AutoCloseable {
    private static final long SAVE_DELAY_MILLIS = 250;
    private static final String SCHEMA_KEY = "schema";
    private static final String SETTINGS_KEY = "settings";

    /** Notified after a change becomes visible; runs on the thread that made the change. */
    @FunctionalInterface
    public interface Listener {
        void changed(ConfigSnapshot previous, ConfigSnapshot current);
    }

    /** Persistence state for settings screens; changes are reported to {@link SaveListener}s. */
    public enum SaveState {
        /** Every visible change is on disk. */
        SAVED,
        /** A debounced write is scheduled or running. */
        PENDING,
        /** The last write failed; the next change retries. */
        FAILED,
        /** The file is from a newer schema or unreadable, so changes are kept in memory only. */
        READ_ONLY
    }

    /** Notified on the I/O thread or the changing thread whenever {@link #saveState()} changes. */
    @FunctionalInterface
    public interface SaveListener {
        void saveStateChanged(SaveState state);
    }

    /** What loading found and repaired. */
    public record LoadReport(boolean fileExisted, int fileVersion, boolean migrated, boolean readOnly,
                             List<String> issues) {
        public LoadReport {
            issues = List.copyOf(issues);
        }
    }

    private final ConfigSchema schema;
    private volatile Path file;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<SaveListener> saveListeners = new CopyOnWriteArrayList<>();
    private volatile SaveState saveState = SaveState.SAVED;
    private final ScheduledExecutorService io;
    private final Object writeLock = new Object();
    private volatile ConfigSnapshot current;
    private volatile boolean readOnly;
    private ScheduledFuture<?> pendingSave;
    private IOException lastSaveFailure;
    private boolean closed;

    public ConfigStore(ConfigSchema schema, Path file) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        current = ConfigSnapshot.defaults(schema);
        io = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alkaline-config-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ConfigSchema schema() {
        return schema;
    }

    public Path file() {
        return file;
    }

    /**
     * Moves the store to another file (a different profile): pending changes are written to the current file
     * first, then {@code next} is loaded as by {@link #load()} and its values are published to listeners. Call
     * off the render loop; it waits for the I/O thread.
     */
    public LoadReport switchTo(Path next) throws IOException {
        Path target = Objects.requireNonNull(next, "next").toAbsolutePath();
        boolean changed;
        synchronized (writeLock) {
            changed = pendingSave != null;
        }
        // Only a store that changed since its last load has anything to write; a fresh one holds defaults that
        // must not replace the file it has not read yet.
        if (changed && !readOnly) {
            flush();
        }
        synchronized (writeLock) {
            requireOpen();
            file = target;
            readOnly = false;
            lastSaveFailure = null;
            pendingSave = null;
        }
        publishSaveState(SaveState.SAVED);
        return load();
    }

    /** Writes the current values to {@code target} now (a copy, for example a duplicated profile) and waits. */
    public void writeCopy(Path target) throws IOException {
        Path destination = Objects.requireNonNull(target, "target").toAbsolutePath();
        ConfigSnapshot snapshot = current;
        try {
            io.submit(() -> {
                write(destination, snapshot);
                return null;
            }).get();
        } catch (java.util.concurrent.ExecutionException failure) {
            throw failure.getCause() instanceof IOException io ? io : new IOException("Copy failed.", failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while copying settings.", interrupted);
        }
    }

    /** Lock-free read of the latest values. */
    public ConfigSnapshot snapshot() {
        return current;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public SaveState saveState() {
        return readOnly ? SaveState.READ_ONLY : saveState;
    }

    public void addSaveListener(SaveListener listener) {
        saveListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeSaveListener(SaveListener listener) {
        saveListeners.remove(listener);
    }

    /**
     * Loads the file (missing is fine), migrates older schemas, replaces invalid values with defaults, and backs
     * up unparseable files before starting from defaults. Call once during startup, off the render loop.
     */
    public LoadReport load() {
        List<String> issues = new ArrayList<>();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return publishLoaded(ConfigSnapshot.defaults(schema), new LoadReport(false, 0, false, false, issues));
        } catch (IOException failure) {
            issues.add("Could not read " + file + ": " + failure.getMessage() + "; using defaults without saving.");
            readOnly = true;
            return publishLoaded(ConfigSnapshot.defaults(schema), new LoadReport(true, 0, false, true, issues));
        }

        Map<String, Object> settings;
        int fileVersion;
        try {
            if (text.length() > Json.MAX_CHARS) {
                throw new Json.JsonException("Settings file is larger than " + Json.MAX_CHARS + " characters.");
            }
            if (!(Json.parse(text) instanceof Map<?, ?> root)) {
                throw new Json.JsonException("The settings root must be a JSON object.");
            }
            if (!(root.get(SCHEMA_KEY) instanceof Long version) || version < 1 || version > Integer.MAX_VALUE) {
                throw new Json.JsonException("Missing or invalid \"schema\" version.");
            }
            fileVersion = version.intValue();
            if (!(root.get(SETTINGS_KEY) instanceof Map<?, ?> rawSettings)) {
                throw new Json.JsonException("Missing \"settings\" object.");
            }
            settings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawSettings.entrySet()) {
                settings.put((String) entry.getKey(), entry.getValue());
            }
        } catch (Json.JsonException failure) {
            Path backup = backupCorrupt();
            issues.add("Settings file is invalid (" + failure.getMessage() + "); "
                    + (backup == null ? "it could not be backed up and will not be overwritten."
                    : "moved to " + backup.getFileName() + " and defaults were restored."));
            readOnly = backup == null;
            return publishLoaded(ConfigSnapshot.defaults(schema), new LoadReport(true, 0, false, readOnly, issues));
        }

        boolean migrated = false;
        if (fileVersion > schema.version()) {
            readOnly = true;
            issues.add("Settings were written by a newer schema (" + fileVersion + " > " + schema.version()
                    + "); loading known values without saving.");
        } else {
            for (int version = fileVersion; version < schema.version(); version++) {
                schema.migrationFrom(version).migrate(settings);
                migrated = true;
            }
        }

        ConfigSnapshot loaded = ConfigSnapshot.defaults(schema).copy();
        Map<String, Object> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            Setting<?> setting = schema.find(entry.getKey());
            if (setting == null) {
                unknown.put(entry.getKey(), entry.getValue());
            } else if (!decodeInto(loaded, setting, entry.getValue())) {
                issues.add("Invalid value for " + setting.id() + "; using default " + setting.defaultValue() + ".");
            }
        }
        // Settings added since the file was written are a compatible change: they take defaults silently and
        // the file is rewritten so it lists every current key.
        boolean missing = false;
        for (Setting<?> setting : schema.settings()) {
            missing |= !settings.containsKey(setting.id());
        }
        LoadReport report = new LoadReport(true, fileVersion, migrated, readOnly, issues);
        publishLoaded(loaded.withUnknown(unknown), report);
        if (!readOnly && (migrated || missing || !issues.isEmpty())) {
            scheduleSave();
        }
        return report;
    }

    /** Validates and publishes one value. Throws {@link IllegalArgumentException} for invalid values. */
    public <T> void set(Setting<T> setting, T value) {
        ConfigSnapshot previous;
        ConfigSnapshot next;
        synchronized (writeLock) {
            requireOpen();
            previous = current;
            next = previous.with(setting, value);
            if (next.equals(previous)) {
                return;
            }
            current = next;
        }
        notifyListeners(previous, next);
        scheduleSave();
    }

    public <T> void reset(Setting<T> setting) {
        set(setting, setting.defaultValue());
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Writes pending changes now and waits; reports the last I/O failure, if any. */
    public void flush() throws IOException {
        synchronized (writeLock) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        try {
            io.submit(this::saveNow).get();
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("Settings save failed.", failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving settings.", interrupted);
        }
        synchronized (writeLock) {
            if (lastSaveFailure != null) {
                throw lastSaveFailure;
            }
        }
    }

    /** Flushes pending changes, then stops the I/O thread. */
    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
        }
        try {
            flush();
        } finally {
            synchronized (writeLock) {
                closed = true;
            }
            io.shutdown();
        }
    }

    private LoadReport publishLoaded(ConfigSnapshot snapshot, LoadReport report) {
        ConfigSnapshot previous;
        synchronized (writeLock) {
            previous = current;
            current = snapshot;
        }
        if (!snapshot.equals(previous)) {
            notifyListeners(previous, snapshot);
        }
        return report;
    }

    private static <T> boolean decodeInto(ConfigSnapshot target, Setting<T> setting, Object json) {
        T value = setting.decode(json);
        if (value == null) {
            return false;
        }
        target.store(setting, value);
        return true;
    }

    private void notifyListeners(ConfigSnapshot previous, ConfigSnapshot next) {
        for (Listener listener : listeners) {
            listener.changed(previous, next);
        }
    }

    private void scheduleSave() {
        if (readOnly) {
            return;
        }
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = io.schedule(this::saveNow, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
        publishSaveState(SaveState.PENDING);
    }

    private void publishSaveState(SaveState state) {
        if (saveState == state) {
            return;
        }
        saveState = state;
        SaveState visible = saveState();
        for (SaveListener listener : saveListeners) {
            listener.saveStateChanged(visible);
        }
    }

    /** Runs on the I/O thread: write a temp file beside the target, then atomically replace it. */
    private void saveNow() {
        if (readOnly) {
            return;
        }
        ConfigSnapshot snapshot = current;
        IOException failure = null;
        try {
            write(file, snapshot);
        } catch (IOException ioFailure) {
            failure = ioFailure;
        }
        boolean stillPending;
        synchronized (writeLock) {
            lastSaveFailure = failure;
            stillPending = pendingSave != null && !pendingSave.isDone() && current != snapshot;
        }
        if (!stillPending) {
            publishSaveState(failure != null ? SaveState.FAILED : current == snapshot ? SaveState.SAVED : SaveState.PENDING);
        }
    }

    private void write(Path destination, ConfigSnapshot snapshot) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(SCHEMA_KEY, (long) schema.version());
        root.put(SETTINGS_KEY, snapshot.encode());
        String text = Json.write(root);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private Path backupCorrupt() {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
        try {
            Files.move(file, backup);
            return backup;
        } catch (IOException failure) {
            return null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Config store is closed.");
        }
    }
}
