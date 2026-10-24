package alkaline.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.net.ServerSession;
import org.junit.jupiter.api.Test;

class WorldBorderTest {
    private long now;
    private final WorldBorder border = new WorldBorder(() -> now);

    private static ServerSession.WorldBorder packet(int action, double cx, double cz, double from, double to,
                                                    long millis, int portal, int time, int blocks) {
        return new ServerSession.WorldBorder(action, cx, cz, from, to, millis, portal, time, blocks);
    }

    @Test
    void initializeSetsCenterSizeAndWarnings() {
        border.apply(packet(3, 10, -20, 100, 100, 0, 29999984, 15, 5));
        assertEquals(WorldBorder.Status.STATIONARY, border.status());
        assertEquals(-40, border.minX(), 1e-9);
        assertEquals(60, border.maxX(), 1e-9);
        assertEquals(-70, border.minZ(), 1e-9);
        assertEquals(30, border.maxZ(), 1e-9);
        assertEquals(15, border.warningTime());
        assertEquals(5, border.warningDistance());
    }

    @Test
    void aMovingBorderInterpolatesLinearlyThenSettles() {
        border.apply(packet(3, 0, 0, 100, 20, 10_000, 29999984, 15, 5));
        assertEquals(WorldBorder.Status.SHRINKING, border.status());
        assertEquals(100, border.diameter(), 1e-9);
        now = 5_000;
        assertEquals(60, border.diameter(), 1e-6);
        assertEquals(0.008, border.resizeSpeed(), 1e-12);
        now = 10_000;
        assertEquals(20, border.diameter(), 1e-9);
        assertEquals(WorldBorder.Status.STATIONARY, border.status());
        border.apply(packet(1, 0, 0, 20, 40, 1_000, 0, 0, 0));
        assertEquals(WorldBorder.Status.GROWING, border.status());
    }

    @Test
    void statusColorsAreVanillas() {
        assertEquals(4259712, WorldBorder.Status.GROWING.color());
        assertEquals(16724016, WorldBorder.Status.SHRINKING.color());
        assertEquals(2138367, WorldBorder.Status.STATIONARY.color());
    }

    @Test
    void closestDistanceAndContainment() {
        border.apply(packet(3, 0, 0, 20, 20, 0, 29999984, 15, 5));
        assertEquals(10, border.closestDistance(0, 0), 1e-9);
        assertEquals(1, border.closestDistance(9, 3), 1e-9);
        assertEquals(-2, border.closestDistance(12, 0), 1e-9);
        assertTrue(border.contains(9, 0));
        assertFalse(border.contains(10, 0));
        assertTrue(border.contains(-10, 0));
        assertFalse(border.contains(-11, 0));
    }

    @Test
    void warningGrowsFromTheWarningDistanceToTheEdge() {
        border.apply(packet(3, 0, 0, 20, 20, 0, 29999984, 15, 5));
        assertEquals(0f, border.warning(0, 0), 1e-6);
        assertEquals(0f, border.warning(5, 0), 1e-6); // exactly five blocks from the edge
        assertEquals(0.5f, border.warning(7.5, 0), 1e-6);
        assertEquals(1f, border.warning(10, 0), 1e-6);
    }

    @Test
    void aShrinkingBorderWarnsEarlierThanTheWarningDistance() {
        // 200 -> 100 over 100 s at warning time 15 s: the edge moves 15 blocks within the warning time.
        border.apply(packet(3, 0, 0, 200, 100, 100_000, 29999984, 15, 5));
        assertEquals(0f, border.warning(85, 0), 1e-6); // 15 from the edge: at the reach
        assertEquals(1f - 7.5f / 15f, border.warning(92.5, 0), 1e-4);
    }
}
