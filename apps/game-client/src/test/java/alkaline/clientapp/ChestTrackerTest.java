package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.net.PacketBuffer;
import alkaline.game.world.ClientWorld;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Chest tiles and lids against TileEntityChest (1.8.9): the open count from Block Action, and the 0.1 lid step. */
class ChestTrackerTest {
    private static final int STONE = 1;
    private static final int PASSES = 10;

    /** A world with one loaded column (chunk 0, 0) of air, which the tracker can scan. */
    private static ClientWorld loadedWorld() throws IOException {
        ClientWorld world = new ClientWorld(false);
        // One section: 4096 block states of 2 bytes each, then 2048 bytes of block light.
        world.applyChunk(0, 0, true, 1, new PacketBuffer(new byte[4096 * 2 + 2048], 0, 4096 * 2 + 2048));
        return world;
    }

    /** A tracker that has rescanned every section the world reported changed. */
    private static ChestTracker scanned(ClientWorld world) {
        ChestTracker tracker = new ChestTracker();
        world.drainChanged(tracker::changed);
        for (int pass = 0; pass < PASSES; pass++) {
            tracker.update(world);
        }
        return tracker;
    }

    private static ChestTracker.Chest at(ChestTracker tracker, int x, int y, int z) {
        for (ChestTracker.Chest chest : tracker.chests()) {
            if (chest.x == x && chest.y == y && chest.z == z) {
                return chest;
            }
        }
        return null;
    }

    private static ChestTracker trackerWithChestAt(int x, int y, int z) throws IOException {
        ClientWorld world = loadedWorld();
        world.setBlock(x, y, z, ChestTracker.CHEST << 4);
        return scanned(world);
    }

    // ---- block keys ------------------------------------------------------------------------------------------

    @Test
    void keyPacksEachCoordinateIntoItsOwnBitsAndUnpacksIt() {
        int[][] positions = {{0, 0, 0}, {1, 2, 3}, {-1, 255, -1}, {-30_000_000, 0, 30_000_000}, {123_456, 64, -654_321}};
        for (int[] position : positions) {
            long key = ChestTracker.key(position[0], position[1], position[2]);
            // x is the top 26 bits, y the next 12, z the low 26; each is sign-extended when read back.
            assertEquals(position[0], (int) (key >> 38));
            assertEquals(position[1], (int) (key << 26 >> 52));
            assertEquals(position[2], (int) (key << 38 >> 38));
        }
    }

    @Test
    void keysDifferForDifferentBlocks() {
        assertNotEquals(ChestTracker.key(1, 2, 3), ChestTracker.key(3, 2, 1));
        assertNotEquals(ChestTracker.key(0, 1, 0), ChestTracker.key(0, 0, 1));
        assertNotEquals(ChestTracker.key(-1, 0, 0), ChestTracker.key(1, 0, 0));
    }

    @Test
    void chestKindsAreChestTrappedAndEnder() {
        assertTrue(ChestTracker.chest(ChestTracker.CHEST));
        assertTrue(ChestTracker.chest(ChestTracker.TRAPPED_CHEST));
        assertTrue(ChestTracker.chest(ChestTracker.ENDER_CHEST));
        assertFalse(ChestTracker.chest(STONE));
        assertFalse(ChestTracker.chest(0));
    }

    @Test
    void scanFindsOnlyChestBlocks() throws IOException {
        ClientWorld world = loadedWorld();
        world.setBlock(3, 4, 5, ChestTracker.CHEST << 4);
        world.setBlock(4, 4, 5, ChestTracker.TRAPPED_CHEST << 4);
        world.setBlock(5, 4, 5, STONE << 4);
        world.setBlock(6, 4, 5, ChestTracker.ENDER_CHEST << 4);
        ChestTracker tracker = scanned(world);
        assertEquals(3, tracker.chests().size());
        assertEquals(ChestTracker.CHEST, at(tracker, 3, 4, 5).block);
        assertEquals(ChestTracker.TRAPPED_CHEST, at(tracker, 4, 4, 5).block);
        assertNull(at(tracker, 5, 4, 5));
        assertEquals(ChestTracker.ENDER_CHEST, at(tracker, 6, 4, 5).block);
    }

    // ---- block actions ---------------------------------------------------------------------------------------

    @Test
    void blockActionOneSetsTheOpenerCountOnChestsOnly() throws IOException {
        ChestTracker tracker = trackerWithChestAt(3, 4, 5);
        ChestTracker.Chest chest = at(tracker, 3, 4, 5);

        tracker.blockAction(3, 4, 5, 1, 2, ChestTracker.CHEST);
        assertEquals(2, chest.players);

        // Other actions are not the chest's open count.
        tracker.blockAction(3, 4, 5, 2, 7, ChestTracker.CHEST);
        assertEquals(2, chest.players);

        tracker.blockAction(3, 4, 5, 1, 0, ChestTracker.CHEST);
        assertEquals(0, chest.players);

        // Action 1 on a block that is not a chest does nothing.
        tracker.blockAction(3, 4, 5, 1, 3, STONE);
        assertEquals(0, chest.players);

        // So does a chest action at a place with no tracked chest.
        tracker.blockAction(9, 4, 5, 1, 4, ChestTracker.CHEST);
        assertEquals(0, chest.players);
    }

    // ---- lids ------------------------------------------------------------------------------------------------

    @Test
    void lidOpensTenthOfAnOpeningPerTickAndStopsAtFullyOpen() throws IOException {
        ChestTracker tracker = trackerWithChestAt(3, 4, 5);
        ChestTracker.Chest chest = at(tracker, 3, 4, 5);
        tracker.blockAction(3, 4, 5, 1, 1, ChestTracker.CHEST);

        tracker.tick();
        assertEquals(0.1f, chest.lid, 1e-6f);
        for (int tick = 1; tick < PASSES; tick++) {
            tracker.tick();
        }
        assertEquals(1f, chest.lid, 1e-5f);
        tracker.tick();
        assertEquals(1f, chest.lid, 0f);
    }

    @Test
    void lidClosesTenthPerTickAndStopsAtFullyClosed() throws IOException {
        ChestTracker tracker = trackerWithChestAt(3, 4, 5);
        ChestTracker.Chest chest = at(tracker, 3, 4, 5);
        tracker.blockAction(3, 4, 5, 1, 1, ChestTracker.CHEST);
        for (int tick = 0; tick < PASSES; tick++) {
            tracker.tick();
        }
        tracker.blockAction(3, 4, 5, 1, 0, ChestTracker.CHEST);

        tracker.tick();
        assertEquals(0.9f, chest.lid, 1e-5f);
        for (int tick = 1; tick < PASSES; tick++) {
            tracker.tick();
        }
        assertEquals(0f, chest.lid, 1e-5f);
        tracker.tick();
        assertEquals(0f, chest.lid, 0f);
    }

    @Test
    void lidInterpolatesBetweenTheLastTwoTicks() throws IOException {
        ChestTracker tracker = trackerWithChestAt(3, 4, 5);
        ChestTracker.Chest chest = at(tracker, 3, 4, 5);
        tracker.blockAction(3, 4, 5, 1, 1, ChestTracker.CHEST);

        tracker.tick();
        assertEquals(0f, chest.lid(0f), 1e-6f);
        assertEquals(0.05f, chest.lid(0.5f), 1e-6f);
        assertEquals(0.1f, chest.lid(1f), 1e-6f);

        tracker.tick();
        assertEquals(0.15f, chest.lid(0.5f), 1e-6f);
    }
}
