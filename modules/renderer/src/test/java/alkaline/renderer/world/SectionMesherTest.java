package alkaline.renderer.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionMesherTest {
    private static final MaterialTable MATERIALS = new MaterialTable(
            new MaterialPass[]{null, MaterialPass.OPAQUE, MaterialPass.CUTOUT, MaterialPass.TRANSLUCENT},
            new boolean[]{false, true, false, false});

    private final SectionMesher mesher = new SectionMesher();

    @Test
    void emitsSixOutwardWoundFacesForAnIsolatedVoxel() {
        char[] cells = cells(1, 1, 1);
        set(cells, 1, 1, 1, 0, 0, 0, 1);
        MeshBuffer output = new MeshBuffer(24);

        mesher.mesh(new SectionData(1, 1, 1, cells), MATERIALS, output);

        assertEquals(24, output.vertexCount());
        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(0, output.indexCount(MaterialPass.CUTOUT));
        assertEquals(0, output.indexCount(MaterialPass.TRANSLUCENT));
        assertOutwardWinding(output);
    }

    @Test
    void keepsSharedFacesBetweenDifferentTranslucentMaterials() {
        char[] cells = cells(2, 1, 1);
        set(cells, 2, 1, 1, 0, 0, 0, 3);
        set(cells, 2, 1, 1, 1, 0, 0, 4);
        MaterialTable translucentMaterials = new MaterialTable(
                new MaterialPass[]{null, MaterialPass.OPAQUE, MaterialPass.CUTOUT,
                        MaterialPass.TRANSLUCENT, MaterialPass.TRANSLUCENT},
                new boolean[]{false, true, false, false, false});
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(2, 1, 1, cells), translucentMaterials, output);

        assertEquals(72, output.indexCount(MaterialPass.TRANSLUCENT));
    }

    @Test
    void cullsInternalFacesBetweenSameTranslucentMaterialButRetainsOuterFaces() {
        char[] cells = cells(2, 1, 1);
        set(cells, 2, 1, 1, 0, 0, 0, 3);
        set(cells, 2, 1, 1, 1, 0, 0, 3);
        MaterialTable translucentMaterials = new MaterialTable(
                new MaterialPass[]{null, MaterialPass.OPAQUE, MaterialPass.CUTOUT,
                        MaterialPass.TRANSLUCENT, MaterialPass.TRANSLUCENT},
                new boolean[]{false, true, false, false, false});
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(2, 1, 1, cells), translucentMaterials, output);

        assertEquals(60, output.indexCount(MaterialPass.TRANSLUCENT));
        assertEquals(40, output.vertexCount());
        int[] faceCounts = new int[6];
        for (int vertex = 0; vertex < output.vertexCount(); vertex += 4) {
            faceCounts[output.vertexComponent(vertex, 4)]++;
        }
        assertArrayEquals(new int[]{1, 1, 2, 2, 2, 2}, faceCounts);
    }

    @Test
    void cullsTheSharedFaceBetweenAdjacentOpaqueVoxels() {
        char[] cells = cells(2, 1, 1);
        set(cells, 2, 1, 1, 0, 0, 0, 1);
        set(cells, 2, 1, 1, 1, 0, 0, 1);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(2, 1, 1, cells), MATERIALS, output);

        assertEquals(40, output.vertexCount());
        assertEquals(60, output.indexCount(MaterialPass.OPAQUE));
    }

    @Test
    void oneCellHaloCullsFacesAtEverySectionBoundary() {
        int[][] haloCoordinates = {
                {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        for (int[] halo : haloCoordinates) {
            char[] cells = cells(1, 1, 1);
            set(cells, 1, 1, 1, 0, 0, 0, 1);
            set(cells, 1, 1, 1, halo[0], halo[1], halo[2], 1);
            MeshBuffer output = new MeshBuffer(0);

            mesher.mesh(new SectionData(1, 1, 1, cells), MATERIALS, output);

            assertEquals(20, output.vertexCount(), "halo " + halo[0] + "," + halo[1] + "," + halo[2]);
            assertEquals(30, output.indexCount(MaterialPass.OPAQUE));
        }
    }

    @Test
    void nonOccludingTranslucentNeighborDoesNotHideOpaqueFace() {
        char[] cells = cells(1, 1, 1);
        set(cells, 1, 1, 1, 0, 0, 0, 1);
        set(cells, 1, 1, 1, 1, 0, 0, 3);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(1, 1, 1, cells), MATERIALS, output);

        assertEquals(24, output.vertexCount());
        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
    }

    @Test
    void groupsIndicesByMaterialPassAndRetainsPerFaceMaterialIds() {
        char[] cells = cells(2, 1, 1);
        set(cells, 2, 1, 1, 0, 0, 0, 2);
        set(cells, 2, 1, 1, 1, 0, 0, 3);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(2, 1, 1, cells), MATERIALS, output);

        assertEquals(36, output.indexCount(MaterialPass.CUTOUT));
        assertEquals(36, output.indexCount(MaterialPass.TRANSLUCENT));
        assertEquals(0, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(2, output.vertexComponent(0, 3));
        assertEquals(2, output.vertexComponent(output.index(MaterialPass.CUTOUT, 0), 3));
        assertEquals(3, output.vertexComponent(output.index(MaterialPass.TRANSLUCENT, 0), 3));
        for (int index = 0; index < output.indexCount(MaterialPass.CUTOUT); index++) {
            assertEquals(2, output.vertexComponent(output.index(MaterialPass.CUTOUT, index), 3));
        }
        for (int index = 0; index < output.indexCount(MaterialPass.TRANSLUCENT); index++) {
            assertEquals(3, output.vertexComponent(output.index(MaterialPass.TRANSLUCENT, index), 3));
        }
    }

    @Test
    void emptySectionReturnsWithoutGrowingMeshOrIndexBuffers() {
        MeshBuffer output = new MeshBuffer(0);
        char[] empty = cells(16, 16, 16);
        mesher.mesh(new SectionData(16, 16, 16, empty), MATERIALS, output);

        assertEquals(0, output.vertexCount());
        assertEquals(0, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(0, output.vertexCapacity());
        assertEquals(0, output.indexCapacity(MaterialPass.OPAQUE));
        assertEquals(0, output.emittedByteCount());
    }

    @Test
    void initializesOnlyTheIndexBufferForThePassThatReceivesQuads() {
        MeshBuffer output = new MeshBuffer(24);
        assertEquals(0, output.indexCapacity(MaterialPass.OPAQUE));
        assertEquals(0, output.indexCapacity(MaterialPass.CUTOUT));
        assertEquals(0, output.indexCapacity(MaterialPass.TRANSLUCENT));

        char[] cells = cells(1, 1, 1);
        set(cells, 1, 1, 1, 0, 0, 0, 1);
        mesher.mesh(new SectionData(1, 1, 1, cells), MATERIALS, output);

        assertEquals(24, output.vertexCapacity());
        assertEquals(48, output.indexCapacity(MaterialPass.OPAQUE));
        assertEquals(0, output.indexCapacity(MaterialPass.CUTOUT));
        assertEquals(0, output.indexCapacity(MaterialPass.TRANSLUCENT));
    }

    @Test
    void reusesCapacityAndProducesDeterministicOutputAcrossCalls() {
        char[] cells = cells(1, 1, 1);
        set(cells, 1, 1, 1, 0, 0, 0, 1);
        SectionData section = new SectionData(1, 1, 1, cells);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(section, MATERIALS, output);
        int vertexCapacity = output.vertexCapacity();
        int indexCapacity = output.indexCapacity(MaterialPass.OPAQUE);
        int[] vertices = output.copyVertices();
        int[] indices = output.copyIndices(MaterialPass.OPAQUE);
        mesher.mesh(section, MATERIALS, output);

        assertEquals(vertexCapacity, output.vertexCapacity());
        assertEquals(indexCapacity, output.indexCapacity(MaterialPass.OPAQUE));
        assertArrayEquals(vertices, output.copyVertices());
        assertArrayEquals(indices, output.copyIndices(MaterialPass.OPAQUE));
    }

    @Test
    void mergesSolidPrismFacesAndPreservesOutwardWindingAndExtents() {
        int width = 16;
        int height = 16;
        int depth = 16;
        char[] cells = cells(width, height, depth);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    set(cells, width, height, depth, x, y, z, 1);
                }
            }
        }
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(width, height, depth, cells), MATERIALS, output);

        assertEquals(24, output.vertexCount());
        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(528, output.emittedByteCount());
        for (int face = 0; face < 6; face++) {
            assertFaceWinding(output, face * 4, face);
        }
        assertFaceBounds(output, 0, 0, 0, 0, height, 0, depth);
        assertFaceBounds(output, 1, width, width, 0, height, 0, depth);
        assertFaceBounds(output, 2, 0, width, 0, 0, 0, depth);
        assertFaceBounds(output, 3, 0, width, height, height, 0, depth);
        assertFaceBounds(output, 4, 0, width, 0, height, 0, 0);
        assertFaceBounds(output, 5, 0, width, 0, height, depth, depth);
    }

    @Test
    void doesNotMergeDifferentMaterialIdsAndKeepsPassIdentity() {
        char[] cells = cells(2, 1, 1);
        set(cells, 2, 1, 1, 0, 0, 0, 1);
        set(cells, 2, 1, 1, 1, 0, 0, 2);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(2, 1, 1, cells), MATERIALS, output);

        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(30, output.indexCount(MaterialPass.CUTOUT));
        assertEquals(44, output.vertexCount());
        for (int i = 0; i < output.indexCount(MaterialPass.OPAQUE); i++) {
            assertEquals(1, output.vertexComponent(output.index(MaterialPass.OPAQUE, i), 3));
        }
        for (int i = 0; i < output.indexCount(MaterialPass.CUTOUT); i++) {
            assertEquals(2, output.vertexComponent(output.index(MaterialPass.CUTOUT, i), 3));
        }
    }

    @Test
    void reusesMeshAndMaskCapacityAcrossLargeAndSmallSections() {
        char[] solid = cells(4, 3, 2);
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 4; x++) {
                    set(solid, 4, 3, 2, x, y, z, 1);
                }
            }
        }
        MeshBuffer output = new MeshBuffer(0);
        mesher.mesh(new SectionData(4, 3, 2, solid), MATERIALS, output);
        int vertexCapacity = output.vertexCapacity();
        int indexCapacity = output.indexCapacity(MaterialPass.OPAQUE);
        char[] empty = cells(1, 1, 1);
        mesher.mesh(new SectionData(1, 1, 1, empty), MATERIALS, output);
        assertEquals(0, output.vertexCount());
        assertEquals(0, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(0, output.emittedByteCount());
        mesher.mesh(new SectionData(4, 3, 2, solid), MATERIALS, output);
        assertEquals(vertexCapacity, output.vertexCapacity());
        assertEquals(indexCapacity, output.indexCapacity(MaterialPass.OPAQUE));
    }

    @Test
    void emittedGreedyAreasMatchEveryVisibleUnitFaceForDeterministicMixedSection() {
        int width = 7;
        int height = 5;
        int depth = 4;
        char[] cells = cells(width, height, depth);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int mixed = x * 31 + y * 17 + z * 13 + x * y * 3 + y * z * 5;
                    int materialId = Math.floorMod(mixed, 4);
                    set(cells, width, height, depth, x, y, z, materialId);
                }
            }
        }
        set(cells, width, height, depth, -1, 0, 0, 1);
        set(cells, width, height, depth, width, height - 1, depth - 1, 2);
        SectionData section = new SectionData(width, height, depth, cells);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(section, MATERIALS, output);

        int[][] expectedArea = new int[4][6];
        int[][] offsets = {
                {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int materialId = section.materialIdAt(x, y, z);
                    if (materialId == 0) {
                        continue;
                    }
                    for (int face = 0; face < offsets.length; face++) {
                        int[] offset = offsets[face];
                        int neighborId = section.materialIdAt(x + offset[0], y + offset[1], z + offset[2]);
                        if (neighborId == 0
                                || !(materialId == 3 && neighborId == 3)
                                && !MATERIALS.occludesFaces((char) neighborId)) {
                            expectedArea[materialId][face]++;
                        }
                    }
                }
            }
        }
        int[][] actualArea = new int[4][6];
        for (int firstVertex = 0; firstVertex < output.vertexCount(); firstVertex += 4) {
            int materialId = output.vertexComponent(firstVertex, 3);
            int face = output.vertexComponent(firstVertex, 4);
            int x0 = output.vertexComponent(firstVertex, 0);
            int y0 = output.vertexComponent(firstVertex, 1);
            int z0 = output.vertexComponent(firstVertex, 2);
            int ax = output.vertexComponent(firstVertex + 1, 0) - x0;
            int ay = output.vertexComponent(firstVertex + 1, 1) - y0;
            int az = output.vertexComponent(firstVertex + 1, 2) - z0;
            int bx = output.vertexComponent(firstVertex + 2, 0) - x0;
            int by = output.vertexComponent(firstVertex + 2, 1) - y0;
            int bz = output.vertexComponent(firstVertex + 2, 2) - z0;
            int area = switch (face) {
                case 0, 1 -> Math.abs(ay * bz - az * by);
                case 2, 3 -> Math.abs(az * bx - ax * bz);
                case 4, 5 -> Math.abs(ax * by - ay * bx);
                default -> throw new AssertionError("Unexpected face code: " + face);
            };
            actualArea[materialId][face] += area;
        }
        for (int materialId = 1; materialId < 4; materialId++) {
            for (int face = 0; face < 6; face++) {
                assertEquals(expectedArea[materialId][face], actualArea[materialId][face],
                        "material=" + materialId + " face=" + face);
            }
        }
    }

    @Test
    void sparseAndPartialSolidSectionsStayOnUnitFacePath() {
        char[] cells = cells(16, 16, 16);
        set(cells, 16, 16, 16, 0, 0, 0, 1);
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(16, 16, 16, cells), MATERIALS, output);

        assertEquals(24, output.vertexCount());
        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
    }

    @Test
    void equalVolumeNonsquareSolidSectionStaysOnUnitFacePath() {
        int width = 8;
        int height = 16;
        int depth = 32;
        char[] cells = cells(width, height, depth);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    set(cells, width, height, depth, x, y, z, 1);
                }
            }
        }
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(width, height, depth, cells), MATERIALS, output);

        int boundaryQuads = 2 * (width * height + width * depth + height * depth);
        assertEquals(4096, width * height * depth);
        assertEquals(boundaryQuads * 4, output.vertexCount());
        assertEquals(boundaryQuads * 6, output.indexCount(MaterialPass.OPAQUE));
    }

    @Test
    void onlyGreedyPathReducesFullyOccupiedLargeSolidToBoundaryQuads() {
        int size = 16;
        char[] cells = cells(size, size, size);
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    set(cells, size, size, size, x, y, z, 1);
                }
            }
        }
        MeshBuffer output = new MeshBuffer(0);

        mesher.mesh(new SectionData(size, size, size, cells), MATERIALS, output);

        assertEquals(24, output.vertexCount());
        assertEquals(36, output.indexCount(MaterialPass.OPAQUE));
        assertEquals(528, output.emittedByteCount());
    }

    @Test
    void rejectsInvalidPaddedDataAndUnknownMaterialIds() {
        assertThrows(IllegalArgumentException.class, () -> new SectionData(1, 1, 1, new char[1]));
        char[] cells = cells(1, 1, 1);
        set(cells, 1, 1, 1, 0, 0, 0, 4);
        assertThrows(IllegalArgumentException.class,
                () -> mesher.mesh(new SectionData(1, 1, 1, cells), MATERIALS, new MeshBuffer(0)));
    }

    private static void assertFaceWinding(MeshBuffer mesh, int firstVertex, int face) {
        int[][] expectedNormals = {
                {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        int x0 = mesh.vertexComponent(firstVertex, 0);
        int y0 = mesh.vertexComponent(firstVertex, 1);
        int z0 = mesh.vertexComponent(firstVertex, 2);
        int ax = mesh.vertexComponent(firstVertex + 1, 0) - x0;
        int ay = mesh.vertexComponent(firstVertex + 1, 1) - y0;
        int az = mesh.vertexComponent(firstVertex + 1, 2) - z0;
        int bx = mesh.vertexComponent(firstVertex + 2, 0) - x0;
        int by = mesh.vertexComponent(firstVertex + 2, 1) - y0;
        int bz = mesh.vertexComponent(firstVertex + 2, 2) - z0;
        assertEquals(expectedNormals[face][0] * Math.abs(ay * bz - az * by), ay * bz - az * by);
        assertEquals(expectedNormals[face][1] * Math.abs(az * bx - ax * bz), az * bx - ax * bz);
        assertEquals(expectedNormals[face][2] * Math.abs(ax * by - ay * bx), ax * by - ay * bx);
        assertEquals(face, mesh.vertexComponent(firstVertex, 4));
    }

    private static void assertFaceBounds(MeshBuffer mesh, int face,
                                         int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int first = face * 4;
        int actualMinX = Integer.MAX_VALUE;
        int actualMaxX = Integer.MIN_VALUE;
        int actualMinY = Integer.MAX_VALUE;
        int actualMaxY = Integer.MIN_VALUE;
        int actualMinZ = Integer.MAX_VALUE;
        int actualMaxZ = Integer.MIN_VALUE;
        for (int vertex = first; vertex < first + 4; vertex++) {
            int x = mesh.vertexComponent(vertex, 0);
            int y = mesh.vertexComponent(vertex, 1);
            int z = mesh.vertexComponent(vertex, 2);
            actualMinX = Math.min(actualMinX, x);
            actualMaxX = Math.max(actualMaxX, x);
            actualMinY = Math.min(actualMinY, y);
            actualMaxY = Math.max(actualMaxY, y);
            actualMinZ = Math.min(actualMinZ, z);
            actualMaxZ = Math.max(actualMaxZ, z);
        }
        assertEquals(minX, actualMinX);
        assertEquals(maxX, actualMaxX);
        assertEquals(minY, actualMinY);
        assertEquals(maxY, actualMaxY);
        assertEquals(minZ, actualMinZ);
        assertEquals(maxZ, actualMaxZ);
    }

    private static void assertOutwardWinding(MeshBuffer mesh) {
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex += 4) {
            assertFaceWinding(mesh, vertex, mesh.vertexComponent(vertex, 4));
        }
    }

    private static char[] cells(int width, int height, int depth) {
        return new char[(width + 2) * (height + 2) * (depth + 2)];
    }

    private static void set(char[] cells, int width, int height, int depth,
                            int x, int y, int z, int materialId) {
        int rowStride = width + 2;
        int sliceStride = rowStride * (height + 2);
        cells[(y + 1) * rowStride + (z + 1) * sliceStride + x + 1] = (char) materialId;
    }
}
