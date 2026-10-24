package alkaline.renderer.vulkan.resource;

import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
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
                  VulkanTextureDescriptors descriptors, long descriptorSetLayout,
                  List<RgbaImage> sources, boolean arrayView, TextureOptions options, VulkanUploader uploader) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.device = Objects.requireNonNull(device, "device");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(uploader, "uploader");
        if (sources.isEmpty() || (!arrayView && sources.size() != 1)) {
            throw new IllegalArgumentException("A texture needs one image, or one or more same-size array layers.");
        }
        RgbaImage first = sources.getFirst();
        for (RgbaImage layer : sources) {
            if (layer.width() != first.width() || layer.height() != first.height()) {
                throw new IllegalArgumentException("Texture array layers must share one size.");
            }
        }
        int format = options.srgb() ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;

        VulkanImage createdImage = null;
        long createdSampler = 0;
        VulkanTextureDescriptors.Allocation createdDescriptor = null;
        ByteBuffer pixels = null;
        try {
            int mipLevels = Math.min(options.mipLevels(),
                    32 - Integer.numberOfLeadingZeros(Math.max(first.width(), first.height())));
            boolean host = uploader.hostImageCopy()
                    && (long) first.width() * first.height() * 4 * sources.size() <= HOST_COPY_MAX_BYTES;
            if (host) {
                try {
                    createdImage = VulkanImage.create(physicalDevice, device, first.width(), first.height(),
                            sources.size(), mipLevels, format,
                            STATIC_USAGE | org.lwjgl.vulkan.VK14.VK_IMAGE_USAGE_HOST_TRANSFER_BIT,
                            VK_IMAGE_ASPECT_COLOR_BIT, arrayView);
                } catch (IllegalStateException full) {
                    host = false; // its memory heap is full: the staging copy needs none of it
                }
            }
            if (!host) {
                createdImage = VulkanImage.create(physicalDevice, device, first.width(), first.height(),
                        sources.size(), mipLevels, format, STATIC_USAGE, VK_IMAGE_ASPECT_COLOR_BIT, arrayView);
            }
            createdSampler = VulkanImage.createSampler(device, options.sampling() == ImageSampling.LINEAR,
                    options.repeat(), mipLevels);
            createdDescriptor = descriptors.allocate(descriptorSetLayout);
            writeDescriptor(device, createdDescriptor.descriptorSet(), createdSampler, createdImage.view());
            byte[][] levels = new byte[sources.size()][];
            long total = 0;
            for (int level = 0; level < mipLevels; level++) {
                total += (long) Math.max(1, first.width() >> level) * Math.max(1, first.height() >> level) * 4
                        * sources.size();
            }
            pixels = memAlloc(Math.toIntExact(total));
            for (int level = 0; level < mipLevels; level++) {
                for (int layer = 0; layer < sources.size(); layer++) {
                    levels[layer] = level == 0 ? sources.get(layer).copyPixels()
                            : MipChain.downsample(levels[layer], Math.max(1, first.width() >> (level - 1)),
                                    Math.max(1, first.height() >> (level - 1)), options.srgb());
                    pixels.put(levels[layer]);
                }
            }
            pixels.flip();
            if (host) {
                uploader.uploadImageFromHost(createdImage.image(), first.width(), first.height(), sources.size(),
                        mipLevels, pixels);
            } else {
                uploader.uploadImage(createdImage.image(), first.width(), first.height(), sources.size(), mipLevels,
                        pixels);
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
        } finally {
            if (pixels != null) {
                memFree(pixels);
            }
        }

        image = createdImage;
        sampler = createdSampler;
        descriptor = createdDescriptor;
        width = first.width();
        height = first.height();
        layers = sources.size();
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
