package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.world.vanilla.BiomeNames189;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * OptiFine / MCPatcher connected textures (CTM): {@code .properties} files under {@code optifine/ctm/} or
 * {@code mcpatcher/ctm/} that give matching block faces another tile chosen from their neighbors (glass that joins
 * into one pane, bookshelves, sandstone) or from their position (random and repeating patterns), or draw overlay
 * tiles over them where other blocks border them. Rules match by block (with metadata) or by the face's texture, on
 * chosen faces, heights and biomes; MCPatcher's file-name conventions ({@code block<id>.properties},
 * {@code <texture>.properties}) apply when neither is given.
 *
 * <p>Methods: {@code ctm} (47 tiles), {@code ctm_compact} (5 tiles split into quarters), {@code horizontal},
 * {@code vertical}, {@code horizontal+vertical}, {@code vertical+horizontal}, {@code top}, {@code random} (with
 * weights), {@code repeat}, {@code fixed}, and the overlays {@code overlay} (17 border tiles),
 * {@code overlay_ctm}, {@code overlay_fixed}, {@code overlay_random} and {@code overlay_repeat}. The tile tables are
 * the format's own (the indices every CTM pack is drawn for).
 *
 * <p>As OptiFine 1.8.9 orders them, rules that match the face's texture come before rules that match the block,
 * each group in file order. A face takes the first rule that gives it a tile; a rule outside its heights or biomes,
 * or whose tile is {@code <skip>}, passes the face on to the next rule. Overlay rules draw their tiles and always
 * pass the face on.
 */
public final class ConnectedTextures {
    public static final ConnectedTextures NONE = new ConnectedTextures(List.of());
    private static final String[] ROOTS = {"assets/minecraft/optifine/ctm/", "assets/minecraft/mcpatcher/ctm/"};
    /** A tile entry that keeps the face's own texture. */
    public static final String DEFAULT_TILE = "<default>";
    /** A tile entry that gives no tile, passing the face on to the next rule. */
    public static final String SKIP_TILE = "<skip>";

    public enum Method {
        CTM, HORIZONTAL, VERTICAL, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL, TOP, RANDOM, REPEAT, FIXED, CTM_COMPACT,
        OVERLAY, OVERLAY_CTM, OVERLAY_FIXED, OVERLAY_RANDOM, OVERLAY_REPEAT;

        /** Overlay methods draw extra tiles over the face instead of replacing its texture. */
        public boolean overlay() {
            return ordinal() >= OVERLAY.ordinal();
        }
    }

    /** How a neighbor joins: the same block state, the same texture on that face, or the same material. */
    public static final int CONNECT_BLOCK = 1;
    public static final int CONNECT_TILE = 2;
    public static final int CONNECT_MATERIAL = 3;

