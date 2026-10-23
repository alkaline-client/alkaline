package alkaline.renderer.vulkan;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_PATCH;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_VARIANT;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

public final class VulkanApiSupport {
    public static final int MINIMUM_API_VERSION = VK_API_VERSION_1_3;

    private VulkanApiSupport() {
    }

    public static boolean meetsMinimum(int apiVersion) {
        return VK_API_VERSION_VARIANT(apiVersion) == 0
                && (VK_API_VERSION_MAJOR(apiVersion) > 1
                || (VK_API_VERSION_MAJOR(apiVersion) == 1 && VK_API_VERSION_MINOR(apiVersion) >= 3));
    }

    public static String format(int apiVersion) {
        return VK_API_VERSION_MAJOR(apiVersion) + "." + VK_API_VERSION_MINOR(apiVersion)
                + "." + VK_API_VERSION_PATCH(apiVersion)
                + (VK_API_VERSION_VARIANT(apiVersion) == 0 ? ""
                : " (variant " + VK_API_VERSION_VARIANT(apiVersion) + ")");
    }

    public static void requireSupportedLoader(int apiVersion) {
        if (!meetsMinimum(apiVersion)) {
            throw new IllegalStateException("Vulkan 1.3 or newer is required; loader reports "
                    + format(apiVersion) + ". There is no older-API fallback.");
        }
    }
}
