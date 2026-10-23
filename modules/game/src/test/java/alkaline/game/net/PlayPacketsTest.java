package alkaline.game.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.inventory.ItemStack;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protocol 47 layouts of the play packets {@link ServerSession#decode} turns into events. */
class PlayPacketsTest {
    private static ServerSession.Event decode(int id, PacketBuffer body) throws IOException {
        return ServerSession.decode(id, PacketBuffer.wrap(body.toByteArray()));
    }

    @Test
    void openWindowCarriesTitleSlotsAndTheHorse() throws IOException {
        var chest = (ServerSession.OpenWindow) decode(0x2D, new PacketBuffer().writeByte(3)
                .writeString("minecraft:chest").writeString("{\"text\":\"Kits\"}").writeByte(54));
        assertEquals(new ServerSession.OpenWindow(3, "minecraft:chest", "{\"text\":\"Kits\"}", 54, -1), chest);
        var horse = (ServerSession.OpenWindow) decode(0x2D, new PacketBuffer().writeByte(4)
                .writeString("EntityHorse").writeString("\"Horse\"").writeByte(2).writeInt(77));
        assertEquals(77, horse.entityId());
    }

    @Test
    void windowPropertiesAreSignedShorts() throws IOException {
        assertEquals(new ServerSession.WindowProperty(2, 3, -1),
                decode(0x31, new PacketBuffer().writeByte(2).writeShort(3).writeShort(-1)));
    }

    @Test
    void entityPropertiesListAttributesAndModifiers() throws IOException {
        UUID speed = UUID.fromString("91AEAA56-376B-4498-935B-2F7F68070635");
        var properties = (ServerSession.EntityProperties) decode(0x20, new PacketBuffer().writeVarInt(5).writeInt(1)
                .writeString("generic.movementSpeed").writeDouble(0.1).writeVarInt(1).writeUuid(speed)
                .writeDouble(0.4).writeByte(2));
        assertEquals(5, properties.entityId());
        ServerSession.Attribute attribute = properties.attributes().getFirst();
        assertEquals("generic.movementSpeed", attribute.key());
        assertEquals(new ServerSession.AttributeModifier(speed, 0.4, 2), attribute.modifiers().getFirst());
    }

    @Test
    void tradeListsAreDecodedFromTheirPluginChannel() throws IOException {
        PacketBuffer body = new PacketBuffer().writeString("MC|TrList").writeInt(9).writeByte(1)
                .writeSlot(new ItemStack(388, 2, 0, null)).writeSlot(new ItemStack(297, 3, 0, null))
                .writeBoolean(false).writeBoolean(true).writeInt(4).writeInt(7);
        var trades = (ServerSession.Trades) decode(0x3F, body);
        assertEquals(9, trades.window());
        assertEquals(new ItemStack(297, 3, 0, null), trades.trades().getFirst().result());
        assertTrue(trades.trades().getFirst().disabled());
        var other = (ServerSession.PluginMessage) decode(0x3F, new PacketBuffer().writeString("BungeeCord")
                .writeBytes(new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3}, other.data());
        assertEquals(new ServerSession.ServerBrand("Spigot"),
                decode(0x3F, new PacketBuffer().writeString("MC|Brand").writeString("Spigot")));
    }

    @Test
    void soundsUseEighthBlocksAndPitchSixtyThirds() throws IOException {
        var sound = (ServerSession.Sound) decode(0x29, new PacketBuffer().writeString("random.click").writeInt(80)
                .writeInt(-16).writeInt(4).writeFloat(1f).writeByte(63));
        assertEquals(10.0, sound.x());
        assertEquals(-2.0, sound.y());
        assertEquals(1f, sound.pitch());
    }

    @Test
    void combatWorldBorderAndHeaderFooter() throws IOException {
        var died = (ServerSession.Combat) decode(0x42, new PacketBuffer().writeVarInt(2).writeVarInt(12).writeInt(30)
                .writeString("{\"text\":\"slain\"}"));
        assertEquals(12, died.playerId());
        assertEquals(30, died.entityId());
        var border = (ServerSession.WorldBorder) decode(0x44, new PacketBuffer().writeVarInt(0).writeDouble(500));
        assertEquals(500.0, border.newDiameter());
        assertEquals(new ServerSession.PlayerListHeaderFooter("\"a\"", "\"b\""),
                decode(0x47, new PacketBuffer().writeString("\"a\"").writeString("\"b\"")));
    }

    @Test
    void blockEventsUnpackPositions() throws IOException {
        var action = (ServerSession.BlockAction) decode(0x24, new PacketBuffer().writePosition(-5, 70, 12)
                .writeByte(1).writeByte(2).writeVarInt(54));
        assertEquals(new ServerSession.BlockAction(-5, 70, 12, 1, 2, 54), action);
        var sign = (ServerSession.UpdateSign) decode(0x33, new PacketBuffer().writePosition(1, 2, 3)
                .writeString("\"1\"").writeString("\"2\"").writeString("\"3\"").writeString("\"4\""));
        assertEquals("\"4\"", sign.lines()[3]);
        assertEquals(new ServerSession.OpenSignEditor(1, 2, 3),
                decode(0x36, new PacketBuffer().writePosition(1, 2, 3)));
    }

    @Test
    void mapsWithAndWithoutAColorPatch() throws IOException {
        var empty = (ServerSession.MapData) decode(0x34, new PacketBuffer().writeVarInt(3).writeByte(0).writeVarInt(1)
                .writeByte(0x12).writeByte(5).writeByte(6).writeByte(0));
        assertEquals(1 << 24 | 5 << 16 | 6 << 8 | 2, empty.icons()[0]);
        assertNull(empty.colors());
        var patch = (ServerSession.MapData) decode(0x34, new PacketBuffer().writeVarInt(3).writeByte(0).writeVarInt(0)
                .writeByte(2).writeByte(1).writeByte(4).writeByte(5).writeByteArray(new byte[]{7, 8}));
        assertArrayEquals(new byte[]{7, 8}, patch.colors());
    }

    @Test
    void spawnsOfPaintingsOrbsAndLightning() throws IOException {
        var painting = (ServerSession.SpawnPainting) decode(0x10, new PacketBuffer().writeVarInt(1)
                .writeString("Kebab").writePosition(1, 64, 1).writeByte(2));
        assertEquals("Kebab", painting.title());
        var orb = (ServerSession.SpawnExperienceOrb) decode(0x11, new PacketBuffer().writeVarInt(2).writeInt(32)
                .writeInt(64).writeInt(-32).writeShort(7));
        assertEquals(new ServerSession.SpawnExperienceOrb(2, 1.0, 2.0, -1.0, 7), orb);
        assertInstanceOf(ServerSession.SpawnGlobalEntity.class, decode(0x2C, new PacketBuffer().writeVarInt(3)
                .writeByte(1).writeInt(0).writeInt(0).writeInt(0)));
        assertEquals(new ServerSession.EntityAttach(4, 9, false),
                decode(0x1B, new PacketBuffer().writeInt(4).writeInt(9).writeByte(0)));
    }

    @Test
    void entityPacketCarriesNothingAndBadCountsAreRejected() throws IOException {
        assertNull(decode(0x14, new PacketBuffer().writeVarInt(5)));
        assertThrows(IOException.class, () -> decode(0x13, new PacketBuffer().writeVarInt(-1)));
        assertThrows(IOException.class, () -> decode(0x44, new PacketBuffer().writeVarInt(9)));
        assertEquals(new ServerSession.ResourcePack("https://example.net/pack.zip", "abc"),
                decode(0x48, new PacketBuffer().writeString("https://example.net/pack.zip").writeString("abc")));
    }
}
