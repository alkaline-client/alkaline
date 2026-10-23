package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTracker;
import alkaline.game.entity.EntityTypes;
import alkaline.game.player.CollisionShapes;
import alkaline.renderer.model.EntityModels;
import java.util.Collection;

/**
 * The client-side animation state vanilla keeps on a few mob classes between ticks, keyed by entity id: the ender
 * dragon's yaw and height history and wing beat, the rabbit's hop, the horse's rearing, grazing, mouth and tail,
 * the slime's squish, the wither's side-head aim and the guardian's spikes, tail and beam charge. It ticks once per
 * game tick (after {@link EntityTracker#tick}), drops the state of entities that left the tracker, and answers the
 * renderer without allocating: states are created when a mob first appears, and every lookup is an int-keyed probe.
 */
final class MobAnimator {
    private static final int RING = 64;
    /** EntityGuardian.func_175464_ck: ticks the beam takes to charge (elder, normal). */
    static final int ELDER_CHARGE = 60;
    static final int CHARGE = 80;

    /** One mob's animation values; fields are the vanilla ones (previous values are kept where vanilla lerps). */
    static final class State {
        int seen;
        // Ender dragon: EntityDragon.ringBuffer, animTime.
        final float[] ringYaw = new float[RING];
        final float[] ringHeight = new float[RING];
        int ringIndex = -1;
        float animTime;
        float previousAnimTime;
        // Rabbit: EntityRabbit.field_175540_bm / field_175535_bn.
        int jumpTicks;
        int jumpDuration;
        // Horse: EntityHorse.headLean, rearingAmount, mouthOpenness (each with its prev*), field_110278_bp.
        float grazing, previousGrazing, rearing, previousRearing, mouth, previousMouth;
        int tailSwish;
        boolean ridden;
        // Slime: EntitySlime.squishAmount and squishFactor.
        float squishAmount;
        float squishFactor;
        boolean wasOnGround;
        // Wither: field_82220_d (pitch) and field_82221_e (yaw) for the two side heads.
        final float[] headYaw = new float[2];
        final float[] headPitch = new float[2];
        // Guardian: field_175482_b/_c (tail phase), _bk (its speed), _bl/_bm (spikes), field_175479_bo (charge).
        float tailPhase, previousTailPhase, tailSpeed, spikes, previousSpikes;
        int charge;
        int target;
        // A small deterministic stream for the rolls vanilla makes with the entity's Random.
        int random;
    }

    /** Open-addressing int map; entries are only ever added by the tick, so lookups never allocate. */
    static final class IntTable<V> {
        private int[] keys = new int[64];
        private Object[] values = new Object[64];
        private int size;

        @SuppressWarnings("unchecked")
        V get(int key) {
            int mask = keys.length - 1;
            for (int slot = hash(key) & mask; values[slot] != null; slot = slot + 1 & mask) {
                if (keys[slot] == key) {
                    return (V) values[slot];
                }
            }
            return null;
        }

        void put(int key, V value) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int slot = hash(key) & mask;
            while (values[slot] != null && keys[slot] != key) {
                slot = slot + 1 & mask;
            }
            if (values[slot] == null) {
                size++;
            }
            keys[slot] = key;
            values[slot] = value;
        }

        /** Removal by backward shift, so probes never need tombstones. */
        void remove(int key) {
            int mask = keys.length - 1;
            int slot = hash(key) & mask;
            while (values[slot] != null && keys[slot] != key) {
                slot = slot + 1 & mask;
            }
            if (values[slot] == null) {
                return;
            }
            size--;
            int hole = slot;
            for (int next = hole + 1 & mask; values[next] != null; next = next + 1 & mask) {
                int home = hash(keys[next]) & mask;
                if ((next - home & mask) >= (next - hole & mask)) {
                    keys[hole] = keys[next];
                    values[hole] = values[next];
                    hole = next;
                }
            }
            values[hole] = null;
        }

        void clear() {
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        int size() {
            return size;
        }

        int capacity() {
            return keys.length;
        }

        int keyAt(int slot) {
            return keys[slot];
        }

        @SuppressWarnings("unchecked")
        V valueAt(int slot) {
            return (V) values[slot];
        }

        private void grow() {
            int[] oldKeys = keys;
            Object[] oldValues = values;
            keys = new int[oldKeys.length * 2];
            values = new Object[oldKeys.length * 2];
            size = 0;
            for (int slot = 0; slot < oldKeys.length; slot++) {
                if (oldValues[slot] != null) {
                    @SuppressWarnings("unchecked") V value = (V) oldValues[slot];
                    put(oldKeys[slot], value);
                }
            }
        }

        private static int hash(int key) {
            return key * 0x9E3779B9 >>> 7;
        }
    }

