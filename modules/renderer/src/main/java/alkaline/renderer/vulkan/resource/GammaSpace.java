package alkaline.renderer.vulkan.resource;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_SRGB_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * UNORM aliases of sRGB color formats. Vanilla blends in OpenGL's default framebuffer, on sRGB-encoded values;
 * an sRGB attachment blends linear values instead. Effects whose look depends on the blend (the additive
 * enchantment glint) render through a UNORM view of the same image, which blends the stored encoded bytes the way
 * vanilla does. Such images are created mutable with both formats listed.
 */
final class GammaSpace {
    private GammaSpace() {
    }

    /** The UNORM format with the same bytes, or 0 when the format is not an 8-bit sRGB format. */
    static int unormAlias(int format) {
        return switch (format) {
            case VK_FORMAT_B8G8R8A8_SRGB -> VK_FORMAT_B8G8R8A8_UNORM;
            case VK_FORMAT_R8G8B8A8_SRGB -> VK_FORMAT_R8G8B8A8_UNORM;
            case VK_FORMAT_A8B8G8R8_SRGB_PACK32 -> VK_FORMAT_A8B8G8R8_UNORM_PACK32;
            default -> 0;
        };
    }

    /** The attachment format a pipeline is built for; gamma-space pipelines use the alias when there is one. */
    static int attachmentFormat(int format, boolean gammaSpace) {
        int alias = gammaSpace ? unormAlias(format) : 0;
        return alias != 0 ? alias : format;
    }
}
