package alkaline.client.settings;

/**
 * Alkaline's GUI scale, in the spirit of vanilla's: discrete steps, an Auto mode that picks the largest step
 * the window can hold, and manual steps that are capped by the same limit so a layout never overflows.
 *
 * <p>A scale is UI pixels per density-independent unit (dp). Steps are 25% apart from 75% to 400%; the setting
 * stores 0 for Auto, or the step index {@code n >= 1} for {@code 0.5 + 0.25 * n}. Layouts are designed for at
 * least {@link #MIN_WIDTH_DP} x {@link #MIN_HEIGHT_DP} dp, which sets the largest step a window allows.
 */
public final class GuiScale {
    public static final int AUTO = 0;
    public static final int MAX_STEP = 14;
    public static final float MIN_WIDTH_DP = 1100f;
    public static final float MIN_HEIGHT_DP = 700f;
    private static final float STEP = 0.25f;
    private static final float SMALLEST = 0.75f;

    private GuiScale() {
    }

    /** Scale a manual step asks for; Auto has no fixed request. */
    public static float requested(int step) {
        if (step < 1 || step > MAX_STEP) {
            throw new IllegalArgumentException("GUI scale steps run from 1 to " + MAX_STEP + ".");
        }
        return 0.5f + STEP * step;
    }

    /** Largest step that fits the window, never below the smallest step. */
    public static float maximum(int widthPixels, int heightPixels) {
        float fit = Math.min(widthPixels / MIN_WIDTH_DP, heightPixels / MIN_HEIGHT_DP);
        float stepped = (float) Math.floor(fit / STEP + 1e-4) * STEP;
        return Math.max(SMALLEST, Math.min(requested(MAX_STEP), stepped));
    }

    /** Effective scale for a setting value and window size. */
    public static float scale(int setting, int widthPixels, int heightPixels) {
        float maximum = maximum(widthPixels, heightPixels);
        return setting == AUTO ? maximum : Math.min(requested(setting), maximum);
    }
}
