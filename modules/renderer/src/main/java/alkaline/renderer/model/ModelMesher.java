package alkaline.renderer.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bakes a model's boxes once into static GPU vertex data. Each vertex is 28 bytes: position (3 x f32, block
 * units), uv (2 x f32, normalized), normal (4 x snorm8), part index (u32). Faces follow Minecraft's box
 * layout and winding, including mirrored boxes.
 */
public final class ModelMesher {
    public static final int VERTEX_BYTES = BakedModel.VERTEX_BYTES;
    public static final float UNITS_PER_BLOCK = 16f;

    private ModelMesher() {
    }

    /** One textured box face as vanilla draws it: four corners in block units, uvs normalized, the face normal. */
    public interface FaceSink {
        void face(int part, float[] positions, float[] uvs, float nx, float ny, float nz);
    }

    /**
     * Every box face of a model in Minecraft's box layout and winding (mirrored boxes included), positions in block
     * units relative to their part's pivot. The arrays are reused between calls.
     */
    public static void faces(CuboidModel model, FaceSink sink) {
        float[][] corners = new float[8][3];
        float[] positions = new float[12];
        float[] uvs = new float[8];
        for (int partIndex = 0; partIndex < model.partCount(); partIndex++) {
            for (ModelBox box : model.parts().get(partIndex).boxes()) {
                float x0 = box.x() - box.inflate();
                float y0 = box.y() - box.inflate();
                float z0 = box.z() - box.inflate();
                float x1 = box.x() + box.width() + box.inflate();
                float y1 = box.y() + box.height() + box.inflate();
                float z1 = box.z() + box.depth() + box.inflate();
                if (box.mirror()) {
                    float swap = x1;
                    x1 = x0;
                    x0 = swap;
                }
                // Corner numbering follows the vanilla box: 0..3 on the z0 side, 4..7 on the z1 side.
                set(corners[0], x1, y0, z0);
                set(corners[1], x1, y1, z0);
                set(corners[2], x0, y1, z0);
                set(corners[3], x0, y0, z1);
                set(corners[4], x1, y0, z1);
                set(corners[5], x1, y1, z1);
                set(corners[6], x0, y1, z1);
                set(corners[7], x0, y0, z0);
                int u = box.textureU();
                int v = box.textureV();
                int w = box.width();
                int h = box.height();
                int d = box.depth();
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 4, 0, 1, 5,
                        u + d + w, v + d, u + d + w + d, v + d + h);
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 7, 3, 6, 2,
                        u, v + d, u + d, v + d + h);
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 4, 3, 7, 0,
                        u + d, v, u + d + w, v + d);
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 1, 2, 6, 5,
                        u + d + w, v + d, u + d + w + w, v);
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 0, 7, 2, 1,
                        u + d, v + d, u + d + w, v + d + h);
                face(sink, model, partIndex, box.mirror(), corners, positions, uvs, 3, 4, 5, 6,
                        u + d + w + d, v + d, u + d + w + d + w, v + d + h);
            }
        }
    }

    /** Vertex bytes followed by uint32 indices, in native order; returns layout info for drawing. */
    public static BakedModel bake(CuboidModel model) {
        int quadCount = 0;
        for (ModelPart part : model.parts()) {
            quadCount += part.boxes().size() * 6;
        }
        int vertexBytes = quadCount * 4 * VERTEX_BYTES;
        int indexCount = quadCount * 6;
        ByteBuffer data = ByteBuffer.allocateDirect(vertexBytes + indexCount * 4).order(ByteOrder.nativeOrder());
        int[] vertex = {0};
        faces(model, (part, positions, uvs, nx, ny, nz) -> {
            for (int corner = 0; corner < 4; corner++) {
                int offset = vertex[0] * VERTEX_BYTES;
                data.putFloat(offset, positions[corner * 3]);
                data.putFloat(offset + 4, positions[corner * 3 + 1]);
                data.putFloat(offset + 8, positions[corner * 3 + 2]);
                data.putFloat(offset + 12, uvs[corner * 2]);
                data.putFloat(offset + 16, uvs[corner * 2 + 1]);
                data.put(offset + 20, snorm(nx));
                data.put(offset + 21, snorm(ny));
                data.put(offset + 22, snorm(nz));
                data.put(offset + 23, (byte) 0);
                data.putInt(offset + 24, part);
                vertex[0]++;
            }
        });
        int indexOffset = vertexBytes;
        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * 4;
            data.putInt(indexOffset, base);
            data.putInt(indexOffset + 4, base + 1);
            data.putInt(indexOffset + 8, base + 2);
            data.putInt(indexOffset + 12, base);
            data.putInt(indexOffset + 16, base + 2);
            data.putInt(indexOffset + 20, base + 3);
            indexOffset += 24;
        }
        data.clear();
        return new BakedModel(model.id(), data, vertexBytes, indexCount, model.partCount());
    }

    private static void face(FaceSink sink, CuboidModel model, int part, boolean flip, float[][] corners,
                             float[] positions, float[] uvs, int a, int b, int c, int d, int u1, int v1, int u2,
                             int v2) {
        // TexturedQuad assigns (u2,v1), (u1,v1), (u1,v2), (u2,v2) to its four vertices in order.
        int[] order = {a, b, c, d};
        float[] us = {u2, u1, u1, u2};
        float[] vs = {v1, v1, v2, v2};
        if (flip) {
            reverse(order);
            reverse(us);
            reverse(vs);
        }
        float[] p0 = corners[order[0]];
        float[] p1 = corners[order[1]];
        float[] p2 = corners[order[2]];
        // Vanilla normal: (p2 - p1) x (p0 - p1).
        float ax = p2[0] - p1[0], ay = p2[1] - p1[1], az = p2[2] - p1[2];
        float bx = p0[0] - p1[0], by = p0[1] - p1[1], bz = p0[2] - p1[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0f) {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        for (int corner = 0; corner < 4; corner++) {
            float[] p = corners[order[corner]];
            positions[corner * 3] = p[0] / UNITS_PER_BLOCK;
            positions[corner * 3 + 1] = p[1] / UNITS_PER_BLOCK;
            positions[corner * 3 + 2] = p[2] / UNITS_PER_BLOCK;
            uvs[corner * 2] = us[corner] / model.textureWidth();
            uvs[corner * 2 + 1] = vs[corner] / model.textureHeight();
        }
        sink.face(part, positions, uvs, nx, ny, nz);
    }

    private static byte snorm(float value) {
        return (byte) Math.round(Math.max(-1f, Math.min(1f, value)) * 127f);
    }

    private static void set(float[] target, float x, float y, float z) {
        target[0] = x;
        target[1] = y;
        target[2] = z;
    }

    private static void reverse(int[] values) {
        for (int left = 0, right = values.length - 1; left < right; left++, right--) {
            int swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
    }

    private static void reverse(float[] values) {
        for (int left = 0, right = values.length - 1; left < right; left++, right--) {
            float swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
    }

}
