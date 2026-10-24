package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PlayerControllerMP's digging: the packets it sends, the crack stages it reports and when blocks break. */
class BlockBreakingTest {
    private static final int STONE = 1 << 4;

    /** A world of one stone block at (0, 64, 0); records what the controller does. */
    private static final class World implements BlockBreaking.Host {
        final List<String> log = new ArrayList<>();
        float progress = 0.25f;
        ItemStack held;
        int state = STONE;

        @Override
        public int state(int x, int y, int z) {
            return x == 0 && y == 64 && z == 0 ? state : 0;
        }

        @Override
        public ItemStack held() {
            return held;
        }

        @Override
        public void send(int status, int x, int y, int z, int face) {
            log.add("send " + status + " " + x + "," + y + "," + z + " f" + face);
        }

        @Override
        public float progress(int state) {
            return progress;
        }

        @Override
        public void stage(int x, int y, int z, int stage) {
            log.add("stage " + stage);
        }

        @Override
        public void destroyed(int x, int y, int z, int state) {
            log.add("destroyed " + x + "," + y + "," + z);
            this.state = 0;
        }

        @Override
        public void hit(int x, int y, int z, int state) {
            log.add("hit");
        }
    }

    @Test
    void holdingTheAttackKeyCracksTheBlockInTenthsThenBreaksIt() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        assertEquals(List.of("send 0 0,64,0 f1", "stage -1"), world.log);
        assertTrue(breaking.hitting());
        world.log.clear();
        for (int tick = 0; tick < 4; tick++) {
            assertTrue(breaking.damage(0, 64, 0, 1, false));
        }
        // 25% a tick: stages 1, 4, 6, and the fourth tick finishes the block and clears the cracks. The hit sound
        // plays on the first.
        assertEquals(List.of("hit", "stage 1", "stage 4", "stage 6", "send 2 0,64,0 f1", "destroyed 0,64,0",
                "stage -1"), world.log);
        assertFalse(breaking.hitting());
    }

    @Test
    void theHitSoundPlaysEveryFourthTick() {
        World world = new World();
        world.progress = 0.01f;
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        world.log.clear();
        for (int tick = 0; tick < 9; tick++) {
            breaking.damage(0, 64, 0, 1, false);
        }
        assertEquals(3, world.log.stream().filter("hit"::equals).count());
    }

    @Test
    void aBlockOneTickCanBreakGoesAtOnceWithoutCracks() {
        World world = new World();
        world.progress = 1f;
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 3, false);
        assertEquals(List.of("send 0 0,64,0 f3", "destroyed 0,64,0"), world.log);
        assertFalse(breaking.hitting());
    }

    @Test
    void afterABreakTheNextFiveTicksDigNothing() {
        World world = new World();
        world.progress = 1f;
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        world.state = STONE;
        world.log.clear();
        // The click path (instant break) does not start the delay; a finish from damage() does.
        world.progress = 0.5f;
        breaking.click(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        assertTrue(world.log.contains("destroyed 0,64,0"));
        world.state = STONE;
        world.log.clear();
        for (int tick = 0; tick < 5; tick++) {
            assertTrue(breaking.damage(0, 64, 0, 1, false));
        }
        assertEquals(List.of(), world.log);
    }

    @Test
    void lookingAtAnotherBlockAbortsTheDigAndStartsAnother() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        world.log.clear();
        breaking.damage(1, 64, 0, 4, false);
        assertEquals("send 1 0,64,0 f4", world.log.get(0));
        assertEquals("send 0 1,64,0 f4", world.log.get(1));
    }

    @Test
    void switchingTheHeldItemRestartsTheDig() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        world.held = new ItemStack(270, 1, 0, null);
        breaking.click(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        world.held = new ItemStack(274, 1, 0, null);
        world.log.clear();
        breaking.damage(0, 64, 0, 1, false);
        assertEquals("send 1 0,64,0 f1", world.log.get(0));
        assertEquals("send 0 0,64,0 f1", world.log.get(1));
        assertEquals(0f, breaking.damage());
    }

    @Test
    void aWornToolIsStillTheSameTool() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        world.held = new ItemStack(270, 1, 0, null);
        breaking.click(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        world.held = new ItemStack(270, 1, 3, null);
        world.log.clear();
        breaking.damage(0, 64, 0, 1, false);
        assertFalse(world.log.contains("send 1 0,64,0 f1"));
        assertEquals(0.5f, breaking.damage());
    }

    @Test
    void releasingTheKeyAbortsAndClearsTheCracks() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        breaking.damage(0, 64, 0, 1, false);
        world.log.clear();
        breaking.reset();
        assertEquals(List.of("send 1 0,64,0 f0", "stage -1"), world.log);
        world.log.clear();
        breaking.reset();
        assertEquals(List.of(), world.log);
    }

    @Test
    void creativeBreaksOnEveryClickAndRepeatsEveryFiveTicks() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, true);
        assertEquals(List.of("send 0 0,64,0 f1", "destroyed 0,64,0"), world.log);
        world.state = STONE;
        world.log.clear();
        for (int tick = 0; tick < 5; tick++) {
            breaking.damage(0, 64, 0, 1, true);
        }
        assertEquals(List.of(), world.log);
        breaking.damage(0, 64, 0, 1, true);
        assertEquals(List.of("send 0 0,64,0 f1", "destroyed 0,64,0"), world.log);
    }

    @Test
    void aSwordCannotBreakBlocksInCreative() {
        World world = new World();
        world.held = new ItemStack(276, 1, 0, null);
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, true);
        assertEquals(List.of("send 0 0,64,0 f1"), world.log);
    }

    @Test
    void airTakesNoProgress() {
        World world = new World();
        BlockBreaking breaking = new BlockBreaking(world);
        breaking.click(0, 64, 0, 1, false);
        world.state = 0;
        assertFalse(breaking.damage(0, 64, 0, 1, false));
        assertFalse(breaking.hitting());
    }
}
