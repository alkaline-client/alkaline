package alkaline.renderer.vulkan.resource;

import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/**
 * BGRA8 texture whose whole contents can be replaced every frame without stalling: each frame slot owns a
 * persistently mapped staging region, and the copy is recorded into that frame's command buffer.
 */
public final class VulkanDynamicTexture extends VulkanSampledImage implements AutoCloseable {
    private final VulkanRenderer owner;
    private final VkDevice device;
    private final VulkanTextureDescriptors descriptors;
    private final VulkanTextureDescriptors.Allocation descriptor;
    private final VulkanImage image;
    private final VulkanBuffer staging;
    private final ByteBuffer stagingBytes;
    private final long sampler;
    private final int width;
    private final int height;
    private final int frameBytes;

    VulkanDynamicTexture(VulkanRenderer owner, VkPhysicalDevice physicalDevice, VkDevice device,
                         VulkanTextureDescriptors descriptors, long descriptorSetLayout, VulkanUploader uploader,
                         int width, int height, boolean srgb, boolean linear, int framesInFlight) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.device = Objects.requireNonNull(device, "device");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384) {
            throw new IllegalArgumentException("Dynamic texture size must be between 1 and 16384 pixels.");
        }
        this.width = width;
        this.height = height;
        this.frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        VulkanImage createdImage = null;
        VulkanBuffer createdStaging = null;
        long createdSampler = 0;
        VulkanTextureDescriptors.Allocation createdDescriptor = null;
        ByteBuffer zeros = null;
        try {
            createdImage = VulkanImage.create(physicalDevice, device, width, height, 1,
                    srgb ? VK_FORMAT_B8G8R8A8_SRGB : VK_FORMAT_B8G8R8A8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT, false);
            createdStaging = VulkanBuffer.create(physicalDevice, device,
                    Math.multiplyExact((long) frameBytes, framesInFlight), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            createdSampler = VulkanImage.createSampler(device, linear, false);
            createdDescriptor = descriptors.allocate(descriptorSetLayout);
            writeDescriptor(device, createdDescriptor.descriptorSet(), createdSampler, createdImage.view());
            // Start transparent and shader-readable so a frame can sample before the first update arrives.
            zeros = MemoryUtil.memCalloc(frameBytes);
            uploader.uploadImage(createdImage.image(), width, height, zeros);
        } catch (RuntimeException | Error failure) {
            if (createdDescriptor != null) {
                descriptors.free(createdDescriptor);
            }
            if (createdSampler != 0) {
                vkDestroySampler(device, createdSampler, null);
            }
            if (createdStaging != null) {
                createdStaging.close();
            }
            if (createdImage != null) {
                createdImage.destroy(device);
            }
            throw failure;
        } finally {
            if (zeros != null) {
                MemoryUtil.memFree(zeros);
            }
        }
        image = createdImage;
        staging = createdStaging;
        stagingBytes = createdStaging.map();
        sampler = createdSampler;
        descriptor = createdDescriptor;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    /** Bytes in one tightly packed BGRA frame. */
    public int frameBytes() {
        return frameBytes;
    }

    /** Copies one tightly packed frame into the slot's staging region and records the image update. */
    void record(VkCommandBuffer command, int frameIndex, ByteBuffer bgra) {
        record(command, frameIndex, bgra, null);
    }

    /**
     * Records an update of only the given row bands ({@code start, end} pairs, end exclusive) of a tightly packed
     * frame, or of the whole frame when {@code bands} is null. Rows outside the bands keep the image's contents,
     * so callers pass every row that differs from the frame last recorded.
     */
    void record(VkCommandBuffer command, int frameIndex, ByteBuffer bgra, int[] bands) {
        ensureResourceOpen();
        if (bgra.remaining() != frameBytes) {
            throw new IllegalArgumentException("Dynamic texture update must contain exactly one packed frame.");
        }
        int[] rows = bands == null ? new int[]{0, height} : bands;
        if (rows.length == 0) {
            return;
        }
        long offset = (long) frameIndex * frameBytes;
        long rowBytes = (long) width * 4;
        for (int band = 0; band + 1 < rows.length; band += 2) {
            int start = Math.max(0, rows[band]);
            int end = Math.min(height, rows[band + 1]);
            if (end > start) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(bgra) + start * rowBytes,
                        MemoryUtil.memAddress(stagingBytes) + offset + start * rowBytes, (end - start) * rowBytes);
            }
        }
        // The previous frame may still be sampling the image; the barrier orders that read before this write.
        image.transition(command, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int regions = rows.length / 2;
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(regions, stack);
            int used = 0;
            for (int band = 0; band < regions; band++) {
                int start = Math.max(0, rows[band * 2]);
                int end = Math.min(height, rows[band * 2 + 1]);
                if (end <= start) {
                    continue;
                }
                copy.get(used++).bufferOffset(offset + start * rowBytes).bufferRowLength(0).bufferImageHeight(0)
                        .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                                .baseArrayLayer(0).layerCount(1))
                        .imageOffset(o -> o.set(0, start, 0))
                        .imageExtent(e -> e.set(width, end - start, 1));
            }
            if (used > 0) {
                copy.limit(used);
                vkCmdCopyBufferToImage(command, staging.nativeHandle(), image.image(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            }
        }
        image.transition(command, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
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
        staging.close();
        image.destroy(device);
    }
}
