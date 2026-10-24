package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** CTM rules against OptiFine's CustomSky-era ConnectedTextures (external/KotlinizedMCP-mcp net/optifine). */
class ConnectedTexturesTest {
    private static final String DIRECTORY = "assets/minecraft/mcpatcher/ctm/";
    /** The port's face codes: -x, +x, -y, +y, -z, +z. */
    private static final int WEST = 0;
    private static final int DOWN = 2;
    private static final int UP = 3;
    private static final int NORTH = 4;

    /** A rule from properties; the file name decides the default match, as MCPatcher reads it. */
    private static ConnectedTextures.Rule rule(String fileName, String... pairs) {
        Properties properties = new Properties();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.setProperty(pairs[index], pairs[index + 1]);
        }
        return ConnectedTextures.parse(DIRECTORY + fileName, properties);
    }

    private static ConnectedTextures.Rule ctm() {
        return rule("glass.properties", "method", "ctm", "tiles", "0-46");
    }

    private static ConnectedTextures.Rule strip(String method) {
        return rule("glass.properties", "method", method, "tiles", "0-3");
    }

    /** The face's neighbors as seen from outside: left, down, right, up, then the four corners between them. */
    private static int[][] ring(int face) {
        int[][] ring = new int[8][];
        for (int side = 0; side < 4; side++) {
            int[] first = ConnectedTextures.DIRECTIONS[face][side];
            int[] second = ConnectedTextures.DIRECTIONS[face][(side + 1) & 3];
            ring[side] = first;
            ring[side + 4] = new int[]{first[0] + second[0], first[1] + second[1], first[2] + second[2]};
        }
        return ring;
    }

    /** Neighbors that join where the mask has a bit: bits 0-3 are the sides, bits 4-7 the corners after them. */
    private static ConnectedTextures.Neighbors neighbors(int face, int mask) {
        Set<String> joined = new HashSet<>();
        int[][] ring = ring(face);
        for (int bit = 0; bit < 8; bit++) {
            if ((mask >> bit & 1) != 0) {
                joined.add(key(ring[bit]));
            }
        }
        return (dx, dy, dz) -> joined.contains(key(new int[]{dx, dy, dz}));
    }

    private static ConnectedTextures.Neighbors joining(int[]... offsets) {
        Set<String> joined = new HashSet<>();
        for (int[] offset : offsets) {
            joined.add(key(offset));
        }
        return (dx, dy, dz) -> joined.contains(key(new int[]{dx, dy, dz}));
    }

    private static String key(int[] offset) {
        return offset[0] + " " + offset[1] + " " + offset[2];
    }

    private static int tile(ConnectedTextures.Rule rule, int face, ConnectedTextures.Neighbors neighbors) {
        return ConnectedTextures.tile(rule, face, 0, 64, 0, neighbors);
    }

    // ---- faces and neighbors ---------------------------------------------------------------------------------

    @Test
    void faceDirectionsAreLeftDownRightUpAsSeenFromOutside() {
        // Standing outside the bottom face, the north side is at the bottom of the tile, so "left" is west.
        assertArrayEquals(new int[]{-1, 0, 0}, ConnectedTextures.DIRECTIONS[DOWN][0]);
        assertArrayEquals(new int[]{0, 0, -1}, ConnectedTextures.DIRECTIONS[DOWN][1]);
        assertArrayEquals(new int[]{1, 0, 0}, ConnectedTextures.DIRECTIONS[DOWN][2]);
        assertArrayEquals(new int[]{0, 0, 1}, ConnectedTextures.DIRECTIONS[DOWN][3]);
        // On the north wall, left is east and up is +y.
        assertArrayEquals(new int[]{1, 0, 0}, ConnectedTextures.DIRECTIONS[NORTH][0]);
        assertArrayEquals(new int[]{0, 1, 0}, ConnectedTextures.DIRECTIONS[NORTH][3]);
    }

    // ---- 47-tile CTM ------------------------------------------------------------------------------------------

    @Test
    void ctmIsolatedBlockIsTileZero() {
        assertEquals(0, tile(ctm(), DOWN, neighbors(DOWN, 0)));
    }

    @Test
    void ctmSingleSidesUseTheStripTiles() {
        // Mask bits: 1 left, 2 down, 4 right, 8 up. Tiles follow OptiFine's basic 16-tile table.
        assertEquals(3, tile(ctm(), DOWN, neighbors(DOWN, 1)));
        assertEquals(12, tile(ctm(), DOWN, neighbors(DOWN, 2)));
        assertEquals(1, tile(ctm(), DOWN, neighbors(DOWN, 4)));
        assertEquals(36, tile(ctm(), DOWN, neighbors(DOWN, 8)));
    }

    @Test
    void ctmOppositeSidePairsUseTheMiddleTiles() {
        assertEquals(2, tile(ctm(), DOWN, neighbors(DOWN, 5)));
        assertEquals(24, tile(ctm(), DOWN, neighbors(DOWN, 10)));
    }

    @Test
    void ctmSidesWithoutCornersIsTile46() {
        assertEquals(46, tile(ctm(), DOWN, neighbors(DOWN, 0x0F)));
    }

    @Test
    void ctmFullNeighborhoodIsTile26() {
        assertEquals(26, tile(ctm(), DOWN, neighbors(DOWN, 0xFF)));
    }

    @Test
    void ctmMissingOneCornerPicksItsTile() {
        // Corner 0 is left-and-down; corner 2 is right-and-up.
        assertEquals(33, tile(ctm(), DOWN, neighbors(DOWN, 0xFF & ~(1 << 4))));
        assertEquals(44, tile(ctm(), DOWN, neighbors(DOWN, 0xFF & ~(1 << 6))));
    }

    @Test
    void ctmUsesAllFortySevenTiles() {
        Set<Integer> seen = new TreeSet<>();
        for (int mask = 0; mask < 256; mask++) {
            seen.add(tile(ctm(), DOWN, neighbors(DOWN, mask)));
        }
        assertEquals(47, seen.size());
        int lowest = seen.iterator().next();
        assertEquals(0, lowest);
        assertTrue(seen.containsAll(List.of(0, 26, 46)));
    }

    @Test
    void ctmInnerSeamsDropsASideWhoseFrontBlockAlsoConnects() {
        ConnectedTextures.Rule seams = rule("glass.properties", "method", "ctm", "tiles", "0-46", "innerSeams", "true");
        // Left (-x) connects, but the block in front of it (down, -y) connects too, so it is an inner seam.
        ConnectedTextures.Neighbors neighbors = joining(new int[]{-1, 0, 0}, new int[]{-1, -1, 0});
        assertEquals(0, tile(seams, DOWN, neighbors));
        assertEquals(3, tile(ctm(), DOWN, neighbors));
    }

    // ---- strips ----------------------------------------------------------------------------------------------

    @Test
    void horizontalStripTilesFollowLeftAndRightNeighbors() {
        ConnectedTextures.Rule horizontal = strip("horizontal");
        assertEquals(3, tile(horizontal, DOWN, neighbors(DOWN, 0)));
        assertEquals(2, tile(horizontal, DOWN, neighbors(DOWN, 1)));
        assertEquals(0, tile(horizontal, DOWN, neighbors(DOWN, 4)));
        assertEquals(1, tile(horizontal, DOWN, neighbors(DOWN, 5)));
    }

    @Test
    void verticalStripTilesFollowDownAndUpNeighbors() {
        ConnectedTextures.Rule vertical = strip("vertical");
        assertEquals(3, tile(vertical, DOWN, neighbors(DOWN, 0)));
        assertEquals(2, tile(vertical, DOWN, neighbors(DOWN, 2)));
        assertEquals(0, tile(vertical, DOWN, neighbors(DOWN, 8)));
        assertEquals(1, tile(vertical, DOWN, neighbors(DOWN, 10)));
    }

    @Test
    void horizontalVerticalPrefersTheHorizontalStrip() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "h+v", "tiles", "0-6");
        // Left is connected, so the horizontal strip answers even though the block below connects too.
        assertEquals(2, tile(rule, DOWN, neighbors(DOWN, 1 | 2)));
        // Without a horizontal neighbor, the vertical neighbor picks tiles 4-6.
        assertEquals(6, tile(rule, DOWN, neighbors(DOWN, 2)));
        assertEquals(4, tile(rule, DOWN, neighbors(DOWN, 8)));
        assertEquals(5, tile(rule, DOWN, neighbors(DOWN, 2 | 8)));
        assertEquals(3, tile(rule, DOWN, neighbors(DOWN, 0)));
    }

    @Test
    void verticalHorizontalPrefersTheVerticalStrip() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "v+h", "tiles", "0-6");
        assertEquals(2, tile(rule, DOWN, neighbors(DOWN, 1 | 2)));
        assertEquals(6, tile(rule, DOWN, neighbors(DOWN, 1)));
        assertEquals(4, tile(rule, DOWN, neighbors(DOWN, 4)));
        assertEquals(5, tile(rule, DOWN, neighbors(DOWN, 1 | 4)));
        assertEquals(3, tile(rule, DOWN, neighbors(DOWN, 0)));
    }

    @Test
    void topTileOnlyAppliesToSideFacesWithAConnectedBlockAbove() {
        ConnectedTextures.Rule top = rule("glass.properties", "method", "top", "tiles", "0");
        ConnectedTextures.Neighbors above = joining(new int[]{0, 1, 0});
        assertEquals(0, tile(top, NORTH, above));
        assertEquals(-1, tile(top, NORTH, joining()));
        // The top and bottom faces never take the top tile.
        assertEquals(-1, tile(top, UP, above));
        assertEquals(-1, tile(top, DOWN, above));
    }

    @Test
    void fixedAlwaysTakesTileZero() {
        ConnectedTextures.Rule fixed = rule("glass.properties", "method", "fixed", "tiles", "0");
        assertEquals(0, tile(fixed, WEST, joining()));
        assertEquals(0, tile(fixed, WEST, joining(new int[]{0, 1, 0})));
    }

    @Test
    void heightRangeOutsideTheRuleGivesNoTile() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "fixed", "tiles", "0", "heights", "10-20");
        assertEquals(-1, ConnectedTextures.tile(rule, DOWN, 0, 9, 0, joining()));
        assertEquals(0, ConnectedTextures.tile(rule, DOWN, 0, 15, 0, joining()));
        assertEquals(-1, ConnectedTextures.tile(rule, DOWN, 0, 21, 0, joining()));
    }

    // ---- random and repeat -----------------------------------------------------------------------------------

    @Test
    void randomChoosesStablyFromItsTiles() {
        ConnectedTextures.Rule random = rule("glass.properties", "method", "random", "tiles", "0-3");
        Set<Integer> seen = new TreeSet<>();
        for (int x = 0; x < 64; x++) {
            int first = ConnectedTextures.tile(random, DOWN, x, 64, 7, joining());
            assertEquals(first, ConnectedTextures.tile(random, DOWN, x, 64, 7, joining()));
            assertTrue(first >= 0 && first < 4);
            seen.add(first);
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    void randomSymmetryKeepsOppositeFacesOrAllFacesTheSame() {
        ConnectedTextures.Rule opposite = rule("glass.properties", "method", "random", "tiles", "0-3", "symmetry", "opposite");
        ConnectedTextures.Rule all = rule("glass.properties", "method", "random", "tiles", "0-3", "symmetry", "all");
        for (int x = 0; x < 32; x++) {
            assertEquals(ConnectedTextures.tile(opposite, WEST, x, 64, 3, joining()),
                    ConnectedTextures.tile(opposite, WEST + 1, x, 64, 3, joining()));
            assertEquals(ConnectedTextures.tile(all, WEST, x, 64, 3, joining()),
                    ConnectedTextures.tile(all, UP, x, 64, 3, joining()));
        }
    }

    @Test
    void randomWeightsOnlyPickWeightedTiles() {
        ConnectedTextures.Rule weighted = rule("glass.properties", "method", "random", "tiles", "0-1", "weights", "0 1");
        for (int x = 0; x < 32; x++) {
            assertEquals(1, ConnectedTextures.tile(weighted, DOWN, x, 64, 0, joining()));
        }
    }

    @Test
    void repeatTilesTheWorldPatternPerFace() {
        ConnectedTextures.Rule repeat = rule("glass.properties", "method", "repeat", "width", "2", "height", "2",
                "tiles", "0-3");
        // Top face: u is x, v is z.
        assertEquals(2, ConnectedTextures.tile(repeat, UP, 0, 64, 1, joining()));
        assertEquals(1, ConnectedTextures.tile(repeat, UP, -1, 64, 0, joining()));
        // Bottom face: v is -z-1.
        assertEquals(2, ConnectedTextures.tile(repeat, DOWN, 0, 64, 0, joining()));
        // East face (+x): u is -z-1, v is -y.
        assertEquals(1, ConnectedTextures.tile(repeat, WEST + 1, 0, 64, 0, joining()));
    }

    // ---- properties parsing ----------------------------------------------------------------------------------

    @Test
    void blockFileNameIsAMatchBlocksRule() {
        ConnectedTextures.Rule rule = rule("block20.properties", "method", "ctm", "tiles", "0-46");
        assertArrayEquals(new int[]{20}, rule.blocks());
        assertTrue(rule.textures().isEmpty());
        assertFalse(rule.connectTile());
    }

    @Test
    void otherFileNameIsAMatchTilesRule() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "ctm", "tiles", "0-46");
        assertEquals(0, rule.blocks().length);
        assertEquals(List.of("blocks/glass"), rule.textures());
        assertTrue(rule.connectTile());
    }

    @Test
    void explicitMatchBlocksOverridesTheFileName() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "ctm", "tiles", "0-46", "matchBlocks", "glass");
        assertArrayEquals(new int[]{20}, rule.blocks());
        assertTrue(rule.textures().isEmpty());
    }

    @Test
    void tileRangesAndSingleNumbersBecomeTilePaths() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "horizontal", "tiles", "0-3 5");
        assertEquals(List.of(DIRECTORY + "0.png", DIRECTORY + "1.png", DIRECTORY + "2.png", DIRECTORY + "3.png",
                DIRECTORY + "5.png"), rule.tiles());
    }

    @Test
    void tooFewTilesRejectTheRule() {
        assertNull(rule("glass.properties", "method", "ctm", "tiles", "0-10"));
        assertNull(rule("glass.properties", "method", "horizontal", "tiles", "0-2"));
    }

    @Test
    void facesBecomeOneBitEach() {
        assertEquals(1 << 3, rule("glass.properties", "method", "fixed", "tiles", "0", "faces", "top").faces());
        assertEquals(0b110011, rule("glass.properties", "method", "fixed", "tiles", "0", "faces", "sides").faces());
        assertEquals((1 << 2) | (1 << 3), rule("glass.properties", "method", "fixed", "tiles", "0", "faces", "bottom top").faces());
        assertEquals(0x3F, rule("glass.properties", "method", "fixed", "tiles", "0").faces());
    }

    @Test
    void connectDefaultsToBlockForBlockRulesAndTileForTileRules() {
        assertFalse(rule("block20.properties", "method", "fixed", "tiles", "0").connectTile());
        assertTrue(rule("glass.properties", "method", "fixed", "tiles", "0").connectTile());
        assertTrue(rule("glass.properties", "method", "fixed", "tiles", "0", "connect", "tile").connectTile());
        assertFalse(rule("glass.properties", "method", "fixed", "tiles", "0", "connect", "block").connectTile());
    }

    // ---- methods added for wave 2: compact, overlays, conditions ---------------------------------------------

    @Test
    void newMethodsParseWithTheirDefaultTileSheets() {
        ConnectedTextures.Rule compact = rule("glass.properties", "method", "ctm_compact");
        assertEquals(ConnectedTextures.Method.CTM_COMPACT, compact.method());
        assertEquals(5, compact.tiles().size());
        ConnectedTextures.Rule overlay = rule("glass.properties", "method", "overlay");
        assertEquals(ConnectedTextures.Method.OVERLAY, overlay.method());
        assertEquals(17, overlay.tiles().size());
        assertEquals(MaterialPass.CUTOUT, overlay.layer());
        // The plain ctm sheet skips MCPatcher's unused cells: 0-11 16-27 32-43 48-58.
        ConnectedTextures.Rule ctm = rule("glass.properties", "method", "ctm");
        assertEquals(47, ctm.tiles().size());
        assertEquals(DIRECTORY + "16.png", ctm.tiles().get(12));
        assertEquals(DIRECTORY + "58.png", ctm.tiles().get(46));
        assertEquals(ConnectedTextures.Method.OVERLAY_CTM, rule("glass.properties", "method", "overlay_ctm").method());
        assertEquals(ConnectedTextures.Method.OVERLAY_RANDOM,
                rule("glass.properties", "method", "overlay_random", "tiles", "0-2").method());
    }

    @Test
    void overlaysNeverDrawInTheSolidLayer() {
        assertNull(rule("glass.properties", "method", "overlay", "layer", "solid"));
        assertEquals(MaterialPass.TRANSLUCENT,
                rule("glass.properties", "method", "overlay", "layer", "translucent").layer());
    }

    @Test
    void renderPassAndUnknownConnectRejectTheRule() {
        assertNull(rule("glass.properties", "method", "ctm", "tiles", "0-46", "renderPass", "1"));
        assertNull(rule("glass.properties", "method", "ctm", "tiles", "0-46", "connect", "sideways"));
    }

    @Test
    void compactQuartersFollowConnectedTexturesCompact() {
        ConnectedTextures.Rule compact = rule("glass.properties", "method", "ctm_compact");
        // Index 0 (alone): tile 0 everywhere; 26 (surrounded): tile 1; 46 (no corners): tile 4.
        for (int quarter = 0; quarter < 4; quarter++) {
            assertEquals(0, ConnectedTextures.compactTile(compact, 0, quarter));
            assertEquals(1, ConnectedTextures.compactTile(compact, 26, quarter));
            assertEquals(4, ConnectedTextures.compactTile(compact, 46, quarter));
        }
        // Index 1 (a right neighbor): the left half keeps tile 0, the right half joins with tile 3.
        assertArrayEquals(new int[]{0, 3, 0, 3}, quarters(compact, 1));
        // Index 12 (a neighbor below): top half tile 0, bottom half tile 2.
        assertArrayEquals(new int[]{0, 0, 2, 2}, quarters(compact, 12));
        // Index 13: getQuadsCompact4(0, 3, 2, 1).
        assertArrayEquals(new int[]{0, 3, 2, 1}, quarters(compact, 13));
    }

    @Test
    void compactIndexOverridesGiveTheWholeFaceOneTile() {
        ConnectedTextures.Rule compact = rule("glass.properties", "method", "ctm_compact", "tiles", "0-5", "ctm.26", "5",
                "ctm.47", "1", "ctm.3", "9");
        assertArrayEquals(new int[]{5, 5, 5, 5}, quarters(compact, 26));
        // Out-of-range indexes and tiles are ignored.
        assertArrayEquals(new int[]{3, 0, 3, 0}, quarters(compact, 3));
    }

    private static int[] quarters(ConnectedTextures.Rule rule, int index) {
        int[] tiles = new int[4];
        for (int quarter = 0; quarter < 4; quarter++) {
            tiles[quarter] = ConnectedTextures.compactTile(rule, index, quarter);
        }
        return tiles;
    }

    /** Bordering blocks for overlays: bits 0-3 the sides (left, down, right, up), 4-7 the corners after them. */
    private static ConnectedTextures.OverlayNeighbors bordering(int face, int overlay, int matching) {
        ConnectedTextures.Neighbors over = neighbors(face, overlay);
        ConnectedTextures.Neighbors match = neighbors(face, matching);
        return new ConnectedTextures.OverlayNeighbors() {
            @Override
            public boolean overlay(int dx, int dy, int dz) {
                return over.connects(dx, dy, dz);
            }

            @Override
            public boolean matching(int dx, int dy, int dz) {
                return match.connects(dx, dy, dz);
            }
        };
    }

    private static List<Integer> overlayTiles(int overlay, int matching) {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "overlay");
        int[] out = new int[2];
        int count = ConnectedTextures.overlay(rule, UP, 64, bordering(UP, overlay, matching), out);
        List<Integer> tiles = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            tiles.add(out[index]);
        }
        return tiles;
    }

    @Test
    void overlayTilesFollowTheBorderingSides() {
        // Mask bits: 1 left, 2 down, 4 right, 8 up.
        assertEquals(List.of(), overlayTiles(0, 0));
        assertEquals(List.of(8), overlayTiles(0x0F, 0));
        assertEquals(List.of(5), overlayTiles(1 | 4 | 2, 0));
        assertEquals(List.of(6), overlayTiles(1 | 2 | 8, 0));
        assertEquals(List.of(12), overlayTiles(4 | 2 | 8, 0));
        assertEquals(List.of(13), overlayTiles(1 | 4 | 8, 0));
        assertEquals(List.of(9), overlayTiles(1, 0));
        assertEquals(List.of(7), overlayTiles(4, 0));
        assertEquals(List.of(1), overlayTiles(2, 0));
        assertEquals(List.of(15), overlayTiles(8, 0));
    }

    @Test
    void overlayCornersAddTheirTileBesideTwoSides() {
        // Right and down border, plus the up-left corner: tiles 3 and 16 (corner bit 7 is up-left).
        assertEquals(List.of(3), overlayTiles(4 | 2, 0));
        assertEquals(List.of(3, 16), overlayTiles(4 | 2 | 1 << 7, 0));
        // Left and up border, plus the down-right corner (bit 5): tiles 11 and 0.
        assertEquals(List.of(11, 0), overlayTiles(1 | 8 | 1 << 5, 0));
    }

    @Test
    void aLoneOverlayCornerNeedsAMatchingSideNextToIt() {
        // Only the down-left corner (bit 4) borders: drawn when the block left or below matches the rule.
        assertEquals(List.of(), overlayTiles(1 << 4, 0));
        assertEquals(List.of(2), overlayTiles(1 << 4, 1));
        assertEquals(List.of(2), overlayTiles(1 << 4, 2));
        assertEquals(List.of(), overlayTiles(1 << 4, 4));
    }

    @Test
    void overlayVariantsPickOneTileLikeTheirPlainMethods() {
        ConnectedTextures.Rule fixed = rule("glass.properties", "method", "overlay_fixed", "tiles", "0");
        assertEquals(0, ConnectedTextures.tile(fixed, UP, 0, 64, 0, joining()));
        ConnectedTextures.Rule ctm = rule("glass.properties", "method", "overlay_ctm", "tiles", "0-46");
        assertEquals(26, ConnectedTextures.tile(ctm, DOWN, 0, 64, 0, neighbors(DOWN, 0xFF)));
        ConnectedTextures.Rule overlay = rule("glass.properties", "method", "overlay");
        assertEquals(-1, ConnectedTextures.tile(overlay, UP, 0, 64, 0, joining()));
    }

    @Test
    void heightsTakeSeveralRangesAndMinMaxHeight() {
        ConnectedTextures.Rule ranges = rule("glass.properties", "method", "fixed", "tiles", "0", "heights", "0-5 60-70");
        assertTrue(ranges.applies(3, 1));
        assertFalse(ranges.applies(30, 1));
        assertTrue(ranges.applies(64, 1));
        ConnectedTextures.Rule minMax = rule("glass.properties", "method", "fixed", "tiles", "0", "minHeight", "10",
                "maxHeight", "20");
        assertFalse(minMax.applies(9, 1));
        assertTrue(minMax.applies(20, 1));
        assertFalse(minMax.applies(21, 1));
        assertTrue(rule("glass.properties", "method", "fixed", "tiles", "0").applies(-5, 1));
    }

    @Test
    void biomesMatchOptiFineNamesAndNegation() {
        ConnectedTextures.Rule desert = rule("glass.properties", "method", "fixed", "tiles", "0", "biomes",
                "Desert extremeHills+ JungleEdgeM nether");
        assertTrue(desert.applies(64, 2));
        assertTrue(desert.applies(64, 34));
        assertTrue(desert.applies(64, 151));
        assertTrue(desert.applies(64, 8));
        assertFalse(desert.applies(64, 1));
        ConnectedTextures.Rule notPlains = rule("glass.properties", "method", "fixed", "tiles", "0", "biomes", "!plains");
        assertFalse(notPlains.applies(64, 1));
        assertTrue(notPlains.applies(64, 2));
        // Ids no biome registers stay out of a negated list.
        assertFalse(notPlains.applies(64, 200));
    }

    @Test
    void connectMaterialAndOverlayConnectionsParse() {
        ConnectedTextures.Rule rule = rule("block1.properties", "method", "overlay", "connect", "material",
                "connectBlocks", "dirt grass:0", "connectTiles", "sand", "tintIndex", "0", "tintBlock", "grass");
        assertEquals(ConnectedTextures.CONNECT_MATERIAL, rule.connect());
        assertTrue(rule.connectsTo(3, 5, "blocks/sand"));
        assertTrue(rule.connectsTo(2, 0, "blocks/sand"));
        assertFalse(rule.connectsTo(2, 1, "blocks/sand"));
        assertFalse(rule.connectsTo(3, 0, "blocks/dirt"));
        assertEquals(0, rule.tintIndex());
        assertEquals(2 << 4, rule.tintBlock());
    }

    @Test
    void skipAndDefaultTilesStayApart() {
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "random", "tiles", "<skip> <default> 3");
        assertEquals(List.of(ConnectedTextures.SKIP_TILE, ConnectedTextures.DEFAULT_TILE, DIRECTORY + "3.png"), rule.tiles());
    }

    @Test
    void textureRulesComeBeforeBlockRules() {
        // "a/block20" sorts before "glass" by path, but OptiFine tries texture rules first.
        ConnectedTextures rules = connected(DIRECTORY + "a/block20.properties", "method=fixed\ntiles=0",
                DIRECTORY + "glass.properties", "method=fixed\ntiles=1");
        int[] order = rules.candidates(20, 0, "blocks/glass", UP);
        assertEquals(2, order.length);
        assertEquals(DIRECTORY + "glass.properties", rules.rules().get(order[0]).path());
        assertEquals(DIRECTORY + "a/block20.properties", rules.rules().get(order[1]).path());
        // Another block with that texture only meets the texture rule.
        assertEquals(1, rules.candidates(95, 0, "blocks/glass", UP).length);
    }

    /** Rules as {@link ConnectedTextures#load} reads them from an in-memory pack of (path, properties text) pairs. */
    private static ConnectedTextures connected(String... pathsAndTexts) {
        java.util.Map<String, String> files = new java.util.TreeMap<>();
        for (int index = 0; index < pathsAndTexts.length; index += 2) {
            files.put(pathsAndTexts[index], pathsAndTexts[index + 1]);
        }
        return ConnectedTextures.load(new alkaline.renderer.asset.AssetSource() {
            @Override
            public java.io.InputStream open(String path) {
                String text = files.get(path);
                return text == null ? null
                        : new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }

            @Override
            public List<String> list(String prefix) {
                return files.keySet().stream().filter(path -> path.startsWith(prefix)).toList();
            }

            @Override
            public String describe() {
                return "test rules";
            }
        });
    }

    @Test
    void randomFollowsOptiFinesPositionHash() {
        ConnectedTextures.Rule random = rule("glass.properties", "method", "random", "tiles", "0-6");
        // Config.getRandom(pos, side) for the top face (side 1) at (3, 64, -7), then % 7.
        int hash = ConnectedTextures.intHash(1 + 37);
        hash = ConnectedTextures.intHash(hash + 3);
        hash = ConnectedTextures.intHash(hash + -7);
        hash = ConnectedTextures.intHash(hash + 64);
        assertEquals((hash & Integer.MAX_VALUE) % 7, ConnectedTextures.tile(random, UP, 3, 64, -7, joining()));
        // A linked rule reads the lowest block of a column of the same block.
        ConnectedTextures.Rule linked = rule("glass.properties", "method", "random", "tiles", "0-6", "linked", "true");
        ConnectedTextures.Neighbors below = new ConnectedTextures.Neighbors() {
            @Override
            public boolean connects(int dx, int dy, int dz) {
                return false;
            }

            @Override
            public boolean sameBlock(int dx, int dy, int dz) {
                return dx == 0 && dz == 0 && dy == -1;
            }
        };
        assertEquals(ConnectedTextures.tile(random, UP, 3, 63, -7, joining()),
                ConnectedTextures.tile(linked, UP, 3, 64, -7, below));
    }

    @Test
    void repeatIgnoresSymmetry() {
        ConnectedTextures.Rule plain = rule("glass.properties", "method", "repeat", "width", "2", "height", "2",
                "tiles", "0-3");
        ConnectedTextures.Rule opposite = rule("glass.properties", "method", "repeat", "width", "2", "height", "2",
                "tiles", "0-3", "symmetry", "opposite");
        for (int x = 0; x < 4; x++) {
            assertEquals(ConnectedTextures.tile(plain, WEST + 1, x, 64, x, joining()),
                    ConnectedTextures.tile(opposite, WEST + 1, x, 64, x, joining()));
        }
    }

    // ---- known deviations from OptiFine (see the handoff note) -----------------------------------------------

    @Test
    void stripsIgnoreInnerSeamsLikeOptiFine() {
        ConnectedTextures.Rule horizontal = rule("glass.properties", "method", "horizontal", "tiles", "0-3",
                "innerSeams", "true");
        // Left connects and so does the block in front of it. OptiFine's strip still reads left as connected: tile 2.
        ConnectedTextures.Neighbors neighbors = joining(new int[]{-1, 0, 0}, new int[]{-1, -1, 0});
        assertEquals(2, tile(horizontal, DOWN, neighbors));
    }

    @Test
    void matchBlocksMetadataListStaysWithItsBlock() {
        // OptiFine reads 'glass:0,1' as glass with metadata 0 and 1; the port also matches stone.
        ConnectedTextures.Rule rule = rule("glass.properties", "method", "ctm", "tiles", "0-46", "matchBlocks", "glass:0,1");
        assertArrayEquals(new int[]{20}, rule.blocks());
        assertEquals(0b11, rule.metadata());
    }
}
