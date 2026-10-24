package alkaline.renderer.texture;

import alkaline.renderer.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * OptiFine's emissive textures (EmissiveTextures): {@code optifine/emissive.properties} names a suffix
 * ({@code suffix.emissive=_e}), and a texture with a {@code <name><suffix>.png} beside it draws that image over itself
 * again at full block light, for parts that glow (eyes, lamps, ores).
 */
public final class EmissiveTextures {
    private static final String PROPERTIES = "assets/minecraft/optifine/emissive.properties";

    private EmissiveTextures() {
    }

    /** The active packs' emissive suffix, or null when they name none. */
    public static String suffix(AssetSource assets) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(PROPERTIES)) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
            String suffix = properties.getProperty("suffix.emissive");
            return suffix == null || suffix.isBlank() ? null : suffix.trim();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The emissive image of a texture asset ({@code assets/minecraft/textures/entity/zombie/zombie.png}) when the
     * packs have one, or null. A texture that is itself emissive has none.
     */
    public static String emissivePath(AssetSource assets, String path, String suffix) {
        if (suffix == null || !path.endsWith(".png") || path.endsWith(suffix + ".png")) {
            return null;
        }
        String emissive = path.substring(0, path.length() - ".png".length()) + suffix + ".png";
        try (InputStream input = assets.open(emissive)) {
            return input == null ? null : emissive;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
