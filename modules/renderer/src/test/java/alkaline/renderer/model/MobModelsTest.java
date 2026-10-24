package alkaline.renderer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MobModelsTest {
    private static CuboidModel model(EntityModels.Kind kind) {
        return EntityModels.model(kind);
    }

    private static ModelBox firstBox(CuboidModel model, String part) {
        return model.parts().get(model.partIndex(part)).boxes().get(0);
    }

    @Test
    void witchIsAVillagerWithAHatOnA64x128Texture() {
        CuboidModel witch = model(EntityModels.Kind.WITCH);
        assertEquals(11, witch.partCount());
        assertEquals(128, witch.textureHeight());
        assertEquals(new ModelBox(0, 64, 0, 0, 0, 10, 2, 10, 0f, false), firstBox(witch, "hat"));
        assertEquals(witch.partIndex("hat3"), witch.parts().get(witch.partIndex("hat4")).parent());
        assertEquals(new ModelBox(0, 0, 0, 3, -6.75f, 1, 1, 1, -0.25f, false), firstBox(witch, "wart"));
    }

    @Test
    void batHangsItsWingsOffTheBody() {
        CuboidModel bat = model(EntityModels.Kind.BAT);
        assertEquals(8, bat.partCount());
        assertEquals(new ModelBox(42, 0, -12, 1, 1.5f, 10, 16, 1, 0f, false), firstBox(bat, "right_wing"));
        assertTrue(firstBox(bat, "left_wing").mirror());
        ModelPose pose = new ModelPose(bat);
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.hanging = true;
        EntityModels.animate(EntityModels.Kind.BAT, motion, pose);
        assertEquals((float) Math.PI, pose.rotationX(MobModels.BAT_BODY), 1e-6f);
        assertEquals(-3f, pose.pivotX(MobModels.BAT_RIGHT_WING), 1e-6f);
    }

    @Test
    void rabbitBoxesAreNeverMirrored() {
        CuboidModel rabbit = model(EntityModels.Kind.RABBIT);
        assertEquals(12, rabbit.partCount());
        assertEquals(new ModelBox(0, 0, -3, -2, -10, 6, 5, 10, 0f, false), firstBox(rabbit, "body"));
        for (ModelPart part : rabbit.parts()) {
            assertTrue(part.boxes().stream().noneMatch(ModelBox::mirror), part.name());
        }
    }

    @Test
    void ocelotSittingFoldsTheBackLegs() {
        CuboidModel ocelot = model(EntityModels.Kind.OCELOT);
        assertEquals(8, ocelot.partCount());
        assertEquals(4, ocelot.parts().get(ocelot.partIndex("head")).boxes().size());
        ModelPose pose = new ModelPose(ocelot);
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.sitting = true;
        EntityModels.animate(EntityModels.Kind.OCELOT, motion, pose);
        assertEquals(21f, pose.pivotY(MobModels.OCELOT_BACK_LEFT), 1e-6f);
        assertEquals((float) (Math.PI / 4), pose.rotationX(MobModels.OCELOT_BODY), 1e-6f);
    }

    @Test
    void silverfishSegmentsAreSpacedByTheirDepths() {
        CuboidModel silverfish = model(EntityModels.Kind.SILVERFISH);
        assertEquals(10, silverfish.partCount());
        assertEquals(-3.5f, silverfish.parts().get(0).pivotZ(), 1e-6f);
        assertEquals(11.5f, silverfish.parts().get(6).pivotZ(), 1e-6f);
        assertEquals(new ModelBox(0, 9, -3, 0, -1.5f, 6, 4, 3, 0f, false), firstBox(silverfish, "segment2"));
        CuboidModel endermite = model(EntityModels.Kind.ENDERMITE);
        assertEquals(4, endermite.partCount());
        assertEquals(new ModelBox(0, 5, -3, 0, -2.5f, 6, 4, 5, 0f, false), firstBox(endermite, "segment1"));
    }

    @Test
    void magmaCubeIsACoreAndEightSlabs() {
        CuboidModel cube = model(EntityModels.Kind.MAGMA_CUBE);
        assertEquals(9, cube.partCount());
        assertEquals(new ModelBox(0, 16, -2, 18, -2, 4, 4, 4, 0f, false), firstBox(cube, "core"));
        assertEquals(new ModelBox(24, 19, -4, 19, -4, 8, 1, 8, 0f, false), firstBox(cube, "segment3"));
    }

    @Test
    void snowGolemArmsSwingWithTheBodyYaw() {
        CuboidModel golem = model(EntityModels.Kind.SNOW_GOLEM);
        assertEquals(5, golem.partCount());
        assertEquals(new ModelBox(0, 36, -6, -12, -6, 12, 12, 12, -0.5f, false), firstBox(golem, "bottom_body"));
        ModelPose pose = new ModelPose(golem);
        EntityModels.Motion motion = new EntityModels.Motion();
        EntityModels.animate(EntityModels.Kind.SNOW_GOLEM, motion, pose);
        assertEquals(5f, pose.pivotX(MobModels.SNOW_RIGHT_HAND), 1e-6f);
        assertEquals(1f, pose.rotationZ(MobModels.SNOW_RIGHT_HAND), 1e-6f);
    }

    @Test
    void zombieVillagerKeepsTheBipedPartOrder() {
        CuboidModel zombie = model(EntityModels.Kind.ZOMBIE_VILLAGER);
        assertEquals(EntityModels.BIPED_PARTS, zombie.partCount());
        assertEquals(EntityModels.HAT, zombie.partIndex("hat"));
        assertEquals(2, zombie.parts().get(EntityModels.HEAD).boxes().size());
        assertEquals(new ModelBox(24, 32, -1, -3, -6, 2, 4, 2, 0f, false), zombie.parts().get(0).boxes().get(1));
    }

    @Test
    void mooshroomMushroomsAreSixCrossedPlanes() {
        CuboidModel mushrooms = model(EntityModels.Kind.MOOSHROOM_MUSHROOMS);
        assertEquals(7, mushrooms.partCount());
        assertEquals(16, mushrooms.textureWidth());
    }

    @Test
    void horseHasThirtyNineParts() {
        CuboidModel horse = model(EntityModels.Kind.HORSE);
        assertEquals(39, horse.partCount());
        assertEquals(128, horse.textureWidth());
        assertEquals(new ModelBox(0, 34, -5, -8, -19, 10, 10, 24, 0f, false), firstBox(horse, "body"));
        assertEquals(HorseModels.HEAD, horse.partIndex("head"));
        assertEquals(HorseModels.HEAD, horse.parts().get(HorseModels.HEAD_UPPER).parent());
        assertEquals(HorseModels.HEAD_GROUP_END, horse.partIndex("left_chest"));
    }

    @Test
    void rearingHorseRaisesTheHeadAndTilts() {
        ModelPose pose = new ModelPose(model(EntityModels.Kind.HORSE));
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.rearing = 1f;
        EntityModels.animate(EntityModels.Kind.HORSE, motion, pose);
        assertEquals(-6f, pose.pivotY(HorseModels.HEAD), 1e-5f);
        assertEquals((float) Math.toRadians(-45), pose.rotationX(HorseModels.BODY), 1e-5f);
    }

    @Test
    void guardianHasTwelveSpinesAnEyeAndAThreePieceTail() {
        CuboidModel guardian = model(EntityModels.Kind.GUARDIAN);
        assertEquals(17, guardian.partCount());
        assertEquals(5, guardian.parts().get(0).boxes().size());
        assertTrue(guardian.parts().get(0).boxes().get(2).mirror());
        assertEquals(new ModelBox(8, 0, -1, 15, 0, 2, 2, 1, 0f, false), firstBox(guardian, "eye"));
        ModelPose pose = new ModelPose(guardian);
        EntityModels.Motion motion = new EntityModels.Motion();
        EntityModels.animate(EntityModels.Kind.GUARDIAN, motion, pose);
        assertEquals(16f + 8f * (1f + (float) Math.cos(8) * 0.01f), pose.pivotY(BossModels.GUARDIAN_SPINES + 8),
                1e-4f);
    }

    @Test
    void witherHasThreeHeadsAndARibcage() {
        CuboidModel wither = model(EntityModels.Kind.WITHER);
        assertEquals(6, wither.partCount());
        assertEquals(-8f, wither.parts().get(1).pivotX(), 1e-6f);
        assertEquals(4, wither.parts().get(BossModels.WITHER_SPINE).boxes().size());
    }

    @Test
    void dragonBuildsBothSidesMirroredAndStaysInThePartLimit() {
        CuboidModel dragon = model(EntityModels.Kind.ENDER_DRAGON);
        assertEquals(38, dragon.partCount());
        assertEquals(256, dragon.textureWidth());
        assertEquals(new ModelBox(0, 0, -12, 0, -16, 24, 24, 64, 0f, false), firstBox(dragon, "body"));
        ModelPart right = dragon.parts().get(dragon.partIndex("wing_r"));
        ModelPart left = dragon.parts().get(dragon.partIndex("wing_l"));
        assertEquals(-left.pivotX(), right.pivotX(), 1e-6f);
        assertTrue(left.boxes().get(0).mirror());
        assertEquals(0f, left.boxes().get(0).x(), 1e-6f);
        // Steady flight: the neck and tail chains lie straight, the tail turned half a circle.
        ModelPose pose = new ModelPose(dragon);
        EntityModels.animate(EntityModels.Kind.ENDER_DRAGON, new EntityModels.Motion(), pose);
        assertEquals((float) Math.PI, pose.rotationY(BossModels.DRAGON_TAIL), 1e-5f);
        assertEquals(0f, pose.rotationY(BossModels.DRAGON_NECK), 1e-5f);
    }

    @Test
    void magmaCubeSlabsSpreadWithTheSquish() {
        ModelPose pose = new ModelPose(model(EntityModels.Kind.MAGMA_CUBE));
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.squish = 0.5f;
        EntityModels.animate(EntityModels.Kind.MAGMA_CUBE, motion, pose);
        assertEquals(-(4 - 0) * 0.5f * 1.7f, pose.pivotY(1), 1e-5f);
        assertEquals(-(4 - 7) * 0.5f * 1.7f, pose.pivotY(8), 1e-5f);
        motion.squish = -1f; // a negative factor clamps to zero
        EntityModels.animate(EntityModels.Kind.MAGMA_CUBE, motion, pose);
        assertEquals(0f, pose.pivotY(1), 0f);
    }

    @Test
    void witherSideHeadsFollowTheirAim() {
        ModelPose pose = new ModelPose(model(EntityModels.Kind.WITHER));
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.sideHeadYaw[0] = 30f;
        motion.sideHeadPitch[1] = -20f;
        EntityModels.animate(EntityModels.Kind.WITHER, motion, pose);
        assertEquals((float) Math.toRadians(30), pose.rotationY(1), 1e-6f);
        assertEquals((float) Math.toRadians(-20), pose.rotationX(2), 1e-6f);
    }

    @Test
    void horseOpensItsMouthSwishesItsTailAndShowsReinsWhenRidden() {
        ModelPose pose = new ModelPose(model(EntityModels.Kind.HORSE));
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.saddled = true;
        motion.mouth = 1f;
        EntityModels.animate(EntityModels.Kind.HORSE, motion, pose);
        assertEquals(-0.09424778f, pose.rotationX(HorseModels.HEAD_UPPER), 1e-6f);
        assertEquals(1f, pose.pivotZ(HorseModels.HEAD_LOWER), 1e-6f);
        assertTrue(!pose.visible(HorseModels.LEFT_REIN));
        motion.ridden = true;
        motion.tailSwish = true;
        motion.ageInTicks = 10f;
        EntityModels.animate(EntityModels.Kind.HORSE, motion, pose);
        assertTrue(pose.visible(HorseModels.LEFT_REIN) && pose.visible(HorseModels.RIGHT_REIN));
        assertEquals((float) -Math.PI / 3f, pose.rotationX(HorseModels.LEFT_SADDLE_ROPE), 1e-6f);
        assertEquals((float) Math.cos(7f), pose.rotationY(HorseModels.TAIL_BASE), 1e-6f);
        assertEquals(0f, pose.rotationX(HorseModels.TAIL_BASE), 0f);
    }

    @Test
    void guardianSpikesPullInAndTheTailFollowsItsPhase() {
        ModelPose pose = new ModelPose(model(EntityModels.Kind.GUARDIAN));
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.spikes = 0f;
        motion.tailPhase = (float) Math.PI / 2f;
        EntityModels.animate(EntityModels.Kind.GUARDIAN, motion, pose);
        float spread = 1f + (float) Math.cos(8) * 0.01f - 0.55f;
        assertEquals(16f + 8f * spread, pose.pivotY(BossModels.GUARDIAN_SPINES + 8), 1e-4f);
        assertEquals((float) Math.PI * 0.15f, pose.rotationY(BossModels.GUARDIAN_TAIL2), 1e-6f);
        assertEquals(2, model(EntityModels.Kind.GUARDIAN_BEAM).partCount());
    }
}
