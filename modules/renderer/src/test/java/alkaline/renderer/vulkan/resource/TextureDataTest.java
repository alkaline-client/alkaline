package alkaline.renderer.vulkan.resource;

import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureDataTest {
    @Test
    void packsEveryLayerLevelByLevelAsTheUploadExpects() {
        // Below and above the layer count that builds mip chains on the common pool.
        for (int count : new int[]{3, 40}) {
            List<RgbaImage> layers = layers(count, 16, 16, count);
            TextureData data = TextureData.array(layers, new TextureOptions(ImageSampling.NEAREST, true, true, 5));
            assertEquals(5, data.mipLevels());
            assertEquals(count, data.layers());
            assertArrayEquals(reference(layers, 16, 16, 5, true), bytes(data.pixels()));
        }
    }

    @Test
    void clampsMipLevelsToTheImageAndKeepsOptions() {
        TextureOptions options = new TextureOptions(ImageSampling.LINEAR, false, false, 16);
        TextureData data = TextureData.of(layers(1, 8, 4, 1).getFirst(), options);
        assertEquals(4, data.mipLevels()); // 8, 4, 2, 1
        assertEquals(8L * 4 * 4, data.levelZeroBytes());
        assertEquals(options, data.options());
        assertEquals(false, data.array());
        assertEquals((8 * 4 + 4 * 2 + 2 + 1) * 4, data.pixels().remaining());
    }

    @Test
    void eachViewStartsAtZero() {
        TextureData data = TextureData.of(layers(1, 4, 4, 2).getFirst(), TextureOptions.pixelArt());
        data.pixels().position(8);
        assertEquals(0, data.pixels().position());
    }

    @Test
    void rejectsLayersOfDifferentSizes() {
        List<RgbaImage> mixed = new ArrayList<>(layers(1, 4, 4, 3));
        mixed.addAll(layers(1, 8, 8, 4));
        assertThrows(IllegalArgumentException.class, () -> TextureData.array(mixed, TextureOptions.pixelArt()));
    }

    private static List<RgbaImage> layers(int count, int width, int height, long seed) {
        Random random = new Random(seed);
        List<RgbaImage> layers = new ArrayList<>(count);
        for (int layer = 0; layer < count; layer++) {
            byte[] pixels = new byte[width * height * 4];
            random.nextBytes(pixels);
            layers.add(new RgbaImage(width, height, pixels));
        }
        return layers;
    }

    /** The layout built the straightforward way: per level, every layer's downsampled copy in order. */
    private static byte[] reference(List<RgbaImage> layers, int width, int height, int levels, boolean srgb) {
        byte[][] current = new byte[layers.size()][];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int level = 0; level < levels; level++) {
            for (int layer = 0; layer < layers.size(); layer++) {
                current[layer] = level == 0 ? layers.get(layer).copyPixels()
                        : MipChain.downsample(current[layer], Math.max(1, width >> (level - 1)),
                                Math.max(1, height >> (level - 1)), srgb);
                out.writeBytes(current[layer]);
            }
        }
        return out.toByteArray();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
