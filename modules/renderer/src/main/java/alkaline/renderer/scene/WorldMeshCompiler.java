package alkaline.renderer.scene;

import alkaline.renderer.texture.TexturedMeshData;
import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.world.MeshBuffer;
import alkaline.renderer.world.SectionMesher;

import java.util.ArrayList;
import java.util.Arrays;

final class WorldMeshCompiler {
    private static final int[] QUAD_INDEX_OFFSETS = {0, 1, 2, 5};
    private static final int FLOATS_PER_VERTEX = TexturedMeshData.FLOATS_PER_VERTEX;

    private WorldMeshCompiler() { }

    static WorldMesh compile(WorldSectionInput input, WorldMaterialTable materials, SectionMesher mesher) {
        MeshBuffer source = new MeshBuffer(0);
        // Each quad maps one atlas tile, so faces must not be merged (a merged face would stretch the tile).
        mesher.mesh(input.toSectionData(), materials.mesherTable(), source, false);

        BatchLookup lookup = new BatchLookup();
        ArrayList<BatchData> batches = new ArrayList<>();
        for (MaterialPass pass : MaterialPass.values()) {
            for (int index = 0; index < source.indexCount(pass); index += 6) {
                int firstVertex = source.index(pass, index);
                int materialId = source.vertexComponent(firstVertex, 3);
                BatchData batch = lookup.get(pass, materialId);
                if (batch == null) {
                    batch = new BatchData(pass, materialId, materials.material((char) materialId));
                    lookup.put(batch);
                    batches.add(batch);
                }
                batch.ensureCapacity(4, 6);
                int vertexBase = batch.vertexCount;
                float minU = Float.POSITIVE_INFINITY;
                float maxU = Float.NEGATIVE_INFINITY;
                float minV = Float.POSITIVE_INFINITY;
                float maxV = Float.NEGATIVE_INFINITY;
                for (int corner = 0; corner < QUAD_INDEX_OFFSETS.length; corner++) {
                    int sourceVertex = source.index(pass, index + QUAD_INDEX_OFFSETS[corner]);
                    float u = uvU(source, sourceVertex);
                    float v = uvV(source, sourceVertex);
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
                AtlasRegion region = batch.material.atlasRegion();
                float atlasWidth = region.maxU() - region.minU();
                float atlasHeight = region.maxV() - region.minV();
                for (int corner = 0; corner < QUAD_INDEX_OFFSETS.length; corner++) {
                    int sourceVertex = source.index(pass, index + QUAD_INDEX_OFFSETS[corner]);
                    float x = source.vertexComponent(sourceVertex, 0);
                    float y = source.vertexComponent(sourceVertex, 1);
                    float z = source.vertexComponent(sourceVertex, 2);
                    float localU = normalizedUv(uvU(source, sourceVertex), minU, maxU);
                    float localV = normalizedUv(uvV(source, sourceVertex), minV, maxV);
                    int vertexOffset = (vertexBase + corner) * FLOATS_PER_VERTEX;
                    batch.vertices[vertexOffset] = WorldSectionInput.worldPosition(input.key().x(), input.width(), x);
                    batch.vertices[vertexOffset + 1] = WorldSectionInput.worldPosition(input.key().y(), input.height(), y);
                    batch.vertices[vertexOffset + 2] = WorldSectionInput.worldPosition(input.key().z(), input.depth(), z);
                    batch.vertices[vertexOffset + 3] = region.minU() + localU * atlasWidth;
                    batch.vertices[vertexOffset + 4] = region.minV() + localV * atlasHeight;
                    batch.vertices[vertexOffset + 5] = batch.material.red();
                    batch.vertices[vertexOffset + 6] = batch.material.green();
                    batch.vertices[vertexOffset + 7] = batch.material.blue();
                    batch.vertices[vertexOffset + 8] = 1f;
                }
                batch.vertexCount += 4;
                batch.indices[batch.indexCount++] = vertexBase;
                batch.indices[batch.indexCount++] = vertexBase + 1;
                batch.indices[batch.indexCount++] = vertexBase + 2;
                batch.indices[batch.indexCount++] = vertexBase;
                batch.indices[batch.indexCount++] = vertexBase + 2;
                batch.indices[batch.indexCount++] = vertexBase + 3;
            }
        }

        batches.sort((left, right) -> {
            int passOrder = Integer.compare(left.pass.ordinal(), right.pass.ordinal());
            return passOrder != 0 ? passOrder : Integer.compare(left.materialId, right.materialId);
        });
        int vertexCount = 0;
        int indexCount = 0;
        for (BatchData batch : batches) {
            vertexCount = Math.addExact(vertexCount, batch.vertexCount);
            indexCount = Math.addExact(indexCount, batch.indexCount);
        }
        float[] vertices = new float[Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX)];
        int[] indices = new int[indexCount];
        MaterialPass[] outputPasses = new MaterialPass[batches.size()];
        int[] materialIds = new int[batches.size()];
        int[] textureIds = new int[batches.size()];
        AtlasRegion[] regions = new AtlasRegion[batches.size()];
        int[] firstIndices = new int[batches.size()];
        int[] indexCounts = new int[batches.size()];
        int vertexOffset = 0;
        int indexOffset = 0;
        for (int batchNumber = 0; batchNumber < batches.size(); batchNumber++) {
            BatchData batch = batches.get(batchNumber);
            System.arraycopy(batch.vertices, 0, vertices, vertexOffset * FLOATS_PER_VERTEX,
                    batch.vertexCount * FLOATS_PER_VERTEX);
            for (int localIndex = 0; localIndex < batch.indexCount; localIndex++) {
                indices[indexOffset + localIndex] = vertexOffset + batch.indices[localIndex];
            }
            outputPasses[batchNumber] = batch.pass;
            materialIds[batchNumber] = batch.materialId;
            textureIds[batchNumber] = batch.material.textureId();
            regions[batchNumber] = batch.material.atlasRegion();
            firstIndices[batchNumber] = indexOffset;
            indexCounts[batchNumber] = batch.indexCount;
            vertexOffset += batch.vertexCount;
            indexOffset += batch.indexCount;
        }
        return new WorldMesh(input.key(), outputPasses, materialIds, textureIds, regions, vertices, indices,
                firstIndices, indexCounts, bounds(input, vertices));
    }

