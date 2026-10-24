package alkaline.game.entity;

import alkaline.game.geometry.AxisAlignedBB;
import alkaline.game.player.CollisionShapes;
import java.util.ArrayList;
import java.util.List;

/**
 * The flight the vanilla client simulates itself for objects between the server's (sparse) position updates:
 * arrows are updated every 20 ticks, thrown items every 10, so without it they would jump. Ported from
 * EntityThrowable, EntityArrow, EntityFishHook, EntityItem, EntityXPOrb, EntityTNTPrimed and EntityFallingBlock's
 * onUpdate as they run on the client. The server stays the authority: its teleports and moves snap the object,
 * and it removes the object when it hits.
 */
public final class ObjectPhysics {
    private ObjectPhysics() {
    }

    /** Whether the client simulates this object's flight. */
    static boolean simulated(Entity entity) {
        if (entity.category != Entity.Category.OBJECT) {
            return false;
        }
        return switch (entity.type) {
            case EntityTypes.OBJECT_ARROW, EntityTypes.OBJECT_SNOWBALL, EntityTypes.OBJECT_EGG,
                 EntityTypes.OBJECT_ENDER_PEARL, EntityTypes.OBJECT_POTION, EntityTypes.OBJECT_EXP_BOTTLE,
                 EntityTypes.OBJECT_FISHING_HOOK, EntityTypes.OBJECT_ITEM, EntityTypes.OBJECT_XP_ORB,
                 EntityTypes.OBJECT_TNT, EntityTypes.OBJECT_FALLING_BLOCK, EntityTypes.OBJECT_FIREWORK -> true;
            default -> false;
        };
    }

    static void tick(Entity entity, CollisionShapes.States world) {
        switch (entity.type) {
            case EntityTypes.OBJECT_ARROW -> arrow(entity, world);
            case EntityTypes.OBJECT_SNOWBALL, EntityTypes.OBJECT_EGG, EntityTypes.OBJECT_ENDER_PEARL ->
                    throwable(entity, world, 0.03F);
            case EntityTypes.OBJECT_POTION -> throwable(entity, world, 0.05F);
            case EntityTypes.OBJECT_EXP_BOTTLE -> throwable(entity, world, 0.07F);
            case EntityTypes.OBJECT_FISHING_HOOK -> hook(entity, world);
            case EntityTypes.OBJECT_FIREWORK -> firework(entity);
            case EntityTypes.OBJECT_ITEM -> fall(entity, world, 0.25, 0.03999999910593033, 0.98F, -0.5, 1.0);
            case EntityTypes.OBJECT_XP_ORB -> fall(entity, world, 0.5, 0.029999999329447746D, 0.98F, -0.8999999761581421D, 1.0);
            case EntityTypes.OBJECT_TNT, EntityTypes.OBJECT_FALLING_BLOCK ->
                    fall(entity, world, 0.98, 0.03999999910593033D, 0.98F, -0.5, 0.699999988079071D);
            default -> {
            }
        }
    }

    /** EntityThrowable.onUpdate: an impact (particles on the client), then motion, drag (0.8 in water) and gravity. */
    private static void throwable(Entity entity, CollisionShapes.States world, float gravity) {
        if (entity.impactTicks == 0 && hits(world, entity.x, entity.y, entity.z, entity.x + entity.motionX,
                entity.y + entity.motionY, entity.z + entity.motionZ) != null) {
            entity.impactTicks = 1;
        } else if (entity.impactTicks > 0) {
            entity.impactTicks++;
        }
        entity.x += entity.motionX;
        entity.y += entity.motionY;
        entity.z += entity.motionZ;
        face(entity);
        float drag = inWater(world, entity) ? 0.8F : 0.99F;
        entity.motionX *= drag;
        entity.motionY *= drag;
        entity.motionZ *= drag;
        entity.motionY -= gravity;
    }

