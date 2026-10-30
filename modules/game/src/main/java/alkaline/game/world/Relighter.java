package alkaline.game.world;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's relighting after a block change, as vanilla's World.checkLightFor does it: when a block's opacity or
 * emission changes, light that came through it is removed by a breadth-first walk and refilled from the remaining
 * sources, within 17 blocks. Sky light also follows the column's height (the top block with any opacity), as
 * Chunk.relightBlock moves it. The server sends light only with whole chunks, so without this a lava fall that
 * starts flowing after its chunk arrived stays dark, and a placed block casts no shade.
 *
 * <p>One instance per world, used by the thread that applies server changes. Light is written into copies of the
 * touched sections, published together by {@link #publish}, so a reader sees a section before or after, never
 * halfway.
 */
final class Relighter {
    /** Vanilla's lightUpdateBlockList: 32768 entries of a relative position and a level. */
    private static final int CAPACITY = 32768;
    private static final int REACH = 17;

    private final ClientWorld world;
    private final ClientWorld.LightRules rules;
    private final int[] queue = new int[CAPACITY];
    /** Section copies with light written this batch, by section key; their arrays are private until published. */
    private final Map<Long, ClientWorld.Section> copies = new HashMap<>();

    Relighter(ClientWorld world, ClientWorld.LightRules rules) {
        this.world = world;
        this.rules = rules;
    }

    /**
     * Relights around a cell whose block changed from {@code oldId} (its state already replaced), when the change
     * affects light, and updates the sky light of the column when its height moved from {@code oldHeight}.
     */
    void changed(int x, int y, int z, int oldId, int newId, int oldHeight) {
        boolean sky = world.hasSkyLight();
        if (sky) {
            int newHeight = world.height(x, z, rules);
            for (int cy = Math.min(oldHeight, newHeight); cy < Math.max(oldHeight, newHeight); cy++) {
                check(true, x, cy, z);
            }
        }
        if (rules.opacity(oldId) != rules.opacity(newId) || rules.emission(oldId) != rules.emission(newId)) {
            if (sky) {
                check(true, x, y, z);
            }
            check(false, x, y, z);
        }
    }

    ClientWorld.LightRules rules() {
        return rules;
    }

    /** Publishes every section copy written since the last call and queues the sections for re-meshing. */
    void publish() {
        for (Map.Entry<Long, ClientWorld.Section> entry : copies.entrySet()) {
            long key = entry.getKey();
            world.publishLight(ClientWorld.sectionKeyX(key), ClientWorld.sectionKeyY(key),
                    ClientWorld.sectionKeyZ(key), entry.getValue());
        }
        copies.clear();
    }

    /** World.checkLightFor at one cell: removal, then refill, both relative to the cell. */
    private void check(boolean sky, int x, int y, int z) {
        if (!world.isAreaLoaded(x, z, REACH)) {
            return;
        }
        int read = 0;
        int written = 0;
        int stored = get(sky, x, y, z);
        int raw = raw(sky, x, y, z);
        if (raw > stored) {
            queue[written++] = pack(0, 0, 0, 0);
        } else if (raw < stored) {
            queue[written++] = pack(0, 0, 0, stored);
            while (read < written) {
                int entry = queue[read++];
                int cx = x + dx(entry);
                int cy = y + dy(entry);
                int cz = z + dz(entry);
                int level = entry >>> 18 & 15;
                if (get(sky, cx, cy, cz) != level) {
                    continue;
                }
                set(sky, cx, cy, cz, 0);
                if (level == 0 || distance(entry) >= REACH) {
                    continue;
                }
                for (int face = 0; face < 6; face++) {
                    int nx = cx + FACE_X[face];
                    int ny = cy + FACE_Y[face];
                    int nz = cz + FACE_Z[face];
                    int opacity = Math.max(1, rules.opacity(world.state(nx, ny, nz) >> 4));
                    if (get(sky, nx, ny, nz) == level - opacity && written < CAPACITY) {
                        queue[written++] = pack(nx - x, ny - y, nz - z, level - opacity);
                    }
                }
            }
            read = 0;
        }
        while (read < written) {
            int entry = queue[read++];
            int cx = x + dx(entry);
            int cy = y + dy(entry);
            int cz = z + dz(entry);
            int current = get(sky, cx, cy, cz);
            int wanted = raw(sky, cx, cy, cz);
            if (wanted == current) {
                continue;
            }
            set(sky, cx, cy, cz, wanted);
            if (wanted <= current || distance(entry) >= REACH || written >= CAPACITY - 6) {
                continue;
            }
            for (int face = 0; face < 6; face++) {
                int nx = cx + FACE_X[face];
                int ny = cy + FACE_Y[face];
                int nz = cz + FACE_Z[face];
                if (ny >= 0 && ny < 256 && get(sky, nx, ny, nz) < wanted) {
                    queue[written++] = pack(nx - x, ny - y, nz - z, 0);
                }
            }
        }
    }

    /** World.getRawLight: what a cell's light should be from its own emission, the sky, and its neighbors. */
    private int raw(boolean sky, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return sky ? 15 : 0;
        }
        if (sky && y >= world.height(x, z, rules)) {
            return 15;
        }
        int id = world.state(x, y, z) >> 4;
        int emission = rules.emission(id);
        int level = sky ? 0 : emission;
        int opacity = rules.opacity(id);
        if (opacity >= 15 && emission > 0) {
            opacity = 1;
        }
        if (opacity < 1) {
            opacity = 1;
        }
        if (opacity >= 15) {
            return 0;
        }
        if (level >= 14) {
            return level;
        }
        for (int face = 0; face < 6; face++) {
            int neighbor = get(sky, x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]) - opacity;
            if (neighbor > level) {
                level = neighbor;
            }
            if (level >= 14) {
                return level;
            }
        }
        return level;
    }

    private int get(boolean sky, int x, int y, int z) {
        if (y < 0) {
            return 0;
        }
        if (y > 255) {
            return sky ? 15 : 0;
        }
        ClientWorld.Section copy = copies.get(ClientWorld.sectionKey(x >> 4, y >> 4, z >> 4));
        if (copy != null) {
            return ClientWorld.nibble(sky ? copy.skyLight() : copy.blockLight(), ClientWorld.cell(x, y, z));
        }
        int light = world.light(x, y, z);
        return sky ? light >> 4 : light & 15;
    }

    private void set(boolean sky, int x, int y, int z, int level) {
        if (y < 0 || y > 255) {
            return;
        }
        long key = ClientWorld.sectionKey(x >> 4, y >> 4, z >> 4);
        ClientWorld.Section copy = copies.get(key);
        if (copy == null) {
            ClientWorld.Section section = world.sectionForLight(x >> 4, y >> 4, z >> 4);
            if (section == null) {
                return;
            }
            copy = new ClientWorld.Section(section.states(), section.blockLight().clone(), section.skyLight().clone());
            copies.put(key, copy);
        }
        byte[] array = sky ? copy.skyLight() : copy.blockLight();
        int cell = ClientWorld.cell(x, y, z);
        int index = cell >> 1;
        array[index] = (byte) ((cell & 1) == 0 ? (array[index] & 0xF0) | level : (array[index] & 0x0F) | level << 4);
    }

    private static final int[] FACE_X = {0, 0, 0, 0, -1, 1};
    private static final int[] FACE_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] FACE_Z = {0, 0, -1, 1, 0, 0};

    /** An offset of -32..31 per axis (6 bits each, biased by 32) and a level, as vanilla packs its list. */
    private static int pack(int dx, int dy, int dz, int level) {
        return (dx + 32) | (dy + 32) << 6 | (dz + 32) << 12 | level << 18;
    }

    private static int dx(int entry) {
        return (entry & 63) - 32;
    }

    private static int dy(int entry) {
        return (entry >> 6 & 63) - 32;
    }

    private static int dz(int entry) {
        return (entry >> 12 & 63) - 32;
    }

    private static int distance(int entry) {
        return Math.abs(dx(entry)) + Math.abs(dy(entry)) + Math.abs(dz(entry));
    }
}
