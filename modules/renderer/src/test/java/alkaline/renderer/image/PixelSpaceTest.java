package alkaline.renderer.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PixelSpaceTest {
    @Test
    void mapsTopLeftPixelCoordinatesToVulkanClipSpace() {
        float[] projection = PixelSpace.projection(800, 600);

        assertEquals(2f / 800, projection[0]);
        assertEquals(2f / 600, projection[5]);
        assertEquals(-1f, projection[12]);
        assertEquals(-1f, projection[13]);
        assertThrows(IllegalArgumentException.class, () -> PixelSpace.projection(0, 600));
    }
}
