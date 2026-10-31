package alkaline.renderer.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.renderer.math.Mat4;
import org.junit.jupiter.api.Test;

class SkeletonMatchTest {
    private static ModelPose walkingPlayer() {
        EntityModels.Motion motion = new EntityModels.Motion();
        motion.limbSwing = 3.7f;
        motion.limbSwingAmount = 0.8f;
        motion.ageInTicks = 41.5f;
        motion.headYaw = 25f;
        motion.headPitch = -10f;
        motion.swingProgress = 0.4f;
        ModelPose pose = new ModelPose(EntityModels.model(EntityModels.Kind.PLAYER));
        EntityModels.animate(EntityModels.Kind.PLAYER, motion, pose);
        return pose;
    }

    @Test
    void armorCopyingTheWearerSharesItsSkeleton() {
        ModelPose wearer = walkingPlayer();
        float[] root = LivingTransforms.root(4f, 64f, -2f, 30f, 1f, new float[16]);
        SkeletonMatch match = new SkeletonMatch();
        match.remember(wearer, root);

        ModelPose armor = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_OUTER));
        armor.copyFrom(wearer, EntityModels.BIPED_PARTS);
        armor.visibleAll(false);
        armor.visible(EntityModels.HEAD, true);
        assertTrue(match.matches(armor, root));

        // The shared rows are the matrices the armor would have resolved itself.
        float[] own = new float[armor.model().partCount() * PoseMath.FLOATS_PER_PART];
        float[] shared = new float[wearer.model().partCount() * PoseMath.FLOATS_PER_PART];
        PoseMath.resolveAll(armor, root, own);
        PoseMath.resolveAll(wearer, root, shared);
        for (int index = 0; index < own.length; index++) {
            assertEquals(own[index], shared[index], 0f, "float " + index);
        }
        assertEquals(1L << EntityModels.HEAD, PoseMath.visibleMask(armor) & ((1L << EntityModels.BIPED_PARTS) - 1));
    }

    @Test
    void anotherRootOrRotationDoesNotMatch() {
        ModelPose wearer = walkingPlayer();
        float[] root = LivingTransforms.root(4f, 64f, -2f, 30f, 1f, new float[16]);
        SkeletonMatch match = new SkeletonMatch();
        match.remember(wearer, root);

        ModelPose armor = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_OUTER));
        armor.copyFrom(wearer, EntityModels.BIPED_PARTS);
        assertFalse(match.matches(armor, LivingTransforms.root(4.5f, 64f, -2f, 30f, 1f, new float[16])));
        armor.rotation[EntityModels.RIGHT_ARM * 3] += 0.01f;
        assertFalse(match.matches(armor, root));
        match.clear();
        assertFalse(match.matches(wearer, root));
    }

    @Test
    void maskAndResolveAllReproduceTheHidingResolve() {
        ModelPose pose = walkingPlayer();
        pose.visible(EntityModels.LEFT_ARM, false);
        pose.visible(EntityModels.HAT, false);
        float[] root = Mat4.identity(new float[16]);
        int parts = pose.model().partCount();
        float[] hidden = new float[parts * PoseMath.FLOATS_PER_PART];
        float[] all = new float[parts * PoseMath.FLOATS_PER_PART];
        PoseMath.resolve(pose, root, hidden);
        PoseMath.resolveAll(pose, root, all);
        long mask = PoseMath.visibleMask(pose);
        for (int part = 0; part < parts; part++) {
            float[] expected = new float[PoseMath.FLOATS_PER_PART];
            float[] actual = new float[PoseMath.FLOATS_PER_PART];
            System.arraycopy(hidden, part * PoseMath.FLOATS_PER_PART, expected, 0, PoseMath.FLOATS_PER_PART);
            if ((mask >>> part & 1L) != 0) {
                System.arraycopy(all, part * PoseMath.FLOATS_PER_PART, actual, 0, PoseMath.FLOATS_PER_PART);
            }
            assertArrayEquals(expected, actual, 0f, "part " + part);
        }
    }
}
