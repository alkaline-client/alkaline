package alkaline.renderer.vulkan.resource;

import alkaline.renderer.image.ImageSampling;
import java.util.Objects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/** Immutable sampled RGBA texture (optionally a same-size 2D array) owned by one Vulkan renderer. */
public final class VulkanTexture extends VulkanSampledImage implements AutoCloseable {
    /** Usage of every static texture image; host image copy adds VK_IMAGE_USAGE_HOST_TRANSFER_BIT. */
    static final int STATIC_USAGE = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | VK_IMAGE_USAGE_SAMPLED_BIT;
    /**
     * The largest texture (level 0, all layers) written by host image copy. Host-transfer images may need the
     * small host-visible device heap (246 MiB on a GTX 1660 SUPER without resizable BAR), so only the textures
     * created during play (skins, items, maps, font pages, animation frames) take that path; atlases and sky
     * images loaded with the assets keep the staging copy.
     */
    private static final long HOST_COPY_MAX_BYTES = 1L << 20;
    private final VulkanRenderer owner;
    private final VkDevice device;
    private final VulkanTextureDescriptors descriptors;
    private final VulkanTextureDescriptors.Allocation descriptor;
    private final VulkanImage image;
    private final long sampler;
    private final int width;
    private final int height;
    private final int layers;
    private final TextureOptions options;

    VulkanTexture(VulkanRenderer owner, VkPhysicalDevice physicalDevice, VkDevice device,
                  VulkanTextureDescriptors descriptors, long descriptorSetLayout, TextureData data,
                  VulkanUploader uploader) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.device = Objects.requireNonNull(device, "device");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(uploader, "uploader");
        options = data.options();
        int format = options.srgb() ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
        int mipLevels = data.mipLevels();

        VulkanImage createdImage = null;
        long createdSampler = 0;
        VulkanTextureDescriptors.Allocation createdDescriptor = null;
        try {
            boolean host = uploader.hostImageCopy() && data.levelZeroBytes() <= HOST_COPY_MAX_BYTES;
            if (host) {
                try {
                    createdImage = VulkanImage.create(physicalDevice, device, data.width(), data.height(),
                            data.layers(), mipLevels, format,
                            STATIC_USAGE | org.lwjgl.vulkan.VK14.VK_IMAGE_USAGE_HOST_TRANSFER_BIT,
                            VK_IMAGE_ASPECT_COLOR_BIT, data.array());
                } catch (IllegalStateException full) {
                    host = false; // its memory heap is full: the staging copy needs none of it
                }
            }
            if (!host) {
                createdImage = VulkanImage.create(physicalDevice, device, data.width(), data.height(),
                        data.layers(), mipLevels, format, STATIC_USAGE, VK_IMAGE_ASPECT_COLOR_BIT, data.array());
            }
            createdSampler = VulkanImage.createSampler(device, options.sampling() == ImageSampling.LINEAR,
                    options.repeat(), mipLevels);
            createdDescriptor = descriptors.allocate(descriptorSetLayout);
            writeDescriptor(device, createdDescriptor.descriptorSet(), createdSampler, createdImage.view());
            if (host) {
                uploader.uploadImageFromHost(createdImage.image(), data.width(), data.height(), data.layers(),
                        mipLevels, data.pixels());
            } else {
                uploader.uploadImage(createdImage.image(), data.width(), data.height(), data.layers(), mipLevels,
                        data.pixels());
            }
        } catch (RuntimeException | Error failure) {
            if (createdDescriptor != null) {
                try {
                    descriptors.free(createdDescriptor);
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (createdSampler != 0) {
                vkDestroySampler(device, createdSampler, null);
            }
            if (createdImage != null) {
                createdImage.destroy(device);
            }
            throw failure;
        }

        image = createdImage;
        sampler = createdSampler;
        descriptor = createdDescriptor;
        width = data.width();
        height = data.height();
        layers = data.layers();
    }

    @Override
    public int width() {
        ensureResourceOpen();
        return width;
    }

    @Override
    public int height() {
        ensureResourceOpen();
        return height;
    }

    public int layers() {
        ensureResourceOpen();
        return layers;
    }

    public ImageSampling sampling() {
        ensureResourceOpen();
        return options.sampling();
    }

    public TextureOptions options() {
        ensureResourceOpen();
        return options;
    }

    VulkanImage image() {
        ensureResourceOpen();
        return image;
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
    }
}
