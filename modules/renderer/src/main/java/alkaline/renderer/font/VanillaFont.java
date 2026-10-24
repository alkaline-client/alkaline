package alkaline.renderer.font;

import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.function.IntFunction;

/**
 * FontRenderer for 1.8.9 as the quads it would draw: widths from the ASCII atlas' used columns and the packed
 * unicode glyph sizes, the legacy character table with unicode pages for everything else, § styles (colors, bold,
 * italic, underline, strikethrough, obfuscated, reset), and vanilla's pixel advance. The caller supplies the atlas
 * and pages from the active resource packs.
 */
public final class VanillaFont {
    public static final int HEIGHT = 9;
    /** The sink's page number for the ASCII atlas; unicode pages are numbered by their high byte. */
    public static final int ASCII_PAGE = -1;

    /** Receives glyph and solid quads. Glyph corners run top-left, top-right, bottom-right, bottom-left. */
    public interface Sink {
        void glyph(int page, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                   float u0, float v0, float u1, float v1, int argb);

        void rect(float left, float top, float right, float bottom, int argb);
    }

    private static final int[] COLORS = new int[16];

    static {
        for (int i = 0; i < 16; i++) {
            int j = (i >> 3 & 1) * 85;
            int red = (i >> 2 & 1) * 170 + j;
            int green = (i >> 1 & 1) * 170 + j;
            int blue = (i & 1) * 170 + j;
            if (i == 6) {
                red += 85;
            }
            COLORS[i] = (red & 255) << 16 | (green & 255) << 8 | blue & 255;
        }
    }

    private final RgbaImage ascii;
    private final int cell;
    private final int[] charWidth = new int[256];
    private final byte[] glyphWidth;
    private final IntFunction<RgbaImage> pages;
    private final Random random = new Random();

    /**
     * @param ascii       {@code textures/font/ascii.png}, sixteen by sixteen cells of any size
     * @param glyphWidth  {@code font/glyph_sizes.bin}, or null for no unicode glyphs
     * @param pages       unicode pages by high byte, null while one is not loaded (its glyphs then keep their room
     *                    but draw nothing); only presence matters here
     */
    public VanillaFont(RgbaImage ascii, byte[] glyphWidth, IntFunction<RgbaImage> pages) {
        this.ascii = ascii;
        this.glyphWidth = glyphWidth != null && glyphWidth.length == 65536 ? glyphWidth : null;
        this.pages = pages;
        cell = ascii.width() / 16;
        readWidths();
    }

    /** FontRenderer.readFontTexture: the last column with any alpha, scaled to 8 pixels, plus one of spacing. */
    private void readWidths() {
        ByteBuffer pixels = ascii.pixels();
        int width = ascii.width();
        int rows = ascii.height() / 16;
        float scale = 8.0F / cell;
        for (int code = 0; code < 256; code++) {
            int column = code % 16;
            int row = code / 16;
            int last = cell - 1;
            for (; last >= 0; last--) {
                boolean empty = true;
                for (int y = 0; y < rows && empty; y++) {
                    int offset = (((row * rows + y) * width) + column * cell + last) * 4 + 3;
                    empty = (pixels.get(offset) & 0xFF) == 0;
                }
                if (!empty) {
                    break;
                }
            }
            charWidth[code] = (int) (0.5 + (last + 1) * scale) + 1;
        }
    }

    /** FontRenderer.getCharWidth: -1 for the § that starts a style. */
    public int charWidth(char character) {
        if (character == '§') {
            return -1;
        }
        if (character == ' ') {
            return 4;
        }
        int index = VanillaFontLayout.atlasIndex(character);
        if (character > 0 && index != -1) {
            return charWidth[index];
        }
        if (glyphWidth != null && glyphWidth[character] != 0) {
            int start = (glyphWidth[character] & 0xFF) >>> 4;
            int end = (glyphWidth[character] & 15) + 1;
            return (end - start) / 2 + 1;
        }
        return 0;
    }

