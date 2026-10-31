package alkaline.clientapp;

import alkaline.game.world.ClientWorld;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chests, trapped chests and ender chests of the loaded world, which vanilla draws as tile entities rather
 * than block models: found by rescanning sections as the world reports them changed (a few per frame), and each
 * with TileEntityChest's lid, opened by Block Action packets and swung 0.1 per tick. Render-thread state.
 */
final class ChestTracker {
    static final int CHEST = 54;
    static final int ENDER_CHEST = 130;
    static final int TRAPPED_CHEST = 146;
    private static final int SCANS_PER_FRAME = 24;

    /** One chest block. */
    static final class Chest {
        final int x;
        final int y;
        final int z;
        int block;
        int players;
        float lid;
        float previousLid;

        Chest(int x, int y, int z, int block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }

        float lid(float partialTick) {
            return previousLid + (lid - previousLid) * partialTick;
        }
    }

    private final Map<Long, Chest> chests = new HashMap<>();
    private final Map<Long, List<Long>> bySection = new HashMap<>();
    private final Set<Long> dirty = new LinkedHashSet<>();
    private final char[] states = new char[16 * 16 * 16];

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | (long) z & 0x3FFFFFFL;
    }

    static boolean chest(int block) {
        return block == CHEST || block == TRAPPED_CHEST || block == ENDER_CHEST;
    }

    /** A section the world reported changed (a block set, a column loaded or unloaded). */
    void changed(long sectionKey) {
        dirty.add(sectionKey);
    }

    void clear() {
        chests.clear();
        bySection.clear();
        dirty.clear();
    }

    /** Rescans a few changed sections; call once per frame. */
    void update(ClientWorld world) {
        var iterator = dirty.iterator();
        for (int scanned = 0; scanned < SCANS_PER_FRAME && iterator.hasNext(); scanned++) {
            long section = iterator.next();
            iterator.remove();
            scan(world, section);
        }
    }

    private void scan(ClientWorld world, long section) {
        int baseX = ClientWorld.sectionKeyX(section) * 16;
        int baseY = ClientWorld.sectionKeyY(section) * 16;
        int baseZ = ClientWorld.sectionKeyZ(section) * 16;
        List<Long> previous = bySection.remove(section);
        Map<Long, Chest> kept = new HashMap<>();
        if (previous != null) {
            for (long key : previous) {
                Chest chest = chests.remove(key);
                if (chest != null) {
                    kept.put(key, chest);
                }
            }
        }
        if (!world.sectionStates(baseX >> 4, baseY >> 4, baseZ >> 4, states)) {
            return;
        }
        List<Long> found = null;
        for (int index = 0; index < states.length; index++) {
            int block = states[index] >> 4;
            if (!chest(block)) {
                continue;
            }
            int x = baseX + (index & 15);
            int z = baseZ + (index >> 4 & 15);
            int y = baseY + (index >> 8);
            long key = key(x, y, z);
            Chest chest = kept.get(key);
            if (chest == null) {
                chest = new Chest(x, y, z, block);
            }
            chest.block = block;
            chests.put(key, chest);
            if (found == null) {
                found = new ArrayList<>();
            }
            found.add(key);
        }
        if (found != null) {
            bySection.put(section, found);
        }
    }

    /** Block Action 1 on a chest: how many players have it open (TileEntityChest.receiveClientEvent). */
    void blockAction(int x, int y, int z, int action, int value, int block) {
        if (action != 1 || !chest(block)) {
            return;
        }
        Chest chest = chests.get(key(x, y, z));
        if (chest != null) {
            chest.players = value;
        }
    }

    /** TileEntityChest.update's lid: 0.1 per tick toward open while anyone uses the chest, then back. */
    void tick() {
        for (Chest chest : chests.values()) {
            chest.previousLid = chest.lid;
            if (chest.players > 0 && chest.lid < 1f || chest.players <= 0 && chest.lid > 0f) {
                chest.lid += chest.players > 0 ? 0.1f : -0.1f;
                chest.lid = Math.max(0f, Math.min(1f, chest.lid));
            }
        }
    }

    Collection<Chest> chests() {
        return chests.values();
    }
}
