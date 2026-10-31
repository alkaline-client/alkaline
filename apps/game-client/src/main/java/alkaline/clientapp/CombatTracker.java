package alkaline.clientapp;

import alkaline.game.entity.Entity;

/**
 * What the PvP readouts know about the fight: the reach of the last landed hit, the combo, and the player being
 * fought. A hit counts as landed when the server answers the attack with the target's hurt status (Entity Status
 * 2) shortly after it; a hit into the target's hurt-resistance ticks gets no status, so it does not count. The
 * player's own hurt status ends the combo. Game-thread state.
 */
final class CombatTracker {
    /** A hurt status this soon after the attack belongs to it; a round trip on a laggy server still fits. */
    private static final long HIT_WINDOW_MILLIS = 1000L;
    /** A combo ends after this long without another landed hit. */
    private static final long COMBO_MILLIS = 3000L;

    private int pendingTarget = -1;
    private long pendingAt;
    private double pendingReach;

    private double reach = -1.0;
    private int combo;
    private long lastHitAt;
    private int target = -1;
    private long targetAt;

    /** The local player attacked {@code entity} from {@code distance} blocks (eyes to the hitbox, along the look). */
    void attacked(Entity entity, double distance, long now) {
        pendingTarget = entity.id;
        pendingAt = now;
        pendingReach = distance;
        if (entity.category == Entity.Category.PLAYER) {
            target = entity.id;
            targetAt = now;
        }
    }

    /** An Entity Status packet: 2 is hurt. */
    void status(int entityId, int status, int selfId, long now) {
        if (status != 2) {
            return;
        }
        if (entityId == selfId) {
            combo = 0;
            return;
        }
        if (entityId == pendingTarget && now - pendingAt <= HIT_WINDOW_MILLIS) {
            reach = pendingReach;
            combo = now - lastHitAt > COMBO_MILLIS ? 1 : combo + 1;
            lastHitAt = now;
            pendingTarget = -1;
            if (target == entityId) {
                targetAt = now;
            }
        }
    }

    /** Blocks of the last landed hit, or -1 before the first. */
    double reach() {
        return reach;
    }

    int combo(long now) {
        return now - lastHitAt > COMBO_MILLIS ? 0 : combo;
    }

    /** The player last attacked, while {@code keepMillis} have not passed since; -1 for none. */
    int target(long now, long keepMillis) {
        return target >= 0 && now - targetAt <= keepMillis ? target : -1;
    }
}
