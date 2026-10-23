package alkaline.game.player;

import alkaline.game.geometry.AxisAlignedBB;
import java.util.ArrayList;
import java.util.List;

/**
 * The local player's movement, one 20 Hz tick at a time, ported from vanilla 1.8.9 in vanilla's order
 * (Entity.onEntityUpdate's water handling, EntityPlayerSP.onLivingUpdate, EntityLivingBase.onLivingUpdate and
 * moveEntityWithHeading, EntityPlayer's flight and air control, Entity.moveEntity). Vanilla's quirks are kept on
 * purpose, because servers and anticheats expect them:
 *
 * <ul>
 *   <li>A double tap of forward within 7 ticks sprints; the tap is detected from last tick's forward input
 *       (EntityPlayerSP reads {@code moveForward} before updating it), and only on the ground and not sneaking.</li>
 *   <li>The sprint ends when forward input drops below 0.8 (sneaking's 0.3 and an item's 0.2 included) or the
 *       player ran into a wall last tick.</li>
 *   <li>Air control ({@code jumpMovementFactor}) is set after moving, so it follows the sprint state one tick late;
 *       ground speed reads the movement speed attribute at once.</li>
 *   <li>Step-up tries two candidates (one measured against the box stretched along the move) and keeps the
 *       original move on a tie.</li>
 *   <li>pushOutOfBlocks never records the south side's distance, so a later side cannot beat it.</li>
 * </ul>
 *
 * Positions are the feet (bounding box minimum y).
 */
public final class LocalPlayer {
    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final float EYE_HEIGHT = 1.62F;
    private static final double STEP = 0.6;
    /** EntityPlayer.speedInAir. */
    private static final float SPEED_IN_AIR = 0.02F;

    /**
     * One tick of player intent (MovementInputFromOptions): forward and strafe are -1..1 before the sneak factor,
     * {@code sprint} is the sprint key (or a toggle) held.
     */
    public record Input(float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
        public static final Input NONE = new Input(0, 0, false, false, false);
    }

    public double x;
    public double y;
    public double z;
    public double previousX;
    public double previousY;
    public double previousZ;
    public double motionX;
    public double motionY;
    public double motionZ;
    public float yaw;
    public float pitch;
    public boolean onGround;
    public boolean collidedHorizontally;
    public boolean collidedVertically;
    public boolean sprinting;
    /** EntityPlayerSP.isSneaking: the sneak input (flying too); drives eye height, edges and the server flag. */
    public boolean sneaking;
    public boolean flying;
    public boolean allowFlying;
    public float flySpeed = 0.05F;
    public float walkSpeed = 0.1F;
    /**
     * generic.movementSpeed as the server's Entity Properties give it, without the sprint modifier the client
     * applies itself; negative until the server sends it (the walk speed stands in).
     */
    public double movementSpeed = -1;
    /** Using an item (eating, drinking, drawing a bow, blocking): the player moves at a fifth of the speed. */
    public boolean usingItem;
    /** The item id in use (-1 for none) and how many ticks it has been used (EntityPlayer.getItemInUseDuration). */
    public int itemInUse = -1;
    public int itemUseTicks;
    public int food = 20;
    public float health = 20F;
    /** Ticks left of the hurt animation (10 after taking damage), as EntityPlayerSP.setPlayerSPHealth sets it. */
    public int hurtTime;
    /** Vanilla waits 10 ticks between jumps while jump is held; off jumps again as soon as the player lands. */
    public boolean jumpDelay = true;
    /** Jump Boost amplifier (0 = level I), or -1 without the effect. */
    public int jumpBoost = -1;
    /** Blindness: no sprinting (and no critical hits). */
    public boolean blind;
    /** Depth Strider level of the boots, 0..3. */
    public int depthStrider;
    /** Haste and Mining Fatigue amplifiers (-1 without), which change how long an arm swing takes. */
    public int haste = -1;
    public int miningFatigue = -1;
    /** Riding: the player's own physics stop and steering is sent instead. */
    public boolean riding;
    /** View bobbing state (Entity.distanceWalkedModified, EntityPlayer.cameraYaw / cameraPitch). */
    public float distanceWalked;
    public float previousDistanceWalked;
    public float cameraYaw;
    public float previousCameraYaw;
    public float cameraPitch;
    public float previousCameraPitch;
    /** The arm's lagging rotation (EntityPlayerSP.renderArmYaw / renderArmPitch). */
    public float armYaw;
    public float armPitch;
    public float previousArmYaw;
    public float previousArmPitch;
    /** Entity.fallDistance (for critical hits). */
    public float fallDistance;
    /** Limb swing for the third-person model (EntityLivingBase.limbSwing / limbSwingAmount). */
    public float limbSwing;
    public float limbSwingAmount;
    public float previousLimbSwingAmount;
    public boolean inWater;
    private boolean swinging;
    private int swingTicks;
    public float swingProgress;
    public float previousSwingProgress;
    private int jumpTicks;
    private int flyToggleTimer;
    private int sprintToggleTimer;
    /** Last tick's MovementInput: forward after the sneak and item factors, jump, sneak. */
    private float lastForward;
    private boolean lastJump;
    private boolean lastSneak;
    private float jumpMovementFactor = SPEED_IN_AIR;
    private boolean inWeb;
    private final List<AxisAlignedBB> boxes = new ArrayList<>();

