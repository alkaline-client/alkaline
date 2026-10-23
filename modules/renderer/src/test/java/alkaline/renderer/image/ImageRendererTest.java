package alkaline.renderer.image;

import alkaline.renderer.texture.TexturedMeshData;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageRendererTest {
    @Test
    void decodesPngRgbaAndPreservesAlpha() throws IOException {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80402010);
        source.setRGB(1, 0, 0xFFABCDEF);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", encoded));

        RgbaImage decoded = ImageDecoder.decode(new java.io.ByteArrayInputStream(encoded.toByteArray()));

        assertEquals(2, decoded.width());
        assertEquals(1, decoded.height());
        assertEquals(8, decoded.byteCount());
        assertEquals(0x40, decoded.pixels().get(0) & 0xff);
        assertEquals(0x80, decoded.pixels().get(3) & 0xff);
        assertEquals(0xef, decoded.pixels().get(6) & 0xff);
        assertEquals(0xff, decoded.pixels().get(7) & 0xff);
        assertTrue(decoded.pixels().isReadOnly());
    }

    @Test
    void rejectsNonPngAndInvalidDimensionsAndPixelBuffers() {
        byte[] nonPng = {'B', 'M', 0, 0, 0, 0, 0, 0};
        assertThrows(IOException.class, () -> ImageDecoder.decode(new java.io.ByteArrayInputStream(nonPng)));
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(0, 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new RgbaImage(1, 1, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> new ImageDisplayOptions(true, Float.NaN));
    }

    @Test
    void vanillaAsciiLoaderRequiresPngSignatureAndAtlasDimensions() throws IOException {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", png));
        RgbaImage atlas = VanillaAsciiFontLoader.load(png.toByteArray());
        assertEquals(128, atlas.width());
        assertEquals(128, atlas.height());

        byte[] nonPng = {'B', 'M', 0, 0, 0, 0, 0, 0};
        assertThrows(IOException.class, () -> VanillaAsciiFontLoader.load(nonPng));
    }

    @Test
    void buildsImageQuadWithSamplingOptionAndOpacity() {
        RgbaImage image = new RgbaImage(1, 1, new byte[]{1, 2, 3, 4});
        TexturedMeshData quad = ImageMeshBuilder.quad(image, 10, 20, 64, 32, 0,
                new ImageDisplayOptions(true, 0.5f));
        float[] vertices = quad.copyVertices();

        assertEquals(4, quad.vertexCount());
        assertEquals(6, quad.indexCount());
        assertEquals(10f, vertices[0]);
        assertEquals(0f, vertices[3]);
        assertEquals(0.5f, vertices[8]);
        assertEquals(74f, vertices[TexturedMeshData.FLOATS_PER_VERTEX]);
        assertTrue(quad.options().antialiasing());
        assertFalse(ImageDisplayOptions.pixelPerfect().antialiasing());
    }
}
