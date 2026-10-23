package alkaline.game.player;

/** BlockLiquid.getFlowVector for water: which way a water block's current pushes, as a unit vector (or zero). */
final class Liquids {
    /** EnumFacing.Plane.HORIZONTAL: north, east, south, west. */
    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private Liquids() {
    }

    static double[] flow(CollisionShapes.States world, int x, int y, int z) {
        int self = decay(world, x, y, z);
        double flowX = 0;
        double flowY = 0;
        double flowZ = 0;
        for (int[] side : SIDES) {
            int nx = x + side[0];
            int nz = z + side[1];
            int level = decay(world, nx, y, nz);
            if (level < 0) {
                if (!blocksMovement(world.state(nx, y, nz) >> 4)) {
                    level = decay(world, nx, y - 1, nz);
                    if (level >= 0) {
                        int weight = level - (self - 8);
                        flowX += side[0] * weight;
                        flowZ += side[1] * weight;
                    }
                }
            } else {
                int weight = level - self;
                flowX += side[0] * weight;
                flowZ += side[1] * weight;
            }
        }
        if ((world.state(x, y, z) & 15) >= 8) {
            // Falling water beside a wall pulls down.
            for (int[] side : SIDES) {
                int nx = x + side[0];
                int nz = z + side[1];
                if (solid(world.state(nx, y, nz) >> 4) || solid(world.state(nx, y + 1, nz) >> 4)) {
                    double[] unit = normalize(flowX, flowY, flowZ);
                    flowX = unit[0];
                    flowY = unit[1] - 6.0;
                    flowZ = unit[2];
                    break;
                }
            }
        }
        return normalize(flowX, flowY, flowZ);
    }

    /** BlockLiquid.getEffectiveFlowDecay for water: the level, 0 for falling water, -1 for anything else. */
    private static int decay(CollisionShapes.States world, int x, int y, int z) {
        int state = world.state(x, y, z);
        int id = state >> 4;
        if (id != 8 && id != 9) {
            return -1;
        }
        int level = state & 15;
        return level >= 8 ? 0 : level;
    }

    /** Material.blocksMovement, near enough: blocks with a collision box, carpets and snow layers aside. */
    private static boolean blocksMovement(int id) {
        return !CollisionShapes.passable(id) && id != 171 && id != 78;
    }

    /** BlockLiquid.isBlockSolid for a horizontal side: a solid material that is neither water nor ice. */
    private static boolean solid(int id) {
        return !CollisionShapes.passable(id) && id != 79 && id != 174 && id != 171 && id != 78;
    }

    /** Vec3.normalize: unit length, or zero below 1e-4. */
    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-4 ? new double[3] : new double[]{x / length, y / length, z / length};
    }
}
