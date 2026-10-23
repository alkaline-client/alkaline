package alkaline.client.servers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.data.nbt.CompressedStreamTools;
import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerTest {
    @Test
    void parsesAddressesWithAndWithoutPorts() {
        assertEquals(new ServerAddress("play.example.net", 25565, false), ServerAddress.parse(" play.example.net "));
        assertEquals(new ServerAddress("127.0.0.1", 25570, true), ServerAddress.parse("127.0.0.1:25570"));
        assertEquals(new ServerAddress("::1", 25566, true), ServerAddress.parse("[::1]:25566"));
        assertEquals(new ServerAddress("::1", 25565, false), ServerAddress.parse("::1"));
        assertFalse(ServerAddress.isValid("host:notaport"));
        assertFalse(ServerAddress.isValid("host:70000"));
        assertFalse(ServerAddress.isValid(""));
    }

    @Test
    void parsesStatusWithChatComponentMotdAndFavicon() {
        String json = """
                {"version":{"name":"1.8.9","protocol":47},
                 "players":{"max":100,"online":7,"sample":[{"name":"Notch","id":"x"}]},
                 "description":{"text":"Hello ","color":"gold","extra":[{"text":"world","bold":true}]},
                 "favicon":"data:image/png;base64,iVBORw0KGgo="}
                """;
        ServerStatus status = ServerPinger.parseStatus(json, 42);
        assertTrue(status.online());
        assertTrue(status.compatible());
        assertEquals(7, status.playersOnline());
        assertEquals(100, status.playersMax());
        assertEquals(List.of("Notch"), status.sample());
        assertEquals("§r§6Hello §r§6§lworld", status.motd());
        assertNotNull(status.favicon());
        assertEquals((byte) 0x89, status.favicon()[0]);
    }

    @Test
    void plainStringMotdAndOtherProtocols() {
        ServerStatus status = ServerPinger.parseStatus(
                "{\"version\":{\"name\":\"1.20.4\",\"protocol\":765},\"description\":\"§aA §bB\"}", -1);
        assertEquals("§aA §bB", status.motd());
        assertFalse(status.compatible());
    }

    @Test
    void varIntsRoundTrip() throws IOException {
        for (int value : new int[]{0, 1, 127, 128, 25565, Integer.MAX_VALUE, -1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ServerPinger.writeVarInt(new DataOutputStream(bytes), value);
            assertEquals(value, ServerPinger.readVarInt(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }

    @Test
    void importsVanillaServersOnceThenPersistsItsOwnList(@TempDir Path directory) throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        NBTTagCompound server = new NBTTagCompound();
        server.setString("name", "Practice");
        server.setString("ip", "practice.example.net");
        list.appendTag(server);
        root.setTag("servers", list);
        Path vanilla = directory.resolve("servers.dat");
        CompressedStreamTools.write(root, vanilla.toFile());

        Path file = directory.resolve("alkaline").resolve("servers.json");
        try (ServerList servers = new ServerList(file, vanilla)) {
            assertEquals(List.of(new ServerEntry("Practice", "practice.example.net")), servers.servers());
            servers.add(new ServerEntry("", "lobby.example.net"));
            servers.move(new ServerEntry("", "lobby.example.net"), 1);
        }
        Files.delete(vanilla);
        try (ServerList reloaded = new ServerList(file, vanilla)) {
            assertArrayEquals(new ServerEntry[]{
                    new ServerEntry("Practice", "practice.example.net"),
                    new ServerEntry("lobby.example.net", "lobby.example.net")}, reloaded.servers().toArray());
        }
    }
}
