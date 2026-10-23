package alkaline.renderer.vulkan.resource;

import java.util.Objects;

/** Immutable device-local vertex/index data uploaded once and drawn across many frames. */
public final class VulkanStaticBuffer extends VulkanTrackedResource implements AutoCloseable {
    private final VulkanRenderer owner;
    private final VulkanBuffer buffer;
    private final long size;

    VulkanStaticBuffer(VulkanRenderer owner, VulkanBuffer buffer) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.size = buffer.size();
    }

    public long size() {
        return size;
    }

    /** GPU address for buffer references; only buffers from createAddressableDeviceBuffer have one. */
    public long deviceAddress() {
        ensureResourceOpen();
        long address = buffer.deviceAddress();
        if (address == 0) {
            throw new IllegalStateException("This buffer was not created addressable.");
        }
        return address;
    }

    long handle() {
        ensureResourceOpen();
        return buffer.nativeHandle();
    }

    boolean belongsTo(VulkanRenderer renderer) {
        return owner == renderer;
    }

    @Override
    public void close() {
        owner.closeStaticBuffer(this);
    }

    void release() {
        requestRelease();
    }

    @Override
    void releaseNative() {
        buffer.close();
    }
}
