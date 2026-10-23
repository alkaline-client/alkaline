package alkaline.renderer.world;

/**
 * Minecraft 1.8.9 block states ({@code id << 4 | metadata}) that Alkaline's own scenes place, such as the menu
 * terrain. Game worlds carry the server's states directly.
 */
public final class VanillaBlocks {
    public static final char AIR = 0;
    public static final char STONE = (char) (1 << 4 | 0);
    public static final char GRASS = (char) (2 << 4 | 0);
    public static final char DIRT = (char) (3 << 4 | 0);
    public static final char BEDROCK = (char) (7 << 4 | 0);
    public static final char WATER = (char) (9 << 4 | 0);
    public static final char SAND = (char) (12 << 4 | 0);
    public static final char GRAVEL = (char) (13 << 4 | 0);
    public static final char OAK_LOG = (char) (17 << 4 | 0);
    public static final char OAK_LEAVES = (char) (18 << 4 | 0);
    public static final char SANDSTONE = (char) (24 << 4 | 0);
    public static final char TALL_GRASS = (char) (31 << 4 | 1);
    public static final char DANDELION = (char) (37 << 4 | 0);
    public static final char POPPY = (char) (38 << 4 | 0);
    public static final char SNOW = (char) (80 << 4 | 0);
    public static final char CLAY = (char) (82 << 4 | 0);

    private VanillaBlocks() {
    }

    public static char state(int id, int meta) {
        if (id < 0 || id > 4095 || meta < 0 || meta > 15) {
            throw new IllegalArgumentException("Block states are a 12-bit id and 4-bit metadata.");
        }
        return (char) (id << 4 | meta);
    }
}
