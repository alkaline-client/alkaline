package alkaline.clientapp;

import alkaline.game.worldgen.NoiseGeneratorPerlin;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.VanillaBlocks;
import java.util.Random;

/**
 * Scenery for the Overworld menu background: rolling grassland with tall grass and flowers, beaches, lakes, a
 * few snowy peaks, and oak trees, from the ported 1.8.9 simplex octave noise. It is a backdrop generator, not vanilla world
 * generation. Thread-safe: every {@link #fill} works on its own scratch arrays.
 */
final class MenuTerrain implements BlockSource {
    private static final int SEA_LEVEL = 62;
    private static final int SNOW_LINE = 90;
    /** Columns sampled around a section: the halo plus room for tree canopies reaching in from neighbors. */
    private static final int MARGIN = BlockSource.HALO + 2;
    private static final int PADDED = BlockSource.PADDED;
    /** Highest a tree reaches above its ground: trunk up to 6, then two layers of leaves. */
    private static final int TREE_REACH = 8;
    private static final int SPAN = 16 + 2 * MARGIN;

    private final NoiseGeneratorPerlin continents;
    private final NoiseGeneratorPerlin hills;
    private final NoiseGeneratorPerlin detail;
    private final long seed;

    MenuTerrain(long seed) {
        this.seed = seed;
        Random random = new Random(seed);
        continents = new NoiseGeneratorPerlin(random, 4);
        hills = new NoiseGeneratorPerlin(random, 4);
        detail = new NoiseGeneratorPerlin(random, 2);
    }

    @Override
    public int minSectionY() {
        return 2;
    }

    @Override
    public int maxSectionY() {
        return 7;
    }

    /** Surface height: broad continents with hills that sharpen inland. */
    int height(int x, int z) {
        double base = continents.func_151601_a(x / 360.0, z / 360.0);
        double ridge = hills.func_151601_a(x / 120.0, z / 120.0);
        double fine = detail.func_151601_a(x / 24.0, z / 24.0);
        double inland = Math.max(0.0, base + 1.0) / 5.0;
        return (int) Math.round(SEA_LEVEL + 6 + base * 2.4 + ridge * (0.8 + inland * 2.2) + fine * 0.6);
    }

    /** A camera height that clears the highest terrain on a circle around the origin. */
    double eyeHeight(double radius) {
        int highest = SEA_LEVEL;
        for (int step = 0; step < 64; step++) {
            double angle = step / 64.0 * Math.PI * 2;
            for (double r = radius - 24; r <= radius + 24; r += 8) {
                highest = Math.max(highest, height((int) (Math.cos(angle) * r), (int) (Math.sin(angle) * r)));
            }
        }
        return highest + 16.0;
    }

    @Override
    public void fill(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light) {
        int originX = sectionX * 16;
        int originY = sectionY * 16;
        int originZ = sectionZ * 16;
        int[] heights = new int[SPAN * SPAN];
        int highest = 0;
        for (int dz = 0; dz < SPAN; dz++) {
            for (int dx = 0; dx < SPAN; dx++) {
                int height = height(originX - MARGIN + dx, originZ - MARGIN + dz);
                heights[dz * SPAN + dx] = height;
                highest = Math.max(highest, height);
            }
        }
        // Sky light comes down each column from above the tallest tree, so blocks are generated for the whole
        // column above the section, not only its halo. Light depends on the column alone, so neighboring
        // sections compute identical halo values and lighting has no seams.
        int bottom = originY - BlockSource.HALO;
        int top = Math.max(originY + 15 + BlockSource.HALO, highest + TREE_REACH);
        int columnHeight = top - bottom + 1;
        char[] column = new char[PADDED * PADDED * columnHeight];
        for (int z = -BlockSource.HALO; z < 16 + BlockSource.HALO; z++) {
            for (int x = -BlockSource.HALO; x < 16 + BlockSource.HALO; x++) {
                int surface = heights[(z + MARGIN) * SPAN + x + MARGIN];
                boolean beach = surface <= SEA_LEVEL + 1;
                boolean peak = surface >= SNOW_LINE;
                for (int worldY = bottom; worldY <= top; worldY++) {
                    char block;
                    if (worldY <= 0) {
                        block = VanillaBlocks.BEDROCK;
                    } else if (worldY < surface - 4) {
                        block = VanillaBlocks.STONE;
                    } else if (worldY < surface) {
                        block = beach ? (worldY < surface - 2 ? VanillaBlocks.SANDSTONE : VanillaBlocks.SAND)
                                : peak ? VanillaBlocks.STONE : VanillaBlocks.DIRT;
                    } else if (worldY == surface) {
                        if (surface < SEA_LEVEL) {
                            block = hash(originX + x, originZ + z, 3) % 5 == 0 ? VanillaBlocks.CLAY
                                    : surface < SEA_LEVEL - 4 ? VanillaBlocks.GRAVEL : VanillaBlocks.SAND;
                        } else {
                            block = beach ? VanillaBlocks.SAND : peak ? VanillaBlocks.SNOW : VanillaBlocks.GRASS;
                        }
                    } else if (worldY <= SEA_LEVEL) {
                        block = VanillaBlocks.WATER;
                    } else if (worldY == surface + 1 && !beach && !peak) {
                        block = plant(originX + x, originZ + z);
                    } else {
                        block = VanillaBlocks.AIR;
                    }
                    column[columnIndex(x, worldY - bottom, z, columnHeight)] = block;
                }
            }
        }
        plantTrees(originX, bottom, originZ, columnHeight, heights, column);
        for (int z = -BlockSource.HALO; z < 16 + BlockSource.HALO; z++) {
            for (int x = -BlockSource.HALO; x < 16 + BlockSource.HALO; x++) {
                int sky = 15;
                for (int level = columnHeight - 1; level >= 0; level--) {
                    char block = column[columnIndex(x, level, z, columnHeight)];
                    sky = Math.max(0, sky - opacity(block));
                    int y = bottom + level - originY;
                    if (y <= 15 + BlockSource.HALO) {
                        int index = BlockSource.index(x, y, z);
                        ids[index] = block;
                        light[index] = (byte) (sky << 4);
                    }
                }
            }
        }
    }

