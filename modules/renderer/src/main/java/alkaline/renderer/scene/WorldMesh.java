package alkaline.renderer.scene;

import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.texture.TexturedMeshData;
import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.image.RgbaImage;

import java.util.Arrays;
import java.util.Objects;

/** Immutable renderer-neutral indexed section mesh, partitioned by material, texture, and render pass. */
public final class WorldMesh {
    private final SectionKey section;
    private final MaterialPass[] passes;
    private final int[] materialIds;
    private final int[] textureIds;
    private final AtlasRegion[] atlasRegions;
    private final float[] vertices;
    private final int[] indices;
    private final int[] firstIndices;
    private final int[] indexCounts;
    private final float[] bounds;
    private final long estimatedBytes;

    WorldMesh(SectionKey section, MaterialPass[] passes, int[] materialIds, int[] textureIds, AtlasRegion[] atlasRegions,
              float[] vertices, int[] indices, int[] firstIndices, int[] indexCounts, float[] bounds) {
        this.section = Objects.requireNonNull(section, "section");
        this.passes = Arrays.copyOf(passes, passes.length);
        this.materialIds = Arrays.copyOf(materialIds, materialIds.length);
        this.textureIds = Arrays.copyOf(textureIds, textureIds.length);
        this.atlasRegions = Arrays.copyOf(atlasRegions, atlasRegions.length);
        this.vertices = Arrays.copyOf(vertices, vertices.length);
        this.indices = Arrays.copyOf(indices, indices.length);
        this.firstIndices = Arrays.copyOf(firstIndices, firstIndices.length);
        this.indexCounts = Arrays.copyOf(indexCounts, indexCounts.length);
        this.bounds = Arrays.copyOf(bounds, bounds.length);
        validateBatches();
        this.estimatedBytes = (long) this.vertices.length * Float.BYTES
                + (long) this.indices.length * Integer.BYTES
                + (long) this.passes.length * (Byte.BYTES + 2 * Integer.BYTES + 4 * Float.BYTES + 2 * Integer.BYTES);
    }

    public SectionKey section() { return section; }
    public int vertexCount() { return vertices.length / TexturedMeshData.FLOATS_PER_VERTEX; }
    public int indexCount() { return indices.length; }
    public long estimatedBytes() { return estimatedBytes; }
    public float minX() { return bounds[0]; }
    public float minY() { return bounds[1]; }
    public float minZ() { return bounds[2]; }
    public float maxX() { return bounds[3]; }
    public float maxY() { return bounds[4]; }
    public float maxZ() { return bounds[5]; }

    public float position(int vertex, int component) {
        checkVertex(vertex);
        if (component < 0 || component >= 3) throw new IndexOutOfBoundsException("Position component must be xyz.");
        return vertices[vertex * TexturedMeshData.FLOATS_PER_VERTEX + component];
    }

    public float uv(int vertex, int component) {
        checkVertex(vertex);
        if (component < 0 || component >= 2) throw new IndexOutOfBoundsException("UV component must be uv.");
        return vertices[vertex * TexturedMeshData.FLOATS_PER_VERTEX + 3 + component];
    }

    public float color(int vertex, int component) {
        checkVertex(vertex);
        if (component < 0 || component >= 4) throw new IndexOutOfBoundsException("Color component must be rgba.");
        return vertices[vertex * TexturedMeshData.FLOATS_PER_VERTEX + 5 + component];
    }

    public int index(int index) {
        if (index < 0 || index >= indices.length) throw new IndexOutOfBoundsException("Index outside mesh.");
        return indices[index];
    }

    public int batchCount() { return passes.length; }
    public MaterialPass batchPass(int batch) { checkBatch(batch); return passes[batch]; }
    public int batchMaterialId(int batch) { checkBatch(batch); return materialIds[batch]; }
    public int batchTextureId(int batch) { checkBatch(batch); return textureIds[batch]; }
    public AtlasRegion batchAtlasRegion(int batch) { checkBatch(batch); return atlasRegions[batch]; }
    public int batchFirstIndex(int batch) { checkBatch(batch); return firstIndices[batch]; }
    public int batchIndexCount(int batch) { checkBatch(batch); return indexCounts[batch]; }

    /** Returns the mesh-local batch ids enabled by a scene view, in stable draw order. */
    public int[] visibleBatchIndices(SceneView view) {
        Objects.requireNonNull(view, "view");
        int visibleCount = 0;
        for (MaterialPass pass : passes) if (view.includes(pass)) visibleCount++;
        int[] visible = new int[visibleCount];
        int cursor = 0;
        for (int batch = 0; batch < passes.length; batch++) {
            if (view.includes(passes[batch])) visible[cursor++] = batch;
        }
        return visible;
    }

    /** Returns one immutable 9-float textured batch suitable for TexturedMeshData vertex layout. */
    public float[] copyVertices() { return Arrays.copyOf(vertices, vertices.length); }
    public int[] copyIndices() { return Arrays.copyOf(indices, indices.length); }

