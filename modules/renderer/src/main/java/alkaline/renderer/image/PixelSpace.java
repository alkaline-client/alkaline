package alkaline.renderer.image;

/** Pixel-space transform for top-left-origin image and HUD geometry. */
public final class PixelSpace {
    private PixelSpace() {
    }

    /** Maps (0,0) to the top-left clip corner and (width,height) to the bottom-right. */
    public static float[] projection(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive.");
        }
        return new float[]{
                2f / width, 0f, 0f, 0f,
                0f, 2f / height, 0f, 0f,
                0f, 0f, 1f, 0f,
                -1f, -1f, 0f, 1f
        };
    }
}
