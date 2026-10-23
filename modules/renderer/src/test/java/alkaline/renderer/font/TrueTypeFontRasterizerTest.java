package alkaline.renderer.font;

import alkaline.renderer.image.RgbaImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrueTypeFontRasterizerTest {
    @Test
    void rejectsInvalidFontStreamsAndEmptyCodepointSets() {
        assertThrows(java.io.IOException.class, () -> TrueTypeFontRasterizer.rasterize(
                new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}), 18f, "A", FontRasterOptions.highQuality()));
        assertThrows(IllegalArgumentException.class, () -> TrueTypeFontRasterizer.rasterize(
                new java.io.ByteArrayInputStream(new byte[0]), 18f, "", FontRasterOptions.highQuality()));
    }

    @Test
    void rasterizesTheOpenTypeFontFormatWithAntialiasingOptions() throws Exception {
        java.nio.file.Path fontPath = java.nio.file.Path.of("/usr/share/fonts/gsfonts/C059-BdIta.otf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isReadable(fontPath));
        GlyphAtlas atlas;
        try (var fontStream = java.nio.file.Files.newInputStream(fontPath)) {
            atlas = TrueTypeFontRasterizer.rasterize(fontStream, 20f, "AΩ", FontRasterOptions.highQuality());
        }
        assertTrue(atlas.glyph('A') != null);
        assertTrue(atlas.glyph('Ω') != null);
        assertTrue(atlas.image().width() > 0);
        var glyph = atlas.glyph('A');
        int pixelOffset = (glyph.y() * atlas.image().width() + glyph.x() + glyph.width() / 2) * 4;
        assertEquals(0xff, atlas.image().pixels().get(pixelOffset) & 0xff);
        assertEquals(0xff, atlas.image().pixels().get(pixelOffset + 1) & 0xff);
        assertEquals(0xff, atlas.image().pixels().get(pixelOffset + 2) & 0xff);

        try (var fontStream = java.nio.file.Files.newInputStream(fontPath)) {
            GlyphAtlas pixelated = TrueTypeFontRasterizer.rasterize(fontStream, 20f, "AΩ", FontRasterOptions.pixelated());
            assertTrue(intermediateAlphaCount(atlas) > intermediateAlphaCount(pixelated));
        }
    }

    private static int intermediateAlphaCount(GlyphAtlas atlas) {
        var pixels = atlas.image().pixels();
        int count = 0;
        for (int offset = 3; offset < pixels.limit(); offset += 4) {
            int alpha = pixels.get(offset) & 0xff;
            if (alpha > 0 && alpha < 255) count++;
        }
        return count;
    }
}
