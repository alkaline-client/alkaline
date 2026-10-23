package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
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
 * into one pane, bookshelves, sandstone) or from their position (random and repeating patterns). Rules match by
 * block (with metadata) or by the face's texture, on chosen faces and heights; MCPatcher's file-name conventions
 * ({@code block<id>.properties}, {@code <texture>.properties}) apply when neither is given.
 *
 * <p>Methods: {@code ctm} (47 tiles), {@code horizontal}, {@code vertical}, {@code horizontal+vertical},
 * {@code vertical+horizontal}, {@code top}, {@code random} (with weights), {@code repeat} and {@code fixed}. The
 * tile tables are the format's own (the indices every CTM pack is drawn for). Overlay methods, {@code ctm_compact}
 * and biome conditions are not supported; such rules are skipped.
 */
public final class ConnectedTextures {
    public static final ConnectedTextures NONE = new ConnectedTextures(List.of());
    private static final String[] ROOTS = {"assets/minecraft/optifine/ctm/", "assets/minecraft/mcpatcher/ctm/"};
    /** A tile entry that keeps the face's own texture. */
    public static final String DEFAULT_TILE = "<default>";

    public enum Method { CTM, HORIZONTAL, VERTICAL, HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL, TOP, RANDOM, REPEAT, FIXED }

