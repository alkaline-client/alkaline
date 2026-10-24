package alkaline.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.player.LocalPlayer;
import org.junit.jupiter.api.Test;

/** EntityLivingBase.swingItem: a swing restarts only when none runs or the running one is past its first half. */
class SwingTimingTest {
    private static final alkaline.game.player.CollisionShapes.States FLOOR = (x, y, z) -> y <= 63 ? 1 << 4 : 0;

    private static void tick(LocalPlayer player) {
        player.tick(LocalPlayer.Input.NONE, FLOOR, true);
    }

    @Test
    void localPlayerRestartsOnlyAfterTheFirstHalf() {
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 64, 0.5);
        assertTrue(player.swing());
        // swingProgressInt = -1 until the next tick, which counts to 0, then 1, 2...
        for (int ticks = 0; ticks < 3; ticks++) {
            tick(player);
            assertFalse(player.swing(), "restarted after " + (ticks + 1) + " ticks, inside the first half");
        }
        tick(player);
        assertTrue(player.swing(), "past half of six");
    }

    @Test
    void hasteAndMiningFatigueChangeTheLength() {
        LocalPlayer player = new LocalPlayer();
        assertEquals(6, player.swingDuration());
        player.haste = 1;
        assertEquals(4, player.swingDuration());
        player.haste = -1;
        player.miningFatigue = 2;
        assertEquals(12, player.swingDuration());
    }

    @Test
    void otherEntitiesFollowTheSameRuleAndFinishInSixTicks() {
        Entity entity = new Entity(1, Entity.Category.PLAYER, 0, java.util.UUID.randomUUID(), 0, 64, 0, 0, 0);
        entity.swingArm();
        entity.tick();
        assertEquals(0f, entity.swingProgress, 1e-6);
        entity.tick();
        assertEquals(1 / 6f, entity.swingProgress, 1e-6);
        entity.swingArm(); // inside the first half: ignored
        entity.tick();
        assertEquals(2 / 6f, entity.swingProgress, 1e-6);
        for (int tick = 0; tick < 3; tick++) {
            entity.tick();
        }
        assertEquals(5 / 6f, entity.swingProgress, 1e-6);
        entity.tick();
        assertEquals(0f, entity.swingProgress, 1e-6);
    }
}
