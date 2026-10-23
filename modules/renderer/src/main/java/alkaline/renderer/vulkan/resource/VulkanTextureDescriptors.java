package alkaline.renderer.vulkan.resource;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_ERROR_FRAGMENTED_POOL;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkFreeDescriptorSets;

/** Owns growable pages of combined-image-sampler descriptor sets. */
final class VulkanTextureDescriptors implements AutoCloseable {
    private static final int SETS_PER_POOL = 256;

    private final VkDevice device;
    private final List<Long> pools = new ArrayList<>();
    private boolean closed;

    VulkanTextureDescriptors(VkDevice device) {
        this.device = device;
    }

    Allocation allocate(long descriptorSetLayout) {
        ensureOpen();
        for (long pool : pools) {
            Allocation allocation = tryAllocate(pool, descriptorSetLayout);
            if (allocation != null) {
                return allocation;
            }
        }
        long pool = createPool();
        pools.add(pool);
        Allocation allocation = tryAllocate(pool, descriptorSetLayout);
        if (allocation == null) {
            throw new IllegalStateException("A new Vulkan texture descriptor pool could not allocate one set.");
        }
        return allocation;
    }

    void free(Allocation allocation) {
        if (allocation == null || allocation.descriptorSet() == 0) {
            return;
        }
        ensureOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int result = vkFreeDescriptorSets(device, allocation.pool(), stack.longs(allocation.descriptorSet()));
            requireSuccess(result, "vkFreeDescriptorSets texture");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int index = pools.size() - 1; index >= 0; index--) {
            vkDestroyDescriptorPool(device, pools.get(index), null);
        }
        pools.clear();
    }

    private Allocation tryAllocate(long pool, long descriptorSetLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool).pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer address = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, info, address);
            if (result == VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                return null;
            }
            requireSuccess(result, "vkAllocateDescriptorSets texture");
            return new Allocation(pool, address.get(0));
        }
    }

    private long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS_PER_POOL);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(SETS_PER_POOL).pPoolSizes(sizes);
            LongBuffer address = stack.mallocLong(1);
            requireSuccess(vkCreateDescriptorPool(device, info, null, address), "vkCreateDescriptorPool texture");
            return address.get(0);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan texture descriptor allocator is closed.");
        }
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    record Allocation(long pool, long descriptorSet) {
    }
}