    /** UUID of the sprint modifier (EntityLivingBase.sprintingSpeedBoostModifierUUID), which the client owns. */
    public static final java.util.UUID SPRINT_MODIFIER = java.util.UUID.fromString("662A6B8D-DA3E-4C1C-8813-96EA6097278D");

    /**
     * ModifiableAttributeInstance.computeValue: operation 0 adds to the base, 1 adds multiples of that sum, 2
     * multiplies the total; modifiers with {@code skip}'s UUID are left out. Movement speed is clamped to 0..1024.
     */
    public static double attribute(double base, java.util.List<alkaline.game.net.ServerSession.AttributeModifier> modifiers,
                                   java.util.UUID skip) {
        double sum = base;
        for (var modifier : modifiers) {
            if (modifier.operation() == 0 && !modifier.uuid().equals(skip)) {
                sum += modifier.amount();
            }
        }
        double value = sum;
        for (var modifier : modifiers) {
            if (modifier.operation() == 1 && !modifier.uuid().equals(skip)) {
                value += sum * modifier.amount();
            }
        }
        for (var modifier : modifiers) {
            if (modifier.operation() == 2 && !modifier.uuid().equals(skip)) {
                value *= 1.0 + modifier.amount();
            }
        }
        return Math.max(0.0, Math.min(1024.0, value));
    }

    /** Rides a vehicle: the position follows it (Entity.updateRidden), with no physics of the player's own. */
    public void ride(double x, double y, double z) {
        previousX = this.x;
        previousY = this.y;
        previousZ = this.z;
        this.x = x;
        this.y = y;
        this.z = z;
        motionX = motionY = motionZ = 0;
        fallDistance = 0;
        onGround = false;
        sprinting = false;
    }

    /** Places the player (a server teleport): position, and no motion carried over. */
    public void teleport(double x, double y, double z) {
        this.x = previousX = x;
        this.y = previousY = y;
        this.z = previousZ = z;
        motionX = motionY = motionZ = 0;
        fallDistance = 0;
    }

    /** Entity Velocity for the player's own id: the server's knockback replaces the motion (setVelocity). */
    public void velocity(double x, double y, double z) {
        motionX = x;
        motionY = y;
        motionZ = z;
    }

    /** An explosion's push is added to the motion. */
    public void push(double x, double y, double z) {
        motionX += x;
        motionY += y;
        motionZ += z;
    }

    /** Update Health: a drop starts the hurt animation (EntityPlayerSP.setPlayerSPHealth). */
    public void health(float value) {
        if (value < health && value > 0) {
            hurtTime = 10;
        }
        health = value;
    }

    /** EntityLivingBase.getArmSwingAnimationEnd: 6 ticks, faster with Haste, slower with Mining Fatigue. */
    public int swingDuration() {
        return haste >= 0 ? 6 - (1 + haste) : miningFatigue >= 0 ? 6 + (1 + miningFatigue) * 2 : 6;
    }

    /** EntityLivingBase.swingItem: restarts the swing unless one is in its first half. */
    public boolean swing() {
        if (!swinging || swingTicks >= swingDuration() / 2 || swingTicks < 0) {
            swingTicks = -1;
            swinging = true;
            return true;
        }
        return false;
    }

