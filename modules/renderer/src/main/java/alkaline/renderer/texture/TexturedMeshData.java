package alkaline.renderer.texture;

import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.image.RgbaImage;
import java.util.Objects;

/** Immutable CPU-side data for one indexed batch that samples one RGBA texture. */
public final class TexturedMeshData {
    public static final int FLOATS_PER_VERTEX = 9;
    private final RgbaImage texture;
    private final ImageDisplayOptions options;
    private final float[] vertices;
    private final int[] indices;

    public TexturedMeshData(RgbaImage texture, ImageDisplayOptions options, float[] xyZUvRgbaVertices, int[] indices) {
        this.texture = Objects.requireNonNull(texture, "texture");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(xyZUvRgbaVertices, "xyZUvRgbaVertices");
        Objects.requireNonNull(indices, "indices");
        if (xyZUvRgbaVertices.length == 0 || xyZUvRgbaVertices.length % FLOATS_PER_VERTEX != 0
                || xyZUvRgbaVertices.length > 2_000_000) {
            throw new IllegalArgumentException("Textured vertices must contain at most 2,000,000 floats in nine-float records.");
        }
        if (indices.length == 0 || indices.length % 3 != 0 || indices.length > 1_000_000) {
            throw new IllegalArgumentException("Textured meshes require at most 1,000,000 triangle indices.");
        }
        int vertexCount = xyZUvRgbaVertices.length / FLOATS_PER_VERTEX;
        for (int offset = 0; offset < xyZUvRgbaVertices.length; offset += FLOATS_PER_VERTEX) {
            for (int component = 0; component < FLOATS_PER_VERTEX; component++) {
                if (!Float.isFinite(xyZUvRgbaVertices[offset + component])) {
                    throw new IllegalArgumentException("Textured vertex components must be finite.");
                }
            }
            if (xyZUvRgbaVertices[offset + 3] < 0f || xyZUvRgbaVertices[offset + 3] > 1f
                    || xyZUvRgbaVertices[offset + 4] < 0f || xyZUvRgbaVertices[offset + 4] > 1f) {
                throw new IllegalArgumentException("Texture coordinates must be normalized to the range zero to one.");
            }
            for (int channel = 5; channel < FLOATS_PER_VERTEX; channel++) {
                if (xyZUvRgbaVertices[offset + channel] < 0f || xyZUvRgbaVertices[offset + channel] > 1f) {
                    throw new IllegalArgumentException("Vertex color components must be normalized.");
                }
            }
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Textured mesh index is outside the vertex array.");
            }
        }
        vertices = xyZUvRgbaVertices.clone();
        this.indices = indices.clone();
    }

    public RgbaImage texture() {
        return texture;
    }

    public ImageDisplayOptions options() {
        return options;
    }

    public float[] copyVertices() {
        return vertices.clone();
    }

    public int[] copyIndices() {
        return indices.clone();
    }

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public int indexCount() {
        return indices.length;
    }
}