    /**
     * One rule.
     *
     * @param blocks    matched block ids (empty: any)
     * @param metadata  bit per allowed metadata value
     * @param textures  matched face textures as palette names ({@code blocks/glass}), or empty
     * @param faces     bit per face code (0 -x, 1 +x, 2 -y, 3 +y, 4 -z, 5 +z)
     * @param tiles     tile textures as full asset paths, or {@link #DEFAULT_TILE}
     * @param symmetry  0 none, 1 opposite, 2 all (random and repeat)
     */
    public record Rule(String path, Method method, int[] blocks, int metadata, List<String> textures, int faces,
                       boolean connectTile, boolean innerSeams, List<String> tiles, int[] weights, int width,
                       int height, int symmetry, int minHeight, int maxHeight, int weight) {
        public boolean matches(int block, int meta, String texture, int face) {
            if ((faces >> face & 1) == 0 || (metadata >> meta & 1) == 0) {
                return false;
            }
            if (blocks.length > 0) {
                boolean found = false;
                for (int id : blocks) {
                    found |= id == block;
                }
                if (!found) {
                    return false;
                }
            }
            return textures.isEmpty() || textures.contains(texture);
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

    /** Whether the block at an offset (dx, dy, dz) from the face's block joins it. */
    public interface Neighbors {
        boolean connects(int dx, int dy, int dz);
    }

    /**
     * The face's directions as seen from outside, each {x, y, z}: left, down, right, up. Up is north on top faces,
     * south on bottom faces and up on the sides; left follows from it.
     */
    static final int[][][] DIRECTIONS = new int[6][4][];

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
     * The tile a face takes, or -1 to keep its texture.
     *
     * @param face face code, with the block at world (x, y, z)
     */
    public static int tile(Rule rule, int face, int x, int y, int z, Neighbors neighbors) {
        if (y < rule.minHeight() || y > rule.maxHeight()) {
            return -1;
        }
        int[][] d = DIRECTIONS[face];
        return switch (rule.method()) {
            case CTM -> {
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
                yield CTM[bits];
            }
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
            case FIXED -> 0;
            case RANDOM -> random(rule, face, x, y, z);
            case REPEAT -> repeat(rule, face, x, y, z);
        };
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

    private static int symmetric(Rule rule, int face) {
        return switch (rule.symmetry()) {
            case 1 -> face & ~1;
            case 2 -> 2;
            default -> face;
        };
    }

    /** A stable per-position choice, weighted when the rule gives weights. */
    private static int random(Rule rule, int face, int x, int y, int z) {
        long hash = (x * 3129871L) ^ (z * 116129781L) ^ y;
        hash = hash * hash * 42317861L + hash * 11L;
        hash = mix(hash ^ (symmetric(rule, face) + 1) * 0x9E3779B97F4A7C15L);
        int value = (int) (hash >>> 33);
        int count = rule.tiles().size();
        int[] weights = rule.weights();
        if (weights.length == 0) {
            return value % count;
        }
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int pick = value % Math.max(1, total);
        for (int index = 0; index < weights.length; index++) {
            pick -= weights[index];
            if (pick < 0) {
                return Math.min(index, count - 1);
            }
        }
        return count - 1;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The repeat pattern laid over world positions, as OptiFine aligns it per face. */
    private static int repeat(Rule rule, int face, int x, int y, int z) {
        int u;
        int v;
        switch (symmetric(rule, face)) {
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

    /** Reads every CTM rule in the assets, highest weight first, then by path. */
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
        rules.sort(Comparator.comparingInt(Rule::weight).reversed().thenComparing(Rule::path));
        return rules.isEmpty() ? NONE : new ConnectedTextures(rules);
    }

    static Rule parse(String path, Properties properties) {
        Method method = switch (properties.getProperty("method", "ctm").trim().toLowerCase(Locale.ROOT)) {
            case "ctm", "glass" -> Method.CTM;
            case "horizontal", "bookshelf" -> Method.HORIZONTAL;
            case "vertical" -> Method.VERTICAL;
            case "horizontal+vertical", "h+v" -> Method.HORIZONTAL_VERTICAL;
            case "vertical+horizontal", "v+h" -> Method.VERTICAL_HORIZONTAL;
            case "top", "sandstone" -> Method.TOP;
            case "random" -> Method.RANDOM;
            case "repeat", "pattern" -> Method.REPEAT;
            case "fixed", "static" -> Method.FIXED;
            default -> null;
        };
        if (method == null) {
            return null;
        }
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        String fileName = path.substring(directory.length(), path.length() - ".properties".length());
        List<Integer> blocks = new ArrayList<>();
        int metadata = 0;
        for (String word : tokens(properties.getProperty("matchBlocks", ""))) {
            String[] parts = word.split(":");
            // minecraft:glass, glass, 20, glass:0,1 (metadata after the name, comma-separated).
            int nameIndex = parts.length > 1 && parts[0].equals("minecraft") ? 1 : 0;
            int id = block(parts[nameIndex]);
            if (id >= 0) {
                blocks.add(id);
            }
            for (int index = nameIndex + 1; index < parts.length; index++) {
                for (int[] range : ranges(parts[index])) {
                    for (int meta = range[0]; meta <= Math.min(15, range[1]); meta++) {
                        metadata |= 1 << meta;
                    }
                }
            }
        }
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
                case "bottom" -> 1 << 2;
                case "top" -> 1 << 3;
                case "north" -> 1 << 4;
                case "south" -> 1 << 5;
                case "west" -> 1;
                case "east" -> 1 << 1;
                case "sides" -> 1 | 1 << 1 | 1 << 4 | 1 << 5;
                default -> 0x3F;
            };
        }
        List<String> tiles = new ArrayList<>();
        for (String word : words(properties.getProperty("tiles", ""))) {
            if (word.matches("\\d+-\\d+")) {
                int dash = word.indexOf('-');
                for (int tile = Integer.parseInt(word.substring(0, dash)); tile <= Integer.parseInt(word.substring(dash + 1)); tile++) {
                    tiles.add(directory + tile + ".png");
                }
            } else if (word.equals("<default>") || word.equals("<skip>")) {
                tiles.add(DEFAULT_TILE);
            } else {
                tiles.add(tilePath(directory, word));
            }
        }
        int width = parseInt(properties.getProperty("width"), 0);
        int height = parseInt(properties.getProperty("height"), 0);
        int needed = switch (method) {
            case CTM -> 47;
            case HORIZONTAL, VERTICAL -> 4;
            case HORIZONTAL_VERTICAL, VERTICAL_HORIZONTAL -> 7;
            case TOP, FIXED, RANDOM -> 1;
            case REPEAT -> Math.max(1, width * height);
        };
        if (tiles.size() < needed || (method == Method.REPEAT && (width <= 0 || height <= 0))) {
            return null;
        }
        int[] weights = words(properties.getProperty("weights", "")).stream().mapToInt(word -> parseInt(word, 1)).toArray();
        int symmetry = switch (properties.getProperty("symmetry", "none").trim().toLowerCase(Locale.ROOT)) {
            case "opposite" -> 1;
            case "all" -> 2;
            default -> 0;
        };
        int minHeight = parseInt(properties.getProperty("minHeight"), Integer.MIN_VALUE);
        int maxHeight = parseInt(properties.getProperty("maxHeight"), Integer.MAX_VALUE);
        String heights = properties.getProperty("heights");
        if (heights != null && !ranges(heights).isEmpty()) {
            int[] range = ranges(heights).getFirst();
            minHeight = range[0];
            maxHeight = range[1];
        }
        String connect = properties.getProperty("connect", textures.isEmpty() || !blocks.isEmpty() ? "block" : "tile")
                .trim().toLowerCase(Locale.ROOT);
        return new Rule(path, method, blocks.stream().mapToInt(Integer::intValue).toArray(), metadata,
                List.copyOf(textures), faces, connect.equals("tile"),
                Boolean.parseBoolean(properties.getProperty("innerSeams", "false").trim()), List.copyOf(tiles), weights,
                width, height, symmetry, minHeight, maxHeight, parseInt(properties.getProperty("weight"), 0));
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
