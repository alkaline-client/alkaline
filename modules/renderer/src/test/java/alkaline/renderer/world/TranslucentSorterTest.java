package alkaline.renderer.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslucentSorterTest {
    private final TranslucentSorter sorter = new TranslucentSorter();

    private static short cell(int x, int y, int z) {
        return (short) (x | y << 4 | z << 8);
    }

    /** Quad indices in draw order, read back from the index list. */
    private int[] order(short[] cells, int count, int cameraKey) {
        ByteBuffer target = ByteBuffer.allocate(count * 24).order(ByteOrder.nativeOrder());
        sorter.sort(cells, count, cameraKey, target, 0);
        int[] quads = new int[count];
        for (int quad = 0; quad < count; quad++) {
            int base = target.getInt(quad * 24);
            assertEquals(base + 1, target.getInt(quad * 24 + 4));
            assertEquals(base, target.getInt(quad * 24 + 20), "two triangles sharing the 0-2 edge");
            quads[quad] = base / 4;
        }
        return quads;
    }

    @Test
    void drawsFartherCellsFirst() {
        short[] cells = {cell(1, 0, 0), cell(9, 0, 0), cell(4, 0, 0)};
        int key = TranslucentSorter.cameraKey(0.5, 0.5, 0.5, 0, 0, 0);
        assertEquals(java.util.List.of(1, 2, 0), java.util.Arrays.stream(order(cells, 3, key)).boxed().toList());
    }

    @Test
    void cellsHidingOthersComeAfterThemOnEveryAxis() {
        // Camera at cell (8, 8, 8). Cell (6, 8, 8) lies between the camera and (2, 7, 8); it must be drawn later.
        short[] cells = {cell(6, 8, 8), cell(2, 7, 8), cell(8, 3, 8)};
        int key = TranslucentSorter.cameraKey(8.5, 8.5, 8.5, 0, 0, 0);
        int[] quads = order(cells, 3, key);
        int near = -1;
        int far = -1;
        for (int position = 0; position < 3; position++) {
            if (quads[position] == 0) near = position;
            if (quads[position] == 1) far = position;
        }
        assertEquals(true, far < near);
    }

    @Test
    void cellsKeepEmissionOrderAtEqualDistance() {
        short[] cells = {cell(3, 3, 3), cell(3, 3, 3), cell(3, 3, 3)};
        int key = TranslucentSorter.cameraKey(0, 0, 0, 0, 0, 0);
        assertEquals(java.util.List.of(0, 1, 2), java.util.Arrays.stream(order(cells, 3, key)).boxed().toList());
    }

    @Test
    void cameraPositionsBeyondTheSectionShareOneKey() {
        int near = TranslucentSorter.cameraKey(-1.5, 40, 3.2, 0, 0, 0);
        int far = TranslucentSorter.cameraKey(-900, 4000, 3.9, 0, 0, 0);
        assertEquals(near, far, "only the clamped cell matters");
        int other = TranslucentSorter.cameraKey(-1.5, 40, 4.1, 0, 0, 0);
        assertEquals(false, near == other, "crossing a cell boundary inside the section's span changes the key");
    }

    @Test
    void sectionsInStaticOrderGetTheIdentity() {
        assertEquals(java.util.List.of(0, 1, 2, 3),
                java.util.Arrays.stream(order(new short[0], 4, 0)).boxed().toList());
    }
}
