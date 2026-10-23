package alkaline.client.hud;

/**
 * The HUD's FPS counter: presented frames counted over half-second windows, so the number is steady enough to
 * read and changes at most twice a second. Allocation-free; call {@link #frame} once per presented frame on
 * the render thread. The in-world HUD shows {@link #fps()} under the {@code client.fps} value key when the
 * {@code interface.fps_counter} setting is on; menus never show it.
 */
public final class FrameRateMeter {
    private static final long WINDOW_NANOS = 500_000_000L;

    private long windowStart;
    private int windowFrames;
    private int fps;

    /** Records a presented frame; returns true when {@link #fps()} changed. */
    public boolean frame(long nowNanos) {
        if (windowStart == 0L) {
            windowStart = nowNanos;
        }
        windowFrames++;
        long elapsed = nowNanos - windowStart;
        if (elapsed < WINDOW_NANOS) {
            return false;
        }
        int next = (int) Math.round(windowFrames * 1e9 / elapsed);
        windowStart = nowNanos;
        windowFrames = 0;
        if (next == fps) {
            return false;
        }
        fps = next;
        return true;
    }

    /** Frames per second over the last complete window, 0 before the first one. */
    public int fps() {
        return fps;
    }
}
