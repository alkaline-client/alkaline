package alkaline.renderer.vulkan.resource;

import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;

/**
 * Writes nested struct fields by address. LWJGL's nested accessors take lambdas or return wrapper objects, and
 * a capturing lambda allocates on every call; per-frame recording goes through these instead.
 */
final class StructWrites {
    private StructWrites() {
    }

    static void rect(long rect, int x, int y, int width, int height) {
        VkOffset2D.nx(rect + VkRect2D.OFFSET, x);
        VkOffset2D.ny(rect + VkRect2D.OFFSET, y);
        VkExtent2D.nwidth(rect + VkRect2D.EXTENT, width);
        VkExtent2D.nheight(rect + VkRect2D.EXTENT, height);
    }

    static void subresourceRange(long range, int aspect, int baseMip, int levels, int baseLayer, int layers) {
        VkImageSubresourceRange.naspectMask(range, aspect);
        VkImageSubresourceRange.nbaseMipLevel(range, baseMip);
        VkImageSubresourceRange.nlevelCount(range, levels);
        VkImageSubresourceRange.nbaseArrayLayer(range, baseLayer);
        VkImageSubresourceRange.nlayerCount(range, layers);
    }
}
