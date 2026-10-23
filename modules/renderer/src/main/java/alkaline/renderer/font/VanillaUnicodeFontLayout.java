package alkaline.renderer.font;

import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.texture.TexturedMeshData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shapes 1.8.9 Unicode font pages using the packed glyph_sizes.bin metrics. */
public final class VanillaUnicodeFontLayout {
    private static final int PAGE_SIZE = 256;
    private static final int CELL_SIZE = 16;
    private static final int MAX_TEXT_LENGTH = 8_192;
    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private VanillaUnicodeFontLayout() {
    }

    /** The caller supplies Unicode page images keyed by their high-byte page number. */
    public static List<TexturedMeshData> layout(Map<Integer, RgbaImage> pages, byte[] glyphSizes,
                                                 String text, float x, float y, float depth, int argb) {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(glyphSizes, "glyphSizes");
        Objects.requireNonNull(text, "text");
        if (glyphSizes.length != 65_536 || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Unicode font layout requires the 65536-byte metrics table and bounded text.");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(depth)) {
            throw new IllegalArgumentException("Text coordinates must be finite.");
        }
        Map<Integer, BatchBuilder> batches = new LinkedHashMap<>();
        float cursorX = x;
        float cursorY = y;
        int color = argb;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\u00a7' && index + 1 < text.length()) {
                char format = Character.toLowerCase(text.charAt(++index));
                int colorIndex = "0123456789abcdefklmnor".indexOf(format);
                if (colorIndex < 16) {
                    if (colorIndex < 0) colorIndex = 15;
                    color = (argb & 0xff000000) | COLORS[colorIndex];
                    bold = false;
                    italic = false;
                    underline = false;
                    strike = false;
                } else {
                    switch (format) {
                        case 'k' -> { }
                        case 'l' -> bold = true;
                        case 'm' -> strike = true;
                        case 'n' -> underline = true;
                        case 'o' -> italic = true;
                        case 'r' -> {
                            color = argb;
                            bold = false;
                            italic = false;
                            underline = false;
                            strike = false;
                        }
                        default -> { }
                    }
                }
                continue;
            }
            if (character == '\n') {
                cursorX = x;
                cursorY += 9f;
                continue;
            }
            int metrics = glyphSizes[character] & 0xff;
            if (metrics == 0) {
                continue;
            }
            int left = metrics >>> 4;
            int right = (metrics & 0x0f) + 1;
            if (right <= left) {
                continue;
            }
            int pageNumber = character >>> 8;
            RgbaImage page = pages.get(pageNumber);
            if (page == null) {
                throw new IllegalArgumentException("Missing Unicode font page " + Integer.toHexString(pageNumber) + ".");
            }
            if (page.width() != PAGE_SIZE || page.height() != PAGE_SIZE) {
                throw new IllegalArgumentException("Unicode font pages must be 256x256 pixels.");
            }
            int originX = (character & 0x0f) * CELL_SIZE;
            int originY = ((character & 0xff) >>> 4) * CELL_SIZE;
            float glyphWidth = right - left - 0.02f;
            float advance = (right - left) / 2f + 1f;
            float leftX = cursorX;
            float rightX = leftX + glyphWidth / 2f;
            float shear = italic ? 1f : 0f;
            float top = cursorY;
            float bottom = top + 7.99f;
            float u0 = (originX + left) / (float) PAGE_SIZE;
            float u1 = (originX + right - 0.02f) / PAGE_SIZE;
            float v0 = originY / (float) PAGE_SIZE;
            float v1 = (originY + 15.98f) / PAGE_SIZE;
            BatchBuilder batch = batches.computeIfAbsent(pageNumber, ignored -> new BatchBuilder(page));
            batch.quad(leftX + shear, top, rightX + shear, top, rightX - shear, bottom,
                    leftX - shear, bottom, depth, u0, v0, u1, v1, color);
            if (bold) {
                batch.quad(leftX + 1 + shear, top, rightX + 1 + shear, top,
                        rightX + 1 - shear, bottom, leftX + 1 - shear, bottom,
                        depth, u0, v0, u1, v1, color);
            }
            if (underline) {
                batch.solidQuad(leftX, bottom - 1, rightX + (bold ? 1 : 0), bottom, depth, color);
            }
            if (strike) {
                batch.solidQuad(leftX, top + 3, rightX + (bold ? 1 : 0), top + 4, depth, color);
            }
            cursorX += advance + (bold ? 1f : 0f);
        }
        ArrayList<TexturedMeshData> output = new ArrayList<>(batches.size());
        for (BatchBuilder batch : batches.values()) {
            output.add(batch.finish());
        }
        return List.copyOf(output);
    }

    private static final class BatchBuilder {
        private final RgbaImage image;
        private final ArrayList<Float> vertices = new ArrayList<>();
        private final ArrayList<Integer> indices = new ArrayList<>();

        private BatchBuilder(RgbaImage image) {
            this.image = image;
        }

        private void solidQuad(float left, float top, float right, float bottom, float depth, int argb) {
            quad(left, top, right, top, right, bottom, left, bottom, depth,
                    0f, 0f, 1f / PAGE_SIZE, 1f / PAGE_SIZE, argb);
        }

        private void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                          float z, float u0, float v0, float u1, float v1, int argb) {
            int first = vertices.size() / TexturedMeshData.FLOATS_PER_VERTEX;
            float red = ((argb >>> 16) & 0xff) / 255f;
            float green = ((argb >>> 8) & 0xff) / 255f;
            float blue = (argb & 0xff) / 255f;
            float alpha = ((argb >>> 24) & 0xff) / 255f;
            vertex(x0, y0, z, u0, v0, red, green, blue, alpha);
            vertex(x1, y1, z, u1, v0, red, green, blue, alpha);
            vertex(x2, y2, z, u1, v1, red, green, blue, alpha);
            vertex(x3, y3, z, u0, v1, red, green, blue, alpha);
            indices.add(first);
            indices.add(first + 1);
            indices.add(first + 2);
            indices.add(first);
            indices.add(first + 2);
            indices.add(first + 3);
        }

        private void vertex(float x, float y, float z, float u, float v,
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

        private TexturedMeshData finish() {
            float[] vertexArray = new float[vertices.size()];
            for (int index = 0; index < vertexArray.length; index++) {
                vertexArray[index] = vertices.get(index);
            }
            int[] indexArray = indices.stream().mapToInt(Integer::intValue).toArray();
            return new TexturedMeshData(image, ImageDisplayOptions.pixelPerfect(), vertexArray, indexArray);
        }
    }
}
