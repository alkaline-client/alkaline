package alkaline.renderer.scene;

import alkaline.renderer.world.SectionMesher;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs immutable CPU scene inputs on a fixed-size worker pool with bounded submission. */
public final class WorldSectionMeshingService implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final ThreadPoolExecutor workers;
    private final SectionCompiler compiler;
    private volatile boolean closed;

    public WorldSectionMeshingService() {
        this(defaultWorkerCount(), defaultQueueCapacity());
    }

    public WorldSectionMeshingService(int workerCount, int maximumQueuedTasks) {
        this(workerCount, maximumQueuedTasks, WorldMeshCompiler::compile);
    }

    WorldSectionMeshingService(int workerCount, int maximumQueuedTasks, SectionCompiler compiler) {
        if (workerCount <= 0 || maximumQueuedTasks <= 0) {
            throw new IllegalArgumentException("Worker and queue capacities must be positive.");
        }
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "alkaline-scene-worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumQueuedTasks), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public static int defaultQueueCapacity() {
        return Math.max(2, Math.min(256, defaultWorkerCount() * 2));
    }

    /** Schedules one section rebuild; saturation fails the future without caller-thread work. */
    public CompletableFuture<WorldMesh> compileAsync(WorldSectionInput input,
                                                      WorldMaterialTable materials,
                                                      SectionMesher mesher) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(mesher, "mesher");
        CompletableFuture<WorldMesh> result = new CompletableFuture<>();
        return submit(result, () -> compiler.compile(input, materials, mesher));
    }

    /** Compiles an immutable entity input list on the same bounded CPU worker pool. */
    public CompletableFuture<List<EntityBatchCompiler.EntityBatch>> compileEntitiesAsync(
            List<EntityInput> entities, EntityBatchCompiler.TextureResolver textures) {
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(textures, "textures");
        List<EntityInput> snapshot = List.copyOf(entities);
        CompletableFuture<List<EntityBatchCompiler.EntityBatch>> result = new CompletableFuture<>();
        return submit(result, () -> EntityBatchCompiler.compile(snapshot, textures));
    }

    private <T> CompletableFuture<T> submit(CompletableFuture<T> result, TaskSupplier<T> task) {
        if (closed) {
            result.completeExceptionally(new IllegalStateException("The scene compilation service is closed."));
            return result;
        }
        try {
            workers.execute(new PendingTask<>(result, task));
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    public int workerCount() {
        return workers.getCorePoolSize();
    }

    public int queuedTaskCount() {
        return workers.getQueue().size();
    }

    /** Waits for queued CPU tasks to finish; call during shutdown, never from a frame callback. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                failPending(workers.shutdownNow(), new IllegalStateException("Scene compilation stopped before execution."));
                if (!workers.awaitTermination(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("Scene compilation workers did not stop within the shutdown timeout.");
                }
            }
        } catch (InterruptedException interrupted) {
            failPending(workers.shutdownNow(), interrupted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping scene compilation workers.", interrupted);
        }
    }

    private static void failPending(List<Runnable> pending, Throwable failure) {
        for (Runnable runnable : pending) {
            if (runnable instanceof PendingTask<?> task) task.fail(failure);
        }
    }

    @FunctionalInterface
    interface SectionCompiler {
        WorldMesh compile(WorldSectionInput input, WorldMaterialTable materials, SectionMesher mesher);
    }

    @FunctionalInterface
    private interface TaskSupplier<T> {
        T get() throws Exception;
    }

    private static final class PendingTask<T> implements Runnable {
        private final CompletableFuture<T> result;
        private final TaskSupplier<T> task;

        private PendingTask(CompletableFuture<T> result, TaskSupplier<T> task) {
            this.result = result;
            this.task = task;
        }

        @Override
        public void run() {
            if (result.isCancelled()) return;
            try {
                result.complete(Objects.requireNonNull(task.get(), "compiled scene result"));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
