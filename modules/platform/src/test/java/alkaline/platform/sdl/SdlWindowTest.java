package alkaline.platform.sdl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SdlWindowTest {
    @Test
    void pixelSizeRequiresPositiveDimensions() {
        SdlWindow.PixelSize size = new SdlWindow.PixelSize(1_920, 1_080);
        assertEquals(1_920, size.width());
        assertEquals(1_080, size.height());
        assertThrows(IllegalArgumentException.class, () -> new SdlWindow.PixelSize(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SdlWindow.PixelSize(1, 0));
    }
}
