package alkaline.renderer.font;

import alkaline.renderer.image.RgbaImage;
import java.util.Map;
import java.util.Objects;

/** Immutable glyph atlas, advances, and normalized atlas bounds for rasterized fonts. */
public final class GlyphAtlas {
    private final RgbaImage image;
    private final Map<Integer, Glyph> glyphs;
    private final int lineHeight;

    public GlyphAtlas(RgbaImage image, Map<Integer, Glyph> glyphs, int lineHeight) {
        this.image = Objects.requireNonNull(image, "image");
        this.glyphs = Map.copyOf(Objects.requireNonNull(glyphs, "glyphs"));
        for (Map.Entry<Integer, Glyph> entry : this.glyphs.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "glyph codepoint");
            Glyph glyph = Objects.requireNonNull(entry.getValue(), "glyph metrics");
            if ((long) glyph.x() + glyph.width() > image.width()
                    || (long) glyph.y() + glyph.height() > image.height()) {
                throw new IllegalArgumentException("Glyph rectangle lies outside its atlas.");
            }
        }
        if (lineHeight <= 0) {
            throw new IllegalArgumentException("Font line height must be positive.");
        }
        this.lineHeight = lineHeight;
    }

    public RgbaImage image() {
        return image;
    }

    public Glyph glyph(int codePoint) {
        return glyphs.get(codePoint);
    }

    public int lineHeight() {
        return lineHeight;
    }

    /** Glyph rectangle and bearing in pixels; UV coordinates are normalized to the atlas. */
    public record Glyph(int x, int y, int width, int height, int bearingX, int bearingY, float advance) {
        public Glyph {
            if (x < 0 || y < 0 || width < 0 || height < 0 || !Float.isFinite(advance) || advance < 0f) {
                throw new IllegalArgumentException("Invalid glyph metrics.");
            }
        }
    }
}
