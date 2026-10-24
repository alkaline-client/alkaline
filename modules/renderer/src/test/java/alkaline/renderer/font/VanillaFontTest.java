package alkaline.renderer.font;

import static org.junit.jupiter.api.Assertions.assertEquals;

import alkaline.renderer.image.RgbaImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VanillaFontTest {
    /** An atlas where every glyph's cell has opaque pixels in its first {@code columns} columns, 'W' in 7. */
    private static VanillaFont font() {
        byte[] pixels = new byte[128 * 128 * 4];
        for (int code = 0; code < 256; code++) {
            int columns = code == VanillaFontLayout.atlasIndex('W') ? 7 : 3;
            for (int x = 0; x < columns; x++) {
                for (int y = 0; y < 8; y++) {
                    pixels[(((code / 16 * 8 + y) * 128) + code % 16 * 8 + x) * 4 + 3] = (byte) 255;
                }
            }
        }
        return new VanillaFont(new RgbaImage(128, 128, pixels), null, null);
    }

    private record Quad(int page, float x0, float y0, float x1, float y1, int argb) {
    }

    private static final class Sink implements VanillaFont.Sink {
        final List<Quad> glyphs = new ArrayList<>();
        final List<float[]> rects = new ArrayList<>();

        @Override
        public void glyph(int page, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                          float u0, float v0, float u1, float v1, int argb) {
            glyphs.add(new Quad(page, x0, y0, x2, y2, argb));
        }

        @Override
        public void rect(float left, float top, float right, float bottom, int argb) {
            rects.add(new float[]{left, top, right, bottom, argb});
        }
    }

    @Test
    void widthIsTheLastUsedColumnPlusOneOfSpacingAndStylesTakeNone() {
        VanillaFont font = font();
        assertEquals(4, font.width(" "));
        assertEquals(4, font.width("A"));
        assertEquals(8, font.width("W"));
        assertEquals(4 + 8 + 4, font.width("A§cW§rA"));
        // Bold adds a pixel to each glyph from the style on, until reset.
        assertEquals(4 + 5 + 5 + 4, font.width("A§lAA§rA"));
        assertEquals(0, font.width("§"), "a lone section sign is a style start");
    }

    @Test
    void layoutAdvancesByTheWidthsAndColorsFollowTheCodes() {
        Sink sink = new Sink();
        int end = font().layout("A§aW", 10, 20, 0x20FFFFFF, sink);
        assertEquals(10 + 4 + 8, end);
        assertEquals(2, sink.glyphs.size());
        assertEquals(10f, sink.glyphs.get(0).x0());
        assertEquals(20f, sink.glyphs.get(0).y0());
        assertEquals(20f + 7.99f, sink.glyphs.get(0).y1(), 1e-4);
        assertEquals(0x20FFFFFF, sink.glyphs.get(0).argb());
        // Green is 0x55FF55 with the base alpha kept.
        assertEquals(0x2055FF55, sink.glyphs.get(1).argb());
        assertEquals(14f, sink.glyphs.get(1).x0());
        // The glyph's quad is 0.01 short of its advance, less the spacing pixel.
        assertEquals(14f + 8 - 0.01f - 1f, sink.glyphs.get(1).x1(), 1e-4);
    }

    @Test
    void underlineAndStrikethroughAreBars() {
        Sink sink = new Sink();
        font().layout("§nA", 0, 0, 0xFFFFFFFF, sink);
        assertEquals(1, sink.rects.size());
        assertEquals(-1f, sink.rects.get(0)[0]);
        assertEquals(8f, sink.rects.get(0)[1]);
        assertEquals(4f, sink.rects.get(0)[2]);
        assertEquals(9f, sink.rects.get(0)[3]);
        sink.rects.clear();
        font().layout("§mA", 0, 0, 0xFFFFFFFF, sink);
        assertEquals(3f, sink.rects.get(0)[1]);
        assertEquals(4f, sink.rects.get(0)[3]);
    }

    @Test
    void anAlphaBelowFourIsOpaque() {
        Sink sink = new Sink();
        font().layout("A", 0, 0, 0x00123456, sink);
        assertEquals(0xFF123456, sink.glyphs.get(0).argb());
    }
}