    private final IntTable<State> states = new IntTable<>();
    private final IntTable<Entity> byId = new IntTable<>();
    private int[] stale = new int[16];
    private int tickCount;
    private Entity self;

    /** Listens for Entity Status 1, which starts a rabbit's hop. */
    void attach(EntityTracker tracker) {
        tracker.statusListener = this::status;
    }

    /** The local player's entity (it is not in the tracker), so beams and heads can aim at it. */
    void self(Entity self) {
        this.self = self;
    }

    int tracked() {
        return states.size();
    }

    /** The mob's state, or null for a mob without one (or before its first tick). */
    State state(Entity entity) {
        return states.get(entity.id);
    }

    /** An entity by id among the tracked ones and the local player, or null. */
    Entity entity(int id) {
        return self != null && self.id == id ? self : byId.get(id);
    }

    /** EntityRabbit.handleStatusUpdate: status 1 starts a ten-tick hop. */
    void status(Entity entity, int status) {
        if (entity.type == EntityTypes.RABBIT && status == 1) {
            State state = create(entity);
            state.jumpDuration = 10;
            state.jumpTicks = 0;
        }
    }

    private State create(Entity entity) {
        State state = states.get(entity.id);
        if (state == null) {
            state = new State();
            state.random = entity.id * 0x2545F491 + 1;
            states.put(entity.id, state);
        }
        return state;
    }

    private static boolean animated(Entity entity) {
        if (entity.category != Entity.Category.MOB) {
            return false;
        }
        return switch (entity.type) {
            case EntityTypes.ENDER_DRAGON, EntityTypes.RABBIT, EntityTypes.HORSE, EntityTypes.SLIME,
                 EntityTypes.MAGMA_CUBE, EntityTypes.WITHER, EntityTypes.GUARDIAN -> true;
            default -> false;
        };
    }

    /** One game tick for every animated mob; states of entities no longer tracked are dropped. */
    void tick(Collection<Entity> entities, CollisionShapes.States world) {
        tickCount++;
        byId.clear();
        for (Entity entity : entities) {
            byId.put(entity.id, entity);
        }
        for (int slot = 0; slot < states.capacity(); slot++) {
            State state = states.valueAt(slot);
            if (state != null) {
                state.ridden = false;
            }
        }
        for (Entity entity : entities) {
            if (entity.vehicle >= 0) {
                State vehicle = states.get(entity.vehicle);
                if (vehicle != null) {
                    vehicle.ridden = true;
                }
            }
        }
        if (self != null && self.vehicle >= 0) {
            State vehicle = states.get(self.vehicle);
            if (vehicle != null) {
                vehicle.ridden = true;
            }
        }
        for (Entity entity : entities) {
            if (!animated(entity)) {
                continue;
            }
            State state = create(entity);
            state.seen = tickCount;
            switch (entity.type) {
                case EntityTypes.ENDER_DRAGON -> tickDragon(state, entity);
                case EntityTypes.RABBIT -> tickRabbit(state);
                case EntityTypes.HORSE -> tickHorse(state, entity);
                case EntityTypes.SLIME -> tickSlime(state, entity, 0.6f);
                case EntityTypes.MAGMA_CUBE -> tickSlime(state, entity, 0.9f);
                case EntityTypes.WITHER -> tickWither(state, entity);
                case EntityTypes.GUARDIAN -> tickGuardian(state, entity, world);
                default -> {
                }
            }
        }
        int count = 0;
        for (int slot = 0; slot < states.capacity(); slot++) {
            if (states.valueAt(slot) != null && states.valueAt(slot).seen != tickCount) {
                if (count == stale.length) {
                    stale = java.util.Arrays.copyOf(stale, count * 2);
                }
                stale[count++] = states.keyAt(slot);
            }
        }
        for (int index = 0; index < count; index++) {
            states.remove(stale[index]);
        }
    }

    // ---- per-mob ticks (each is the client half of the vanilla class's onUpdate / onLivingUpdate) ---------------

