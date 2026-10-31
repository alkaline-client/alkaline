package alkaline.renderer.vulkan.world;

import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.ChunkMesh;
import alkaline.renderer.world.ChunkMesher;
import alkaline.renderer.world.LeafCulling;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the sections around a point loaded: worker threads fill block ids from a {@link BlockSource} and mesh
 * them, nearest first; the render thread hands finished meshes to the {@link WorldRenderer} and drops sections
 * that left the radius. Meshing never runs on the render thread, and a section is rebuilt only when it enters
 * the radius or a meshing option changes.
 */
public final class SectionStreamer implements AutoCloseable {
    private record Result(long generation, long version, long key, ChunkMesh mesh) {
    }

    private final BlockSource source;
    private final BlockPalette palette;
    private final WorldRenderer renderer;
    private final ThreadPoolExecutor workers;
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);
    private final ThreadLocal<char[]> buffers = ThreadLocal.withInitial(() -> new char[BlockSource.VOLUME]);
    private final ThreadLocal<byte[]> lightBuffers = ThreadLocal.withInitial(() -> new byte[BlockSource.VOLUME]);
    private final ThreadLocal<byte[]> biomeBuffers = ThreadLocal.withInitial(() -> new byte[BlockSource.COLUMNS]);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong meshNanos = new AtomicLong();
    private final AtomicLong meshed = new AtomicLong();
    private final Set<Long> wanted = new HashSet<>();
    private final Set<Long> requested = new HashSet<>();
    /** Latest re-mesh request per section; an older job finishing late must not replace a newer mesh. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong nextVersion = new AtomicLong();
    /**
     * Sections with a re-mesh queued and not yet started. A section changes many times while the server sends its
     * chunk and lighting; one queued job reads the latest blocks when it starts, so further changes before then
     * need no job of their own.
     */
    private final Set<Long> queuedRemesh = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Jobs per section of the current generation not yet drained (render thread), for {@link #settled}. */
    private final java.util.HashMap<Long, Integer> unfinished = new java.util.HashMap<>();
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int radius = -1;
    private volatile boolean ambientOcclusion;
    private volatile LeafCulling leaves = LeafCulling.OFF;
    private volatile boolean closed;

    public SectionStreamer(BlockSource source, BlockPalette palette, WorldRenderer renderer) {
        this.source = Objects.requireNonNull(source, "source");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        AtomicInteger names = new AtomicInteger();
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "alkaline-section-mesher-" + names.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
    }

    /**
     * Re-centers the loaded area (section coordinates of the camera column) and applies options. Cheap when
     * nothing changed; call once per frame on the render thread.
     */
    public void update(int sectionX, int sectionZ, int radiusChunks, boolean ambientOcclusion) {
        update(sectionX, sectionZ, radiusChunks, ambientOcclusion, LeafCulling.OFF);
    }

    /** As {@link #update(int, int, int, boolean)}, with the faces between leaves to leave out. */
    public void update(int sectionX, int sectionZ, int radiusChunks, boolean ambientOcclusion, LeafCulling leaves) {
        boolean remesh = ambientOcclusion != this.ambientOcclusion || leaves != this.leaves;
        if (sectionX != centerX || sectionZ != centerZ || radiusChunks != radius || remesh) {
            renderer.configure(source.minSectionY(), source.maxSectionY(), radiusChunks + 1);
            centerX = sectionX;
            centerZ = sectionZ;
            radius = radiusChunks;
            this.ambientOcclusion = ambientOcclusion;
            this.leaves = leaves;
            reschedule(remesh);
        }
        drain();
    }

    private void reschedule(boolean remesh) {
        wanted.clear();
        List<long[]> additions = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius + radius) {
                    continue;
                }
                for (int y = source.minSectionY(); y <= source.maxSectionY(); y++) {
                    long key = WorldRenderer.key(centerX + dx, y, centerZ + dz);
                    wanted.add(key);
                    if (!requested.contains(key)) {
                        additions.add(new long[]{centerX + dx, y, centerZ + dz, dx * dx + dz * dz});
                    }
                }
            }
        }
        // Drop sections that left the radius.
        List<Long> stale = new ArrayList<>();
        for (long key : requested) {
            if (!wanted.contains(key)) {
                stale.add(key);
            }
        }
        for (long key : stale) {
            requested.remove(key);
            renderer.remove(unpackX(key), unpackY(key), unpackZ(key));
        }
        if (remesh) {
            // Old meshes stay on screen until their replacements arrive; queued old-generation jobs are dropped.
            generation.incrementAndGet();
            workers.getQueue().clear();
            queuedRemesh.clear();
            unfinished.clear();
            inFlight.set(workers.getActiveCount());
            requested.clear();
            additions.clear();
            for (long key : wanted) {
                int x = unpackX(key);
                int z = unpackZ(key);
                additions.add(new long[]{x, unpackY(key), z,
                        (long) (x - centerX) * (x - centerX) + (long) (z - centerZ) * (z - centerZ)});
            }
        }
        long current = generation.get();
        for (long[] section : additions) {
            requested.add(WorldRenderer.key((int) section[0], (int) section[1], (int) section[2]));
            renderer.request((int) section[0], (int) section[1], (int) section[2]);
            unfinished.merge(WorldRenderer.key((int) section[0], (int) section[1], (int) section[2]), 1, Integer::sum);
            inFlight.incrementAndGet();
            workers.execute(new Job((int) section[0], (int) section[1], (int) section[2], (int) section[3], current));
        }
    }

    private void drain() {
        long current = generation.get();
        Result result;
        while ((result = results.poll()) != null) {
            ChunkMesh mesh = result.mesh();
            long key = result.key();
            if (result.generation() == current) {
                unfinished.computeIfPresent(key, (ignored, count) -> count > 1 ? count - 1 : null);
            }
            if (mesh == null) {
                continue; // a job that had nothing to do
            }
            Long latest = versions.get(key);
            if (result.generation() == current && wanted.contains(key)
                    && (latest == null || result.version() >= latest)) {
                renderer.submit(mesh);
            }
        }
    }

    /**
     * Re-meshes a section whose blocks or light changed (a no-op outside the loaded area). The old mesh stays
     * on screen until the new one arrives. Call on the render thread.
     */
    public void invalidate(int x, int y, int z) {
        long key = WorldRenderer.key(x, y, z);
        if (closed || !requested.contains(key)) {
            return;
        }
        versions.put(key, nextVersion.incrementAndGet());
        if (!queuedRemesh.add(key)) {
            return; // the queued job will see this change too
        }
        unfinished.merge(key, 1, Integer::sum);
        inFlight.incrementAndGet();
        int distance = (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ);
        workers.execute(new Job(x, y, z, distance, generation.get(), true));
    }

    /**
     * Whether the section's newest blocks are meshed and on the GPU: no job is queued or running for it and no
     * mesh is waiting to be uploaded. Render thread.
     */
    public boolean settled(int x, int y, int z) {
        return !unfinished.containsKey(WorldRenderer.key(x, y, z)) && renderer.settled(x, y, z);
    }

    /** Sections still being generated, meshed, or uploaded. */
    public int pending() {
        return Math.max(0, inFlight.get()) + results.size() + renderer.pendingUploads();
    }

    /** Average meshing time per section in microseconds, including block generation. */
    public double averageMeshMicros() {
        long count = meshed.get();
        return count == 0 ? 0 : meshNanos.get() / 1000.0 / count;
    }

    private final class Job implements Runnable, Comparable<Job> {
        private final int x;
        private final int y;
        private final int z;
        private final int distance;
        private final long jobGeneration;
        /** A re-mesh after a change, rather than the section's first mesh. */
        private final boolean remesh;

        Job(int x, int y, int z, int distance, long jobGeneration) {
            this(x, y, z, distance, jobGeneration, false);
        }

        Job(int x, int y, int z, int distance, long jobGeneration, boolean remesh) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
            this.jobGeneration = jobGeneration;
            this.remesh = remesh;
        }

        @Override
        public int compareTo(Job other) {
            int order = Integer.compare(distance, other.distance);
            return order != 0 ? order : Integer.compare(other.y, y);
        }

        @Override
        public void run() {
            long key = WorldRenderer.key(x, y, z);
            ChunkMesh mesh = null;
            long version = 0L;
            try {
                if (remesh) {
                    queuedRemesh.remove(key);
                }
                if (closed || jobGeneration != generation.get()) {
                    return;
                }
                // Read after leaving the queue: every change up to here is in the blocks this job meshes.
                version = remesh ? versions.getOrDefault(key, 0L) : 0L;
                long start = System.nanoTime();
                char[] ids = buffers.get();
                byte[] light = lightBuffers.get();
                source.fill(x, y, z, ids, light);
                byte[] biomes = null;
                if (palette.needsBiomes()) {
                    biomes = biomeBuffers.get();
                    source.fillBiomes(x, z, biomes);
                }
                mesh = meshers.get().mesh(x, y, z, ids, light, biomes, palette, ambientOcclusion, leaves);
                meshNanos.addAndGet(System.nanoTime() - start);
                meshed.incrementAndGet();
            } catch (RuntimeException failure) {
                System.err.println("Section " + x + "," + y + "," + z + " failed: " + failure);
            } finally {
                // Every job reports back, so the render thread can count it finished.
                results.add(new Result(jobGeneration, version, key, mesh));
                inFlight.decrementAndGet();
            }
        }
    }

    private static int unpackX(long key) {
        return (int) (key << 0 >> 42);
    }

    private static int unpackZ(long key) {
        return (int) (key << 22 >> 42);
    }

    private static int unpackY(long key) {
        return (int) (key << 44 >> 44);
    }

    @Override
    public void close() {
        closed = true;
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        results.clear();
    }
}
