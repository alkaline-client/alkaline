package alkaline.client;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Publishes immutable simulation snapshots from a dedicated fixed-rate tick thread. */
public final class GameLoop<S> implements AutoCloseable {
    public static final int TICKS_PER_SECOND = 20;
    public static final long TICK_INTERVAL_NANOS = 1_000_000_000L / TICKS_PER_SECOND;
    private static final long MAX_CATCH_UP_TICKS = 5;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final TickHandler<S> handler;
    private final AtomicReference<TickSnapshot<S>> snapshot;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread worker;

    public GameLoop(S initialState, TickHandler<S> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.snapshot = new AtomicReference<>(new TickSnapshot<>(0, System.nanoTime(),
                Objects.requireNonNull(initialState, "initialState")));
        this.worker = new Thread(this::run, "alkaline-game-tick");
        this.worker.setDaemon(true);
    }

    /** Starts the loop once. Subsequent start calls are rejected, even after shutdown. */
    public synchronized void start() {
        if (!lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.RUNNING)) {
            throw new IllegalStateException("The game loop can only be started once.");
        }
        worker.start();
    }

    /** Requests asynchronous shutdown and wakes the tick thread if it is waiting. */
    public void stop() {
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.NEW) {
            if (lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.TERMINATED)) return;
            current = lifecycle.get();
        }
        if (current == Lifecycle.RUNNING && lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.STOPPING)) {
            worker.interrupt();
        }
    }

    public TickSnapshot<S> snapshot() {
        return snapshot.get();
    }

    public boolean isRunning() {
        return lifecycle.get() == Lifecycle.RUNNING;
    }

    public boolean isTerminated() {
        return lifecycle.get() == Lifecycle.TERMINATED;
    }

    /** Returns the tick failure, if the handler stopped the loop with an exception. */
    public Throwable failure() {
        return failure.get();
    }

    /** Waits outside the render loop for the worker to terminate. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Timeout must not be negative.");
        if (worker.getState() == Thread.State.NEW) return lifecycle.get() == Lifecycle.TERMINATED;
        if (!worker.isAlive()) return true;
        long nanos = timeout.toNanos();
        if (nanos == 0) return false;
        long millis = nanos / 1_000_000L;
        int extraNanos = (int) (nanos % 1_000_000L);
        worker.join(millis, extraNanos);
        return !worker.isAlive();
    }

    @Override
    public void close() {
        stop();
        try {
            if (!awaitTermination(CLOSE_TIMEOUT)) {
                throw new IllegalStateException("The game tick thread did not stop within " + CLOSE_TIMEOUT + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the game tick thread to stop.", interrupted);
        }
    }

    private void run() {
        long nextTickDeadline = System.nanoTime() + TICK_INTERVAL_NANOS;
        long tick = 0;
        try {
            while (lifecycle.get() == Lifecycle.RUNNING) {
                long remaining = nextTickDeadline - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(this, remaining);
                    if (Thread.interrupted() && lifecycle.get() != Lifecycle.RUNNING) break;
                    continue;
                }

                TickSnapshot<S> previous = snapshot.get();
                S nextState = Objects.requireNonNull(handler.tick(previous.state(), ++tick),
                        "tick handler returned null state");
                snapshot.set(new TickSnapshot<>(tick, System.nanoTime(), nextState));
                nextTickDeadline += TICK_INTERVAL_NANOS;
                if (System.nanoTime() - nextTickDeadline > TICK_INTERVAL_NANOS * MAX_CATCH_UP_TICKS) {
                    nextTickDeadline = System.nanoTime() + TICK_INTERVAL_NANOS;
                }
            }
        } catch (InterruptedException interrupted) {
            if (lifecycle.get() == Lifecycle.RUNNING) failure.set(interrupted);
        } catch (Throwable tickFailure) {
            failure.set(tickFailure);
        } finally {
            lifecycle.set(Lifecycle.TERMINATED);
        }
    }

    @FunctionalInterface
    public interface TickHandler<S> {
        S tick(S previousState, long tick) throws Exception;
    }

    /** The state reference must itself be immutable after publication. */
    public record TickSnapshot<S>(long tick, long publishedAtNanos, S state) {
        public TickSnapshot {
            if (tick < 0) throw new IllegalArgumentException("Tick number must not be negative.");
            Objects.requireNonNull(state, "state");
        }
    }

    private enum Lifecycle { NEW, RUNNING, STOPPING, TERMINATED }
}