    /**
     * EntityArrow.onUpdate: in flight it moves, turns to its motion and falls (0.05, drag 0.99 or 0.6 in water);
     * hitting a block stops it 0.05 short of the hit point, stuck, with a 7-tick shake.
     */
    private static void arrow(Entity entity, CollisionShapes.States world) {
        if (entity.arrowShake > 0) {
            entity.arrowShake--;
        }
        if (entity.stuck) {
            // Unstuck when the block it hit is gone (the server then sends its new motion).
            if (CollisionShapes.passable(world.state(entity.stuckX, entity.stuckY, entity.stuckZ) >> 4)) {
                entity.stuck = false;
            } else {
                return;
            }
        }
        double[] hit = hits(world, entity.x, entity.y, entity.z, entity.x + entity.motionX, entity.y + entity.motionY,
                entity.z + entity.motionZ);
        if (hit != null) {
            entity.motionX = hit[0] - entity.x;
            entity.motionY = hit[1] - entity.y;
            entity.motionZ = hit[2] - entity.z;
            double length = Math.sqrt(entity.motionX * entity.motionX + entity.motionY * entity.motionY
                    + entity.motionZ * entity.motionZ);
            if (length > 0) {
                entity.x -= entity.motionX / length * 0.05000000074505806D;
                entity.y -= entity.motionY / length * 0.05000000074505806D;
                entity.z -= entity.motionZ / length * 0.05000000074505806D;
            }
            entity.stuck = true;
            entity.stuckX = (int) hit[3];
            entity.stuckY = (int) hit[4];
            entity.stuckZ = (int) hit[5];
            entity.arrowShake = 7;
        }
        entity.x += entity.motionX;
        entity.y += entity.motionY;
        entity.z += entity.motionZ;
        face(entity);
        float drag = inWater(world, entity) ? 0.6F : 0.99F;
        entity.motionX *= drag;
        entity.motionY *= drag;
        entity.motionZ *= drag;
        entity.motionY -= 0.05000000074505806D;
        if (entity.stuck) {
            entity.motionX = entity.motionY = entity.motionZ = 0;
        }
    }

    /** EntityFireworkRocket.onUpdate: it speeds up 15% a tick horizontally and climbs, turning to its motion. */
    private static void firework(Entity entity) {
        entity.motionX *= 1.15;
        entity.motionZ *= 1.15;
        entity.motionY += 0.04;
        entity.x += entity.motionX;
        entity.y += entity.motionY;
        entity.z += entity.motionZ;
        face(entity);
    }

    /** EntityFishHook in the air: drag 0.92 and gravity 0.04; it floats once in water. */
    private static void hook(Entity entity, CollisionShapes.States world) {
        if (inWater(world, entity)) {
            entity.motionY = entity.motionY * 0.5 + 0.02;
            entity.motionX *= 0.8;
            entity.motionZ *= 0.8;
        }
        entity.x += entity.motionX;
        entity.y += entity.motionY;
        entity.z += entity.motionZ;
        if (!CollisionShapes.passable(world.state((int) Math.floor(entity.x), (int) Math.floor(entity.y),
                (int) Math.floor(entity.z)) >> 4)) {
            entity.motionX = entity.motionY = entity.motionZ = 0;
        }
        entity.motionX *= 0.92;
        entity.motionY *= 0.92;
        entity.motionZ *= 0.92;
        entity.motionY -= 0.03999999910593033D;
    }

    /**
     * Items, orbs, primed TNT and falling blocks: gravity, a collision move, drag (with the block's slipperiness on
     * the ground) and a bounce back up when landing.
     */
    private static void fall(Entity entity, CollisionShapes.States world, double size, double gravity, float drag,
                             double bounce, double groundDrag) {
        entity.motionY -= gravity;
        move(entity, world, size);
        float friction = drag;
        if (entity.onGround) {
            int below = world.state((int) Math.floor(entity.x), (int) Math.floor(entity.y) - 1,
                    (int) Math.floor(entity.z)) >> 4;
            friction = (below == 79 || below == 174 ? 0.98F : below == 165 ? 0.8F : 0.6F) * 0.98F;
            if (groundDrag != 1.0) {
                friction = 0.98F;
            }
        }
        entity.motionX *= friction;
        entity.motionY *= 0.9800000190734863D;
        entity.motionZ *= friction;
        if (entity.onGround) {
            entity.motionX *= groundDrag;
            entity.motionZ *= groundDrag;
            entity.motionY *= bounce;
        }
    }