    /** EntityDragon.onLivingUpdate: wing phase from speed, then the yaw and height ring. */
    static void tickDragon(State state, Entity entity) {
        state.previousAnimTime = state.animTime;
        double dx = entity.x - entity.previousX;
        double dz = entity.z - entity.previousZ;
        double dy = entity.y - entity.previousY;
        float speed = 0.2f / ((float) Math.sqrt(dx * dx + dz * dz) * 10f + 1f);
        state.animTime += speed * (float) Math.pow(2.0, dy);
        float yaw = Entity.wrap(entity.yaw);
        if (state.ringIndex < 0) {
            java.util.Arrays.fill(state.ringYaw, yaw);
            java.util.Arrays.fill(state.ringHeight, (float) entity.y);
            state.ringIndex = 0;
        }
        state.ringIndex = state.ringIndex + 1 & RING - 1;
        state.ringYaw[state.ringIndex] = yaw;
        state.ringHeight[state.ringIndex] = (float) entity.y;
    }

    /** EntityRabbit.onLivingUpdate: the hop counter runs to its duration, then resets. */
    static void tickRabbit(State state) {
        if (state.jumpTicks != state.jumpDuration) {
            state.jumpTicks++;
        } else if (state.jumpDuration != 0) {
            state.jumpTicks = 0;
            state.jumpDuration = 0;
        }
    }

    /**
     * EntityHorse.onLivingUpdate (the tail swish rolls 1 in 200 each tick, and counts to 8) and onUpdate
     * (head lean, rearing and mouth ease toward their flags).
     */
    static void tickHorse(State state, Entity entity) {
        state.random = state.random * 1103515245 + 12345;
        if ((state.random >>> 8) % 200 == 0) {
            state.tailSwish = 1;
        }
        if (state.tailSwish > 0 && ++state.tailSwish > 8) {
            state.tailSwish = 0;
        }
        int flags = entity.metadata.getInt(16, 0);
        state.previousGrazing = state.grazing;
        if ((flags & 32) != 0) {
            state.grazing = Math.min(1f, state.grazing + ((1f - state.grazing) * 0.4f + 0.05f));
        } else {
            state.grazing = Math.max(0f, state.grazing + ((0f - state.grazing) * 0.4f - 0.05f));
        }
        state.previousRearing = state.rearing;
        if ((flags & 64) != 0) {
            state.previousGrazing = state.grazing = 0f;
            state.rearing = Math.min(1f, state.rearing + ((1f - state.rearing) * 0.4f + 0.05f));
        } else {
            float amount = state.rearing;
            state.rearing = Math.max(0f, amount + ((0.8f * amount * amount * amount - amount) * 0.6f - 0.05f));
        }
        state.previousMouth = state.mouth;
        if ((flags & 128) != 0) {
            state.mouth = Math.min(1f, state.mouth + ((1f - state.mouth) * 0.7f + 0.05f));
        } else {
            state.mouth = Math.max(0f, state.mouth + ((0f - state.mouth) * 0.7f - 0.05f));
        }
    }

    /** EntitySlime.onUpdate: the squish eases to its target, which a landing and a take-off set. */
    static void tickSlime(State state, Entity entity, float decay) {
        state.squishFactor += (state.squishAmount - state.squishFactor) * 0.5f;
        if (entity.onGround && !state.wasOnGround) {
            state.squishAmount = -0.5f;
        } else if (!entity.onGround && state.wasOnGround) {
            state.squishAmount = 1f;
        }
        state.wasOnGround = entity.onGround;
        state.squishAmount *= decay;
    }

    /** EntityWither.onLivingUpdate's side heads: turn toward the target (metadata 18, 19) at 10 and 40 degrees a tick. */
    void tickWither(State state, Entity entity) {
        for (int head = 0; head < 2; head++) {
            Entity target = entity(entity.metadata.getInt(18 + head, 0));
            if (target != null) {
                float angle = (entity.bodyYaw + 180 * head) / 180f * (float) Math.PI;
                double ox = entity.x + Math.cos(angle) * 1.3;
                double oz = entity.z + Math.sin(angle) * 1.3;
                double oy = entity.y + 2.2;
                double dx = target.x - ox;
                double dy = target.y + eyeHeight(target) - oy;
                double dz = target.z - oz;
                double flat = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
                float pitch = (float) -(Math.atan2(dy, flat) * 180.0 / Math.PI);
                state.headPitch[head] = approach(state.headPitch[head], pitch, 40f);
                state.headYaw[head] = approach(state.headYaw[head], yaw, 10f);
            } else {
                state.headYaw[head] = approach(state.headYaw[head], entity.bodyYaw, 10f);
            }
        }
    }

    /** EntityWither.func_82204_b: move toward a target angle by at most {@code step}. */
    static float approach(float current, float target, float step) {
        float delta = Math.max(-step, Math.min(step, Entity.wrap(target - current)));
        return current + delta;
    }

    static float eyeHeight(Entity entity) {
        return entity.category == Entity.Category.PLAYER ? 1.62f : EntityTypes.height(entity) * 0.85f;
    }

