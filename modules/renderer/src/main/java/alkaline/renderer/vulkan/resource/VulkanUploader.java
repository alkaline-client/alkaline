package alkaline.renderer.vulkan.resource;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkQueueWaitIdle;

/** Owns transient staging uploads submitted through the renderer's graphics queue. */
final class VulkanUploader {
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final long commandPool;
    private final boolean hostImageCopy;

    VulkanUploader(VkPhysicalDevice physicalDevice, VkDevice device, VkQueue graphicsQueue, long commandPool,
                   boolean hostImageCopy) {
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.commandPool = commandPool;
        this.hostImageCopy = hostImageCopy;
    }

    /**
     * Whether static textures are written from the CPU (Vulkan 1.4 host image copy): their images need
     * VK_IMAGE_USAGE_HOST_TRANSFER_BIT and go through {@link #uploadImageFromHost}.
     */
    boolean hostImageCopy() {
        return hostImageCopy;
    }

    /**
     * {@link #uploadImage(long, int, int, int, int, ByteBuffer)} for an image created with host transfer usage:
     * the host moves it to SHADER_READ_ONLY_OPTIMAL and copies each mip level into it. Nothing is submitted, so
     * the GPU keeps working on the frames in flight instead of draining for a staging copy.
     */
    void uploadImageFromHost(long destinationImage, int width, int height, int layers, int mipLevels,
                             ByteBuffer pixels) {
        if (!hostImageCopy) {
            throw new IllegalStateException("Host image copy is not enabled on this device.");
        }
        requireTightlyPacked(width, height, layers, mipLevels, pixels);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var transition = org.lwjgl.vulkan.VkHostImageLayoutTransitionInfo.calloc(1, stack);
            transition.get(0).sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_HOST_IMAGE_LAYOUT_TRANSITION_INFO)
                    .image(destinationImage).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers));
            requireSuccess(org.lwjgl.vulkan.VK14.vkTransitionImageLayout(device, transition),
                    "vkTransitionImageLayout");
            var regions = org.lwjgl.vulkan.VkMemoryToImageCopy.calloc(mipLevels, stack);
            long address = org.lwjgl.system.MemoryUtil.memAddress(pixels);
            for (int level = 0; level < mipLevels; level++) {
                int levelWidth = Math.max(1, width >> level);
                int levelHeight = Math.max(1, height >> level);
                int mip = level;
                regions.get(level).sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_MEMORY_TO_IMAGE_COPY)
                        .pHostPointer(address).memoryRowLength(0).memoryImageHeight(0)
                        .imageSubresource(subresource -> subresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(mip).baseArrayLayer(0).layerCount(layers))
                        .imageOffset(origin -> origin.set(0, 0, 0))
                        .imageExtent(extent -> extent.set(levelWidth, levelHeight, 1));
                address += (long) levelWidth * levelHeight * 4 * layers;
            }
            var copy = org.lwjgl.vulkan.VkCopyMemoryToImageInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK14.VK_STRUCTURE_TYPE_COPY_MEMORY_TO_IMAGE_INFO)
                    .dstImage(destinationImage).dstImageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .pRegions(regions);
            requireSuccess(org.lwjgl.vulkan.VK14.vkCopyMemoryToImage(device, copy), "vkCopyMemoryToImage");
        }
    }

    private static void requireTightlyPacked(int width, int height, int layers, int mipLevels, ByteBuffer pixels) {
        long expected = 0;
        for (int level = 0; level < mipLevels; level++) {
            expected += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4 * layers;
        }
        if (width <= 0 || height <= 0 || layers <= 0 || mipLevels <= 0 || expected != pixels.remaining()) {
            throw new IllegalArgumentException("Texture upload dimensions must match tightly packed RGBA pixels.");
        }
    }

    void upload(VulkanBuffer destination, long destinationOffset, ByteBuffer source) {
        ByteBuffer payload = source.duplicate();
        if (!payload.hasRemaining()) {
            return;
        }
        VulkanBuffer staging = createStaging(payload.remaining());
        try {
            staging.upload(0, payload);
            submitOneTime(command -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack)
                            .srcOffset(0).dstOffset(destinationOffset).size(payload.remaining());
                    vkCmdCopyBuffer(command, staging.nativeHandle(), destination.nativeHandle(), copy);
                }
            });
        } finally {
            staging.close();
        }
    }

    void uploadImage(long destinationImage, int width, int height, ByteBuffer pixels) {
        uploadImage(destinationImage, width, height, 1, pixels);
    }

    /** Uploads tightly packed RGBA layers in order and leaves every layer shader-readable. */
    void uploadImage(long destinationImage, int width, int height, int layers, ByteBuffer pixels) {
        uploadImage(destinationImage, width, height, layers, 1, pixels);
    }

    /**
     * Uploads a mip chain: level 0 for every layer, then level 1 for every layer, and so on, each level tightly
     * packed at half the previous size (minimum 1).
     */
    void uploadImage(long destinationImage, int width, int height, int layers, int mipLevels, ByteBuffer pixels) {
        ByteBuffer payload = pixels.duplicate();
        requireTightlyPacked(width, height, layers, mipLevels, payload);
        VulkanBuffer staging = createStaging(payload.remaining());
        try {
            staging.upload(0, payload);
            submitOneTime(command -> recordImageUpload(command, staging.nativeHandle(), destinationImage, width, height, layers,
                    mipLevels));
        } finally {
            staging.close();
        }
    }

    private VulkanBuffer createStaging(int byteCount) {
        return VulkanBuffer.create(physicalDevice, device, byteCount, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    private void recordImageUpload(VkCommandBuffer command, long sourceBuffer, long destinationImage, int width, int height,
                                   int layers, int mipLevels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(destinationImage).subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers));
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, toTransfer);
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(mipLevels, stack);
            long offset = 0;
            for (int level = 0; level < mipLevels; level++) {
                int levelWidth = Math.max(1, width >> level);
                int levelHeight = Math.max(1, height >> level);
                int mip = level;
                copy.get(level).bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0)
                        .imageSubresource(subresource -> subresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(mip).baseArrayLayer(0).layerCount(layers))
                        .imageOffset(origin -> origin.set(0, 0, 0))
                        .imageExtent(extent -> extent.set(levelWidth, levelHeight, 1));
                offset += (long) levelWidth * levelHeight * 4 * layers;
            }
            vkCmdCopyBufferToImage(command, sourceBuffer, destinationImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            VkImageMemoryBarrier.Buffer toShaderRead = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(destinationImage).subresourceRange(range -> range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers));
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, null, toShaderRead);
        }
    }

    private void submitOneTime(CommandRecorder recorder) {
        VkCommandBuffer command = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
                PointerBuffer address = stack.mallocPointer(1);
                requireSuccess(vkAllocateCommandBuffers(device, allocate, address), "vkAllocateCommandBuffers staging");
                command = new VkCommandBuffer(address.get(0), device);
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                requireSuccess(vkBeginCommandBuffer(command, begin), "vkBeginCommandBuffer staging");
                recorder.record(command);
                requireSuccess(vkEndCommandBuffer(command), "vkEndCommandBuffer staging");
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(command.address()));
                requireSuccess(vkQueueSubmit(graphicsQueue, submit, 0L), "vkQueueSubmit staging");
                requireSuccess(vkQueueWaitIdle(graphicsQueue), "vkQueueWaitIdle staging");
            }
        } finally {
            if (command != null) {
                PointerBuffer commandAddress = memAllocPointer(1);
                try {
                    commandAddress.put(0, command.address());
                    vkFreeCommandBuffers(device, commandPool, commandAddress);
                } finally {
                    memFree(commandAddress);
                }
            }
        }
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    @FunctionalInterface
    private interface CommandRecorder {
        void record(VkCommandBuffer command);
    }
}
