package alkaline.clientapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The packs servers send (ResourcePackRepository.downloadResourcePack): each is stored under its SHA-1 when the
 * server gave one ({@code legacy} otherwise), a stored file whose hash matches is used without downloading, and
 * only the newest {@value #KEPT} files are kept. Downloads are capped at {@value #MAX_BYTES} bytes, as vanilla's.
 * Unlike 1.8.9, only http and https addresses are fetched: vanilla's URL connection would also open file: and
 * jar: addresses a server names.
 */
final class ServerPacks {
    static final long MAX_BYTES = 52_428_800L;
    static final int KEPT = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Bytes received so far and the total the server announced (-1 when it did not). */
    @FunctionalInterface
    interface Progress {
        void update(long received, long total);
    }

    private final Path folder;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            // NORMAL follows redirects but never from https to http, and never to other schemes.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    ServerPacks(Path folder) {
        this.folder = folder;
    }

    /** Whether the client fetches {@code url} at all. */
    static boolean downloadable(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (java.net.URISyntaxException invalid) {
            return false;
        }
    }

    /** The stored file's name: the hash when it is one, as vanilla checks it, else {@code legacy}. */
    static String fileName(String hash) {
        return hash != null && hash.matches("^[a-f0-9]{40}$") ? hash : "legacy";
    }

    /** The stored pack for {@code hash} when its contents still match; a mismatching file is deleted. */
    Path cached(String hash) {
        if (hash == null || hash.length() != 40) {
            return null;
        }
        Path file = folder.resolve(fileName(hash));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (sha1(file).equals(hash)) {
                return file;
            }
            System.err.println("[resource pack] " + file + " had the wrong hash; deleting it");
        } catch (IOException unreadable) {
            System.err.println("[resource pack] " + file + " could not be hashed; deleting it");
        }
        deleteQuietly(file);
        return null;
    }

    /**
     * Downloads the pack, blocking. Sends vanilla's session headers (Minecraft.getSessionInfo). Stops early when
     * {@code cancelled} turns true (the player left the server).
     */
    Path download(String url, String hash, String username, String uuid, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        if (!downloadable(url)) {
            throw new IOException("Unsupported address: " + url);
        }
        Files.createDirectories(folder);
        prune();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("X-Minecraft-Username", username)
                .header("X-Minecraft-UUID", uuid)
                .header("X-Minecraft-Version", "1.8.9")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
        Path file = folder.resolve(fileName(hash));
        Path partial = Files.createTempFile(folder, "download", ".part");
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode());
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (total > MAX_BYTES) {
                throw new IOException("Pack too large: " + total + " bytes");
            }
            copy(body, partial, total, progress, cancelled);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(partial);
        }
        if (hash != null && hash.length() == 40 && !sha1(file).equals(hash)) {
            // Vanilla loads it anyway and deletes it on the next offer; servers with stale hashes keep working.
            System.err.println("[resource pack] downloaded pack does not match the server's hash " + hash);
        }
        return file;
    }

    /** Copies at most {@link #MAX_BYTES}, reporting progress. */
    static void copy(InputStream in, Path target, long total, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long received = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int read; (read = in.read(buffer)) >= 0; ) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("Cancelled");
                }
                received += read;
                if (received > MAX_BYTES) {
                    throw new IOException("Pack larger than " + MAX_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
                progress.update(received, total);
            }
        }
    }

    /** Keeps the newest {@value #KEPT} stored packs (ResourcePackRepository.deleteOldServerResourcesPacks). */
    void prune() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listed = Files.list(folder)) {
            listed.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(ServerPacks::modified).reversed());
        for (int index = KEPT; index < files.size(); index++) {
            deleteQuietly(files.get(index));
        }
    }

    static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A later prune or download retries.
        }
    }
}
