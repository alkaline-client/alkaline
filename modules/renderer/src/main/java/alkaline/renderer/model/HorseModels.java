package alkaline.renderer.model;

import static alkaline.renderer.model.MobModels.box;
import static alkaline.renderer.model.MobModels.cos;
import static alkaline.renderer.model.MobModels.sin;

import alkaline.renderer.model.EntityModels.Motion;

/**
 * ModelHorse (horse, donkey, mule, zombie and skeleton horse): its 39 parts on a 128x128 sheet, and the pose from
 * {@code setLivingAnimations}. Parts sit in three groups a foal draws with different scales (legs, trunk, head), then
 * the adult-only saddle, chests and reins.
 */
final class HorseModels {
    private static final float PI = (float) Math.PI;

    static final int BACK_LEFT_LEG = 0, BACK_LEFT_SHIN = 1, BACK_LEFT_HOOF = 2, BACK_RIGHT_LEG = 3,
            BACK_RIGHT_SHIN = 4, BACK_RIGHT_HOOF = 5, FRONT_LEFT_LEG = 6, FRONT_LEFT_SHIN = 7, FRONT_LEFT_HOOF = 8,
            FRONT_RIGHT_LEG = 9, FRONT_RIGHT_SHIN = 10, FRONT_RIGHT_HOOF = 11;
    static final int BODY = 12, TAIL_BASE = 13, TAIL_MIDDLE = 14, TAIL_TIP = 15, NECK = 16, MANE = 17;
    static final int HORSE_LEFT_EAR = 18, HORSE_RIGHT_EAR = 19, MULE_LEFT_EAR = 20, MULE_RIGHT_EAR = 21, HEAD = 22,
            HEAD_UPPER = 23, HEAD_LOWER = 24;
    static final int LEFT_CHEST = 25, RIGHT_CHEST = 26;
    static final int SADDLE_BOTTOM = 27, SADDLE_FRONT = 28, SADDLE_BACK = 29, LEFT_SADDLE_ROPE = 30,
            LEFT_SADDLE_METAL = 31, RIGHT_SADDLE_ROPE = 32, RIGHT_SADDLE_METAL = 33, LEFT_FACE_METAL = 34,
            RIGHT_FACE_METAL = 35, FACE_ROPES = 36, LEFT_REIN = 37, RIGHT_REIN = 38;
    /** Part ranges (first inclusive, last exclusive) a foal draws with their own transforms. */
    static final int LEG_GROUP_END = 12, TRUNK_GROUP_END = 18, HEAD_GROUP_END = 25;

    private static final int[] HEAD_FOLLOWERS = {HORSE_LEFT_EAR, HORSE_RIGHT_EAR, NECK, MANE};
    private static final int[] RIDING_FOLLOWERS = {FACE_ROPES, LEFT_FACE_METAL, RIGHT_FACE_METAL};

    private HorseModels() {
    }