    /** Whether an arm swing is under way. */
    public boolean swinging() {
        return swinging;
    }

    /** The swing interpolated for rendering (getSwingProgress). */
    public float swing(float partialTick) {
        float delta = swingProgress - previousSwingProgress;
        if (delta < 0f) {
            delta++;
        }
        return previousSwingProgress + delta * partialTick;
    }

    /**
     * Attacking a player with knockback (sprinting, or a Knockback item) slows the attacker and ends the sprint
     * (EntityPlayer.attackTargetEntityWithCurrentItem).
     */
    public void knockbackDealt() {
        motionX *= 0.6;
        motionZ *= 0.6;
        sprinting = false;
    }

    public AxisAlignedBB box() {
        return new AxisAlignedBB(x - WIDTH / 2, y, z - WIDTH / 2, x + WIDTH / 2, y + HEIGHT, z + WIDTH / 2);
    }

    /**
     * Whether a critical hit lands (EntityPlayer.attackTargetEntityWithCurrentItem): falling, not on a ladder, in
     * water or blind, and not riding.
     */
    public boolean critical(CollisionShapes.States world) {
        return fallDistance > 0 && !onGround && !onLadder(world) && !inWater && !blind && !riding;
    }

    /** Advances one tick. {@code loaded} is false while the player's chunk has not arrived (vanilla then sinks slowly). */
    public void tick(Input input, CollisionShapes.States world, boolean loaded) {
        previousX = x;
        previousY = y;
        previousZ = z;
        previousDistanceWalked = distanceWalked;
        previousCameraYaw = cameraYaw;
        previousCameraPitch = cameraPitch;
        previousSwingProgress = swingProgress;
        previousLimbSwingAmount = limbSwingAmount;
        if (hurtTime > 0) {
            hurtTime--;
        }
        // Entity.onEntityUpdate: water (with its current) is checked before moving, from where the player is.
        inWater = handleWater(world);
        if (inWater) {
            fallDistance = 0;
        }

        // ---- EntityPlayerSP.onLivingUpdate --------------------------------------------------------------------
        if (sprintToggleTimer > 0) {
            sprintToggleTimer--;
        }
        boolean wasJumping = lastJump;
        boolean wasSneaking = lastSneak;
        boolean wasMovingForward = lastForward >= 0.8F;
        // MovementInputFromOptions.updatePlayerMoveState.
        float forward = input.forward();
        float strafe = input.strafe();
        sneaking = input.sneak();
        if (sneaking) {
            forward *= 0.3F;
            strafe *= 0.3F;
        }
        if (usingItem && !riding) {
            forward *= 0.2F;
            strafe *= 0.2F;
            sprintToggleTimer = 0;
        }
        double side = WIDTH * 0.35;
        pushOutOfBlocks(world, x - side, y + 0.5, z + side);
        pushOutOfBlocks(world, x - side, y + 0.5, z - side);
        pushOutOfBlocks(world, x + side, y + 0.5, z - side);
        pushOutOfBlocks(world, x + side, y + 0.5, z + side);
        boolean canSprint = food > 6 || allowFlying;
        if (onGround && !wasSneaking && !wasMovingForward && forward >= 0.8F && !sprinting && canSprint && !usingItem
                && !blind) {
            if (sprintToggleTimer <= 0 && !input.sprint()) {
                sprintToggleTimer = 7;
            } else {
                sprinting = true;
            }
        }
        if (!sprinting && forward >= 0.8F && canSprint && !usingItem && !blind && input.sprint()) {
            sprinting = true;
        }
        if (sprinting && (forward < 0.8F || collidedHorizontally || !canSprint)) {
            sprinting = false;
        }
        if (allowFlying && !wasJumping && input.jump()) {
            if (flyToggleTimer == 0) {
                flyToggleTimer = 7;
            } else {
                flying = !flying;
                flyToggleTimer = 0;
            }
        }
        if (flying) {
            if (input.sneak()) {
                motionY -= flySpeed * 3.0F;
            }
            if (input.jump()) {
                motionY += flySpeed * 3.0F;
            }
        }
        lastForward = forward;
        lastJump = input.jump();
        lastSneak = input.sneak();

        // ---- EntityPlayer / EntityLivingBase.onLivingUpdate ----------------------------------------------------
        if (flyToggleTimer > 0) {
            flyToggleTimer--;
        }
        // EntityPlayer.updateEntityActionState: the swing advances; the arm follows the view by half per tick.
        updateSwing();
        previousArmYaw = armYaw;
        previousArmPitch = armPitch;
        armPitch += (pitch - armPitch) * 0.5F;
        armYaw += (yaw - armYaw) * 0.5F;
        if (jumpTicks > 0) {
            jumpTicks--;
        }
        if (Math.abs(motionX) < 0.005) {
            motionX = 0;
        }
        if (Math.abs(motionY) < 0.005) {
            motionY = 0;
        }
        if (Math.abs(motionZ) < 0.005) {
            motionZ = 0;
        }
        boolean inLava = inLava(world);
        if (input.jump()) {
            if (inWater || inLava) {
                // updateAITick and handleJumpLava.
                motionY += 0.04F;
            } else if (onGround && (!jumpDelay || jumpTicks == 0)) {
                jump();
                jumpTicks = jumpDelay ? 10 : 0;
            }
        } else {
            jumpTicks = 0;
        }
        strafe *= 0.98F;
        forward *= 0.98F;
        double startX = x;
        double startZ = z;
        if (flying) {
            // EntityPlayer.moveEntityWithHeading: flight speed as air control, and vertical motion kept at 60%.
            double keptY = motionY;
            float kept = jumpMovementFactor;
            jumpMovementFactor = flySpeed * (sprinting ? 2 : 1);
            moveWithHeading(strafe, forward, world, loaded, false, false);
            motionY = keptY * 0.6;
            jumpMovementFactor = kept;
        } else {
            moveWithHeading(strafe, forward, world, loaded, inWater, inLava);
        }
        float moved = (float) Math.sqrt((x - startX) * (x - startX) + (z - startZ) * (z - startZ)) * 4.0F;
        limbSwingAmount += (Math.min(1.0F, moved) - limbSwingAmount) * 0.4F;
        limbSwing += limbSwingAmount;

        // ---- EntityPlayer.onLivingUpdate, after moving: air control for next tick, the view bob -----------------
        jumpMovementFactor = SPEED_IN_AIR;
        if (sprinting) {
            jumpMovementFactor = (float) (jumpMovementFactor + SPEED_IN_AIR * 0.3D);
        }
        float speed = (float) Math.min(0.1, Math.sqrt(motionX * motionX + motionZ * motionZ));
        float tilt = (float) (Math.atan(-motionY * 0.20000000298023224D) * 15.0D);
        if (!onGround || health <= 0) {
            speed = 0;
        }
        if (onGround || health <= 0) {
            tilt = 0;
        }
        cameraYaw += (speed - cameraYaw) * 0.4F;
        cameraPitch += (tilt - cameraPitch) * 0.8F;
        if (onGround && flying) {
            flying = false;
        }
    }

