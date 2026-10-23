package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.inventory.ItemStack;
import alkaline.game.net.ServerSession;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InteractionTest {
    private static final UUID SPEED = UUID.fromString("91AEAA56-376B-4498-935B-2F7F68070635");

    @Test
    void speedPotionsRaiseTheMovementSpeedAndTheClientKeepsSprinting() {
        List<ServerSession.AttributeModifier> modifiers = List.of(
                new ServerSession.AttributeModifier(SPEED, 0.4, 2), // Speed II
                new ServerSession.AttributeModifier(LocalPlayer.SPRINT_MODIFIER, 0.3, 2));
        assertEquals(0.14, LocalPlayer.attribute(0.1, modifiers, LocalPlayer.SPRINT_MODIFIER), 1e-9);
        assertEquals(0.182, LocalPlayer.attribute(0.1, modifiers, null), 1e-9);
        // Operation 0 adds to the base, 1 adds multiples of that sum.
        assertEquals((0.1 + 0.05) * 1.5, LocalPlayer.attribute(0.1, List.of(
                new ServerSession.AttributeModifier(UUID.randomUUID(), 0.05, 0),
                new ServerSession.AttributeModifier(UUID.randomUUID(), 0.5, 1)), null), 1e-9);
    }

    @Test
    void usingAnItemSlowsToAFifthAndStopsTheSprint() {
        LocalPlayer player = new LocalPlayer();
        player.teleport(0.5, 4, 0.5);
        player.onGround = true;
        CollisionShapes.States ground = (x, y, z) -> y < 4 ? 1 << 4 : 0;
        LocalPlayer.Input sprinting = new LocalPlayer.Input(1, 0, false, false, true);
        player.tick(sprinting, ground, true);
        player.tick(sprinting, ground, true);
        assertTrue(player.sprinting);
        player.usingItem = true;
        player.tick(sprinting, ground, true);
        assertFalse(player.sprinting); // a fifth of the forward input is below the 0.8 a sprint needs
    }

    @Test
    void foodIsEatenOnlyWhenHungryButGoldenApplesAlways() {
        ItemStack steak = new ItemStack(364, 5, 0, null);
        assertEquals(0, Interactions.useDuration(steak, 20, false, false));
        assertEquals(32, Interactions.useDuration(steak, 15, false, false));
        assertEquals(32, Interactions.useDuration(new ItemStack(322, 1, 0, null), 20, false, false));
        assertEquals(72000, Interactions.useDuration(new ItemStack(276, 1, 0, null), 20, false, false));
        assertEquals(0, Interactions.useDuration(new ItemStack(261, 1, 0, null), 20, false, false)); // no arrows
        assertEquals(0, Interactions.useDuration(new ItemStack(373, 1, 16385, null), 20, false, false)); // splash
        assertTrue(Interactions.activatable(54));
        assertFalse(Interactions.activatable(1));
        assertTrue(Interactions.placeable(4));
        assertFalse(Interactions.placeable(276));
    }

    @Test
    void theLookRayFindsTheFaceItEnters() {
        // A single stone block two blocks south of an eye at 5.62, looking down at it.
        CollisionShapes.States world = (x, y, z) -> x == 0 && y == 4 && z == 2 ? 1 << 4 : 0;
        BlockTarget target = BlockTarget.pick(world, 0.5, 5.62, 0.5, 0f, 31f, 4.5);
        assertNotNull(target);
        assertEquals(2, target.z());
        assertEquals(2, target.face()); // the north face, nearest the player
        assertNull(BlockTarget.pick(world, 0.5, 5.62, 0.5, 180f, 31f, 4.5)); // looking away
        BlockTarget top = BlockTarget.pick(world, 0.5, 5.62, 2.5, 0f, 90f, 4.5);
        assertEquals(1, top.face());
    }
}
