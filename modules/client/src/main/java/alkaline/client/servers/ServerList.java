package alkaline.client.servers;

import alkaline.config.Json;
import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player's saved servers, persisted as Alkaline JSON. On first use the list is seeded from vanilla's
 * {@code servers.dat}, so servers saved in the vanilla launcher appear without being shared afterwards (Alkaline
 * never writes vanilla's file). Mutations apply immediately in memory and are written in order on one
 * background thread.
 */
public final class ServerList implements AutoCloseable {
    private static final int FORMAT = 1;
    private static final int MAX_SERVERS = 256;

    /** Notified on the mutating thread after every change. */
    @FunctionalInterface
    public interface Listener {
        void changed(List<ServerEntry> servers);
    }

    private final Path file;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("alkaline-server-list").daemon().factory());
    private volatile List<ServerEntry> servers;

    /**
     * Loads synchronously: the file is a few kilobytes and is read once, before the menu shows.
     *
     * @param vanillaServers vanilla's {@code servers.dat}, used only when Alkaline has no list yet
     */
    public ServerList(Path file, Path vanillaServers) {
        this.file = Objects.requireNonNull(file, "file");
        List<ServerEntry> loaded;
        if (Files.isRegularFile(file)) {
            loaded = readJson(file);
        } else {
            loaded = importVanilla(vanillaServers);
            if (!loaded.isEmpty()) {
                save(loaded);
            }
        }
        servers = List.copyOf(loaded);
    }

    public List<ServerEntry> servers() {
        return servers;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Adds a server at the top of the list; returns false when the list is full. */
    public synchronized boolean add(ServerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (servers.size() >= MAX_SERVERS) {
            return false;
        }
        List<ServerEntry> next = new ArrayList<>(servers.size() + 1);
        next.add(entry);
        next.addAll(servers);
        publish(next);
        return true;
    }

    public synchronized void replace(ServerEntry previous, ServerEntry updated) {
        List<ServerEntry> next = new ArrayList<>(servers);
        int index = next.indexOf(previous);
        if (index >= 0) {
            next.set(index, Objects.requireNonNull(updated, "updated"));
            publish(next);
        }
    }

    public synchronized void remove(ServerEntry entry) {
        List<ServerEntry> next = new ArrayList<>(servers);
        if (next.remove(entry)) {
            publish(next);
        }
    }

    /** The first saved entry for {@code address} (as typed when joining), or null for a direct connection. */
    public ServerEntry find(String address) {
        for (ServerEntry entry : servers) {
            if (entry.address().equalsIgnoreCase(address.strip())) {
                return entry;
            }
        }
        return null;
    }

    /** Moves an entry by {@code delta} places, clamped to the list. */
    public synchronized void move(ServerEntry entry, int delta) {
        List<ServerEntry> next = new ArrayList<>(servers);
        int index = next.indexOf(entry);
        if (index < 0) {
            return;
        }
        int target = Math.max(0, Math.min(next.size() - 1, index + delta));
        if (target != index) {
            next.add(target, next.remove(index));
            publish(next);
        }
    }

    private void publish(List<ServerEntry> next) {
        servers = List.copyOf(next);
        List<ServerEntry> snapshot = servers;
        writer.execute(() -> save(snapshot));
        for (Listener listener : listeners) {
            listener.changed(snapshot);
        }
    }

    private void save(List<ServerEntry> snapshot) {
        List<Object> entries = new ArrayList<>(snapshot.size());
        for (ServerEntry entry : snapshot) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", entry.name());
            value.put("address", entry.address());
            if (entry.packs() != ServerEntry.Packs.PROMPT) {
                value.put("resourcePacks", entry.packs().name().toLowerCase(java.util.Locale.ROOT));
            }
            entries.add(value);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", (long) FORMAT);
        root.put("servers", entries);
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "servers", ".tmp");
            Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            System.err.println("Saving servers failed: " + failure.getMessage());
        }
    }

    private static List<ServerEntry> readJson(Path file) {
        List<ServerEntry> result = new ArrayList<>();
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("servers") instanceof List<?> entries) {
                for (Object value : entries) {
                    if (value instanceof Map<?, ?> entry && entry.get("address") instanceof String address
                            && result.size() < MAX_SERVERS) {
                        try {
                            result.add(new ServerEntry(entry.get("name") instanceof String name ? name : "", address,
                                    packs(entry.get("resourcePacks"))));
                        } catch (IllegalArgumentException invalid) {
                            // Skip entries without an address.
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Reading " + file + " failed: " + failure.getMessage());
        }
        return result;
    }

    private static ServerEntry.Packs packs(Object saved) {
        if (saved instanceof String name) {
            for (ServerEntry.Packs packs : ServerEntry.Packs.values()) {
                if (packs.name().equalsIgnoreCase(name)) {
                    return packs;
                }
            }
        }
        return ServerEntry.Packs.PROMPT;
    }

    /** Vanilla's servers.dat: uncompressed NBT, {@code servers} list of {@code {name, ip}} compounds. */
    static List<ServerEntry> importVanilla(Path vanillaServers) {
        List<ServerEntry> result = new ArrayList<>();
        if (vanillaServers == null || !Files.isRegularFile(vanillaServers)) {
            return result;
        }
        try {
            NBTTagCompound root = CompressedStreamTools.read(vanillaServers.toFile());
            if (root == null) {
                return result;
            }
            NBTTagList list = root.getTagList("servers", 10);
            for (int index = 0; index < list.tagCount() && result.size() < MAX_SERVERS; index++) {
                NBTTagCompound server = list.getCompoundTagAt(index);
                String address = server.getString("ip");
                if (!address.isBlank()) {
                    // ServerData.getNBTCompound: acceptTextures is absent while the player has not chosen.
                    ServerEntry.Packs packs = !server.hasKey("acceptTextures") ? ServerEntry.Packs.PROMPT
                            : server.getBoolean("acceptTextures") ? ServerEntry.Packs.ENABLED : ServerEntry.Packs.DISABLED;
                    result.add(new ServerEntry(server.getString("name"), address, packs));
                }
            }
        } catch (IOException | RuntimeException failure) {
            System.err.println("Importing vanilla servers failed: " + failure.getMessage());
        }
        return result;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("Server list save did not finish in time.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
