package alkaline.renderer.vulkan.resource;

import alkaline.renderer.api.RenderBuffer;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkFlushMappedMemoryRanges;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;

public final class VulkanBuffer implements RenderBuffer {
    private final VkDevice device;
    private final long buffer;
    private final long memory;
    private final long size;
    private final long allocationSize;
    private final boolean hostVisible;
    private final boolean coherent;
    private final long nonCoherentAtomSize;
    private ByteBuffer mapped;
    private long deviceAddress;
    private boolean closed;

    VulkanBuffer(VkDevice device, long buffer, long memory, long size, long allocationSize,
                         boolean hostVisible, boolean coherent, long nonCoherentAtomSize) {
        this.device = device;
        this.buffer = buffer;
        this.memory = memory;
        this.size = size;
        this.allocationSize = allocationSize;
        this.hostVisible = hostVisible;
        this.coherent = coherent;
        this.nonCoherentAtomSize = nonCoherentAtomSize;
    }

    public static VulkanBuffer create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            long size,
            int usage,
            int requiredMemoryProperties,
            int preferredMemoryProperties
    ) {
        return create(physicalDevice, device, size, usage, requiredMemoryProperties, preferredMemoryProperties, false);
    }

    /** Creates a buffer; deviceAddress also requests SHADER_DEVICE_ADDRESS usage and allocation flags. */
    static VulkanBuffer create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            long size,
            int usage,
            int requiredMemoryProperties,
            int preferredMemoryProperties,
            boolean deviceAddress
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        validateSize(size);
        long buffer = 0;
        long memory = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(deviceAddress ? usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer bufferAddress = stack.mallocLong(1);
            requireSuccess(vkCreateBuffer(device, bufferInfo, null, bufferAddress), "vkCreateBuffer");
            buffer = bufferAddress.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
            VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);
            MemoryType memoryType = findMemoryType(
                    memoryProperties,
                    requirements.memoryTypeBits(),
                    requiredMemoryProperties,
                    preferredMemoryProperties
            );

            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType.index());
            if (deviceAddress) {
                allocation.pNext(VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).address());
            }
            LongBuffer memoryAddress = stack.mallocLong(1);
            requireSuccess(vkAllocateMemory(device, allocation, null, memoryAddress), "vkAllocateMemory");
            memory = memoryAddress.get(0);
            requireSuccess(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            long address = 0L;
            if (deviceAddress) {
                address = VK12.vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO).buffer(buffer));
                if (address == 0L) {
                    throw new IllegalStateException("vkGetBufferDeviceAddress returned a null address.");
                }
            }
            VulkanBuffer created = new VulkanBuffer(device, buffer, memory, size, requirements.size(),
                    memoryType.hostVisible(), memoryType.coherent(), deviceProperties.limits().nonCoherentAtomSize());
            created.deviceAddress = address;
            return created;
        } catch (RuntimeException | Error failure) {
            if (buffer != 0) {
                vkDestroyBuffer(device, buffer, null);
            }
            if (memory != 0) {
                vkFreeMemory(device, memory, null);
            }
            throw failure;
        }
    }

    @Override
    public synchronized long nativeHandle() {
        ensureOpen();
        return buffer;
    }

    @Override
    public synchronized long size() {
        return size;
    }

    /** Shader-visible address, or zero when the buffer was not created for device addressing. */
    synchronized long deviceAddress() {
        ensureOpen();
        return deviceAddress;
    }

    public synchronized boolean isHostVisible() {
        return hostVisible;
    }

    public synchronized boolean isHostCoherent() {
        return coherent;
    }

    public synchronized ByteBuffer map() {
        ensureOpen();
        if (!hostVisible) {
            throw new IllegalStateException("Buffer memory is not host-visible.");
        }
        if (mapped == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer address = stack.mallocPointer(1);
                requireSuccess(vkMapMemory(device, memory, 0, allocationSize, 0, address), "vkMapMemory");
                mapped = memByteBuffer(address.get(0), Math.toIntExact(size));
            }
        }
        return mapped.duplicate().clear();
    }

    @Override
    public synchronized void upload(long offset, ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        ensureOpen();
        if (offset < 0 || offset > size || data.remaining() > size - offset) {
            throw new IndexOutOfBoundsException("Upload range exceeds the Vulkan buffer.");
        }
        if (!data.hasRemaining()) {
            return;
        }
        ByteBuffer destination = map();
        destination.position(Math.toIntExact(offset));
        destination.put(data.duplicate());
        if (!coherent && data.hasRemaining()) {
            flush(offset, data.remaining());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mapped != null) {
            vkUnmapMemory(device, memory);
            mapped = null;
        }
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }

    static void validateSize(long size) {
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer size must be between 1 and Integer.MAX_VALUE bytes.");
        }
    }

    private void flush(long offset, long length) {
        long atomStart = offset - offset % nonCoherentAtomSize;
        long requestedEnd = Math.addExact(offset, length);
        long roundedEnd = Math.multiplyExact(
                Math.floorDiv(Math.addExact(requestedEnd, nonCoherentAtomSize - 1), nonCoherentAtomSize),
                nonCoherentAtomSize
        );
        long flushSize = roundedEnd >= allocationSize
                ? org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE
                : roundedEnd - atomStart;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                    .memory(memory)
                    .offset(atomStart)
                    .size(flushSize);
            requireSuccess(vkFlushMappedMemoryRanges(device, range), "vkFlushMappedMemoryRanges");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Vulkan buffer is closed.");
        }
    }

    private static MemoryType findMemoryType(
            VkPhysicalDeviceMemoryProperties properties,
            int typeBits,
            int required,
            int preferred
    ) {
        MemoryType firstCompatible = null;
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            if ((typeBits & (1 << index)) == 0) {
                continue;
            }
            int flags = properties.memoryTypes(index).propertyFlags();
            if ((flags & required) != required) {
                continue;
            }
            MemoryType type = new MemoryType(index, (flags & preferred) == preferred,
                    (flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0,
                    (flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0);
            if (type.preferred()) {
                return type;
            }
            if (firstCompatible == null) {
                firstCompatible = type;
            }
        }
        if (firstCompatible == null) {
            throw new IllegalStateException("No Vulkan memory type satisfies required property flags.");
        }
        return firstCompatible;
    }

    private static void requireSuccess(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    private record MemoryType(int index, boolean preferred, boolean hostVisible, boolean coherent) {
    }
}