    private static float normalizedUv(float coordinate, float minimum, float maximum) {
        float extent = maximum - minimum;
        return extent == 0f ? 0f : (coordinate - minimum) / extent;
    }

    private static float uvU(MeshBuffer mesh, int vertex) {
        return switch (mesh.vertexComponent(vertex, 4)) {
            case 0, 1 -> mesh.vertexComponent(vertex, 2);
            case 2, 3, 4, 5 -> mesh.vertexComponent(vertex, 0);
            default -> throw new AssertionError("Unexpected normal code.");
        };
    }

    private static float uvV(MeshBuffer mesh, int vertex) {
        return switch (mesh.vertexComponent(vertex, 4)) {
            case 0, 1 -> mesh.vertexComponent(vertex, 1);
            case 2, 3 -> mesh.vertexComponent(vertex, 2);
            case 4, 5 -> mesh.vertexComponent(vertex, 1);
            default -> throw new AssertionError("Unexpected normal code.");
        };
    }

    private static float[] bounds(WorldSectionInput input, float[] vertices) {
        if (vertices.length == 0) {
            Aabb sectionBounds = input.worldBounds();
            return new float[]{sectionBounds.minX(), sectionBounds.minY(), sectionBounds.minZ(),
                    sectionBounds.maxX(), sectionBounds.maxY(), sectionBounds.maxZ()};
        }
        float[] result = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int vertex = 0; vertex < vertices.length / FLOATS_PER_VERTEX; vertex++) {
            int offset = vertex * FLOATS_PER_VERTEX;
            for (int axis = 0; axis < 3; axis++) {
                float coordinate = vertices[offset + axis];
                result[axis] = Math.min(result[axis], coordinate);
                result[axis + 3] = Math.max(result[axis + 3], coordinate);
            }
        }
        return result;
    }

    private static final class BatchData {
        private final MaterialPass pass;
        private final int materialId;
        private final WorldMaterial material;
        private float[] vertices = new float[FLOATS_PER_VERTEX * 4];
        private int[] indices = new int[6];
        private int vertexCount;
        private int indexCount;

        private BatchData(MaterialPass pass, int materialId, WorldMaterial material) {
            this.pass = pass;
            this.materialId = materialId;
            this.material = material;
        }

        private void ensureCapacity(int additionalVertices, int additionalIndices) {
            int requiredFloats = Math.multiplyExact(Math.addExact(vertexCount, additionalVertices), FLOATS_PER_VERTEX);
            if (requiredFloats > vertices.length) {
                vertices = Arrays.copyOf(vertices, Math.max(requiredFloats, Math.multiplyExact(vertices.length, 2)));
            }
            int requiredIndices = Math.addExact(indexCount, additionalIndices);
            if (requiredIndices > indices.length) {
                indices = Arrays.copyOf(indices, Math.max(requiredIndices, Math.multiplyExact(indices.length, 2)));
            }
        }
    }

    /** Sparse primitive-key table avoids per-quad map keys and material-count-sized temporary arrays. */
    private static final class BatchLookup {
        private int[] encodedKeys = new int[16];
        private BatchData[] values = new BatchData[16];
        private int size;

        private BatchData get(MaterialPass pass, int materialId) {
            int encodedKey = encode(pass, materialId) + 1;
            int slot = slot(encodedKey, encodedKeys.length);
            while (encodedKeys[slot] != 0) {
                if (encodedKeys[slot] == encodedKey) return values[slot];
                slot = (slot + 1) & (encodedKeys.length - 1);
            }
            return null;
        }

        private void put(BatchData batch) {
            if ((size + 1) * 4 >= encodedKeys.length * 3) grow();
            int encodedKey = encode(batch.pass, batch.materialId) + 1;
            int slot = slot(encodedKey, encodedKeys.length);
            while (encodedKeys[slot] != 0) slot = (slot + 1) & (encodedKeys.length - 1);
            encodedKeys[slot] = encodedKey;
            values[slot] = batch;
            size++;
        }

        private void grow() {
            int[] previousKeys = encodedKeys;
            BatchData[] previousValues = values;
            encodedKeys = new int[Math.multiplyExact(previousKeys.length, 2)];
            values = new BatchData[encodedKeys.length];
            for (int index = 0; index < previousKeys.length; index++) {
                if (previousKeys[index] == 0) continue;
                int slot = slot(previousKeys[index], encodedKeys.length);
                while (encodedKeys[slot] != 0) slot = (slot + 1) & (encodedKeys.length - 1);
                encodedKeys[slot] = previousKeys[index];
                values[slot] = previousValues[index];
            }
        }

        private static int encode(MaterialPass pass, int materialId) {
            return (pass.ordinal() << Character.SIZE) | materialId;
        }

        private static int slot(int key, int capacity) {
            int mixed = key * 0x9e3779b9;
            mixed ^= mixed >>> 16;
            return mixed & (capacity - 1);
        }
    }
}
