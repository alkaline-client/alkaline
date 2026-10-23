package alkaline.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLoopTest {
    @Test
    void publishesTickSnapshotsAndStopsCleanly() throws Exception {
        GameLoop<Integer> loop = new GameLoop<>(0, (previous, tick) -> previous + 1);
        loop.start();
        try {
            long startedAt = System.nanoTime();
            long deadline = startedAt + TimeUnit.SECONDS.toNanos(2);
            while (loop.snapshot().tick() < 3 && System.nanoTime() < deadline) Thread.sleep(2L);
            GameLoop.TickSnapshot<Integer> snapshot = loop.snapshot();
            assertTrue(snapshot.tick() >= 3);
            assertEquals(snapshot.tick(), snapshot.state().longValue());
            assertTrue(snapshot.publishedAtNanos() - startedAt >= GameLoop.TICK_INTERVAL_NANOS);
        } finally {
            loop.close();
        }
        assertTrue(loop.isTerminated());
        assertFalse(loop.isRunning());
    }

    @Test
    void exposesHandlerFailureAndTerminates() throws Exception {
        IllegalStateException expected = new IllegalStateException("tick failed");
        CountDownLatch called = new CountDownLatch(1);
        GameLoop<Integer> loop = new GameLoop<>(0, (previous, tick) -> {
            called.countDown();
            throw expected;
        });
        loop.start();
        assertTrue(called.await(2, TimeUnit.SECONDS));
        assertTrue(loop.awaitTermination(Duration.ofSeconds(2)));
        assertSame(expected, loop.failure());
        assertTrue(loop.isTerminated());
        loop.close();
    }

    @Test
    void rejectsRestartAndCanStopBeforeStart() throws Exception {
        GameLoop<Integer> neverStarted = new GameLoop<>(0, (previous, tick) -> previous + 1);
        neverStarted.stop();
        assertTrue(neverStarted.isTerminated());
        assertTrue(neverStarted.awaitTermination(Duration.ZERO));
        assertThrows(IllegalStateException.class, neverStarted::start);

        GameLoop<Integer> started = new GameLoop<>(0, (previous, tick) -> previous + 1);
        started.start();
        started.stop();
        assertThrows(IllegalStateException.class, started::start);
        started.close();
    }

    @Test
    void handlerCannotPublishNullState() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        GameLoop<Integer> loop = new GameLoop<>(0, (previous, tick) -> {
            called.countDown();
            return null;
        });
        loop.start();
        try {
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertTrue(loop.awaitTermination(Duration.ofSeconds(2)));
            assertNotNull(loop.failure());
            assertTrue(loop.failure() instanceof NullPointerException);
        } finally {
            loop.close();
        }
    }
}