    /** EntityLivingBase.updateArmSwingProgress. */
    private void updateSwing() {
        int duration = swingDuration();
        if (swinging) {
            if (++swingTicks >= duration) {
                swingTicks = 0;
                swinging = false;
            }
        } else {
            swingTicks = 0;
        }
        swingProgress = swingTicks / (float) duration;
    }

    /** Whether the player wants to move in some direction this tick (for the sprint key toggles). */
    public float lastForward() {
        return lastForward;
    }

    /** EntityLivingBase.jump, with Jump Boost and the sprint boost along the view. */
    private void jump() {
        motionY = 0.42F;
        if (jumpBoost >= 0) {
            motionY += (jumpBoost + 1) * 0.1F;
        }
        if (sprinting) {
            float radians = yaw * 0.017453292F;
            motionX -= Math.sin(radians) * 0.2F;
            motionZ += Math.cos(radians) * 0.2F;
        }
    }

    /** EntityLivingBase.moveEntityWithHeading for the player (EntityPlayerSP.isServerWorld is true). */
    private void moveWithHeading(float strafe, float forward, CollisionShapes.States world, boolean loaded,
                                 boolean water, boolean lava) {
        if (water) {
            double startY = y;
            float drag = 0.8F;
            float acceleration = 0.02F;
            float strider = Math.min(3.0F, depthStrider);
            if (!onGround) {
                strider *= 0.5F;
            }
            if (strider > 0.0F) {
                drag += (0.54600006F - drag) * strider / 3.0F;
                acceleration += (groundSpeed() - acceleration) * strider / 3.0F;
            }
            moveFlying(strafe, forward, acceleration);
            move(motionX, motionY, motionZ, world);
            motionX *= drag;
            motionY *= 0.800000011920929D;
            motionZ *= drag;
            motionY -= 0.02;
            hopOut(world, startY);
            return;
        }
        if (lava) {
            double startY = y;
            moveFlying(strafe, forward, 0.02F);
            move(motionX, motionY, motionZ, world);
            motionX *= 0.5;
            motionY *= 0.5;
            motionZ *= 0.5;
            motionY -= 0.02;
            hopOut(world, startY);
            return;
        }
        float friction = 0.91F;
        if (onGround) {
            friction = slipperiness(world) * 0.91F;
        }
        float acceleration = 0.16277136F / (friction * friction * friction);
        moveFlying(strafe, forward, onGround ? groundSpeed() * acceleration : jumpMovementFactor);
        friction = 0.91F;
        if (onGround) {
            friction = slipperiness(world) * 0.91F;
        }
        boolean ladder = onLadder(world);
        if (ladder) {
            float limit = 0.15F;
            motionX = Math.max(-limit, Math.min(limit, motionX));
            motionZ = Math.max(-limit, Math.min(limit, motionZ));
            fallDistance = 0;
            if (motionY < -0.15) {
                motionY = -0.15;
            }
            if (sneaking && motionY < 0) {
                motionY = 0;
            }
        }
        move(motionX, motionY, motionZ, world);
        if (collidedHorizontally && onLadder(world)) {
            motionY = 0.2;
        }
        if (!loaded) {
            motionY = y > 0 ? -0.1 : 0;
        } else {
            motionY -= 0.08;
        }
        motionY *= 0.9800000190734863D;
        motionX *= friction;
        motionZ *= friction;
    }

