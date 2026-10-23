package alkaline.renderer.vulkan.resource;

import java.util.Objects;

/** Reusable indexed mesh with float32 xyz + uv + rgba vertices and uint32 indices. */
public final class VulkanTexturedMesh extends VulkanTrackedResource implements AutoCloseable {
    private final VulkanRenderer owner;
    private final VulkanBuffer vertices;
    private final VulkanBuffer indices;
    private final int indexCount;

    VulkanTexturedMesh(VulkanRenderer owner, VulkanBuffer vertices, VulkanBuffer indices, int indexCount) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.vertices = Objects.requireNonNull(vertices, "vertices");
        this.indices = Objects.requireNonNull(indices, "indices");
        this.indexCount = indexCount;
    }

    int indexCount() {
        return indexCount;
    }

    long vertexHandle() {
        ensureResourceOpen();
        return vertices.nativeHandle();
    }

    long indexHandle() {
        ensureResourceOpen();
        return indices.nativeHandle();
    }

    boolean belongsTo(VulkanRenderer renderer) {
        return owner == renderer;
    }

    @Override
    public void close() {
        owner.closeTexturedMesh(this);
    }

    void release() {
        requestRelease();
    }

    @Override
    void releaseNative() {
        indices.close();
        vertices.close();
    }
}
