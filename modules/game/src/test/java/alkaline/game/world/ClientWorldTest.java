package alkaline.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.net.PacketBuffer;
import org.junit.jupiter.api.Test;

class ClientWorldTest {
    private static final int END_STONE = 121 << 4;
    private static final int THE_END = 9;

    /**
     * A Map Chunk Bulk from the End: a void column (no sections, only its 256 biome bytes), then a column with one
     * section (states, block light, no sky light, biomes). The void column must consume its biomes, or the next
     * column reads them as block states.
     */
    @Test
    void voidColumnInBulkKeepsTheNextColumnAligned() throws Exception {
        byte[] voidColumn = biomes();
        byte[] column = new byte[4096 * 2 + 2048 + 256];
        column[2 * 2] = (byte) END_STONE;
        column[2 * 2 + 1] = (byte) (END_STONE >> 8);
        System.arraycopy(biomes(), 0, column, column.length - 256, 256);
        byte[] bulk = new byte[voidColumn.length + column.length];
        System.arraycopy(voidColumn, 0, bulk, 0, voidColumn.length);
        System.arraycopy(column, 0, bulk, voidColumn.length, column.length);

        ClientWorld world = new ClientWorld(false);
        PacketBuffer data = PacketBuffer.wrap(bulk);
        world.applyChunk(0, 0, true, 0, data);
        world.applyChunk(1, 0, true, 1, data);

        assertTrue(world.isLoaded(0, 0));
        assertEquals(THE_END, world.biome(0, 0));
        assertEquals(0, world.state(16, 0, 0));
        assertEquals(END_STONE, world.state(18, 0, 0));
        assertEquals(THE_END, world.biome(16, 0));
        assertEquals(0, data.remaining());
    }

    /** Stone (opaque), lava (emits 15), everything else clear. */
    private static final ClientWorld.LightRules RULES = new ClientWorld.LightRules() {
        @Override
        public int opacity(int id) {
            return id == 1 ? 255 : 0;
        }

        @Override
        public int emission(int id) {
            return id == 10 || id == 11 ? 15 : 0;
        }
    };

    /** 3x3 columns around chunk 0, 0, each with one air section at y 0..15 and the given sky light. */
    private static ClientWorld airWorld(boolean sky) throws Exception {
        ClientWorld world = new ClientWorld(sky);
        for (int cz = -1; cz <= 1; cz++) {
            for (int cx = -1; cx <= 1; cx++) {
                byte[] column = new byte[4096 * 2 + 2048 + (sky ? 2048 : 0) + 256];
                if (sky) {
                    java.util.Arrays.fill(column, 4096 * 2 + 2048, 4096 * 2 + 4096, (byte) 0xFF);
                }
                world.applyChunk(cx, cz, true, 1, PacketBuffer.wrap(column));
            }
        }
        world.lightRules(RULES);
        return world;
    }

    @Test
    void lavaThatFlowsInLightsItsSurroundingsAndRemovingItDarkensThem() throws Exception {
        ClientWorld world = airWorld(false);
        world.setBlock(8, 8, 8, 10 << 4);
        assertEquals(15, world.light(8, 8, 8) & 15);
        assertEquals(14, world.light(9, 8, 8) & 15);
        assertEquals(10, world.light(8, 8, 13) & 15);
        assertEquals(11, world.light(8, 12, 8) & 15);
        // No sky in the Nether: relighting never brightens the sky channel.
        assertEquals(0, world.light(8, 8, 8) >> 4);

        world.setBlocks(0, 0, new int[]{8 | 8 << 4 | 8 << 8}, new int[]{0}, 1);
        assertEquals(0, world.light(8, 8, 8) & 15);
        assertEquals(0, world.light(8, 8, 13) & 15);
    }

    @Test
    void aPlacedBlockShadesTheSkyLightBelowIt() throws Exception {
        ClientWorld world = airWorld(true);
        world.setBlock(8, 10, 8, 1 << 4);
        assertEquals(15, world.light(8, 11, 8) >> 4);
        // Beneath it the column no longer sees the sky; light spills in from the sides, one level down.
        assertEquals(14, world.light(8, 9, 8) >> 4);
        assertEquals(14, world.light(8, 2, 8) >> 4);
        assertEquals(15, world.light(9, 9, 8) >> 4);

        world.setBlock(8, 10, 8, 0);
        assertEquals(15, world.light(8, 9, 8) >> 4);
        assertEquals(15, world.light(8, 2, 8) >> 4);
    }

    private static byte[] biomes() {
        byte[] biomes = new byte[256];
        java.util.Arrays.fill(biomes, (byte) THE_END);
        return biomes;
    }
}
