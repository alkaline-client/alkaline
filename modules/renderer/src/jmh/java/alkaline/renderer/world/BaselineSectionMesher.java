package alkaline.renderer.world;

/** Frozen pre-greedy implementation used only by the isolated JMH comparison. */
final class BaselineSectionMesher {
    private static final int[][] FACE_OFFSETS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    private static final int[][] FACE_CORNERS = {
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1},
            {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1}
    };

    void mesh(SectionData section, MaterialTable materials, BaselineMeshBuffer output) {
        output.clear();
        char[] ids = section.materialIds();
        int rowStride = section.rowStride();
        int sliceStride = section.sliceStride();
        for (int y = 0; y < section.height(); y++) {
            for (int z = 0; z < section.depth(); z++) {
                int rowOffset = (y + 1) * rowStride + (z + 1) * sliceStride + 1;
                for (int x = 0; x < section.width(); x++) {
                    char materialId = ids[rowOffset + x];
                    if (materialId == 0) {
                        continue;
                    }
                    MaterialPass pass = materials.pass(materialId);
                    for (int face = 0; face < FACE_OFFSETS.length; face++) {
                        int[] offset = FACE_OFFSETS[face];
                        char neighborId = ids[rowOffset + x + offset[0]
                                + offset[1] * rowStride + offset[2] * sliceStride];
                        if (neighborId != 0 && materials.occludesFaces(neighborId)) {
                            continue;
                        }
                        emitFace(output, x, y, z, materialId, face, pass);
                    }
                }
            }
        }
    }

    private static void emitFace(
            BaselineMeshBuffer output, int x, int y, int z, int materialId, int face, MaterialPass pass
    ) {
        int baseVertex = output.vertexCount();
        int[] corners = FACE_CORNERS[face];
        for (int vertex = 0; vertex < 4; vertex++) {
            int corner = vertex * 3;
            output.appendVertex(x + corners[corner], y + corners[corner + 1],
                    z + corners[corner + 2], materialId, face);
        }
        output.appendIndex(baseVertex, pass);
        output.appendIndex(baseVertex + 1, pass);
        output.appendIndex(baseVertex + 2, pass);
        output.appendIndex(baseVertex, pass);
        output.appendIndex(baseVertex + 2, pass);
        output.appendIndex(baseVertex + 3, pass);
    }
}
