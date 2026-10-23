package alkaline.renderer.font;

import alkaline.renderer.image.RgbaImage;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded TTF/OTF rasterization into a glyph atlas for a selected codepoint set. */
public final class TrueTypeFontRasterizer {
    private static final int MAX_FONT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_GLYPHS = 4096;
    private static final int MAX_ATLAS_DIMENSION = 4096;

    private TrueTypeFontRasterizer() {
    }

    public static GlyphAtlas rasterize(InputStream fontStream, float pixelSize, String characters,
                                       FontRasterOptions options) throws IOException {
        Objects.requireNonNull(fontStream, "fontStream");
        Objects.requireNonNull(characters, "characters");
        Objects.requireNonNull(options, "options");
        if (!Float.isFinite(pixelSize) || pixelSize <= 0f || pixelSize > 256f) {
            throw new IllegalArgumentException("Font size must be finite and between zero and 256 pixels.");
        }
        int[] codePoints = characters.codePoints().distinct().toArray();
        if (codePoints.length == 0 || codePoints.length > MAX_GLYPHS) {
            throw new IllegalArgumentException("Font rasterization requires 1 to 4096 distinct codepoints.");
        }
        byte[] bytes = fontStream.readNBytes(MAX_FONT_BYTES + 1);
        if (bytes.length > MAX_FONT_BYTES || fontStream.read() != -1) {
            throw new IOException("Font file exceeds the supported size limit.");
        }
        Font font;
        try (InputStream bounded = new ByteArrayInputStream(bytes)) {
            font = Font.createFont(Font.TRUETYPE_FONT, bounded).deriveFont(pixelSize);
        } catch (FontFormatException failure) {
            throw new IOException("Unsupported or invalid TrueType/OpenType font.", failure);
        }

        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metricsGraphics = metricsImage.createGraphics();
        setHints(metricsGraphics, options);
        metricsGraphics.setFont(font);
        var metrics = metricsGraphics.getFontMetrics();
        int lineHeight = metrics.getHeight();
        float ascent = metrics.getAscent();
        GlyphInfo[] glyphInfos = new GlyphInfo[codePoints.length];
        int cellWidth = 1;
        for (int index = 0; index < codePoints.length; index++) {
            String glyphText = new String(Character.toChars(codePoints[index]));
            GlyphVector vector = font.createGlyphVector(metricsGraphics.getFontRenderContext(), glyphText);
            Rectangle pixelBounds = vector.getPixelBounds(metricsGraphics.getFontRenderContext(), 0f, 0f);
            float advance = (float) vector.getGlyphPosition(vector.getNumGlyphs()).getX();
            glyphInfos[index] = new GlyphInfo(codePoints[index], vector, pixelBounds, advance);
            cellWidth = Math.max(cellWidth, pixelBounds.width + 2);
        }
        metricsGraphics.dispose();
        int columns = (int) Math.ceil(Math.sqrt(codePoints.length));
        int rows = (codePoints.length + columns - 1) / columns;
        long atlasWidth = (long) columns * cellWidth;
        long atlasHeight = (long) rows * lineHeight;
        if (atlasWidth > MAX_ATLAS_DIMENSION || atlasHeight > MAX_ATLAS_DIMENSION
                || atlasWidth * atlasHeight > 4_194_304L) {
            throw new IllegalArgumentException("Requested glyph set exceeds atlas dimensions or pixel budget.");
        }
        BufferedImage atlas = new BufferedImage((int) atlasWidth, (int) atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        setHints(graphics, options);
        graphics.setFont(font);
        Map<Integer, GlyphAtlas.Glyph> glyphs = new HashMap<>(codePoints.length);
        for (int index = 0; index < glyphInfos.length; index++) {
            GlyphInfo info = glyphInfos[index];
            int originX = index % columns * cellWidth;
            int originY = index / columns * lineHeight;
            if (info.bounds().width > 0 && info.bounds().height > 0) {
                graphics.drawGlyphVector(info.vector(), originX + 1 - info.bounds().x,
                        originY + 1 - info.bounds().y);
                glyphs.put(info.codePoint(), new GlyphAtlas.Glyph(originX + 1, originY + 1,
                        info.bounds().width, info.bounds().height, info.bounds().x,
                        Math.round(ascent + info.bounds().y + info.bounds().height), info.advance()));
            } else {
                glyphs.put(info.codePoint(), new GlyphAtlas.Glyph(originX, originY, 0, 0, 0,
                        lineHeight, info.advance()));
            }
        }
        graphics.dispose();
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(atlas.getWidth(), atlas.getHeight()), 4)];
        int offset = 0;
        for (int y = 0; y < atlas.getHeight(); y++) {
            for (int x = 0; x < atlas.getWidth(); x++) {
                int alpha = atlas.getRGB(x, y) >>> 24;
                rgba[offset++] = (byte) 0xff;
                rgba[offset++] = (byte) 0xff;
                rgba[offset++] = (byte) 0xff;
                rgba[offset++] = (byte) alpha;
            }
        }
        return new GlyphAtlas(new RgbaImage(atlas.getWidth(), atlas.getHeight(), rgba), glyphs, lineHeight);
    }

    private static void setHints(Graphics2D graphics, FontRasterOptions options) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                options.antialiasing() ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                options.fractionalMetrics() ? RenderingHints.VALUE_FRACTIONALMETRICS_ON
                        : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    private record GlyphInfo(int codePoint, GlyphVector vector, Rectangle bounds, float advance) {
    }
}
