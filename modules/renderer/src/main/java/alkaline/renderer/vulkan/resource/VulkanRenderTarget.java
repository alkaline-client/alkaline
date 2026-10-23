package alkaline.renderer.vulkan.resource;

import java.util.Objects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/**
 * Fixed-size offscreen color image that one pass renders and later passes sample. Layout transitions are
 * recorded by {@link VulkanFrame#beginRendering} and {@link VulkanFrame#endRendering}.
 */
public final class VulkanRenderTarget extends VulkanSampledImage implements AutoCloseable {
    private final VulkanRenderer owner;
    private final VkDevice device;
    private final VulkanTextureDescriptors descriptors;
    private final VulkanTextureDescriptors.Allocation descriptor;
    private final VulkanImage image;
    private final long sampler;
    private final int width;
    private final int height;
    private final RenderTargetFormat format;
    private final VulkanImage depth;
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    VulkanRenderTarget(VulkanRenderer owner, VkPhysicalDevice physicalDevice, VkDevice device,
                       VulkanTextureDescriptors descriptors, long descriptorSetLayout,
                       int width, int height, RenderTargetFormat format, boolean linear, boolean repeat,
                       int depthFormat) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.device = Objects.requireNonNull(device, "device");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.format = Objects.requireNonNull(format, "format");
        if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384) {
            throw new IllegalArgumentException("Render target size must be between 1 and 16384 pixels.");
        }
        VulkanImage createdImage = null;
        long createdSampler = 0;
        VulkanTextureDescriptors.Allocation createdDescriptor = null;
        VulkanImage createdDepth = null;
        try {
            if (depthFormat != 0) {
                createdDepth = VulkanImage.create(physicalDevice, device, width, height, 1, depthFormat,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT, false);
            }
            // Targets with depth hold scenes; their sRGB images also get a UNORM alias for gamma-space passes.
            createdImage = VulkanImage.create(physicalDevice, device, width, height, 1, 1, format.vulkanFormat(),
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT,
                    false, depthFormat != 0);
            createdSampler = VulkanImage.createSampler(device, linear, repeat);
            createdDescriptor = descriptors.allocate(descriptorSetLayout);
            writeDescriptor(device, createdDescriptor.descriptorSet(), createdSampler, createdImage.view());
        } catch (RuntimeException | Error failure) {
            if (createdDescriptor != null) {
                descriptors.free(createdDescriptor);
            }
            if (createdSampler != 0) {
                vkDestroySampler(device, createdSampler, null);
            }
            if (createdImage != null) {
                createdImage.destroy(device);
            }
            if (createdDepth != null) {
                createdDepth.destroy(device);
            }
            throw failure;
        }
        image = createdImage;
        depth = createdDepth;
        sampler = createdSampler;
        descriptor = createdDescriptor;
        this.width = width;
        this.height = height;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    public RenderTargetFormat format() {
        return format;
    }

    /** True when passes into this target carry a depth attachment cleared to 1 at the start of each pass. */
    public boolean hasDepth() {
        return depth != null;
    }

    VulkanImage depthImage() {
        ensureResourceOpen();
        return depth;
    }

    VulkanImage image() {
        ensureResourceOpen();
        return image;
    }

    int layout() {
        return layout;
    }

    void layout(int newLayout) {
        layout = newLayout;
    }

    @Override
    long descriptorSet() {
        ensureResourceOpen();
        return descriptor.descriptorSet();
    }

    @Override
    boolean belongsTo(VulkanRenderer renderer) {
        return owner == renderer;
    }

    @Override
    public void close() {
        owner.closeSampled(this);
    }

    void release() {
        requestRelease();
    }

    @Override
    void releaseNative() {
        descriptors.free(descriptor);
        vkDestroySampler(device, sampler, null);
        image.destroy(device);
        if (depth != null) {
            depth.destroy(device);
        }
    }
}
