package alkaline.game.world;

import alkaline.game.net.ServerSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks being dug, one per digger (RenderGlobal.damagedBlocks): the local player's own progress and what
 * Block Break Animation packets report for others. Entries not refreshed for 400 ticks are dropped, checked every
 * 20 ticks, and the renderer drops those it finds more than 32 blocks away.
 */
public final class BlockDamage {
    /** A block being dug and its crack stage (0..9). */
    public static final class Entry {
        private final int breaker;
        private int x;
        private int y;
        private int z;
        private int stage;
        private int updated;

        private Entry(int breaker) {
            this.breaker = breaker;
        }

        public int breaker() {
            return breaker;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        /** The destroy_stage texture to draw: 0..9. */
        public int stage() {
            return stage;
        }
    }

    private static final int EXPIRY_TICKS = 400;
    private static final int CLEANUP_INTERVAL = 20;
    /** RenderGlobal.drawBlockDamageTexture's distance limit, squared. */
    private static final double RANGE_SQUARED = 1024.0;

    private final Map<Integer, Entry> entries = new HashMap<>();
    private final List<Entry> list = new ArrayList<>();
    private int tick;

    /**
     * RenderGlobal.sendBlockBreakProgress: {@code breaker} has dug {@code progress} (0..9) of the block; any other
     * value ends its dig. Moving to another block restarts the entry.
     */
    public void set(int breaker, int x, int y, int z, int progress) {
        if (progress < 0 || progress >= 10) {
            Entry removed = entries.remove(breaker);
            if (removed != null) {
                list.remove(removed);
            }
            return;
        }
        Entry entry = entries.get(breaker);
        if (entry == null) {
            entry = new Entry(breaker);
            entries.put(breaker, entry);
            list.add(entry);
        }
        entry.x = x;
        entry.y = y;
        entry.z = z;
        entry.stage = progress;
        entry.updated = tick;
    }

    /** NetHandlerPlayClient.handleBlockBreakAnim: another player's (or the server's) dig progress. */
    public void apply(ServerSession.BlockBreak packet) {
        set(packet.breakerId(), packet.x(), packet.y(), packet.z(), packet.stage());
    }

    /** One game tick (RenderGlobal.updateClouds): every 20th drops the entries not refreshed for 400 ticks. */
    public void tick() {
        tick++;
        if (tick % CLEANUP_INTERVAL != 0) {
            return;
        }
        for (int index = list.size() - 1; index >= 0; index--) {
            Entry entry = list.get(index);
            if (tick - entry.updated > EXPIRY_TICKS) {
                list.remove(index);
                entries.remove(entry.breaker);
            }
        }
    }

    /** Drops the entries more than 32 blocks from the camera, as the renderer does while drawing. */
    public void cull(double cameraX, double cameraY, double cameraZ) {
        for (int index = list.size() - 1; index >= 0; index--) {
            Entry entry = list.get(index);
            double dx = entry.x - cameraX;
            double dy = entry.y - cameraY;
            double dz = entry.z - cameraZ;
            if (dx * dx + dy * dy + dz * dz > RANGE_SQUARED) {
                list.remove(index);
                entries.remove(entry.breaker);
            }
        }
    }

    /** The blocks being dug now; do not modify. */
    public List<Entry> entries() {
        return list;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public void clear() {
        entries.clear();
        list.clear();
    }
}
