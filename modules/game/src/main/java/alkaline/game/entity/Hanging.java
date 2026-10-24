package alkaline.game.entity;

/**
 * Paintings and item frames hang on a wall block's face. The server sends the block they occupy and a facing
 * (EnumFacing.getHorizontal order: south, west, north, east); the entity's position and yaw follow from that as
 * EntityHanging.updateBoundingBox derives them.
 */
public final class Hanging {
    /** One painting: EntityPainting.EnumArt, in pixels, with the art's offset in the painting atlas. */
    public record Art(String title, int width, int height, int u, int v) {
    }

    /** In EnumArt order. */
    private static final Art[] ART = {
            new Art("Kebab", 16, 16, 0, 0), new Art("Aztec", 16, 16, 16, 0), new Art("Alban", 16, 16, 32, 0),
            new Art("Aztec2", 16, 16, 48, 0), new Art("Bomb", 16, 16, 64, 0), new Art("Plant", 16, 16, 80, 0),
            new Art("Wasteland", 16, 16, 96, 0), new Art("Pool", 32, 16, 0, 32), new Art("Courbet", 32, 16, 32, 32),
            new Art("Sea", 32, 16, 64, 32), new Art("Sunset", 32, 16, 96, 32), new Art("Creebet", 32, 16, 128, 32),
            new Art("Wanderer", 16, 32, 0, 64), new Art("Graham", 16, 32, 16, 64), new Art("Match", 32, 32, 0, 128),
            new Art("Bust", 32, 32, 32, 128), new Art("Stage", 32, 32, 64, 128), new Art("Void", 32, 32, 96, 128),
            new Art("SkullAndRoses", 32, 32, 128, 128), new Art("Wither", 32, 32, 160, 128),
            new Art("Fighters", 64, 32, 0, 96), new Art("Pointer", 64, 64, 0, 192),
            new Art("Pigscene", 64, 64, 64, 192), new Art("BurningSkull", 64, 64, 128, 192),
            new Art("Skeleton", 64, 48, 192, 64), new Art("DonkeyKong", 64, 48, 192, 112)};
    /** Item frames are 12 by 12 pixels. */
    public static final int FRAME_PIXELS = 12;
    /** EnumFacing.getHorizontal's front offsets (x, z) and the same facings turned a quarter counter-clockwise. */
    private static final int[][] FRONT = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    private Hanging() {
    }

    /** The art's index by title (a painting with an unknown title draws as the first, "Kebab"). */
    public static int artIndex(String title) {
        for (int index = 0; index < ART.length; index++) {
            if (ART[index].title.equals(title)) {
                return index;
            }
        }
        return 0;
    }

    public static Art art(int index) {
        return ART[Math.floorMod(index, ART.length)];
    }

    /**
     * Where an entity {@code width} by {@code height} pixels hanging in block (x, y, z) facing {@code facing}
     * stands: pushed 0.03125 back into the wall, and moved half a block along the wall for sizes that straddle a
     * block edge.
     */
    public static double[] position(int x, int y, int z, int facing, int width, int height) {
        int[] front = FRONT[facing & 3];
        int[] left = FRONT[(facing + 3) & 3];
        double along = width % 32 == 0 ? 0.5 : 0.0;
        double up = height % 32 == 0 ? 0.5 : 0.0;
        return new double[]{x + 0.5 - front[0] * 0.46875 + along * left[0], y + 0.5 + up,
                z + 0.5 - front[1] * 0.46875 + along * left[1]};
    }
}