    /** Vanilla light opacity of the blocks this generator places: leaves 1, water 3, solid blocks block all. */
    private static int opacity(char block) {
        return switch (block) {
            case VanillaBlocks.AIR, VanillaBlocks.TALL_GRASS, VanillaBlocks.DANDELION, VanillaBlocks.POPPY -> 0;
            case VanillaBlocks.OAK_LEAVES -> 1;
            case VanillaBlocks.WATER -> 3;
            default -> 15;
        };
    }

    private static int columnIndex(int x, int level, int z, int columnHeight) {
        return ((z + BlockSource.HALO) * PADDED + x + BlockSource.HALO) * columnHeight + level;
    }

    /** Oaks on a jittered 6-block grid over grass, stamped into the padded volume where they reach it. */
    private void plantTrees(int originX, int bottom, int originZ, int columnHeight, int[] heights, char[] column) {
        int fromX = Math.floorDiv(originX - MARGIN, 6);
        int toX = Math.floorDiv(originX + 16 + MARGIN, 6);
        int fromZ = Math.floorDiv(originZ - MARGIN, 6);
        int toZ = Math.floorDiv(originZ + 16 + MARGIN, 6);
        for (int cellZ = fromZ; cellZ <= toZ; cellZ++) {
            for (int cellX = fromX; cellX <= toX; cellX++) {
                int roll = hash(cellX, cellZ, 1);
                if (roll % 100 >= 34) {
                    continue;
                }
                int treeX = cellX * 6 + 1 + roll % 4;
                int treeZ = cellZ * 6 + 1 + (roll >> 8) % 4;
                int localX = treeX - originX + MARGIN;
                int localZ = treeZ - originZ + MARGIN;
                if (localX < 0 || localZ < 0 || localX >= SPAN || localZ >= SPAN) {
                    continue;
                }
                int ground = heights[localZ * SPAN + localX];
                if (ground <= SEA_LEVEL + 1 || ground >= SNOW_LINE - 4) {
                    continue;
                }
                int trunk = 4 + (roll >> 16) % 3;
                int top = ground + trunk;
                for (int y = ground + 1; y <= top; y++) {
                    set(column, columnHeight, treeX - originX, y - bottom, treeZ - originZ, VanillaBlocks.OAK_LOG);
                }
                for (int y = top - 2; y <= top + 1; y++) {
                    int radius = y <= top - 1 ? 2 : 1;
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                            if (corner && (radius == 1 || hash(treeX + dx, treeZ + dz, y) % 2 == 0)) {
                                continue;
                            }
                            int lx = treeX + dx - originX;
                            int level = y - bottom;
                            int lz = treeZ + dz - originZ;
                            if (inside(lx, level, lz, columnHeight)
                                    && column[columnIndex(lx, level, lz, columnHeight)] == VanillaBlocks.AIR) {
                                column[columnIndex(lx, level, lz, columnHeight)] = VanillaBlocks.OAK_LEAVES;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Ground cover on grass: tall grass in loose patches, the odd dandelion or poppy, bare ground elsewhere. */
    private char plant(int x, int z) {
        double patch = detail.func_151601_a(x / 9.0 + 31.7, z / 9.0 - 12.3);
        int roll = hash(x, z, 7) % 1000;
        if (roll < 12) {
            return VanillaBlocks.DANDELION;
        }
        if (roll < 22) {
            return VanillaBlocks.POPPY;
        }
        return roll < (patch > 0.2 ? 420 : 90) ? VanillaBlocks.TALL_GRASS : VanillaBlocks.AIR;
    }

    private static void set(char[] column, int columnHeight, int x, int level, int z, char block) {
        if (inside(x, level, z, columnHeight)) {
            column[columnIndex(x, level, z, columnHeight)] = block;
        }
    }

    private static boolean inside(int x, int level, int z, int columnHeight) {
        return x >= -BlockSource.HALO && x < 16 + BlockSource.HALO && z >= -BlockSource.HALO
                && z < 16 + BlockSource.HALO && level >= 0 && level < columnHeight;
    }

    private int hash(int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (int) (h & 0x7FFFFFFF);
    }
}
