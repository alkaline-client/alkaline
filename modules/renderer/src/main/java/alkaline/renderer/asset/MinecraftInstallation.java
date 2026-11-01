package alkaline.renderer.asset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Finds the player's own vanilla 1.8.9 client jar in the standard launcher locations. */
public final class MinecraftInstallation {
    public static final String VERSION = "1.8.9";

    private MinecraftInstallation() {
    }

    /**
     * Honors {@code ALKALINE_MINECRAFT_JAR}, then the {@code alkaline.minecraftJar} property (which the version JSON
     * for Minecraft's own launcher sets), then the launcher directory for the current OS.
     */
    public static Optional<Path> locateClientJar() {
        String override = System.getenv("ALKALINE_MINECRAFT_JAR");
        if (override == null || override.isBlank()) {
            override = System.getProperty("alkaline.minecraftJar");
        }
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
        }
        Path jar = gameDirectory().resolve("versions").resolve(VERSION).resolve(VERSION + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    public static Path gameDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, ".minecraft");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }
}
