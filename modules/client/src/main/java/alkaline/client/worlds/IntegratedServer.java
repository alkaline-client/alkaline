package alkaline.client.worlds;

import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleplayer through a local Paper 1.8.8 server (protocol 47, the same as 1.8.9) in a child JVM, bound to the
 * loopback address in offline mode. The client then joins it like any other server.
 *
 * <p>The world stays a vanilla save. Bukkit keeps the Nether and the End in their own world folders and would
 * move {@code DIM-1} and {@code DIM1} out of the save on first start, so the server sees the save through a world
 * container of links: {@code <id>} links to the save, {@code <id>_nether/DIM-1} and {@code <id>_the_end/DIM1} link
 * to the save's dimension folders. The singleplayer player lives in {@code level.dat}'s {@code Data.Player}, which
 * a dedicated server ignores; it is copied to {@code playerdata/<offline uuid>.dat} before start and back after
 * the server has saved, together with the world's display name, which the server overwrites with its level name.
 *
 * <p>Paper is downloaded once from PaperMC and checked against its SHA-256. Paperclip patches Mojang's server
 * jar on the first run; later starts run the patched jar directly with an AppCDS archive, which measured about
 * 25% faster to "Done" than Paperclip on Java 25. The Mojang EULA is accepted on the player's behalf.
 */
public final class IntegratedServer implements AutoCloseable {
    static final String PAPER_JAR = "paper-1.8.8-445.jar";
    static final String PAPER_SHA256 = "7ff6d2cec671ef0d95b3723b5c92890118fb882d73b7f8fa0a2cd31d97c55f86";
    static final URI PAPER_URL = URI.create("https://fill-data.papermc.io/v1/objects/" + PAPER_SHA256 + "/" + PAPER_JAR);
    static final String PATCHED_JAR = "cache/patched_1.8.8.jar";

    private static final Duration START_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern SPAWN_PROGRESS = Pattern.compile("Preparing spawn area: (\\d+)%");
    private static final int LOG_LINES = 40;

    /** Where a world comes from and what the menus call it. */
    public record World(Path folder, String name) {
        public World {
            Objects.requireNonNull(folder, "folder");
            Objects.requireNonNull(name, "name");
        }

        String id() {
            return folder.getFileName().toString();
        }
    }

    private final Path home;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Running current;

    /** @param home the server's own directory, for example {@code ~/.alkaline/legacy/server} */
    public IntegratedServer(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath();
    }

    /**
     * Starts the world (stopping and saving any world still running first) and returns the port it listens on
     * at 127.0.0.1. Blocks until the server is ready; call it off the render and UI threads.
     *
     * @param viewDistance the client's render distance, which the server uses as its view distance
     * @param status short progress lines for the joining card
     * @throws java.util.concurrent.CancellationException when {@link #stopAsync} was called meanwhile
     */
    public int start(World world, String player, int viewDistance, Consumer<String> status)
            throws IOException, InterruptedException {
        int attempt = generation.incrementAndGet();
        Running running;
        lifecycle.lock();
        try {
            stop(current);
            Path jar = prepareJar(status, attempt);
            checkCurrent(attempt);
            status.accept("Opening the world");
            Launch launch = Launch.prepare(home, world, player, viewDistance);
            running = new Running(launch, launch(jar, launch));
            current = running;
        } finally {
            lifecycle.unlock();
        }
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (!running.ready.await(100, TimeUnit.MILLISECONDS)) {
            if (!running.process.isAlive()) {
                stopAsync();
                throw new IOException("The world's server stopped while starting:\n" + running.tail());
            }
            if (attempt != generation.get()) {
                stopAsync();
                throw new java.util.concurrent.CancellationException();
            }
            if (System.nanoTime() > deadline) {
                stopAsync();
                throw new IOException("The world's server did not start within " + START_TIMEOUT.toMinutes() + " minutes.");
            }
            String progress = running.progress;
            if (progress != null) {
                status.accept(progress);
            }
        }
        return running.launch.port;
    }

