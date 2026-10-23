package alkaline.renderer.world;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Back-to-front order for a section's translucent quads, as an index list.
 *
 * <p>Each quad belongs to a block cell. If a quad in cell A hides part of a quad in cell B, every point of A lies
 * between the camera and B on each axis, so A is no farther from the camera's cell than B on any axis. Drawing
 * cells by decreasing Manhattan distance |dx| + |dy| + |dz| from the camera's cell therefore draws every hidden
 * quad before the quad that hides it. Quads in one cell keep emission order, which is correct for convex blocks
 * (their visible faces never overlap). Unlike distance-to-center sorting this is exact, and unlike a comparison
 * sort it is a counting sort over at most 52 buckets: linear in the quad count.
 *
 * <p>The order only changes when the camera's cell changes. Positions outside the section along an axis order
 * the cells identically (the distance gains a constant), so the camera cell is clamped to [-1, 16] per axis:
 * {@link #cameraKey} is the only input that can change the result, and a section needs re-sorting only when
 * its key changes. Sections far from the camera's slab on every axis never re-sort.
 *
 * <p>Not thread-safe: one instance per thread. Reuses its scratch arrays.
 */
public final class TranslucentSorter {
    private static final int BUCKETS = 3 * 17 + 1;
    private final int[] starts = new int[BUCKETS + 1];
    private int[] keys = new int[256];
    private int[] order = new int[256];

    /** The camera's block cell relative to a section, clamped per axis to [-1, 16] and packed in 15 bits. */
    public static int cameraKey(double cameraX, double cameraY, double cameraZ, int sectionX, int sectionY,
                                int sectionZ) {
        int x = clamp((int) Math.floor(cameraX) - sectionX * 16);
        int y = clamp((int) Math.floor(cameraY) - sectionY * 16);
        int z = clamp((int) Math.floor(cameraZ) - sectionZ * 16);
        return (x + 1) | (y + 1) << 5 | (z + 1) << 10;
    }

    private static int clamp(int value) {
        return Math.max(-1, Math.min(16, value));
    }

    /**
     * Writes six uint32 indices per quad (vertices {@code 4q + 0, 1, 2, 2, 3, 0}, relative to the first
     * translucent vertex) at {@code byteOffset}, farthest cells first.
     *
     * @param cells one cell per quad, {@code x | y << 4 | z << 8} (see {@link ChunkMesh#translucentCells()}), or
     *              empty for a section whose quads are already in a camera-independent order
     * @param count the section's translucent quad count
     */
    public void sort(short[] cells, int count, int cameraKey, ByteBuffer target, int byteOffset) {
        if (cells.length == 0) {
            for (int quad = 0; quad < count; quad++) {
                put(target, byteOffset + quad * 24, quad * 4);
            }
            return;
        }
        if (cells.length != count) {
            throw new IllegalArgumentException("One cell per translucent quad is required.");
        }
        if (keys.length < count) {
            keys = new int[Integer.highestOneBit(count) << 1];
            order = new int[keys.length];
        }
        int cx = (cameraKey & 31) - 1;
        int cy = (cameraKey >> 5 & 31) - 1;
        int cz = (cameraKey >> 10 & 31) - 1;
        Arrays.fill(starts, 0);
        for (int quad = 0; quad < count; quad++) {
            int cell = cells[quad];
            // Far first: bucket 0 holds the largest distance.
            int key = BUCKETS - 1 - (Math.abs((cell & 15) - cx) + Math.abs((cell >> 4 & 15) - cy)
                    + Math.abs((cell >> 8 & 15) - cz));
            keys[quad] = key;
            starts[key + 1]++;
        }
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            starts[bucket + 1] += starts[bucket];
        }
        for (int quad = 0; quad < count; quad++) {
            order[starts[keys[quad]]++] = quad;
        }
        int position = byteOffset;
        for (int index = 0; index < count; index++) {
            put(target, position, order[index] * 4);
            position += 24;
        }
    }

    private static void put(ByteBuffer target, int position, int base) {
        target.putInt(position, base).putInt(position + 4, base + 1).putInt(position + 8, base + 2)
                .putInt(position + 12, base + 2).putInt(position + 16, base + 3).putInt(position + 20, base);
    }
}
