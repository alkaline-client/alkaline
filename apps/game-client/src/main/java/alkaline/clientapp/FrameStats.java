package alkaline.clientapp;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Records presented-frame intervals, per-frame CPU recording time, the pre-input GPU wait, GPU frame time and
 * render-thread heap allocation, without allocating itself. Reports percentiles because averages hide the
 * stutter that matters for input feel.
 */
final class FrameStats {
    private static final int CAPACITY = 1 << 16;
    /** Two seconds, or {@code -Dalkaline.stats.warmupMs} to measure a scripted scene without loading and joining. */
    private static final long WARMUP_NANOS = Long.getLong("alkaline.stats.warmupMs", 2000L) * 1_000_000L;

    private final long[] intervals = new long[CAPACITY];
    private final long[] cpu = new long[CAPACITY];
    private final long[] gpu = new long[CAPACITY];
    private final long[] waits = new long[CAPACITY];
    private final long[] latencies = new long[CAPACITY];
    private int latencyCount;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final long startNanos = System.nanoTime();
    private long previousFrame;
    private int count;
    private int cpuCount;
    private int gpuCount;
    private int waitCount;
    private long firstMeasuredAllocation = -1;
    private int allocationFrames;

    private boolean warm() {
        return System.nanoTime() - startNanos >= WARMUP_NANOS;
    }

    /** Time spent waiting for a frame slot before input was sampled. */
    void waited(long nanos) {
        if (warm() && waitCount < CAPACITY) {
            waits[waitCount++] = nanos;
        }
    }

    /** CPU time from input to submission, and the latest completed GPU frame time (-1 when unavailable). */
    void recorded(long cpuNanos, long gpuNanos) {
        if (!warm()) {
            return;
        }
        if (cpuCount < CAPACITY) {
            cpu[cpuCount++] = cpuNanos;
        }
        if (gpuNanos >= 0 && gpuCount < CAPACITY) {
            gpu[gpuCount++] = gpuNanos;
        }
    }

    /** Time from sampling a frame's input to that frame reaching the screen (present wait only). */
    void shown(long nanos) {
        if (warm() && latencyCount < CAPACITY && nanos > 0) {
            latencies[latencyCount++] = nanos;
        }
    }

    /** Diagnostics: {@code -Dalkaline.stats.hitchMillis=20} logs every frame interval longer than that, with its time. */
    private static final long HITCH_NANOS = Long.getLong("alkaline.stats.hitchMillis", 0L) * 1_000_000L;

    void framePresented() {
        long now = System.nanoTime();
        if (HITCH_NANOS > 0 && previousFrame != 0 && now - previousFrame > HITCH_NANOS) {
            System.out.printf("Hitch: %.1f ms frame at %d ms%n", (now - previousFrame) / 1e6, (now - startNanos) / 1_000_000L);
        }
        if (now - startNanos < WARMUP_NANOS) {
            previousFrame = now;
            return;
        }
        if (firstMeasuredAllocation < 0) {
            firstMeasuredAllocation = threads.getCurrentThreadAllocatedBytes();
        } else {
            allocationFrames++;
        }
        if (previousFrame != 0 && count < CAPACITY) {
            intervals[count++] = now - previousFrame;
        }
        previousFrame = now;
    }

    String report() {
        if (count == 0) {
            return "Frame stats: no frames after warm-up.";
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - firstMeasuredAllocation;
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        double sum = 0;
        for (long interval : sorted) {
            sum += interval;
        }
        return String.format(
                "Frame stats (%d frames after %.1f s warm-up): mean %.3f ms (%.0f fps), p50 %.3f ms, p95 %.3f ms, "
                        + "p99 %.3f ms, max %.3f ms; render thread allocated %.1f bytes/frame%n"
                        + "  %s%n  %s%n  %s",
                count, WARMUP_NANOS / 1e9, sum / count / 1e6, 1e9 / (sum / count), percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), sorted[count - 1] / 1e6,
                allocationFrames == 0 ? 0.0 : allocated / (double) allocationFrames,
                line("CPU record", cpu, cpuCount), line("GPU frame ", gpu, gpuCount),
                line("slot wait ", waits, waitCount))
                + (latencyCount == 0 ? "" : "\n  " + line("input→screen", latencies, latencyCount));
    }

    private static String line(String label, long[] values, int size) {
        if (size == 0) {
            return label + ": n/a";
        }
        long[] sorted = Arrays.copyOf(values, size);
        Arrays.sort(sorted);
        double sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return String.format("%s: mean %.3f ms, p50 %.3f, p95 %.3f, p99 %.3f, max %.3f", label, sum / size / 1e6,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99), sorted[size - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double fraction) {
        int index = (int) Math.min(sorted.length - 1, Math.round(fraction * (sorted.length - 1)));
        return sorted[index] / 1e6;
    }
}
