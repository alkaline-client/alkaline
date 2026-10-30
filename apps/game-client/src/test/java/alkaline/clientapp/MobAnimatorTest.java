package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.renderer.model.EntityModels;
import java.util.List;
import org.junit.jupiter.api.Test;

class MobAnimatorTest {
    private static Entity mob(int id, int type) {
        return new Entity(id, Entity.Category.MOB, type, null, 0, 4, 0, 0f, 0f);
    }

    @Test
    void rabbitHopRunsTenTicksFromStatusOneThenResets() {
        MobAnimator animator = new MobAnimator();
        Entity rabbit = mob(7, EntityTypes.RABBIT);
        animator.tick(List.of(rabbit), null);
        EntityModels.Motion motion = new EntityModels.Motion();
        animator.apply(rabbit, animator.state(rabbit), 0f, motion);
        assertEquals(0f, motion.jump, 0f);
        animator.status(rabbit, 1);
        animator.apply(rabbit, animator.state(rabbit), 0.5f, motion);
        assertEquals(0.05f, motion.jump, 1e-6f);
        for (int tick = 0; tick < 10; tick++) {
            animator.tick(List.of(rabbit), null);
        }
        animator.apply(rabbit, animator.state(rabbit), 0f, motion);
        assertEquals(1f, motion.jump, 1e-6f);
        animator.tick(List.of(rabbit), null);
        animator.apply(rabbit, animator.state(rabbit), 0f, motion);
        assertEquals(0f, motion.jump, 0f);
    }

    @Test
    void horseRearingGrazingAndMouthEaseTowardTheirFlagsAndBack() {
        Entity horse = mob(1, EntityTypes.HORSE);
        MobAnimator.State state = new MobAnimator.State();
        horse.metadata.setInt(16, 32 | 128);
        MobAnimator.tickHorse(state, horse);
        assertEquals(0.45f, state.grazing, 1e-6f); // 0 + ((1 - 0) * 0.4 + 0.05)
        assertEquals(0.75f, state.mouth, 1e-6f); // 0 + ((1 - 0) * 0.7 + 0.05)
        assertEquals(0f, state.rearing, 0f);
        horse.metadata.setInt(16, 64);
        MobAnimator.tickHorse(state, horse);
        assertEquals(0f, state.grazing, 0f); // rearing zeroes the head lean and its previous value
        assertEquals(0.45f, state.rearing, 1e-6f);
        assertTrue(state.mouth < 0.75f);
        horse.metadata.setInt(16, 0);
        float before = state.rearing;
        MobAnimator.tickHorse(state, horse);
        // Falling back: amount + ((0.8 * amount^3 - amount) * 0.6 - 0.05).
        assertEquals(before + ((0.8f * before * before * before - before) * 0.6f - 0.05f), state.rearing, 1e-6f);
    }

    @Test
    void horseTailSwishCountsEightTicksThenStops() {
        Entity horse = mob(1, EntityTypes.HORSE);
        MobAnimator.State state = new MobAnimator.State();
        state.tailSwish = 1;
        for (int tick = 0; tick < 7; tick++) {
            MobAnimator.tickHorse(state, horse);
        }
        assertEquals(8, state.tailSwish);
        MobAnimator.tickHorse(state, horse);
        assertEquals(0, state.tailSwish);
    }

    @Test
    void ridingFlagsTheHorseItsRiderSitsOn() {
        MobAnimator animator = new MobAnimator();
        Entity horse = mob(1, EntityTypes.HORSE);
        Entity rider = mob(2, EntityTypes.ZOMBIE);
        animator.tick(List.of(horse, rider), null);
        assertTrue(!animator.state(horse).ridden);
        rider.vehicle = horse.id;
        animator.tick(List.of(horse, rider), null);
        assertTrue(animator.state(horse).ridden);
    }

