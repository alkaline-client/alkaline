package alkaline.renderer.scene;

import java.util.Arrays;
import java.util.Objects;

/** Immutable renderer-neutral entity model input: local xyz/uv/rgb/a vertices and indexed triangles. */
public final class EntityModelSnapshot {
    public static final int FLOATS_PER_VERTEX = 9;

    private final int textureId;
    private final AtlasRegion atlasRegion;
    private final float[] vertices;
    private final int[] indices;

    /** Input UVs are normalized model-local coordinates in [0, 1]; baking maps them into atlasRegion once. */
    public EntityModelSnapshot(int textureId, AtlasRegion atlasRegion, float[] vertices, int[] indices) {
        if (textureId < 0) throw new IllegalArgumentException("Texture id must not be negative.");
        this.textureId = textureId;
        this.atlasRegion = Objects.requireNonNull(atlasRegion, "atlasRegion");
        Objects.requireNonNull(vertices, "vertices");
        Objects.requireNonNull(indices, "indices");
        if (vertices.length == 0 || vertices.length % FLOATS_PER_VERTEX != 0
                || vertices.length > 2_000_000 || indices.length == 0 || indices.length % 3 != 0
                || indices.length > 1_000_000) {
            throw new IllegalArgumentException("Entity model requires bounded textured triangle geometry.");
        }
        for (int offset = 0; offset < vertices.length; offset += FLOATS_PER_VERTEX) {
            for (int component = 0; component < FLOATS_PER_VERTEX; component++) {
                if (!Float.isFinite(vertices[offset + component])) {
                    throw new IllegalArgumentException("Entity model vertex values must be finite.");
                }
            }
            if (vertices[offset + 3] < 0 || vertices[offset + 3] > 1
                    || vertices[offset + 4] < 0 || vertices[offset + 4] > 1) {
                throw new IllegalArgumentException("Entity model UV coordinates must be normalized.");
            }
            for (int component = 5; component < FLOATS_PER_VERTEX; component++) {
                if (vertices[offset + component] < 0 || vertices[offset + component] > 1) {
                    throw new IllegalArgumentException("Entity model colors must be normalized.");
                }
            }
        }
        int vertexCount = vertices.length / FLOATS_PER_VERTEX;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Entity model index is outside the vertex array.");
            }
        }
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.indices = Arrays.copyOf(indices, indices.length);
    }

    public int textureId() { return textureId; }
    public AtlasRegion atlasRegion() { return atlasRegion; }
    public int vertexCount() { return vertices.length / FLOATS_PER_VERTEX; }
    public int indexCount() { return indices.length; }
    public int indexAt(int index) {
        if (index < 0 || index >= indices.length) throw new IndexOutOfBoundsException("Entity model index outside snapshot.");
        return indices[index];
    }
    public float[] copyVertices() { return Arrays.copyOf(vertices, vertices.length); }
    public int[] copyIndices() { return Arrays.copyOf(indices, indices.length); }

    void appendTransformed(EntityPose pose, float[] destination, int vertexOffset) {
        for (int vertex = 0; vertex < vertexCount(); vertex++) {
            int source = vertex * FLOATS_PER_VERTEX;
            int target = (vertexOffset + vertex) * FLOATS_PER_VERTEX;
            float x = vertices[source];
            float y = vertices[source + 1];
            float z = vertices[source + 2];
            destination[target] = pose.transformX(x, y, z);
            destination[target + 1] = pose.transformY(x, y, z);
            destination[target + 2] = pose.transformZ(x, y, z);
            destination[target + 3] = atlasRegion.minU() + vertices[source + 3] * (atlasRegion.maxU() - atlasRegion.minU());
            destination[target + 4] = atlasRegion.minV() + vertices[source + 4] * (atlasRegion.maxV() - atlasRegion.minV());
            System.arraycopy(vertices, source + 5, destination, target + 5, 4);
        }
    }
}
