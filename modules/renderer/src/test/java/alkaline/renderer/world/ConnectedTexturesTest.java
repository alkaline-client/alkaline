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
