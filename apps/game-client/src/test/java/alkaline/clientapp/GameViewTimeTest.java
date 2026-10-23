package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The time of day the client keeps, against WorldClient.setWorldTime and S03PacketTimeUpdate (1.8.9). */
class GameViewTimeTest {
    @Test
    void anUpdateWithoutTheDaylightCycleKeepsItsAbsoluteTime() {
        // The server negates the time when doDaylightCycle is off; /time set 6000 arrives as -6000.
        assertEquals(6000L, GameView.dayTime(-6000L));
        assertEquals(18000L, GameView.dayTime(-18000L));
        // S03PacketTimeUpdate sends -1 for a time of 0 so that the sign survives.
        assertEquals(1L, GameView.dayTime(-1L));
    }

    @Test
    void anUpdateWithTheDaylightCycleKeepsItsTime() {
        assertEquals(6000L, GameView.dayTime(6000L));
        assertEquals(0L, GameView.dayTime(0L));
    }
}
