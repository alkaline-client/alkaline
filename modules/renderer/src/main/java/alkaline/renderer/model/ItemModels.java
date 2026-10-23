package alkaline.renderer.model;

import alkaline.renderer.image.RgbaImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a flat item sprite into a 3D model one pixel thick, the shape vanilla gives generated items (front and
 * back at z = 7.5/16 and 8.5/16 of the item's block space, edges where opaque pixels meet transparent ones).
 *
 * <p>Vanilla 1.8.9 builds the front and back as two whole 16x16 quads that rely on alpha testing, and the edges
 * as quads spanning runs of a row or column, with texture coordinates pulled 0.1% toward each face's center.
 * The visible pixel boundaries of the front are therefore decided by texture sampling while the edges are
 * decided by geometry, and the two never meet exactly: thin gaps show between faces of a held item, and flicker
 * as it moves. {@link #extrude} instead builds a closed surface on the pixel grid. Each opaque pixel gets its own
 * front and back quad, and each pixel edge on the silhouette its own side quad, all with corners computed from
 * the same integer grid, so every edge is shared by exactly two quads with bit-identical endpoints. Rasterization
 * rules then guarantee that no pixel falls between them. Every quad samples the center of its texel, so filtering
 * can never bleed a neighbor's color or transparency in, and no alpha test is needed. The cost is a few hundred
 * quads per item, built once.
 *
 * <p>{@link #vanilla} reproduces vanilla's geometry for side-by-side comparison only.
 */
public final class ItemModels {
    /** Item depth in block units: the sprite plane sits at z = 7.5/16 .. 8.5/16. */
    private static final float BACK_Z = 7.5f / 16f;
    private static final float FRONT_Z = 8.5f / 16f;

    private ItemModels() {
    }

    /** Alkaline's gap-free extrusion of an item sprite (see the class comment). Pixels with alpha 0 are empty. */
    public static BakedModel extrude(String id, RgbaImage sprite) {
        Objects.requireNonNull(sprite, "sprite");
        int width = sprite.width();
        int height = sprite.height();
        byte[] pixels = sprite.copyPixels();
        BakedModel.Builder builder = new BakedModel.Builder();
        float[] corners = new float[12];
        float[] uvs = new float[8];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (!opaque(pixels, width, height, column, row)) {
                    continue;
                }
                // Grid coordinates: x grows with columns, y with rows counted from the bottom.
                float x0 = column / (float) width;
                float x1 = (column + 1) / (float) width;
                float y0 = (height - row - 1) / (float) height;
                float y1 = (height - row) / (float) height;
                float u = (column + 0.5f) / width;
                float v = (row + 0.5f) / height;
                for (int corner = 0; corner < 4; corner++) {
                    uvs[corner * 2] = u;
                    uvs[corner * 2 + 1] = v;
                }
                builder.quad(0, set(corners, x0, y0, FRONT_Z, x1, y0, FRONT_Z, x1, y1, FRONT_Z, x0, y1, FRONT_Z),
                        uvs, 0, 0, 1);
                builder.quad(0, set(corners, x1, y0, BACK_Z, x0, y0, BACK_Z, x0, y1, BACK_Z, x1, y1, BACK_Z),
                        uvs, 0, 0, -1);
                if (!opaque(pixels, width, height, column - 1, row)) {
                    builder.quad(0, set(corners, x0, y0, BACK_Z, x0, y0, FRONT_Z, x0, y1, FRONT_Z, x0, y1, BACK_Z),
                            uvs, -1, 0, 0);
                }
                if (!opaque(pixels, width, height, column + 1, row)) {
                    builder.quad(0, set(corners, x1, y0, FRONT_Z, x1, y0, BACK_Z, x1, y1, BACK_Z, x1, y1, FRONT_Z),
                            uvs, 1, 0, 0);
                }
                if (!opaque(pixels, width, height, column, row - 1)) {
                    builder.quad(0, set(corners, x0, y1, FRONT_Z, x1, y1, FRONT_Z, x1, y1, BACK_Z, x0, y1, BACK_Z),
                            uvs, 0, 1, 0);
                }
                if (!opaque(pixels, width, height, column, row + 1)) {
                    builder.quad(0, set(corners, x0, y0, BACK_Z, x1, y0, BACK_Z, x1, y0, FRONT_Z, x0, y0, FRONT_Z),
                            uvs, 0, -1, 0);
                }
            }
        }
        return builder.build(id, 1);
    }

    private static boolean opaque(byte[] pixels, int width, int height, int column, int row) {
        return column >= 0 && row >= 0 && column < width && row < height
                && pixels[(row * width + column) * 4 + 3] != 0;
    }

    private static float[] set(float[] target, float... values) {
        System.arraycopy(values, 0, target, 0, 12);
        return target;
    }

    // ---- vanilla reference --------------------------------------------------------------------------------

    private enum Facing { UP, DOWN, WEST, EAST, NORTH, SOUTH }

    /** An edge run as vanilla tracks it: facing, the row or column it lies on, and the range it covers. */
    private static final class Span {
        final int facing;
        final int anchor;
        int min;
        int max;

        Span(int facing, int anchor, int position) {
            this.facing = facing;
            this.anchor = anchor;
            min = position;
            max = position;
        }
    }

    // Vanilla's span kinds: up, down, left (its quad is emitted as an east face), right (a west face).
    private static final int UP = 0;
    private static final int DOWN = 1;
    private static final int LEFT = 2;
    private static final int RIGHT = 3;
    private static final int[] SPAN_DX = {0, 0, -1, 1};
    private static final int[] SPAN_DY = {-1, 1, 0, 0};

    /**
     * Vanilla 1.8.9's generated item geometry (ItemModelGenerator + FaceBakery) for comparison: alpha-tested
     * whole front and back quads, edge quads spanning each row's or column's extent (even across transparent
     * pixels), and texture coordinates contracted 0.1% toward each face's opposite corner.
     */
    public static BakedModel vanilla(String id, RgbaImage sprite) {
        int width = sprite.width();
        int height = sprite.height();
        byte[] pixels = sprite.copyPixels();
        BakedModel.Builder builder = new BakedModel.Builder();
        bakeFace(builder, Facing.SOUTH, 0, 0, 7.5f, 16, 16, 8.5f, new float[]{0, 0, 16, 16});
        bakeFace(builder, Facing.NORTH, 0, 0, 7.5f, 16, 16, 8.5f, new float[]{16, 0, 0, 16});
        List<Span> spans = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                if (!opaque(pixels, width, height, column, row)) {
                    continue;
                }
                for (int kind = 0; kind < 4; kind++) {
                    if (!opaque(pixels, width, height, column + SPAN_DX[kind], row + SPAN_DY[kind])) {
                        boolean vertical = kind == UP || kind == DOWN;
                        int anchor = vertical ? row : column;
                        int position = vertical ? column : row;
                        Span found = null;
                        for (Span span : spans) {
                            if (span.facing == kind && span.anchor == anchor) {
                                found = span;
                                break;
                            }
                        }
                        if (found == null) {
                            spans.add(new Span(kind, anchor, position));
                        } else {
                            found.min = Math.min(found.min, position);
                            found.max = Math.max(found.max, position);
                        }
                    }
                }
            }
        }
        float w = width;
        float h = height;
        for (Span span : spans) {
            float x0 = 0;
            float x1 = 0;
            float y0 = 0;
            float y1 = 0;
            float u0 = 0;
            float u1 = 0;
            float v0 = 0;
            float v1 = 0;
            float uScale;
            float vScale;
            float min = span.min;
            float max = span.max;
            float anchor = span.anchor;
            switch (span.facing) {
                case UP -> {
                    u0 = x0 = min;
                    u1 = x1 = max + 1;
                    v0 = y0 = anchor;
                    v1 = anchor;
                    y1 = anchor;
                    uScale = 16f / w;
                    vScale = 16f / (h - 1);
                }
                case DOWN -> {
                    v0 = v1 = anchor;
                    u0 = x0 = min;
                    u1 = x1 = max + 1;
                    y0 = y1 = anchor + 1;
                    uScale = 16f / w;
                    vScale = 16f / (h - 1);
                }
                case LEFT -> {
                    u0 = x0 = anchor;
                    u1 = x1 = anchor;
                    v1 = y0 = min;
                    v0 = y1 = max + 1;
                    uScale = 16f / (w - 1);
                    vScale = 16f / h;
                }
                default -> {
                    u0 = u1 = anchor;
                    x0 = x1 = anchor + 1;
                    v1 = y0 = min;
                    v0 = y1 = max + 1;
                    uScale = 16f / (w - 1);
                    vScale = 16f / h;
                }
            }
            x0 *= 16f / w;
            x1 *= 16f / w;
            y0 = 16f - y0 * (16f / h);
            y1 = 16f - y1 * (16f / h);
            float[] uv = {u0 * uScale, v0 * vScale, u1 * uScale, v1 * vScale};
            switch (span.facing) {
                case UP -> bakeFace(builder, Facing.UP, x0, y0, 7.5f, x1, y0, 8.5f, uv);
                case DOWN -> bakeFace(builder, Facing.DOWN, x0, y1, 7.5f, x1, y1, 8.5f, uv);
                case LEFT -> bakeFace(builder, Facing.EAST, x0, y0, 7.5f, x0, y1, 8.5f, uv);
                default -> bakeFace(builder, Facing.WEST, x1, y0, 7.5f, x1, y1, 8.5f, uv);
            }
        }
        return builder.build(id, 1);
    }

    /**
     * One face of a vanilla element (coordinates in 1/16 block, from/to unordered) with the face bakery's vertex
     * order and texture mapping (rotation 0), including its 0.999/0.001 contraction.
     */
    private static void bakeFace(BakedModel.Builder builder, Facing facing, float ax, float ay, float az,
                                 float bx, float by, float bz, float[] uv) {
        float west = Math.min(ax, bx) / 16f;
        float east = Math.max(ax, bx) / 16f;
        float down = Math.min(ay, by) / 16f;
        float up = Math.max(ay, by) / 16f;
        float north = Math.min(az, bz) / 16f;
        float south = Math.max(az, bz) / 16f;
        float[] corners = switch (facing) {
            case DOWN -> new float[]{west, down, south, west, down, north, east, down, north, east, down, south};
            case UP -> new float[]{west, up, north, west, up, south, east, up, south, east, up, north};
            case NORTH -> new float[]{east, up, north, east, down, north, west, down, north, west, up, north};
            case SOUTH -> new float[]{west, up, south, west, down, south, east, down, south, east, up, south};
            case WEST -> new float[]{west, up, north, west, down, north, west, down, south, west, up, south};
            case EAST -> new float[]{east, up, south, east, down, south, east, down, north, east, up, north};
        };
        float[] us = {uv[0], uv[0], uv[2], uv[2]};
        float[] vs = {uv[1], uv[3], uv[3], uv[1]};
        float[] uvs = new float[8];
        for (int vertex = 0; vertex < 4; vertex++) {
            int opposite = (vertex + 2) % 4;
            uvs[vertex * 2] = (float) (us[vertex] * 0.999 + us[opposite] * 0.001) / 16f;
            uvs[vertex * 2 + 1] = (float) (vs[vertex] * 0.999 + vs[opposite] * 0.001) / 16f;
        }
        float nx = facing == Facing.EAST ? 1 : facing == Facing.WEST ? -1 : 0;
        float ny = facing == Facing.UP ? 1 : facing == Facing.DOWN ? -1 : 0;
        float nz = facing == Facing.SOUTH ? 1 : facing == Facing.NORTH ? -1 : 0;
        builder.quad(0, corners, uvs, nx, ny, nz);
    }
}
