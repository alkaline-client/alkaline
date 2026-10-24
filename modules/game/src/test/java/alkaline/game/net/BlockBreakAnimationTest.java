package alkaline.game.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.world.BlockDamage;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Block Break Animation (0x25) from the wire to the cracks the renderer draws. */
class BlockBreakAnimationTest {
    @Test
    void aBlockBreakAnimationPacketBecomesACrackStage() throws IOException {
        // Packet 0x25: breaker entity id (VarInt), position, destroy stage (byte).
        PacketBuffer body = new PacketBuffer().writeVarInt(300).writePosition(-12, 70, 5).writeByte(4);
        var packet = assertInstanceOf(ServerSession.BlockBreak.class,
                ServerSession.decode(0x25, PacketBuffer.wrap(body.toByteArray())));
        assertEquals(new ServerSession.BlockBreak(300, -12, 70, 5, 4), packet);

        BlockDamage damage = new BlockDamage();
        damage.apply(packet);
        assertEquals(1, damage.entries().size());
        BlockDamage.Entry entry = damage.entries().getFirst();
        assertEquals(300, entry.breaker());
        assertEquals(-12, entry.x());
        assertEquals(70, entry.y());
        assertEquals(5, entry.z());
        assertEquals(4, entry.stage());
    }

    @Test
    void anOutOfRangeStageEndsTheDig() throws IOException {
        BlockDamage damage = new BlockDamage();
        damage.set(7, 1, 2, 3, 9);
        assertEquals(1, damage.entries().size());
        // The server sends -1 (0xFF as a signed byte) when the digger stops.
        var stop = (ServerSession.BlockBreak) ServerSession.decode(0x25, PacketBuffer.wrap(
                new PacketBuffer().writeVarInt(7).writePosition(1, 2, 3).writeByte(-1).toByteArray()));
        damage.apply(stop);
        assertTrue(damage.isEmpty());
        damage.set(7, 1, 2, 3, 10);
        assertTrue(damage.isEmpty());
    }
}
