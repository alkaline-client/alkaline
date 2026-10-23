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
}
