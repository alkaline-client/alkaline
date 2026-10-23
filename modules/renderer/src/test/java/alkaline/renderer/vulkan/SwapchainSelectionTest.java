package alkaline.renderer.vulkan;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwapchainSelectionTest {
    @Test
    void selectsPreferredSurfaceFormatOrTheFirstSupportedFormat() {
        SwapchainSelection.SurfaceFormat preferred = new SwapchainSelection.SurfaceFormat(44, 0);
        SwapchainSelection.SurfaceFormat fallback = new SwapchainSelection.SurfaceFormat(50, 1);

        assertEquals(preferred, SwapchainSelection.chooseSurfaceFormat(List.of(fallback, preferred), preferred));
        assertEquals(fallback, SwapchainSelection.chooseSurfaceFormat(List.of(fallback), preferred));
    }

    @Test
    void rejectsAnEmptySurfaceFormatList() {
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseSurfaceFormat(List.of(), new SwapchainSelection.SurfaceFormat(44, 0)));
    }

    @Test
    void choosesPreferredPresentModeOrRequiredFallback() {
        assertEquals(2, SwapchainSelection.choosePresentMode(new int[]{1, 2}, new int[]{2}, 1));
        assertEquals(1, SwapchainSelection.choosePresentMode(new int[]{1}, new int[]{2}, 1));
        // Immediate falls back to mailbox before FIFO (native Wayland offers FIFO and mailbox only).
        assertEquals(1, SwapchainSelection.choosePresentMode(new int[]{2, 1}, new int[]{0, 1, 2}, 2));
    }

    @Test
    void rejectsPresentModesWithoutTheRequiredFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.choosePresentMode(new int[]{3, 4}, new int[]{2}, 1));
    }

    @Test
    void clampsFramebufferExtentWhenTheSurfaceExtentIsVariable() {
        assertEquals(new SwapchainSelection.Extent(800, 600), SwapchainSelection.chooseExtent(
                -1, -1, 320, 200, 1920, 1080, 800, 600));
        assertEquals(new SwapchainSelection.Extent(1920, 200), SwapchainSelection.chooseExtent(
                -1, -1, 320, 200, 1920, 1080, 2400, 100));
    }

    @Test
    void acceptsZeroMinimumExtentAndClampsFramebufferToIt() {
        assertEquals(new SwapchainSelection.Extent(1, 1), SwapchainSelection.chooseExtent(
                -1, -1, 0, 0, 1920, 1080, 1, 1));
        assertEquals(new SwapchainSelection.Extent(0, 0), SwapchainSelection.chooseExtent(
                0, 0, 0, 0, 1920, 1080, 800, 600));
    }

    @Test
    void rejectsFixedSurfaceExtentsOutsideAdvertisedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseExtent(319, 720, 320, 200, 1920, 1080, 800, 600));
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseExtent(1280, 1081, 320, 200, 1920, 1080, 800, 600));
    }

    @Test
    void respectsAFixedSurfaceExtent() {
        assertEquals(new SwapchainSelection.Extent(1280, 720), SwapchainSelection.chooseExtent(
                1280, 720, 320, 200, 1920, 1080, 800, 600));
    }

    @Test
    void rejectsInvalidVariableExtentBoundsAndFramebufferSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseExtent(-1, -1, -1, 200, 1920, 1080, 800, 600));
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseExtent(-1, -1, 320, 200, 1920, 1080, 0, 600));
        assertThrows(IllegalArgumentException.class,
                () -> SwapchainSelection.chooseExtent(-1, 720, 320, 200, 1920, 1080, 800, 600));
    }

    @Test
    void choosesOneMoreImageThanTheMinimumWithinTheMaximum() {
        assertEquals(3, SwapchainSelection.chooseImageCount(2, 4));
        assertEquals(2, SwapchainSelection.chooseImageCount(2, 2));
        assertEquals(3, SwapchainSelection.chooseImageCount(2, 0));
        assertEquals(4, SwapchainSelection.chooseImageCount(2, 8, 4)); // mailbox
        assertEquals(3, SwapchainSelection.chooseImageCount(2, 3, 4));
        assertEquals(Integer.MAX_VALUE,
                SwapchainSelection.chooseImageCount(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void rejectsInvalidImageCountBounds() {
        assertThrows(IllegalArgumentException.class, () -> SwapchainSelection.chooseImageCount(0, 2));
        assertThrows(IllegalArgumentException.class, () -> SwapchainSelection.chooseImageCount(3, 2));
        assertThrows(ArithmeticException.class, () -> SwapchainSelection.chooseImageCount(Integer.MAX_VALUE, 0));
    }
}
