package alkaline.game.player;

import alkaline.game.geometry.AxisAlignedBB;
import java.util.ArrayList;
import java.util.List;

/**
 * The block under the crosshair (EntityRenderer.getMouseOver's block half, World.rayTraceBlocks): the first block
 * the look ray enters within reach, the face it enters through and the hit point. Solid blocks are tested
 * against their collision boxes; blocks without one (plants, torches, rails, signs...) count as their whole cell,
 * which is near enough for right clicks. Air, liquids, fire and portals are not targets, as in vanilla.
 *
 * @param face EnumFacing index: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
 */
public record BlockTarget(int x, int y, int z, int face, double hitX, double hitY, double hitZ, double distance) {
    /**
     * Steps through the cells the ray crosses (Amanatides-Woo), nearest first, and returns the first hit, or null.
     */
    public static BlockTarget pick(CollisionShapes.States world, double eyeX, double eyeY, double eyeZ, float yaw,
                                   float pitch, double reach) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double dirX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double dirY = -Math.sin(pitchRadians);
        double dirZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        int x = (int) Math.floor(eyeX);
        int y = (int) Math.floor(eyeY);
        int z = (int) Math.floor(eyeZ);
        int stepX = dirX > 0 ? 1 : -1;
        int stepY = dirY > 0 ? 1 : -1;
        int stepZ = dirZ > 0 ? 1 : -1;
        double deltaX = Math.abs(1.0 / dirX);
        double deltaY = Math.abs(1.0 / dirY);
        double deltaZ = Math.abs(1.0 / dirZ);
        double nextX = (dirX > 0 ? x + 1 - eyeX : eyeX - x) * deltaX;
        double nextY = (dirY > 0 ? y + 1 - eyeY : eyeY - y) * deltaY;
        double nextZ = (dirZ > 0 ? z + 1 - eyeZ : eyeZ - z) * deltaZ;
        List<AxisAlignedBB> boxes = new ArrayList<>();
        double travelled = 0;
        while (travelled <= reach) {
            BlockTarget hit = test(world, x, y, z, eyeX, eyeY, eyeZ, dirX, dirY, dirZ, reach, boxes);
            if (hit != null) {
                return hit;
            }
            if (nextX <= nextY && nextX <= nextZ) {
                travelled = nextX;
                nextX += deltaX;
                x += stepX;
            } else if (nextY <= nextZ) {
                travelled = nextY;
                nextY += deltaY;
                y += stepY;
            } else {
                travelled = nextZ;
                nextZ += deltaZ;
                z += stepZ;
            }
            if (y < 0 && stepY < 0 || y > 255 && stepY > 0) {
                return null;
            }
        }
        return null;
    }

    private static BlockTarget test(CollisionShapes.States world, int x, int y, int z, double eyeX, double eyeY,
                                    double eyeZ, double dirX, double dirY, double dirZ, double reach,
                                    List<AxisAlignedBB> boxes) {
        int id = world.state(x, y, z) >> 4;
        if (id == 0 || id >= 8 && id <= 11 || id == 51 || id == 90 || id == 119) {
            return null;
        }
        boxes.clear();
        AxisAlignedBB cell = new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1);
        CollisionShapes.add(world, x, y, z, cell, boxes);
        if (boxes.isEmpty()) {
            boxes.add(cell);
        }
        BlockTarget best = null;
        for (AxisAlignedBB box : boxes) {
            BlockTarget hit = intersect(x, y, z, box, eyeX, eyeY, eyeZ, dirX, dirY, dirZ);
            if (hit != null && hit.distance <= reach && (best == null || hit.distance < best.distance)) {
                best = hit;
            }
        }
        return best;
    }

    /** The slab test, remembering which face the ray entered through; a ray starting inside misses. */
    private static BlockTarget intersect(int x, int y, int z, AxisAlignedBB box, double ox, double oy, double oz,
                                         double dx, double dy, double dz) {
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};
        double[] low = {box.minX, box.minY, box.minZ};
        double[] high = {box.maxX, box.maxY, box.maxZ};
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(direction[a]) < 1e-12) {
                if (origin[a] < low[a] || origin[a] > high[a]) {
                    return null;
                }
                continue;
            }
            double t0 = (low[a] - origin[a]) / direction[a];
            double t1 = (high[a] - origin[a]) / direction[a];
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            if (t0 > near) {
                near = t0;
                axis = a;
            }
            far = Math.min(far, t1);
            if (near > far) {
                return null;
            }
        }
        if (axis < 0 || near < 0) {
            return null;
        }
        int face = switch (axis) {
            case 0 -> dx > 0 ? 4 : 5;
            case 1 -> dy > 0 ? 0 : 1;
            default -> dz > 0 ? 2 : 3;
        };
        return new BlockTarget(x, y, z, face, ox + dx * near, oy + dy * near, oz + dz * near, near);
    }
}
