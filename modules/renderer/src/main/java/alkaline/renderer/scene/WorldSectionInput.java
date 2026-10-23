package alkaline.renderer.scene;

import alkaline.renderer.world.SectionData;

import java.util.Arrays;
import java.util.Objects;

/** Immutable copied section contents and coordinates used as renderer-neutral mesh input. */
public final class WorldSectionInput {
    private final SectionKey key;
    private final int width;
    private final int height;
    private final int depth;
    private final char[] paddedMaterialIds;
    private final long voxelCount;

    /**
     * Copies x-fastest material ids including SectionData's one-cell halo. Coordinates identify
     * section-grid cells; emitted world positions use coordinate * corresponding dimension.
     */
    public WorldSectionInput(int sectionX, int sectionY, int sectionZ,
                             int width, int height, int depth, char[] paddedMaterialIds) {
        Objects.requireNonNull(paddedMaterialIds, "paddedMaterialIds");
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Section dimensions must be positive.");
        }
        long expected;
        try {
            expected = Math.multiplyExact(
                    Math.multiplyExact((long) width + 2, (long) height + 2), (long) depth + 2);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Section dimensions exceed supported padded storage.", overflow);
        }
        if (expected > Integer.MAX_VALUE || paddedMaterialIds.length != (int) expected) {
            throw new IllegalArgumentException("Padded material data length does not match section dimensions.");
        }
        this.key = new SectionKey(sectionX, sectionY, sectionZ);
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.paddedMaterialIds = Arrays.copyOf(paddedMaterialIds, paddedMaterialIds.length);
        this.voxelCount = Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
    }

    public SectionKey key() { return key; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public long voxelCount() { return voxelCount; }

    /** Conservative world-space bounds for culling before section meshing. */
    public Aabb worldBounds() {
        float minX = worldPosition(key.x(), width, 0);
        float minY = worldPosition(key.y(), height, 0);
        float minZ = worldPosition(key.z(), depth, 0);
        return new Aabb(minX, minY, minZ,
                worldPosition(key.x(), width, width),
                worldPosition(key.y(), height, height),
                worldPosition(key.z(), depth, depth));
    }

    long estimatedBytes() {
        return (long) paddedMaterialIds.length * Character.BYTES;
    }

    boolean contentEquals(WorldSectionInput other) {
        return other != null && key.equals(other.key)
                && width == other.width && height == other.height && depth == other.depth
                && Arrays.equals(paddedMaterialIds, other.paddedMaterialIds);
    }

    SectionData toSectionData() {
        return new SectionData(width, height, depth, paddedMaterialIds);
    }

    static float worldPosition(int sectionCoordinate, int sectionSize, float local) {
        return (float) (sectionCoordinate * (double) sectionSize + local);
    }
}
