package alkaline.renderer.image;

/** Sampling and edge filtering used when an image is scaled for display. */
public record ImageDisplayOptions(boolean antialiasing, float opacity) {
    public ImageDisplayOptions {
        if (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException("Image opacity must be finite and between zero and one.");
        }
    }

    public static ImageDisplayOptions pixelPerfect() {
        return new ImageDisplayOptions(false, 1f);
    }

    public static ImageDisplayOptions smooth() {
        return new ImageDisplayOptions(true, 1f);
    }
}