    /** EntityGuardian.onLivingUpdate (client half): tail speed and phase, spikes, and the beam's charge. */
    static void tickGuardian(State state, Entity entity, CollisionShapes.States world) {
        int flags = entity.metadata.getInt(16, 0);
        boolean retracted = (flags & 2) != 0;
        boolean elder = (flags & 4) != 0;
        int block = world == null ? 0 : world.state((int) Math.floor(entity.x), (int) Math.floor(entity.y),
                (int) Math.floor(entity.z)) >> 4;
        boolean inWater = block == 8 || block == 9;
        state.previousTailPhase = state.tailPhase;
        if (!inWater) {
            state.tailSpeed = 2f;
        } else if (retracted) {
            state.tailSpeed = state.tailSpeed < 0.5f ? 4f : state.tailSpeed + (0.5f - state.tailSpeed) * 0.1f;
        } else {
            state.tailSpeed += (0.125f - state.tailSpeed) * 0.2f;
        }
        state.tailPhase += state.tailSpeed;
        state.previousSpikes = state.spikes;
        if (!inWater) {
            state.random = state.random * 1103515245 + 12345;
            state.spikes = (state.random >>> 8 & 0xFFFF) / 65536f;
        } else if (retracted) {
            state.spikes += (0f - state.spikes) * 0.25f;
        } else {
            state.spikes += (1f - state.spikes) * 0.06f;
        }
        int target = entity.metadata.getInt(17, 0);
        if (target != state.target) {
            state.charge = 0; // onDataWatcherUpdate(17)
            state.target = target;
        }
        if (target != 0 && state.charge < (elder ? ELDER_CHARGE : CHARGE)) {
            state.charge++;
        }
    }

    // ---- reading -------------------------------------------------------------------------------------------

    /** EntityGuardian.func_175477_p: how charged the beam is, 0..1. */
    static float beamCharge(State state, boolean elder, float partialTick) {
        return (state.charge + partialTick) / (elder ? ELDER_CHARGE : CHARGE);
    }

    /** EntityDragon.getMovementOffsets for offsets 0..23, written to {@code motion.movement} as yaw, height pairs. */
    static void dragonOffsets(State state, boolean dead, float partialTick, EntityModels.Motion motion) {
        float blend = 1f - (dead ? 0f : partialTick);
        for (int offset = 0; offset < 24; offset++) {
            int from = state.ringIndex - offset & RING - 1;
            int to = state.ringIndex - offset - 1 & RING - 1;
            float yaw = state.ringYaw[from];
            motion.movement[offset * 2] = yaw + Entity.wrap(state.ringYaw[to] - yaw) * blend;
            motion.movement[offset * 2 + 1] = state.ringHeight[from]
                    + (state.ringHeight[to] - state.ringHeight[from]) * blend;
        }
    }

    /** Copies the state's interpolated values into the motion fields the models read. */
    void apply(Entity entity, State state, float partialTick, EntityModels.Motion motion) {
        switch (entity.type) {
            case EntityTypes.ENDER_DRAGON -> {
                motion.animTime = state.previousAnimTime + (state.animTime - state.previousAnimTime) * partialTick;
                dragonOffsets(state, entity.health() <= 0f, partialTick, motion);
            }
            case EntityTypes.RABBIT -> motion.jump = state.jumpDuration == 0 ? 0f
                    : (state.jumpTicks + partialTick) / state.jumpDuration;
            case EntityTypes.HORSE -> {
                motion.grazing = lerp(state.previousGrazing, state.grazing, partialTick);
                motion.rearing = lerp(state.previousRearing, state.rearing, partialTick);
                motion.mouth = lerp(state.previousMouth, state.mouth, partialTick);
                motion.tailSwish = state.tailSwish != 0;
                motion.ridden = state.ridden;
            }
            case EntityTypes.SLIME, EntityTypes.MAGMA_CUBE -> motion.squish = state.squishFactor;
            case EntityTypes.WITHER -> {
                for (int head = 0; head < 2; head++) {
                    motion.sideHeadYaw[head] = state.headYaw[head] - entity.bodyYaw;
                    motion.sideHeadPitch[head] = state.headPitch[head];
                }
            }
            case EntityTypes.GUARDIAN -> {
                motion.spikes = lerp(state.previousSpikes, state.spikes, partialTick);
                motion.tailPhase = lerp(state.previousTailPhase, state.tailPhase, partialTick);
            }
            default -> {
            }
        }
    }

    private static float lerp(float previous, float current, float partialTick) {
        return previous + (current - previous) * partialTick;
    }
}
