package alkaline.renderer.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK10.VK_MAKE_API_VERSION;

class VulkanApiSupportTest {
    @Test
    void acceptsVersion13AndLater() {
        assertTrue(VulkanApiSupport.meetsMinimum(VK_API_VERSION_1_3));
        assertTrue(VulkanApiSupport.meetsMinimum(VK_MAKE_API_VERSION(0, 1, 4, 0)));
    }

    @Test
    void rejectsVersionsBelow13() {
        assertFalse(VulkanApiSupport.meetsMinimum(VK_API_VERSION_1_2));
        assertFalse(VulkanApiSupport.meetsMinimum(VK_MAKE_API_VERSION(0, 1, 1, 9)));
        assertFalse(VulkanApiSupport.meetsMinimum(VK_MAKE_API_VERSION(0, 0, 9, 9)));
        assertFalse(VulkanApiSupport.meetsMinimum(VK_MAKE_API_VERSION(1, 1, 3, 0)));
    }

    @Test
    void unsupportedLoaderExplainsMinimumAndHasNoFallback() {
        var failure = assertThrows(IllegalStateException.class,
                () -> VulkanApiSupport.requireSupportedLoader(VK_API_VERSION_1_2));
        assertTrue(failure.getMessage().contains("Vulkan 1.3 or newer is required"));
        assertTrue(failure.getMessage().contains("1.2.0"));
        VulkanApiSupport.requireSupportedLoader(VK_API_VERSION_1_3);
        assertEquals("1.3.0", VulkanApiSupport.format(VK_API_VERSION_1_3));
    }
}
