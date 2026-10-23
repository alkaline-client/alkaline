package alkaline.client.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameRateMeterTest {
    @Test
    void reportsFramesPerSecondPerHalfSecondWindow() {
        FrameRateMeter meter = new FrameRateMeter();
        long frame = 1_000_000_000L / 120;
        long now = 1L;
        boolean changed = false;
        for (int index = 0; index <= 70; index++) {
            changed |= meter.frame(now);
            now += frame;
        }
        assertTrue(changed);
        assertEquals(120, meter.fps(), 2);
    }

    @Test
    void staysQuietWithinAWindowAndWhenUnchanged() {
        FrameRateMeter meter = new FrameRateMeter();
        assertFalse(meter.frame(1L));
        assertFalse(meter.frame(100_000_000L));
        assertEquals(0, meter.fps());
    }
}