    /**
     * One rule.
     *
     * @param blocks        matched block ids (empty: any)
     * @param metadata      bit per allowed metadata value
     * @param textures      matched face textures as palette names ({@code blocks/glass}), or empty
     * @param faces         bit per face code (0 -x, 1 +x, 2 -y, 3 +y, 4 -z, 5 +z)
     * @param tiles         tile textures as full asset paths, {@link #DEFAULT_TILE} or {@link #SKIP_TILE}
     * @param symmetry      OptiFine's symmetry: 1 none, 2 opposite, 6 all (random)
     * @param heights       inclusive block y ranges as pairs, or empty for any height
     * @param biomes        a bit per biome id ({@link BiomeNames189#parse}), or null for any biome
     * @param compact       ctm_compact's {@code ctm.<index>} overrides: a tile per CTM index (-1 none), or null
     * @param connectBlocks overlays: the bordering blocks that draw ({@code id << 16 | metadata bits}), or empty
     * @param connectTiles  overlays: the bordering textures that draw, or empty
     * @param tintBlock     overlays: the block state ({@code id << 4 | meta}) whose tint colors the tiles, or -1
     * @param layer         overlays: the pass the tiles draw in (cutout or translucent)
     */
    public record Rule(String path, Method method, int[] blocks, int metadata, List<String> textures, int faces,
                       int connect, boolean innerSeams, List<String> tiles, int[] weights, int width, int height,
                       int symmetry, int randomLoops, boolean linked, int[] heights, long[] biomes, int[] compact,
                       int[] connectBlocks, List<String> connectTiles, int tintIndex, int tintBlock,
                       MaterialPass layer) {
        public boolean connectTile() {
            return connect == CONNECT_TILE;
        }

        public boolean matches(int block, int meta, String texture, int face) {
            return (faces >> face & 1) != 0 && matchesBlock(block, meta)
                    && (textures.isEmpty() || textures.contains(texture));
        }

        /** ConnectedProperties.matchesBlock: one of the blocks (any, without any), with an allowed metadata. */
        public boolean matchesBlock(int block, int meta) {
            if ((metadata >> meta & 1) == 0) {
                return false;
            }
            if (blocks.length == 0) {
                return true;
            }
            for (int id : blocks) {
                if (id == block) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the rule covers a block at height {@code y} in a biome. */
        public boolean applies(int y, int biome) {
            return inHeights(y) && BiomeNames189.contains(biomes, biome);
        }

        boolean inHeights(int y) {
            if (heights.length == 0) {
                return true;
            }
            for (int index = 0; index < heights.length; index += 2) {
                if (y >= heights[index] && y <= heights[index + 1]) {
                    return true;
                }
            }
            return false;
        }

        /** Overlays: whether a bordering block (state and its face's texture) is one that draws. */
        public boolean connectsTo(int block, int meta, String texture) {
            if (connectBlocks.length > 0) {
                boolean found = false;
                for (int entry : connectBlocks) {
                    found |= entry >>> 16 == block && (entry >> meta & 1) != 0;
                }
                if (!found) {
                    return false;
                }
            }
            return connectTiles.isEmpty() || connectTiles.contains(texture);
        }
    }

    private final List<Rule> rules;

    private ConnectedTextures(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    // ---- tile selection -------------------------------------------------------------------------------------

    /** The 47-tile index for 8 neighbor bits: 1 left, 2 down-left, 4 down, 8 down-right, 16 right, 32 up-right, 64 up, 128 up-left. */
    private static final int[] CTM = {
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
            16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
            36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
            37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
            1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
            36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
            16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
            36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
            37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
    };
    /** Horizontal (bits 1 left, 2 right) and vertical (1 down, 2 up) strips. */
    private static final int[] STRIP = {3, 2, 0, 1};
    /** horizontal+vertical without a horizontal neighbor: bits 1 down-left, 2 down, 4 down-right, 8 up-right, 16 up, 32 up-left. */
    private static final int[] HORIZONTAL_VERTICAL = {
            3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3,
            4, 4, 5, 4, 4, 4, 4, 4, 3, 3, 6, 3, 3, 3, 3, 3,
            3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3,
            3, 3, 6, 3, 3, 3, 3, 3, 3, 3, 6, 3, 3, 3, 3, 3,
    };
    /** vertical+horizontal without a vertical neighbor: bits 1 left, 2 down-left, 4 down-right, 8 right, 16 up-right, 32 up-left. */
    private static final int[] VERTICAL_HORIZONTAL = {
            3, 6, 3, 3, 3, 6, 3, 3, 4, 5, 4, 4, 3, 6, 3, 3,
            3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3, 3, 6, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    };
    /**
     * ConnectedTexturesCompact: per CTM index, the compact tile of each quarter of the face (up-left, up-right,
     * down-left, down-right in texture space).
     */
    private static final int[] COMPACT = {
            0, 0, 0, 0, 0, 3, 0, 3, 3, 3, 3, 3, 3, 0, 3, 0, 0, 3, 2, 4, 3, 0, 4, 2, 2, 4, 2, 4, 3, 3, 4, 4,
            4, 1, 4, 4, 4, 4, 4, 1, 1, 4, 1, 4, 1, 1, 4, 4, 0, 0, 2, 2, 0, 3, 2, 1, 3, 3, 1, 1, 3, 0, 1, 2,
            2, 4, 0, 3, 4, 2, 3, 0, 4, 4, 3, 3, 4, 2, 4, 2, 1, 4, 4, 4, 4, 4, 1, 4, 4, 4, 1, 1, 4, 1, 4, 1,
            2, 2, 2, 2, 2, 1, 2, 1, 1, 1, 1, 1, 1, 2, 1, 2, 2, 4, 2, 1, 3, 3, 1, 4, 2, 1, 2, 4, 3, 3, 4, 1,
            1, 1, 1, 4, 1, 1, 4, 1, 4, 1, 1, 4, 1, 4, 4, 1, 2, 2, 0, 0, 2, 1, 0, 3, 1, 1, 3, 3, 1, 2, 3, 0,
            4, 1, 3, 3, 1, 2, 4, 2, 1, 4, 3, 3, 4, 2, 1, 2, 1, 4, 1, 1, 4, 1, 1, 1, 4, 4, 4, 4,
    };

    /** Whether the block at an offset (dx, dy, dz) from the face's block joins it. */
    public interface Neighbors {
        boolean connects(int dx, int dy, int dz);

        /** The same block (id) at an offset, for {@code linked} random rules; none by default. */
        default boolean sameBlock(int dx, int dy, int dz) {
            return false;
        }
    }

    /** The bordering blocks an overlay rule looks at, with offsets from the face's block. */
    public interface OverlayNeighbors {
        /** ConnectedTextures.isNeighbourOverlay: a bordering block whose edge the overlay draws. */
        boolean overlay(int dx, int dy, int dz);

        /** isNeighbourMatching: a bordering block the rule itself matches. */
        boolean matching(int dx, int dy, int dz);
    }

    /**
     * The face's directions as seen from outside, each {x, y, z}: left, down, right, up. Up is north on top faces,
     * south on bottom faces and up on the sides; left follows from it.
     */
    static final int[][][] DIRECTIONS = new int[6][4][];
    /** OptiFine's side numbers (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) by face code. */
    private static final int[] SIDE = {4, 5, 0, 1, 2, 3};

    static {
        for (int face = 0; face < 6; face++) {
            int[] normal = new int[3];
            normal[face >> 1] = (face & 1) == 1 ? 1 : -1;
            int[] up = face == 3 ? new int[]{0, 0, -1} : face == 2 ? new int[]{0, 0, 1} : new int[]{0, 1, 0};
            int[] left = {normal[1] * up[2] - normal[2] * up[1], normal[2] * up[0] - normal[0] * up[2],
                    normal[0] * up[1] - normal[1] * up[0]};
            DIRECTIONS[face][0] = left;
            DIRECTIONS[face][1] = new int[]{-up[0], -up[1], -up[2]};
            DIRECTIONS[face][2] = new int[]{-left[0], -left[1], -left[2]};
            DIRECTIONS[face][3] = up;
        }
    }

    /**
     * The tile a face takes, or -1 for none (the face passes on to the next rule). {@code ctm_compact} and
     * {@code overlay_ctm} give the 47-tile CTM index; {@code overlay} gives none (see {@link #overlay}).
     *
     * @param face face code, with the block at world (x, y, z)
     */
    public static int tile(Rule rule, int face, int x, int y, int z, Neighbors neighbors) {
        if (!rule.inHeights(y)) {
            return -1;
        }
        int[][] d = DIRECTIONS[face];
        return switch (rule.method()) {
            case CTM, CTM_COMPACT, OVERLAY_CTM -> ctmIndex(rule, face, d, neighbors);
            case HORIZONTAL -> STRIP[horizontal(neighbors, d)];
            case VERTICAL -> STRIP[vertical(neighbors, d)];
            case HORIZONTAL_VERTICAL -> {
                int bits = horizontal(neighbors, d);
                if (bits != 0) {
                    yield STRIP[bits];
                }
                int secondary = 0;
                for (int i = 0; i < 2; i++) {
                    int[] vertical = d[i * 2 + 1];
                    if (connects(neighbors, vertical, null)) {
                        secondary |= 1 << i * 3 + 1;
                        for (int j = 0; j < 2; j++) {
                            if (connects(neighbors, vertical, d[((i + j) % 2) * 2])) {
                                secondary |= 1 << i * 3 + j * 2;
                            }
                        }
                    }
                }
                yield HORIZONTAL_VERTICAL[secondary];
            }
            case VERTICAL_HORIZONTAL -> {
                int bits = vertical(neighbors, d);
                if (bits != 0) {
                    yield STRIP[bits];
                }
                int secondary = 0;
                for (int i = 0; i < 2; i++) {
                    int[] horizontal = d[i * 2];
                    if (connects(neighbors, horizontal, null)) {
                        secondary |= 1 << i * 3;
                        for (int j = 0; j < 2; j++) {
                            if (connects(neighbors, horizontal, d[((i + j) % 2) * 2 + 1])) {
                                secondary |= 1 << (i * 3 + j * 2 + 5) % 6;
                            }
                        }
                    }
                }
                yield VERTICAL_HORIZONTAL[secondary];
            }
            case TOP -> face >> 1 != 1 && connects(neighbors, new int[]{0, 1, 0}, null) ? 0 : -1;
            case FIXED, OVERLAY_FIXED -> 0;
            case RANDOM, OVERLAY_RANDOM -> random(rule, face, x, y, z, neighbors);
            case REPEAT, OVERLAY_REPEAT -> repeat(rule, face, x, y, z);
            case OVERLAY -> -1;
        };
    }

    private static int ctmIndex(Rule rule, int face, int[][] d, Neighbors neighbors) {
        int bits = 0;
        for (int side = 0; side < 4; side++) {
            if (ctmJoins(neighbors, rule, face, d[side], null)) {
                bits |= 1 << side * 2;
            }
        }
        for (int side = 0; side < 4; side++) {
            int next = (side + 1) & 3;
            if ((bits >> side * 2 & 1) != 0 && (bits >> next * 2 & 1) != 0
                    && ctmJoins(neighbors, rule, face, d[side], d[next])) {
                bits |= 1 << side * 2 + 1;
            }
        }
        return CTM[bits];
    }

    /**
     * ctm_compact: the tile of one quarter of the face (0 up-left, 1 up-right, 2 down-left, 3 down-right in
     * texture space) for a CTM index; a {@code ctm.<index>} override gives the whole face one tile.
     */
    public static int compactTile(Rule rule, int ctmIndex, int quarter) {
        if (rule.compact() != null && ctmIndex >= 0 && ctmIndex < rule.compact().length && rule.compact()[ctmIndex] >= 0) {
            return rule.compact()[ctmIndex];
        }
        return ctmIndex >= 0 && ctmIndex < 47 ? COMPACT[ctmIndex * 4 + quarter] : 0;
    }

    /**
     * ConnectedTextures.getConnectedTextureOverlay: the border tiles of an {@code overlay} rule for a face, written
     * to {@code out} (room for two); returns how many.
     */
    public static int overlay(Rule rule, int face, int y, OverlayNeighbors neighbors, int[] out) {
        if (!rule.inHeights(y)) {
            return 0;
        }
        int[][] d = DIRECTIONS[face];
        // OptiFine's order: left, right, down, up; corners down-right, down-left, up-right, up-left.
        boolean left = overlay(neighbors, d[0], null);
        boolean right = overlay(neighbors, d[2], null);
        boolean down = overlay(neighbors, d[1], null);
        boolean up = overlay(neighbors, d[3], null);
        if (left && right && down && up) {
            return put(out, 0, 8);
        }
        if (left && right && down) {
            return put(out, 0, 5);
        }
        if (left && down && up) {
            return put(out, 0, 6);
        }
        if (right && down && up) {
            return put(out, 0, 12);
        }
        if (left && right && up) {
            return put(out, 0, 13);
        }
        boolean downRight = overlay(neighbors, d[1], d[2]);
        boolean downLeft = overlay(neighbors, d[1], d[0]);
        boolean upRight = overlay(neighbors, d[3], d[2]);
        boolean upLeft = overlay(neighbors, d[3], d[0]);
        if (right && down) {
            int count = put(out, 0, 3);
            return upLeft ? put(out, count, 16) : count;
        }
        if (left && down) {
            int count = put(out, 0, 4);
            return upRight ? put(out, count, 14) : count;
        }
        if (right && up) {
            int count = put(out, 0, 10);
            return downLeft ? put(out, count, 2) : count;
        }
        if (left && up) {
            int count = put(out, 0, 11);
            return downRight ? put(out, count, 0) : count;
        }
        // At most one side borders here, so at most one side tile and the corner tile away from it.
        int count = 0;
        if (left) {
            count = put(out, count, 9);
        }
        if (right) {
            count = put(out, count, 7);
        }
        if (down) {
            count = put(out, count, 1);
        }
        if (up) {
            count = put(out, count, 15);
        }
        boolean matchLeft = neighbors.matching(d[0][0], d[0][1], d[0][2]);
        boolean matchRight = neighbors.matching(d[2][0], d[2][1], d[2][2]);
        boolean matchDown = neighbors.matching(d[1][0], d[1][1], d[1][2]);
        boolean matchUp = neighbors.matching(d[3][0], d[3][1], d[3][2]);
        if (downRight && (matchRight || matchDown) && !right && !down) {
            count = put(out, count, 0);
        }
        if (downLeft && (matchLeft || matchDown) && !left && !down) {
            count = put(out, count, 2);
        }
        if (upRight && (matchRight || matchUp) && !right && !up) {
            count = put(out, count, 14);
        }
        if (upLeft && (matchLeft || matchUp) && !left && !up) {
            count = put(out, count, 16);
        }
        return count;
    }

    private static int put(int[] out, int count, int tile) {
        if (count < out.length) {
            out[count] = tile;
            return count + 1;
        }
        return count;
    }

    private static boolean overlay(OverlayNeighbors neighbors, int[] a, int[] b) {
        return neighbors.overlay(a[0] + (b == null ? 0 : b[0]), a[1] + (b == null ? 0 : b[1]), a[2] + (b == null ? 0 : b[2]));
    }

    private static int horizontal(Neighbors neighbors, int[][] d) {
        return (connects(neighbors, d[0], null) ? 1 : 0) | (connects(neighbors, d[2], null) ? 2 : 0);
    }

    private static int vertical(Neighbors neighbors, int[][] d) {
        return (connects(neighbors, d[1], null) ? 1 : 0) | (connects(neighbors, d[3], null) ? 2 : 0);
    }

    /** A neighbor at one or two steps joins. The strips and the top ignore inner seams, as OptiFine's do. */
    private static boolean connects(Neighbors neighbors, int[] a, int[] b) {
        return neighbors.connects(a[0] + (b == null ? 0 : b[0]), a[1] + (b == null ? 0 : b[1]), a[2] + (b == null ? 0 : b[2]));
    }

    /** CTM's join: as {@link #connects}, and with inner seams only where the block in front of the neighbor does not join. */
    private static boolean ctmJoins(Neighbors neighbors, Rule rule, int face, int[] a, int[] b) {
        int x = a[0] + (b == null ? 0 : b[0]);
        int y = a[1] + (b == null ? 0 : b[1]);
        int z = a[2] + (b == null ? 0 : b[2]);
        if (!neighbors.connects(x, y, z)) {
            return false;
        }
        if (!rule.innerSeams()) {
            return true;
        }
        int axis = face >> 1;
        int step = (face & 1) == 1 ? 1 : -1;
        return !neighbors.connects(x + (axis == 0 ? step : 0), y + (axis == 1 ? step : 0), z + (axis == 2 ? step : 0));
    }

    /**
     * getConnectedTextureRandom: OptiFine's position hash (Config.getRandom) of the block (the lowest of a linked
     * column) and the face folded by the symmetry, rehashed {@code randomLoops} times, weighted when the rule gives
     * weights.
     */
    private static int random(Rule rule, int face, int x, int y, int z, Neighbors neighbors) {
        int count = rule.tiles().size();
        if (count == 1) {
            return 0;
        }
        int side = SIDE[face] / rule.symmetry() * rule.symmetry();
        int bottom = y;
        if (rule.linked()) {
            // The halo holds two blocks below the section; columns of linked blocks are short (double plants).
            while (bottom - y > -2 && bottom > 0 && neighbors.sameBlock(0, bottom - 1 - y, 0)) {
                bottom--;
            }
        }
        int hash = intHash(side + 37);
        hash = intHash(hash + x);
        hash = intHash(hash + z);
        hash = intHash(hash + bottom);
        hash &= Integer.MAX_VALUE;
        for (int loop = 0; loop < rule.randomLoops(); loop++) {
            hash = intHash(hash);
        }
        int[] weights = rule.weights();
        if (weights.length == 0) {
            return hash % count;
        }
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int pick = hash % Math.max(1, total);
        int sum = 0;
        for (int index = 0; index < weights.length; index++) {
            sum += weights[index];
            if (pick < sum) {
                return index;
            }
        }
        return 0;
    }

    /** Config.intHash. */
    static int intHash(int value) {
        value = value ^ 61 ^ value >> 16;
        value = value + (value << 3);
        value = value ^ value >> 4;
        value = value * 668265261;
        return value ^ value >> 15;
    }

    /** getConnectedTextureRepeat: the pattern laid over world positions, as OptiFine aligns it per face. */
    private static int repeat(Rule rule, int face, int x, int y, int z) {
        if (rule.tiles().size() == 1) {
            return 0;
        }
        int u;
        int v;
        switch (face) {
            case 2 -> { u = x; v = -z - 1; }
            case 3 -> { u = x; v = z; }
            case 4 -> { u = -x - 1; v = -y; }
            case 5 -> { u = x; v = -y; }
            case 0 -> { u = z; v = -y; }
            default -> { u = -z - 1; v = -y; }
        }
        u = Math.floorMod(u, rule.width());
        v = Math.floorMod(v, rule.height());
        return rule.width() * v + u;
    }

    // ---- loading --------------------------------------------------------------------------------------------

    /** Reads every CTM rule in the assets: rules matching textures first, then rules matching blocks, each by path. */
    public static ConnectedTextures load(AssetSource assets) {
        List<Rule> rules = new ArrayList<>();
        for (String root : ROOTS) {
            List<String> paths;
            try {
                paths = assets.list(root);
            } catch (IOException failure) {
                continue;
            }
            for (String path : paths.stream().sorted().toList()) {
                if (!path.endsWith(".properties")) {
                    continue;
                }
                try (InputStream input = assets.open(path)) {
                    if (input == null) {
                        continue;
                    }
                    Properties properties = new Properties();
                    properties.load(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
                    Rule rule = parse(path, properties);
                    if (rule != null) {
                        rules.add(rule);
                    }
                } catch (IOException | RuntimeException failure) {
                    System.err.println("Connected texture " + path + " skipped: " + failure.getMessage());
                }
            }
        }
        rules.sort(Comparator.comparing(Rule::path));
        return rules.isEmpty() ? NONE : new ConnectedTextures(rules);
    }

    /**
     * The rules a face goes through, in OptiFine's order (ConnectedTextures.getConnectedTextureSingle): those
     * matching its texture (and block id) first, then those matching its block (and texture). Indices into
     * {@link #rules()}; a rule matching both comes up in both groups, as in OptiFine.
     */
    public int[] candidates(int block, int meta, String texture, int face) {
        int[] found = new int[8];
        int count = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < rules.size(); index++) {
                Rule rule = rules.get(index);
                boolean group = pass == 0 ? !rule.textures().isEmpty() : rule.blocks().length > 0;
                if (group && rule.matches(block, meta, texture, face)) {
                    if (count == found.length) {
                        found = java.util.Arrays.copyOf(found, count * 2);
                    }
                    found[count++] = index;
                }
            }
        }
        return java.util.Arrays.copyOf(found, count);
    }

    static Rule parse(String path, Properties properties) {
        Method method = switch (properties.getProperty("method", "ctm").trim().toLowerCase(Locale.ROOT)) {
            case "ctm", "glass" -> Method.CTM;
            case "ctm_compact" -> Method.CTM_COMPACT;
            case "horizontal", "bookshelf" -> Method.HORIZONTAL;
            case "vertical" -> Method.VERTICAL;
            case "horizontal+vertical", "h+v" -> Method.HORIZONTAL_VERTICAL;
            case "vertical+horizontal", "v+h" -> Method.VERTICAL_HORIZONTAL;
            case "top", "sandstone" -> Method.TOP;
            case "random" -> Method.RANDOM;
            case "repeat", "pattern" -> Method.REPEAT;
            case "fixed", "static" -> Method.FIXED;
            case "overlay" -> Method.OVERLAY;
            case "overlay_ctm" -> Method.OVERLAY_CTM;
            case "overlay_fixed" -> Method.OVERLAY_FIXED;
            case "overlay_random" -> Method.OVERLAY_RANDOM;
            case "overlay_repeat" -> Method.OVERLAY_REPEAT;
            default -> null;
        };
        if (method == null || parseInt(properties.getProperty("renderPass"), -1) > 0) {
            return null;
        }
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        String fileName = path.substring(directory.length(), path.length() - ".properties".length());
        List<Integer> blocks = new ArrayList<>();
        int metadata = blockList(properties.getProperty("matchBlocks", ""), blocks);
        List<String> textures = new ArrayList<>();
        for (String word : words(properties.getProperty("matchTiles", ""))) {
            textures.add(texture(word));
        }
        if (blocks.isEmpty() && textures.isEmpty()) {
            if (fileName.startsWith("block") && fileName.substring(5).matches("\\d+")) {
                blocks.add(Integer.parseInt(fileName.substring(5)));
            } else {
                textures.add(texture(fileName));
            }
        }
        for (int[] range : ranges(properties.getProperty("metadata", ""))) {
            for (int meta = range[0]; meta <= Math.min(15, range[1]); meta++) {
                metadata |= 1 << meta;
            }
        }
        if (metadata == 0) {
            metadata = 0xFFFF;
        }
        int faces = 0;
        for (String face : words(properties.getProperty("faces", "all").toLowerCase(Locale.ROOT))) {
            faces |= switch (face) {
                case "bottom", "down" -> 1 << 2;
                case "top", "up" -> 1 << 3;
                case "north" -> 1 << 4;
                case "south" -> 1 << 5;
                case "west" -> 1;
                case "east" -> 1 << 1;
                case "sides" -> 1 | 1 << 1 | 1 << 4 | 1 << 5;
                default -> 0x3F;
            };
        }
        String tileText = properties.getProperty("tiles");
        if (tileText == null) {
            // ConnectedProperties.isValid*: the methods with a standard tile sheet default to it.
            tileText = switch (method) {
                case CTM, OVERLAY_CTM -> "0-11 16-27 32-43 48-58";
                case CTM_COMPACT -> "0-4";
                case OVERLAY -> "0-16";
                case HORIZONTAL -> "12-15";
                case TOP -> "66";
                default -> "";
            };
        }
        List<String> tiles = new ArrayList<>();
        for (String word : words(tileText)) {
            if (word.matches("\\d+-\\d+")) {
                int dash = word.indexOf('-');
                for (int tile = Integer.parseInt(word.substring(0, dash)); tile <= Integer.parseInt(word.substring(dash + 1)); tile++) {
                    tiles.add(directory + tile + ".png");
                }
            } else if (word.equals("<default>") || word.equals("<default>.png")) {
                tiles.add(DEFAULT_TILE);
            } else if (word.equals("<skip>") || word.equals("<skip>.png")) {
                tiles.add(SKIP_TILE);
            } else {
                tiles.add(tilePath(directory, word));
            }
        }
        int width = parseInt(properties.getProperty("width"), 0);
        int height = parseInt(properties.getProperty("height"), 0);
        int needed = switch (method) {
            case CTM, OVERLAY_CTM -> 47;
            case CTM_COMPACT -> 5;
            case OVERLAY -> 17;
            case HORIZONTAL, VERTICAL -> 4;
            case HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL -> 7;
            case TOP, FIXED, RANDOM, OVERLAY_FIXED, OVERLAY_RANDOM -> 1;
            case REPEAT, OVERLAY_REPEAT -> Math.max(1, width * height);
        };
        boolean repeat = method == Method.REPEAT || method == Method.OVERLAY_REPEAT;
        if (tiles.size() < needed || (repeat && (width <= 0 || height <= 0))) {
            return null;
        }
        String layerName = properties.getProperty("layer", "cutout_mipped").trim().toLowerCase(Locale.ROOT);
        MaterialPass layer = switch (layerName) {
            case "cutout", "cutout_mipped" -> MaterialPass.CUTOUT;
            case "translucent" -> MaterialPass.TRANSLUCENT;
            default -> MaterialPass.OPAQUE;
        };
        if (method.overlay() && layer == MaterialPass.OPAQUE) {
            return null; // isValidOverlay*: overlays never draw in the solid layer.
        }
        int[] weights = words(properties.getProperty("weights", "")).stream().mapToInt(word -> parseInt(word, 1)).toArray();
        if (weights.length > 0 && weights.length != tiles.size()) {
            // isValidRandom: trim extra weights, pad missing ones with the average.
            int average = (int) Math.round(java.util.Arrays.stream(weights).average().orElse(1));
            int[] fitted = java.util.Arrays.copyOf(weights, tiles.size());
            for (int index = weights.length; index < fitted.length; index++) {
                fitted[index] = average;
            }
            weights = fitted;
        }
        int symmetry = switch (properties.getProperty("symmetry", "none").trim().toLowerCase(Locale.ROOT)) {
            case "opposite" -> 2;
            case "all" -> 6;
            default -> 1;
        };
        int randomLoops = parseInt(properties.getProperty("randomLoops"), 0);
        if (randomLoops < 0 || randomLoops > 9) {
            return null;
        }
        int[] heights = heights(properties);
        long[] biomes = BiomeNames189.parse(properties.getProperty("biomes"));
        String connectText = properties.getProperty("connect");
        int connect = connectText == null ? (blocks.isEmpty() ? CONNECT_TILE : CONNECT_BLOCK)
                : switch (connectText.trim().toLowerCase(Locale.ROOT)) {
                    case "block" -> CONNECT_BLOCK;
                    case "tile" -> CONNECT_TILE;
                    case "material" -> CONNECT_MATERIAL;
                    default -> -1;
                };
        if (connect < 0) {
            return null;
        }
        List<Integer> connectBlockIds = new ArrayList<>();
        List<Integer> connectBlockMeta = new ArrayList<>();
        for (String word : tokens(properties.getProperty("connectBlocks", ""))) {
            List<Integer> one = new ArrayList<>();
            int meta = blockList(word, one);
            for (int id : one) {
                connectBlockIds.add(id);
                connectBlockMeta.add(meta == 0 ? 0xFFFF : meta);
            }
        }
        int[] connectBlocks = new int[connectBlockIds.size()];
        for (int index = 0; index < connectBlocks.length; index++) {
            connectBlocks[index] = connectBlockIds.get(index) << 16 | connectBlockMeta.get(index);
        }
        List<String> connectTiles = new ArrayList<>();
        for (String word : words(properties.getProperty("connectTiles", ""))) {
            connectTiles.add(texture(word));
        }
        return new Rule(path, method, blocks.stream().mapToInt(Integer::intValue).toArray(), metadata,
                List.copyOf(textures), faces, connect,
                Boolean.parseBoolean(properties.getProperty("innerSeams", "false").trim()), List.copyOf(tiles), weights,
                width, height, symmetry, randomLoops,
                Boolean.parseBoolean(properties.getProperty("linked", "false").trim()), heights, biomes,
                compact(properties, tiles.size()), connectBlocks, List.copyOf(connectTiles),
                parseInt(properties.getProperty("tintIndex"), -1), blockState(properties.getProperty("tintBlock")),
                layer);
    }

    /** {@code heights} (ranges) or, without it, {@code minHeight}/{@code maxHeight} (OptiFine's -1..1024 defaults). */
    private static int[] heights(Properties properties) {
        String text = properties.getProperty("heights");
        if (text != null) {
            List<int[]> ranges = ranges(text);
            int[] heights = new int[ranges.size() * 2];
            for (int index = 0; index < ranges.size(); index++) {
                heights[index * 2] = ranges.get(index)[0];
                heights[index * 2 + 1] = ranges.get(index)[1];
            }
            return heights;
        }
        int min = parseInt(properties.getProperty("minHeight"), -1);
        int max = parseInt(properties.getProperty("maxHeight"), 1024);
        return min == -1 && max == 1024 ? new int[0] : new int[]{min, max};
    }

    /** ConnectedProperties.parseCtmTileIndexes: {@code ctm.<0-46>=<tile>} entries, or null without any. */
    private static int[] compact(Properties properties, int tileCount) {
        int[] indexes = null;
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("ctm.")) {
                continue;
            }
            int index = parseInt(key.substring(4), -1);
            int tile = parseInt(properties.getProperty(key), -1);
            if (index >= 0 && index <= 46 && tile >= 0 && tile < tileCount) {
                if (indexes == null) {
                    indexes = new int[47];
                    java.util.Arrays.fill(indexes, -1);
                }
                indexes[index] = tile;
            }
        }
        return indexes;
    }

