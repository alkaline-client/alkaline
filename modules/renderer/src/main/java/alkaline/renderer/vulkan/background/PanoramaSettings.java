package alkaline.renderer.vulkan.background;

/**
 * Tunables for the vanilla title panorama. {@link #vanilla()} reproduces 1.8.9: 8x8 jittered cube renders,
 * seven rotate-and-blur passes, unit rotation speed, and the two default title-screen gradients.
 *
 * @param samplesPerAxis jittered cube renders per axis (1..8); vanilla uses 8 for 64 renders
 * @param blurPasses rotate-and-blur passes (0..11); vanilla uses 7
 * @param speed rotation speed multiplier; 1 matches vanilla
 * @param overlayTopA ARGB of the first gradient's top edge (vanilla 0x80FFFFFF)
 * @param overlayBottomA ARGB of the first gradient's bottom edge (vanilla 0x00FFFFFF)
 * @param overlayTopB ARGB of the second gradient's top edge (vanilla 0x00000000)
 * @param overlayBottomB ARGB of the second gradient's bottom edge (vanilla 0x80000000)
 */
public record PanoramaSettings(int samplesPerAxis, int blurPasses, float speed, int overlayTopA, int overlayBottomA,
                               int overlayTopB, int overlayBottomB) {
    public static final int MAX_SAMPLES_PER_AXIS = 8;
    public static final int MAX_BLUR_PASSES = 11;

    public PanoramaSettings {
        if (samplesPerAxis < 1 || samplesPerAxis > MAX_SAMPLES_PER_AXIS) {
            throw new IllegalArgumentException("Panorama samples per axis must be between 1 and 8.");
        }
        if (blurPasses < 0 || blurPasses > MAX_BLUR_PASSES) {
            throw new IllegalArgumentException("Panorama blur passes must be between 0 and 11.");
        }
        if (!Float.isFinite(speed) || speed < 0f || speed > 16f) {
            throw new IllegalArgumentException("Panorama speed must be between 0 and 16.");
        }
    }

    public static PanoramaSettings vanilla() {
        return new PanoramaSettings(8, 7, 1f, 0x80FFFFFF, 0x00FFFFFF, 0x00000000, 0x80000000);
    }
}
