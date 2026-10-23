package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.renderer.world.vanilla.Blocks189;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ChunkMesherTest {
    private static final byte SKY = (byte) 0xF0;
    private static final BlockPalette PALETTE = BlockPalette.load(new TestBlockAssets());
    private static final char ICE = VanillaBlocks.state(79, 0);
    private final ChunkMesher mesher = new ChunkMesher();
    private final char[] ids = new char[BlockSource.VOLUME];
    private final byte[] light = new byte[BlockSource.VOLUME];

    private static int index(int x, int y, int z) {
        return BlockSource.index(x, y, z);
    }

    private void set(int x, int y, int z, char block) {
        ids[index(x, y, z)] = block;
    }

    /** Full sky light in every non-occluding cell, none inside opaque blocks, as vanilla stores it. */
    private ChunkMesh mesh(boolean smooth) {
        for (int cell = 0; cell < ids.length; cell++) {
            light[cell] = Blocks189.opaqueCube(ids[cell] >> 4) ? 0 : SKY;
        }
        return mesher.mesh(0, 0, 0, ids, light, PALETTE, smooth);
    }

    private static int count(ChunkMesh mesh, MaterialPass pass, int face) {
        return mesh.rangeCount(ChunkMesh.range(pass, face));
    }

    private static int word(ChunkMesh mesh, int quad, int vertex, int word) {
        return mesh.words()[quad * ChunkMesh.INTS_PER_QUAD + vertex * ChunkMesh.INTS_PER_VERTEX + word];
    }

    /** Vertex position in blocks, section-local. */
    private static float[] position(ChunkMesh mesh, int quad, int vertex) {
        int w0 = word(mesh, quad, vertex, 0);
        int w1 = word(mesh, quad, vertex, 1);
        return new float[]{(w0 & 0x3FFF) / 512f - 8f, (w0 >> 14 & 0x3FFF) / 512f - 8f, (w1 & 0x3FFF) / 512f - 8f};
    }

    private static int red(ChunkMesh mesh, int quad, int vertex) {
        return word(mesh, quad, vertex, 3) & 0xFF;
    }

    private static int green(ChunkMesh mesh, int quad, int vertex) {
        return word(mesh, quad, vertex, 3) >> 8 & 0xFF;
    }

    /** Sky light in lightmap units (1/16 level). */
    private static int sky(ChunkMesh mesh, int quad, int vertex) {
        return word(mesh, quad, vertex, 3) >>> 24;
    }

    private static int block(ChunkMesh mesh, int quad, int vertex) {
        return word(mesh, quad, vertex, 2) >>> 24;
    }

    private static int layer(ChunkMesh mesh, int quad) {
        return word(mesh, quad, 0, 2) >> 13 & 0x7FF;
    }

    private static int vertexAt(ChunkMesh mesh, int quad, float x, float y, float z) {
        for (int vertex = 0; vertex < 4; vertex++) {
            float[] p = position(mesh, quad, vertex);
            if (p[0] == x && p[1] == y && p[2] == z) {
                return vertex;
            }
        }
        return -1;
    }

    private static int topQuadOf(ChunkMesh mesh, int x, int y, int z) {
        int range = ChunkMesh.range(MaterialPass.OPAQUE, 3);
        for (int quad = mesh.rangeStart(range); quad < mesh.rangeStart(range) + mesh.rangeCount(range); quad++) {
            if (vertexAt(mesh, quad, x, y + 1, z) >= 0 && vertexAt(mesh, quad, x + 1, y + 1, z + 1) >= 0) {
                return quad;
            }
        }
        throw new AssertionError("no top quad covers block " + x + "," + y + "," + z);
    }

    @Test
    void emptySectionHasNoQuadsAndSeesThrough() {
        ChunkMesh mesh = mesh(true);
        assertTrue(mesh.isEmpty());
        assertEquals(ChunkMesh.ALL_CONNECTED, mesh.visibility());
    }

    @Test
    void singleBlockEmitsSixFaces() {
        set(3, 4, 5, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(true);
        assertEquals(6, mesh.quadCount());
        for (int face = 0; face < 6; face++) {
            assertEquals(1, count(mesh, MaterialPass.OPAQUE, face));
        }
    }

    @Test
    void uniformlyLitLayerMergesIntoOneQuadPerFace() {
        for (int z = -BlockSource.HALO; z < 16 + BlockSource.HALO; z++) {
            for (int x = -BlockSource.HALO; x < 16 + BlockSource.HALO; x++) {
                set(x, 0, z, VanillaBlocks.STONE);
            }
        }
        ChunkMesh mesh = mesh(true);
        assertEquals(1, count(mesh, MaterialPass.OPAQUE, 3), "one merged top");
        assertEquals(1, count(mesh, MaterialPass.OPAQUE, 2), "one merged bottom");
        assertEquals(0, count(mesh, MaterialPass.OPAQUE, 0), "sides are hidden by the halo");
    }

    @Test
    void neighborsInTheHaloHideBoundaryFaces() {
        set(15, 0, 0, VanillaBlocks.STONE);
        set(16, 0, 0, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(true);
        assertEquals(0, count(mesh, MaterialPass.OPAQUE, 1));
        assertEquals(5, mesh.quadCount());
    }

    @Test
    void quadsWindCounterClockwiseFromOutside() {
        set(5, 5, 5, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(false);
        for (int face = 0; face < 6; face++) {
            int quad = mesh.rangeStart(ChunkMesh.range(MaterialPass.OPAQUE, face));
            float[] p0 = position(mesh, quad, 0);
            float[] p1 = position(mesh, quad, 1);
            float[] p2 = position(mesh, quad, 2);
            float[] a = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
            float[] b = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
            float[] normal = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            assertEquals((face & 1) == 1 ? 1f : -1f, Math.signum(normal[face >> 1]), "face " + face);
        }
    }

    @Test
    void smoothLightingFollowsVanillaCornerOcclusion() {
        set(5, 0, 5, VanillaBlocks.STONE);
        set(4, 1, 5, VanillaBlocks.STONE);
        set(5, 1, 4, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(true);
        int quad = topQuadOf(mesh, 5, 0, 5);
        int corner = vertexAt(mesh, quad, 5, 1, 5);
        assertEquals((int) (255 * 0.6F), red(mesh, quad, corner));
        // Opaque samples have no light; vanilla replaces them with the center, so the corner stays fully lit.
        assertEquals(240, sky(mesh, quad, corner));
        assertEquals(0, block(mesh, quad, corner));
        assertEquals(255, red(mesh, quad, vertexAt(mesh, quad, 6, 1, 6)));
    }

    @Test
    void cornerFallsBackToTheSideWhenBothCellsBeyondBlockLight() {
        set(5, 0, 5, VanillaBlocks.STONE);
        set(4, 1, 5, VanillaBlocks.STONE);
        set(5, 1, 4, VanillaBlocks.STONE);
        set(4, 2, 5, VanillaBlocks.STONE);
        set(5, 2, 4, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(true);
        int quad = topQuadOf(mesh, 5, 0, 5);
        assertEquals((int) (255 * 0.4F), red(mesh, quad, vertexAt(mesh, quad, 5, 1, 5)));
    }

    @Test
    void lightAveragesFourSamplesInLightmapUnits() {
        set(5, 0, 5, VanillaBlocks.STONE);
        mesh(true);
        light[index(4, 1, 4)] = (byte) (3 << 4 | 8);
        ChunkMesh mesh = mesher.mesh(0, 0, 0, ids, light, PALETTE, true);
        int quad = topQuadOf(mesh, 5, 0, 5);
        int corner = vertexAt(mesh, quad, 5, 1, 5);
        assertEquals((15 + 15 + 3 + 15) * 16 / 4, sky(mesh, quad, corner));
        assertEquals(8 * 16 / 4, block(mesh, quad, corner));
        assertEquals(240, sky(mesh, quad, vertexAt(mesh, quad, 6, 1, 6)));
    }

    @Test
    void flatLightingTakesTheCellInFrontOfTheFace() {
        set(5, 0, 5, VanillaBlocks.STONE);
        mesh(false);
        light[index(5, 1, 5)] = (byte) (7 << 4 | 2);
        ChunkMesh mesh = mesher.mesh(0, 0, 0, ids, light, PALETTE, false);
        int quad = topQuadOf(mesh, 5, 0, 5);
        for (int vertex = 0; vertex < 4; vertex++) {
            assertEquals(7 * 16, sky(mesh, quad, vertex));
            assertEquals(2 * 16, block(mesh, quad, vertex));
            assertEquals(255, red(mesh, quad, vertex));
        }
    }

    @Test
    void grassSidesDrawATintedOverlayOnTheSameCorners() {
        set(5, 5, 5, VanillaBlocks.GRASS);
        ChunkMesh mesh = mesh(false);
        assertEquals(1, count(mesh, MaterialPass.OPAQUE, 4), "base north face");
        assertEquals(1, count(mesh, MaterialPass.CUTOUT, 4), "overlay north face");
        assertEquals(0, count(mesh, MaterialPass.CUTOUT, 3), "no overlay on top");
        int base = mesh.rangeStart(ChunkMesh.range(MaterialPass.OPAQUE, 4));
        int overlay = mesh.rangeStart(ChunkMesh.range(MaterialPass.CUTOUT, 4));
        for (int vertex = 0; vertex < 4; vertex++) {
            assertTrue(Arrays.equals(position(mesh, base, vertex), position(mesh, overlay, vertex)));
        }
        assertEquals("blocks/grass_side_overlay", PALETTE.textures().get(layer(mesh, overlay)));
        // Untinted base keeps the north shade; the overlay multiplies it by the grass color.
        assertEquals(204, green(mesh, base, 0));
        assertEquals((int) (204 * (1.0F * ((BlockPalette.GRASS_COLOR >> 8 & 255) / 255.0F))), green(mesh, overlay, 0));
    }

    @Test
    void translucentFacesFacingSeveralWaysStayPerCellForSorting() {
        set(0, 0, 0, ICE);
        set(1, 0, 0, ICE);
        set(0, 1, 0, VanillaBlocks.OAK_LEAVES);
        ChunkMesh mesh = mesh(false);
        // Ice hides its faces against ice; every other face of the 2x1 box is its own quad: 10.
        assertEquals(10, mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE));
        assertEquals(10, mesh.translucentCells().length);
        assertEquals(1, count(mesh, MaterialPass.CUTOUT, 2), "leaves keep their bottom face above ice");
    }

    @Test
    void translucentFacesFacingOneWayNeedNoSorting() {
        for (int z = -BlockSource.HALO; z < 16 + BlockSource.HALO; z++) {
            for (int x = -BlockSource.HALO; x < 16 + BlockSource.HALO; x++) {
                for (int y = -BlockSource.HALO; y < 5; y++) {
                    set(x, y, z, VanillaBlocks.STONE);
                }
                set(x, 5, z, ICE);
            }
        }
        ChunkMesh mesh = mesh(false);
        assertEquals(256, mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE));
        assertEquals(0, mesh.translucentCells().length, "one direction keeps a static order");
    }

    @Test
    void stillWaterHasAFlatSurfaceBelowTheBlockTop() {
        for (int z = -BlockSource.HALO; z < 16 + BlockSource.HALO; z++) {
            for (int x = -BlockSource.HALO; x < 16 + BlockSource.HALO; x++) {
                for (int y = -BlockSource.HALO; y < 5; y++) {
                    set(x, y, z, VanillaBlocks.STONE);
                }
                set(x, 5, z, VanillaBlocks.WATER);
            }
        }
        ChunkMesh mesh = mesh(false);
        // Vanilla draws each top twice: once from above and once as the underside seen from below the surface.
        assertEquals(512, mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE), "two per column, no sides");
        assertEquals(0, mesh.translucentCells().length, "a flat sea needs no sorting");
        int quad = mesh.rangeStart(ChunkMesh.TRANSLUCENT_RANGE);
        // Vanilla: 1 - (1/9 * 11) / 11 at every corner of a source surrounded by sources, minus 0.001.
        assertEquals(5 + 1 - 1 / 9.0F - 0.001F, position(mesh, quad, 0)[1], 1.5f / 512);
    }

    @Test
    void waterAgainstAirShowsItsSideFromBothDirections() {
        set(5, 5, 5, VanillaBlocks.WATER);
        set(5, 4, 5, VanillaBlocks.STONE);
        ChunkMesh mesh = mesh(false);
        // Top, plus four sides drawn with both windings, plus the top's underside (air around the cell above).
        assertEquals(1 + 8 + 1, mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE));
    }

    @Test
    void plantsAreFlatLitOffsetCrossesThatHideNothing() {
        set(3, 4, 5, VanillaBlocks.TALL_GRASS);
        set(3, 3, 5, VanillaBlocks.GRASS);
        ChunkMesh mesh = mesh(true);
        assertEquals(4, count(mesh, MaterialPass.CUTOUT, ChunkMesh.ANY_DIRECTION));
        assertEquals(1, count(mesh, MaterialPass.OPAQUE, 3), "the grass block keeps its top face");
        int start = mesh.rangeStart(ChunkMesh.range(MaterialPass.CUTOUT, ChunkMesh.ANY_DIRECTION));
        // The cross model asks for no ambient occlusion, so vanilla's flat path offsets it by x and z only.
        long hash = ChunkMesher.positionHash(3, 0, 5);
        float offsetX = (float) ((((hash >> 16 & 15L) / 15.0F) - 0.5D) * 0.5D);
        for (int quad = start; quad < start + 4; quad++) {
            assertEquals(240, sky(mesh, quad, 0), "plants take their own cell's light");
            float minX = Float.MAX_VALUE;
            for (int vertex = 0; vertex < 4; vertex++) {
                minX = Math.min(minX, position(mesh, quad, vertex)[0]);
            }
            assertEquals(3 + 0.8f / 16 + offsetX, minX, 2f / 512, "vanilla's per-position offset");
        }
    }

    @Test
    void aWallAcrossTheSectionSeparatesItsSides() {
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                set(8, y, z, VanillaBlocks.STONE);
            }
        }
        long visibility = mesh(false).visibility();
        assertFalse(ChunkMesh.connects(visibility, 0, 1), "west cannot see east through the wall");
        assertTrue(ChunkMesh.connects(visibility, 0, 3), "west still reaches the top");
        assertTrue(ChunkMesh.connects(visibility, 1, 4), "east reaches the north face");
        assertTrue(ChunkMesh.connects(visibility, 2, 3), "top and bottom both have open halves");
    }

    @Test
    void aSolidSectionSeesNothingThrough() {
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    set(x, y, z, VanillaBlocks.STONE);
                }
            }
        }
        assertEquals(0L, mesh(false).visibility());
    }

    @Test
    void meshingIsDeterministic() {
        set(3, 3, 3, VanillaBlocks.GRASS);
        set(4, 3, 3, VanillaBlocks.WATER);
        set(3, 4, 3, VanillaBlocks.POPPY);
        ChunkMesh first = mesh(true);
        ChunkMesh second = mesh(true);
        assertTrue(Arrays.equals(first.words(), second.words()));
    }
}