    /** Saves and stops the running world, if any, off the calling thread; cancels a start in progress. */
    public CompletableFuture<Void> stopAsync() {
        generation.incrementAndGet();
        Running target = current;
        if (target == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            lifecycle.lock();
            try {
                stop(target);
            } finally {
                lifecycle.unlock();
            }
        }, command -> Thread.ofVirtual().name("alkaline-server-stop").start(command));
    }

    /** Whether a world's server is running. */
    public boolean running() {
        Running running = current;
        return running != null && running.process.isAlive();
    }

    /** Saves and stops the running world, waiting for it. */
    @Override
    public void close() {
        generation.incrementAndGet();
        lifecycle.lock();
        try {
            stop(current);
        } finally {
            lifecycle.unlock();
        }
    }

    private void checkCurrent(int attempt) {
        if (attempt != generation.get()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    // ---- the server jar --------------------------------------------------------------------------------------

    private Path prepareJar(Consumer<String> status, int attempt) throws IOException, InterruptedException {
        Path patched = home.resolve(PATCHED_JAR);
        if (Files.isRegularFile(patched)) {
            return patched;
        }
        Files.createDirectories(home);
        Path paper = home.resolve(PAPER_JAR);
        if (!Files.isRegularFile(paper) || !PAPER_SHA256.equals(sha256(paper))) {
            status.accept("Downloading Paper 1.8.8");
            download(PAPER_URL, PAPER_SHA256, paper);
        }
        checkCurrent(attempt);
        // Paperclip downloads Mojang's server jar, patches it into cache/, then runs it; -v makes the server
        // print its version and exit.
        status.accept("Preparing the server");
        writeEula(home);
        Process paperclip = new ProcessBuilder(java(), "-jar", paper.toString(), "-v")
                .directory(home.toFile()).redirectErrorStream(true).start();
        ArrayDeque<String> log = new ArrayDeque<>();
        try (BufferedReader output = paperclip.inputReader(StandardCharsets.UTF_8)) {
            for (String line; (line = output.readLine()) != null; ) {
                if (log.size() == LOG_LINES) {
                    log.removeFirst();
                }
                log.addLast(line);
            }
        }
        if (!paperclip.waitFor(5, TimeUnit.MINUTES) || !Files.isRegularFile(patched)) {
            paperclip.destroyForcibly();
            throw new IOException("Paperclip could not patch the server:\n" + String.join("\n", log));
        }
        return patched;
    }

    static void download(URI url, String sha256, Path target) throws IOException, InterruptedException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() != 200) {
                throw new IOException("Downloading " + url + " failed with HTTP " + response.statusCode());
            }
        }
        String actual = sha256(partial);
        if (!sha256.equals(actual)) {
            Files.deleteIfExists(partial);
            throw new IOException("The download of " + url + " has SHA-256 " + actual + ", expected " + sha256);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = input.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- the process -----------------------------------------------------------------------------------------

    private Process launch(Path jar, Launch launch) throws IOException {
        List<String> command = List.of(java(),
                "-Xms256M", "-Xmx2G", "-XX:+UseCompactObjectHeaders",
                "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=" + home.resolve("cache/server.jsa"),
                "-Dfile.encoding=UTF-8",
                "-jar", jar.toString(), "--nojline",
                "--world-container", launch.container.toString(), "--level-name", launch.levelName,
                "--host", "127.0.0.1", "--port", Integer.toString(launch.port));
        return new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true).start();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void stop(Running running) {
        if (running == null) {
            return;
        }
        Process process = running.process;
        try {
            if (process.isAlive()) {
                try {
                    Writer console = running.console;
                    console.write("stop\n");
                    console.flush();
                    console.close();
                } catch (IOException closed) {
                    // The server already closed its console; waiting below covers it.
                }
                if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    System.err.println("The world's server did not stop in time; terminating it.");
                    process.destroy();
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroy();
            Thread.currentThread().interrupt();
        } finally {
            running.removeHook();
            try {
                running.launch.finish();
            } catch (IOException | RuntimeException failure) {
                System.err.println("Writing the world's level.dat back failed: " + failure.getMessage());
            }
        }
    }

    /** A started server: its console, a log tail for errors, and the ready signal. */
    private final class Running {
        final Launch launch;
        final Process process;
        final Writer console;
        final CountDownLatch ready = new CountDownLatch(1);
        final ArrayDeque<String> log = new ArrayDeque<>();
        final Thread hook;
        volatile String progress;

        Running(Launch launch, Process process) {
            this.launch = launch;
            this.process = process;
            this.console = process.outputWriter(StandardCharsets.UTF_8);
            // A client that exits without close() still lets the server save: destroy() is SIGTERM on Unix,
            // which runs Bukkit's own shutdown hook.
            this.hook = new Thread(process::destroy, "alkaline-server-exit");
            Runtime.getRuntime().addShutdownHook(hook);
            Thread.ofVirtual().name("alkaline-server-log").start(this::pump);
        }

        private void pump() {
            try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
                for (String line; (line = output.readLine()) != null; ) {
                    synchronized (log) {
                        if (log.size() == LOG_LINES) {
                            log.removeFirst();
                        }
                        log.addLast(line);
                    }
                    Matcher spawn = SPAWN_PROGRESS.matcher(line);
                    if (spawn.find()) {
                        progress = "Preparing spawn area " + spawn.group(1) + "%";
                    } else if (line.contains("Done (")) {
                        ready.countDown();
                    }
                }
            } catch (IOException closed) {
                // The process ended.
            }
        }

        String tail() {
            synchronized (log) {
                return String.join("\n", log);
            }
        }

        void removeHook() {
            if (current == this) {
                current = null;
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The hook is running or about to.
            }
        }
    }

    // ---- the world -------------------------------------------------------------------------------------------

    static void writeEula(Path home) throws IOException {
        Files.writeString(home.resolve("eula.txt"),
                "# Accepted by Alkaline for its local singleplayer server (https://aka.ms/MinecraftEULA)\neula=true\n");
    }

    /** One start's files: the link container, the server settings and the singleplayer player. */
    static final class Launch {
        final World world;
        final String levelName;
        final Path container;
        final int port;
        final UUID player;
        /** The display name to put back after the server renamed the world; null for a world it creates. */
        final String restoreName;

        private Launch(World world, Path container, int port, UUID player, String restoreName) {
            this.world = world;
            this.levelName = world.id();
            this.container = container;
            this.port = port;
            this.player = player;
            this.restoreName = restoreName;
        }

        static Launch prepare(Path home, World world, String player, int viewDistance) throws IOException {
            Files.createDirectories(world.folder());
            NBTTagCompound level = readLevel(world.folder());
            NBTTagCompound data = level == null ? new NBTTagCompound() : level.getCompoundTag("Data");
            UUID uuid = offlineUuid(player);

            if (data.hasKey("Player", 10)) {
                Path playerData = world.folder().resolve("playerdata");
                Files.createDirectories(playerData);
                writeNbt(data.getCompoundTag("Player"), playerData.resolve(uuid + ".dat"));
            }

            Path container = home.resolve("worlds").resolve(Integer.toHexString(
                    world.folder().toAbsolutePath().normalize().toString().hashCode()));
            link(container.resolve(world.id()), world.folder());
            linkDimension(container, world, "_nether", "DIM-1");
            linkDimension(container, world, "_the_end", "DIM1");

            int port;
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = probe.getLocalPort();
            }
            writeEula(home);
            writeSettings(home, world, data, level != null, viewDistance, port);
            // Cheats make the player an operator; a new world starts without them, as vanilla's does.
            boolean cheats = data.getBoolean("allowCommands");
            Files.writeString(home.resolve("ops.json"), cheats
                    ? "[{\"uuid\":\"" + uuid + "\",\"name\":\"" + jsonEscape(player) + "\",\"level\":4,\"bypassesPlayerLimit\":false}]\n"
                    : "[]\n");
            String restore = level != null && !world.name().equals(world.id()) ? world.name() : null;
            return new Launch(world, container, port, uuid, restore);
        }

        /** Puts the player and the display name back into level.dat once the server has saved and exited. */
        void finish() throws IOException {
            NBTTagCompound level = readLevel(world.folder());
            if (level == null) {
                return;
            }
            NBTTagCompound data = level.getCompoundTag("Data");
            if (restoreName != null) {
                data.setString("LevelName", restoreName);
            }
            Path playerFile = world.folder().resolve("playerdata").resolve(player + ".dat");
            if (Files.isRegularFile(playerFile)) {
                try (InputStream input = Files.newInputStream(playerFile)) {
                    data.setTag("Player", CompressedStreamTools.readCompressed(input));
                }
            }
            level.setTag("Data", data);
            writeNbt(level, world.folder().resolve("level.dat"));
        }

        private static void writeSettings(Path home, World world, NBTTagCompound data, boolean exists,
                                          int viewDistance, int port) throws IOException {
            Properties server = new Properties();
            server.setProperty("online-mode", "false");
            server.setProperty("server-ip", "127.0.0.1");
            server.setProperty("server-port", Integer.toString(port));
            server.setProperty("level-name", world.id());
            server.setProperty("motd", world.name());
            // Netty's native transport cannot read buffer addresses on Java 25 in this server's Netty version.
            server.setProperty("use-native-transport", "false");
            // Loopback: compressing every packet only costs both processes CPU.
            server.setProperty("network-compression-threshold", "-1");
            server.setProperty("view-distance", Integer.toString(Math.clamp(viewDistance, 3, 15)));
            // The dedicated server forces these on the world, so they come from level.dat.
            server.setProperty("gamemode", Integer.toString(exists ? data.getInteger("GameType") : 0));
            server.setProperty("difficulty", Integer.toString(exists && data.hasKey("Difficulty") ? data.getByte("Difficulty") : 2));
            server.setProperty("hardcore", Boolean.toString(exists && data.getBoolean("hardcore")));
            server.setProperty("spawn-protection", "0");
            server.setProperty("allow-flight", "true");
            server.setProperty("enable-command-block", "true");
            server.setProperty("snooper-enabled", "false");
            server.setProperty("max-players", "8");
            try (OutputStream output = Files.newOutputStream(home.resolve("server.properties"))) {
                server.store(output, "Written by Alkaline for each singleplayer start");
            }
            // Bukkit merges its defaults into these files, so only the differences are written.
            Files.writeString(home.resolve("bukkit.yml"), """
                    settings:
                      connection-throttle: -1
                      query-plugins: false
                    """);
            Files.writeString(home.resolve("spigot.yml"), """
                    settings:
                      bungeecord: false
                    timings:
                      enabled: false
                    world-settings:
                      default:
                        view-distance: %d
                        anti-xray:
                          enabled: false
                    """.formatted(Math.clamp(viewDistance, 3, 15)));
            // Vanilla keeps only the Overworld's spawn chunks loaded; preloading the Nether's and the End's
            // spawn areas cost about a second of every start.
            Files.writeString(home.resolve("paper.yml"), """
                    world-settings:
                      default:
                        keep-spawn-loaded: false
                      %s:
                        keep-spawn-loaded: true
                    """.formatted(yamlQuote(world.id())));
        }

        private static void linkDimension(Path container, World world, String suffix, String dimension) throws IOException {
            Path target = world.folder().resolve(dimension);
            Files.createDirectories(target);
            Path folder = container.resolve(world.id() + suffix);
            Files.createDirectories(folder);
            link(folder.resolve(dimension), target);
        }
    }

    /** Creates or refreshes a directory link; never deletes anything but a link. */
    static void link(Path link, Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(absolute)) {
                return;
            }
            Files.delete(link);
        } else if (Files.exists(link)) {
            if (Files.isSameFile(link, absolute)) {
                return;
            }
            throw new IOException(link + " exists and is not a link to " + absolute);
        }
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, absolute);
        } catch (IOException | UnsupportedOperationException denied) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                throw denied;
            }
            // Symbolic links need Developer Mode on Windows; directory junctions do not.
            try {
                Process junction = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), absolute.toString())
                        .redirectErrorStream(true).start();
                if (junction.waitFor() != 0 || !Files.isDirectory(link)) {
                    throw new IOException("Cannot link " + link + " to " + absolute + ": "
                            + new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while linking " + link);
            }
        }
    }

    /** The UUID an offline-mode server gives a player name. */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static NBTTagCompound readLevel(Path folder) throws IOException {
        Path file = folder.resolve("level.dat");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    /** Writes compressed NBT the way vanilla's save handler does: a new file, the old one kept as _old. */
    private static void writeNbt(NBTTagCompound tag, Path file) throws IOException {
        Path fresh = file.resolveSibling(file.getFileName() + "_new");
        try (OutputStream output = Files.newOutputStream(fresh)) {
            CompressedStreamTools.writeCompressed(tag, output);
        }
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + "_old"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String yamlQuote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