    @Test
    void slimeSquishesOnLandingAndStretchesOnTakeOff() {
        Entity slime = mob(3, EntityTypes.SLIME);
        MobAnimator.State state = new MobAnimator.State();
        slime.onGround = true;
        MobAnimator.tickSlime(state, slime, 0.6f);
        assertEquals(0f, state.squishFactor, 0f); // the factor moves first, toward the old target
        assertEquals(-0.5f * 0.6f, state.squishAmount, 1e-6f);
        slime.onGround = true;
        MobAnimator.tickSlime(state, slime, 0.6f);
        assertEquals(-0.15f, state.squishFactor, 1e-6f);
        slime.onGround = false;
        MobAnimator.tickSlime(state, slime, 0.6f);
        assertEquals(0.6f, state.squishAmount, 1e-6f); // take-off: 1.0 * 0.6
        // Magma cubes decay slower.
        MobAnimator.State magma = new MobAnimator.State();
        magma.squishAmount = 1f;
        MobAnimator.tickSlime(magma, slime, 0.9f);
        assertEquals(0.9f, magma.squishAmount, 1e-6f);
    }

    @Test
    void witherSideHeadsTurnTenDegreesAndFortyDegreesAtATime() {
        assertEquals(10f, MobAnimator.approach(0f, 90f, 10f), 1e-6f);
        assertEquals(-10f, MobAnimator.approach(0f, -170f, 10f), 1e-6f);
        assertEquals(180f, MobAnimator.approach(170f, -170f, 10f), 1e-4f); // the short way round, through 180
        MobAnimator animator = new MobAnimator();
        Entity wither = mob(10, EntityTypes.WITHER);
        Entity target = new Entity(11, Entity.Category.PLAYER, 0, java.util.UUID.randomUUID(), 10, 4, 0, 0f, 0f);
        wither.metadata.setInt(18, 11);
        animator.tick(List.of(wither, target), null);
        MobAnimator.State state = animator.state(wither);
        // Target due +x: atan2(0, dx) - 90 = -90 degrees; one tick moves yaw by -10.
        assertEquals(-10f, state.headYaw[0], 1e-4f);
        // The other head has no target and eases toward the body yaw (0), already there.
        assertEquals(0f, state.headYaw[1], 1e-4f);
        assertTrue(state.headPitch[0] > 0f); // the target's eyes sit below the head, so it looks down
    }

    @Test
    void guardianSpikesChargeAndTailFollowVanillasRules() {
        Entity guardian = mob(5, EntityTypes.GUARDIAN);
        MobAnimator.State state = new MobAnimator.State();
        alkaline.game.player.CollisionShapes.States water = (x, y, z) -> 9 << 4;
        MobAnimator.tickGuardian(state, guardian, water);
        assertEquals(0.06f, state.spikes, 1e-6f); // 0 + (1 - 0) * 0.06 in water
        assertEquals(0.125f * 0.2f, state.tailSpeed, 1e-6f);
        guardian.metadata.setInt(16, 2); // retracted
        MobAnimator.tickGuardian(state, guardian, water);
        assertEquals(0.06f * 0.75f, state.spikes, 1e-6f);
        assertEquals(4f, state.tailSpeed, 1e-6f); // under 0.5 and retracted: a burst of 4
        guardian.metadata.setInt(16, 0);
        MobAnimator.tickGuardian(state, guardian, (x, y, z) -> 0); // out of water: speed 2, random spikes
        assertEquals(2f, state.tailSpeed, 0f);
        assertTrue(state.spikes >= 0f && state.spikes < 1f);
        // The beam charges one tick at a time while targeting, and starts over on a new target.
        guardian.metadata.setInt(17, 40);
        MobAnimator.tickGuardian(state, guardian, water);
        MobAnimator.tickGuardian(state, guardian, water);
        assertEquals(2, state.charge);
        assertEquals((2 + 0.5f) / 80f, MobAnimator.beamCharge(state, false, 0.5f), 1e-6f);
        assertEquals((2 + 0.5f) / 60f, MobAnimator.beamCharge(state, true, 0.5f), 1e-6f);
        guardian.metadata.setInt(17, 41);
        MobAnimator.tickGuardian(state, guardian, water);
        assertEquals(1, state.charge);
    }