    /** EntityPlayer.getAIMoveSpeed: the movement speed attribute, the client's sprint modifier included. */
    public float groundSpeed() {
        float base = movementSpeed >= 0 ? (float) movementSpeed : walkSpeed;
        return base * (sprinting ? 1.3F : 1.0F);
    }

    /** Leaving water or lava against a wall: a hop when the spot 0.6 higher is free (isOffsetPositionInLiquid). */
    private void hopOut(CollisionShapes.States world, double startY) {
        if (collidedHorizontally) {
            AxisAlignedBB target = box().offset(motionX, motionY + 0.6000000238418579D - y + startY, motionZ);
            if (collisions(world, target).isEmpty() && !anyLiquid(world, target)) {
                motionY = 0.30000001192092896D;
            }
        }
    }

    /** Entity.moveFlying: input turned by yaw, normalized when longer than one. */
    private void moveFlying(float strafe, float forward, float friction) {
        float length = strafe * strafe + forward * forward;
        if (length >= 1.0E-4F) {
            length = (float) Math.sqrt(length);
            if (length < 1.0F) {
                length = 1.0F;
            }
            length = friction / length;
            strafe *= length;
            forward *= length;
            float sin = (float) Math.sin(yaw * Math.PI / 180.0);
            float cos = (float) Math.cos(yaw * Math.PI / 180.0);
            motionX += strafe * cos - forward * sin;
            motionZ += forward * cos + strafe * sin;
        }
    }

    private float slipperiness(CollisionShapes.States world) {
        int id = world.state((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z)) >> 4;
        return switch (id) {
            case 79, 174 -> 0.98F;
            case 165 -> 0.8F;
            default -> 0.6F;
        };
    }

