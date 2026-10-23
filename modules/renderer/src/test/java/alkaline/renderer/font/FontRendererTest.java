package alkaline.renderer.font;

import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.texture.TexturedMeshData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontRendererTest {
    @Test
    void laysOutGlyphAtlasTextIntoOneTexturedBatch() {
        RgbaImage atlasImage = new RgbaImage(16, 16, new byte[16 * 16 * 4]);
        GlyphAtlas atlas = new GlyphAtlas(atlasImage,
                java.util.Map.of((int) 'A', new GlyphAtlas.Glyph(2, 3, 5, 7, 0, 6, 6)), 9);

        var batch = GlyphTextLayout.layout(atlas, "AA", 10, 20, 0, 2, 0xffffffff, true);

        assertEquals(8, batch.vertexCount());
        assertEquals(12, batch.indexCount());
        assertEquals(10f, batch.copyVertices()[0]);
        assertEquals(22f, batch.copyVertices()[TexturedMeshData.FLOATS_PER_VERTEX * 4]);
        assertEquals(0.125f, batch.copyVertices()[3]);
        assertEquals(0.1875f, batch.copyVertices()[4]);
        assertEquals(1f, batch.copyVertices()[8]);
        assertTrue(batch.options().antialiasing());
    }

    @Test
    void laysOutVanillaAsciiFromTheGlyphAtlasAndFormatting() {
        byte[] pixels = new byte[128 * 128 * 4];
        int asciiA = VanillaFontLayout.atlasIndex('A');
        for (int y = 1; y < 7; y++) {
            for (int x = 2; x < 5; x++) {
                pixels[((asciiA / 16 * 8 + y) * 128 + (asciiA % 16 * 8 + x)) * 4 + 3] = (byte) 0xff;
            }
        }
        RgbaImage atlas = new RgbaImage(128, 128, pixels);

        var plain = VanillaFontLayout.layout(atlas, "A", 3, 4, 0, 0xffffffff);
        var bold = VanillaFontLayout.layout(atlas, "§lA", 3, 4, 0, 0xffffffff);

        assertEquals(4, plain.vertexCount());
        assertEquals(6, plain.indexCount());
        assertEquals(8, bold.vertexCount());
        assertEquals(12, bold.indexCount());
        assertEquals(((VanillaFontLayout.atlasIndex('A') % 16) * 8f) / 128f, plain.copyVertices()[3]);
        assertEquals(32f / 128f, plain.copyVertices()[4]);
        assertEquals(1f, plain.copyVertices()[8]);
        assertEquals(128, VanillaFontLayout.atlasIndex('\u00c7'));
        assertEquals(65, VanillaFontLayout.atlasIndex('A'));
        assertThrows(IllegalArgumentException.class,
                () -> VanillaFontLayout.layout(atlas, "A\u0141", 0, 0, 0, 0xffffffff));
    }

    @Test
    void batchesVanillaUnicodeGlyphsPerFontPageAndSkipsMissingGlyphs() {
        byte[] glyphSizes = new byte[65_536];
        glyphSizes['A'] = 0x12;
        glyphSizes[0x0141] = 0x12;
        RgbaImage asciiPage = new RgbaImage(256, 256, new byte[256 * 256 * 4]);
        RgbaImage unicodePage = new RgbaImage(256, 256, new byte[256 * 256 * 4]);

        var batches = VanillaUnicodeFontLayout.layout(java.util.Map.of(0, asciiPage, 1, unicodePage),
                glyphSizes, "A\u0141\u0142", 4, 6, 0, 0xffffffff);

        assertEquals(2, batches.size());
        assertEquals(4, batches.get(0).vertexCount());
        assertEquals(4, batches.get(1).vertexCount());
        assertEquals(asciiPage, batches.get(0).texture());
        assertEquals(unicodePage, batches.get(1).texture());
        assertTrue(VanillaUnicodeFontLayout.layout(java.util.Map.of(), glyphSizes,
                "\u0142", 4, 6, 0, 0xffffffff).isEmpty());
    }
}
