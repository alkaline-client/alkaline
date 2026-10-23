package alkaline.renderer.scene;

import java.util.Objects;

/** Immutable render properties resolved from a world material id. */
public final class WorldMaterial {
    private final int materialId;
    private final int textureId;
    private final AtlasRegion atlasRegion;
    private final float red;
    private final float green;
    private final float blue;

    public WorldMaterial(int materialId, int textureId, AtlasRegion atlasRegion,
                         float red, float green, float blue) {
        if (materialId <= 0 || materialId > Character.MAX_VALUE) {
            throw new IllegalArgumentException("World material id must fit a non-zero section material id.");
        }
        if (textureId < 0) throw new IllegalArgumentException("Texture id must not be negative.");
        requireColor(red, "red");
        requireColor(green, "green");
        requireColor(blue, "blue");
        this.materialId = materialId;
        this.textureId = textureId;
        this.atlasRegion = Objects.requireNonNull(atlasRegion, "atlasRegion");
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public int materialId() { return materialId; }
    public int textureId() { return textureId; }
    public AtlasRegion atlasRegion() { return atlasRegion; }
    public float red() { return red; }
    public float green() { return green; }
    public float blue() { return blue; }

    private static void requireColor(float value, String component) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(component + " tint must be finite and normalized.");
        }
    }
}
