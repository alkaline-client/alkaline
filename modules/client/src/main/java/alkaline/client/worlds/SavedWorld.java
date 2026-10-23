package alkaline.client.worlds;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One singleplayer world folder as {@code level.dat} describes it. Read-only metadata for the menus; the world
 * itself is loaded by the game adapter.
 *
 * @param icon the world's 64x64 {@code icon.png} as straight-alpha ARGB pixels, or null when it has none
 */
public record SavedWorld(Path folder, String name, long lastPlayedMillis, GameMode mode, boolean hardcore,
                         boolean cheats, int[] icon) {
    /** Vanilla game types, by their {@code GameType} id. */
    public enum GameMode {
        SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR;

        static GameMode fromId(int id) {
            return id >= 0 && id < values().length ? values()[id] : SURVIVAL;
        }
    }

    public static final int ICON_SIZE = 64;

    public SavedWorld {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        if (icon != null && icon.length != ICON_SIZE * ICON_SIZE) {
            throw new IllegalArgumentException("World icons are 64x64.");
        }
    }

    /** Folder name, which vanilla uses as the world's id. */
    public String id() {
        return folder.getFileName().toString();
    }
}
