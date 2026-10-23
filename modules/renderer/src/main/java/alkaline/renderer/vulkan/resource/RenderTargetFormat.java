package alkaline.renderer.vulkan.resource;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/** Color formats offered for offscreen passes; all are mandatory color-attachment formats in Vulkan 1.3. */
public enum RenderTargetFormat {
    /** Stores shader output verbatim; used where vanilla math runs on sRGB-encoded values. */
    RGBA8_UNORM(VK_FORMAT_R8G8B8A8_UNORM),
    /** Encodes linear shader output to sRGB on write and decodes on sample. */
    RGBA8_SRGB(VK_FORMAT_R8G8B8A8_SRGB),
    /** Linear half-float color for HDR intermediate passes. */
    RGBA16_FLOAT(VK_FORMAT_R16G16B16A16_SFLOAT);

    private final int vulkanFormat;

    RenderTargetFormat(int vulkanFormat) {
        this.vulkanFormat = vulkanFormat;
    }

    int vulkanFormat() {
        return vulkanFormat;
    }
}
