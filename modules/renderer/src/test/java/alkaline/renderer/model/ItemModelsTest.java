package alkaline.renderer.model;

import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemModelsTest {
    /** A 16x16 sprite: a diagonal blade with a guard, a hole, and pixels touching only at corners. */
    private static RgbaImage sprite() {
        byte[] pixels = new byte[16 * 16 * 4];
        for (int i = 2; i < 14; i++) {
            opaque(pixels, i, 15 - i);
            opaque(pixels, i + 1, 15 - i);
        }
        for (int i = 3; i < 8; i++) {
            opaque(pixels, i, 11);
        }
        opaque(pixels, 0, 0); // touches nothing
        opaque(pixels, 1, 1); // corner-to-corner with (0, 0)
        for (int y = 5; y < 9; y++) {
            for (int x = 10; x < 14; x++) {
                if (x != 11 || y != 6) {
                    opaque(pixels, x, y); // a ring around a one-pixel hole
                }
            }
        }
        return new RgbaImage(16, 16, pixels);
    }

    private static void opaque(byte[] pixels, int x, int y) {
        int offset = (y * 16 + x) * 4;
        pixels[offset] = (byte) (x * 16);
        pixels[offset + 1] = (byte) (y * 16);
        pixels[offset + 3] = (byte) 0xFF;
    }

    private static String point(ByteBuffer data, int vertex) {
        int offset = vertex * BakedModel.VERTEX_BYTES;
        // Raw float bits: equal only when bit-identical, the condition for crack-free rasterization.
        return Integer.toHexString(data.getInt(offset)) + "," + Integer.toHexString(data.getInt(offset + 4)) + ","
                + Integer.toHexString(data.getInt(offset + 8));
    }

    /** Directed edges that lack an exact reverse edge: each is a potential crack. */
    private static int unmatchedEdges(BakedModel model) {
        ByteBuffer data = model.data();
        Map<String, Integer> edges = new HashMap<>();
        int quads = model.vertexCount() / 4;
        for (int quad = 0; quad < quads; quad++) {
            for (int corner = 0; corner < 4; corner++) {
                String from = point(data, quad * 4 + corner);
                String to = point(data, quad * 4 + (corner + 1) % 4);
                String reverse = to + ">" + from;
                if (edges.merge(reverse, -1, Integer::sum) == 0) {
                    edges.remove(reverse);
                } else {
                    edges.merge(reverse, 1, Integer::sum);
                    edges.merge(from + ">" + to, 1, Integer::sum);
                }
            }
        }
        return edges.values().stream().mapToInt(Math::abs).sum();
    }

    @Test
    void extrudedItemsAreClosedSurfaces() {
        BakedModel model = ItemModels.extrude("test", sprite());
        assertEquals(0, unmatchedEdges(model), "every edge is shared, bit for bit, by exactly two quads");
    }

    @Test
    void vanillaGeometryIsNotClosed() {
        assertTrue(unmatchedEdges(ItemModels.vanilla("test", sprite())) > 0,
                "vanilla's alpha-tested faces leave edges unmatched; the gaps the extrusion removes");
    }

    @Test
    void everyQuadSamplesOneTexelCenter() {
        BakedModel model = ItemModels.extrude("test", sprite());
        ByteBuffer data = model.data();
        for (int vertex = 0; vertex < model.vertexCount(); vertex++) {
            int offset = vertex * BakedModel.VERTEX_BYTES;
            float u = data.getFloat(offset + 12) * 16f;
            float v = data.getFloat(offset + 16) * 16f;
            assertEquals(0.5f, u - (float) Math.floor(u), 1e-6f);
            assertEquals(0.5f, v - (float) Math.floor(v), 1e-6f);
            int quadStart = (vertex / 4) * 4 * BakedModel.VERTEX_BYTES;
            assertEquals(data.getFloat(quadStart + 12), data.getFloat(offset + 12));
        }
    }

    @Test
    void geometryStaysOnePixelThickInsideTheModelBlock() {
        BakedModel model = ItemModels.extrude("test", sprite());
        ByteBuffer data = model.data();
        for (int vertex = 0; vertex < model.vertexCount(); vertex++) {
            int offset = vertex * BakedModel.VERTEX_BYTES;
            float z = data.getFloat(offset + 8);
            assertTrue(z == 7.5f / 16f || z == 8.5f / 16f);
            assertTrue(data.getFloat(offset) >= 0f && data.getFloat(offset) <= 1f);
        }
        assertEquals(1, model.partCount());
    }
}
