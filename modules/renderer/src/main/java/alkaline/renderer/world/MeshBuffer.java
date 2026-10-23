package alkaline.renderer.world;

import java.util.Arrays;
import java.util.Objects;

/** Reusable primitive mesh storage with one contiguous index stream per material pass. */
public final class MeshBuffer {
    private static final int PACKED_COMPONENTS_PER_VERTEX = 4;
    private static final int PUBLIC_COMPONENTS_PER_VERTEX = 5;

    private int[] vertices;
    private int vertexCount;
    private int[] opaqueIndices;
    private int opaqueIndexCount;
    private int[] cutoutIndices;
    private int cutoutIndexCount;
    private int[] translucentIndices;
    private int translucentIndexCount;
    private char[] faceMask = new char[0];

    public MeshBuffer(int initialVertexCapacity) {
        if (initialVertexCapacity < 0) {
            throw new IllegalArgumentException("Initial vertex capacity must not be negative.");
        }
        vertices = new int[Math.multiplyExact(initialVertexCapacity, PACKED_COMPONENTS_PER_VERTEX)];
        opaqueIndices = new int[0];
        cutoutIndices = new int[0];
        translucentIndices = new int[0];
    }

    public void clear() {
        vertexCount = 0;
        opaqueIndexCount = 0;
        cutoutIndexCount = 0;
        translucentIndexCount = 0;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount(MaterialPass pass) {
        return switch (Objects.requireNonNull(pass, "pass")) {
            case OPAQUE -> opaqueIndexCount;
            case CUTOUT -> cutoutIndexCount;
            case TRANSLUCENT -> translucentIndexCount;
        };
    }

    /** Returns a decoded component: x, y, z, material id, or face-normal code (component 0–4). */
    public int vertexComponent(int vertexIndex, int component) {
        if (vertexIndex < 0 || vertexIndex >= vertexCount
                || component < 0 || component >= PUBLIC_COMPONENTS_PER_VERTEX) {
            throw new IndexOutOfBoundsException("Vertex component is outside the written mesh.");
        }
        int offset = vertexIndex * PACKED_COMPONENTS_PER_VERTEX;
        if (component < 3) {
            return vertices[offset + component];
        }
        int packedMaterialAndNormal = vertices[offset + 3];
        return component == 3 ? packedMaterialAndNormal & 0xffff : packedMaterialAndNormal >>> 16;
    }

    public int index(MaterialPass pass, int indexIndex) {
        int count = indexCount(pass);
        if (indexIndex < 0 || indexIndex >= count) {
            throw new IndexOutOfBoundsException("Index is outside the written material pass.");
        }
        return indices(pass)[indexIndex];
    }

    public int vertexCapacity() {
        return vertices.length / PACKED_COMPONENTS_PER_VERTEX;
    }

    public int indexCapacity(MaterialPass pass) {
        return indices(pass).length;
    }

    /** Number of bytes occupied by the written vertices and indices, excluding spare capacity. */
    public int emittedByteCount() {
        return Math.addExact(Math.multiplyExact(vertexCount, PACKED_COMPONENTS_PER_VERTEX * Integer.BYTES),
                Math.multiplyExact(totalIndexCount(), Integer.BYTES));
    }

    public int[] copyVertices() {
        int[] copy = new int[Math.multiplyExact(vertexCount, PUBLIC_COMPONENTS_PER_VERTEX)];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int packedOffset = vertex * PACKED_COMPONENTS_PER_VERTEX;
            int publicOffset = vertex * PUBLIC_COMPONENTS_PER_VERTEX;
            copy[publicOffset] = vertices[packedOffset];
            copy[publicOffset + 1] = vertices[packedOffset + 1];
            copy[publicOffset + 2] = vertices[packedOffset + 2];
            int materialAndNormal = vertices[packedOffset + 3];
            copy[publicOffset + 3] = materialAndNormal & 0xffff;
            copy[publicOffset + 4] = materialAndNormal >>> 16;
        }
        return copy;
    }

    public int[] copyIndices(MaterialPass pass) {
        return Arrays.copyOf(indices(pass), indexCount(pass));
    }

    void appendVertex(int x, int y, int z, int materialId, int normalCode) {
        if ((materialId & ~0xffff) != 0 || normalCode < 0 || normalCode > 7) {
            throw new IllegalArgumentException("Material id or normal code cannot be represented in a packed vertex.");
        }
        ensureVertexCapacity(Math.addExact(vertexCount, 1));
        int offset = vertexCount++ * PACKED_COMPONENTS_PER_VERTEX;
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = z;
        vertices[offset + 3] = materialId | normalCode << 16;
    }

    void appendIndex(int index, MaterialPass pass) {
        int required = Math.addExact(indexCount(pass), 1);
        ensureIndexCapacity(pass, required);
        switch (pass) {
            case OPAQUE -> opaqueIndices[opaqueIndexCount++] = index;
            case CUTOUT -> cutoutIndices[cutoutIndexCount++] = index;
            case TRANSLUCENT -> translucentIndices[translucentIndexCount++] = index;
        }
    }

    char[] faceMask(int requiredCells) {
        if (requiredCells > faceMask.length) {
            int grown = Math.max(requiredCells, Math.max(16, faceMask.length * 2));
            faceMask = Arrays.copyOf(faceMask, grown);
        }
        return faceMask;
    }

    private int totalIndexCount() {
        return opaqueIndexCount + cutoutIndexCount + translucentIndexCount;
    }

    private int[] indices(MaterialPass pass) {
        return switch (Objects.requireNonNull(pass, "pass")) {
            case OPAQUE -> opaqueIndices;
            case CUTOUT -> cutoutIndices;
            case TRANSLUCENT -> translucentIndices;
        };
    }

    private void ensureVertexCapacity(int requiredVertices) {
        int requiredComponents = Math.multiplyExact(requiredVertices, PACKED_COMPONENTS_PER_VERTEX);
        if (requiredComponents > vertices.length) {
            int grownComponents = Math.max(requiredComponents,
                    Math.max(PACKED_COMPONENTS_PER_VERTEX, vertices.length * 2));
            vertices = Arrays.copyOf(vertices, grownComponents);
        }
    }

    private void ensureIndexCapacity(MaterialPass pass, int requiredIndices) {
        int[] current = indices(pass);
        if (requiredIndices > current.length) {
            int[] expanded = Arrays.copyOf(current, Math.max(requiredIndices, Math.max(6, current.length * 2)));
            switch (pass) {
                case OPAQUE -> opaqueIndices = expanded;
                case CUTOUT -> cutoutIndices = expanded;
                case TRANSLUCENT -> translucentIndices = expanded;
            }
        }
    }
}
