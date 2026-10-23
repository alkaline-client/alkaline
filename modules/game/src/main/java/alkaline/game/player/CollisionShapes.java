package alkaline.game.player;

import alkaline.game.geometry.AxisAlignedBB;
import java.util.List;

/**
 * Collision boxes of Minecraft 1.8.9 blocks (block-local, added at the block position), following each block's
 * {@code addCollisionBoxesToList} / {@code getCollisionBoundingBox} for the blocks players walk on and into.
 * Blocks without collision (plants, liquids, torches, rails, redstone, signs...) add nothing; any other block
 * not listed here is a full cube.
 *
 * <p>Simplified so far: stairs use their straight shape (corner stairs collide as straight ones), panes and
 * iron bars use a centered post with arms, and chests use their single-chest box.
 */
public final class CollisionShapes {
    /** Reads block states around the block being tested (for connections). */
    @FunctionalInterface
    public interface States {
        int state(int x, int y, int z);
    }

    private static final boolean[] PASSABLE = new boolean[4096];

    static {
        int[] ids = {0, 6, 8, 9, 10, 11, 27, 28, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59, 63, 66, 68, 69, 70, 72, 75,
                76, 77, 83, 90, 104, 105, 106, 115, 119, 131, 132, 141, 142, 143, 147, 148, 157, 175, 176, 177, 30};
        for (int id : ids) {
            PASSABLE[id] = true;
        }
    }

    private CollisionShapes() {
    }

    private static final boolean[] NOT_NORMAL = new boolean[4096];

    static {
        // Blocks with partial collision boxes (the cases of add below), plus translucent or power-giving full
        // cubes: glass, leaves, glowstone, ice, TNT, slime, beacon, sea lantern, redstone block, hopper, brewing
        // stand, cauldron, flower pot, skull, anvil.
        int[] ids = {44, 126, 182, 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180, 78, 171, 60, 88, 81,
                92, 26, 116, 120, 151, 178, 93, 94, 149, 150, 111, 54, 146, 130, 65, 96, 167, 64, 71, 193, 194, 195, 196,
                197, 85, 113, 188, 189, 190, 191, 192, 107, 183, 184, 185, 186, 187, 139, 101, 102, 160, 20, 95, 18, 161,
                89, 79, 46, 165, 138, 169, 152, 154, 117, 118, 140, 144, 145};
        for (int id : ids) {
            NOT_NORMAL[id] = true;
        }
    }

    /** Block.isNormalCube: an opaque full cube that gives no power (what pushes the player out of blocks). */
    public static boolean normalCube(int id) {
        return !passable(id) && id >= 0 && id < NOT_NORMAL.length && !NOT_NORMAL[id];
    }

    /** Whether the block has no collision box at all. */
    public static boolean passable(int id) {
        return id < 0 || id >= PASSABLE.length || PASSABLE[id];
    }

