package alkaline.game.entity;

import alkaline.game.inventory.ItemStack;
import java.util.UUID;

/**
 * One entity the server tracks for this client, as the client simulates it between packets: vanilla's
 * three-tick position interpolation, body and head yaw, limb swing, the arm swing, the hurt flash and the death
 * tilt. Plain mutable state owned by the game thread; renderers read it between ticks and interpolate with the
 * partial tick, as vanilla's renderers do.
 */
public final class Entity {
    /** Players, mobs (living, with limbs and health), and objects (items, arrows, minecarts...). */
    public enum Category { PLAYER, MOB, OBJECT }

    /** Vanilla's maxHurtTime: the red flash lasts 10 ticks. */
    public static final int HURT_TICKS = 10;
    /** EntityLivingBase.getArmSwingAnimationEnd without haste or mining fatigue. */
    public static final int SWING_TICKS = 6;

    public final int id;
    public final Category category;
    /** Spawn Mob type, Spawn Object type, or 0 for players. */
    public final int type;
    /** Players only. */
    public final UUID uuid;
    public final Metadata metadata = new Metadata();
    /** Equipment slots: 0 held item, 1 boots, 2 leggings, 3 chestplate, 4 helmet. */
    public final ItemStack[] equipment = new ItemStack[5];
    /** Spawn Object's extra integer (an arrow's shooter, a falling block's state...). */
    public int objectData;
    /** Projectiles: ticks since a thrown item hit a block (0 before), and an arrow's shake and stuck block. */
    public int impactTicks;
    public int arrowShake;
    public boolean stuck;
    public int stuckX;
    public int stuckY;
    public int stuckZ;
    /** The entity this one rides (Attach Entity), or -1. */
    public int vehicle = -1;

    public double x;
    public double y;
    public double z;
    /** Entity.getPosition when it spawned (block of the feet + 0.5), for OptiFine's random entity rules. */
    public final int spawnX;
    public final int spawnY;
    public final int spawnZ;
    /** generic.maxHealth from Entity Properties, 0 until the server sends it. */
    public float maxHealth;
    public double previousX;
    public double previousY;
    public double previousZ;
    public double motionX;
    public double motionY;
    public double motionZ;
    public float yaw;
    public float pitch;
    public float previousYaw;
    public float previousPitch;
    public float headYaw;
    public float previousHeadYaw;
    public float bodyYaw;
    public float previousBodyYaw;
    public boolean onGround;

    public float limbSwing;
    public float limbSwingAmount;
    public float previousLimbSwingAmount;
    public int hurtTime;
    public int deathTime;
    public boolean dead;
    public int ticksExisted;
    /**
     * Lightning bolts (EntityLightningBolt): the flash state (2 on spawn, below 0 between strikes), the strikes still
     * to come, and the seed the bolt's zigzag is drawn from.
     */
    public int lightningState = 2;
    public int boltLivingTime;
    public long boltVertex;
    private static final java.util.Random LIGHTNING = new java.util.Random();

    /** Active potion effects by id, as amplifiers (the server sends every tracked living entity's). */
    public final java.util.Map<Integer, Integer> effects = new java.util.HashMap<>();
    private boolean swinging;
    private int swingTicks;
    public float swingProgress;
    public float previousSwingProgress;

    // Server-side fixed point position (1/32 block) and the interpolation target.
    private long serverX;
    private long serverY;
    private long serverZ;
    private double targetX;
    private double targetY;
    private double targetZ;
    private float targetYaw;
    private float targetPitch;
    private int increments;

    public Entity(int id, Category category, int type, UUID uuid, double x, double y, double z, float yaw, float pitch) {
        this.id = id;
        this.category = category;
        this.type = type;
        this.uuid = uuid;
        serverX = Math.round(x * 32.0);
        serverY = Math.round(y * 32.0);
        serverZ = Math.round(z * 32.0);
        this.x = previousX = targetX = x;
        this.y = previousY = targetY = y;
        this.z = previousZ = targetZ = z;
        spawnX = (int) Math.floor(x);
        spawnY = (int) Math.floor(y + 0.5);
        spawnZ = (int) Math.floor(z);
        this.yaw = previousYaw = targetYaw = yaw;
        this.pitch = previousPitch = targetPitch = pitch;
        headYaw = previousHeadYaw = yaw;
        bodyYaw = previousBodyYaw = yaw;
        if (category == Category.OBJECT && type == EntityTypes.OBJECT_LIGHTNING) {
            boltVertex = LIGHTNING.nextLong();
            boltLivingTime = LIGHTNING.nextInt(3) + 1;
        }
    }

