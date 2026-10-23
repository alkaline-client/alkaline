package alkaline.renderer.scene;

import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.texture.TexturedMeshData;
import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.world.SectionMesher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSceneTest {
    private static final AtlasRegion FULL = new AtlasRegion(0f, 0f, 1f, 1f);

    @Test
    void mapsWorldUvIntoMaterialAtlasRegionAndExposesTexturedVertexLayout() {
        AtlasRegion region = new AtlasRegion(0.25f, 0.5f, 0.5f, 0.75f);
        WorldMaterialTable materials = materials(
                new WorldMaterial[]{null, material(1, 9, region)},
                new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true});
        WorldMesh mesh = WorldMeshCompiler.compile(section(0, 0, 0, 1, 1, 1, 1), materials, new SectionMesher());

        assertEquals(1, mesh.batchCount());
        assertEquals(9, mesh.batchTextureId(0));
        assertEquals(region, mesh.batchAtlasRegion(0));
        assertEquals(36, mesh.batchIndexCount(0));
        assertEquals(1, mesh.visibleBatchIndices(new SceneView(new Aabb(-1, -1, -1, 2, 2, 2), true, false, false)).length);
        assertEquals(0, mesh.visibleBatchIndices(new SceneView(new Aabb(-1, -1, -1, 2, 2, 2), false, true, true)).length);
        assertThrows(IndexOutOfBoundsException.class, () -> mesh.batchTextureId(1));
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            assertTrue(mesh.uv(vertex, 0) >= region.minU() && mesh.uv(vertex, 0) <= region.maxU());
            assertTrue(mesh.uv(vertex, 1) >= region.minV() && mesh.uv(vertex, 1) <= region.maxV());
            assertEquals(1f, mesh.color(vertex, 3));
        }
        assertTrue(mesh.copyVertices().length == mesh.vertexCount() * TexturedMeshData.FLOATS_PER_VERTEX);
        assertTrue(mesh.texturedBatch(0, texture()).mesh().vertexCount() > 0);
    }

    @Test
    void separatesBatchesAtMaterialPassAndTextureBoundaries() {
        AtlasRegion firstRegion = new AtlasRegion(0f, 0f, 0.5f, 1f);
        AtlasRegion secondRegion = new AtlasRegion(0.5f, 0f, 1f, 1f);
        AtlasRegion thirdRegion = new AtlasRegion(0f, 0.5f, 1f, 1f);
        WorldMaterialTable materials = materials(
                new WorldMaterial[]{null, material(1, 4, firstRegion), material(2, 4, secondRegion), material(3, 7, thirdRegion)},
                new MaterialPass[]{null, MaterialPass.OPAQUE, MaterialPass.OPAQUE, MaterialPass.CUTOUT},
                new boolean[]{false, false, false, false});
        WorldMesh mesh = WorldMeshCompiler.compile(section(0, 0, 0, 3, 1, 1, 1, 2, 3), materials,
                new SectionMesher());

        assertEquals(3, mesh.batchCount());
        assertEquals(MaterialPass.OPAQUE, mesh.batchPass(0));
        assertEquals(MaterialPass.OPAQUE, mesh.batchPass(1));
        assertEquals(MaterialPass.CUTOUT, mesh.batchPass(2));
        assertEquals(1, mesh.batchMaterialId(0));
        assertEquals(2, mesh.batchMaterialId(1));
        assertEquals(3, mesh.batchMaterialId(2));
        assertEquals(4, mesh.batchTextureId(0));
        assertEquals(4, mesh.batchTextureId(1));
        assertEquals(7, mesh.batchTextureId(2));
        assertEquals(firstRegion, mesh.batchAtlasRegion(0));
        assertEquals(secondRegion, mesh.batchAtlasRegion(1));
        assertEquals(thirdRegion, mesh.batchAtlasRegion(2));
        assertEquals(36, mesh.batchIndexCount(0));
        assertEquals(36, mesh.batchIndexCount(1));
        assertEquals(36, mesh.batchIndexCount(2));
    }

    @Test
    void usesHaloForFaceVisibilityAndScalesNegativeSectionCoordinates() {
        WorldMaterialTable materials = materials(
                new WorldMaterial[]{null, material(1, 1, FULL)},
                new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true});
        char[] halo = padded(1, 1, 1, 1);
        halo[index(1, 1, 1, -1, 0, 0)] = 1;
        WorldMesh mesh = WorldMeshCompiler.compile(new WorldSectionInput(-2, 3, -4, 1, 1, 1, halo), materials,
                new SectionMesher());

        assertEquals(30, mesh.indexCount());
        assertEquals(-2f, mesh.minX());
        assertEquals(-1f, mesh.maxX());
        assertEquals(3f, mesh.minY());
        assertEquals(4f, mesh.maxY());
        assertEquals(-4f, mesh.minZ());
        assertEquals(-3f, mesh.maxZ());
        assertEquals(new SectionKey(-2, 3, -4), mesh.section());
    }

    @Test
    void inputCopiesPaddedContentsAndChecksDimensionOverflow() {
        char[] source = padded(1, 1, 1, 1);
        WorldSectionInput input = new WorldSectionInput(0, 0, 0, 1, 1, 1, source);
        source[index(1, 1, 1, 0, 0, 0)] = 0;
        WorldMesh mesh = WorldMeshCompiler.compile(input, materials(
                new WorldMaterial[]{null, material(1, 0, FULL)},
                new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true}), new SectionMesher());
        assertEquals(36, mesh.indexCount());
        assertThrows(IllegalArgumentException.class,
                () -> new WorldSectionInput(0, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, new char[0]));
    }

    @Test
    void cacheReusesUnchangedSectionsInvalidatesEditsAndCullsBeforeCompile() {
        WorldMaterialTable materials = materials(
                new WorldMaterial[]{null, material(1, 2, FULL)},
                new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true});
        SectionMesher mesher = new SectionMesher();
        WorldMeshCache cache = new WorldMeshCache(2, 1_000_000);
        WorldSectionInput near = section(0, 0, 0, 1, 1, 1, 1);
        WorldSectionInput far = section(20, 0, 0, 1, 1, 1, 1);
        WorldMesh first = cache.getOrCompile(near, materials, mesher);
        assertSame(first, cache.getOrCompile(section(0, 0, 0, 1, 1, 1, 1), materials, mesher));

        List<WorldMesh> visible = cache.visibleMeshes(List.of(near, far), materials, mesher,
                new SceneView(new Aabb(-1, -1, -1, 2, 2, 2), true, true, true));
        assertEquals(1, visible.size());
        assertSame(first, visible.get(0));

        List<WorldMesh> passCulled = cache.visibleMeshes(List.of(near), materials, mesher,
                new SceneView(new Aabb(-1, -1, -1, 2, 2, 2), false, true, false));
        assertTrue(passCulled.isEmpty());

        WorldMesh edited = cache.getOrCompile(section(0, 0, 0, 1, 1, 1, 0), materials, mesher);
        assertNotSame(first, edited);
        assertEquals(0, edited.indexCount());
        assertTrue(cache.invalidate(near.key()));
        assertFalse(cache.invalidate(near.key()));
        WorldMesh rebuilt = cache.getOrCompile(near, materials, mesher);
        assertNotSame(first, rebuilt);
        assertTrue(cache.cachedBytes() > 0);
        cache.clear();
        assertEquals(0, cache.cachedSectionCount());
        assertEquals(0, cache.cachedBytes());
    }

    @Test
    void cacheEvictsLeastRecentlyUsedEntryWithinBounds() {
        WorldMaterialTable materials = materials(
                new WorldMaterial[]{null, material(1, 2, FULL)},
                new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true});
        SectionMesher mesher = new SectionMesher();
        WorldMeshCache cache = new WorldMeshCache(1, 1_000_000);
        WorldSectionInput one = section(0, 0, 0, 1, 1, 1, 1);
        WorldSectionInput two = section(2, 0, 0, 1, 1, 1, 1);
        WorldMesh first = cache.getOrCompile(one, materials, mesher);
        cache.getOrCompile(two, materials, mesher);
        assertEquals(1, cache.cachedSectionCount());
        WorldMesh recompiled = cache.getOrCompile(one, materials, mesher);
        assertNotSame(first, recompiled);
    }

    @Test
    void entityBatchAppliesPoseAndAtlasUvWhilePreservingImmutableInputs() {
        AtlasRegion region = new AtlasRegion(0.2f, 0.3f, 0.6f, 0.7f);
        float[] vertices = {
                0, 0, 0, 0.25f, 0.5f, 1, 0.5f, 0.25f, 1,
                1, 0, 0, 0.75f, 0.5f, 0.5f, 1, 0.25f, 1,
                0, 1, 0, 0.25f, 1f, 0.25f, 0.5f, 1, 1
        };
        int[] indices = {0, 1, 2};
        EntityModelSnapshot model = new EntityModelSnapshot(17, region, vertices, indices);
        float[] transform = {
                2, 0, 0, 0,
                0, 3, 0, 0,
                0, 0, 1, 0,
                10, 20, 30, 1
        };
        EntityPose pose = new EntityPose(transform);
        vertices[0] = 99;
        indices[0] = 2;
        transform[12] = -8;

        RgbaImage image = texture();
        List<EntityBatchCompiler.EntityBatch> batches = EntityBatchCompiler.compile(
                List.of(new EntityInput(44, model, pose, MaterialPass.CUTOUT)), id -> image);

        assertEquals(1, batches.size());
        var batch = batches.get(0);
        assertEquals(17, batch.textureId());
        assertEquals(MaterialPass.CUTOUT, batch.pass());
        assertSame(image, batch.mesh().texture());
        float[] compiled = batch.mesh().copyVertices();
        assertEquals(10f, compiled[0]);
        assertEquals(23f, compiled[TexturedMeshData.FLOATS_PER_VERTEX * 2 + 1]);
        assertEquals(0.3f, compiled[3]);
        assertEquals(0.5f, compiled[TexturedMeshData.FLOATS_PER_VERTEX + 3]);
        assertEquals(0.7f, compiled[TexturedMeshData.FLOATS_PER_VERTEX * 2 + 4]);
        assertEquals(0, batch.mesh().copyIndices()[0]);
        assertEquals(10f, pose.copyTransform()[12]);
        assertEquals(0f, model.copyVertices()[0]);
    }

    @Test
    void rejectsInvalidEntityPoseAndSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new EntityPose(new float[15]));
        assertThrows(IllegalArgumentException.class, () -> new EntityPose(new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityModelSnapshot(0, FULL, new float[9], new int[]{0, 1, 2}));
    }

    private static WorldMaterial material(int id, int textureId, AtlasRegion region) {
        return new WorldMaterial(id, textureId, region, 0.75f, 0.8f, 0.9f);
    }

    private static WorldMaterialTable materials(WorldMaterial[] renderMaterials,
                                                MaterialPass[] passes,
                                                boolean[] occludes) {
        return new WorldMaterialTable(renderMaterials, passes, occludes);
    }

    private static WorldSectionInput section(int sectionX, int sectionY, int sectionZ,
                                             int width, int height, int depth, int... materialIds) {
        char[] values = padded(width, height, depth, materialIds);
        return new WorldSectionInput(sectionX, sectionY, sectionZ, width, height, depth, values);
    }

    private static char[] padded(int width, int height, int depth, int... materialIds) {
        if (materialIds.length != width * height * depth) throw new IllegalArgumentException("Wrong test fixture size.");
        char[] result = new char[(width + 2) * (height + 2) * (depth + 2)];
        int cell = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    result[index(width, height, depth, x, y, z)] = (char) materialIds[cell++];
                }
            }
        }
        return result;
    }

    private static int index(int width, int height, int depth, int x, int y, int z) {
        return (y + 1) * (width + 2) + (z + 1) * (width + 2) * (height + 2) + x + 1;
    }

    private static RgbaImage texture() {
        return new RgbaImage(1, 1, new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
    }
}
