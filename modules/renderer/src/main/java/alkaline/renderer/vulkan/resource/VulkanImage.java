package alkaline.renderer.vulkan.resource;

import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;

/**
 * Device-local 2D image, memory, and view with explicit destruction. {@code aliasView} is a second view in the
 * format's UNORM alias (see {@link GammaSpace}), or 0.
 */
record VulkanImage(long image, long memory, long view, int format, int aspect, int layers, int mipLevels,
                   long aliasView) {

    static VulkanImage create(VkPhysicalDevice physicalDevice, VkDevice device, int width, int height, int layers,
                              int format, int usage, int aspect, boolean arrayView) {
        return create(physicalDevice, device, width, height, layers, 1, format, usage, aspect, arrayView);
    }

    static VulkanImage create(VkPhysicalDevice physicalDevice, VkDevice device, int width, int height, int layers,
                              int mipLevels, int format, int usage, int aspect, boolean arrayView) {
        return create(physicalDevice, device, width, height, layers, mipLevels, format, usage, aspect, arrayView,
                false);
    }

    /** With {@code gammaAlias}, an sRGB image is created mutable and also gets a UNORM {@link #aliasView}. */
    static VulkanImage create(VkPhysicalDevice physicalDevice, VkDevice device, int width, int height, int layers,
                              int mipLevels, int format, int usage, int aspect, boolean arrayView,
                              boolean gammaAlias) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (width <= 0 || height <= 0 || layers <= 0 || mipLevels <= 0) {
            throw new IllegalArgumentException("Image dimensions, layer count and mip levels must be positive.");
        }
        long image = 0;
        long memory = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(extent -> extent.set(width, height, 1))
                    .mipLevels(mipLevels).arrayLayers(layers).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            int alias = gammaAlias ? GammaSpace.unormAlias(format) : 0;
            if (alias != 0) {
                // Listing both formats lets drivers keep color compression on a mutable image.
                imageInfo.flags(org.lwjgl.vulkan.VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)
                        .pNext(org.lwjgl.vulkan.VkImageFormatListCreateInfo.calloc(stack)
                                .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO)
                                .pViewFormats(stack.ints(format, alias)));
            }
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreateImage(device, imageInfo, null, address), "vkCreateImage");
            image = address.get(0);

            VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(deviceLocalType(memoryProperties, requirements.memoryTypeBits()));
            requireSuccess(vkAllocateMemory(device, allocation, null, address), "vkAllocateMemory image");
            memory = address.get(0);
            requireSuccess(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");

            long createdImage = image;
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(createdImage)
                    .viewType(arrayView ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .components(c -> c.r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY))
                    .subresourceRange(range -> range.aspectMask(aspect)
                            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers));
            requireSuccess(vkCreateImageView(device, viewInfo, null, address), "vkCreateImageView");
            long view = address.get(0);
            long aliasView = 0;
            if (alias != 0) {
                viewInfo.format(alias);
                int result = vkCreateImageView(device, viewInfo, null, address);
                if (result != VK_SUCCESS) {
                    vkDestroyImageView(device, view, null);
                    requireSuccess(result, "vkCreateImageView alias");
                }
                aliasView = address.get(0);
            }
            return new VulkanImage(image, memory, view, format, aspect, layers, mipLevels, aliasView);
        } catch (RuntimeException | Error failure) {
            if (image != 0) {
                vkDestroyImage(device, image, null);
            }
            if (memory != 0) {
                vkFreeMemory(device, memory, null);
            }
            throw failure;
        }
    }

    /** Depth-only aspect for barriers; combined depth/stencil formats must name both aspects. */
    static int depthBarrierAspect(int depthFormat) {
        return depthFormat == VK_FORMAT_D24_UNORM_S8_UINT || depthFormat == VK_FORMAT_D32_SFLOAT_S8_UINT
                ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    static long createSampler(VkDevice device, boolean linear, boolean repeat) {
        return createSampler(device, linear, repeat, 1);
    }

    /** With several mip levels, blends between levels (nearest texels within a level stay crisp for pixel art). */
    static long createSampler(VkDevice device, boolean linear, boolean repeat, int mipLevels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            int address = repeat ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO).magFilter(filter).minFilter(filter)
                    .mipmapMode(mipLevels > 1 ? org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR
                            : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(address).addressModeV(address).addressModeW(address)
                    .mipLodBias(0f).anisotropyEnable(false).compareEnable(false)
                    .minLod(0f).maxLod(mipLevels - 1).borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);
            LongBuffer handle = stack.mallocLong(1);
            requireSuccess(vkCreateSampler(device, info, null, handle), "vkCreateSampler");
            return handle.get(0);
        }
    }

    void transition(VkCommandBuffer command, int oldLayout, int newLayout, int sourceStage, int destinationStage,
                    int sourceAccess, int destinationAccess) {
        barrier(command, image, aspect, layers, oldLayout, newLayout, sourceStage, destinationStage,
                sourceAccess, destinationAccess);
    }

    static void barrier(VkCommandBuffer command, long image, int aspect, int layers, int oldLayout, int newLayout,
                        int sourceStage, int destinationStage, int sourceAccess, int destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            StructWrites.subresourceRange(barrier.address() + VkImageMemoryBarrier.SUBRESOURCERANGE, aspect, 0, 1, 0,
                    layers);
            vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, barrier);
        }
    }

    void destroy(VkDevice device) {
        if (aliasView != 0) {
            vkDestroyImageView(device, aliasView, null);
        }
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, memory, null);
    }

    private static int deviceLocalType(VkPhysicalDeviceMemoryProperties properties, int typeBits) {
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((typeBits & (1 << index)) != 0
                    && (properties.memoryTypes(index).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                return index;
            }
        }
        throw new IllegalStateException("No device-local memory type satisfies the image.");
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
