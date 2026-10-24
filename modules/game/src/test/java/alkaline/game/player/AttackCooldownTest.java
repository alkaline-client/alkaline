package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttackCooldownTest {
    @Test
    void aSurvivalMissBlocksClicksForTenTicks() {
        AttackCooldown cooldown = new AttackCooldown();
        assertTrue(cooldown.ready());
        cooldown.missed(false);
        assertFalse(cooldown.ready());
        for (int tick = 0; tick < 9; tick++) {
            cooldown.tick(false);
            assertFalse(cooldown.ready(), "tick " + tick);
        }
        cooldown.tick(false);
        assertTrue(cooldown.ready());
    }

    @Test
    void creativeMissesCostNothing() {
        AttackCooldown cooldown = new AttackCooldown();
        cooldown.missed(true);
        assertTrue(cooldown.ready());
    }

    @Test
    void releasingTheKeyClearsTheCounterEachTick() {
        AttackCooldown cooldown = new AttackCooldown();
        cooldown.missed(false);
        assertFalse(cooldown.sustain(true));
        assertEquals(10, cooldown.counter());
        assertTrue(cooldown.sustain(false));
        assertEquals(0, cooldown.counter());
        assertTrue(cooldown.ready());
    }

    @Test
    void anOpenScreenPinsTheCounterUntilTheKeyIsReleased() {
        AttackCooldown cooldown = new AttackCooldown();
        cooldown.tick(true);
        assertEquals(AttackCooldown.SCREEN - 1, cooldown.counter());
        // The screen closes with the attack key still held: nothing digs or swings.
        cooldown.tick(false);
        assertFalse(cooldown.sustain(true));
        assertFalse(cooldown.ready());
        // One tick with the key up and the next click is free.
        assertTrue(cooldown.sustain(false));
        assertTrue(cooldown.ready());
    }
}
