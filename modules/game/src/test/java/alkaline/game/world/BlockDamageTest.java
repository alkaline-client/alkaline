package alkaline.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Block Break Animation packets and the local player's progress, as RenderGlobal keeps them. */
class BlockDamageTest {
    @Test
    void eachDiggerHasOneBlockAndMovingReplacesIt() {
        BlockDamage damage = new BlockDamage();
        damage.set(1, 0, 0, 0, 2);
        damage.set(2, 5, 5, 5, 6);
        damage.set(1, 3, 4, 5, 3);
        assertEquals(2, damage.entries().size());
        BlockDamage.Entry first = damage.entries().stream().filter(entry -> entry.breaker() == 1).findFirst().orElseThrow();
        assertEquals(3, first.x());
        assertEquals(3, first.stage());
    }

    @Test
    void entriesNotRefreshedForFourHundredTicksExpireOnTheTwentyTickSweep() {
        BlockDamage damage = new BlockDamage();
        damage.set(1, 0, 0, 0, 2);
        for (int tick = 0; tick < 400; tick++) {
            damage.tick();
        }
        assertEquals(1, damage.entries().size());
        for (int tick = 0; tick < 20; tick++) {
            damage.tick();
        }
        assertTrue(damage.isEmpty());
    }

    @Test
    void refreshingKeepsAnEntryAlive() {
        BlockDamage damage = new BlockDamage();
        damage.set(1, 0, 0, 0, 2);
        for (int tick = 0; tick < 1000; tick++) {
            if (tick % 100 == 0) {
                damage.set(1, 0, 0, 0, 3);
            }
            damage.tick();
        }
        assertEquals(1, damage.entries().size());
    }

    @Test
    void farBlocksAreDroppedWhenDrawn() {
        BlockDamage damage = new BlockDamage();
        damage.set(1, 31, 0, 0, 2);
        damage.set(2, 33, 0, 0, 2);
        damage.cull(0, 0, 0);
        assertEquals(1, damage.entries().size());
        assertEquals(1, damage.entries().getFirst().breaker());
    }
}
