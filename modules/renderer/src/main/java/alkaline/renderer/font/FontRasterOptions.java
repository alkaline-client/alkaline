package alkaline.renderer.font;

/** Rasterization settings for TrueType and supported OpenType font resources. */
public record FontRasterOptions(boolean antialiasing, boolean fractionalMetrics) {
    public static FontRasterOptions highQuality() {
        return new FontRasterOptions(true, true);
    }

    public static FontRasterOptions pixelated() {
        return new FontRasterOptions(false, false);
    }
}