    /**
     * Space-separated block entries ({@code minecraft:glass}, {@code glass}, {@code 20}, {@code glass:0,1} with
     * metadata after the name) into {@code ids}; returns the metadata bits they name (0 for none).
     */
    private static int blockList(String text, List<Integer> ids) {
        int metadata = 0;
        for (String word : tokens(text)) {
            String[] parts = word.split(":");
            int nameIndex = parts.length > 1 && parts[0].equals("minecraft") ? 1 : 0;
            int id = block(parts[nameIndex]);
            if (id >= 0) {
                ids.add(id);
            }
            for (int index = nameIndex + 1; index < parts.length; index++) {
                for (int[] range : ranges(parts[index])) {
                    for (int meta = range[0]; meta <= Math.min(15, range[1]); meta++) {
                        metadata |= 1 << meta;
                    }
                }
            }
        }
        return metadata;
    }

    /** A {@code tintBlock} state ({@code grass}, {@code minecraft:wool:3}) as {@code id << 4 | meta}, or -1. */
    private static int blockState(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String[] parts = text.trim().split(":");
        int nameIndex = parts.length > 1 && parts[0].equals("minecraft") ? 1 : 0;
        int id = block(parts[nameIndex]);
        if (id < 0) {
            return -1;
        }
        int meta = parts.length > nameIndex + 1 ? Math.max(0, Math.min(15, parseInt(parts[nameIndex + 1], 0))) : 0;
        return id << 4 | meta;
    }

