package alkaline.game.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FrameTimerTest
{
    @Test
    void acceptsSamplesInOrderAndAdvancesIndexWithoutAdvancingLastIndexBeforeFull()
    {
        FrameTimer timer = new FrameTimer();
        long[] frames = timer.getFrames();

        timer.addFrame(11L);
        timer.addFrame(22L);
        timer.addFrame(33L);

        assertEquals(240, frames.length);
        assertEquals(11L, frames[0]);
        assertEquals(22L, frames[1]);
        assertEquals(33L, frames[2]);
        assertEquals(3, timer.getIndex());
        assertEquals(0, timer.getLastIndex());
    }

    @Test
    void wrapsAtCapacityAndUpdatesTheLegacyLastIndexCursor()
    {
        FrameTimer timer = new FrameTimer();
        long[] frames = timer.getFrames();

        for (int sample = 0; sample < frames.length; ++sample)
        {
            timer.addFrame(sample + 1L);
        }

        assertEquals(0, timer.getIndex());
        assertEquals(0, timer.getLastIndex());
        assertEquals(1L, frames[0]);
        assertEquals(240L, frames[239]);

        timer.addFrame(241L);

        assertEquals(1, timer.getIndex());
        assertEquals(2, timer.getLastIndex());
        assertEquals(241L, frames[0]);
        assertEquals(2L, frames[1]);
        assertSame(frames, timer.getFrames());
    }

    @Test
    void parseIndexUsesTheRingCapacityAndExposesJavaRemainderForNegativeValues()
    {
        FrameTimer timer = new FrameTimer();

        assertEquals(0, timer.parseIndex(240));
        assertEquals(1, timer.parseIndex(241));
        assertEquals(239, timer.parseIndex(479));
        assertEquals(-1, timer.parseIndex(-1));
    }

    @Test
    void convertsFrameTimeToLagometerUnitsByTruncatingTheScaledValue()
    {
        FrameTimer timer = new FrameTimer();

        assertEquals(60, timer.getLagometerValue(16_666_666L, 60));
        assertEquals(30, timer.getLagometerValue(8_333_333L, 60));
        assertEquals(0, timer.getLagometerValue(100L, 1));
    }
}
