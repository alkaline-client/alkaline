package alkaline.renderer.vulkan.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuCompositorTest {
    private static GlassRegion region(float left, float top, float right, float bottom) {
        return new GlassRegion(left, top, right, bottom, 4f, 0, 1f, true);
    }

    @Test
    void regionsArePaddedClampedAndScaledToTheScene() {
        float[] out = new float[16];
        int count = MenuCompositor.padRects(List.of(region(10, 20, 30, 40)), 2f, 2f, 8f, 100, 100, out);
        assertEquals(1, count);
        assertArrayEquals(new float[]{12, 32, 68, 88}, Arrays.copyOf(out, 4));
        count = MenuCompositor.padRects(List.of(region(0, 0, 10, 10)), 1f, 1f, 20f, 100, 100, out);
        assertArrayEquals(new float[]{0, 0, 30, 30}, Arrays.copyOf(out, 4));
    }

    @Test
    void overlappingPaddedRegionsMergeAndDistantOnesStayApart() {
        float[] out = new float[16];
        int count = MenuCompositor.padRects(List.of(region(0, 0, 10, 10), region(15, 0, 25, 10), region(80, 80, 90, 90)),
                1f, 1f, 4f, 200, 200, out);
        assertEquals(2, count);
        assertArrayEquals(new float[]{0, 0, 29, 14}, Arrays.copyOf(out, 4));
        assertArrayEquals(new float[]{76, 76, 94, 94}, Arrays.copyOfRange(out, 4, 8));
    }
}