    /** EntityLivingBase.getArmSwingAnimationEnd: Haste shortens the swing, Mining Fatigue lengthens it. */
    public int swingDuration() {
        Integer haste = effects.get(3);
        if (haste != null) {
            return SWING_TICKS - (1 + haste);
        }
        Integer fatigue = effects.get(4);
        return fatigue != null ? SWING_TICKS + (1 + fatigue) * 2 : SWING_TICKS;
    }

    /** Metadata 0's 0x10 flag: eating, drinking, blocking or drawing a bow (EntityOtherPlayerMP's item in use). */
    public boolean usingItem() {
        return (metadata.getByte(0, 0) & 0x10) != 0;
    }

    /** Ticks the current item use has lasted (0 when not using), as EntityOtherPlayerMP counts it. */
    public int usingTicks;

    public boolean living() {
        return category != Category.OBJECT;
    }

    /** Metadata index 0's flags. */
    public boolean sneaking() {
        return (metadata.getByte(0, 0) & 0x02) != 0;
    }

    public boolean sprinting() {
        return (metadata.getByte(0, 0) & 0x08) != 0;
    }

    public boolean invisible() {
        return (metadata.getByte(0, 0) & 0x20) != 0;
    }

    public boolean burning() {
        return (metadata.getByte(0, 0) & 0x01) != 0;
    }

    /** Living entities' health (metadata 6). */
    public float health() {
        return metadata.getFloat(6, 1f);
    }

    /** Entity Relative Move / Look: accumulate the server position exactly, then interpolate toward it. */
    public void move(double dx, double dy, double dz, boolean look, float newYaw, float newPitch, boolean ground) {
        serverX += Math.round(dx * 32.0);
        serverY += Math.round(dy * 32.0);
        serverZ += Math.round(dz * 32.0);
        retarget(serverX / 32.0, serverY / 32.0, serverZ / 32.0, look ? newYaw : targetYaw,
                look ? newPitch : targetPitch);
        onGround = ground;
    }

    /** Entity Teleport. Vanilla keeps the current position when it is within half a pixel of the new one. */
    public void teleport(double newX, double newY, double newZ, float newYaw, float newPitch, boolean ground) {
        serverX = Math.round(newX * 32.0);
        serverY = Math.round(newY * 32.0);
        serverZ = Math.round(newZ * 32.0);
        double tx = serverX / 32.0;
        double ty = serverY / 32.0;
        double tz = serverZ / 32.0;
        if (Math.abs(x - tx) < 0.03125 && Math.abs(y - ty) < 0.015625 && Math.abs(z - tz) < 0.03125) {
            tx = x;
            ty = y;
            tz = z;
        }
        retarget(tx, ty, tz, newYaw, newPitch);
        onGround = ground;
    }

    /**
     * Living entities reach the new position over three ticks (setPositionAndRotation2 with 3 increments);
     * objects take it on the next tick, so rendering still interpolates from where they were.
     */
    private void retarget(double tx, double ty, double tz, float newYaw, float newPitch) {
        targetX = tx;
        targetY = ty;
        targetZ = tz;
        targetYaw = newYaw;
        targetPitch = newPitch;
        // Minecarts take two more ticks than living entities to arrive (turnProgress), boats four.
        increments = living() ? 3 : type == EntityTypes.OBJECT_MINECART ? 5 : type == EntityTypes.OBJECT_BOAT ? 7 : 1;
    }

    /** Moves exactly to a position on the next {@link #tick} (the client's model of the local player). */
    public void follow(double x, double y, double z, float yaw, float pitch) {
        targetX = x;
        targetY = y;
        targetZ = z;
        targetYaw = yaw;
        targetPitch = pitch;
        increments = 1;
    }

    /** Animation 0 (EntityLivingBase.swingItem as the client sees it). */
    public void swingArm() {
        if (!swinging || swingTicks >= swingDuration() / 2 || swingTicks < 0) {
            swingTicks = -1;
            swinging = true;
        }
    }

    /** Entity Status 2 or Animation 1: performHurtAnimation, plus handleStatusUpdate's limb kick. */
    public void hurt() {
        hurtTime = HURT_TICKS;
        limbSwingAmount = 1.5f;
    }

    /** Entity Status 3: the death tilt starts. */
    public void die() {
        dead = true;
    }

    /** One 20 Hz tick without a world (objects then only follow the server). */
    public void tick() {
        tick(null);
    }

