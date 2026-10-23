package alkaline.client.worlds;

import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/**
 * Lists the player's singleplayer worlds from a vanilla {@code saves} folder, newest first. Scans run on a
 * background thread (disk access never touches the render or UI thread); unreadable worlds are skipped with a
 * message rather than failing the list.
 */
public final class WorldLibrary {
    private static final int MAX_WORLDS = 512;

    private final Path saves;

    public WorldLibrary(Path saves) {
        this.saves = Objects.requireNonNull(saves, "saves");
    }

    public Path savesFolder() {
        return saves;
    }

    /** Reads every world asynchronously; completes with an empty list when the folder does not exist. */
    public CompletableFuture<List<SavedWorld>> scan() {
        return CompletableFuture.supplyAsync(this::scanNow,
                command -> Thread.ofVirtual().name("alkaline-world-scan").start(command));
    }

    List<SavedWorld> scanNow() {
        List<SavedWorld> worlds = new ArrayList<>();
        if (!Files.isDirectory(saves)) {
            return worlds;
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(saves, Files::isDirectory)) {
            for (Path folder : folders) {
                if (worlds.size() >= MAX_WORLDS) {
                    break;
                }
                Path levelFile = folder.resolve("level.dat");
                if (!Files.isRegularFile(levelFile)) {
                    continue;
                }
                try {
                    worlds.add(read(folder, levelFile));
                } catch (IOException | RuntimeException unreadable) {
                    System.err.println("Skipping world " + folder.getFileName() + ": " + unreadable.getMessage());
                }
            }
        } catch (IOException failure) {
            System.err.println("Cannot list " + saves + ": " + failure.getMessage());
        }
        worlds.sort(Comparator.comparingLong(SavedWorld::lastPlayedMillis).reversed());
        return worlds;
    }

    static SavedWorld read(Path folder, Path levelFile) throws IOException {
        NBTTagCompound root;
        try (InputStream input = Files.newInputStream(levelFile)) {
            root = CompressedStreamTools.readCompressed(input);
        }
        NBTTagCompound data = root.getCompoundTag("Data");
        String name = data.getString("LevelName");
        if (name.isBlank()) {
            name = folder.getFileName().toString();
        }
        return new SavedWorld(folder, name, data.getLong("LastPlayed"),
                SavedWorld.GameMode.fromId(data.getInteger("GameType")), data.getBoolean("hardcore"),
                data.getBoolean("allowCommands"), readIcon(folder.resolve("icon.png")));
    }

    private static int[] readIcon(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(file)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() != SavedWorld.ICON_SIZE || image.getHeight() != SavedWorld.ICON_SIZE) {
                return null;
            }
            return image.getRGB(0, 0, SavedWorld.ICON_SIZE, SavedWorld.ICON_SIZE, null, 0, SavedWorld.ICON_SIZE);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
