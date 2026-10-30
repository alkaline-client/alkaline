package alkaline.renderer.world;

import alkaline.renderer.world.vanilla.BlockModels189;
import alkaline.renderer.world.vanilla.BlockStates189;
import alkaline.renderer.world.vanilla.Blocks189;
import alkaline.renderer.world.vanilla.NeighborRules;
import java.util.Arrays;
import java.util.Objects;

/**
 * Meshes a 16x16x16 section of Minecraft 1.8.9 block states ({@code id << 4 | metadata}), read with a two-block
 * halo from a {@link BlockSource}, into {@link ChunkMesh} quads, following vanilla's BlockModelRenderer and
 * BlockFluidRenderer.
 *
 * <p>Three paths:
 * <ul>
 *   <li><b>Cubes.</b> Models that are plain textured cubes (stone, dirt, logs, leaves, glass, grass with its side
 *   overlay...) are meshed per face plane and merged greedily where the face's appearance, lighting and
 *   occlusion are uniform, so merging never changes the image: texture coordinates follow block positions
 *   (with the face's texture orientation) and the sampler repeats the layer. Overlays are emitted with the same
 *   corners as their base face, so they rasterize to the same depth.
 *   <li><b>Models.</b> Every other block model draws its baked quads with vanilla's lighting: smooth lighting
 *   weighted by the quad's bounds for partial faces, flat lighting from the block or its neighbor, per-position
 *   offsets for plants, and each block's {@code shouldSideBeRendered} for culled faces.
 *   <li><b>Liquids.</b> Water and lava with vanilla's corner heights, flow direction, two-sided sides and flat
 *   lighting.
 * </ul>
 * Smooth lighting reproduces vanilla's AmbientOcclusionFace exactly (per-vertex averages with dark samples
 * replaced by the center, corner substitution two layers out, 0.2 occlusion for normal cubes), and the baked
 * vertex color is vanilla's 8-bit product of shade, tint and occlusion. Light is stored in vanilla's lightmap
 * units (1/16 level).
 *
 * <p>Translucent quads keep their block cell for {@link TranslucentSorter}. The mesher also records which faces
 * of the section see each other through non-opaque blocks (a flood fill), for occlusion culling.
 *
 * <p>Not thread-safe: use one instance per worker.
 */
public final class ChunkMesher {
    public static final int SIZE = 16;
    /** Merge-key capacity for distinct cube face appearances. */
    static final int MAX_APPEARANCES = 1 << 16;
    private static final int PADDED = BlockSource.PADDED;
    private static final int HALO = BlockSource.HALO;
    private static final int SX = 1;
    private static final int SY = PADDED;
    private static final int SZ = PADDED * PADDED;
    private static final int[] STRIDE = {SX, SY, SZ};
    /** In-plane axes per normal axis: X faces span (z, y), Y faces (x, z), Z faces (x, y). */
    private static final int[] U_AXIS = {2, 0, 0};
    private static final int[] V_AXIS = {1, 2, 1};
    private static final int[] CORNER_U = {0, 1, 1, 0};
    private static final int[] CORNER_V = {0, 0, 1, 1};
    private static final int[] NATURAL = {0, 1, 2, 3};
    private static final int[] REVERSED = {0, 3, 2, 1};
    /** Vanilla's directional shade as baked by its face bakery ((int) (brightness * 255)), per face code. */
    static final int[] SHADE = {153, 153, 127, 255, 204, 204};
    /**
     * Which side vanilla substitutes for a corner it may not sample: true for the u-axis side, per face. It
     * follows the neighbor order vanilla lists per face (x first for up, down and south; y first otherwise).
     */
    private static final boolean[] PRIMARY_U = {false, false, true, true, false, true};
    /**
     * Rotation of the emitted corner order per face, so vertices 0 and 2 (the triangles' shared edge) sit on
     * the same diagonal as vertex 0 and 2 of vanilla's quad for that face.
     */
    private static final int[] ROTATE = {1, 0, 1, 0, 0, 1};
    /** Occlusion for a 4-bit pattern of occluding samples, summed in vanilla's order. */
    private static final float[] OCCLUSION = new float[16];
    private static final int TEXTURE_OFFSET_TILES = 8;

    // Vanilla EnumFacing order (down, up, north, south, west, east) for the model lighting tables.
    private static final int[] VANILLA_TO_CODE = {2, 3, 4, 5, 0, 1};
    private static final int[] CODE_TO_VANILLA = {4, 5, 0, 1, 2, 3};
    private static final int[] VANILLA_OFFSET = {-SY, SY, -SZ, SZ, -SX, SX};
    /** EnumNeighborInfo corners per vanilla face. */
    private static final int[][] NEIGHBORS = {{4, 5, 2, 3}, {5, 4, 2, 3}, {1, 0, 5, 4}, {4, 5, 0, 1}, {1, 0, 2, 3},
            {0, 1, 2, 3}};
    /** EnumNeighborInfo weight orientations (bounds index, +6 for flipped), per vanilla face, four arrays of 8. */
    private static final int[][][] WEIGHTS = new int[6][][];
    /** VertexTranslations per vanilla face. */
    private static final int[][] TRANSLATION = {{0, 1, 2, 3}, {2, 3, 0, 1}, {3, 0, 1, 2}, {0, 1, 2, 3}, {3, 0, 1, 2},
            {1, 2, 3, 0}};

    static {
        for (int pattern = 0; pattern < 16; pattern++) {
            OCCLUSION[pattern] = (occ(pattern, 0) + occ(pattern, 1) + occ(pattern, 2) + occ(pattern, 3)) * 0.25F;
        }
        final int dn = 0;
        final int up = 1;
        final int no = 2;
        final int so = 3;
        final int we = 4;
        final int ea = 5;
        final int f = 6;
        WEIGHTS[2] = new int[][]{{up, f + we, up, we, f + up, we, f + up, f + we},
                {up, f + ea, up, ea, f + up, ea, f + up, f + ea},
                {dn, f + ea, dn, ea, f + dn, ea, f + dn, f + ea},
                {dn, f + we, dn, we, f + dn, we, f + dn, f + we}};
        WEIGHTS[3] = new int[][]{{up, f + we, f + up, f + we, f + up, we, up, we},
                {dn, f + we, f + dn, f + we, f + dn, we, dn, we},
                {dn, f + ea, f + dn, f + ea, f + dn, ea, dn, ea},
                {up, f + ea, f + up, f + ea, f + up, ea, up, ea}};
        WEIGHTS[4] = new int[][]{{up, so, up, f + so, f + up, f + so, f + up, so},
                {up, no, up, f + no, f + up, f + no, f + up, no},
                {dn, no, dn, f + no, f + dn, f + no, f + dn, no},
                {dn, so, dn, f + so, f + dn, f + so, f + dn, so}};
        WEIGHTS[5] = new int[][]{{f + dn, so, f + dn, f + so, dn, f + so, dn, so},
                {f + dn, no, f + dn, f + no, dn, f + no, dn, no},
                {f + up, no, f + up, f + no, up, f + no, up, no},
                {f + up, so, f + up, f + so, up, f + so, up, so}};
    }

    private static float occ(int pattern, int bit) {
        return (pattern >> bit & 1) != 0 ? 0.2F : 1.0F;
    }

    private static final long PRESENT = 1L << 63;
    private static final long MERGEABLE = 1L << 62;

    private final long[] mask = new long[SIZE * SIZE];
    /** Per mask cell and corner, for faces that do not merge: occlusion pattern and light (sky8 << 8 | block8). */
    private final int[] cornerPattern = new int[SIZE * SIZE * 4];
    private final int[] cornerLight = new int[SIZE * SIZE * 4];
    private final int[][] buckets = new int[ChunkMesh.RANGES][];
    private final int[] bucketWords = new int[ChunkMesh.RANGES];
    private short[] cells = new short[256];
    /** Per translucent quad: its direction (face code) or 7 when not axis-aligned, and its plane coordinate. */
    private byte[] translucentDirection = new byte[256];
    private int cellCount;
    private final int[] position = new int[3];
    private final long[] visited = new long[SIZE * SIZE * SIZE / 64];
    private final int[] queue = new int[SIZE * SIZE * SIZE];
    /** Vanilla combined light per padded cell (sky * 16 << 16 | block * 16): with and without own emission. */
    private final int[] bright = new int[BlockSource.VOLUME];
    private final int[] raw = new int[BlockSource.VOLUME];
    /** Per section cell: the entry (state + neighbor code) and chosen model alternative, or -1. */
    private final int[] cellEntry = new int[SIZE * SIZE * SIZE];
    private final int[] cellChoice = new int[SIZE * SIZE * SIZE];
    // Scratch for model lighting.
    private final float[] quadBounds = new float[12];
    private final float[] vertexMultiplier = new float[4];
    private final int[] vertexBrightness = new int[4];
    private final float[] liquidHeights = new float[4];

    /** Connected-texture overlay tiles a face can carry (OptiFine draws one or two per overlay rule). */
    private static final int MAX_OVERLAYS = 4;
    /** The merge key's connected-texture field for a ctm_compact face, whose quarters' layers are in {@link #compactLayers}. */
    private static final int COMPACT = 0xFFFF;
    /** Per mask cell: connected-texture overlay layers and their rules, and a ctm_compact face's quarter layers. */
    private final int[] overlayCount = new int[SIZE * SIZE];
    private final int[] overlayLayers = new int[SIZE * SIZE * MAX_OVERLAYS];
    private final int[] overlayRules = new int[SIZE * SIZE * MAX_OVERLAYS];
    private final int[] compactLayers = new int[SIZE * SIZE * 4];
    private final int[] overlayTiles = new int[2];

    private BlockPalette palette;
    private char[] ids;
    private byte[] light;
    /** Biome ids per padded column ({@link BlockSource#biomeIndex}), or null for plains. */
    private byte[] biomes;
    private boolean smooth;
    private LeafCulling leaves = LeafCulling.OFF;
    private int originX;
    private int originY;
    private int originZ;
    /** The only section cell to mesh ({@code x | y << 4 | z << 8}), or -1 for all of them. */
    private int onlyCell = -1;

    public ChunkMesher() {
        for (int range = 0; range < buckets.length; range++) {
            buckets[range] = new int[512];
        }
    }

    /**
     * @param ids    {@link BlockSource#VOLUME} block states, laid out as {@link BlockSource#index}
     * @param light  the same cells' light, {@code sky << 4 | block}
     * @param smooth vanilla smooth lighting (ambient occlusion and per-vertex light); off is flat per face
     */
    public ChunkMesh mesh(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light, BlockPalette palette,
                          boolean smooth) {
        return mesh(sectionX, sectionY, sectionZ, ids, light, null, palette, smooth);
    }

    /**
     * @param biomes {@link BlockSource#COLUMNS} biome ids of the padded columns, or null for plains everywhere; read
     *               only when connected textures have biome conditions
     */
    public ChunkMesh mesh(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light, byte[] biomes,
                          BlockPalette palette, boolean smooth) {
        return mesh(sectionX, sectionY, sectionZ, ids, light, biomes, palette, smooth, LeafCulling.OFF);
    }