    /** One 20 Hz tick (onUpdate for the client's copy); {@code world} lets objects fly between server updates. */
    public void tick(alkaline.game.player.CollisionShapes.States world) {
        ticksExisted++;
        previousX = x;
        previousY = y;
        previousZ = z;
        previousYaw = yaw;
        previousPitch = pitch;
        previousHeadYaw = headYaw;
        previousBodyYaw = bodyYaw;
        previousSwingProgress = swingProgress;
        previousLimbSwingAmount = limbSwingAmount;
        if (increments > 0) {
            x += (targetX - x) / increments;
            y += (targetY - y) / increments;
            z += (targetZ - z) / increments;
            yaw += wrap(targetYaw - yaw) / increments;
            pitch += (targetPitch - pitch) / increments;
            increments--;
        }
        if (!living()) {
            if (type == EntityTypes.OBJECT_LIGHTNING) {
                lightning();
            }
            if (world != null && ObjectPhysics.simulated(this)) {
                ObjectPhysics.tick(this, world);
            }
            previousYaw = unwrapNear(previousYaw, yaw);
            return;
        }
        if (hurtTime > 0) {
            hurtTime--;
        }
        usingTicks = usingItem() ? usingTicks + 1 : 0;
        if (dead && deathTime < 20) {
            deathTime++;
        }
        // updateArmSwingProgress.
        if (swinging) {
            swingTicks++;
            if (swingTicks >= swingDuration()) {
                swingTicks = 0;
                swinging = false;
            }
        } else {
            swingTicks = 0;
        }
        swingProgress = swingTicks / (float) swingDuration();
        // moveEntityWithHeading's limb swing on remote entities.
        double dx = x - previousX;
        double dz = z - previousZ;
        float speed = Math.min(1f, (float) Math.sqrt(dx * dx + dz * dz) * 4f);
        limbSwingAmount += (speed - limbSwingAmount) * 0.4f;
        limbSwing += limbSwingAmount;
        // EntityLivingBase.onUpdate's body yaw: toward the walking direction, within 75 degrees of the look.
        float distance = (float) (dx * dx + dz * dz);
        float target = bodyYaw;
        if (distance > 0.0025000002F) {
            target = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        }
        if (swingProgress > 0f) {
            target = yaw;
        }
        bodyYaw += wrap(target - bodyYaw) * 0.3f;
        float offset = wrap(yaw - bodyYaw);
        offset = Math.max(-75f, Math.min(75f, offset));
        bodyYaw = yaw - offset;
        if (offset * offset > 2500f) {
            bodyYaw += offset * 0.2f;
        }
        previousYaw = unwrapNear(previousYaw, yaw);
        previousBodyYaw = unwrapNear(previousBodyYaw, bodyYaw);
        previousHeadYaw = unwrapNear(previousHeadYaw, headYaw);
    }

    /**
     * EntityLightningBolt.onUpdate: the bolt flashes for two ticks, then strikes again after a random pause until its
     * strikes run out. The server never destroys it, so the client ends it ({@link #dead}).
     */
    private void lightning() {
        lightningState--;
        if (lightningState < 0) {
            if (boltLivingTime == 0) {
                dead = true;
            } else if (lightningState < -LIGHTNING.nextInt(10)) {
                boltLivingTime--;
                lightningState = 1;
                boltVertex = LIGHTNING.nextLong();
            }
        }
    }

    /** Swing progress (0..1) interpolated for rendering, as getSwingProgress does. */
    public float swing(float partialTick) {
        float delta = swingProgress - previousSwingProgress;
        if (delta < 0f) {
            delta++;
        }
        return previousSwingProgress + delta * partialTick;
    }

    public double renderX(float partialTick) {
        return previousX + (x - previousX) * partialTick;
    }

    public double renderY(float partialTick) {
        return previousY + (y - previousY) * partialTick;
    }

    public double renderZ(float partialTick) {
        return previousZ + (z - previousZ) * partialTick;
    }

    /** The previous angle moved by whole turns to lie within 180 degrees of {@code current}. */
    private static float unwrapNear(float previous, float current) {
        while (current - previous < -180f) {
            previous -= 360f;
        }
        while (current - previous >= 180f) {
            previous += 360f;
        }
        return previous;
    }

    /** MathHelper.wrapAngleTo180_float. */
    public static float wrap(float degrees) {
        degrees %= 360f;
        if (degrees >= 180f) {
            degrees -= 360f;
        }
        if (degrees < -180f) {
            degrees += 360f;
        }
        return degrees;
    }
}