    /** Splits indexed batches by texture id; image resolution stays in the caller's texture registry. */
    public TexturedBatch texturedBatch(int batch, RgbaImage texture) {
        checkBatch(batch);
        Objects.requireNonNull(texture, "texture");
        int firstIndex = firstIndices[batch];
        int count = indexCounts[batch];
        int minimumVertex = Integer.MAX_VALUE;
        int maximumVertex = -1;
        int batchEnd = firstIndices[batch] + indexCounts[batch];
        for (int index = firstIndex; index < batchEnd; index++) {
            int sourceVertex = indices[index];
            minimumVertex = Math.min(minimumVertex, sourceVertex);
            maximumVertex = Math.max(maximumVertex, sourceVertex);
        }
        int localVertexCount = maximumVertex - minimumVertex + 1;
        float[] localVertices = new float[Math.multiplyExact(localVertexCount, TexturedMeshData.FLOATS_PER_VERTEX)];
        System.arraycopy(vertices, minimumVertex * TexturedMeshData.FLOATS_PER_VERTEX,
                localVertices, 0, localVertices.length);
        int[] localIndices = new int[count];
        for (int index = 0; index < count; index++) {
            localIndices[index] = indices[firstIndex + index] - minimumVertex;
        }
        return new TexturedBatch(textureIds[batch], passes[batch], atlasRegions[batch],
                new TexturedMeshData(texture, ImageDisplayOptions.pixelPerfect(), localVertices, localIndices));
    }

    private void validateBatches() {
        int batchCount = passes.length;
        if (materialIds.length != batchCount || textureIds.length != batchCount || atlasRegions.length != batchCount
                || firstIndices.length != batchCount || indexCounts.length != batchCount || bounds.length != 6
                || vertices.length % TexturedMeshData.FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("World mesh arrays have inconsistent dimensions.");
        }
        int expectedFirstIndex = 0;
        int previousPass = -1;
        int previousMaterialId = -1;
        for (int batch = 0; batch < batchCount; batch++) {
            Objects.requireNonNull(passes[batch], "batch pass");
            Objects.requireNonNull(atlasRegions[batch], "batch atlas region");
            if (textureIds[batch] < 0 || materialIds[batch] <= 0 || firstIndices[batch] != expectedFirstIndex
                    || indexCounts[batch] <= 0 || indexCounts[batch] % 3 != 0) {
                throw new IllegalArgumentException("World mesh batches must be valid contiguous indexed ranges.");
            }
            int passOrdinal = passes[batch].ordinal();
            if (passOrdinal != previousPass) previousMaterialId = -1;
            if (passOrdinal < previousPass || (passOrdinal == previousPass && materialIds[batch] <= previousMaterialId)) {
                throw new IllegalArgumentException("World mesh batches must be uniquely grouped by pass and material.");
            }
            previousPass = passOrdinal;
            previousMaterialId = materialIds[batch];
            expectedFirstIndex = Math.addExact(expectedFirstIndex, indexCounts[batch]);
        }
        if (expectedFirstIndex != indices.length) {
            throw new IllegalArgumentException("World mesh batch ranges must cover all indices.");
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("World mesh index is outside the vertex array.");
            }
        }
        for (int offset = 0; offset < vertices.length; offset += TexturedMeshData.FLOATS_PER_VERTEX) {
            for (int component = 0; component < TexturedMeshData.FLOATS_PER_VERTEX; component++) {
                if (!Float.isFinite(vertices[offset + component])) {
                    throw new IllegalArgumentException("World mesh vertex components must be finite.");
                }
            }
            if (vertices[offset + 3] < 0f || vertices[offset + 3] > 1f
                    || vertices[offset + 4] < 0f || vertices[offset + 4] > 1f) {
                throw new IllegalArgumentException("World mesh UV components must be normalized.");
            }
            for (int component = 5; component < TexturedMeshData.FLOATS_PER_VERTEX; component++) {
                if (vertices[offset + component] < 0f || vertices[offset + component] > 1f) {
                    throw new IllegalArgumentException("World mesh colors must be normalized.");
                }
            }
        }
    }

    private void checkBatch(int batch) {
        if (batch < 0 || batch >= passes.length) throw new IndexOutOfBoundsException("Batch outside mesh.");
    }

    private void checkVertex(int vertex) {
        if (vertex < 0 || vertex >= vertexCount()) throw new IndexOutOfBoundsException("Vertex outside mesh.");
    }

    /** Texture lookup is deliberately external; the scene compiler does not own GPU texture objects. */
    public record TexturedBatch(int textureId, MaterialPass pass, AtlasRegion atlasRegion, TexturedMeshData mesh) {
        public TexturedBatch {
            if (textureId < 0) throw new IllegalArgumentException("Texture id must not be negative.");
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(atlasRegion, "atlasRegion");
            Objects.requireNonNull(mesh, "mesh");
        }
    }
}