    @Test
    void dragonRingKeepsTheLastSixtyFourTicksAndInterpolates() {
        Entity dragon = mob(9, EntityTypes.ENDER_DRAGON);
        MobAnimator.State state = new MobAnimator.State();
        for (int tick = 0; tick < 70; tick++) {
            dragon.previousX = dragon.x;
            dragon.yaw = tick;
            MobAnimator.tickDragon(state, dragon);
        }
        EntityModels.Motion motion = new EntityModels.Motion();
        MobAnimator.dragonOffsets(state, false, 0f, motion);
        // Offset 0 at partial 0 blends fully toward the older sample (index - 1).
        assertEquals(68f, motion.movement[0], 1e-6f);
        MobAnimator.dragonOffsets(state, false, 1f, motion);
        assertEquals(69f, motion.movement[0], 1e-6f);
        MobAnimator.dragonOffsets(state, true, 1f, motion); // dead: held at partial 0
        assertEquals(68f, motion.movement[0], 1e-6f);
        assertEquals(59f, motion.movement[2 * 9], 1e-6f); // offset 9 reads the sample nine ticks back
        assertTrue(state.animTime > 0f);
    }

    @Test
    void statesAreDroppedWhenTheEntityLeavesAndTablesSurviveChurn() {
        MobAnimator animator = new MobAnimator();
        Entity rabbit = mob(100, EntityTypes.RABBIT);
        Entity horse = mob(200, EntityTypes.HORSE);
        animator.tick(List.of(rabbit, horse), null);
        assertEquals(2, animator.tracked());
        animator.tick(List.of(horse), null);
        assertEquals(1, animator.tracked());
        assertNull(animator.state(rabbit));
        assertNotNull(animator.state(horse));
        MobAnimator.IntTable<Integer> table = new MobAnimator.IntTable<>();
        for (int key = 0; key < 1000; key++) {
            table.put(key * 64, key);
        }
        for (int key = 0; key < 1000; key += 2) {
            table.remove(key * 64);
        }
        for (int key = 0; key < 1000; key++) {
            assertEquals(key % 2 == 0 ? null : (Integer) key, table.get(key * 64));
        }
        assertEquals(500, table.size());
    }

    @Test
    void dragonHealsFromTheNearestCrystalInRangeAndLetsGoWhenItIsGone() {
        MobAnimator animator = new MobAnimator();
        Entity dragon = mob(9, EntityTypes.ENDER_DRAGON);
        Entity near = new Entity(20, Entity.Category.OBJECT, EntityTypes.OBJECT_ENDER_CRYSTAL, null, 30, 4, 0, 0f, 0f);
        Entity far = new Entity(21, Entity.Category.OBJECT, EntityTypes.OBJECT_ENDER_CRYSTAL, null, -35, 4, 0, 0f, 0f);
        // Past the dragon's box grown by 32 (8 + 32 = 40 from its middle, plus the crystal's half width).
        Entity outside = new Entity(22, Entity.Category.OBJECT, EntityTypes.OBJECT_ENDER_CRYSTAL, null, 0, 4, 41.5, 0f,
                0f);
        // A skeleton shares the crystal's type number but is a mob.
        Entity skeleton = mob(23, EntityTypes.SKELETON);
        List<Entity> all = List.of(dragon, near, far, outside, skeleton);
        for (int tick = 0; tick < 200; tick++) {
            animator.tick(all, null);
        }
        assertEquals(20, animator.state(dragon).healingCrystal);
        List<Entity> onlyOutside = List.of(dragon, outside);
        animator.tick(onlyOutside, null);
        assertEquals(-1, animator.state(dragon).healingCrystal);
    }

    @Test
    void crystalRotationStartsBelowOneHundredThousandAndCountsTicks() {
        Entity crystal = new Entity(-5, Entity.Category.OBJECT, EntityTypes.OBJECT_ENDER_CRYSTAL, null, 0, 0, 0, 0f, 0f);
        int start = MobAnimator.crystalRotation(crystal);
        assertTrue(start >= 0 && start < 100000);
        crystal.tick();
        assertEquals(start + 1, MobAnimator.crystalRotation(crystal));
    }
}
