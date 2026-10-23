package alkaline.renderer.font;

import alkaline.renderer.image.ImageDisplayOptions;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.texture.TexturedMeshData;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;

/** Shapes the vanilla 1.8.9 ASCII atlas using its legacy character map and fixed cells. */
public final class VanillaFontLayout {
    private static final int CELL_SIZE = 8;
    private static final int ATLAS_SIZE = 128;
    private static final int MAX_TEXT_LENGTH = 8_192;
    private static final String LEGACY_CHARACTERS =
            "\u00c0\u00c1\u00c2\u00c8\u00ca\u00cb\u00cd\u00d3\u00d4\u00d5\u00da\u00df\u00e3\u00f5\u011f\u0130\u0131\u0152\u0153\u015e\u015f\u0174\u0175\u017e\u0207"
                    + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000 !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
                    + "\u0000\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00f8\u00a3\u00d8\u00d7\u0192\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u00ae\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580\u03b1\u03b2\u0393\u03c0\u03a3\u03c3\u03bc\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u2205\u2208\u2229\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u0000";
    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private VanillaFontLayout() {
    }

    public static int atlasIndex(char character) {
        return LEGACY_CHARACTERS.indexOf(character);
    }

    /** The atlas must come from the user's legal 1.8.9 installation; this module does not bundle game assets. */
    public static TexturedMeshData layout(RgbaImage atlas, String text, float x, float y, float depth, int argb) {
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(text, "text");
        if (atlas.width() != ATLAS_SIZE || atlas.height() != ATLAS_SIZE) {
            throw new IllegalArgumentException("Vanilla ASCII font requires a 128x128 atlas.");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text exceeds the supported vanilla-font layout length.");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(depth)) {
            throw new IllegalArgumentException("Text coordinates must be finite.");
        }
        float[] advances = measureAdvances(atlas);
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> indices = new ArrayList<>();
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
            if (character < 0x20) {
                throw new IllegalArgumentException("Control characters are not supported by the vanilla ASCII atlas.");
            }
            int code = LEGACY_CHARACTERS.indexOf(character);
            if (code < 0) {
                throw new IllegalArgumentException("Unsupported character for the vanilla font atlas.");
            }
            if (advances[code] == 0f) {
                continue;
            }
            int cellX = code % 16 * CELL_SIZE;
            int cellY = code / 16 * CELL_SIZE;
            float left = cursorX;
            float top = cursorY;
            float right = left + 6.99f;
            float bottom = top + 7.99f;
            float shear = italic ? 1f : 0f;
            float u0 = (float) cellX / ATLAS_SIZE;
            float u1 = (cellX + 6.99f) / ATLAS_SIZE;
            float v0 = (float) cellY / ATLAS_SIZE;
            float v1 = (cellY + 7.99f) / ATLAS_SIZE;
            appendQuad(vertices, indices, left + shear, top, right + shear, top,
                    right - shear, bottom, left - shear, bottom, depth, u0, v0, u1, v1, color);
            if (bold) {
                appendQuad(vertices, indices, left + 1 + shear, top, right + 1 + shear, top,
                        right + 1 - shear, bottom, left + 1 - shear, bottom, depth, u0, v0, u1, v1, color);
            }
            if (underline) {
                appendSolidQuad(vertices, indices, left, bottom - 1, right + (bold ? 1 : 0), bottom, depth, color);
            }
            if (strike) {
                appendSolidQuad(vertices, indices, left, top + 3, right + (bold ? 1 : 0), top + 4, depth, color);
            }
            cursorX += advances[code] + (bold ? 1f : 0f);
        }
        if (indices.isEmpty()) {
            throw new IllegalArgumentException("Text layout contains no drawable vanilla glyphs.");
        }
        return new TexturedMeshData(atlas, ImageDisplayOptions.pixelPerfect(), toArray(vertices),
                indices.stream().mapToInt(Integer::intValue).toArray());
    }

    private static float[] measureAdvances(RgbaImage atlas) {
        ByteBuffer pixels = atlas.pixels();
        float[] advances = new float[256];
        for (int code = 0; code < advances.length; code++) {
            int lastColumn = -1;
            int cellX = code % 16 * CELL_SIZE;
            int cellY = code / 16 * CELL_SIZE;
            for (int column = CELL_SIZE - 1; column >= 0 && lastColumn < 0; column--) {
                for (int row = 0; row < CELL_SIZE; row++) {
                    int alphaOffset = ((cellY + row) * ATLAS_SIZE + cellX + column) * 4 + 3;
                    if ((pixels.get(alphaOffset) & 0xff) > 16) {
                        lastColumn = column;
                        break;
                    }
                }
            }
            advances[code] = code == 32 ? 4f : Math.max(0f, lastColumn + 2f);
        }
        return advances;
    }

    private static void appendSolidQuad(ArrayList<Float> vertices, ArrayList<Integer> indices,
                                        float left, float top, float right, float bottom, float depth, int argb) {
        appendQuad(vertices, indices, left, top, right, top, right, bottom, left, bottom,
                depth, 0f, 0f, 1f, 1f, argb);
    }

    private static void appendQuad(ArrayList<Float> vertices, ArrayList<Integer> indices,
                                   float leftTopX, float leftTopY, float rightTopX, float rightTopY,
                                   float rightBottomX, float rightBottomY, float leftBottomX, float leftBottomY,
                                   float depth, float u0, float v0, float u1, float v1, int argb) {
        int first = vertices.size() / TexturedMeshData.FLOATS_PER_VERTEX;
        float red = ((argb >>> 16) & 0xff) / 255f;
        float green = ((argb >>> 8) & 0xff) / 255f;
        float blue = (argb & 0xff) / 255f;
        float alpha = ((argb >>> 24) & 0xff) / 255f;
        appendVertex(vertices, leftTopX, leftTopY, depth, u0, v0, red, green, blue, alpha);
        appendVertex(vertices, rightTopX, rightTopY, depth, u1, v0, red, green, blue, alpha);
        appendVertex(vertices, rightBottomX, rightBottomY, depth, u1, v1, red, green, blue, alpha);
        appendVertex(vertices, leftBottomX, leftBottomY, depth, u0, v1, red, green, blue, alpha);
        indices.add(first);
        indices.add(first + 1);
        indices.add(first + 2);
        indices.add(first);
        indices.add(first + 2);
        indices.add(first + 3);
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

    private static float[] toArray(ArrayList<Float> values) {
        float[] result = new float[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = values.get(index);
        return result;
    }
}
