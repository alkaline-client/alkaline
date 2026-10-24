package alkaline.renderer.world;

/**
 * Supplies block ids and light for section meshing. Implementations must be thread-safe: meshing workers call
 * {@link #fill} concurrently for different sections.
 *
 * <p>The volume is the section plus a two-block halo on every side ({@link #PADDED}^3 cells, x fastest, then
 * y, then z; local coordinates -2..17, index {@code (x+2) + (y+2)*20 + (z+2)*400}). Smooth lighting reads up to
 * two cells beyond a face, as vanilla does, so one block of halo is not enough.
 */
public interface BlockSource {
    int HALO = 2;
    int PADDED = 16 + 2 * HALO;
    int VOLUME = PADDED * PADDED * PADDED;
    /** Columns of the padded volume, for biomes ({@link #biomeIndex}). */
    int COLUMNS = PADDED * PADDED;

    /** Lowest and highest section Y (inclusive) that can contain blocks. */
    int minSectionY();

    int maxSectionY();

    /**
     * Writes the section's ids and light levels plus the halo. {@code light} holds {@code sky << 4 | block}
     * (each 0..15) per cell; opaque cells carry 0, like vanilla's stored light.
     */
    void fill(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light);

    /**
     * Writes the biome id of each column of the section and its halo ({@link #COLUMNS} bytes, {@link #biomeIndex}).
     * Sources without biomes leave plains (1).
     */
    default void fillBiomes(int sectionX, int sectionZ, byte[] biomes) {
        java.util.Arrays.fill(biomes, (byte) 1);
    }

    static int index(int x, int y, int z) {
        return (x + HALO) + (y + HALO) * PADDED + (z + HALO) * PADDED * PADDED;
    }

    static int biomeIndex(int x, int z) {
        return (x + HALO) + (z + HALO) * PADDED;
    }
}
