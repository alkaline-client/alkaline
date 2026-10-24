package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.net.ServerSession;
import alkaline.game.world.WorldBorder;
import org.junit.jupiter.api.Test;

/** World.getCollidingBoundingBoxes: beyond the border the world reads as stone. */
class BorderCollisionTest {
    private static final CollisionShapes.States FLOOR = (x, y, z) -> y <= 63 ? 1 << 4 : 0;

    private static WorldBorder border(double diameter) {
        WorldBorder border = new WorldBorder();
        border.apply(new ServerSession.WorldBorder(3, 0, 0, diameter, diameter, 0, 29999984, 15, 5));
        return border;
    }

    private static LocalPlayer walkingEast(double startX) {
        LocalPlayer player = new LocalPlayer();
        player.teleport(startX, 64, 0.5);
        player.yaw = -90; // +x
        return player;
    }

    @Test
    void thePlayerStopsAtTheBorder() {
        LocalPlayer player = walkingEast(5.5);
        player.border = border(20);
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 80; tick++) {
            player.tick(walk, FLOOR, true);
        }
        assertTrue(player.x < 10.0, "x = " + player.x);
        assertTrue(player.x > 9.5, "x = " + player.x);
    }

    @Test
    void withoutABorderTheSameWalkContinues() {
        LocalPlayer player = walkingEast(5.5);
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 80; tick++) {
            player.tick(walk, FLOOR, true);
        }
        assertTrue(player.x > 12.0, "x = " + player.x);
    }

    @Test
    void aPlayerOutsideTheBorderMovesFreely() {
        LocalPlayer player = walkingEast(12.5);
        player.border = border(20);
        LocalPlayer.Input walk = new LocalPlayer.Input(1, 0, false, false, false);
        for (int tick = 0; tick < 40; tick++) {
            player.tick(walk, FLOOR, true);
        }
        assertTrue(player.x > 14.0, "x = " + player.x);
    }
}
