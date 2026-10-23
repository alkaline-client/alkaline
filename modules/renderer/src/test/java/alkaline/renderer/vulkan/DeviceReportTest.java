package alkaline.renderer.vulkan;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceReportTest {
    @Test
    void reportsObservedSurfaceCapabilityWithoutPromisingHardwareSupport() {
        var report = new DeviceReport("Test device", VulkanApiSupport.MINIMUM_API_VERSION, 0, 0, 0, 1, true,
                List.of("44/0"), List.of(2), null);
        assertTrue(report.usableForPrototype());
        assertFalse(new DeviceReport("Test device", VulkanApiSupport.MINIMUM_API_VERSION, 0, 0, 0, -1, true,
                List.of("44/0"), List.of(2), "No present queue").usableForPrototype());
    }

    @Test
    void rejectsOlderApiEvenWhenEverySurfaceCapabilityIsPresent() {
        var report = new DeviceReport("Old API", org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2,
                0, 0, 0, 0, true, List.of("44/0"), List.of(2), null);
        assertFalse(report.usableForPrototype());
    }

    @Test
    void snapshotsCollectedFormatsAndPresentModes() {
        var formats = new ArrayList<>(List.of("44/0"));
        var modes = new ArrayList<>(List.of(2));
        var report = new DeviceReport("Test device", VulkanApiSupport.MINIMUM_API_VERSION, 0, 0, 0, 0, true,
                formats, modes, null);
        formats.clear();
        modes.clear();
        assertTrue(report.usableForPrototype());
        assertThrows(UnsupportedOperationException.class, () -> report.surfaceFormats().clear());
    }
}
