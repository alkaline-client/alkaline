package alkaline.client.account;

import alkaline.config.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * The signed-in Microsoft account: sign-in, sign-out, and restoring the previous session at startup from the
 * stored refresh token. State changes are published to listeners from background threads.
 *
 * <p>Only the player's name, UUID and the Microsoft refresh token are stored, in an owner-readable file
 * ({@code rw-------} on POSIX systems). Minecraft access tokens stay in memory.
 */
public final class AccountManager implements AutoCloseable {
    private static final int FORMAT = 1;
    /** Face pixels: the 8x8 head front with the hat layer composited over it. */
    public static final int FACE_SIZE = 8;

    /** What the account chip shows. */
    public sealed interface State {
        /** Not signed in; {@code notice} explains why when a previous session could not be restored. */
        record SignedOut(String notice) implements State { }

        /** Waiting on the browser or the services; {@code url} is the page the player should complete. */
        record SigningIn(String url, String step) implements State { }

        /** {@code face} is 8x8 ARGB, or null until the skin arrives. */
        record SignedIn(MinecraftProfile profile, int[] face) implements State { }
    }

    @FunctionalInterface
    public interface Listener {
        void changed(State state);
    }

    private final Path file;
    private final MicrosoftAuth auth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = new State.SignedOut(null);
    private volatile MinecraftSession session;
    private volatile MicrosoftAuth.Authorization pending;
    private volatile Thread worker;

    public AccountManager(Path file, OAuthClient client) {
        this.file = Objects.requireNonNull(file, "file");
        this.auth = new MicrosoftAuth(client);
    }

    public State state() {
        return state;
    }

    /** The session a game connection would use, or null when signed out. */
    public MinecraftSession session() {
        return session;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Restores the saved account in the background, if there is one. */
    public synchronized void restore() {
        Stored stored = read();
        if (stored == null || worker != null) {
            return;
        }
        publish(new State.SigningIn(null, "Signing in as " + stored.name()));
        worker = Thread.ofVirtual().name("alkaline-account").start(() -> {
            try {
                complete(auth.refresh(stored.refreshToken()));
            } catch (AuthException failure) {
                session = null;
                publish(new State.SignedOut(failure.getMessage()));
            } finally {
                worker = null;
            }
        });
    }

    /**
     * Starts an interactive sign-in. {@code openUrl} receives the Microsoft page to open in the browser; it is
     * also kept in the state so the menu can offer it again.
     */
    public synchronized void signIn(Consumer<String> openUrl) {
        if (worker != null) {
            return;
        }
        MicrosoftAuth.Authorization authorization;
        try {
            authorization = auth.authorize();
        } catch (AuthException failure) {
            publish(new State.SignedOut(failure.getMessage()));
            return;
        }
        pending = authorization;
        publish(new State.SigningIn(authorization.url(), "Continue in your browser"));
        openUrl.accept(authorization.url());
        worker = Thread.ofVirtual().name("alkaline-account").start(() -> {
            try {
                MicrosoftAuth.Result result = auth.finish(authorization);
                complete(result);
            } catch (AuthException failure) {
                publish(new State.SignedOut(pending == null ? null : failure.getMessage()));
            } finally {
                pending = null;
                worker = null;
            }
        });
    }

    /** Stops a sign-in that is waiting on the browser. */
    public void cancel() {
        MicrosoftAuth.Authorization authorization = pending;
        pending = null;
        if (authorization != null) {
            authorization.close();
        }
        publish(new State.SignedOut(null));
    }

    public synchronized void signOut() {
        cancel();
        session = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            System.err.println("Removing saved account failed: " + failure.getMessage());
        }
        publish(new State.SignedOut(null));
    }

    private void complete(MicrosoftAuth.Result result) {
        session = result.session();
        MinecraftProfile profile = result.session().profile();
        write(new Stored(profile.uuid(), profile.name(), result.refreshToken()));
        publish(new State.SignedIn(profile, null));
        int[] face = fetchFace(profile.skinUrl());
        if (face != null && state instanceof State.SignedIn signedIn && signedIn.profile().equals(profile)) {
            publish(new State.SignedIn(profile, face));
        }
    }

    /** Downloads the skin and cuts out the face; null when there is no skin or it cannot be read. */
    private int[] fetchFace(String url) {
        if (url == null || !url.startsWith("https://") && !url.startsWith("http://textures.minecraft.net/")) {
            return null;
        }
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            BufferedImage skin;
            try (InputStream input = response.body()) {
                skin = ImageIO.read(input);
            }
            return skin == null ? null : face(skin);
        } catch (IOException failure) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Head front (8..16, 8..16) with the hat overlay (40..48, 8..16) alpha-composited on top. */
    static int[] face(BufferedImage skin) {
        if (skin.getWidth() < 64 || skin.getHeight() < 32) {
            return null;
        }
        int[] face = new int[FACE_SIZE * FACE_SIZE];
        for (int y = 0; y < FACE_SIZE; y++) {
            for (int x = 0; x < FACE_SIZE; x++) {
                int base = skin.getRGB(8 + x, 8 + y) | 0xFF000000;
                int hat = skin.getRGB(40 + x, 8 + y);
                int alpha = hat >>> 24;
                face[y * FACE_SIZE + x] = alpha == 0 ? base : blend(base, hat, alpha);
            }
        }
        return face;
    }

    private static int blend(int base, int over, int alpha) {
        int r = ((over >> 16 & 0xFF) * alpha + (base >> 16 & 0xFF) * (255 - alpha)) / 255;
        int g = ((over >> 8 & 0xFF) * alpha + (base >> 8 & 0xFF) * (255 - alpha)) / 255;
        int b = ((over & 0xFF) * alpha + (base & 0xFF) * (255 - alpha)) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private void publish(State next) {
        state = next;
        for (Listener listener : listeners) {
            listener.changed(next);
        }
    }

    private record Stored(String uuid, String name, String refreshToken) {
        @Override
        public String toString() {
            return "Stored[" + name + "]";
        }
    }

    private Stored read() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("refreshToken") instanceof String token && !token.isEmpty()) {
                return new Stored(root.get("uuid") instanceof String uuid ? uuid : "",
                        root.get("name") instanceof String name ? name : "your account", token);
            }
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Reading saved account failed: " + failure.getMessage());
        }
        return null;
    }

    private void write(Stored stored) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", (long) FORMAT);
        root.put("uuid", stored.uuid());
        root.put("name", stored.name());
        root.put("refreshToken", stored.refreshToken());
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            Path temporary = posix
                    ? Files.createTempFile(parent, "account", ".tmp",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(parent, "account", ".tmp");
            Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            System.err.println("Saving account failed: " + failure.getMessage());
        }
    }

    @Override
    public void close() {
        MicrosoftAuth.Authorization authorization = pending;
        pending = null;
        if (authorization != null) {
            authorization.close();
        }
    }
}
