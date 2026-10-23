package alkaline.renderer.scene;

/** Normalized, non-empty rectangle inside a texture atlas. */
public final class AtlasRegion {
    private final float minU;
    private final float minV;
    private final float maxU;
    private final float maxV;

    public AtlasRegion(float minU, float minV, float maxU, float maxV) {
        if (!unit(minU) || !unit(minV) || !unit(maxU) || !unit(maxV)
                || minU >= maxU || minV >= maxV) {
            throw new IllegalArgumentException("Atlas bounds must form a non-empty normalized region.");
        }
        this.minU = minU;
        this.minV = minV;
        this.maxU = maxU;
        this.maxV = maxV;
    }

    public float minU() { return minU; }
    public float minV() { return minV; }
    public float maxU() { return maxU; }
    public float maxV() { return maxV; }

    private static boolean unit(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AtlasRegion region)) return false;
        return Float.floatToIntBits(minU) == Float.floatToIntBits(region.minU)
                && Float.floatToIntBits(minV) == Float.floatToIntBits(region.minV)
                && Float.floatToIntBits(maxU) == Float.floatToIntBits(region.maxU)
                && Float.floatToIntBits(maxV) == Float.floatToIntBits(region.maxV);
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(minU);
        result = 31 * result + Float.hashCode(minV);
        result = 31 * result + Float.hashCode(maxU);
        return 31 * result + Float.hashCode(maxV);
    }
}
