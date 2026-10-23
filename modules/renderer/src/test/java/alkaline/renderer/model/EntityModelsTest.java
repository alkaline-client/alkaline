package alkaline.renderer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntityModelsTest {
    @Test
    void everyModelBakesAndPosesWithFiniteMatrices() {
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.limbSwing = 3.7f;
        motion.limbSwingAmount = 0.8f;
        motion.ageInTicks = 41.5f;
        motion.headYaw = 25f;
        motion.headPitch = -10f;
        motion.swingProgress = 0.4f;
        motion.onGround = false;
        float[] root = LivingTransforms.root(0f, 64f, 0f, 30f, 1f, new float[16]);
        for (EntityModels.Kind kind : EntityModels.Kind.values()) {
            CuboidModel model = EntityModels.model(kind);
            BakedModel baked = ModelMesher.bake(model);
            assertTrue(baked.indexCount() > 0, kind.name());
            ModelPose pose = new ModelPose(model);
            EntityModels.animate(kind, motion, pose);
            float[] matrices = new float[model.partCount() * PoseMath.FLOATS_PER_PART];
            PoseMath.resolve(pose, root, matrices);
            for (float value : matrices) {
                assertTrue(Float.isFinite(value), kind.name());
            }
        }
    }

    @Test
    void bipedModelsShareThePartOrderArmorCopies() {
        for (EntityModels.Kind kind : new EntityModels.Kind[]{EntityModels.Kind.PLAYER, EntityModels.Kind.PLAYER_SLIM,
                EntityModels.Kind.BIPED, EntityModels.Kind.ARMOR_OUTER, EntityModels.Kind.ARMOR_INNER,
                EntityModels.Kind.SKELETON, EntityModels.Kind.ENDERMAN}) {
            CuboidModel model = EntityModels.model(kind);
            assertEquals(EntityModels.HEAD, model.partIndex("head"), kind.name());
            assertEquals(EntityModels.BODY, model.partIndex("body"), kind.name());
            assertEquals(EntityModels.RIGHT_ARM, model.partIndex("right_arm"), kind.name());
            assertEquals(EntityModels.LEFT_ARM, model.partIndex("left_arm"), kind.name());
            assertEquals(EntityModels.RIGHT_LEG, model.partIndex("right_leg"), kind.name());
            assertEquals(EntityModels.LEFT_LEG, model.partIndex("left_leg"), kind.name());
            assertEquals(EntityModels.HAT, model.partIndex("hat"), kind.name());
        }
    }
}
