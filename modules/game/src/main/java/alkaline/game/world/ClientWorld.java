package alkaline.game.world;

import alkaline.game.net.PacketBuffer;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

/**
 * The blocks and light the server has sent, by chunk column: 16 sections of 16x16x16 block states ({@code id << 4
 * | metadata}, vanilla's 1.8 chunk storage) with block and sky light nibbles.
 *
 * <p>Thread safety: the network thread applies chunk data and block changes; meshing workers read concurrently.
 * Sections are never modified in place: a change copies the section and publishes the copy, so a reader always
 * sees one consistent section. Changed sections are queued for re-meshing ({@link #drainChanged}).
 */
public final class ClientWorld {
    public static final int SECTIONS = 16;
    private static final int SECTION_CELLS = 4096;

    /** One immutable section snapshot. Light arrays hold one nibble per cell, the even cell in the low nibble. */
    record Section(char[] states, byte[] blockLight, byte[] skyLight) {
        static final Section EMPTY_LIT = new Section(new char[SECTION_CELLS], new byte[2048], filled((byte) 0xFF));

        private static byte[] filled(byte value) {
            byte[] light = new byte[2048];
            Arrays.fill(light, value);
            return light;
        }
    }

    private static final class Column {
        final Section[] sections = new Section[SECTIONS];
        /** Biome id per (z << 4 | x); plains until the server sends biomes. */
        volatile byte[] biomes = DEFAULT_BIOMES;
        /** Cached precipitation heights per (z << 4 | x); null after a change. */
        volatile short[] precipitation;
    }

    private static final byte[] DEFAULT_BIOMES = filledBiomes();