    /** A matched texture as the palette names it: {@code glass}, {@code blocks/glass} and {@code textures/blocks/glass} all give {@code blocks/glass}. */
    static String texture(String name) {
        String path = name.startsWith("minecraft:") ? name.substring(10) : name;
        if (path.startsWith("textures/")) {
            path = path.substring(9);
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return path.contains("/") ? path : "blocks/" + path;
    }

    /** A tile name as a full asset path: beside the file, {@code ~/} for the optifine folder, else under assets/minecraft. */
    static String tilePath(String directory, String name) {
        String path = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        if (path.startsWith("~/")) {
            return "assets/minecraft/optifine/" + path.substring(2) + ".png";
        }
        if (path.startsWith("./")) {
            return directory + path.substring(2) + ".png";
        }
        if (!path.contains("/")) {
            return directory + path + ".png";
        }
        return "assets/minecraft/" + (path.startsWith("minecraft:") ? path.substring(10) : path) + ".png";
    }

    private static int block(String name) {
        if (name.matches("\\d+")) {
            return Integer.parseInt(name);
        }
        for (int id = 0; id < Blocks189.COUNT; id++) {
            if (name.equals(Blocks189.name(id))) {
                return id;
            }
        }
        return -1;
    }

    static List<int[]> ranges(String text) {
        List<int[]> ranges = new ArrayList<>();
        for (String word : words(text)) {
            try {
                int dash = word.indexOf('-', 1);
                if (dash < 0) {
                    int value = Integer.parseInt(word);
                    ranges.add(new int[]{value, value});
                } else {
                    String high = word.substring(dash + 1);
                    ranges.add(new int[]{Integer.parseInt(word.substring(0, dash)),
                            high.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(high)});
                }
            } catch (NumberFormatException ignored) {
                // Not a range (a block name, for example).
            }
        }
        return ranges;
    }

    /** Space-separated entries, so a matchBlocks entry keeps its metadata list together ({@code glass:0,1}). */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.trim().split("[\\s,]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