    static CuboidModel build() {
        float tilt = PI / 6f;
        return CuboidModel.builder("horse", 128, 128)
                .part("back_left_leg", null, 4, 9, 11, box(78, 29, -2.5f, -2, -2.5f, 4, 9, 5, 0))
                .part("back_left_shin", null, 4, 16, 11, box(78, 43, -2, 0, -1.5f, 3, 5, 3, 0))
                .part("back_left_hoof", null, 4, 16, 11, box(78, 51, -2.5f, 5.1f, -2, 4, 3, 4, 0))
                .part("back_right_leg", null, -4, 9, 11, box(96, 29, -1.5f, -2, -2.5f, 4, 9, 5, 0))
                .part("back_right_shin", null, -4, 16, 11, box(96, 43, -1, 0, -1.5f, 3, 5, 3, 0))
                .part("back_right_hoof", null, -4, 16, 11, box(96, 51, -1.5f, 5.1f, -2, 4, 3, 4, 0))
                .part("front_left_leg", null, 4, 9, -8, box(44, 29, -1.9f, -1, -2.1f, 3, 8, 4, 0))
                .part("front_left_shin", null, 4, 16, -8, box(44, 41, -1.9f, 0, -1.6f, 3, 5, 3, 0))
                .part("front_left_hoof", null, 4, 16, -8, box(44, 51, -2.4f, 5.1f, -2.1f, 4, 3, 4, 0))
                .part("front_right_leg", null, -4, 9, -8, box(60, 29, -1.1f, -1, -2.1f, 3, 8, 4, 0))
                .part("front_right_shin", null, -4, 16, -8, box(60, 41, -1.1f, 0, -1.6f, 3, 5, 3, 0))
                .part("front_right_hoof", null, -4, 16, -8, box(60, 51, -1.6f, 5.1f, -2.1f, 4, 3, 4, 0))
                .part("body", null, 0, 11, 9, box(0, 34, -5, -8, -19, 10, 10, 24, 0))
                .part("tail_base", null, 0, 3, 14, -1.134464f, 0, 0, box(44, 0, -1, -1, 0, 2, 2, 3, 0))
                .part("tail_middle", null, 0, 3, 14, -1.134464f, 0, 0, box(38, 7, -1.5f, -2, 3, 3, 4, 7, 0))
                .part("tail_tip", null, 0, 3, 14, -1.40215f, 0, 0, box(24, 3, -1.5f, -4.5f, 9, 3, 4, 7, 0))
                .part("neck", null, 0, 4, -10, tilt, 0, 0, box(0, 12, -2.05f, -9.8f, -2, 4, 14, 8, 0))
                .part("mane", null, 0, 4, -10, tilt, 0, 0, box(58, 0, -1, -11.5f, 5, 2, 16, 4, 0))
                .part("horse_left_ear", null, 0, 4, -10, tilt, 0, 0, box(0, 0, 0.45f, -12, 4, 2, 3, 1, 0))
                .part("horse_right_ear", null, 0, 4, -10, tilt, 0, 0, box(0, 0, -2.45f, -12, 4, 2, 3, 1, 0))
                .part("mule_left_ear", null, 0, 4, -10, tilt, 0, PI / 12f, box(0, 12, -2, -16, 4, 2, 7, 1, 0))
                .part("mule_right_ear", null, 0, 4, -10, tilt, 0, -PI / 12f, box(0, 12, 0, -16, 4, 2, 7, 1, 0))
                .part("head", null, 0, 4, -10, tilt, 0, 0, box(0, 0, -2.5f, -10, -1.5f, 5, 5, 7, 0))
                .part("head_upper", "head", 0, 0.02f, 0.02f, box(24, 18, -2, -10, -7, 4, 3, 6, 0))
                .part("head_lower", "head", 0, 0, 0, box(24, 27, -2, -7, -6.5f, 4, 2, 5, 0))
                .part("left_chest", null, -7.5f, 3, 10, 0, PI / 2f, 0, box(0, 34, -3, 0, 0, 8, 8, 3, 0))
                .part("right_chest", null, 4.5f, 3, 10, 0, PI / 2f, 0, box(0, 47, -3, 0, 0, 8, 8, 3, 0))
                .part("saddle_bottom", null, 0, 2, 2, box(80, 0, -5, 0, -3, 10, 1, 8, 0))
                .part("saddle_front", null, 0, 2, 2, box(106, 9, -1.5f, -1, -3, 3, 1, 2, 0))
                .part("saddle_back", null, 0, 2, 2, box(80, 9, -4, -1, 3, 8, 1, 2, 0))
                .part("left_saddle_rope", null, 5, 3, 2, box(70, 0, -0.5f, 0, -0.5f, 1, 6, 1, 0))
                .part("left_saddle_metal", null, 5, 3, 2, box(74, 0, -0.5f, 6, -1, 1, 2, 2, 0))
                .part("right_saddle_rope", null, -5, 3, 2, box(80, 0, -0.5f, 0, -0.5f, 1, 6, 1, 0))
                .part("right_saddle_metal", null, -5, 3, 2, box(74, 4, -0.5f, 6, -1, 1, 2, 2, 0))
                .part("left_face_metal", null, 0, 4, -10, tilt, 0, 0, box(74, 13, 1.5f, -8, -4, 1, 2, 2, 0))
                .part("right_face_metal", null, 0, 4, -10, tilt, 0, 0, box(74, 13, -2.5f, -8, -4, 1, 2, 2, 0))
                .part("face_ropes", null, 0, 4, -10, tilt, 0, 0, box(80, 12, -2.5f, -10.1f, -7, 5, 5, 12, 0.2f))
                .part("left_rein", null, 0, 4, -10, box(44, 10, 2.6f, -6, -6, 0, 3, 16, 0))
                .part("right_rein", null, 0, 4, -10, box(44, 5, -2.6f, -6, -6, 0, 3, 16, 0))
                .build();
    }

