package alkaline.game.entity;

import alkaline.game.geometry.AxisAlignedBB;
import alkaline.game.player.CollisionShapes;
import java.util.ArrayList;
import java.util.List;

/**
 * EntityRenderer.getMouseOver for attacks: the nearest living entity or ender crystal whose hitbox, grown by
 * vanilla's 0.1 collision border, the look ray enters within reach, unless a block is hit first. Blocks are tested
 * against their collision boxes, which match vanilla's selection boxes for full and partial solid blocks (plants and
 * other pass-through blocks do not stop the ray here).
 */
public final class EntityPicker {
    private final List<AxisAlignedBB> boxes = new ArrayList<>();
    private double distance;

    /** How far along the look ray the last picked entity's hitbox starts. */
    public double distance() {
        return distance;
    }

    /**
     * @param reach vanilla's entity reach: 3 blocks in survival and adventure, 6 in creative
     * @return the picked entity, or null
     */
    public Entity pick(EntityTracker entities, CollisionShapes.States world, double eyeX, double eyeY, double eyeZ,
                       float yaw, float pitch, double reach) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double dirX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double dirY = -Math.sin(pitchRadians);
        double dirZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double limit = Math.min(reach, blockDistance(world, eyeX, eyeY, eyeZ, dirX, dirY, dirZ, reach));
        Entity best = null;
        double bestDistance = limit;
        for (Entity entity : entities.all()) {
            if (!pickable(entity) || entity.dead) {
                continue;
            }
            double half = EntityTypes.width(entity) / 2.0 + 0.1;
            double height = EntityTypes.height(entity) + 0.1;
            double distance = intersect(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, entity.x - half, entity.y - 0.1,
                    entity.z - half, entity.x + half, entity.y + height, entity.z + half);
            if (distance >= 0 && distance <= bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        distance = bestDistance;
        return best;
    }

    /** Living entities, and the ender crystal (EntityEnderCrystal.canBeCollidedWith), which a hit detonates. */
    private static boolean pickable(Entity entity) {
        return entity.living() || entity.type == EntityTypes.OBJECT_ENDER_CRYSTAL;
    }

    /** Distance along the ray to the first block collision box, or {@code reach} when none is closer. */
    private double blockDistance(CollisionShapes.States world, double x, double y, double z, double dirX, double dirY,
                                 double dirZ, double reach) {
        double nearest = reach;
        // Step through every block whose cell the ray passes (sampled finely enough for reach <= 6).
        int steps = (int) Math.ceil(reach * 8);
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int step = 0; step <= steps; step++) {
            double t = reach * step / steps;
            int bx = (int) Math.floor(x + dirX * t);
            int by = (int) Math.floor(y + dirY * t);
            int bz = (int) Math.floor(z + dirZ * t);
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;
            AxisAlignedBB cell = new AxisAlignedBB(bx, by, bz, bx + 1, by + 1, bz + 1);
            boxes.clear();
            // The block below as well: fences and walls reach 1.5 blocks up.
            CollisionShapes.add(world, bx, by, bz, cell, boxes);
            CollisionShapes.add(world, bx, by - 1, bz, cell, boxes);
            for (AxisAlignedBB box : boxes) {
                double hit = intersect(x, y, z, dirX, dirY, dirZ, box.minX, box.minY, box.minZ, box.maxX,
                        box.maxY, box.maxZ);
                if (hit >= 0 && hit < nearest) {
                    nearest = hit;
                }
            }
            if (nearest < t) {
                break;
            }
        }
        return nearest;
    }

    /** Slab test: the distance where the ray enters the box (0 when it starts inside), or -1 for a miss. */
    static double intersect(double x, double y, double z, double dirX, double dirY, double dirZ, double minX,
                            double minY, double minZ, double maxX, double maxY, double maxZ) {
        double near = 0;
        double far = Double.MAX_VALUE;
        double[] origin = {x, y, z};
        double[] direction = {dirX, dirY, dirZ};
        double[] low = {minX, minY, minZ};
        double[] high = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) {
                    return -1;
                }
                continue;
            }
            double t0 = (low[axis] - origin[axis]) / direction[axis];
            double t1 = (high[axis] - origin[axis]) / direction[axis];
            if (t0 > t1) {
                double swap = t0;
                t0 = t1;
                t1 = swap;
            }
            near = Math.max(near, t0);
            far = Math.min(far, t1);
            if (near > far) {
                return -1;
            }
        }
        return near;
    }
}
