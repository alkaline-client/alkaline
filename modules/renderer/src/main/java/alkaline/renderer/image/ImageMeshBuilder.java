package alkaline.renderer.image;

import alkaline.renderer.texture.TexturedMeshData;
import java.util.Objects;

/** Builds a single textured quad for arbitrary image display, including HUD-sized image placement. */
public final class ImageMeshBuilder {
    private ImageMeshBuilder() {
    }

    public static TexturedMeshData quad(RgbaImage image, float x, float y, float width, float height,
                                        float depth, ImageDisplayOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width) || !Float.isFinite(height)
                || !Float.isFinite(depth) || width <= 0f || height <= 0f) {
            throw new IllegalArgumentException("Image quad coordinates and dimensions must be finite and positive.");
        }
        float alpha = options.opacity();
        float[] vertices = {
                x, y, depth, 0f, 0f, 1f, 1f, 1f, alpha,
                x + width, y, depth, 1f, 0f, 1f, 1f, 1f, alpha,
                x + width, y + height, depth, 1f, 1f, 1f, 1f, 1f, alpha,
                x, y + height, depth, 0f, 1f, 1f, 1f, 1f, alpha,
        };
        return new TexturedMeshData(image, options, vertices, new int[]{0, 1, 2, 0, 2, 3});
    }
}
