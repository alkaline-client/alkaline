package alkaline.game.entity;

import alkaline.game.player.CollisionShapes;

/**
 * Where a minecart sits on its rail: EntityMinecart.func_70489_a (getPos) and func_70495_a (getPosOffset), which the
 * renderer uses to ride the rail's slope and turn the cart along the track instead of its own heading.
 */
public final class MinecartRails {
    /** Per rail shape (the block's metadata), the two ends as {x, y, z} offsets from the rail block's middle. */
    private static final int[][][] ENDS = {
            {{0, 0, -1}, {0, 0, 1}}, {{-1, 0, 0}, {1, 0, 0}}, {{-1, -1, 0}, {1, 0, 0}}, {{-1, 0, 0}, {1, -1, 0}},
            {{0, 0, -1}, {0, -1, 1}}, {{0, -1, -1}, {0, 0, 1}}, {{0, 0, 1}, {1, 0, 0}}, {{0, 0, 1}, {-1, 0, 0}},
            {{0, 0, -1}, {-1, 0, 0}}, {{0, 0, -1}, {1, 0, 0}}};

    private MinecartRails() {
    }

    public static boolean isRail(int block) {
        return block == 66 || block == 27 || block == 28 || block == 157;
    }

    /** A point on the track under (x, y, z) as {x, y, z}, or null when no rail is there. */
    public static double[] position(CollisionShapes.States world, double x, double y, double z) {
        int bx = floor(x);
        int by = floor(y);
        int bz = floor(z);
        if (isRail(world.state(bx, by - 1, bz) >> 4)) {
            by--;
        }
        int state = world.state(bx, by, bz);
        if (!isRail(state >> 4)) {
            return null;
        }
        int[][] ends = ENDS[shape(state)];
        double startX = bx + 0.5 + ends[0][0] * 0.5;
        double startY = by + 0.0625 + ends[0][1] * 0.5;
        double startZ = bz + 0.5 + ends[0][2] * 0.5;
        double dx = bx + 0.5 + ends[1][0] * 0.5 - startX;
        double dy = (by + 0.0625 + ends[1][1] * 0.5 - startY) * 2.0;
        double dz = bz + 0.5 + ends[1][2] * 0.5 - startZ;
        double along;
        if (dx == 0.0) {
            x = bx + 0.5;
            along = z - bz;
        } else if (dz == 0.0) {
            z = bz + 0.5;
            along = x - bx;
        } else {
            along = ((x - startX) * dx + (z - startZ) * dz) * 2.0;
        }
        double height = startY + dy * along;
        if (dy < 0.0) {
            height++;
        }
        if (dy > 0.0) {
            height += 0.5;
        }
        return new double[]{startX + dx * along, height, startZ + dz * along};
    }

    /** The track point {@code offset} blocks ahead (negative: behind) along the rail under (x, y, z), or null. */
    public static double[] offset(CollisionShapes.States world, double x, double y, double z, double offset) {
        int bx = floor(x);
        int by = floor(y);
        int bz = floor(z);
        if (isRail(world.state(bx, by - 1, bz) >> 4)) {
            by--;
        }
        int state = world.state(bx, by, bz);
        if (!isRail(state >> 4)) {
            return null;
        }
        int shape = shape(state);
        y = shape >= 2 && shape <= 5 ? by + 1 : by;
        int[][] ends = ENDS[shape];
        double dx = ends[1][0] - ends[0][0];
        double dz = ends[1][2] - ends[0][2];
        double length = Math.sqrt(dx * dx + dz * dz);
        x += dx / length * offset;
        z += dz / length * offset;
        if (ends[0][1] != 0 && floor(x) - bx == ends[0][0] && floor(z) - bz == ends[0][2]) {
            y += ends[0][1];
        } else if (ends[1][1] != 0 && floor(x) - bx == ends[1][0] && floor(z) - bz == ends[1][2]) {
            y += ends[1][1];
        }
        return position(world, x, y, z);
    }

    /** The rail's shape index: plain rails use all four bits, powered ones the low three. */
    private static int shape(int state) {
        int meta = state & 15;
        return (state >> 4) == 66 ? Math.min(meta, 9) : meta & 7;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
