package alkaline.renderer.scene;

import alkaline.renderer.texture.TexturedMeshData;
import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.world.MaterialPass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synchronously compiles immutable entity snapshots into textured CPU batches grouped by pass and texture. */
public final class EntityBatchCompiler {
    private EntityBatchCompiler() { }

    /** Resolves stable scene texture ids using the caller's CPU texture registry. */
    public static List<EntityBatch> compile(Iterable<EntityInput> entities, TextureResolver textures) {
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(textures, "textures");
        Map<BatchKey, BatchData> batches = new LinkedHashMap<>();
        for (EntityInput entity : entities) {
            Objects.requireNonNull(entity, "entity");
            EntityModelSnapshot model = entity.model();
            BatchKey key = new BatchKey(entity.pass(), model.textureId(), model.atlasRegion());
            BatchData batch = batches.computeIfAbsent(key, ignored -> new BatchData());
            int vertexBase = batch.vertexCount;
            batch.ensureCapacity(model.vertexCount(), model.indexCount());
            model.appendTransformed(entity.pose(), batch.vertices, vertexBase);
            for (int index = 0; index < model.indexCount(); index++) {
                batch.indices[batch.indexCount++] = vertexBase + model.indexAt(index);
            }
            batch.vertexCount += model.vertexCount();
        }

        ArrayList<EntityBatch> result = new ArrayList<>(batches.size());
        for (Map.Entry<BatchKey, BatchData> entry : batches.entrySet()) {
            BatchKey key = entry.getKey();
            BatchData data = entry.getValue();
            float[] vertices = Arrays.copyOf(data.vertices,
                    Math.multiplyExact(data.vertexCount, TexturedMeshData.FLOATS_PER_VERTEX));
            int[] indices = Arrays.copyOf(data.indices, data.indexCount);
            RgbaImage texture = Objects.requireNonNull(textures.resolve(key.textureId), "resolved texture");
            result.add(new EntityBatch(key.textureId, key.pass, key.region,
                    new TexturedMeshData(texture, ImageDisplayOptions.pixelPerfect(), vertices, indices)));
        }
        return List.copyOf(result);
    }

    @FunctionalInterface
    public interface TextureResolver {
        RgbaImage resolve(int textureId);
    }

    private record BatchKey(MaterialPass pass, int textureId, AtlasRegion region) { }

    private static final class BatchData {
        private float[] vertices = new float[0];
        private int[] indices = new int[0];
        private int vertexCount;
        private int indexCount;

        private void ensureCapacity(int additionalVertices, int additionalIndices) {
            int totalVertices = Math.addExact(vertexCount, additionalVertices);
            int requiredFloats = Math.multiplyExact(totalVertices, TexturedMeshData.FLOATS_PER_VERTEX);
            int requiredIndices = Math.addExact(indexCount, additionalIndices);
            if (requiredFloats > 2_000_000 || requiredIndices > 1_000_000) {
                throw new IllegalArgumentException("Entity texture batches exceed TexturedMeshData capacity.");
            }
            if (requiredFloats > vertices.length) {
                vertices = Arrays.copyOf(vertices, Math.max(requiredFloats,
                        Math.max(TexturedMeshData.FLOATS_PER_VERTEX * 4, vertices.length * 2)));
            }
            if (requiredIndices > indices.length) {
                indices = Arrays.copyOf(indices, Math.max(requiredIndices, Math.max(6, indices.length * 2)));
            }
        }
    }

    public record EntityBatch(int textureId, MaterialPass pass, AtlasRegion atlasRegion, TexturedMeshData mesh) {
        public EntityBatch {
            if (textureId < 0) throw new IllegalArgumentException("Texture id must not be negative.");
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(atlasRegion, "atlasRegion");
            Objects.requireNonNull(mesh, "mesh");
        }
    }
}
