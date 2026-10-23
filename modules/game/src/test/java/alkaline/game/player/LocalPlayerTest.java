package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalPlayerTest {
    private static final int STONE = 1 << 4;
    private static final int SLAB = 44 << 4;

    /** A stone floor whose top is y = 64, plus placed blocks. */
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
        player.tick(LocalPlayer.Input.NONE, world, true);
        return player;
    }

    @Test
    void fallsAndLandsOnTheFloor() {
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 70, 0.5);
        for (int tick = 0; tick < 40; tick++) {
            player.tick(LocalPlayer.Input.NONE, world, true);
        }
        assertTrue(player.onGround);
        assertEquals(64.0, player.y, 1e-9);
    }

    @Test
    void walkingReachesVanillaSpeed() {
        LocalPlayer player = standing();
        player.yaw = 0; // +z
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 60; tick++) {
            player.tick(walk, world, true);
        }
        double perTick = player.z - player.previousZ;
        // 0.1 * 0.98 input over (1 - 0.6 * 0.91) ground friction: 4.317 blocks per second.
        assertEquals(0.098 / (1 - 0.546), perTick, 2e-3);
        assertEquals(64.0, player.y, 1e-9);
    }

    @Test
    void wallsStopTheWalk() {
        for (int y = 64; y < 67; y++) {
            placed.put(key(0, y, 3), STONE);
        }
        LocalPlayer player = standing();
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 40; tick++) {
            player.tick(walk, world, true);
        }
        assertEquals(3 - LocalPlayer.WIDTH / 2, player.z, 1e-9);
        assertTrue(player.collidedHorizontally);
    }

    @Test
    void stepsOntoSlabsButNotFullBlocks() {
        placed.put(key(0, 64, 3), SLAB);
        LocalPlayer player = standing();
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 40; tick++) {
            player.tick(walk, world, true);
        }
        assertTrue(player.z > 3.5, "walked over the slab");
        placed.put(key(0, 64, 3), STONE);
        LocalPlayer blocked = standing();
        for (int tick = 0; tick < 40; tick++) {
            blocked.tick(walk, world, true);
        }
        assertTrue(blocked.z < 3, "a full block is too high to step");
    }

    @Test
    void jumpingClearsOneBlock() {
        placed.put(key(0, 64, 2), STONE);
        LocalPlayer player = standing();
        LocalPlayer.Input jumpForward = new LocalPlayer.Input(1, 0, true, false, false);
        double highest = 0;
        for (int tick = 0; tick < 40; tick++) {
            player.tick(jumpForward, world, true);
            if (tick < 10) {
                highest = Math.max(highest, player.y); // the first jump only
            }
        }
        // Vanilla's jump peaks at about 1.25 blocks.
        assertEquals(1.2522, highest - 64, 0.01);
        assertTrue(player.z > 2.5, "landed on or past the block");
    }

    @Test
    void sneakingStopsAtLedges() {
        for (int x = -2; x <= 2; x++) {
            for (int z = 1; z <= 8; z++) {
                placed.put(key(x, 63, z), 0);
            }
        }
        LocalPlayer player = standing();
        LocalPlayer.Input sneak = new LocalPlayer.Input(1, 0, false, true, false);
        for (int tick = 0; tick < 60; tick++) {
            player.tick(sneak, world, true);
        }
        assertTrue(player.onGround);
        assertFalse(player.z > 1 + LocalPlayer.WIDTH / 2, "did not walk off the edge");
    }
}
