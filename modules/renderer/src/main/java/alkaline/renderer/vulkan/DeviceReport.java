package alkaline.renderer.vulkan;

import java.util.List;

/** Capability observations for one Vulkan physical device, not a hardware support promise. */
public record DeviceReport(
        String name,
        int apiVersion,
        int vendorId,
        int deviceId,
        int graphicsQueueFamily,
        int presentQueueFamily,
        boolean swapchainExtension,
        List<String> surfaceFormats,
        List<Integer> presentModes,
        String failure
) {
    public DeviceReport {
        surfaceFormats = List.copyOf(surfaceFormats);
        presentModes = List.copyOf(presentModes);
    }

    public boolean usableForPrototype() {
        return VulkanApiSupport.meetsMinimum(apiVersion) && failure == null
                && graphicsQueueFamily >= 0 && presentQueueFamily >= 0
                && swapchainExtension && !surfaceFormats.isEmpty() && !presentModes.isEmpty();
    }
}