    /** EntityLivingBase.isOnLadder: a ladder or vines at the feet. */
    private boolean onLadder(CollisionShapes.States world) {
        int id = world.state((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) >> 4;
        return id == 65 || id == 106;
    }

    /**
     * Entity.handleWaterMovement (World.handleMaterialAcceleration): in water when a water block's surface reaches
     * the box shrunk by 0.4 at top and bottom; the summed flow of those blocks pushes the player at 0.014 per tick
     * (not while flying).
     */
    private boolean handleWater(CollisionShapes.States world) {
        AxisAlignedBB box = box().expand(0.0, -0.4000000059604645D, 0.0).contract(0.001, 0.001, 0.001);
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX + 1.0);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY + 1.0);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ + 1.0);
        boolean found = false;
        double flowX = 0;
        double flowY = 0;
        double flowZ = 0;
        for (int bx = minX; bx < maxX; bx++) {
            for (int by = minY; by < maxY; by++) {
                for (int bz = minZ; bz < maxZ; bz++) {
                    int state = world.state(bx, by, bz);
                    int id = state >> 4;
                    if (id == 8 || id == 9) {
                        double surface = by + 1 - liquidHeight(state & 15);
                        if (maxY >= surface) {
                            found = true;
                            double[] flow = Liquids.flow(world, bx, by, bz);
                            flowX += flow[0];
                            flowY += flow[1];
                            flowZ += flow[2];
                        }
                    }
                }
            }
        }
        double length = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
        if (length > 0 && !flying) {
            motionX += flowX / length * 0.014;
            motionY += flowY / length * 0.014;
            motionZ += flowZ / length * 0.014;
        }
        return found;
    }

    /** BlockLiquid.getLiquidHeightPercent. */
    static float liquidHeight(int meta) {
        if (meta >= 8) {
            meta = 0;
        }
        return (meta + 1) / 9.0F;
    }

    /** Entity.isInLava: any lava in the box shrunk by 0.1 sideways and 0.4 at top and bottom. */
    private boolean inLava(CollisionShapes.States world) {
        AxisAlignedBB box = box().expand(-0.10000000149011612D, -0.4000000059604645D, -0.10000000149011612D);
        return anyMaterial(world, box, 10, 11);
    }

    private boolean anyLiquid(CollisionShapes.States world, AxisAlignedBB box) {
        return anyMaterial(world, box, 8, 11);
    }

    /** World.isMaterialInBB: any block with an id in [low, high] in the blocks the box covers. */
    private static boolean anyMaterial(CollisionShapes.States world, AxisAlignedBB box, int low, int high) {
        for (int bx = (int) Math.floor(box.minX); bx < (int) Math.floor(box.maxX + 1.0); bx++) {
            for (int by = (int) Math.floor(box.minY); by < (int) Math.floor(box.maxY + 1.0); by++) {
                for (int bz = (int) Math.floor(box.minZ); bz < (int) Math.floor(box.maxZ + 1.0); bz++) {
                    int id = world.state(bx, by, bz) >> 4;
                    if (id >= low && id <= high) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * EntityPlayerSP.pushOutOfBlocks: inside a normal cube, push 0.1 toward the nearest open side. Vanilla never
     * records the south side's distance, so a side checked after it can still win; kept as vanilla has it.
     */
    private void pushOutOfBlocks(CollisionShapes.States world, double px, double py, double pz) {
        int bx = (int) Math.floor(px);
        int by = (int) Math.floor(py);
        int bz = (int) Math.floor(pz);
        double fx = px - bx;
        double fz = pz - bz;
        if (open(world, bx, by, bz)) {
            return;
        }
        int direction = -1;
        double nearest = 9999.0;
        if (open(world, bx - 1, by, bz) && fx < nearest) {
            nearest = fx;
            direction = 0;
        }
        if (open(world, bx + 1, by, bz) && 1.0 - fx < nearest) {
            nearest = 1.0 - fx;
            direction = 1;
        }
        if (open(world, bx, by, bz - 1) && fz < nearest) {
            nearest = fz;
            direction = 4;
        }
        if (open(world, bx, by, bz + 1) && 1.0 - fz < nearest) {
            direction = 5;
        }
        float push = 0.1F;
        switch (direction) {
            case 0 -> motionX = -push;
            case 1 -> motionX = push;
            case 4 -> motionZ = -push;
            case 5 -> motionZ = push;
            default -> {
            }
        }
    }

    /** EntityPlayerSP.isOpenBlockSpace: neither the block nor the one above is a normal cube. */
    private static boolean open(CollisionShapes.States world, int x, int y, int z) {
        return !CollisionShapes.normalCube(world.state(x, y, z) >> 4)
                && !CollisionShapes.normalCube(world.state(x, y + 1, z) >> 4);
    }

    /** Entity.moveEntity: cobwebs, sneaking edge safety, collision y then x then z, step-up, landing, blocks. */
    private void move(double dx, double dy, double dz, CollisionShapes.States world) {
        if (inWeb) {
            inWeb = false;
            dx *= 0.25;
            dy *= 0.05000000074505806D;
            dz *= 0.25;
            motionX = 0;
            motionY = 0;
            motionZ = 0;
        }
        double wantX = dx;
        double wantY = dy;
        double wantZ = dz;
        boolean edgeSafe = onGround && sneaking;
        AxisAlignedBB box = box();
        if (edgeSafe) {
            double step = 0.05;
            while (dx != 0 && collisions(world, box.offset(dx, -1, 0)).isEmpty()) {
                dx = dx < step && dx >= -step ? 0 : dx > 0 ? dx - step : dx + step;
                wantX = dx;
            }
            while (dz != 0 && collisions(world, box.offset(0, -1, dz)).isEmpty()) {
                dz = dz < step && dz >= -step ? 0 : dz > 0 ? dz - step : dz + step;
                wantZ = dz;
            }
            while (dx != 0 && dz != 0 && collisions(world, box.offset(dx, -1, dz)).isEmpty()) {
                dx = dx < step && dx >= -step ? 0 : dx > 0 ? dx - step : dx + step;
                wantX = dx;
                dz = dz < step && dz >= -step ? 0 : dz > 0 ? dz - step : dz + step;
                wantZ = dz;
            }
        }
        List<AxisAlignedBB> list = collisions(world, box.addCoord(dx, dy, dz));
        AxisAlignedBB start = box;
        for (AxisAlignedBB other : list) {
            dy = other.calculateYOffset(box, dy);
        }
        box = box.offset(0, dy, 0);
        boolean grounded = onGround || wantY != dy && wantY < 0;
        for (AxisAlignedBB other : list) {
            dx = other.calculateXOffset(box, dx);
        }
        box = box.offset(dx, 0, 0);
        for (AxisAlignedBB other : list) {
            dz = other.calculateZOffset(box, dz);
        }
        box = box.offset(0, 0, dz);
        if (grounded && (wantX != dx || wantZ != dz)) {
            double plainX = dx;
            double plainY = dy;
            double plainZ = dz;
            AxisAlignedBB plain = box;
            double up = STEP;
            List<AxisAlignedBB> around = collisions(world, start.addCoord(wantX, up, wantZ));
            // First candidate: the rise measured against the box stretched along the move (vanilla's quirk).
            AxisAlignedBB first = start;
            AxisAlignedBB stretched = first.addCoord(wantX, 0.0, wantZ);
            double firstUp = up;
            for (AxisAlignedBB other : around) {
                firstUp = other.calculateYOffset(stretched, firstUp);
            }
            first = first.offset(0, firstUp, 0);
            double firstX = wantX;
            for (AxisAlignedBB other : around) {
                firstX = other.calculateXOffset(first, firstX);
            }
            first = first.offset(firstX, 0, 0);
            double firstZ = wantZ;
            for (AxisAlignedBB other : around) {
                firstZ = other.calculateZOffset(first, firstZ);
            }
            first = first.offset(0, 0, firstZ);
            // Second candidate: the rise measured against the box itself.
            AxisAlignedBB second = start;
            double secondUp = up;
            for (AxisAlignedBB other : around) {
                secondUp = other.calculateYOffset(second, secondUp);
            }
            second = second.offset(0, secondUp, 0);
            double secondX = wantX;
            for (AxisAlignedBB other : around) {
                secondX = other.calculateXOffset(second, secondX);
            }
            second = second.offset(secondX, 0, 0);
            double secondZ = wantZ;
            for (AxisAlignedBB other : around) {
                secondZ = other.calculateZOffset(second, secondZ);
            }
            second = second.offset(0, 0, secondZ);
            double down;
            if (firstX * firstX + firstZ * firstZ > secondX * secondX + secondZ * secondZ) {
                dx = firstX;
                dz = firstZ;
                down = -firstUp;
                box = first;
            } else {
                dx = secondX;
                dz = secondZ;
                down = -secondUp;
                box = second;
            }
            for (AxisAlignedBB other : around) {
                down = other.calculateYOffset(box, down);
            }
            box = box.offset(0, down, 0);
            dy = down;
            if (plainX * plainX + plainZ * plainZ >= dx * dx + dz * dz) {
                dx = plainX;
                dy = plainY;
                dz = plainZ;
                box = plain;
            }
        }
        double fromX = x;
        double fromZ = z;
        x = (box.minX + box.maxX) / 2.0;
        y = box.minY;
        z = (box.minZ + box.maxZ) / 2.0;
        collidedHorizontally = wantX != dx || wantZ != dz;
        collidedVertically = wantY != dy;
        onGround = collidedVertically && wantY < 0;
        // The block landed on: the one 0.2 below the feet, or a fence, wall or gate under air.
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y - 0.20000000298023224D);
        int bz = (int) Math.floor(z);
        int under = world.state(bx, by, bz) >> 4;
        if (under == 0) {
            int below = world.state(bx, by - 1, bz) >> 4;
            if (fenceLike(below)) {
                under = below;
            }
        }
        // EntityLivingBase.updateFallState: water is checked again (current included) when the tick began dry.
        if (!inWater) {
            inWater = handleWater(world);
            if (inWater) {
                fallDistance = 0;
            }
        }
        // Entity.updateFallState.
        if (onGround) {
            fallDistance = 0;
        } else if (dy < 0) {
            fallDistance -= (float) dy;
        }
        if (wantX != dx) {
            motionX = 0;
        }
        if (wantZ != dz) {
            motionZ = 0;
        }
        if (collidedVertically) {
            // Block.onLanded: slime bounces a falling player back up unless sneaking.
            if (under == 165 && !sneaking && motionY < 0) {
                motionY = -motionY;
            } else if (under != 165 || sneaking) {
                motionY = 0;
            }
        }
        if (!edgeSafe && !riding) {
            distanceWalked += (float) (Math.sqrt((x - fromX) * (x - fromX) + (z - fromZ) * (z - fromZ)) * 0.6);
            // BlockSlime.onEntityCollidedWithBlock (walking on it): slowed unless bouncing or sneaking.
            if (onGround && under == 165 && Math.abs(motionY) < 0.1 && !sneaking) {
                double factor = 0.4 + Math.abs(motionY) * 0.2;
                motionX *= factor;
                motionZ *= factor;
            }
        }
        blockCollisions(world);
    }

    private static boolean fenceLike(int id) {
        return id == 85 || id == 113 || id >= 188 && id <= 192 || id == 139 || id == 107 || id >= 183 && id <= 187;
    }

    /** Entity.doBlockCollisions: cobwebs catch the player for the next move, soul sand slows it. */
    private void blockCollisions(CollisionShapes.States world) {
        AxisAlignedBB box = box();
        for (int bx = (int) Math.floor(box.minX + 0.001); bx <= (int) Math.floor(box.maxX - 0.001); bx++) {
            for (int by = (int) Math.floor(box.minY + 0.001); by <= (int) Math.floor(box.maxY - 0.001); by++) {
                for (int bz = (int) Math.floor(box.minZ + 0.001); bz <= (int) Math.floor(box.maxZ - 0.001); bz++) {
                    int id = world.state(bx, by, bz) >> 4;
                    if (id == 30) {
                        inWeb = true;
                    } else if (id == 88) {
                        motionX *= 0.4;
                        motionZ *= 0.4;
                    }
                }
            }
        }
    }

    private List<AxisAlignedBB> collisions(CollisionShapes.States world, AxisAlignedBB area) {
        boxes.clear();
        int minX = (int) Math.floor(area.minX);
        int maxX = (int) Math.floor(area.maxX + 1);
        int minY = (int) Math.floor(area.minY);
        int maxY = (int) Math.floor(area.maxY + 1);
        int minZ = (int) Math.floor(area.minZ);
        int maxZ = (int) Math.floor(area.maxZ + 1);
        for (int bx = minX; bx < maxX; bx++) {
            for (int bz = minZ; bz < maxZ; bz++) {
                // One block lower so 1.5-high fences below the box are found.
                for (int by = minY - 1; by < maxY; by++) {
                    CollisionShapes.add(world, bx, by, bz, area, boxes);
                }
            }
        }
        return new ArrayList<>(boxes);
    }

    /** Eye position interpolated between ticks. */
    public double eyeX(float partial) {
        return previousX + (x - previousX) * partial;
    }

    public double eyeY(float partial) {
        return previousY + (y - previousY) * partial + EYE_HEIGHT - (sneaking ? 0.08 : 0);
    }

    public double eyeZ(float partial) {
        return previousZ + (z - previousZ) * partial;
    }
}
