package alkaline.renderer.model;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Static model geometry in the model renderer's vertex format, ready to upload once: {@code data[0,
 * vertexBytes)} holds 28-byte vertices (position 3 x f32 in blocks, uv 2 x f32 normalized, normal 4 x snorm8,
 * part index u32), the rest uint32 triangle indices. Cuboid entity models ({@link ModelMesher}) and extruded
 * item sprites ({@link ItemModels}) both bake to this, so one instanced pipeline draws every model.
 *
 * @param partCount transforms each instance supplies; vertices name their part
 */
public record BakedModel(String id, ByteBuffer data, int vertexBytes, int indexCount, int partCount) {
    public static final int VERTEX_BYTES = 28;

    public BakedModel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        if (vertexBytes % VERTEX_BYTES != 0 || indexCount % 3 != 0 || partCount < 1) {
            throw new IllegalArgumentException("Baked model data is malformed.");
        }
    }

    public int vertexCount() {
        return vertexBytes / VERTEX_BYTES;
    }

    /** Builds vertex and index data quad by quad (indices 0-1-2, 0-2-3). */
    static final class Builder {
        private ByteBuffer vertices = java.nio.ByteBuffer.allocate(64 * 4 * VERTEX_BYTES)
                .order(java.nio.ByteOrder.nativeOrder());
        private int quads;

        /** One quad; corners in order, wound counter-clockwise when seen from the side the normal faces. */
        void quad(int part, float[] corners, float[] uvs, float nx, float ny, float nz) {
            if (vertices.remaining() < 4 * VERTEX_BYTES) {
                ByteBuffer grown = ByteBuffer.allocate(vertices.capacity() * 2).order(java.nio.ByteOrder.nativeOrder());
                vertices.flip();
                grown.put(vertices);
                vertices = grown;
            }
            for (int corner = 0; corner < 4; corner++) {
                vertices.putFloat(corners[corner * 3]).putFloat(corners[corner * 3 + 1])
                        .putFloat(corners[corner * 3 + 2]);
                vertices.putFloat(uvs[corner * 2]).putFloat(uvs[corner * 2 + 1]);
                vertices.put(snorm(nx)).put(snorm(ny)).put(snorm(nz)).put((byte) 0);
                vertices.putInt(part);
            }
            quads++;
        }

        BakedModel build(String id, int partCount) {
            int vertexBytes = quads * 4 * VERTEX_BYTES;
            ByteBuffer data = ByteBuffer.allocateDirect(vertexBytes + quads * 6 * Integer.BYTES)
                    .order(java.nio.ByteOrder.nativeOrder());
            vertices.flip();
            data.put(vertices);
            for (int quad = 0; quad < quads; quad++) {
                int base = quad * 4;
                data.putInt(base).putInt(base + 1).putInt(base + 2).putInt(base).putInt(base + 2).putInt(base + 3);
            }
            data.clear();
            return new BakedModel(id, data, vertexBytes, quads * 6, partCount);
        }

        private static byte snorm(float value) {
            return (byte) Math.round(Math.max(-1f, Math.min(1f, value)) * 127f);
        }
    }
}