    private static void move(Entity entity, CollisionShapes.States world, double size) {
        double half = size / 2;
        AxisAlignedBB box = new AxisAlignedBB(entity.x - half, entity.y, entity.z - half, entity.x + half,
                entity.y + size, entity.z + half);
        double dx = entity.motionX;
        double dy = entity.motionY;
        double dz = entity.motionZ;
        List<AxisAlignedBB> list = new ArrayList<>();
        AxisAlignedBB area = box.addCoord(dx, dy, dz);
        for (int bx = (int) Math.floor(area.minX); bx < (int) Math.floor(area.maxX + 1); bx++) {
            for (int bz = (int) Math.floor(area.minZ); bz < (int) Math.floor(area.maxZ + 1); bz++) {
                for (int by = (int) Math.floor(area.minY) - 1; by < (int) Math.floor(area.maxY + 1); by++) {
                    CollisionShapes.add(world, bx, by, bz, area, list);
                }
            }
        }
        double wantY = dy;
        for (AxisAlignedBB other : list) {
            dy = other.calculateYOffset(box, dy);
        }
        box = box.offset(0, dy, 0);
        double wantX = dx;
        for (AxisAlignedBB other : list) {
            dx = other.calculateXOffset(box, dx);
        }
        box = box.offset(dx, 0, 0);
        double wantZ = dz;
        for (AxisAlignedBB other : list) {
            dz = other.calculateZOffset(box, dz);
        }
        box = box.offset(0, 0, dz);
        entity.x = (box.minX + box.maxX) / 2;
        entity.y = box.minY;
        entity.z = (box.minZ + box.maxZ) / 2;
        entity.onGround = wantY != dy && wantY < 0;
        if (wantX != dx) {
            entity.motionX = 0;
        }
        if (wantZ != dz) {
            entity.motionZ = 0;
        }
        if (wantY != dy) {
            // Block.onLanded zeroes the fall (so the callers' bounce only matters on slime, which reverses it).
            int under = world.state((int) Math.floor(entity.x), (int) Math.floor(entity.y - 0.2), (int) Math.floor(entity.z)) >> 4;
            entity.motionY = under == 165 && entity.motionY < 0 ? -entity.motionY : 0;
        }
    }

    /** The rotation follows the motion, eased by 20% a tick as EntityThrowable and EntityArrow do. */
    private static void face(Entity entity) {
        double horizontal = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);
        float yaw = (float) Math.toDegrees(Math.atan2(entity.motionX, entity.motionZ));
        float pitch = (float) Math.toDegrees(Math.atan2(entity.motionY, horizontal));
        float previousPitch = entity.pitch;
        float previousYaw = entity.yaw;
        while (pitch - previousPitch < -180f) {
            previousPitch -= 360f;
        }
        while (pitch - previousPitch >= 180f) {
            previousPitch += 360f;
        }
        while (yaw - previousYaw < -180f) {
            previousYaw -= 360f;
        }
        while (yaw - previousYaw >= 180f) {
            previousYaw += 360f;
        }
        entity.pitch = previousPitch + (pitch - previousPitch) * 0.2F;
        entity.yaw = previousYaw + (yaw - previousYaw) * 0.2F;
    }

    private static boolean inWater(CollisionShapes.States world, Entity entity) {
        int id = world.state((int) Math.floor(entity.x), (int) Math.floor(entity.y), (int) Math.floor(entity.z)) >> 4;
        return id == 8 || id == 9;
    }

    /**
     * World.rayTraceBlocks along a segment against collision boxes: the first hit as {x, y, z, blockX, blockY,
     * blockZ}, or null.
     */
    public static double[] hits(CollisionShapes.States world, double x0, double y0, double z0, double x1, double y1,
                         double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) {
            return null;
        }
        int steps = (int) Math.ceil(length * 4) + 1;
        List<AxisAlignedBB> boxes = new ArrayList<>();
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        double best = Double.MAX_VALUE;
        double[] result = null;
        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;
            int bx = (int) Math.floor(x0 + dx * t);
            int by = (int) Math.floor(y0 + dy * t);
            int bz = (int) Math.floor(z0 + dz * t);
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;
            boxes.clear();
            CollisionShapes.add(world, bx, by, bz, new AxisAlignedBB(bx, by, bz, bx + 1, by + 1, bz + 1), boxes);
            for (AxisAlignedBB box : boxes) {
                double enter = EntityPicker.intersect(x0, y0, z0, dx / length, dy / length, dz / length, box.minX,
                        box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
                if (enter >= 0 && enter <= length && enter < best) {
                    best = enter;
                    result = new double[]{x0 + dx / length * enter, y0 + dy / length * enter, z0 + dz / length * enter,
                            bx, by, bz};
                }
            }
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