    /** @param leaves which faces between leaf blocks to leave out ({@link LeafCulling}) */
    public ChunkMesh mesh(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light, byte[] biomes,
                          BlockPalette palette, boolean smooth, LeafCulling leaves) {
        this.leaves = Objects.requireNonNull(leaves, "leaves");
        try {
            return mesh(sectionX, sectionY, sectionZ, ids, light, biomes, palette, smooth, -1);
        } finally {
            this.leaves = LeafCulling.OFF;
        }
    }

    /**
     * Meshes one block of a section as the section would draw it, for effects that redraw a single block (the
     * destroy overlay). The other cells still count as neighbors for culling, connections and lighting. The mesh
     * holds that block's quads only, in the section's coordinates.
     *
     * @param x section-local block position, 0..15 each
     */
    public ChunkMesh meshBlock(int sectionX, int sectionY, int sectionZ, int x, int y, int z, char[] ids,
                               byte[] light, BlockPalette palette, boolean smooth) {
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
            throw new IllegalArgumentException("Block position outside the section: " + x + ", " + y + ", " + z);
        }
        return mesh(sectionX, sectionY, sectionZ, ids, light, null, palette, smooth, x | y << 4 | z << 8);
    }

    private ChunkMesh mesh(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light, byte[] biomes,
                           BlockPalette palette, boolean smooth, int only) {
        this.onlyCell = only;
        this.palette = Objects.requireNonNull(palette, "palette");
        if (ids.length != BlockSource.VOLUME || light.length != BlockSource.VOLUME) {
            throw new IllegalArgumentException("Section ids and light must be 20^3 including the halo.");
        }
        if (biomes != null && biomes.length != BlockSource.COLUMNS) {
            throw new IllegalArgumentException("Section biomes must be 20^2 including the halo.");
        }
        this.ids = ids;
        this.light = light;
        this.biomes = biomes;
        this.smooth = smooth;
        originX = sectionX * SIZE;
        originY = sectionY * SIZE;
        originZ = sectionZ * SIZE;
        Arrays.fill(bucketWords, 0);
        cellCount = 0;
        try {
            computeBrightness();
            resolveCells();
            for (int face = 0; face < 6; face++) {
                meshFace(face);
            }
            meshModelsAndLiquids();
            orderTranslucent();
            int total = 0;
            for (int words : bucketWords) {
                total += words;
            }
            int[] words = new int[total];
            int[] start = new int[ChunkMesh.RANGES];
            int[] count = new int[ChunkMesh.RANGES];
            int cursor = 0;
            for (int range = 0; range < ChunkMesh.RANGES; range++) {
                System.arraycopy(buckets[range], 0, words, cursor, bucketWords[range]);
                start[range] = cursor / ChunkMesh.INTS_PER_QUAD;
                count[range] = bucketWords[range] / ChunkMesh.INTS_PER_QUAD;
                cursor += bucketWords[range];
            }
            boolean sorted = staticTranslucent();
            return new ChunkMesh(sectionX, sectionY, sectionZ, words, start, count,
                    sorted ? new short[0] : Arrays.copyOf(cells, cellCount), visibility());
        } finally {
            this.palette = null;
            this.ids = null;
            this.light = null;
            this.biomes = null;
        }
    }

    // ---- per-section preparation ----------------------------------------------------------------------------

    /**
     * Vanilla's getMixedBrightnessForBlock for every padded cell: stored light, or the brightest up/side
     * neighbor for blocks with useNeighborBrightness; block light at least the cell's own emission; and a slab
     * that reads zero takes the cell below.
     */
    private void computeBrightness() {
        for (int z = 0; z < PADDED; z++) {
            for (int y = 0; y < PADDED; y++) {
                for (int x = 0; x < PADDED; x++) {
                    int index = x + y * SY + z * SZ;
                    int id = ids[index] >> 4;
                    int packed = light[index] & 0xFF;
                    int sky = packed >> 4;
                    int block = packed & 15;
                    if (Blocks189.neighborBrightness(id) && x > 0 && x < PADDED - 1 && y < PADDED - 1 && z > 0
                            && z < PADDED - 1) {
                        sky = 0;
                        block = 0;
                        for (int offset : new int[]{SY, SX, -SX, SZ, -SZ}) {
                            int neighbor = light[index + offset] & 0xFF;
                            sky = Math.max(sky, neighbor >> 4);
                            block = Math.max(block, neighbor & 15);
                        }
                    }
                    raw[index] = sky * 16 << 16 | block * 16;
                    bright[index] = sky * 16 << 16 | Math.max(block, Blocks189.light(id)) * 16;
                }
            }
        }
        for (int index = SY; index < BlockSource.VOLUME; index++) {
            if (bright[index] == 0 && slab(ids[index] >> 4)) {
                bright[index] = bright[index - SY];
            }
        }
    }

    private static boolean slab(int id) {
        return id == 43 || id == 44 || id == 125 || id == 126 || id == 181 || id == 182;
    }

    /** Resolves each section cell's palette entry (with neighbor-derived properties) and model alternative. */
    private void resolveCells() {
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                int row = BlockSource.index(0, y, z);
                for (int x = 0; x < SIZE; x++) {
                    int cell = x | y << 4 | z << 8;
                    int index = row + x;
                    int state = ids[index];
                    int base = state == 0 || onlyCell >= 0 && cell != onlyCell ? -1 : palette.stateBase[state];
                    if (base < 0) {
                        cellEntry[cell] = -1;
                        cellChoice[cell] = -1;
                        continue;
                    }
                    int id = state >> 4;
                    int extra = BlockStates189.extraCount(id) > 1
                            ? NeighborRules.extra(ids, index, originX + x, originY + y, originZ + z) : 0;
                    int entry = base + extra;
                    int count = palette.entryChoiceCount[entry];
                    if (count == 0) {
                        cellEntry[cell] = -1;
                        cellChoice[cell] = -1;
                        continue;
                    }
                    int first = palette.entryFirstChoice[entry];
                    int pick = 0;
                    if (count > 1) {
                        long random = positionHash(originX + x, originY + y, originZ + z);
                        int total = 0;
                        for (int choice = 0; choice < count; choice++) {
                            total += palette.choiceWeight[first + choice];
                        }
                        int weight = Math.abs((int) random >> 16) % total;
                        for (pick = 0; pick < count - 1; pick++) {
                            weight -= palette.choiceWeight[first + pick];
                            if (weight < 0) {
                                break;
                            }
                        }
                    }
                    cellEntry[cell] = entry;
                    cellChoice[cell] = first + pick;
                }
            }
        }
    }

    // ---- side culling ---------------------------------------------------------------------------------------

    /**
     * The block's shouldSideBeRendered for face {@code face} (mesher code), given its block bounds from its
     * model.
     */
    private boolean sideVisible(int index, int face, int model) {
        int state = ids[index];
        int id = state >> 4;
        int neighborIndex = index + ((face & 1) == 1 ? STRIDE[face >> 1] : -STRIDE[face >> 1]);
        int neighbor = ids[neighborIndex];
        int neighborId = neighbor >> 4;
        return switch (Blocks189.sides(id)) {
            case ALWAYS -> true;
            case GLASS -> neighbor != state;
            case SAME_BLOCK, PANE -> neighborId != id && defaultSide(face, neighborId, model);
            case WALL -> face != BlockModels189.DOWN || defaultSide(face, neighborId, model);
            case TOP_ALWAYS -> face == BlockModels189.UP || defaultSide(face, neighborId, model);
            case FARMLAND -> switch (face) {
                case BlockModels189.UP -> true;
                case BlockModels189.DOWN -> defaultSide(face, neighborId, model);
                default -> !Blocks189.opaqueCube(neighborId) && neighborId != 60;
            };
            case DIODE -> face != BlockModels189.UP && face != BlockModels189.DOWN;
            case SLAB -> slabSide(state, neighbor, face, model);
            case PORTAL -> portalSide(neighborIndex, face);
            case DEFAULT -> defaultSide(face, neighborId, model);
        };
    }

    /** Block.shouldSideBeRendered: faces inside the block's bounds always show; others unless an opaque cube hides them. */
    private boolean defaultSide(int face, int neighborId, int model) {
        if (model >= 0) {
            float[] bounds = palette.modelBounds;
            int base = model * 6;
            boolean inside = switch (face) {
                case BlockModels189.DOWN -> bounds[base + 1] > 0.0F;
                case BlockModels189.UP -> bounds[base + 4] < 1.0F;
                case BlockModels189.NORTH -> bounds[base + 2] > 0.0F;
                case BlockModels189.SOUTH -> bounds[base + 5] < 1.0F;
                case BlockModels189.WEST -> bounds[base] > 0.0F;
                default -> bounds[base + 3] < 1.0F;
            };
            if (inside) {
                return true;
            }
        }
        return !Blocks189.opaqueCube(neighborId);
    }

    /** BlockSlab.shouldSideBeRendered for half slabs. */
    private boolean slabSide(int state, int neighbor, int face, int model) {
        int neighborId = neighbor >> 4;
        boolean vertical = face == BlockModels189.UP || face == BlockModels189.DOWN;
        if (!vertical && !defaultSide(face, neighborId, model)) {
            return false;
        }
        boolean neighborSlab = Blocks189.halfSlab(neighborId);
        boolean neighborTop = neighborSlab && (neighbor & 8) != 0;
        boolean ownTop = (state & 8) != 0;
        if (ownTop) {
            return face == BlockModels189.DOWN
                    || (face == BlockModels189.UP && defaultSide(face, neighborId, model))
                    || !neighborSlab || !neighborTop;
        }
        return face == BlockModels189.UP
                || (face == BlockModels189.DOWN && defaultSide(face, neighborId, model))
                || !neighborSlab || neighborTop;
    }

    /** BlockPortal.shouldSideBeRendered; {@code at} is the neighbor's index (vanilla's pos). */
    private boolean portalSide(int at, int face) {
        int state = ids[at];
        int axis = -1;
        if (state >> 4 == 90) {
            axis = (state & 3) == 2 ? 2 : 0;
            if (axis == 2 && face != BlockModels189.EAST && face != BlockModels189.WEST) {
                return false;
            }
            if (axis == 0 && face != BlockModels189.SOUTH && face != BlockModels189.NORTH) {
                return false;
            }
        }
        boolean west = ids[at - SX] >> 4 == 90 && ids[at - 2 * SX] >> 4 != 90;
        boolean east = ids[at + SX] >> 4 == 90 && ids[at + 2 * SX] >> 4 != 90;
        boolean north = ids[at - SZ] >> 4 == 90 && ids[at - 2 * SZ] >> 4 != 90;
        boolean south = ids[at + SZ] >> 4 == 90 && ids[at + 2 * SZ] >> 4 != 90;
        boolean alongX = west || east || axis == 0;
        boolean alongZ = north || south || axis == 2;
        return alongX && (face == BlockModels189.WEST || face == BlockModels189.EAST)
                || alongZ && (face == BlockModels189.NORTH || face == BlockModels189.SOUTH);
    }

    // ---- cube faces -----------------------------------------------------------------------------------------

    private void meshFace(int face) {
        int axis = face >> 1;
        int uAxis = U_AXIS[axis];
        int vAxis = V_AXIS[axis];
        int uStride = STRIDE[uAxis];
        int vStride = STRIDE[vAxis];
        for (int plane = 0; plane < SIZE; plane++) {
            boolean any = false;
            int planeBase = HALO * (SX + SY + SZ) + plane * STRIDE[axis];
            for (int v = 0; v < SIZE; v++) {
                int row = planeBase + v * vStride;
                for (int u = 0; u < SIZE; u++) {
                    position[axis] = plane;
                    position[uAxis] = u;
                    position[vAxis] = v;
                    int cell = position[0] | position[1] << 4 | position[2] << 8;
                    long key = faceKey(row + u * uStride, face, cell, v * SIZE + u);
                    mask[v * SIZE + u] = key;
                    any |= key != 0;
                }
            }
            if (any) {
                mergePlane(face, axis, uAxis, vAxis, plane);
            }
        }
    }

    /**
     * The merge key of one cube face, or 0 when the cell is not a cube or the face is hidden: present |
     * mergeable | appearance (16) << 36 | occlusion pattern (4) << 32 | connected-texture layer + 1 (16) << 16 |
     * light (sky8 << 8 | block8). Faces whose
     * corners differ keep their corners in {@link #cornerPattern}/{@link #cornerLight} and never merge.
     */
    private long faceKey(int index, int face, int cell, int maskCell) {
        overlayCount[maskCell] = 0;
        int choice = cellChoice[cell];
        if (choice < 0) {
            return 0;
        }
        int model = palette.choiceModel[choice];
        if (!palette.modelCube[model]) {
            return 0;
        }
        int faceQuad = palette.modelQuadStart[model];
        while (palette.quadFace[faceQuad] != face) {
            faceQuad++;
        }
        int id = ids[index] >> 4;
        boolean visible = (palette.quadCullFace[faceQuad] >= 0 ? sideVisible(index, face, model)
                : !Blocks189.opaqueCube(ids[index + offsetOf(face)] >> 4)) && !leafHidden(id, index, face);
        if (!visible) {
            return 0;
        }
        int appearance = palette.choiceAppearance[choice * 6 + face];
        boolean translucent = Blocks189.pass(id) == MaterialPass.TRANSLUCENT;
        long base = PRESENT | (translucent ? 0 : MERGEABLE) | (long) appearance << 36;
        int list = palette.choiceCtm[choice * 6 + face];
        // Compact faces and faces with overlays are drawn one by one, from their corners.
        boolean single = false;
        if (list >= 0) {
            int connected = connectedCube(list, index, face, cell, maskCell, palette.appearanceLayer[appearance]);
            base |= (long) connected << 16;
            single = connected == COMPACT || overlayCount[maskCell] > 0;
        }
        int axis = face >> 1;
        int normal = (face & 1) == 1 ? STRIDE[axis] : -STRIDE[axis];
        int front = index + normal;
        if (!smoothFor(id, model)) {
            int lit = lightWord(bright[front]);
            if (single) {
                for (int corner = 0; corner < 4; corner++) {
                    cornerPattern[maskCell * 4 + corner] = 0;
                    cornerLight[maskCell * 4 + corner] = lit;
                }
                base &= ~MERGEABLE;
            }
            return base | lit;
        }
        int uStep = STRIDE[U_AXIS[axis]];
        int vStep = STRIDE[V_AXIS[axis]];
        boolean centerOccludes = Blocks189.normalCube(ids[front] >> 4);
        int center = bright[front];
        boolean uniform = true;
        int firstPattern = 0;
        int firstLight = 0;
        for (int corner = 0; corner < 4; corner++) {
            int sideU = front + (CORNER_U[corner] == 1 ? uStep : -uStep);
            int sideV = front + (CORNER_V[corner] == 1 ? vStep : -vStep);
            int primary = PRIMARY_U[face] ? sideU : sideV;
            int secondary = PRIMARY_U[face] ? sideV : sideU;
            boolean open = Blocks189.translucent(ids[sideU + normal] >> 4)
                    || Blocks189.translucent(ids[sideV + normal] >> 4);
            int diagonal = open ? sideU + sideV - front : primary;
            // Vanilla's order of operations: (secondary + primary + corner + center) * 0.25F.
            int pattern = (Blocks189.normalCube(ids[secondary] >> 4) ? 1 : 0)
                    | (Blocks189.normalCube(ids[primary] >> 4) ? 2 : 0)
                    | (Blocks189.normalCube(ids[diagonal] >> 4) ? 4 : 0) | (centerOccludes ? 8 : 0);
            int lit = lightWord(aoBrightness(bright[primary], bright[secondary], bright[diagonal], center));
            cornerPattern[maskCell * 4 + corner] = pattern;
            cornerLight[maskCell * 4 + corner] = lit;
            if (corner == 0) {
                firstPattern = pattern;
                firstLight = lit;
            } else if (pattern != firstPattern || lit != firstLight) {
                uniform = false;
            }
        }
        if (!uniform || single) {
            base &= ~MERGEABLE;
        }
        return base | (long) firstPattern << 32 | firstLight;
    }

    /**
     * Runs a cube face through its connected-texture rules: overlay rules leave their tiles in
     * {@link #overlayLayers}; the first other rule that gives a tile decides the face's texture. Returns the merge
     * key's connected field: the tile's layer + 1, 0 to keep the face's texture, or {@link #COMPACT} with the
     * quarters' layers in {@link #compactLayers}.
     */
    private int connectedCube(int list, int index, int face, int cell, int maskCell, int baseLayer) {
        int x = originX + (cell & 15);
        int y = originY + (cell >> 4 & 15);
        int z = originZ + (cell >> 8);
        int biome = palette.ctmBiomes ? biome(cell & 15, cell >> 8) : 1;
        for (int at = list; palette.ctmLists[at] >= 0; at++) {
            int ruleIndex = palette.ctmLists[at];
            ConnectedTextures.Rule rule = palette.ctm.rules().get(ruleIndex);
            if (!rule.applies(y, biome)) {
                continue;
            }
            neighbors.set(index, face, baseLayer, rule);
            int start = palette.ctmTileStart[ruleIndex];
            if (rule.method().overlay()) {
                if (rule.method() == ConnectedTextures.Method.OVERLAY) {
                    int count = ConnectedTextures.overlay(rule, face, y, neighbors, overlayTiles);
                    for (int tile = 0; tile < count; tile++) {
                        addOverlay(maskCell, palette.ctmTiles[start + overlayTiles[tile]], ruleIndex);
                    }
                } else {
                    int tile = ConnectedTextures.tile(rule, face, x, y, z, neighbors);
                    if (tile >= 0) {
                        addOverlay(maskCell, palette.ctmTiles[start + tile], ruleIndex);
                    }
                }
                continue;
            }
            int tile = ConnectedTextures.tile(rule, face, x, y, z, neighbors);
            if (tile < 0) {
                continue;
            }
            if (rule.method() == ConnectedTextures.Method.CTM_COMPACT) {
                for (int quarter = 0; quarter < 4; quarter++) {
                    int layer = palette.ctmTiles[start + ConnectedTextures.compactTile(rule, tile, quarter)];
                    compactLayers[maskCell * 4 + quarter] = layer < 0 ? baseLayer : layer;
                }
                return COMPACT;
            }
            int layer = palette.ctmTiles[start + tile];
            if (layer == BlockPalette.SKIP_TILE) {
                continue;
            }
            return layer == BlockPalette.KEEP_TILE || layer == baseLayer ? 0 : layer + 1;
        }
        return 0;
    }

    private void addOverlay(int maskCell, int layer, int rule) {
        int count = overlayCount[maskCell];
        if (layer >= 0 && count < MAX_OVERLAYS) {
            overlayLayers[maskCell * MAX_OVERLAYS + count] = layer;
            overlayRules[maskCell * MAX_OVERLAYS + count] = rule;
            overlayCount[maskCell] = count + 1;
        }
    }

    /**
     * A model quad's texture after its connected-texture rules (overlays and ctm_compact only apply to cube faces),
     * or its own layer.
     */
    private int connectedQuad(int list, int index, int face, int cell, int baseLayer) {
        int x = originX + (cell & 15);
        int y = originY + (cell >> 4 & 15);
        int z = originZ + (cell >> 8);
        int biome = palette.ctmBiomes ? biome(cell & 15, cell >> 8) : 1;
        for (int at = list; palette.ctmLists[at] >= 0; at++) {
            int ruleIndex = palette.ctmLists[at];
            ConnectedTextures.Rule rule = palette.ctm.rules().get(ruleIndex);
            if (rule.method().overlay() || rule.method() == ConnectedTextures.Method.CTM_COMPACT
                    || !rule.applies(y, biome)) {
                continue;
            }
            neighbors.set(index, face, baseLayer, rule);
            int tile = ConnectedTextures.tile(rule, face, x, y, z, neighbors);
            if (tile < 0) {
                continue;
            }
            int layer = palette.ctmTiles[palette.ctmTileStart[ruleIndex] + tile];
            if (layer == BlockPalette.SKIP_TILE) {
                continue;
            }
            return layer == BlockPalette.KEEP_TILE ? baseLayer : layer;
        }
        return baseLayer;
    }

    /** The biome of a section-local column (-2..17). */
    private int biome(int x, int z) {
        return biomes == null ? 1 : biomes[BlockSource.biomeIndex(x, z)] & 0xFF;
    }

    /**
     * The texture a cell shows a neighbor on a face (ConnectedTextures.getNeighbourIcon), from its resolved entry:
     * inside the section the one {@link #resolveCells} chose, in the halo the state's own (neighbor-derived
     * properties are resolved where the halo still holds their neighbors).
     */
    private int faceLayerAt(int index, int face) {
        int state = ids[index];
        int base = state == 0 ? -1 : palette.stateBase[state];
        if (base < 0) {
            return -1;
        }
        int x = index % PADDED - HALO;
        int y = index / SY % PADDED - HALO;
        int z = index / SZ - HALO;
        int entry = base;
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE) {
            entry = cellEntry[x | y << 4 | z << 8];
        } else if (BlockStates189.extraCount(state >> 4) > 1 && x >= -1 && x <= SIZE && y >= -1 && y <= SIZE
                && z >= -1 && z <= SIZE) {
            entry = base + NeighborRules.extra(ids, index, originX + x, originY + y, originZ + z);
        }
        return entry < 0 ? -1 : palette.entryFaceLayer[entry * 6 + face];
    }

    /**
     * ConnectedTextures.isNeighbour and the overlay tests for one rule and face: a neighbor joins when it is the
     * same block state, or by the rule's connect (the same texture on that face, or the same material).
     */
    private final class Neighbors implements ConnectedTextures.Neighbors, ConnectedTextures.OverlayNeighbors {
        int index;
        int state;
        int face;
        int layer;
        ConnectedTextures.Rule rule;

        void set(int index, int face, int layer, ConnectedTextures.Rule rule) {
            this.index = index;
            this.state = ids[index];
            this.face = face;
            this.layer = layer;
            this.rule = rule;
        }

        @Override
        public boolean connects(int dx, int dy, int dz) {
            return joins(index + dx * SX + dy * SY + dz * SZ);
        }

        private boolean joins(int at) {
            int other = ids[at];
            if (other == state) {
                return true;
            }
            if (other == 0) {
                return false;
            }
            return switch (rule.connect()) {
                case ConnectedTextures.CONNECT_TILE -> faceLayerAt(at, face) == layer;
                case ConnectedTextures.CONNECT_MATERIAL -> Blocks189.material(other >> 4) == Blocks189.material(state >> 4);
                default -> false;
            };
        }

        @Override
        public boolean sameBlock(int dx, int dy, int dz) {
            return ids[index + dx * SX + dy * SY + dz * SZ] >> 4 == state >> 4;
        }

        /** isNeighbourOverlay: a full cube of another kind, with its face toward the viewer uncovered. */
        @Override
        public boolean overlay(int dx, int dy, int dz) {
            int at = index + dx * SX + dy * SY + dz * SZ;
            int other = ids[at];
            int id = other >> 4;
            if (!Blocks189.fullCube(id) && id != 20 && id != 95 || other == 0) {
                return false;
            }
            if ((rule.connectBlocks().length > 0 || !rule.connectTiles().isEmpty()) && !rule.connectsTo(id, other & 15,
                    texture(faceLayerAt(at, face)))) {
                return false;
            }
            return uncovered(at) && !joins(at);
        }

        /** isNeighbourMatching: a block the rule itself matches, with its face toward the viewer uncovered. */
        @Override
        public boolean matching(int dx, int dy, int dz) {
            int at = index + dx * SX + dy * SY + dz * SZ;
            int other = ids[at];
            if (other == 0) {
                return false;
            }
            if (rule.blocks().length > 0 && !rule.matchesBlock(other >> 4, other & 15)) {
                return false;
            }
            if (!rule.textures().isEmpty() && faceLayerAt(at, face) != layer) {
                return false;
            }
            return uncovered(at);
        }

        private boolean uncovered(int at) {
            int front = ids[at + offsetOf(face)] >> 4;
            return !Blocks189.opaqueCube(front) && (face != BlockModels189.UP || front != 78);
        }

        private String texture(int layer) {
            return layer < 0 ? "" : palette.textures().get(layer);
        }
    }

    private final Neighbors neighbors = new Neighbors();

    /** Vanilla smooth-lights a block when enabled, the block emits no light, and its model asks for occlusion. */
    private boolean smoothFor(int id, int model) {
        return smooth && Blocks189.light(id) == 0 && palette.modelAo[model];
    }

    /** AmbientOcclusionFace.getAoBrightness: dark samples take the center's value. */
    private static int aoBrightness(int a, int b, int c, int center) {
        if (a == 0) {
            a = center;
        }
        if (b == 0) {
            b = center;
        }
        if (c == 0) {
            c = center;
        }
        return a + b + c + center >> 2 & 0xFF00FF;
    }

    /** Vanilla packed brightness (sky << 16 | block, lightmap units) to the vertex's sky8 << 8 | block8. */
    private static int lightWord(int brightness) {
        return (brightness >> 16 & 255) << 8 | brightness & 255;
    }

    private static int rgb(int red, int green, int blue) {
        return red | green << 8 | blue << 16;
    }

    /** Vanilla's color multiplication: (int) (shade * (occlusion * tint channel)). */
    private static int color(int shade, float occlusion, int tint) {
        float red = (tint >> 16 & 255) / 255.0F;
        float green = (tint >> 8 & 255) / 255.0F;
        float blue = (tint & 255) / 255.0F;
        return rgb((int) (shade * (occlusion * red)), (int) (shade * (occlusion * green)),
                (int) (shade * (occlusion * blue)));
    }

    private void mergePlane(int face, int axis, int uAxis, int vAxis, int plane) {
        for (int v = 0; v < SIZE; v++) {
            for (int u = 0; u < SIZE; ) {
                long key = mask[v * SIZE + u];
                if (key == 0) {
                    u++;
                    continue;
                }
                int width = 1;
                int height = 1;
                if ((key & MERGEABLE) != 0) {
                    while (u + width < SIZE && mask[v * SIZE + u + width] == key) {
                        width++;
                    }
                    grow:
                    while (v + height < SIZE) {
                        int row = (v + height) * SIZE + u;
                        for (int across = 0; across < width; across++) {
                            if (mask[row + across] != key) {
                                break grow;
                            }
                        }
                        height++;
                    }
                }
                for (int dv = 0; dv < height; dv++) {
                    Arrays.fill(mask, (v + dv) * SIZE + u, (v + dv) * SIZE + u + width, 0L);
                }
                emitCube(face, axis, uAxis, vAxis, plane, u, v, width, height, key);
                u += width;
            }
        }
    }

    private void emitCube(int face, int axis, int uAxis, int vAxis, int plane, int u, int v, int width, int height,
                          long key) {
        int appearance = (int) (key >>> 36) & 0xFFFF;
        position[axis] = plane;
        position[uAxis] = u;
        position[vAxis] = v;
        int cellIndex = BlockSource.index(position[0], position[1], position[2]);
        int id = ids[cellIndex] >> 4;
        int connected = (int) (key >>> 16) & 0xFFFF;
        int orient = palette.appearanceOrient[appearance];
        int tint = palette.appearanceTint[appearance];
        int maskCell = v * SIZE + u;
        if (connected == COMPACT) {
            for (int quarter = 0; quarter < 4; quarter++) {
                emitCompactQuarter(face, axis, uAxis, vAxis, plane, u, v, quarter, id, orient, tint, maskCell);
            }
        } else {
            int layer = connected != 0 ? connected - 1 : palette.appearanceLayer[appearance];
            MaterialPass pass = passFor(id, layer);
            emitCubeLayer(face, axis, uAxis, vAxis, plane, u, v, width, height, key, layer, orient, tint, pass, false);
            int glow = palette.emissiveLayer[layer];
            if (glow >= 0) {
                emitCubeLayer(face, axis, uAxis, vAxis, plane, u, v, width, height, key, glow, orient, tint,
                        emissivePass(pass), true);
            }
        }
        int overlay = palette.overlayLayer[appearance];
        if (overlay >= 0) {
            MaterialPass overlayPass = Blocks189.pass(id) == MaterialPass.TRANSLUCENT ? MaterialPass.TRANSLUCENT
                    : MaterialPass.CUTOUT;
            emitCubeLayer(face, axis, uAxis, vAxis, plane, u, v, width, height, key, overlay,
                    palette.overlayOrient[appearance], palette.overlayTint[appearance], overlayPass, false);
        }
        // Connected-texture overlays: full-face quads in the standard orientation (BlockModelUtils.makeBakedQuad).
        for (int index = 0; index < overlayCount[maskCell] && (key & MERGEABLE) == 0; index++) {
            int rule = overlayRules[maskCell * MAX_OVERLAYS + index];
            emitCubeLayer(face, axis, uAxis, vAxis, plane, u, v, width, height, key,
                    overlayLayers[maskCell * MAX_OVERLAYS + index], 0, palette.ctmTint[rule],
                    palette.ctm.rules().get(rule).layer(), false);
        }
    }

    /** OptiFine draws a quad's emissive texture in the quad's layer, or cutout for solid quads. */
    private static MaterialPass emissivePass(MaterialPass pass) {
        return pass == MaterialPass.OPAQUE ? MaterialPass.CUTOUT : pass;
    }

    /**
     * One quarter of a ctm_compact face (ConnectedTexturesCompact): the face split at its middle in the plane, each
     * quarter textured with the compact tile of the texture quarter it covers, lit by interpolating the face's
     * corners as vanilla lights a partial face.
     */
    private void emitCompactQuarter(int face, int axis, int uAxis, int vAxis, int plane, int u, int v, int quarter,
                                    int id, int orient, int tint, int maskCell) {
        int pu = quarter & 1;
        int pv = quarter >> 1;
        boolean positive = (face & 1) == 1;
        int facePlane = positive ? plane + 1 : plane;
        // Texture quarter: from the natural texture coordinates (in half blocks) of the quarter's lower corner.
        position[axis] = facePlane * 2;
        position[uAxis] = u * 2 + pu;
        position[vAxis] = v * 2 + pv;
        int nu = Math.min(naturalU(face, position[0], position[1], position[2], 2 * SIZE),
                naturalU(face, position[0] + (uAxis == 0 || vAxis == 0 ? 1 : 0),
                        position[1] + (uAxis == 1 || vAxis == 1 ? 1 : 0),
                        position[2] + (uAxis == 2 || vAxis == 2 ? 1 : 0), 2 * SIZE));
        int nv = Math.min(naturalV(face, position[0], position[1], position[2], 2 * SIZE),
                naturalV(face, position[0] + (uAxis == 0 || vAxis == 0 ? 1 : 0),
                        position[1] + (uAxis == 1 || vAxis == 1 ? 1 : 0),
                        position[2] + (uAxis == 2 || vAxis == 2 ? 1 : 0), 2 * SIZE));
        int layer = compactLayers[maskCell * 4 + (Math.floorMod(nv, 2) << 1 | Math.floorMod(nu, 2))];
        MaterialPass pass = passFor(id, layer);
        boolean reversed = axis == 2 ? !positive : positive;
        int[] order = reversed ? REVERSED : NATURAL;
        int range = ChunkMesh.range(pass, face);
        int[] bucket = ensureBucket(range);
        int cursor = bucketWords[range];
        for (int index = 0; index < 4; index++) {
            int corner = order[(index + ROTATE[face]) & 3];
            // The vertex's place on the face, in halves of the block: 0, 1 or 2.
            int fu = pu + CORNER_U[corner];
            int fv = pv + CORNER_V[corner];
            position[axis] = facePlane * 2;
            position[uAxis] = u * 2 + fu;
            position[vAxis] = v * 2 + fv;
            float occlusion = 0f;
            float sky = 0f;
            float block = 0f;
            for (int sample = 0; sample < 4; sample++) {
                float weight = (CORNER_U[sample] == 1 ? fu : 2 - fu) * (CORNER_V[sample] == 1 ? fv : 2 - fv) * 0.25F;
                occlusion += OCCLUSION[cornerPattern[maskCell * 4 + sample]] * weight;
                int lit = cornerLight[maskCell * 4 + sample];
                sky += (lit >> 8 & 255) * weight;
                block += (lit & 255) * weight;
            }
            int nu2 = naturalU(face, position[0], position[1], position[2], 2 * SIZE);
            int nv2 = naturalV(face, position[0], position[1], position[2], 2 * SIZE);
            int tu = orient(orient, nu2, nv2, 2 * SIZE, true);
            int tv = orient(orient, nu2, nv2, 2 * SIZE, false);
            cursor = vertex(bucket, cursor, position[0] * (ChunkMesh.POSITION_SCALE / 2),
                    position[1] * (ChunkMesh.POSITION_SCALE / 2), position[2] * (ChunkMesh.POSITION_SCALE / 2), face,
                    tu * (ChunkMesh.UV_SCALE / 2), tv * (ChunkMesh.UV_SCALE / 2), layer,
                    color(SHADE[face], occlusion, tint), ((int) sky & 255) << 8 | (int) block & 255);
        }
        bucketWords[range] = cursor;
        if (range == ChunkMesh.TRANSLUCENT_RANGE) {
            position[axis] = plane;
            position[uAxis] = u;
            position[vAxis] = v;
            addCell(position[0] | position[1] << 4 | position[2] << 8, face);
        }
    }

    /** The block's pass, with cutout faces whose texture has no transparency moved to the opaque pass. */
    private MaterialPass passFor(int id, int layer) {
        MaterialPass pass = Blocks189.pass(id);
        return pass == MaterialPass.CUTOUT && palette.textureOpaque(layer) ? MaterialPass.OPAQUE : pass;
    }

    /** @param emissive at full block light without occlusion, as OptiFine draws emissive textures */
    private void emitCubeLayer(int face, int axis, int uAxis, int vAxis, int plane, int u, int v, int width,
                               int height, long key, int layer, int orient, int tint, MaterialPass pass,
                               boolean emissive) {
        boolean positive = (face & 1) == 1;
        int facePlane = positive ? plane + 1 : plane;
        // (u, v) corner order winds toward -X, -Y and +Z; reverse it for the opposite faces so every quad is
        // counter-clockwise seen from outside.
        boolean reversed = axis == 2 ? !positive : positive;
        int[] order = reversed ? REVERSED : NATURAL;
        boolean merged = (key & MERGEABLE) != 0;
        int maskCell = v * SIZE + u;
        int range = ChunkMesh.range(pass, face);
        int[] bucket = ensureBucket(range);
        int cursor = bucketWords[range];
        for (int index = 0; index < 4; index++) {
            int corner = order[(index + ROTATE[face]) & 3];
            position[axis] = facePlane;
            position[uAxis] = u + CORNER_U[corner] * width;
            position[vAxis] = v + CORNER_V[corner] * height;
            int pattern = emissive ? 0 : merged ? (int) (key >>> 32) & 15 : cornerPattern[maskCell * 4 + corner];
            int lit = merged ? (int) key & 0xFFFF : cornerLight[maskCell * 4 + corner];
            if (emissive) {
                lit = lit & 0xFF00 | 240;
            }
            int nu = naturalU(face, position[0], position[1], position[2], SIZE);
            int nv = naturalV(face, position[0], position[1], position[2], SIZE);
            int tu = orient(orient, nu, nv, SIZE, true);
            int tv = orient(orient, nu, nv, SIZE, false);
            cursor = vertex(bucket, cursor, position[0] * ChunkMesh.POSITION_SCALE,
                    position[1] * ChunkMesh.POSITION_SCALE, position[2] * ChunkMesh.POSITION_SCALE, face,
                    tu * ChunkMesh.UV_SCALE, tv * ChunkMesh.UV_SCALE, layer, color(SHADE[face], OCCLUSION[pattern], tint),
                    lit);
        }
        bucketWords[range] = cursor;
        if (range == ChunkMesh.TRANSLUCENT_RANGE) {
            position[axis] = plane;
            position[uAxis] = u;
            position[vAxis] = v;
            addCell(position[0] | position[1] << 4 | position[2] << 8, face);
        }
    }

    /**
     * Texture coordinates of a cube face from block positions, before the face's orientation: the mapping a
     * vanilla full-face quad with uv [0, 0, 16, 16] has. {@code k} is the coordinate span (1 for a block,
     * {@link #SIZE} for section-local positions; the sampler wraps, so only the fraction matters).
     */
    static int naturalU(int face, int x, int y, int z, int k) {
        return switch (face) {
            case 0 -> z;
            case 1 -> k - z;
            case 2, 3, 5 -> x;
            default -> k - x;
        };
    }

    static int naturalV(int face, int x, int y, int z, int k) {
        return switch (face) {
            case 2 -> k - z;
            case 3 -> z;
            default -> k - y;
        };
    }

    static int naturalU(int face, int x, int y, int z) {
        return naturalU(face, x, y, z, 1);
    }

    static int naturalV(int face, int x, int y, int z) {
        return naturalV(face, x, y, z, 1);
    }

    /**
     * One of the eight orientations of a square texture: mirror u when bit 2 is set, then rotate a quarter turn
     * (u, v) -> (k - v, u) per step in bits 0-1. Returns the u or v result.
     */
    static int orient(int orientation, int u, int v, int k, boolean wantU) {
        if ((orientation & 4) != 0) {
            u = k - u;
        }
        for (int turn = 0; turn < (orientation & 3); turn++) {
            int rotated = k - v;
            v = u;
            u = rotated;
        }
        return wantU ? u : v;
    }

    /**
     * Packs one vertex: positions section-local in 1/512 block (offset by 8 blocks), texture coordinates in
     * 1/256 tile, light in vanilla's lightmap units (sky8 << 8 | block8).
     */
    private static int vertex(int[] bucket, int cursor, int x, int y, int z, int face, int u, int v, int layer,
                              int color, int lit) {
        int offset = ChunkMesh.POSITION_OFFSET_BLOCKS * ChunkMesh.POSITION_SCALE;
        bucket[cursor] = (x + offset) | (y + offset) << 14 | face << 28;
        bucket[cursor + 1] = (z + offset) | (u & 0x1FFF) << 14 | (layer >> 11) << 27;
        bucket[cursor + 2] = (v & 0x1FFF) | (layer & 0x7FF) << 13 | (lit & 255) << 24;
        bucket[cursor + 3] = color | (lit >> 8 & 255) << 24;
        return cursor + ChunkMesh.INTS_PER_VERTEX;
    }

    // ---- block models ---------------------------------------------------------------------------------------

    private void meshModelsAndLiquids() {
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                int row = BlockSource.index(0, y, z);
                for (int x = 0; x < SIZE; x++) {
                    int index = row + x;
                    int state = ids[index];
                    if (state == 0) {
                        continue;
                    }
                    int cell = x | y << 4 | z << 8;
                    if (onlyCell >= 0 && cell != onlyCell) {
                        continue;
                    }
                    int choice = cellChoice[cell];
                    if (choice >= 0) {
                        int model = palette.choiceModel[choice];
                        if (!palette.modelCube[model]) {
                            meshModel(index, cell, x, y, z, choice, model, palette.entryTint[cellEntry[cell]]);
                        }
                    } else if (Blocks189.liquid(state >> 4)) {
                        meshLiquid(index, cell, x, y, z);
                    }
                }
            }
        }
    }

    private void meshModel(int index, int cell, int x, int y, int z, int choice, int model, int tint) {
        int id = ids[index] >> 4;
        boolean ao = smoothFor(id, model);
        float offsetX = 0;
        float offsetY = 0;
        float offsetZ = 0;
        int offsetType = Blocks189.offset(id);
        if (offsetType != 0) {
            // Smooth lighting seeds with the full position random; vanilla's flat path hashes x and z only.
            long hash = ao ? positionHash(originX + x, originY + y, originZ + z)
                    : positionHash(originX + x, 0, originZ + z);
            offsetX = (float) ((((hash >> 16 & 15L) / 15.0F) - 0.5D) * 0.5D);
            offsetZ = (float) ((((hash >> 24 & 15L) / 15.0F) - 0.5D) * 0.5D);
            if (offsetType == 2) {
                offsetY = (float) ((((hash >> 20 & 15L) / 15.0F) - 1.0D) * 0.2D);
            }
        }
        int start = palette.modelQuadStart[model];
        int end = start + palette.modelQuadCount[model];
        int rules = palette.choiceQuadCtm[choice];
        // Vanilla tests each cull face once per block; cache the six answers lazily (-1 unknown).
        int sideMask = 0;
        int sideKnown = 0;
        boolean fullCube = Blocks189.fullCube(id);
        // Translucent models draw their unculled (inner) quads before the outer, culled ones: from outside, inner
        // geometry such as slime's core lies behind the faces that hide it.
        boolean innerFirst = Blocks189.pass(id) == MaterialPass.TRANSLUCENT;
        for (int step = 0; step < (innerFirst ? 2 * (end - start) : end - start); step++) {
            int quad = start + (innerFirst ? step % (end - start) : step);
            int cullFace = palette.quadCullFace[quad];
            if (innerFirst && (cullFace >= 0) != (step >= end - start)) {
                continue;
            }
            if (cullFace >= 0) {
                if ((sideKnown >> cullFace & 1) == 0) {
                    sideKnown |= 1 << cullFace;
                    if (sideVisible(index, cullFace, model)) {
                        sideMask |= 1 << cullFace;
                    }
                }
                if ((sideMask >> cullFace & 1) == 0) {
                    continue;
                }
            }
            int list = rules < 0 ? -1 : palette.quadCtm[rules + quad - start];
            emitModelQuad(index, cell, x, y, z, quad, cullFace, id, tint, ao, fullCube, offsetX, offsetY, offsetZ, list);
        }
    }

    /**
     * ConnectedTextures.skipConnectedTexture: with connected textures, panes hide the seams of a pane wall. An edge
     * quad ({@code glass_pane_top}) facing the same pane state is not drawn, and neither is a top or bottom face
     * against a pane of the same block (and color) that continues the part of the pane the quad covers.
     */
    private boolean paneSeam(int index, int quad, int face, int id) {
        int neighborIndex = index + offsetOf(face);
        int neighbor = ids[neighborIndex];
        if (palette.quadPaneEdge[quad] && neighbor == ids[index]) {
            return true;
        }
        if (face != BlockModels189.UP && face != BlockModels189.DOWN || neighbor >> 4 != id
                || id == 160 && neighbor != ids[index]) {
            return false;
        }
        float[] positions = palette.quadPositions;
        int p = quad * 12;
        float plane = face == BlockModels189.UP ? 1f : 0f;
        float minX = 1f;
        float maxX = 0f;
        float minZ = 1f;
        float maxZ = 0f;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (positions[p + vertex * 3 + 1] != plane) {
                return false; // isFaceQuad: only quads on the block's face
            }
            minX = Math.min(minX, positions[p + vertex * 3]);
            maxX = Math.max(maxX, positions[p + vertex * 3]);
            minZ = Math.min(minZ, positions[p + vertex * 3 + 2]);
            maxZ = Math.max(maxZ, positions[p + vertex * 3 + 2]);
        }
        int y = neighborIndex / SY % PADDED - HALO;
        int x = neighborIndex % PADDED - HALO;
        int z = neighborIndex / SZ - HALO;
        // Connection bits: north 1, west 2, south 4, east 8.
        int sides = NeighborRules.extra(ids, neighborIndex, originX + x, originY + y, originZ + z);
        float midX = (minX + maxX) / 2f;
        float midZ = (minZ + maxZ) / 2f;
        if (midX < 0.4F) {
            return (sides & 2) != 0;
        }
        if (midX > 0.6F) {
            return (sides & 8) != 0;
        }
        if (midZ < 0.4F) {
            return (sides & 1) != 0;
        }
        return midZ <= 0.6F || (sides & 4) != 0;
    }

    private void emitModelQuad(int index, int cell, int x, int y, int z, int quad, int cullFace, int id, int tint,
                               boolean ao, boolean fullCube, float offsetX, float offsetY, float offsetZ, int list) {
        float[] positions = palette.quadPositions;
        int p = quad * 12;
        int face = palette.quadFace[quad];
        int vanillaFace = CODE_TO_VANILLA[face];
        // Quad bounds and vanilla's two bounds flags.
        float minX = Math.min(Math.min(positions[p], positions[p + 3]), Math.min(positions[p + 6], positions[p + 9]));
        float minY = Math.min(Math.min(positions[p + 1], positions[p + 4]), Math.min(positions[p + 7], positions[p + 10]));
        float minZ = Math.min(Math.min(positions[p + 2], positions[p + 5]), Math.min(positions[p + 8], positions[p + 11]));
        float maxX = Math.max(Math.max(positions[p], positions[p + 3]), Math.max(positions[p + 6], positions[p + 9]));
        float maxY = Math.max(Math.max(positions[p + 1], positions[p + 4]), Math.max(positions[p + 7], positions[p + 10]));
        float maxZ = Math.max(Math.max(positions[p + 2], positions[p + 5]), Math.max(positions[p + 8], positions[p + 11]));
        boolean partial;
        boolean onBoundary;
        switch (vanillaFace) {
            case 0 -> {
                partial = minX >= 1.0E-4F || minZ >= 1.0E-4F || maxX <= 0.9999F || maxZ <= 0.9999F;
                onBoundary = (minY < 1.0E-4F || fullCube) && minY == maxY;
            }
            case 1 -> {
                partial = minX >= 1.0E-4F || minZ >= 1.0E-4F || maxX <= 0.9999F || maxZ <= 0.9999F;
                onBoundary = (maxY > 0.9999F || fullCube) && minY == maxY;
            }
            case 2 -> {
                partial = minX >= 1.0E-4F || minY >= 1.0E-4F || maxX <= 0.9999F || maxY <= 0.9999F;
                onBoundary = (minZ < 1.0E-4F || fullCube) && minZ == maxZ;
            }
            case 3 -> {
                partial = minX >= 1.0E-4F || minY >= 1.0E-4F || maxX <= 0.9999F || maxY <= 0.9999F;
                onBoundary = (maxZ > 0.9999F || fullCube) && minZ == maxZ;
            }
            case 4 -> {
                partial = minY >= 1.0E-4F || minZ >= 1.0E-4F || maxY <= 0.9999F || maxZ <= 0.9999F;
                onBoundary = (minX < 1.0E-4F || fullCube) && minX == maxX;
            }
            default -> {
                partial = minY >= 1.0E-4F || minZ >= 1.0E-4F || maxY <= 0.9999F || maxZ <= 0.9999F;
                onBoundary = (maxX > 0.9999F || fullCube) && minX == maxX;
            }
        }
        if (cullFace < 0 && onBoundary && !partial && Blocks189.opaqueCube(ids[index + VANILLA_OFFSET[vanillaFace]] >> 4)) {
            // A full face flush with an opaque cube faces into it: no camera outside that cube can see it.
            return;
        }
        if (palette.ctm != ConnectedTextures.NONE && Blocks189.pane(id) && paneSeam(index, quad, face, id)) {
            return;
        }
        int shade = palette.quadShade[quad] & 0xFFFF;
        int quadTint = palette.quadTinted[quad] ? tint : BlockPalette.NO_TINT;
        if (ao) {
            quadBounds[4] = minX;
            quadBounds[5] = maxX;
            quadBounds[0] = minY;
            quadBounds[1] = maxY;
            quadBounds[2] = minZ;
            quadBounds[3] = maxZ;
            for (int bound = 0; bound < 6; bound++) {
                quadBounds[bound + 6] = 1.0F - quadBounds[bound];
            }
            smoothVertices(index, vanillaFace, onBoundary, partial);
        } else {
            int lit;
            if (cullFace >= 0) {
                lit = bright[index + offsetOf(cullFace)];
            } else {
                lit = onBoundary ? bright[index + VANILLA_OFFSET[vanillaFace]] : bright[index];
            }
            Arrays.fill(vertexBrightness, lit);
            Arrays.fill(vertexMultiplier, 1.0F);
        }
        int layer = palette.quadLayer[quad];
        if (list >= 0) {
            layer = connectedQuad(list, index, face, cell, layer);
        }
        MaterialPass pass = passFor(id, layer);
        boolean aligned = palette.quadFace[quad] == face && planar(positions, p, face >> 1);
        emitModelLayer(cell, x, y, z, quad, face, aligned, layer, pass, shade, quadTint, ao, false, offsetX, offsetY,
                offsetZ);
        int glow = palette.emissiveLayer[layer];
        if (glow >= 0) {
            emitModelLayer(cell, x, y, z, quad, face, aligned, glow, emissivePass(pass), shade, quadTint, ao, true,
                    offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Writes a model quad with the lighting {@link #emitModelQuad} computed; {@code emissive} draws it at full block
     * light without occlusion (BlockModelRenderer's setMaxBlockLight).
     */
    private void emitModelLayer(int cell, int x, int y, int z, int quad, int face, boolean aligned, int layer,
                                MaterialPass pass, int shade, int quadTint, boolean ao, boolean emissive, float offsetX,
                                float offsetY, float offsetZ) {
        float[] positions = palette.quadPositions;
        int p = quad * 12;
        int range = ChunkMesh.range(pass, aligned ? face : ChunkMesh.ANY_DIRECTION);
        int[] bucket = ensureBucket(range);
        int cursor = bucketWords[range];
        float[] uvs = palette.quadUvs;
        int u = quad * 8;
        for (int vertex = 0; vertex < 4; vertex++) {
            float px = positions[p + vertex * 3] + x + offsetX;
            float py = positions[p + vertex * 3 + 1] + y + offsetY;
            float pz = positions[p + vertex * 3 + 2] + z + offsetZ;
            int color = ao && !emissive ? color(shade, vertexMultiplier[vertex], quadTint)
                    : flatColor(shade, quadTint);
            int lit = lightWord(vertexBrightness[vertex]);
            if (emissive) {
                lit = lit & 0xFF00 | 240;
            }
            cursor = vertex(bucket, cursor, Math.round(px * ChunkMesh.POSITION_SCALE),
                    Math.round(py * ChunkMesh.POSITION_SCALE), Math.round(pz * ChunkMesh.POSITION_SCALE), face,
                    Math.round((uvs[u + vertex * 2] + TEXTURE_OFFSET_TILES) * ChunkMesh.UV_SCALE),
                    Math.round((uvs[u + vertex * 2 + 1] + TEXTURE_OFFSET_TILES) * ChunkMesh.UV_SCALE), layer, color,
                    lit);
        }
        bucketWords[range] = cursor;
        if (range == ChunkMesh.TRANSLUCENT_RANGE) {
            addCell(cell, aligned ? face : 7);
        }
    }

    /** Untinted flat quads keep the baked shade; tinted ones multiply it, as vanilla's color multiplier does. */
    private static int flatColor(int shade, int tint) {
        if (tint == BlockPalette.NO_TINT) {
            return rgb(shade, shade, shade);
        }
        return rgb((int) (shade * ((tint >> 16 & 255) / 255.0F)), (int) (shade * ((tint >> 8 & 255) / 255.0F)),
                (int) (shade * ((tint & 255) / 255.0F)));
    }

    private static boolean planar(float[] positions, int p, int axis) {
        float value = positions[p + axis];
        return positions[p + 3 + axis] == value && positions[p + 6 + axis] == value && positions[p + 9 + axis] == value;
    }

    /** Whether {@link #leaves} drops the face of a leaf block at {@code index} toward its neighbor. */
    private boolean leafHidden(int id, int index, int face) {
        if (leaves == LeafCulling.OFF || !leaf(id)) {
            return false;
        }
        int neighbor = index + offsetOf(face);
        if (!leaf(ids[neighbor] >> 4)) {
            return false;
        }
        // Cells of the section lie two blocks inside the halo, so the block beyond the neighbor is always present.
        return leaves == LeafCulling.ALL || ids[neighbor + offsetOf(face)] >> 4 != 0;
    }

    private static boolean leaf(int id) {
        return id == 18 || id == 161;
    }

    private static int offsetOf(int face) {
        return (face & 1) == 1 ? STRIDE[face >> 1] : -STRIDE[face >> 1];
    }

    /** AmbientOcclusionFace.updateVertexBrightness, writing per vertex (vanilla vertex order) multiplier and light. */
    private void smoothVertices(int index, int face, boolean onBoundary, boolean partial) {
        int normal = VANILLA_OFFSET[face];
        int at = onBoundary ? index + normal : index;
        int[] corners = NEIGHBORS[face];
        int p1 = at + VANILLA_OFFSET[corners[0]];
        int p2 = at + VANILLA_OFFSET[corners[1]];
        int p3 = at + VANILLA_OFFSET[corners[2]];
        int p4 = at + VANILLA_OFFSET[corners[3]];
        int i = bright[p1];
        int j = bright[p2];
        int k = bright[p3];
        int l = bright[p4];
        float f = ao(p1);
        float f1 = ao(p2);
        float f2 = ao(p3);
        float f3 = ao(p4);
        boolean flag = translucent(p1 + normal);
        boolean flag1 = translucent(p2 + normal);
        boolean flag2 = translucent(p3 + normal);
        boolean flag3 = translucent(p4 + normal);
        float f4;
        int i1;
        if (!flag2 && !flag) {
            f4 = f;
            i1 = i;
        } else {
            int corner = p1 + VANILLA_OFFSET[corners[2]];
            f4 = ao(corner);
            i1 = bright[corner];
        }
        float f26;
        int j1;
        if (!flag3 && !flag) {
            f26 = f;
            j1 = i;
        } else {
            int corner = p1 + VANILLA_OFFSET[corners[3]];
            f26 = ao(corner);
            j1 = bright[corner];
        }
        float f27;
        int k1;
        if (!flag2 && !flag1) {
            f27 = f1;
            k1 = j;
        } else {
            int corner = p2 + VANILLA_OFFSET[corners[2]];
            f27 = ao(corner);
            k1 = bright[corner];
        }
        float f28;
        int l1;
        if (!flag3 && !flag1) {
            f28 = f1;
            l1 = j;
        } else {
            int corner = p2 + VANILLA_OFFSET[corners[3]];
            f28 = ao(corner);
            l1 = bright[corner];
        }
        int i3 = bright[index];
        if (onBoundary || !Blocks189.opaqueCube(ids[index + normal] >> 4)) {
            i3 = bright[index + normal];
        }
        float f5 = onBoundary ? ao(at) : ao(index);
        int[] t = TRANSLATION[face];
        float c0 = (f3 + f + f26 + f5) * 0.25F;
        float c1 = (f2 + f + f4 + f5) * 0.25F;
        float c2 = (f2 + f1 + f27 + f5) * 0.25F;
        float c3 = (f3 + f1 + f28 + f5) * 0.25F;
        int b0 = aoBrightness(l, i, j1, i3);
        int b1 = aoBrightness(k, i, i1, i3);
        int b2 = aoBrightness(k, j, k1, i3);
        int b3 = aoBrightness(l, j, l1, i3);
        if (partial && WEIGHTS[face] != null) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int[] w = WEIGHTS[face][vertex];
                float w0 = quadBounds[w[0]] * quadBounds[w[1]];
                float w1 = quadBounds[w[2]] * quadBounds[w[3]];
                float w2 = quadBounds[w[4]] * quadBounds[w[5]];
                float w3 = quadBounds[w[6]] * quadBounds[w[7]];
                vertexMultiplier[t[vertex]] = c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3;
                vertexBrightness[t[vertex]] = weighted(b0, b1, b2, b3, w0, w1, w2, w3);
            }
        } else {
            vertexBrightness[t[0]] = b0;
            vertexBrightness[t[1]] = b1;
            vertexBrightness[t[2]] = b2;
            vertexBrightness[t[3]] = b3;
            vertexMultiplier[t[0]] = c0;
            vertexMultiplier[t[1]] = c1;
            vertexMultiplier[t[2]] = c2;
            vertexMultiplier[t[3]] = c3;
        }
    }

    /** AmbientOcclusionFace.getVertexBrightness. */
    private static int weighted(int a, int b, int c, int d, float wa, float wb, float wc, float wd) {
        int sky = (int) ((a >> 16 & 255) * wa + (b >> 16 & 255) * wb + (c >> 16 & 255) * wc + (d >> 16 & 255) * wd) & 255;
        int block = (int) ((a & 255) * wa + (b & 255) * wb + (c & 255) * wc + (d & 255) * wd) & 255;
        return sky << 16 | block;
    }

    private float ao(int index) {
        return Blocks189.normalCube(ids[index] >> 4) ? 0.2F : 1.0F;
    }

    private boolean translucent(int index) {
        return Blocks189.translucent(ids[index] >> 4);
    }

    /** Vanilla's per-position random (MathHelper.getPositionRandom); plants use it for their offset. */
    static long positionHash(int x, int y, int z) {
        long hash = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        return hash * hash * 42317861L + hash * 11L;
    }

    // ---- liquids ----------------------------------------------------------------------------------------------

    /** BlockFluidRenderer.renderFluid. */
    private void meshLiquid(int index, int cell, int x, int y, int z) {
        int state = ids[index];
        int id = state >> 4;
        boolean water = id == 8 || id == 9;
        boolean up = liquidSide(index, index + SY, BlockModels189.UP);
        boolean down = liquidSide(index, index - SY, BlockModels189.DOWN);
        boolean north = liquidSide(index, index - SZ, BlockModels189.NORTH);
        boolean south = liquidSide(index, index + SZ, BlockModels189.SOUTH);
        boolean west = liquidSide(index, index - SX, BlockModels189.WEST);
        boolean east = liquidSide(index, index + SX, BlockModels189.EAST);
        if (!up && !down && !north && !south && !west && !east) {
            return;
        }
        int still = water ? palette.waterStill : palette.lavaStill;
        int flow = water ? palette.waterFlow : palette.lavaFlow;
        MaterialPass pass = water ? MaterialPass.TRANSLUCENT : MaterialPass.OPAQUE;
        int range = ChunkMesh.range(pass, ChunkMesh.ANY_DIRECTION);
        float h00 = fluidHeight(index, water);
        float h01 = fluidHeight(index + SZ, water);
        float h11 = fluidHeight(index + SX + SZ, water);
        float h10 = fluidHeight(index + SX, water);
        float inset = 0.001F;
        if (up) {
            double flowAngle = flowDirection(index, water);
            int sprite = flowAngle > -999.0D ? flow : still;
            h00 -= inset;
            h01 -= inset;
            h11 -= inset;
            h10 -= inset;
            float u0;
            float v0;
            float u1;
            float v1;
            float u2;
            float v2;
            float u3;
            float v3;
            if (flowAngle < -999.0D) {
                u0 = 0;
                v0 = 0;
                u1 = 0;
                v1 = 1;
                u2 = 1;
                v2 = 1;
                u3 = 1;
                v3 = 0;
            } else {
                float sin = VanillaMath.sin((float) flowAngle) * 0.25F;
                float cos = VanillaMath.cos((float) flowAngle) * 0.25F;
                u0 = (8.0F + (-cos - sin) * 16.0F) / 16.0F;
                v0 = (8.0F + (-cos + sin) * 16.0F) / 16.0F;
                u1 = (8.0F + (-cos + sin) * 16.0F) / 16.0F;
                v1 = (8.0F + (cos + sin) * 16.0F) / 16.0F;
                u2 = (8.0F + (cos + sin) * 16.0F) / 16.0F;
                v2 = (8.0F + (cos - sin) * 16.0F) / 16.0F;
                u3 = (8.0F + (cos - sin) * 16.0F) / 16.0F;
                v3 = (8.0F + (-cos - sin) * 16.0F) / 16.0F;
            }
            int lit = lightWord(liquidBrightness(index));
            int color = water ? color(255, 1.0F, palette.waterTint) : rgb(255, 255, 255);
            boolean flat = h00 == h01 && h01 == h11 && h11 == h10;
            int direction = flat ? BlockModels189.UP : 7;
            liquidQuad(range, cell, direction, sprite, color, lit,
                    x, y + h00, z, u0, v0,
                    x, y + h01, z + 1, u1, v1,
                    x + 1, y + h11, z + 1, u2, v2,
                    x + 1, y + h10, z, u3, v3);
            if (liquidUnderside(index + SY, water)) {
                liquidQuad(range, cell, flat ? BlockModels189.DOWN : 7, sprite, color, lit,
                        x, y + h00, z, u0, v0,
                        x + 1, y + h10, z, u3, v3,
                        x + 1, y + h11, z + 1, u2, v2,
                        x, y + h01, z + 1, u1, v1);
            }
        }
        if (down) {
            int lit = lightWord(liquidBrightness(index - SY));
            int shade = 127;
            liquidQuad(range, cell, BlockModels189.DOWN, still,
                    water ? color(shade, 1.0F, palette.waterTint) : rgb(shade, shade, shade), lit,
                    x, y, z + 1, 0, 1,
                    x, y, z, 0, 0,
                    x + 1, y, z, 1, 0,
                    x + 1, y, z + 1, 1, 1);
        }
        for (int side = 0; side < 4; side++) {
            boolean visible = switch (side) {
                case 0 -> north;
                case 1 -> south;
                case 2 -> west;
                default -> east;
            };
            if (!visible) {
                continue;
            }
            float ha;
            float hb;
            float x0;
            float z0;
            float x1;
            float z1;
            int neighbor;
            int face;
            switch (side) {
                case 0 -> {
                    ha = h00;
                    hb = h10;
                    x0 = x;
                    x1 = x + 1;
                    z0 = z + inset;
                    z1 = z + inset;
                    neighbor = index - SZ;
                    face = BlockModels189.NORTH;
                }
                case 1 -> {
                    ha = h11;
                    hb = h01;
                    x0 = x + 1;
                    x1 = x;
                    z0 = z + 1 - inset;
                    z1 = z + 1 - inset;
                    neighbor = index + SZ;
                    face = BlockModels189.SOUTH;
                }
                case 2 -> {
                    ha = h01;
                    hb = h00;
                    x0 = x + inset;
                    x1 = x + inset;
                    z0 = z + 1;
                    z1 = z;
                    neighbor = index - SX;
                    face = BlockModels189.WEST;
                }
                default -> {
                    ha = h10;
                    hb = h11;
                    x0 = x + 1 - inset;
                    x1 = x + 1 - inset;
                    z0 = z;
                    z1 = z + 1;
                    neighbor = index + SX;
                    face = BlockModels189.EAST;
                }
            }
            float ua = 0.0F;
            float ub = 0.5F;
            float va = (1.0F - ha) * 16.0F * 0.5F / 16.0F;
            float vb = (1.0F - hb) * 16.0F * 0.5F / 16.0F;
            float vBottom = 0.5F;
            int lit = lightWord(liquidBrightness(neighbor));
            int shade = side < 2 ? 204 : 153;
            int color = water ? color(shade, 1.0F, palette.waterTint) : rgb(shade, shade, shade);
            liquidQuad(range, cell, face, flow, color, lit,
                    x0, y + ha, z0, ua, va,
                    x1, y + hb, z1, ub, vb,
                    x1, y, z1, ub, vBottom,
                    x0, y, z0, ua, vBottom);
            liquidQuad(range, cell, face ^ 1, flow, color, lit,
                    x0, y, z0, ua, vBottom,
                    x1, y, z1, ub, vBottom,
                    x1, y + hb, z1, ub, vb,
                    x0, y + ha, z0, ua, va);
        }
    }

    private void liquidQuad(int range, int cell, int direction, int layer, int color, int lit,
                            float x0, float y0, float z0, float u0, float v0,
                            float x1, float y1, float z1, float u1, float v1,
                            float x2, float y2, float z2, float u2, float v2,
                            float x3, float y3, float z3, float u3, float v3) {
        int[] bucket = ensureBucket(range);
        int cursor = bucketWords[range];
        cursor = liquidVertex(bucket, cursor, x0, y0, z0, u0, v0, layer, color, lit);
        cursor = liquidVertex(bucket, cursor, x1, y1, z1, u1, v1, layer, color, lit);
        cursor = liquidVertex(bucket, cursor, x2, y2, z2, u2, v2, layer, color, lit);
        cursor = liquidVertex(bucket, cursor, x3, y3, z3, u3, v3, layer, color, lit);
        bucketWords[range] = cursor;
        if (range == ChunkMesh.TRANSLUCENT_RANGE) {
            addCell(cell, direction);
        }
    }

    private static int liquidVertex(int[] bucket, int cursor, float x, float y, float z, float u, float v, int layer,
                                    int color, int lit) {
        return vertex(bucket, cursor, Math.round(x * ChunkMesh.POSITION_SCALE), Math.round(y * ChunkMesh.POSITION_SCALE),
                Math.round(z * ChunkMesh.POSITION_SCALE), 6, Math.round((u + TEXTURE_OFFSET_TILES) * ChunkMesh.UV_SCALE),
                Math.round((v + TEXTURE_OFFSET_TILES) * ChunkMesh.UV_SCALE), layer, color, lit);
    }

    /** BlockLiquid.shouldSideBeRendered: never against the same liquid; the top always; else the default rule. */
    private boolean liquidSide(int index, int neighborIndex, int face) {
        int neighborId = ids[neighborIndex] >> 4;
        if (Blocks189.material(neighborId) == Blocks189.material(ids[index] >> 4)) {
            return false;
        }
        return face == BlockModels189.UP || !Blocks189.opaqueCube(neighborId);
    }

    /** BlockLiquid.shouldRenderSides: some cell around the one above is neither this liquid nor a full block. */
    private boolean liquidUnderside(int above, boolean water) {
        Blocks189.Material material = water ? Blocks189.Material.WATER : Blocks189.Material.LAVA;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int id = ids[above + dx * SX + dz * SZ] >> 4;
                if (Blocks189.material(id) != material && !Blocks189.fullBlock(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** BlockLiquid.getMixedBrightnessForBlock: the brighter of the cell and the one above, without emission. */
    private int liquidBrightness(int index) {
        int a = raw[index];
        int b = raw[index + SY];
        return Math.max(a & 255, b & 255) | Math.max(a >> 16 & 255, b >> 16 & 255) << 16;
    }

    /** BlockFluidRenderer.getFluidHeight for the corner at the cell's minimum x and z. */
    private float fluidHeight(int index, boolean water) {
        Blocks189.Material material = water ? Blocks189.Material.WATER : Blocks189.Material.LAVA;
        int count = 0;
        float sum = 0.0F;
        for (int corner = 0; corner < 4; corner++) {
            int at = index - (corner & 1) * SX - (corner >> 1 & 1) * SZ;
            if (Blocks189.material(ids[at + SY] >> 4) == material) {
                return 1.0F;
            }
            int state = ids[at];
            Blocks189.Material other = Blocks189.material(state >> 4);
            if (other != material) {
                if (!other.solid()) {
                    sum++;
                    count++;
                }
            } else {
                int level = state & 15;
                if (level >= 8 || level == 0) {
                    sum += heightPercent(level) * 10.0F;
                    count += 10;
                }
                sum += heightPercent(level);
                count++;
            }
        }
        return 1.0F - sum / count;
    }

    private static float heightPercent(int level) {
        if (level >= 8) {
            level = 0;
        }
        return (level + 1) / 9.0F;
    }

    /** BlockLiquid.getFlowDirection: the flow angle, or -1000 when the liquid does not flow. */
    private double flowDirection(int index, boolean water) {
        Blocks189.Material material = water ? Blocks189.Material.WATER : Blocks189.Material.LAVA;
        double vx = 0;
        double vy = 0;
        double vz = 0;
        int decay = effectiveDecay(index, material);
        int[] offsets = {-SZ, SX, SZ, -SX};
        int[] dxs = {0, 1, 0, -1};
        int[] dzs = {-1, 0, 1, 0};
        for (int side = 0; side < 4; side++) {
            int neighbor = index + offsets[side];
            int other = effectiveDecay(neighbor, material);
            if (other < 0) {
                if (!Blocks189.material(ids[neighbor] >> 4).blocksMovement()) {
                    other = effectiveDecay(neighbor - SY, material);
                    if (other >= 0) {
                        int k = other - (decay - 8);
                        vx += dxs[side] * k;
                        vz += dzs[side] * k;
                    }
                }
            } else {
                int l = other - decay;
                vx += dxs[side] * l;
                vz += dzs[side] * l;
            }
        }
        if ((ids[index] & 15) >= 8) {
            for (int side = 0; side < 4; side++) {
                int neighbor = index + offsets[side];
                if (liquidSolid(neighbor, material) || liquidSolid(neighbor + SY, material)) {
                    double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (length < 1.0E-4D) {
                        vx = 0;
                        vy = 0;
                        vz = 0;
                    } else {
                        vx /= length;
                        vy /= length;
                        vz /= length;
                    }
                    vy -= 6.0D;
                    break;
                }
            }
        }
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length < 1.0E-4D) {
            vx = 0;
            vz = 0;
        } else {
            vx /= length;
            vz /= length;
        }
        return vx == 0.0D && vz == 0.0D ? -1000.0D : VanillaMath.atan2(vz, vx) - Math.PI / 2D;
    }

    private int effectiveDecay(int index, Blocks189.Material material) {
        int state = ids[index];
        if (Blocks189.material(state >> 4) != material) {
            return -1;
        }
        int level = state & 15;
        return level >= 8 ? 0 : level;
    }

    /** BlockLiquid.isBlockSolid for a horizontal side. */
    private boolean liquidSolid(int index, Blocks189.Material material) {
        Blocks189.Material other = Blocks189.material(ids[index] >> 4);
        return other != material && other != Blocks189.Material.ICE && other.solid();
    }

    // ---- occlusion graph ----------------------------------------------------------------------------------

    /**
     * Flood-fills the section's non-occluding cells. Every pair of section faces touched by one connected
     * region can see each other; the result is the {@link ChunkMesh#visibility()} bit matrix.
     */
    private long visibility() {
        int occluding = 0;
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                int row = BlockSource.index(0, y, z);
                for (int x = 0; x < SIZE; x++) {
                    if (Blocks189.opaqueCube(ids[row + x] >> 4)) {
                        occluding++;
                    }
                }
            }
        }
        if (occluding == 0) {
            return ChunkMesh.ALL_CONNECTED;
        }
        if (occluding == SIZE * SIZE * SIZE) {
            return 0;
        }
        Arrays.fill(visited, 0L);
        long result = 0;
        for (int start = 0; start < SIZE * SIZE * SIZE; start++) {
            if (isVisited(start) || closed(start)) {
                continue;
            }
            int faces = fill(start);
            for (int a = 0; a < 6; a++) {
                if ((faces >> a & 1) == 0) {
                    continue;
                }
                for (int b = 0; b < 6; b++) {
                    if ((faces >> b & 1) != 0) {
                        result |= 1L << (a * 6 + b);
                    }
                }
            }
            if (result == ChunkMesh.ALL_CONNECTED) {
                break;
            }
        }
        return result;
    }

    /** Breadth-first fill from a local cell index (x | y << 4 | z << 8); returns the section faces it reaches. */
    private int fill(int start) {
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        markVisited(start);
        int faces = 0;
        while (head < tail) {
            int cell = queue[head++];
            int x = cell & 15;
            int y = cell >> 4 & 15;
            int z = cell >> 8;
            faces |= x == 0 ? 1 : x == 15 ? 2 : 0;
            faces |= y == 0 ? 4 : y == 15 ? 8 : 0;
            faces |= z == 0 ? 16 : z == 15 ? 32 : 0;
            if (x > 0) tail = visit(cell - 1, tail);
            if (x < 15) tail = visit(cell + 1, tail);
            if (y > 0) tail = visit(cell - 16, tail);
            if (y < 15) tail = visit(cell + 16, tail);
            if (z > 0) tail = visit(cell - 256, tail);
            if (z < 15) tail = visit(cell + 256, tail);
        }
        return faces;
    }

    private int visit(int cell, int tail) {
        if (!isVisited(cell) && !closed(cell)) {
            markVisited(cell);
            queue[tail++] = cell;
        }
        return tail;
    }

    private boolean closed(int cell) {
        return Blocks189.opaqueCube(ids[BlockSource.index(cell & 15, cell >> 4 & 15, cell >> 8)] >> 4);
    }

    private boolean isVisited(int cell) {
        return (visited[cell >> 6] >>> cell & 1L) != 0;
    }

    private void markVisited(int cell) {
        visited[cell >> 6] |= 1L << cell;
    }

    // ---- translucency ---------------------------------------------------------------------------------------

    /**
     * Whether every translucent quad lies on planes of one axis (open water's surface and its underside, a wall
     * of glass). A ray from the camera meets only the faces of one direction along that axis (the others face
     * away and are culled), and faces of one direction never overlap out of order once drawn farthest plane
     * first, so the section keeps one static order and needs no camera-dependent sorting.
     */
    private boolean staticTranslucent() {
        if (cellCount == 0) {
            return true;
        }
        int axis = translucentDirection[0] >> 1;
        if (translucentDirection[0] > 5) {
            return false;
        }
        for (int quad = 1; quad < cellCount; quad++) {
            if (translucentDirection[quad] > 5 || translucentDirection[quad] >> 1 != axis) {
                return false;
            }
        }
        return true;
    }

    /**
     * For a static section, orders translucent quads by their plane: faces toward the positive side first, in
     * ascending planes (higher planes are nearer to a camera on that side), then the others descending. Stable,
     * so quads in a cell keep their order.
     */
    private void orderTranslucent() {
        if (!staticTranslucent() || cellCount < 2) {
            return;
        }
        int axis = translucentDirection[0] >> 1;
        int shift = axis == 0 ? 0 : axis == 1 ? 4 : 8;
        int[] bucket = buckets[ChunkMesh.TRANSLUCENT_RANGE];
        int[] sorted = new int[bucketWords[ChunkMesh.TRANSLUCENT_RANGE]];
        int cursor = 0;
        for (int side = 1; side >= 0; side--) {
            for (int step = 0; step < SIZE; step++) {
                int plane = side == 1 ? step : SIZE - 1 - step;
                for (int quad = 0; quad < cellCount; quad++) {
                    if ((translucentDirection[quad] & 1) == side && (cells[quad] >> shift & 15) == plane) {
                        System.arraycopy(bucket, quad * ChunkMesh.INTS_PER_QUAD, sorted, cursor,
                                ChunkMesh.INTS_PER_QUAD);
                        cursor += ChunkMesh.INTS_PER_QUAD;
                    }
                }
            }
        }
        System.arraycopy(sorted, 0, bucket, 0, cursor);
    }

    // ---- buffers ------------------------------------------------------------------------------------------

    private int[] ensureBucket(int range) {
        int[] bucket = buckets[range];
        if (bucketWords[range] + ChunkMesh.INTS_PER_QUAD > bucket.length) {
            bucket = Arrays.copyOf(bucket, bucket.length * 2);
            buckets[range] = bucket;
        }
        return bucket;
    }

    private void addCell(int cell, int direction) {
        if (cellCount == cells.length) {
            cells = Arrays.copyOf(cells, cells.length * 2);
            translucentDirection = Arrays.copyOf(translucentDirection, translucentDirection.length * 2);
        }
        translucentDirection[cellCount] = (byte) direction;
        cells[cellCount++] = (short) cell;
    }
}
