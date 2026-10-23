package alkaline.renderer.world;

import java.util.Objects;

/**
 * One meshed 16x16x16 section in the GPU vertex format: four vertices per quad, four 32-bit words (16 bytes)
 * per vertex. Opaque and cutout quads are grouped by face direction so a renderer can skip faces that cannot
 * point at the camera; translucent quads form one range that is drawn through a per-camera sorted index list.
 *
 * <p>Vertex words (positions in 1/512 block, offset by 8 blocks so elements may overhang the section; texture
 * coordinates in 1/256 tile, wrapping through the sampler):
 * <ul>
 *   <li>0: x (14 bits) | y (14) << 14 | face (3) << 28
 *   <li>1: z (14) | u (13) << 14 | texture id high bits (5) << 27
 *   <li>2: v (13) | texture id low bits (11) << 13 | block light (8) << 24
 *   <li>3: red | green << 8 | blue << 16 | sky light (8) << 24
 * </ul>
 * Color is vanilla's baked vertex color: tint x directional shade x ambient occlusion, 8-bit sRGB. Light is in
 * vanilla's lightmap units (1/16 level, 0..240), the exact precision of its smooth lighting including the
 * weighted corners of partial faces; the lightmap is applied in the shader. Faces 0-5 are axis directions; 6 is
 * any other orientation.
 */
public final class ChunkMesh {
    public static final int INTS_PER_VERTEX = 4;
    public static final int INTS_PER_QUAD = 4 * INTS_PER_VERTEX;
    public static final int BYTES_PER_QUAD = INTS_PER_QUAD * Integer.BYTES;
    public static final int POSITION_SCALE = 512;
    public static final int POSITION_OFFSET_BLOCKS = 8;
    public static final int UV_SCALE = 256;
    /**
     * Layers per block texture array (the array-layer limit of common GPUs). Vertices address up to 65536 texture
     * ids, which the renderer's frame table maps to one of its two arrays.
     */
    public static final int MAX_LAYERS = 2048;
    /** Range slot (and the end of the cube directions) for quads drawn regardless of the camera side. */
    public static final int ANY_DIRECTION = 6;
    public static final int SLOTS_PER_PASS = 7;
    /** The single range holding every translucent quad, in emission order; draw it through a sorted index list. */
    public static final int TRANSLUCENT_RANGE = 2 * SLOTS_PER_PASS;
    public static final int RANGES = TRANSLUCENT_RANGE + 1;

    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private final int[] words;
    private final int[] rangeStart;
    private final int[] rangeCount;
    private final short[] translucentCells;
    private final long visibility;

    ChunkMesh(int sectionX, int sectionY, int sectionZ, int[] words, int[] rangeStart, int[] rangeCount,
              short[] translucentCells, long visibility) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.words = Objects.requireNonNull(words, "words");
        this.rangeStart = rangeStart;
        this.rangeCount = rangeCount;
        this.translucentCells = translucentCells;
        this.visibility = visibility;
    }

    public int sectionX() { return sectionX; }
    public int sectionY() { return sectionY; }
    public int sectionZ() { return sectionZ; }

    public int quadCount() {
        return words.length / INTS_PER_QUAD;
    }

    public boolean isEmpty() {
        return words.length == 0;
    }

    /** Range index for a pass and a face direction (0-5) or {@link #ANY_DIRECTION}; translucent has one range. */
    public static int range(MaterialPass pass, int slot) {
        return pass == MaterialPass.TRANSLUCENT ? TRANSLUCENT_RANGE : pass.ordinal() * SLOTS_PER_PASS + slot;
    }

    /** First quad of a range, relative to the start of this mesh. */
    public int rangeStart(int range) {
        return rangeStart[range];
    }

    public int rangeCount(int range) {
        return rangeCount[range];
    }

    /** Packed vertex words; owned by this mesh, do not modify. */
    public int[] words() {
        return words;
    }

    /**
     * The block cell of each translucent quad, in range order: {@code x | y << 4 | z << 8} (section-local).
     * Owned by this mesh; see {@link TranslucentSorter}.
     */
    public short[] translucentCells() {
        return translucentCells;
    }

    /**
     * Which pairs of section faces see each other through non-opaque blocks: bit {@code a * 6 + b} (and
     * {@code b * 6 + a}) for faces a, b in the mesher's codes. Drives occlusion culling.
     */
    public long visibility() {
        return visibility;
    }

    /** Whether light (and sight) entering through face {@code from} can leave through face {@code to}. */
    public static boolean connects(long visibility, int from, int to) {
        return (visibility >>> (from * 6 + to) & 1L) != 0;
    }

    /** Every face connects to every other: an empty or unknown section. */
    public static final long ALL_CONNECTED = (1L << 36) - 1;
}
