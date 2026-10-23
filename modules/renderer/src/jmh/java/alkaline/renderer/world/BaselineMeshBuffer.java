package alkaline.renderer.world;

import java.util.Arrays;

/** Frozen pre-upgrade output layout used only by JMH to keep the baseline comparable. */
final class BaselineMeshBuffer {
    private int[] vertices;
    private int vertexCount;
    private int[] opaqueIndices;
    private int opaqueIndexCount;
    private int[] cutoutIndices;
    private int cutoutIndexCount;
    private int[] translucentIndices;
    private int translucentIndexCount;

    BaselineMeshBuffer(int initialVertexCapacity) {
        vertices = new int[Math.multiplyExact(initialVertexCapacity, 5)];
        int indexCapacity = Math.multiplyExact(initialVertexCapacity, 6);
        opaqueIndices = new int[indexCapacity];
        cutoutIndices = new int[indexCapacity];
        translucentIndices = new int[indexCapacity];
    }

    void clear() {
        vertexCount = 0;
        opaqueIndexCount = 0;
        cutoutIndexCount = 0;
        translucentIndexCount = 0;
    }

    int vertexCount() {
        return vertexCount;
    }

    int indexCount(MaterialPass pass) {
        return switch (pass) {
            case OPAQUE -> opaqueIndexCount;
            case CUTOUT -> cutoutIndexCount;
            case TRANSLUCENT -> translucentIndexCount;
        };
    }

    int emittedByteCount() {
        return vertexCount * 5 * Integer.BYTES
                + (opaqueIndexCount + cutoutIndexCount + translucentIndexCount) * Integer.BYTES;
    }

    void appendVertex(int x, int y, int z, int materialId, int normalCode) {
        ensureVertexCapacity(vertexCount + 1);
        int offset = vertexCount++ * 5;
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = z;
        vertices[offset + 3] = materialId;
        vertices[offset + 4] = normalCode;
    }

    void appendIndex(int index, MaterialPass pass) {
        switch (pass) {
            case OPAQUE -> {
                ensureIndexCapacity(MaterialPass.OPAQUE, opaqueIndexCount + 1);
                opaqueIndices[opaqueIndexCount++] = index;
            }
            case CUTOUT -> {
                ensureIndexCapacity(MaterialPass.CUTOUT, cutoutIndexCount + 1);
                cutoutIndices[cutoutIndexCount++] = index;
            }
            case TRANSLUCENT -> {
                ensureIndexCapacity(MaterialPass.TRANSLUCENT, translucentIndexCount + 1);
                translucentIndices[translucentIndexCount++] = index;
            }
        }
    }

    private void ensureVertexCapacity(int required) {
        int requiredComponents = Math.multiplyExact(required, 5);
        if (requiredComponents > vertices.length) {
            vertices = Arrays.copyOf(vertices, Math.max(requiredComponents, Math.max(5, vertices.length * 2)));
        }
    }

    private void ensureIndexCapacity(MaterialPass pass, int required) {
        int[] current = switch (pass) {
            case OPAQUE -> opaqueIndices;
            case CUTOUT -> cutoutIndices;
            case TRANSLUCENT -> translucentIndices;
        };
        if (required <= current.length) {
            return;
        }
        int[] expanded = Arrays.copyOf(current, Math.max(required, Math.max(6, current.length * 2)));
        switch (pass) {
            case OPAQUE -> opaqueIndices = expanded;
            case CUTOUT -> cutoutIndices = expanded;
            case TRANSLUCENT -> translucentIndices = expanded;
        }
    }
}
