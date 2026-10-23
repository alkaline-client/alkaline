package alkaline.renderer.vulkan.world;

import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.ChunkMesh;
import alkaline.renderer.world.ChunkMesher;
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
    private record Result(long generation, long version, ChunkMesh mesh) {
    }

    private final BlockSource source;
    private final BlockPalette palette;
    private final WorldRenderer renderer;
    private final ThreadPoolExecutor workers;
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);
    private final ThreadLocal<char[]> buffers = ThreadLocal.withInitial(() -> new char[BlockSource.VOLUME]);
    private final ThreadLocal<byte[]> lightBuffers = ThreadLocal.withInitial(() -> new byte[BlockSource.VOLUME]);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong meshNanos = new AtomicLong();
    private final AtomicLong meshed = new AtomicLong();
    private final Set<Long> wanted = new HashSet<>();
    private final Set<Long> requested = new HashSet<>();
    /** Latest re-mesh request per section; an older job finishing late must not replace a newer mesh. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong nextVersion = new AtomicLong();
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private int radius = -1;
    private volatile boolean ambientOcclusion;
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
        boolean remesh = ambientOcclusion != this.ambientOcclusion;
        if (sectionX != centerX || sectionZ != centerZ || radiusChunks != radius || remesh) {
            renderer.configure(source.minSectionY(), source.maxSectionY(), radiusChunks + 1);
            centerX = sectionX;
            centerZ = sectionZ;
            radius = radiusChunks;
            this.ambientOcclusion = ambientOcclusion;
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
            inFlight.incrementAndGet();
            workers.execute(new Job((int) section[0], (int) section[1], (int) section[2], (int) section[3], current));
        }
    }

    private void drain() {
        long current = generation.get();
        Result result;
        while ((result = results.poll()) != null) {
            ChunkMesh mesh = result.mesh();
            long key = WorldRenderer.key(mesh.sectionX(), mesh.sectionY(), mesh.sectionZ());
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
        long version = nextVersion.incrementAndGet();
        versions.put(key, version);
        inFlight.incrementAndGet();
        int distance = (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ);
        workers.execute(new Job(x, y, z, distance, generation.get(), version));
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
        private final long version;

        Job(int x, int y, int z, int distance, long jobGeneration) {
            this(x, y, z, distance, jobGeneration, 0);
        }

        Job(int x, int y, int z, int distance, long jobGeneration, long version) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
            this.jobGeneration = jobGeneration;
            this.version = version;
        }

        @Override
        public int compareTo(Job other) {
            int order = Integer.compare(distance, other.distance);
            return order != 0 ? order : Integer.compare(other.y, y);
        }

        @Override
        public void run() {
            try {
                if (closed || jobGeneration != generation.get()) {
                    return;
                }
                long start = System.nanoTime();
                char[] ids = buffers.get();
                byte[] light = lightBuffers.get();
                source.fill(x, y, z, ids, light);
                ChunkMesh mesh = meshers.get().mesh(x, y, z, ids, light, palette, ambientOcclusion);
                meshNanos.addAndGet(System.nanoTime() - start);
                meshed.incrementAndGet();
                results.add(new Result(jobGeneration, version, mesh));
            } catch (RuntimeException failure) {
                System.err.println("Section " + x + "," + y + "," + z + " failed: " + failure);
            } finally {
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
