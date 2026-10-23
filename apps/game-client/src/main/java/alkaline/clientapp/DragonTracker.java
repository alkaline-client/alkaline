package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.renderer.model.EntityModels;
import java.util.HashMap;
import java.util.Map;

/**
 * EntityDragon's client-side animation state: the 64-tick ring buffer of yaw and height that bends its neck and
 * tail, and the wing-beat phase, updated once per game tick the renderer sees and read back with
 * {@code getMovementOffsets}' interpolation.
 */
final class DragonTracker {
    private static final int RING = 64;

    private static final class State {
        final float[] yaw = new float[RING];
        final float[] height = new float[RING];
        int index = -1;
        float animTime;
        float previousAnimTime;
        int lastTick = Integer.MIN_VALUE;
    }

    private final Map<Integer, State> states = new HashMap<>();

    /** Advances the entity's state to its current tick and fills {@code motion}'s wing phase and history offsets. */
    void update(Entity entity, float partialTick, EntityModels.Motion motion) {
        State state = states.computeIfAbsent(entity.id, id -> new State());
        if (state.lastTick != entity.ticksExisted) {
            tick(state, entity);
            state.lastTick = entity.ticksExisted;
        }
        motion.animTime = state.previousAnimTime + (state.animTime - state.previousAnimTime) * partialTick;
        // EntityDragon.getMovementOffsets: a dead dragon holds the newest sample.
        float blend = 1f - (entity.health() <= 0f ? 0f : partialTick);
        for (int offset = 0; offset < 24; offset++) {
            int from = state.index - offset & RING - 1;
            int to = state.index - offset - 1 & RING - 1;
            float yaw = state.yaw[from];
            motion.movement[offset * 2] = yaw + Entity.wrap(state.yaw[to] - yaw) * blend;
            motion.movement[offset * 2 + 1] = state.height[from] + (state.height[to] - state.height[from]) * blend;
        }
    }

    /** One onLivingUpdate: the wing phase advances by the speed, and the ring takes this tick's yaw and height. */
    private static void tick(State state, Entity entity) {
        state.previousAnimTime = state.animTime;
        double dx = entity.x - entity.previousX;
        double dz = entity.z - entity.previousZ;
        double dy = entity.y - entity.previousY;
        float speed = 0.2f / ((float) Math.sqrt(dx * dx + dz * dz) * 10f + 1f);
        state.animTime += speed * (float) Math.pow(2.0, dy);
        float yaw = Entity.wrap(entity.yaw);
        if (state.index < 0) {
            java.util.Arrays.fill(state.yaw, yaw);
            java.util.Arrays.fill(state.height, (float) entity.y);
            state.index = 0;
        }
        state.index = state.index + 1 & RING - 1;
        state.yaw[state.index] = yaw;
        state.height[state.index] = (float) entity.y;
    }
}
