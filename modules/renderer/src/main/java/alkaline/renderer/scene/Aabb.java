package alkaline.renderer.scene;

/** Immutable axis-aligned bounds in world space. */
public final class Aabb {
    private final float minX, minY, minZ, maxX, maxY, maxZ;

    public Aabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!finite(minX, minY, minZ, maxX, maxY, maxZ) || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Bounds must be finite and ordered.");
        }
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
    }

    public float minX() { return minX; }
    public float minY() { return minY; }
    public float minZ() { return minZ; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }
    public float maxZ() { return maxZ; }

    public boolean intersects(Aabb other) {
        return maxX >= other.minX && minX <= other.maxX
                && maxY >= other.minY && minY <= other.maxY
                && maxZ >= other.minZ && minZ <= other.maxZ;
    }

    static boolean finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }
}
