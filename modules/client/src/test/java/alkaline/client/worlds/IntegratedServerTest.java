package alkaline.client.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegratedServerTest {
    @TempDir
    Path temp;

    private static NBTTagCompound read(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static void write(NBTTagCompound tag, Path file) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            CompressedStreamTools.writeCompressed(tag, output);
        }
    }

    @Test
    void aVanillaSaveRoundTripsThroughTheDedicatedServer() throws IOException {
        Path save = temp.resolve("saves").resolve("Old Save");
        Files.createDirectories(save);
        NBTTagCompound player = new NBTTagCompound();
        player.setFloat("Health", 17f);
        NBTTagCompound data = new NBTTagCompound();
        data.setString("LevelName", "My World");
        data.setInteger("GameType", 1);
        data.setByte("Difficulty", (byte) 3);
        data.setBoolean("allowCommands", true);
        data.setTag("Player", player);
        NBTTagCompound level = new NBTTagCompound();
        level.setTag("Data", data);
        write(level, save.resolve("level.dat"));

        Path home = temp.resolve("server");
        IntegratedServer.Launch launch = IntegratedServer.Launch.prepare(home,
                new IntegratedServer.World(save, "My World"), "Steve", 8);

        UUID uuid = IntegratedServer.offlineUuid("Steve");
        assertEquals(17f, read(save.resolve("playerdata").resolve(uuid + ".dat")).getFloat("Health"));
        assertTrue(Files.isSameFile(launch.container.resolve("Old Save"), save));
        assertTrue(Files.isSameFile(launch.container.resolve("Old Save_nether").resolve("DIM-1"), save.resolve("DIM-1")));
        assertTrue(Files.isSameFile(launch.container.resolve("Old Save_the_end").resolve("DIM1"), save.resolve("DIM1")));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(home.resolve("server.properties"))) {
            properties.load(input);
        }
        assertEquals("1", properties.getProperty("gamemode"));
        assertEquals("3", properties.getProperty("difficulty"));
        assertEquals("false", properties.getProperty("online-mode"));
        assertEquals("127.0.0.1", properties.getProperty("server-ip"));
        assertTrue(Files.readString(home.resolve("ops.json")).contains(uuid.toString()));
        assertTrue(Files.readString(home.resolve("eula.txt")).contains("eula=true"));

        // The server renames the world to its level name and saves the player under its UUID.
        NBTTagCompound saved = read(save.resolve("level.dat"));
        saved.getCompoundTag("Data").setString("LevelName", "Old Save");
        write(saved, save.resolve("level.dat"));
        NBTTagCompound played = new NBTTagCompound();
        played.setFloat("Health", 9f);
        write(played, save.resolve("playerdata").resolve(uuid + ".dat"));

        launch.finish();
        NBTTagCompound restored = read(save.resolve("level.dat")).getCompoundTag("Data");
        assertEquals("My World", restored.getString("LevelName"));
        assertEquals(9f, restored.getCompoundTag("Player").getFloat("Health"));
        // Preparing again reuses the links.
        IntegratedServer.Launch.prepare(home, new IntegratedServer.World(save, "My World"), "Steve", 8);
    }

    @Test
    void newWorldFoldersFollowVanillasNaming() throws IOException {
        Path saves = temp.resolve("saves");
        WorldLibrary library = new WorldLibrary(saves);
        assertEquals(saves.resolve("New World"), library.newWorldFolder("New World"));
        Files.createDirectories(saves.resolve("New World"));
        assertEquals(saves.resolve("New World-"), library.newWorldFolder("New World"));
        assertEquals(saves.resolve("a_b_"), library.newWorldFolder("a/b?"));
        assertEquals(saves.resolve("_CON_"), library.newWorldFolder("CON"));
    }
}
