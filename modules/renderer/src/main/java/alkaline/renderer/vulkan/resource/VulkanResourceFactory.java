package alkaline.renderer.vulkan.resource;

import alkaline.renderer.api.BufferDescriptor;
import alkaline.renderer.api.BufferUsage;
import java.util.Objects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;

public final class VulkanResourceFactory {
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;

    public VulkanResourceFactory(VkPhysicalDevice physicalDevice, VkDevice device) {
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        this.device = Objects.requireNonNull(device, "device");
    }

    public VulkanBuffer createBuffer(BufferDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        requireHostVisible(descriptor);
        return VulkanBuffer.create(physicalDevice, device, descriptor.size(), getUsageFlags(descriptor),
                getRequiredMemoryProperties(descriptor), getPreferredMemoryProperties(descriptor));
    }

    static void requireHostVisible(BufferDescriptor descriptor) {
        if (!descriptor.hostVisible()) {
            throw new IllegalArgumentException(
                    "Direct buffer uploads require host-visible memory; staging transfers are not implemented yet.");
        }
    }

    static int getUsageFlags(BufferDescriptor descriptor) {
        int usageFlags = 0;
        for (BufferUsage usage : descriptor.usages()) {
            usageFlags |= switch (usage) {
                case VERTEX -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                case INDEX -> VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                case UNIFORM -> VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                case STORAGE -> VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                case TRANSFER_SOURCE -> VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
                case TRANSFER_DESTINATION -> VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            };
        }
        return usageFlags;
    }

    static int getRequiredMemoryProperties(BufferDescriptor descriptor) {
        return switch (descriptor.memoryAccess()) {
            case DEVICE_LOCAL -> VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            case HOST_VISIBLE -> VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
            case HOST_COHERENT -> VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        };
    }

    static int getPreferredMemoryProperties(BufferDescriptor descriptor) {
        return descriptor.memoryAccess() == BufferDescriptor.MemoryAccess.DEVICE_LOCAL
                ? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                : VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    }
}