    private static byte[] filledBiomes() {
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 1);
        return biomes;
    }

    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();
    /**
     * The last column looked up, so repeated lookups in one column (the render thread asks about the same few
     * columns many times a frame) neither box a key nor search the map. The version guards against keeping a
     * column that was replaced or unloaded while it was being looked up.
     */
    private record Cached(long key, Column column, int version) {
    }

    private volatile Cached cached;
    private volatile int columnVersion;
    private final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
    private final boolean skyLight;

    /** @param skyLight whether the dimension has sky light (the overworld; not the nether or end) */
    public ClientWorld(boolean skyLight) {
        this.skyLight = skyLight;
    }

    public boolean hasSkyLight() {
        return skyLight;
    }

    /**
     * The column map key: x and z packed, then multiplied by an odd constant. The multiply is a bijection on 64-bit
     * values, so keys stay unique, and it spreads both coordinates over Long.hashCode's {@code high ^ low}: the plain
     * packing hashes to {@code x ^ z}, which collides for every column on a diagonal and turned the map's buckets
     * into trees around the player.
     */
    private static long columnKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL) * 0x9E3779B97F4A7C15L;
    }

    /** Section key for change notifications: x and z (22 bits each) and y (8 bits). */
    public static long sectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 30 | ((long) z & 0x3FFFFF) << 8 | y & 0xFF;
    }

    public static int sectionKeyX(long key) {
        return (int) (key << 12 >> 42);
    }

    public static int sectionKeyZ(long key) {
        return (int) (key << 34 >> 42);
    }

    public static int sectionKeyY(long key) {
        return (int) (key & 0xFF);
    }

    /** The column at chunk coordinates, or null; see {@link Cached}. */
    private Column column(int chunkX, int chunkZ) {
        long key = columnKey(chunkX, chunkZ);
        int version = columnVersion;
        Cached hit = cached;
        if (hit != null && hit.key == key && hit.version == version) {
            return hit.column;
        }
        Column column = columns.get(key);
        if (column != null) {
            cached = new Cached(key, column, version);
        }
        return column;
    }

    public boolean isLoaded(int chunkX, int chunkZ) {
        return columns.containsKey(columnKey(chunkX, chunkZ));
    }

    public int loadedColumns() {
        return columns.size();
    }

    // ---- reading -------------------------------------------------------------------------------------------

    /** The block state at a position; air outside loaded columns and the 0..255 height range. */
    public int state(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return 0;
        }
        Column column = column(x >> 4, z >> 4);
        if (column == null) {
            return 0;
        }
        Section section = column.sections[y >> 4];
        return section == null ? 0 : section.states[cell(x, y, z)];
    }

    /** Light at a position as {@code sky << 4 | block}. */
    public int light(int x, int y, int z) {
        if (y > 255) {
            return skyLight ? 0xF0 : 0;
        }
        if (y < 0) {
            return 0;
        }
        Column column = column(x >> 4, z >> 4);
        if (column == null) {
            return skyLight ? 0xF0 : 0;
        }
        Section section = column.sections[y >> 4];
        if (section == null) {
            return skyLight ? 0xF0 : 0;
        }
        int cell = cell(x, y, z);
        return nibble(section.skyLight, cell) << 4 | nibble(section.blockLight, cell);
    }

    /**
     * Copies a box of states and light into flat arrays (x fastest, then y, then z), resolving each column
     * once. Used by section meshing.
     */
    public void copy(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, char[] states, byte[] light) {
        byte openSky = (byte) (skyLight ? 0xF0 : 0);
        for (int dz = 0; dz < sizeZ; dz++) {
            int z = minZ + dz;
            for (int dx = 0; dx < sizeX; dx++) {
                int x = minX + dx;
                Column column = column(x >> 4, z >> 4);
                for (int dy = 0; dy < sizeY; dy++) {
                    int y = minY + dy;
                    int index = dx + dy * sizeX + dz * sizeX * sizeY;
                    if (y < 0) {
                        states[index] = 0;
                        light[index] = 0;
                        continue;
                    }
                    Section section = column == null || y > 255 ? null : column.sections[y >> 4];
                    if (section == null) {
                        states[index] = 0;
                        light[index] = openSky;
                        continue;
                    }
                    int cell = cell(x, y, z);
                    states[index] = section.states[cell];
                    light[index] = (byte) (nibble(section.skyLight, cell) << 4 | nibble(section.blockLight, cell));
                }
            }
        }
    }

    private static int cell(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    private static int nibble(byte[] array, int cell) {
        int value = array[cell >> 1];
        return (cell & 1) == 0 ? value & 15 : value >> 4 & 15;
    }

    /** The biome id of a column (plains where none is known). */
    public int biome(int x, int z) {
        Column column = column(x >> 4, z >> 4);
        return column == null ? 1 : column.biomes[(z & 15) << 4 | x & 15] & 0xFF;
    }

    /**
     * Chunk.getPrecipitationHeight: one above the highest block that blocks movement or is a liquid, as
     * {@code stops} decides from a block id; 0 in unloaded columns. Cached per column until it changes.
     */
    public int precipitationHeight(int x, int z, java.util.function.IntPredicate stops) {
        Column column = column(x >> 4, z >> 4);
        if (column == null) {
            return 0;
        }
        short[] heights = column.precipitation;
        if (heights == null) {
            heights = new short[256];
            for (int cell = 0; cell < 256; cell++) {
                int localX = cell & 15;
                int localZ = cell >> 4;
                int height = 0;
                for (int y = 255; y >= 0 && height == 0; y--) {
                    Section section = column.sections[y >> 4];
                    if (section == null) {
                        y &= ~15;
                        continue;
                    }
                    if (stops.test(section.states[(y & 15) << 8 | localZ << 4 | localX] >> 4)) {
                        height = y + 1;
                    }
                }
                heights[cell] = (short) height;
            }
            column.precipitation = heights;
        }
        return heights[(z & 15) << 4 | x & 15];
    }

    // ---- changes from the server ---------------------------------------------------------------------------

    /**
     * Applies one column of chunk data (protocol 47): for each section in {@code mask}, 4096 block states as
     * little-endian shorts, then each section's block light, then (with sky light) each section's sky light,
     * then 256 biome bytes when {@code groundUp}. A ground-up column with an empty mask unloads the column.
     */
    public void applyChunk(int chunkX, int chunkZ, boolean groundUp, int mask, PacketBuffer data) throws IOException {
        long key = columnKey(chunkX, chunkZ);
        if (groundUp && mask == 0) {
            unload(chunkX, chunkZ);
            return;
        }
        int count = Integer.bitCount(mask & 0xFFFF);
        char[][] states = new char[count][];
        byte[][] blockLight = new byte[count][];
        byte[][] sky = new byte[count][];
        byte[] raw = new byte[SECTION_CELLS * 2];
        for (int index = 0; index < count; index++) {
            data.readInto(raw, 0, raw.length);
            char[] section = new char[SECTION_CELLS];
            for (int cell = 0; cell < SECTION_CELLS; cell++) {
                section[cell] = (char) ((raw[cell * 2] & 0xFF) | (raw[cell * 2 + 1] & 0xFF) << 8);
            }
            states[index] = section;
        }
        for (int index = 0; index < count; index++) {
            blockLight[index] = data.readBytes(2048);
        }
        for (int index = 0; index < count; index++) {
            sky[index] = skyLight ? data.readBytes(2048) : new byte[2048];
        }
        byte[] biomes = groundUp && data.remaining() >= 256 ? data.readBytes(256) : null;
        Column column = groundUp ? new Column() : columns.computeIfAbsent(key, ignored -> new Column());
        if (!groundUp) {
            synchronized (column) {
                fillSections(column, mask, states, blockLight, sky);
            }
        } else {
            Column existing = columns.get(key);
            fillSections(column, mask, states, blockLight, sky);
            if (biomes != null) {
                column.biomes = biomes;
            }
            columns.put(key, column);
            columnVersion++;
            if (existing != null) {
                // Rewrites of a loaded column change every section.
                mask = 0xFFFF;
            }
        }
        for (int y = 0; y < SECTIONS; y++) {
            if (groundUp || (mask >> y & 1) != 0) {
                markAround(chunkX, y, chunkZ);
            }
        }
    }

    private void fillSections(Column column, int mask, char[][] states, byte[][] blockLight, byte[][] sky) {
        column.precipitation = null;
        int index = 0;
        for (int y = 0; y < SECTIONS; y++) {
            if ((mask >> y & 1) != 0) {
                column.sections[y] = new Section(states[index], blockLight[index], sky[index]);
                index++;
            }
        }
    }

    public void unload(int chunkX, int chunkZ) {
        if (columns.remove(columnKey(chunkX, chunkZ)) != null) {
            columnVersion++;
            for (int y = 0; y < SECTIONS; y++) {
                markAround(chunkX, y, chunkZ);
            }
        }
    }

    /** One block change. Light is left as the server last sent it (the client does not relight yet). */
    public void setBlock(int x, int y, int z, int state) {
        if (y < 0 || y > 255) {
            return;
        }
        Column column = column(x >> 4, z >> 4);
        if (column == null) {
            return;
        }
        synchronized (column) {
            Section section = column.sections[y >> 4];
            if (section == null) {
                section = Section.EMPTY_LIT;
            }
            char[] states = section.states.clone();
            states[cell(x, y, z)] = (char) state;
            column.sections[y >> 4] = new Section(states, section.blockLight, section.skyLight);
            column.precipitation = null;
        }
        changedAt(x, y, z);
    }

    /** Several changes in one column, as Multi Block Change carries them: (x | z << 4 | y << 8, state) pairs. */
    public void setBlocks(int chunkX, int chunkZ, int[] positions, int[] states, int count) {
        Column column = columns.get(columnKey(chunkX, chunkZ));
        if (column == null) {
            return;
        }
        synchronized (column) {
            Section[] copies = new Section[SECTIONS];
            for (int index = 0; index < count; index++) {
                int y = positions[index] >> 8 & 255;
                int sectionY = y >> 4;
                if (copies[sectionY] == null) {
                    Section section = column.sections[sectionY] == null ? Section.EMPTY_LIT : column.sections[sectionY];
                    copies[sectionY] = new Section(section.states.clone(), section.blockLight, section.skyLight);
                }
                int x = positions[index] & 15;
                int z = positions[index] >> 4 & 15;
                copies[sectionY].states[cell(x, y, z)] = (char) states[index];
            }
            for (int y = 0; y < SECTIONS; y++) {
                if (copies[y] != null) {
                    column.sections[y] = copies[y];
                }
            }
            column.precipitation = null;
        }
        for (int index = 0; index < count; index++) {
            changedAt(chunkX * 16 + (positions[index] & 15), positions[index] >> 8 & 255,
                    chunkZ * 16 + (positions[index] >> 4 & 15));
        }
    }

    public void clear() {
        columns.clear();
        changed.clear();
    }

    /** Sections whose meshes depend on a cell: its own, and neighbors whose halo (two blocks) reaches it. */
    private void changedAt(int x, int y, int z) {
        int sectionX = x >> 4;
        int sectionY = y >> 4;
        int sectionZ = z >> 4;
        int lowX = (x - 2) >> 4;
        int highX = (x + 2) >> 4;
        int lowY = Math.max(0, (y - 2) >> 4);
        int highY = Math.min(SECTIONS - 1, (y + 2) >> 4);
        int lowZ = (z - 2) >> 4;
        int highZ = (z + 2) >> 4;
        for (int cz = lowZ; cz <= highZ; cz++) {
            for (int cy = lowY; cy <= highY; cy++) {
                for (int cx = lowX; cx <= highX; cx++) {
                    changed.add(sectionKey(cx, cy, cz));
                }
            }
        }
    }

    /** A section and every section around it (column loads change their neighbors' halos). */
    private void markAround(int chunkX, int sectionY, int chunkZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                int y = sectionY + dy;
                if (y < 0 || y >= SECTIONS) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    changed.add(sectionKey(chunkX + dx, y, chunkZ + dz));
                }
            }
        }
    }

    /** Hands every section changed since the last call to {@code consumer} (duplicates possible). */
    public void drainChanged(LongConsumer consumer) {
        Long key;
        while ((key = changed.poll()) != null) {
            consumer.accept(key);
        }
    }
}
