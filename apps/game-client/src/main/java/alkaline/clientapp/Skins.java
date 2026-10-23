package alkaline.clientapp;

import alkaline.config.Json;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.model.SkinImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Player skins from the profile "textures" property the server sends in the player list: the skin URL (on
 * Mojang's texture server only) and its model, downloaded off the render thread, cached on disk by texture hash,
 * and normalized to the 64x64 layout. Players without a skin, or before theirs arrives, wear vanilla's default for
 * their UUID (Alex when the UUID hash is odd, else Steve).
 */
final class Skins implements AutoCloseable {
    /** A decoded skin and whether it uses the slim (3-pixel arm) model. */
    record Skin(RgbaImage image, boolean slim) {
    }

    private static final Pattern TEXTURE_HOST = Pattern.compile("https?://textures\\.minecraft\\.net/texture/([0-9a-f]{16,128})");
    private static final int MAX_BYTES = 1 << 20;

    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /** By texture hash: the decoded skin once ready; absent while loading or after a failure. */
    private final Map<String, Skin> ready = new ConcurrentHashMap<>();
    private final Map<String, Boolean> requested = new ConcurrentHashMap<>();

    Skins(Path cache) {
        this.cache = cache;
    }

    /** Vanilla's default model for a UUID without a skin: DefaultPlayerSkin.isSlimSkin. */
    static boolean defaultSlim(UUID uuid) {
        return uuid != null && (uuid.hashCode() & 1) == 1;
    }

    /** The texture hash and model of a textures property, or null when it names no usable skin. */
    static Reference reference(String texturesProperty) {
        if (texturesProperty == null) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(texturesProperty), StandardCharsets.UTF_8);
            if (!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("textures") instanceof Map<?, ?> textures)
                    || !(textures.get("SKIN") instanceof Map<?, ?> skin) || !(skin.get("url") instanceof String url)) {
                return null;
            }
            var matcher = TEXTURE_HOST.matcher(url);
            if (!matcher.matches()) {
                return null;
            }
            boolean slim = skin.get("metadata") instanceof Map<?, ?> metadata && "slim".equals(metadata.get("model"));
            return new Reference(matcher.group(1), slim);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    record Reference(String hash, boolean slim) {
    }

    /** The skin for a reference if downloaded; starts the download (once) otherwise and returns null. */
    Skin skin(Reference reference) {
        Skin skin = ready.get(reference.hash());
        if (skin != null) {
            return skin;
        }
        if (requested.putIfAbsent(reference.hash(), Boolean.TRUE) == null) {
            Thread.ofVirtual().name("alkaline-skin").start(() -> fetch(reference));
        }
        return null;
    }

    private void fetch(Reference reference) {
        try {
            Path file = cache.resolve(reference.hash() + ".png");
            byte[] bytes;
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
                bytes = Files.readAllBytes(file);
            } else {
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("https://textures.minecraft.net/texture/" + reference.hash()))
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body().length > MAX_BYTES) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                bytes = response.body();
                Files.createDirectories(cache);
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temporary, bytes);
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            RgbaImage image = SkinImages.normalize(ImageDecoder.decode(new ByteArrayInputStream(bytes)));
            ready.put(reference.hash(), new Skin(image, reference.slim()));
        } catch (IOException | RuntimeException failure) {
            System.err.println("Skin " + reference.hash() + " unavailable: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
