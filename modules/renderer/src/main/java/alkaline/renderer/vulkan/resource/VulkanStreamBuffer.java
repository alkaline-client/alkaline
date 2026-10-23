package alkaline.renderer.vulkan.resource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;

/**
 * One persistently mapped, host-coherent buffer split into a region per frame in flight. Per-frame instance,
 * vertex and shader-addressed data is bump-allocated here, so steady-state frames allocate nothing.
 */
final class VulkanStreamBuffer implements AutoCloseable {
    private final VulkanBuffer buffer;
    private final ByteBuffer bytes;
    private final long regionBytes;
    private final long baseAddress;
    private long regionStart;
    private long cursor;
    private long regionEnd;
    private long peakBytes;

    VulkanStreamBuffer(VkPhysicalDevice physicalDevice, VkDevice device, long regionBytes, int regions) {
        if (regionBytes <= 0 || regions <= 0 || regionBytes % 256 != 0) {
            throw new IllegalArgumentException("Stream regions must be positive multiples of 256 bytes.");
        }
        this.regionBytes = regionBytes;
        // Prefer device-local host-visible memory (resizable BAR) and fall back to plain host memory.
        buffer = VulkanBuffer.create(Objects.requireNonNull(physicalDevice), Objects.requireNonNull(device),
                Math.multiplyExact(regionBytes, regions),
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                        | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                        | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                true);
        bytes = buffer.map().order(ByteOrder.nativeOrder());
        baseAddress = buffer.deviceAddress();
    }

    /** Resets the bump pointer to a frame slot whose previous GPU work has completed. */
    void beginRegion(int frameIndex) {
        regionStart = frameIndex * regionBytes;
        cursor = regionStart;
        regionEnd = regionStart + regionBytes;
    }

    /** Returns an absolute byte offset, or -1 when this frame's region is exhausted. */
    long allocate(long size, int alignment) {
        if (size < 0 || alignment <= 0 || Integer.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("Stream allocations need a non-negative size and power-of-two alignment.");
        }
        long start = (cursor + alignment - 1) & -(long) alignment;
        if (start + size > regionEnd) {
            return -1L;
        }
        cursor = start + size;
        peakBytes = Math.max(peakBytes, cursor - regionStart);
        return start;
    }

    long regionBytes() {
        return regionBytes;
    }

    long peakBytes() {
        return peakBytes;
    }

    /** Mapped view of the whole buffer; callers write at absolute offsets returned by {@link #allocate}. */
    ByteBuffer bytes() {
        return bytes;
    }

    long handle() {
        return buffer.nativeHandle();
    }

    long deviceAddress(long offset) {
        return baseAddress + offset;
    }

    @Override
    public void close() {
        buffer.close();
    }
}