    /** ModelHorse.setLivingAnimations (mouth openness and the tail-swish counter are not tracked, so zero), plus render's part visibility. */
    static void animate(Motion motion, ModelPose pose) {
        float g = motion.limbSwingAmount;
        float yaw = Math.max(-20f, Math.min(20f, motion.headYaw)) * PI / 180f;
        float lean = (float) Math.toRadians(motion.headPitch);
        if (g > 0.2f) {
            lean += cos(motion.limbSwing * 0.4f) * 0.15f * g;
        }
        float m = motion.grazing;
        float n = motion.rearing;
        float o = 1f - n;
        float other = 1f - Math.max(n, m);
        float q = motion.ageInTicks;
        float r = cos(motion.limbSwing * 0.6662f + PI);
        float s = r * 0.8f * g;

        float headX = n * (PI / 12f + lean) + m * 2.18166f + other * (PI / 6f + lean);
        float headY = n * yaw + other * yaw;
        float headPivotY = n * -6f + m * 11f + other * 4f;
        float headPivotZ = n * -1f + m * -10f + other * -10f;
        pose.pivot(HEAD, 0, headPivotY, headPivotZ);
        pose.rotation(HEAD, headX, headY, 0);
        for (int part : HEAD_FOLLOWERS) {
            pose.pivot(part, 0, headPivotY, headPivotZ);
            pose.rotation(part, headX, headY, 0);
        }
        pose.pivot(MULE_LEFT_EAR, 0, headPivotY, headPivotZ);
        pose.rotation(MULE_LEFT_EAR, headX, headY, PI / 12f);
        pose.pivot(MULE_RIGHT_EAR, 0, headPivotY, headPivotZ);
        pose.rotation(MULE_RIGHT_EAR, headX, headY, -PI / 12f);
        float mouth = motion.mouth;
        pose.pivot(HEAD_UPPER, 0, 0.02f, 0.02f - mouth);
        pose.pivot(HEAD_LOWER, 0, 0, mouth);
        pose.rotation(HEAD_UPPER, -0.09424778f * mouth, 0, 0);
        pose.rotation(HEAD_LOWER, PI / 20f * mouth, 0, 0);

        float bodyX = n * -45f * PI / 180f;
        pose.rotation(BODY, bodyX, 0, 0);
        float tailPivotY = n * 9f + o * 3f;
        float chestY = n * 5.5f + o * 3f;
        float chestZ = n * 15f + o * 10f;
        pose.pivot(RIGHT_CHEST, 4.5f, chestY, chestZ);
        pose.rotation(RIGHT_CHEST, -s / 5f, PI / 2f, 0);
        pose.rotation(LEFT_CHEST, s / 5f, PI / 2f, 0);

        float w = PI / 12f * n;
        float x = cos(q * 0.6f + PI);
        float frontY = -2f * n + 9f * o;
        float frontZ = -2f * n + -8f * o;
        pose.pivot(FRONT_LEFT_LEG, 4, frontY, frontZ);
        pose.pivot(FRONT_RIGHT_LEG, -4, frontY, frontZ);
        float swingBack = r * 0.5f * g;
        shin(pose, BACK_LEFT_SHIN, BACK_LEFT_HOOF, 4, 9f, 11f, w + o * -swingBack);
        shin(pose, BACK_RIGHT_SHIN, BACK_RIGHT_HOOF, -4, 9f, 11f, w + o * swingBack);
        float frontLeft = (-PI / 3f + x) * n + s * o;
        float frontRight = (-PI / 3f + -x) * n + -s * o;
        shin(pose, FRONT_LEFT_SHIN, FRONT_LEFT_HOOF, 4, frontY, frontZ, frontLeft);
        shin(pose, FRONT_RIGHT_SHIN, FRONT_RIGHT_HOOF, -4, frontY, frontZ, frontRight);
        pose.rotation(BACK_LEFT_LEG, w + -swingBack * o, 0, 0);
        float backLeftShin = -0.08726646f * n + (-swingBack - Math.max(0f, swingBack)) * o;
        pose.rotation(BACK_LEFT_SHIN, backLeftShin, 0, 0);
        pose.rotation(BACK_LEFT_HOOF, backLeftShin, 0, 0);
        pose.rotation(BACK_RIGHT_LEG, w + swingBack * o, 0, 0);
        float backRightShin = -0.08726646f * n + (swingBack - Math.max(0f, -swingBack)) * o;
        pose.rotation(BACK_RIGHT_SHIN, backRightShin, 0, 0);
        pose.rotation(BACK_RIGHT_HOOF, backRightShin, 0, 0);
        pose.rotation(FRONT_LEFT_LEG, frontLeft, 0, 0);
        float frontLeftShin = (frontLeft + PI * Math.max(0f, 0.2f + x * 0.2f)) * n
                + (s + Math.max(0f, swingBack)) * o;
        pose.rotation(FRONT_LEFT_SHIN, frontLeftShin, 0, 0);
        pose.rotation(FRONT_LEFT_HOOF, frontLeftShin, 0, 0);
        pose.rotation(FRONT_RIGHT_LEG, frontRight, 0, 0);
        float frontRightShin = (frontRight + PI * Math.max(0f, 0.2f - x * 0.2f)) * n
                + (-s + Math.max(0f, -swingBack)) * o;
        pose.rotation(FRONT_RIGHT_SHIN, frontRightShin, 0, 0);
        pose.rotation(FRONT_RIGHT_HOOF, frontRightShin, 0, 0);

        if (motion.saddled && !motion.foal) {
            float bottomY = n * 0.5f + o * 2f;
            float bottomZ = n * 11f + o * 2f;
            pose.pivot(SADDLE_BOTTOM, 0, bottomY, bottomZ);
            pose.pivot(SADDLE_FRONT, 0, bottomY, bottomZ);
            pose.pivot(SADDLE_BACK, 0, bottomY, bottomZ);
            pose.pivot(LEFT_SADDLE_ROPE, 5, bottomY, bottomZ);
            pose.pivot(LEFT_SADDLE_METAL, 5, bottomY, bottomZ);
            pose.pivot(RIGHT_SADDLE_ROPE, -5, bottomY, bottomZ);
            pose.pivot(RIGHT_SADDLE_METAL, -5, bottomY, bottomZ);
            pose.pivot(LEFT_CHEST, -7.5f, chestY, chestZ);
            pose.rotation(SADDLE_BOTTOM, bodyX, 0, 0);
            pose.rotation(SADDLE_FRONT, bodyX, 0, 0);
            pose.rotation(SADDLE_BACK, bodyX, 0, 0);
            for (int part : RIDING_FOLLOWERS) {
                pose.pivot(part, 0, headPivotY, headPivotZ);
                pose.rotation(part, headX, headY, 0);
            }
            pose.pivot(LEFT_REIN, 0, headPivotY, headPivotZ);
            pose.pivot(RIGHT_REIN, 0, headPivotY, headPivotZ);
            pose.rotation(LEFT_REIN, lean, headY, 0);
            pose.rotation(RIGHT_REIN, lean, headY, 0);
            // A rider holds the stirrups up; otherwise they swing with the stride.
            float swingX = motion.ridden ? -PI / 3f : s / 3f;
            float swingZ = motion.ridden ? 0f : s / 5f;
            pose.rotation(LEFT_SADDLE_ROPE, swingX, 0, swingZ);
            pose.rotation(LEFT_SADDLE_METAL, swingX, 0, swingZ);
            pose.rotation(RIGHT_SADDLE_ROPE, swingX, 0, -swingZ);
            pose.rotation(RIGHT_SADDLE_METAL, swingX, 0, -swingZ);
        }

        float t = Math.min(0f, -1.3089f + g * 1.5f);
        float tailYaw = 0f;
        if (motion.tailSwish) {
            tailYaw = cos(q * 0.7f);
            t = 0f;
        }
        pose.pivot(TAIL_BASE, 0, tailPivotY, 14);
        pose.pivot(TAIL_MIDDLE, 0, tailPivotY, 14);
        pose.pivot(TAIL_TIP, 0, tailPivotY, 14);
        pose.rotation(TAIL_BASE, t, tailYaw, 0);
        pose.rotation(TAIL_MIDDLE, t, tailYaw, 0);
        pose.rotation(TAIL_TIP, -0.2618f + t, tailYaw, 0);

        // ModelHorse.render: which parts draw.
        boolean adult = !motion.foal;
        pose.visible(MULE_LEFT_EAR, motion.longEars);
        pose.visible(MULE_RIGHT_EAR, motion.longEars);
        pose.visible(HORSE_LEFT_EAR, !motion.longEars);
        pose.visible(HORSE_RIGHT_EAR, !motion.longEars);
        boolean saddle = adult && motion.saddled;
        for (int part = SADDLE_BOTTOM; part <= RIGHT_FACE_METAL; part++) {
            pose.visible(part, saddle);
        }
        pose.visible(FACE_ROPES, saddle);
        pose.visible(LEFT_REIN, saddle && motion.ridden);
        pose.visible(RIGHT_REIN, saddle && motion.ridden);
        pose.visible(LEFT_CHEST, adult && motion.chested);
        pose.visible(RIGHT_CHEST, adult && motion.chested);
    }

    /** A shin hangs {@code 7} units below its leg's pivot along the leg's angle; the hoof follows the shin. */
    private static void shin(ModelPose pose, int shin, int hoof, float x, float legY, float legZ, float angle) {
        float y = legY + sin(PI / 2f + angle) * 7f;
        float z = legZ + cos(PI * 3f / 2f + angle) * 7f;
        pose.pivot(shin, x, y, z);
        pose.pivot(hoof, x, y, z);
    }
}