    /** FontRenderer.getStringWidth: styles take no room; bold adds a pixel per glyph. */
    public int width(String text) {
        int width = 0;
        boolean bold = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            int advance = charWidth(character);
            if (advance < 0 && index < text.length() - 1) {
                character = text.charAt(++index);
                if (character == 'l' || character == 'L') {
                    bold = true;
                } else if (character == 'r' || character == 'R') {
                    bold = false;
                }
                advance = 0;
            }
            width += advance;
            if (bold && advance > 0) {
                width++;
            }
        }
        return width;
    }

    /**
     * FontRenderer.renderString without the shadow: lays {@code text} out from (x, y) in {@code argb} (alpha
     * under 4 counts as opaque, as vanilla). Returns the pen's final x.
     */
    public int layout(String text, float x, float y, int argb, Sink sink) {
        if ((argb & -67108864) == 0) {
            argb |= 0xFF000000;
        }
        int base = argb;
        int color = argb;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        boolean random = false;
        float pen = x;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '§' && index + 1 < text.length()) {
                int code = "0123456789abcdefklmnor".indexOf(Character.toLowerCase(text.charAt(index + 1)));
                if (code < 16) {
                    random = bold = strike = underline = italic = false;
                    color = (base & 0xFF000000) | COLORS[code < 0 ? 15 : code];
                } else if (code == 16) {
                    random = true;
                } else if (code == 17) {
                    bold = true;
                } else if (code == 18) {
                    strike = true;
                } else if (code == 19) {
                    underline = true;
                } else if (code == 20) {
                    italic = true;
                } else {
                    random = bold = strike = underline = italic = false;
                    color = base;
                }
                index++;
                continue;
            }
            int table = VanillaFontLayout.atlasIndex(character);
            if (random && table != -1) {
                int width = charWidth(character);
                char replacement;
                do {
                    int pick = this.random.nextInt(256);
                    replacement = VanillaFontLayout.legacyCharacter(pick);
                } while (width != charWidth(replacement));
                character = replacement;
            }
            float advance = glyph(character, pen, y, italic, color, sink);
            if (bold) {
                glyph(character, pen + 1f, y, italic, color, sink);
                advance++;
            }
            if (strike) {
                sink.rect(pen, y + HEIGHT / 2 - 1.0F, pen + advance, y + HEIGHT / 2, color);
            }
            if (underline) {
                sink.rect(pen - 1, y + HEIGHT - 1.0F, pen + advance, y + HEIGHT, color);
            }
            pen += (int) advance;
        }
        return (int) pen;
    }

    /** renderChar: one glyph at the pen; returns its advance. */
    private float glyph(char character, float x, float y, boolean italic, int argb, Sink sink) {
        if (character == ' ') {
            return 4.0F;
        }
        int index = VanillaFontLayout.atlasIndex(character);
        float lean = italic ? 1.0F : 0.0F;
        if (index != -1) {
            int width = charWidth[index];
            float left = index % 16 * 8;
            float top = index / 16 * 8;
            float extent = width - 0.01F;
            sink.glyph(ASCII_PAGE, x + lean, y, x + extent - 1.0F + lean, y, x + extent - 1.0F - lean, y + 7.99F,
                    x - lean, y + 7.99F, left / 128.0F, top / 128.0F, (left + extent - 1.0F) / 128.0F,
                    (top + 7.99F) / 128.0F, argb);
            return width;
        }
        if (glyphWidth == null || glyphWidth[character] == 0) {
            return 0.0F;
        }
        int page = character / 256;
        boolean drawn = pages != null && pages.apply(page) != null;
        float start = (glyphWidth[character] & 0xFF) >>> 4;
        float end = (glyphWidth[character] & 15) + 1;
        float u = character % 16 * 16 + start;
        float v = (character & 255) / 16 * 16;
        float span = end - start - 0.02F;
        if (drawn) {
            sink.glyph(page, x + lean, y, x + span / 2.0F + lean, y, x + span / 2.0F - lean, y + 7.99F, x - lean,
                    y + 7.99F, u / 256.0F, v / 256.0F, (u + span) / 256.0F, (v + 15.98F) / 256.0F, argb);
        }
        return (end - start) / 2.0F + 1.0F;
    }
}
