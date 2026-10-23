package alkaline.renderer.font;

import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.texture.TexturedMeshData;
import java.util.ArrayList;
import java.util.Objects;

/** Shapes text from a glyph atlas into a single texture batch. */
public final class GlyphTextLayout {
    private static final int MAX_TEXT_LENGTH = 8_192;

    private GlyphTextLayout() {
    }

    public static TexturedMeshData layout(GlyphAtlas atlas, String text, float x, float y, float depth,
                                          float scale, int argb, boolean antialiasing) {
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text exceeds the supported glyph layout length.");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(depth)
                || !Float.isFinite(scale) || scale <= 0f || scale > 64f) {
            throw new IllegalArgumentException("Text position and scale must be finite, with scale in (0, 64].");
        }
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> indices = new ArrayList<>();
        float cursorX = x;
        float cursorY = y;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        int alpha = (argb >>> 24) & 0xff;
        float colorRed = red / 255f;
        float colorGreen = green / 255f;
        float colorBlue = blue / 255f;
        float colorAlpha = alpha / 255f;
        int atlasWidth = atlas.image().width();
        int atlasHeight = atlas.image().height();
        for (int codePoint : text.codePoints().toArray()) {
            if (codePoint == '\n') {
                cursorX = x;
                cursorY += atlas.lineHeight() * scale;
                continue;
            }
            GlyphAtlas.Glyph glyph = atlas.glyph(codePoint);
            if (glyph == null) {
                continue;
            }
            if (glyph.width() > 0 && glyph.height() > 0) {
                int gx = glyph.x();
                int gy = glyph.y();
                if ((long) gx + glyph.width() > atlasWidth || (long) gy + glyph.height() > atlasHeight) {
                    throw new IllegalArgumentException("Glyph rectangle lies outside its atlas.");
                }
                float left = cursorX + glyph.bearingX() * scale;
                float top = cursorY + (glyph.bearingY() - glyph.height()) * scale;
                float right = left + glyph.width() * scale;
                float bottom = top + glyph.height() * scale;
                float u0 = (float) gx / atlasWidth;
                float v0 = (float) gy / atlasHeight;
                float u1 = (float) (gx + glyph.width()) / atlasWidth;
                float v1 = (float) (gy + glyph.height()) / atlasHeight;
                int first = vertices.size() / TexturedMeshData.FLOATS_PER_VERTEX;
                appendVertex(vertices, left, top, depth, u0, v0, colorRed, colorGreen, colorBlue, colorAlpha);
                appendVertex(vertices, right, top, depth, u1, v0, colorRed, colorGreen, colorBlue, colorAlpha);
                appendVertex(vertices, right, bottom, depth, u1, v1, colorRed, colorGreen, colorBlue, colorAlpha);
                appendVertex(vertices, left, bottom, depth, u0, v1, colorRed, colorGreen, colorBlue, colorAlpha);
                indices.add(first);
                indices.add(first + 1);
                indices.add(first + 2);
                indices.add(first);
                indices.add(first + 2);
                indices.add(first + 3);
            }
            cursorX += glyph.advance() * scale;
        }
        float[] vertexArray = new float[vertices.size()];
        for (int index = 0; index < vertexArray.length; index++) {
            vertexArray[index] = vertices.get(index);
        }
        int[] indexArray = indices.stream().mapToInt(Integer::intValue).toArray();
        if (indexArray.length == 0) {
            throw new IllegalArgumentException("Text layout contains no drawable glyphs.");
        }
        return new TexturedMeshData(atlas.image(), new ImageDisplayOptions(antialiasing, 1f),
                vertexArray, indexArray);
    }

    private static void appendVertex(ArrayList<Float> vertices, float x, float y, float z, float u, float v,
                                     float red, float green, float blue, float alpha) {
        vertices.add(x);
        vertices.add(y);
        vertices.add(z);
        vertices.add(u);
        vertices.add(v);
        vertices.add(red);
        vertices.add(green);
        vertices.add(blue);
        vertices.add(alpha);
    }
}
