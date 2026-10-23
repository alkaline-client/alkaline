package alkaline.renderer.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueFamiliesTest {
    @Test
    void prefersACombinedQueueForTheSurface() {
        assertEquals(new QueueFamilies(2, 2), QueueFamilies.choose(
                new boolean[]{true, false, true}, new boolean[]{false, true, true}));
    }

    @Test
    void acceptsSeparateGraphicsAndPresentationQueues() {
        assertEquals(new QueueFamilies(0, 1), QueueFamilies.choose(
                new boolean[]{true, false}, new boolean[]{false, true}));
    }

    @Test
    void reportsMissingPresentationCapability() {
        assertEquals(new QueueFamilies(0, -1), QueueFamilies.choose(
                new boolean[]{true}, new boolean[]{false}));
    }

    @Test
    void rejectsMismatchedObservationLengths() {
        assertThrows(IllegalArgumentException.class, () -> QueueFamilies.choose(
                new boolean[]{true}, new boolean[]{true, false}));
    }
}
