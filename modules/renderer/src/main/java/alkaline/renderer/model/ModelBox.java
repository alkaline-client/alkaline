package alkaline.renderer.model;

/**
 * One cuboid in a model part, in model units (1/16 block). Texture layout follows the Minecraft box unwrap:
 * the {@code depth x depth} strip on top holds the up and down faces, the strip below holds the four sides.
 *
 * @param textureU left edge of the unwrap, in texels
 * @param textureV top edge of the unwrap, in texels
 * @param inflate grows the box on every side without changing its texture area (armor and skin overlays)
 * @param mirror swaps the box horizontally in texture space (vanilla's mirrored limbs)
 */
public record ModelBox(int textureU, int textureV, float x, float y, float z, int width, int height, int depth,
                       float inflate, boolean mirror) {
    public ModelBox {
        if (width < 0 || height < 0 || depth < 0 || !Float.isFinite(inflate)) {
            throw new IllegalArgumentException("Model boxes need non-negative integer sizes and a finite inflate.");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Model box origin must be finite.");
        }
    }
}
