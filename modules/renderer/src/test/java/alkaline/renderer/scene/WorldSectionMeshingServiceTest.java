package alkaline.renderer.scene;

import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.world.SectionMesher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSectionMeshingServiceTest {
    private static final AtlasRegion FULL = new AtlasRegion(0f, 0f, 1f, 1f);
    private static final WorldMaterialTable MATERIALS = new WorldMaterialTable(
            new WorldMaterial[]{null, new WorldMaterial(1, 3, FULL, 1f, 1f, 1f)},
            new MaterialPass[]{null, MaterialPass.OPAQUE}, new boolean[]{false, true});
    private static final SectionMesher MESHER = new SectionMesher();

    @Test
    void compilesSectionsOnDifferentWorkerThreads() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger workerIds = new AtomicInteger();
        WorldSectionMeshingService service = new WorldSectionMeshingService(2, 2, (input, materials, mesher) -> {
            workerIds.incrementAndGet();
            entered.countDown();
            await(release);
            return WorldMeshCompiler.compile(input, materials, mesher);
        });
        try {
            CompletableFuture<WorldMesh> one = service.compileAsync(section(0), MATERIALS, MESHER);
            CompletableFuture<WorldMesh> two = service.compileAsync(section(2), MATERIALS, MESHER);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(36, one.get(2, TimeUnit.SECONDS).indexCount());
            assertEquals(36, two.get(2, TimeUnit.SECONDS).indexCount());
            assertEquals(2, workerIds.get());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void rejectsSubmissionsWhenWorkersAndBoundedQueueAreFull() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorldSectionMeshingService service = new WorldSectionMeshingService(1, 1, (input, materials, mesher) -> {
            running.countDown();
            await(release);
            return WorldMeshCompiler.compile(input, materials, mesher);
        });
        CompletableFuture<WorldMesh> active = service.compileAsync(section(0), MATERIALS, MESHER);
        assertTrue(running.await(2, TimeUnit.SECONDS));
        CompletableFuture<WorldMesh> queued = service.compileAsync(section(1), MATERIALS, MESHER);
        CompletableFuture<WorldMesh> rejected = service.compileAsync(section(2), MATERIALS, MESHER);
        try {
            assertEquals(1, service.queuedTaskCount());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rejected.get(2, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
            release.countDown();
            assertEquals(36, active.get(2, TimeUnit.SECONDS).indexCount());
            assertEquals(36, queued.get(2, TimeUnit.SECONDS).indexCount());
        } finally {
            release.countDown();
            service.close();
        }
        assertThrows(ExecutionException.class,
                () -> service.compileAsync(section(3), MATERIALS, MESHER).get(2, TimeUnit.SECONDS));
    }

    @Test
    void compilesEntityBatchesUsingTheSameWorkerPool() throws Exception {
        EntityModelSnapshot model = new EntityModelSnapshot(4, FULL,
                new float[]{0, 0, 0, 0, 0, 1, 1, 1, 1,
                        1, 0, 0, 1, 0, 1, 1, 1, 1,
                        0, 1, 0, 0, 1, 1, 1, 1, 1},
                new int[]{0, 1, 2});
        EntityInput entity = new EntityInput(8, model, EntityPose.identity(), MaterialPass.CUTOUT);
        RgbaImage texture = new RgbaImage(1, 1, new byte[]{-1, -1, -1, -1});
        try (WorldSectionMeshingService service = new WorldSectionMeshingService(1, 1)) {
            List<EntityBatchCompiler.EntityBatch> batches = service
                    .compileEntitiesAsync(List.of(entity), ignored -> texture).get(2, TimeUnit.SECONDS);
            assertEquals(1, batches.size());
            assertEquals(MaterialPass.CUTOUT, batches.get(0).pass());
            assertEquals(3, batches.get(0).mesh().indexCount());
        }
    }

    @Test
    void reportsCompilerFailureThroughTheReturnedFuture() {
        IllegalArgumentException expected = new IllegalArgumentException("bad section");
        try (WorldSectionMeshingService service = new WorldSectionMeshingService(1, 1,
                (input, materials, mesher) -> { throw expected; })) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> service.compileAsync(section(0), MATERIALS, MESHER).get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() == expected);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker timed out.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker interrupted.", interrupted);
        }
    }

    private static WorldSectionInput section(int x) {
        char[] materials = new char[27];
        materials[13] = 1;
        return new WorldSectionInput(x, 0, 0, 1, 1, 1, materials);
    }
}
