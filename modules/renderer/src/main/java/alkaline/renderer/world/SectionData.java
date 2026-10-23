package alkaline.renderer.world;

import java.util.Objects;

/** Caller-owned material ids for a rectangular section with a one-voxel neighbor halo. */
public final class SectionData {
    private final int width;
    private final int height;
    private final int depth;
    private final int rowStride;
    private final int sliceStride;
    private final char[] materialIds;
    private final int nonEmptyVoxelCount;
    private final char homogeneousMaterialId;

    /**
     * Creates a section from a flattened one-cell halo using x-fastest, then y, then z order.
     * Interior coordinates range from zero to dimension minus one; halo coordinates extend one cell
     * on every side. The supplied array is retained and must not be mutated after construction.
     */
    public SectionData(int width, int height, int depth, char[] paddedMaterialIds) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Section dimensions must be positive.");
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        rowStride = Math.addExact(width, 2);
        int paddedHeight = Math.addExact(height, 2);
        sliceStride = Math.multiplyExact(rowStride, paddedHeight);
        int expectedLength = Math.multiplyExact(sliceStride, Math.addExact(depth, 2));
        materialIds = Objects.requireNonNull(paddedMaterialIds, "paddedMaterialIds");
        if (materialIds.length != expectedLength) {
            throw new IllegalArgumentException("Padded material data length does not match section dimensions.");
        }
        int nonEmptyCount = 0;
        char onlyMaterial = 0;
        boolean allSameMaterial = true;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                int offset = (y + 1) * rowStride + (z + 1) * sliceStride + 1;
                for (int x = 0; x < width; x++) {
                    char materialId = materialIds[offset + x];
                    if (materialId != 0) {
                        nonEmptyCount++;
                        if (onlyMaterial == 0) {
                            onlyMaterial = materialId;
                        } else if (onlyMaterial != materialId) {
                            allSameMaterial = false;
                        }
                    }
                }
            }
        }
        nonEmptyVoxelCount = nonEmptyCount;
        homogeneousMaterialId = allSameMaterial && nonEmptyCount == Math.multiplyExact(Math.multiplyExact(width, height), depth)
                ? onlyMaterial : 0;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    /** Reads an interior or one-cell halo coordinate, where each axis is in [-1, dimension]. */
    public char materialIdAt(int x, int y, int z) {
        if (x < -1 || x > width || y < -1 || y > height || z < -1 || z > depth) {
            throw new IndexOutOfBoundsException("Coordinate lies outside the section and its one-cell halo.");
        }
        return materialIds[indexUnchecked(x, y, z)];
    }

    int nonEmptyVoxelCount() {
        return nonEmptyVoxelCount;
    }

    char homogeneousMaterialId() {
        return homogeneousMaterialId;
    }

    char[] materialIds() {
        return materialIds;
    }

    int rowStride() {
        return rowStride;
    }

    int sliceStride() {
        return sliceStride;
    }

    int indexUnchecked(int x, int y, int z) {
        return (y + 1) * rowStride + (z + 1) * sliceStride + x + 1;
    }
}
