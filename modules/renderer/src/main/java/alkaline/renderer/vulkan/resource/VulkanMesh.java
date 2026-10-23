package alkaline.renderer.vulkan.resource;

import java.util.Objects;

/** Reusable device-local indexed mesh with float32 xyz + rgb vertices and uint32 indices. */
public final class VulkanMesh extends VulkanTrackedResource implements AutoCloseable {
    private final VulkanRenderer owner;
    private final VulkanBuffer vertices;
    private final VulkanBuffer indices;
    private final int indexCount;
    private boolean closed;

    VulkanMesh(VulkanRenderer owner, VulkanBuffer vertices, VulkanBuffer indices, int indexCount) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.vertices = Objects.requireNonNull(vertices, "vertices");
        this.indices = Objects.requireNonNull(indices, "indices");
        this.indexCount = indexCount;
    }

    int indexCount() {
        return indexCount;
    }

    long vertexHandle() {
        ensureOpen();
        return vertices.nativeHandle();
    }

    long indexHandle() {
        ensureOpen();
        return indices.nativeHandle();
    }

    boolean belongsTo(VulkanRenderer renderer) {
        return owner == renderer;
    }

    void ensureOpen() {
        ensureResourceOpen();
        if (closed) {
            throw new IllegalStateException("Vulkan mesh is closed.");
        }
    }

    @Override
    public void close() {
        owner.closeMesh(this);
    }

    void release() {
        if (!closed) {
            closed = true;
            requestRelease();
        }
    }

    @Override
    void releaseNative() {
        indices.close();
        vertices.close();
    }
}
