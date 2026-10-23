package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Vanilla 1.8.9's movement numbers and quirks (the values players and anticheats know: 4.317 m/s walking, 5.612
 * sprinting, 1.295 sneaking, a 1.24919-block jump, 0.1176 blocks per tick up ladders) and the one-tick lags the
 * client must keep.
 */
class VanillaPhysicsTest {
    private static final int STONE = 1 << 4;
    private final Map<Long, Integer> placed = new HashMap<>();
    private final CollisionShapes.States world = (x, y, z) -> {
        Integer state = placed.get(key(x, y, z));
        if (state != null) {
            return state;
        }
        return y <= 63 ? STONE : 0;
    };

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) << 40 | ((long) z & 0xFFFFF) << 20 | ((long) y & 0xFFFFF);
    }

    private LocalPlayer standing() {
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 64, 0.5);
        // Gravity applies after the move, so a placed player lands on its second tick.
        player.tick(LocalPlayer.Input.NONE, world, true);
        player.tick(LocalPlayer.Input.NONE, world, true);
        return player;
    }

    private double steadySpeed(LocalPlayer.Input input) {
        LocalPlayer player = standing();
        for (int tick = 0; tick < 80; tick++) {
            player.tick(input, world, true);
        }
        return (player.z - player.previousZ) * 20.0;
    }

    @Test
    void groundSpeedsAreVanillas() {
        assertEquals(4.317, steadySpeed(new LocalPlayer.Input(1, 0, false, false, false)), 1e-3);
        assertEquals(5.612, steadySpeed(new LocalPlayer.Input(1, 0, false, false, true)), 1e-3);
        assertEquals(1.295, steadySpeed(new LocalPlayer.Input(1, 0, false, true, false)), 1e-3);
    }

    @Test
    void aJumpPeaksAt1Point2522() {
        LocalPlayer player = standing();
        double peak = 0;
        player.tick(new LocalPlayer.Input(0, 0, true, false, false), world, true);
        for (int tick = 0; tick < 20; tick++) {
            peak = Math.max(peak, player.y - 64);
            player.tick(LocalPlayer.Input.NONE, world, true);
        }
        // 1.8 zeroes vertical motion below 0.005, which cuts the last 0.003 of the rise (1.9+ peaks at 1.2522).
        assertEquals(1.24919, peak, 1e-5);
        assertTrue(player.onGround);
    }

    @Test
    void sprintFollowsTheCurrentForwardInput() {
        LocalPlayer player = standing();
        LocalPlayer.Input sprint = new LocalPlayer.Input(1, 0, false, false, true);
        player.tick(sprint, world, true);
        assertTrue(player.sprinting);
        // Sneaking scales forward input to 0.3, below the 0.8 a sprint needs: it ends at once.
        player.tick(new LocalPlayer.Input(1, 0, false, true, true), world, true);
        assertFalse(player.sprinting);
        // So does a wall run into on the previous tick, even with forward still held.
        player.tick(sprint, world, true);
        assertTrue(player.sprinting);
    }

    @Test
    void doubleTappingForwardSprints() {
        LocalPlayer player = standing();
        LocalPlayer.Input forward = new LocalPlayer.Input(1, 0, false, false, false);
        player.tick(forward, world, true);
        player.tick(LocalPlayer.Input.NONE, world, true);
        assertFalse(player.sprinting);
        player.tick(forward, world, true);
        assertTrue(player.sprinting);
        // Too slow a second tap only arms the timer again.
        LocalPlayer slow = standing();
        slow.tick(forward, world, true);
        for (int tick = 0; tick < 8; tick++) {
            slow.tick(LocalPlayer.Input.NONE, world, true);
        }
        slow.tick(forward, world, true);
        assertFalse(slow.sprinting);
    }

    @Test
    void airControlFollowsSprintOneTickLate() {
        // Sprint-jump, then a hit on a player ends the sprint mid-air (sprint key let go so it stays off): the next
        // air tick still uses sprinting air control, the one after that the walking one.
        LocalPlayer player = standing();
        LocalPlayer.Input sprint = new LocalPlayer.Input(1, 0, false, false, true);
        for (int tick = 0; tick < 10; tick++) {
            player.tick(sprint, world, true);
        }
        player.tick(new LocalPlayer.Input(1, 0, true, false, true), world, true);
        assertFalse(player.onGround);
        player.knockbackDealt();
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        double before = player.motionZ;
        player.tick(walk, world, true);
        assertFalse(player.sprinting);
        assertEquals(0.98 * 0.026, player.motionZ / 0.91 - before, 1e-6);
        before = player.motionZ;
        player.tick(walk, world, true);
        assertEquals(0.98 * 0.02, player.motionZ / 0.91 - before, 1e-6);
    }

    @Test
    void laddersClimbAndSneakingHolds() {
        for (int y = 64; y < 70; y++) {
            placed.put(key(0, y, 1), 65 << 4 | 2); // ladder on the south wall (box at the block's south side)
            placed.put(key(0, y, 2), STONE);
        }
        LocalPlayer player = standing();
        player.teleport(0.5, 64, 1.5);
        LocalPlayer.Input up = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 6; tick++) {
            player.tick(up, world, true);
        }
        assertEquals(0.1176, player.y - player.previousY, 1e-4);
        // Sneaking on a ladder stops the slide down.
        double height = player.y;
        for (int tick = 0; tick < 5; tick++) {
            player.tick(new LocalPlayer.Input(0, 0, false, true, false), world, true);
        }
        assertTrue(player.y >= height - 0.2);
        double held = player.y;
        player.tick(new LocalPlayer.Input(0, 0, false, true, false), world, true);
        assertEquals(held, player.y, 1e-9);
    }

    @Test
    void slimeBouncesUnlessSneaking() {
        placed.put(key(0, 63, 0), 165 << 4);
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 70, 0.5);
        double rise = 0;
        boolean landed = false;
        for (int tick = 0; tick < 40; tick++) {
            player.tick(LocalPlayer.Input.NONE, world, true);
            if (player.motionY > 0) {
                landed = true;
                rise = Math.max(rise, player.motionY);
            }
        }
        assertTrue(landed && rise > 0.5);
    }

    @Test
    void cobwebsAndSoulSandSlow() {
        placed.put(key(0, 64, 3), 30 << 4); // cobweb in the way
        LocalPlayer player = standing();
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 12; tick++) {
            player.tick(walk, world, true);
        }
        double fast = player.z - player.previousZ;
        for (int tick = 0; tick < 6; tick++) {
            player.tick(walk, world, true);
        }
        assertTrue(player.z < 3.6, "caught in the web");
        assertTrue(player.z - player.previousZ < fast * 0.3);
    }

    @Test
    void waterCurrentPushes() {
        // A flowing water channel: level 1 at z = 0 flowing to level 3 at z = 2 (toward +z).
        for (int z = -2; z <= 4; z++) {
            placed.put(key(0, 64, z), (8 << 4) | Math.min(7, Math.max(0, z + 1)));
            placed.put(key(0, 65, z), (8 << 4) | Math.min(7, Math.max(0, z + 1)));
        }
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 64, 0.5);
        for (int tick = 0; tick < 10; tick++) {
            player.tick(LocalPlayer.Input.NONE, world, true);
        }
        assertTrue(player.inWater);
        assertTrue(player.z > 0.6, "pushed downstream");
    }

    @Test
    void jumpBoostRaisesTheJump() {
        LocalPlayer player = standing();
        player.jumpBoost = 1; // Jump Boost II
        player.tick(new LocalPlayer.Input(0, 0, true, false, false), world, true);
        assertEquals(0.62 - 0.08, player.motionY / 0.98, 1e-5);
    }

    @Test
    void hasteShortensTheSwing() {
        LocalPlayer player = standing();
        player.haste = 1;
        assertEquals(4, player.swingDuration());
        player.haste = -1;
        player.miningFatigue = 0;
        assertEquals(8, player.swingDuration());
    }

    @Test
    void stuckInABlockThePlayerIsPushedOut() {
        for (int z = -1; z <= 1; z++) {
            placed.put(key(0, 64, z), STONE);
            placed.put(key(0, 65, z), STONE);
        }
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.2, 64, 0.5);
        player.tick(LocalPlayer.Input.NONE, world, true);
        assertTrue(player.x < 0.2, "toward the nearer, open west side");
    }
}