    /** Adds the block's boxes (in world coordinates) that intersect {@code area} to {@code out}. */
    public static void add(States world, int x, int y, int z, AxisAlignedBB area, List<AxisAlignedBB> out) {
        int state = world.state(x, y, z);
        int id = state >> 4;
        int meta = state & 15;
        if (passable(id)) {
            return;
        }
        switch (id) {
            case 44, 126, 182 -> box(out, area, x, y, z, 0, (meta & 8) != 0 ? 0.5 : 0, 0, 1,
                    (meta & 8) != 0 ? 1 : 0.5, 1);
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180 -> stairs(out, area, x, y, z, meta);
            case 78 -> {
                int layers = (meta & 7) + 1;
                if (layers > 1) {
                    box(out, area, x, y, z, 0, 0, 0, 1, (layers - 1) * 0.125, 1);
                }
            }
            case 171 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.0625, 1);
            case 60 -> box(out, area, x, y, z, 0, 0, 0, 1, 1, 1);
            case 88 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.875, 1);
            case 81 -> box(out, area, x, y, z, 0.0625, 0, 0.0625, 0.9375, 0.9375, 0.9375);
            case 92 -> {
                double bite = (1 + Math.min(meta, 6) * 2) / 16.0;
                box(out, area, x, y, z, bite, 0, 0.0625, 0.9375, 0.5, 0.9375);
            }
            case 26 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.5625, 1);
            case 116 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.75, 1);
            case 120 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.8125, 1);
            case 151, 178 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.375, 1);
            case 93, 94, 149, 150 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.125, 1);
            case 111 -> box(out, area, x, y, z, 0, 0, 0, 1, 0.015625, 1);
            case 54, 146, 130 -> box(out, area, x, y, z, 0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375);
            case 65 -> ladder(out, area, x, y, z, meta);
            case 96, 167 -> trapdoor(out, area, x, y, z, meta);
            case 64, 71, 193, 194, 195, 196, 197 -> door(world, out, area, x, y, z, state);
            case 85, 113, 188, 189, 190, 191, 192 -> fence(world, out, area, x, y, z, id);
            case 107, 183, 184, 185, 186, 187 -> {
                if ((meta & 4) == 0) {
                    boolean alongX = (meta & 1) == 0;
                    if (alongX) {
                        box(out, area, x, y, z, 0, 0, 0.375, 1, 1.5, 0.625);
                    } else {
                        box(out, area, x, y, z, 0.375, 0, 0, 0.625, 1.5, 1);
                    }
                }
            }
            case 139 -> wall(world, out, area, x, y, z);
            case 101, 102, 160 -> pane(world, out, area, x, y, z);
            default -> box(out, area, x, y, z, 0, 0, 0, 1, 1, 1);
        }
    }

    private static void box(List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z, double minX,
                            double minY, double minZ, double maxX, double maxY, double maxZ) {
        AxisAlignedBB box = new AxisAlignedBB(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
        if (box.intersectsWith(area)) {
            out.add(box);
        }
    }

    /** The bottom (or top) half plus the half toward the stair's facing. */
    private static void stairs(List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z, int meta) {
        boolean top = (meta & 4) != 0;
        box(out, area, x, y, z, 0, top ? 0.5 : 0, 0, 1, top ? 1 : 0.5, 1);
        double low = top ? 0 : 0.5;
        double high = top ? 0.5 : 1;
        switch (meta & 3) {
            case 0 -> box(out, area, x, y, z, 0.5, low, 0, 1, high, 1);   // east
            case 1 -> box(out, area, x, y, z, 0, low, 0, 0.5, high, 1);   // west
            case 2 -> box(out, area, x, y, z, 0, low, 0.5, 1, high, 1);   // south
            default -> box(out, area, x, y, z, 0, low, 0, 1, high, 0.5);  // north
        }
    }

    private static void ladder(List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z, int meta) {
        double t = 0.125;
        switch (meta) {
            case 2 -> box(out, area, x, y, z, 0, 0, 1 - t, 1, 1, 1);  // north-facing, on the south wall
            case 3 -> box(out, area, x, y, z, 0, 0, 0, 1, 1, t);
            case 4 -> box(out, area, x, y, z, 1 - t, 0, 0, 1, 1, 1);
            default -> box(out, area, x, y, z, 0, 0, 0, t, 1, 1);
        }
    }

    private static void trapdoor(List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z, int meta) {
        double t = 0.1875;
        if ((meta & 4) == 0) {
            boolean top = (meta & 8) != 0;
            box(out, area, x, y, z, 0, top ? 1 - t : 0, 0, 1, top ? 1 : t, 1);
            return;
        }
        switch (meta & 3) {
            case 0 -> box(out, area, x, y, z, 0, 0, 1 - t, 1, 1, 1);
            case 1 -> box(out, area, x, y, z, 0, 0, 0, 1, 1, t);
            case 2 -> box(out, area, x, y, z, 1 - t, 0, 0, 1, 1, 1);
            default -> box(out, area, x, y, z, 0, 0, 0, t, 1, 1);
        }
    }

    /** BlockDoor.setBoundBasedOnMeta: a 3/16 slab on the side the door faces, or swung by its hinge when open. */
    private static void door(States world, List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z,
                             int state) {
        int meta = state & 15;
        boolean upper = (meta & 8) != 0;
        int lower = upper ? world.state(x, y - 1, z) & 15 : meta;
        int top = upper ? meta : world.state(x, y + 1, z) & 15;
        int facing = lower & 3; // 0 east, 1 south, 2 west, 3 north (getHorizontal(meta).rotateYCCW)
        boolean open = (lower & 4) != 0;
        boolean right = (top & 1) != 0;
        double t = 0.1875;
        int side = facing;
        if (open) {
            side = right ? (facing + 3) & 3 : (facing + 1) & 3;
        }
        switch (side) {
            case 0 -> box(out, area, x, y, z, 0, 0, 0, t, 1, 1);
            case 1 -> box(out, area, x, y, z, 0, 0, 0, 1, 1, t);
            case 2 -> box(out, area, x, y, z, 1 - t, 0, 0, 1, 1, 1);
            default -> box(out, area, x, y, z, 0, 0, 1 - t, 1, 1, 1);
        }
    }

    private static boolean solidFull(int id) {
        return !passable(id) && !fence(id) && !gate(id) && id != 139 && !(id == 101 || id == 102 || id == 160)
                && id != 20 && id != 95 && id != 18 && id != 161 && id != 89 && id != 79 && id != 46;
    }

    private static boolean fence(int id) {
        return id == 85 || id == 113 || (id >= 188 && id <= 192);
    }

    private static boolean gate(int id) {
        return id == 107 || (id >= 183 && id <= 187);
    }

    /** 1.5 high: the post plus arms toward connecting neighbors. */
    private static void fence(States world, List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z, int id) {
        boolean north = fenceConnects(world.state(x, y, z - 1) >> 4, id);
        boolean south = fenceConnects(world.state(x, y, z + 1) >> 4, id);
        boolean west = fenceConnects(world.state(x - 1, y, z) >> 4, id);
        boolean east = fenceConnects(world.state(x + 1, y, z) >> 4, id);
        box(out, area, x, y, z, 0.375, 0, north ? 0 : 0.375, 0.625, 1.5, south ? 1 : 0.625);
        box(out, area, x, y, z, west ? 0 : 0.375, 0, 0.375, east ? 1 : 0.625, 1.5, 0.625);
    }

    private static boolean fenceConnects(int neighbor, int self) {
        if (neighbor == 166) {
            return false;
        }
        boolean netherFence = self == 113;
        return (fence(neighbor) && (neighbor == 113) == netherFence) || gate(neighbor) || solidFull(neighbor);
    }

    private static void wall(States world, List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z) {
        boolean north = wallConnects(world.state(x, y, z - 1) >> 4);
        boolean south = wallConnects(world.state(x, y, z + 1) >> 4);
        boolean west = wallConnects(world.state(x - 1, y, z) >> 4);
        boolean east = wallConnects(world.state(x + 1, y, z) >> 4);
        double minX = west ? 0 : 0.25;
        double maxX = east ? 1 : 0.75;
        double minZ = north ? 0 : 0.25;
        double maxZ = south ? 1 : 0.75;
        if (north && south && !west && !east) {
            minX = 0.3125;
            maxX = 0.6875;
        } else if (!north && !south && west && east) {
            minZ = 0.3125;
            maxZ = 0.6875;
        }
        box(out, area, x, y, z, minX, 0, minZ, maxX, 1.5, maxZ);
    }

    private static boolean wallConnects(int neighbor) {
        return neighbor != 166 && (neighbor == 139 || gate(neighbor) || solidFull(neighbor));
    }

    private static void pane(States world, List<AxisAlignedBB> out, AxisAlignedBB area, int x, int y, int z) {
        boolean north = paneConnects(world.state(x, y, z - 1) >> 4);
        boolean south = paneConnects(world.state(x, y, z + 1) >> 4);
        boolean west = paneConnects(world.state(x - 1, y, z) >> 4);
        boolean east = paneConnects(world.state(x + 1, y, z) >> 4);
        if (!north && !south && !west && !east) {
            north = south = west = east = true;
        }
        if (west || east) {
            box(out, area, x, y, z, west ? 0 : 0.4375, 0, 0.4375, east ? 1 : 0.5625, 1, 0.5625);
        }
        if (north || south) {
            box(out, area, x, y, z, 0.4375, 0, north ? 0 : 0.4375, 0.5625, 1, south ? 1 : 0.5625);
        }
    }

    private static boolean paneConnects(int neighbor) {
        return neighbor == 101 || neighbor == 102 || neighbor == 160 || neighbor == 20 || neighbor == 95
                || solidFull(neighbor);
    }
}
