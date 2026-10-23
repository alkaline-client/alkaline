package alkaline.clientapp;

import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.util.concurrent.locks.LockSupport;

/**
 * Paces V-Sync to the display so input is sampled as late as possible. Plain FIFO lets the game run ahead until the
 * swapchain is full; the frame then waits one or two refreshes in the queue, and the input it was built from ages
 * with it. With VK_KHR_present_wait2 the loop instead waits until the last frame is on screen, then sleeps until
 * {@code margin} before the next refresh, samples input, and presents just in time for that refresh.
 *
 * <p>The compositor's latch deadline is unknown, so the margin is learned: it shrinks slowly until a frame misses
 * its refresh (shown two periods after the one before), then settles one millisecond above where that happened.
 * Later misses (a hitch, a busier compositor) push it up again, never past three quarters of a period.
 *
 * <p>Some compositors (COSMIC among them) latch the next frame right after a refresh, before the present-wait
 * signal for the frame just shown arrives; a frame started on that signal always lands a refresh late. When frames
 * keep missing at the largest margin, the pacer waits for the frame before the last one instead, keeping one frame
 * queued: still about one refresh of latency less than plain FIFO's full queue.
 */
final class VsyncPacer {
    private static final long WAIT_TIMEOUT_NANOS = 100_000_000L;
    private static final long SPIN_NANOS = 200_000L;
    private static final long START_MARGIN_NANOS = 4_000_000L;
    private static final long MIN_MARGIN_NANOS = 700_000L;
    private static final long SHRINK_NANOS = 5_000L;
    private static final long SETTLE_NANOS = 1_000_000L;
    private static final long BUMP_NANOS = 500_000L;

    /** How many presented frames may still be queued when the next one starts: 0, or 1 on early-latching compositors. */
    private int lag;
    private int consecutiveMisses;
    private long margin = START_MARGIN_NANOS;
    private boolean settled;
    /** The present id the last pace waited for. */
    private long waited;
    private long lastShown;
    private long misses;
    private long paced;

    /**
     * Waits for the last presented frame to reach the screen, then until the start of the next frame. Returns
     * false (and does not wait) when there is nothing to wait for, for example before the first frame.
     */
    boolean pace(VulkanRenderer renderer, float refreshHz) {
        long id = renderer.lastPresentId() - lag;
        if (refreshHz <= 0f || id <= 0 || !renderer.waitForPresent(id, WAIT_TIMEOUT_NANOS)) {
            lastShown = 0L;
            return false;
        }
        waited = id;
        long shown = System.nanoTime();
        long period = (long) (1_000_000_000.0 / refreshHz);
        if (lastShown != 0L) {
            long gap = shown - lastShown;
            if (gap > period * 3 / 2 && gap < period * 5 / 2) {
                // Shown a refresh late: the frame started too close to the compositor's deadline.
                misses++;
                consecutiveMisses++;
                margin = settled ? margin + BUMP_NANOS : margin + SETTLE_NANOS;
                settled = true;
                if (lag == 0 && consecutiveMisses >= 10 && margin >= period * 3 / 4) {
                    // Every frame lands a refresh late however early it starts: keep one frame queued.
                    lag = 1;
                    margin = START_MARGIN_NANOS;
                    settled = false;
                    consecutiveMisses = 0;
                }
            } else {
                consecutiveMisses = 0;
                if (!settled) {
                    margin -= SHRINK_NANOS;
                }
            }
        }
        margin = Math.max(MIN_MARGIN_NANOS, Math.min(margin, period * 3 / 4));
        lastShown = shown;
        paced++;
        long start = shown + period - margin;
        long remaining = start - System.nanoTime();
        if (remaining > SPIN_NANOS) {
            LockSupport.parkNanos(remaining - SPIN_NANOS);
        }
        while (System.nanoTime() < start) {
            Thread.onSpinWait();
        }
        return true;
    }

    /** The present id the last {@link #pace} waited for. */
    long waited() {
        return waited;
    }

    /** Frames left queued when the next starts (diagnostics). */
    int lag() {
        return lag;
    }

    /** The current lead before the refresh, for diagnostics. */
    long marginNanos() {
        return margin;
    }

    long misses() {
        return misses;
    }

    long paced() {
        return paced;
    }
}
