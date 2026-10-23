package alkaline.renderer.world;

import java.util.Objects;

/** Emits deterministic greedy quads for visible same-material voxel faces. */
public final class SectionMesher {
    private static final int[][] FACE_OFFSETS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    /**
     * Uses greedy merging only for fully occupied, single-material sections with every dimension at
     * least 16 cells, matching the recorded 16^3 benchmark shape that showed reduced output. Empty
     * sections return immediately; mixed, sparse, partial, smaller, and cutout sections use unit faces.
     * This is a conservative routing heuristic, not a claim of a universal performance win.
     */
    public void mesh(SectionData section, MaterialTable materials, MeshBuffer output) {
        mesh(section, materials, output, true);
    }

    /**
     * As above; {@code mergeFaces = false} always emits unit faces. Callers that map one texture tile per
     * quad (an atlas without per-fragment wrapping) must disable merging, or merged faces stretch one tile.
     */
    public void mesh(SectionData section, MaterialTable materials, MeshBuffer output, boolean mergeFaces) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(output, "output");
        output.clear();

        if (section.nonEmptyVoxelCount() == 0) {
            return;
        }
        if (mergeFaces && section.homogeneousMaterialId() != 0
                && section.width() >= 16 && section.height() >= 16 && section.depth() >= 16
                && (materials.pass(section.homogeneousMaterialId()) == MaterialPass.OPAQUE
                || materials.pass(section.homogeneousMaterialId()) == MaterialPass.TRANSLUCENT)) {
            meshGreedyFaces(section, materials, output);
        } else {
            meshUnitFaces(section, materials, output);
        }
    }

    private void meshUnitFaces(SectionData section, MaterialTable materials, MeshBuffer output) {
        char[] ids = section.materialIds();
        int rowStride = section.rowStride();
        int sliceStride = section.sliceStride();
        for (int y = 0; y < section.height(); y++) {
            for (int z = 0; z < section.depth(); z++) {
                for (int x = 0; x < section.width(); x++) {
                    char materialId = ids[(y + 1) * rowStride + (z + 1) * sliceStride + x + 1];
                    if (materialId == 0) {
                        continue;
                    }
                    materials.pass(materialId);
                    for (int face = 0; face < FACE_OFFSETS.length; face++) {
                        int neighborOffset = FACE_OFFSETS[face][0]
                                + FACE_OFFSETS[face][1] * rowStride
                                + FACE_OFFSETS[face][2] * sliceStride;
                        char neighborId = ids[(y + 1) * rowStride + (z + 1) * sliceStride + x + 1
                                + neighborOffset];
                        if (isFaceVisible(materialId, neighborId, materials)) {
                            switch (face) {
                                case 0, 1 -> emitQuad(output, face, x, z, y, 1, 1,
                                        materialId, materials.pass(materialId));
                                case 2, 3 -> emitQuad(output, face, y, x, z, 1, 1,
                                        materialId, materials.pass(materialId));
                                case 4, 5 -> emitQuad(output, face, z, x, y, 1, 1,
                                        materialId, materials.pass(materialId));
                                default -> throw new AssertionError("Unexpected face code: " + face);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isFaceVisible(char materialId, char neighborId, MaterialTable materials) {
        if (neighborId == 0) {
            return true;
        }
        MaterialPass sourcePass = materials.pass(materialId);
        MaterialPass neighborPass = materials.pass(neighborId);
        // Only identical translucent materials hide their shared face (water beside water); water beside
        // glass keeps both faces.
        if (sourcePass == MaterialPass.TRANSLUCENT && neighborPass == MaterialPass.TRANSLUCENT) {
            return materialId != neighborId;
        }
        return !materials.occludesFaces(neighborId);
    }

    private void meshGreedyFaces(SectionData section, MaterialTable materials, MeshBuffer output) {
        int faceMaskWidth;
        int faceMaskHeight;
        int planeCount;
        char[] ids = section.materialIds();
        int rowStride = section.rowStride();
        int sliceStride = section.sliceStride();

        for (int face = 0; face < FACE_OFFSETS.length; face++) {
            switch (face) {
                case 0, 1 -> {
                    faceMaskWidth = section.depth();
                    faceMaskHeight = section.height();
                    planeCount = section.width();
                }
                case 2, 3 -> {
                    faceMaskWidth = section.width();
                    faceMaskHeight = section.depth();
                    planeCount = section.height();
                }
                case 4, 5 -> {
                    faceMaskWidth = section.width();
                    faceMaskHeight = section.height();
                    planeCount = section.depth();
                }
                default -> throw new AssertionError("Unexpected face code: " + face);
            }
            int maskCells = Math.multiplyExact(faceMaskWidth, faceMaskHeight);
            char[] mask = output.faceMask(maskCells);
            int du = 0;
            int dv = 0;
            switch (face) {
                case 0, 1 -> {
                    du = sliceStride;
                    dv = rowStride;
                }
                case 2, 3 -> {
                    du = 1;
                    dv = sliceStride;
                }
                case 4, 5 -> {
                    du = 1;
                    dv = rowStride;
                }
                default -> throw new AssertionError("Unexpected face code: " + face);
            }

            for (int plane = 0; plane < planeCount; plane++) {
                int baseOffset = switch (face) {
                    case 0, 1 -> rowStride + sliceStride + (plane + 1);
                    case 2, 3 -> (plane + 1) * rowStride + sliceStride + 1;
                    case 4, 5 -> rowStride + (plane + 1) * sliceStride + 1;
                    default -> throw new AssertionError("Unexpected face code: " + face);
                };
                int neighborOffset = FACE_OFFSETS[face][0]
                        + FACE_OFFSETS[face][1] * rowStride
                        + FACE_OFFSETS[face][2] * sliceStride;
                for (int v = 0; v < faceMaskHeight; v++) {
                    int row = v * faceMaskWidth;
                    int sourceOffset = baseOffset + v * dv;
                    for (int u = 0; u < faceMaskWidth; u++) {
                        char materialId = ids[sourceOffset + u * du];
                        if (materialId == 0) {
                            mask[row + u] = 0;
                            continue;
                        }
                        char neighborId = ids[sourceOffset + u * du + neighborOffset];
                        mask[row + u] = isFaceVisible(materialId, neighborId, materials)
                                ? materialId : 0;
                    }
                }

                for (int v = 0; v < faceMaskHeight; v++) {
                    int row = v * faceMaskWidth;
                    for (int u = 0; u < faceMaskWidth; ) {
                        char materialId = mask[row + u];
                        if (materialId == 0) {
                            u++;
                            continue;
                        }
                        int quadWidth = 1;
                        while (u + quadWidth < faceMaskWidth && mask[row + u + quadWidth] == materialId) {
                            quadWidth++;
                        }
                        int quadHeight = 1;
                        heightScan:
                        while (v + quadHeight < faceMaskHeight) {
                            int nextRow = (v + quadHeight) * faceMaskWidth + u;
                            for (int across = 0; across < quadWidth; across++) {
                                if (mask[nextRow + across] != materialId) {
                                    break heightScan;
                                }
                            }
                            quadHeight++;
                        }
                        for (int consumedV = 0; consumedV < quadHeight; consumedV++) {
                            int consumedRow = (v + consumedV) * faceMaskWidth + u;
                            for (int consumedU = 0; consumedU < quadWidth; consumedU++) {
                                mask[consumedRow + consumedU] = 0;
                            }
                        }
                        emitQuad(output, face, plane, u, v, quadWidth, quadHeight,
                                materialId, materials.pass(materialId));
                        u += quadWidth;
                    }
                }
            }
        }
    }

    private static void emitQuad(
            MeshBuffer output, int face, int plane, int u, int v,
            int width, int height, int materialId, MaterialPass pass
    ) {
        int baseVertex = output.vertexCount();
        switch (face) {
            case 0 -> { // -X: u=z, v=y
                append(output, plane, v, u, materialId, face);
                append(output, plane, v, u + width, materialId, face);
                append(output, plane, v + height, u + width, materialId, face);
                append(output, plane, v + height, u, materialId, face);
            }
            case 1 -> { // +X: u=z, v=y; reverse u for outward winding
                append(output, plane + 1, v, u + width, materialId, face);
                append(output, plane + 1, v, u, materialId, face);
                append(output, plane + 1, v + height, u, materialId, face);
                append(output, plane + 1, v + height, u + width, materialId, face);
            }
            case 2 -> { // -Y: u=x, v=z
                append(output, u, plane, v + height, materialId, face);
                append(output, u, plane, v, materialId, face);
                append(output, u + width, plane, v, materialId, face);
                append(output, u + width, plane, v + height, materialId, face);
            }
            case 3 -> { // +Y: u=x, v=z
                append(output, u, plane + 1, v, materialId, face);
                append(output, u, plane + 1, v + height, materialId, face);
                append(output, u + width, plane + 1, v + height, materialId, face);
                append(output, u + width, plane + 1, v, materialId, face);
            }
            case 4 -> { // -Z: u=x, v=y
                append(output, u + width, v, plane, materialId, face);
                append(output, u, v, plane, materialId, face);
                append(output, u, v + height, plane, materialId, face);
                append(output, u + width, v + height, plane, materialId, face);
            }
            case 5 -> { // +Z: u=x, v=y
                append(output, u, v, plane + 1, materialId, face);
                append(output, u + width, v, plane + 1, materialId, face);
                append(output, u + width, v + height, plane + 1, materialId, face);
                append(output, u, v + height, plane + 1, materialId, face);
            }
            default -> throw new AssertionError("Unexpected face code: " + face);
        }
        output.appendIndex(baseVertex, pass);
        output.appendIndex(baseVertex + 1, pass);
        output.appendIndex(baseVertex + 2, pass);
        output.appendIndex(baseVertex, pass);
        output.appendIndex(baseVertex + 2, pass);
        output.appendIndex(baseVertex + 3, pass);
    }

    private static void append(MeshBuffer output, int x, int y, int z, int materialId, int face) {
        output.appendVertex(x, y, z, materialId, face);
    }
}
