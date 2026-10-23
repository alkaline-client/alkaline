package alkaline.renderer.model;

import static alkaline.renderer.model.MobModels.box;
import static alkaline.renderer.model.MobModels.cos;
import static alkaline.renderer.model.MobModels.mirrored;
import static alkaline.renderer.model.MobModels.sin;

import alkaline.renderer.model.EntityModels.Motion;

/**
 * The guardian (ModelGuardian), the wither (ModelWither) and the ender dragon (ModelDragon), whose bodies are
 * built from many parts the renderer bends every frame.
 */
final class BossModels {
    private static final float PI = (float) Math.PI;

    static final int GUARDIAN_BODY = 0, GUARDIAN_SPINES = 1, GUARDIAN_EYE = 13, GUARDIAN_TAIL0 = 14,
            GUARDIAN_TAIL1 = 15, GUARDIAN_TAIL2 = 16;
    static final int WITHER_HEAD = 0, WITHER_RIBS = 3, WITHER_SPINE = 4, WITHER_TAIL = 5;
    static final int DRAGON_ROOT = 0, DRAGON_NECK = 1, DRAGON_HEAD = 6, DRAGON_JAW = 7, DRAGON_BANK = 8,
            DRAGON_BODY = 9, DRAGON_WINGS = 10, DRAGON_LEFT_WINGS = 18, DRAGON_TAIL = 26;
    /** Part offsets inside one side's eight limb parts. */
    private static final int WING = 0, WING_TIP = 1, FRONT_LEG = 2, FRONT_TIP = 3, FRONT_FOOT = 4, REAR_LEG = 5,
            REAR_TIP = 6, REAR_FOOT = 7;

    private BossModels() {
    }

    // ---- guardian ------------------------------------------------------------------------------------------

    /** ModelGuardian: a spiked box body, twelve spines, an eye, and a three-piece tail. */
    static CuboidModel guardian() {
        CuboidModel.Builder builder = CuboidModel.builder("guardian", 64, 64)
                .part("body", null, 0, 0, 0, box(0, 0, -6, 10, -8, 12, 12, 16, 0), box(0, 28, -8, 10, -6, 2, 12, 12, 0),
                        mirrored(0, 28, 6, 10, -6, 2, 12, 12, 0), box(16, 40, -6, 8, -6, 12, 2, 12, 0),
                        box(16, 40, -6, 22, -6, 12, 2, 12, 0));
        for (int spine = 0; spine < 12; spine++) {
            builder.part("spine" + spine, "body", 0, 0, 0, box(0, 0, -1, -4.5f, -1, 2, 9, 2, 0));
        }
        return builder.part("eye", "body", 0, 0, -8.25f, box(8, 0, -1, 15, 0, 2, 2, 1, 0))
                .part("tail0", "body", 0, 0, 0, box(40, 0, -2, 14, 7, 4, 4, 8, 0))
                .part("tail1", "tail0", -1.5f, 0.5f, 14, box(0, 54, 0, 14, 0, 3, 3, 7, 0))
                .part("tail2", "tail1", 0.5f, 0.5f, 6, box(41, 32, 0, 14, 0, 2, 2, 6, 0),
                        box(25, 19, 1, 10.5f, 3, 1, 9, 9, 0))
                .build();
    }

    private static final float[] SPINE_X = {1.75f, 0.25f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, 1.25f, 0.75f, 0f, 0f};
    private static final float[] SPINE_Y = {0f, 0f, 0f, 0f, 0.25f, 1.75f, 1.25f, 0.75f, 0f, 0f, 0f, 0f};
    private static final float[] SPINE_Z = {0f, 0f, 0.25f, 1.75f, 0f, 0f, 0f, 0f, 0f, 0f, 0.75f, 1.25f};
    private static final float[] SPINE_PX = {0f, 0f, 8f, -8f, -8f, 8f, 8f, -8f, 0f, 0f, 8f, -8f};
    private static final float[] SPINE_PY = {-8f, -8f, -8f, -8f, 0f, 0f, 0f, 0f, 8f, 8f, 8f, 8f};
    private static final float[] SPINE_PZ = {8f, -8f, 0f, 0f, -8f, -8f, 8f, 8f, 8f, -8f, 0f, 0f};

    /** ModelGuardian.setRotationAngles; the spines draw in unless the guardian has pulled them back. */
    static void guardian(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        pose.rotation(GUARDIAN_BODY, headPitch, headYaw, 0);
        float pulled = (motion.retracted ? 1f : 0f) * 0.55f;
        for (int spine = 0; spine < 12; spine++) {
            float spread = 1f + cos(motion.ageInTicks * 1.5f + spine) * 0.01f - pulled;
            int part = GUARDIAN_SPINES + spine;
            pose.rotation(part, PI * SPINE_X[spine], PI * SPINE_Y[spine], PI * SPINE_Z[spine]);
            pose.pivot(part, SPINE_PX[spine] * spread, 16f + SPINE_PY[spine] * spread, SPINE_PZ[spine] * spread);
        }
        pose.pivot(GUARDIAN_EYE, motion.eyeX, motion.eyeLow ? 1f : 0f, -8.25f);
        float tail = motion.ageInTicks * (motion.retracted ? 0.5f : 0.125f);
        pose.rotation(GUARDIAN_TAIL0, 0, sin(tail) * PI * 0.05f, 0);
        pose.rotation(GUARDIAN_TAIL1, 0, sin(tail) * PI * 0.1f, 0);
        pose.rotation(GUARDIAN_TAIL2, 0, sin(tail) * PI * 0.15f, 0);
    }

    // ---- wither --------------------------------------------------------------------------------------------

    /** ModelWither: three heads, a shoulder bar, a spine with ribs, and a tail. */
    static CuboidModel wither() {
        return CuboidModel.builder("wither", 64, 64)
                .part("head", null, 0, 0, 0, box(0, 0, -4, -4, -4, 8, 8, 8, 0))
                .part("head_right", null, -8, 4, 0, box(32, 0, -4, -4, -4, 6, 6, 6, 0))
                .part("head_left", null, 10, 4, 0, box(32, 0, -4, -4, -4, 6, 6, 6, 0))
                .part("shoulders", null, 0, 0, 0, box(0, 16, -10, 3.9f, -0.5f, 20, 3, 3, 0))
                .part("ribcage", null, -2, 6.9f, -0.5f, box(0, 22, 0, 0, 0, 3, 10, 3, 0),
                        box(24, 22, -4, 1.5f, 0.5f, 11, 2, 2, 0), box(24, 22, -4, 4, 0.5f, 11, 2, 2, 0),
                        box(24, 22, -4, 6.5f, 0.5f, 11, 2, 2, 0))
                .part("tail", null, 0, 0, 0, box(12, 22, 0, 0, 0, 3, 6, 3, 0))
                .build();
    }

    static void wither(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        float wave = cos(motion.ageInTicks * 0.1f);
        float ribs = (0.065f + 0.05f * wave) * PI;
        pose.rotation(WITHER_SPINE, ribs, 0, 0);
        pose.pivot(WITHER_TAIL, -2, 6.9f + cos(ribs) * 10f, -0.5f + sin(ribs) * 10f);
        pose.rotation(WITHER_TAIL, (0.265f + 0.1f * wave) * PI, 0, 0);
        pose.rotation(WITHER_HEAD, headPitch, headYaw, 0);
    }

    // ---- ender dragon --------------------------------------------------------------------------------------

    private static final ModelBox[] NECK_BOXES = {box(192, 104, -5, -5, -5, 10, 10, 10, 0),
            box(48, 0, -1, -9, -3, 2, 4, 6, 0)};

    /**
     * ModelDragon: a dummy root carrying the renderer's per-frame lift and pitch, the neck, head and jaw, a
     * dummy "bank" part the body, wings and legs hang from (the renderer rolls them together), both sides' wings
     * and legs, and twelve tail segments. The left side is the right side reflected: pivots negate x, boxes
     * mirror across the axis, and yaw and roll flip sign when posed.
     */
    static CuboidModel dragon() {
        CuboidModel.Builder builder = CuboidModel.builder("ender_dragon", 256, 256)
                .part("root", null, 0, 0, 0);
        for (int segment = 0; segment < 5; segment++) {
            builder.part("neck" + segment, "root", 0, 0, 0, NECK_BOXES);
        }
        builder.part("head", "root", 0, 0, 0, box(176, 44, -6, -1, -24, 12, 5, 16, 0),
                        box(112, 30, -8, -8, -10, 16, 16, 16, 0), mirrored(0, 0, -5, -12, -4, 2, 4, 6, 0),
                        mirrored(112, 0, -5, -3, -22, 2, 2, 4, 0), box(0, 0, 3, -12, -4, 2, 4, 6, 0),
                        box(112, 0, 3, -3, -22, 2, 2, 4, 0))
                .part("jaw", "head", 0, 4, -8, box(176, 65, -6, 0, -16, 12, 4, 16, 0))
                .part("bank", "root", 0, 16, 0)
                .part("body", "bank", 0, -12, 8, box(0, 0, -12, 0, -16, 24, 24, 64, 0),
                        box(220, 53, -1, -6, -10, 2, 6, 12, 0), box(220, 53, -1, -6, 10, 2, 6, 12, 0),
                        box(220, 53, -1, -6, 30, 2, 6, 12, 0));
        side(builder, false);
        side(builder, true);
        for (int segment = 0; segment < 12; segment++) {
            builder.part("tail" + segment, "root", 0, 0, 0, NECK_BOXES);
        }
        return builder.build();
    }

    /** One side's wing, front leg and rear leg chains, as the model builds them for the right side. */
    private static void side(CuboidModel.Builder builder, boolean left) {
        String tag = left ? "_l" : "_r";
        float s = left ? -1f : 1f;
        builder.part("wing" + tag, "bank", -12 * s, -11, 2, wingBoxes(left, 0, 8, 8, 88, 112, 88))
                .part("wing_tip" + tag, "wing" + tag, -56 * s, 0, 0, wingBoxes(left, 0, 4, 4, 144, 112, 136))
                .part("front_leg" + tag, "bank", -12 * s, 4, 2, limb(left, 112, 104, -4, -4, -4, 8, 24, 8))
                .part("front_leg_tip" + tag, "front_leg" + tag, 0, 20, -1, limb(left, 226, 138, -3, -1, -3, 6, 24, 6))
                .part("front_foot" + tag, "front_leg_tip" + tag, 0, 23, 0, limb(left, 144, 104, -4, 0, -12, 8, 4, 16))
                .part("rear_leg" + tag, "bank", -16 * s, 0, 42, limb(left, 0, 0, -8, -4, -8, 16, 32, 16))
                .part("rear_leg_tip" + tag, "rear_leg" + tag, 0, 32, -4, limb(left, 196, 0, -6, -2, 0, 12, 32, 12))
                .part("rear_foot" + tag, "rear_leg_tip" + tag, 0, 31, 4, limb(left, 112, 0, -9, 0, -20, 18, 6, 24));
    }

    /** A wing part's bone (height {@code bone}) and its flat skin, reflected for the left wing. */
    private static ModelBox[] wingBoxes(boolean left, int unused, int boneHeight, int boneDepth, int skinV, int boneU,
                                        int boneV) {
        float y = -boneHeight / 2f;
        float z = -boneDepth / 2f;
        return new ModelBox[]{reflect(left, boneU, boneV, -56, y, z, 56, boneHeight, boneDepth),
                reflect(left, -56, skinV, -56, 0, 2, 56, 0, 56)};
    }

    private static ModelBox[] limb(boolean left, int u, int v, float x, float y, float z, int w, int h, int d) {
        return new ModelBox[]{reflect(left, u, v, x, y, z, w, h, d)};
    }

    private static ModelBox reflect(boolean left, int u, int v, float x, float y, float z, int w, int h, int d) {
        return left ? mirrored(u, v, -(x + w), y, z, w, h, d, 0) : box(u, v, x, y, z, w, h, d, 0);
    }

    /** ModelDragon.render's per-frame pose; {@code motion.movement} holds {yaw, y} history for offsets 0..23. */
    static void dragon(Motion motion, ModelPose pose) {
        float[] move = motion.movement;
        float k = motion.animTime;
        float cycle = k * PI * 2f;
        pose.rotation(DRAGON_JAW, (sin(cycle) + 1f) * 0.2f, 0, 0);
        float l = sin(cycle - 1f) + 1f;
        l = (l * l + l * 2f) * 0.05f;
        pose.pivot(DRAGON_ROOT, 0, (l - 2f) * 16f, -3f * 16f);
        pose.rotation(DRAGON_ROOT, (float) Math.toRadians(l * 2f), 0, 0);
        float o = 1.5f;
        float referenceYaw = move[6 * 2];
        float referenceY = move[6 * 2 + 1];
        float p = wrap(move[5 * 2] - move[10 * 2]);
        float q = wrap(move[5 * 2] + p / 2f);
        float m = 20f;
        float s = -12f;
        float n = 0f;
        for (int segment = 0; segment < 5; segment++) {
            int at = (5 - segment) * 2;
            float bob = cos(segment * 0.45f + cycle) * 0.15f;
            float yaw = (float) Math.toRadians(wrap(move[at] - referenceYaw)) * o;
            float pitch = bob + (float) Math.toRadians(move[at + 1] - referenceY) * o * 5f;
            float roll = -(float) Math.toRadians(wrap(move[at] - q)) * o;
            int part = DRAGON_NECK + segment;
            pose.pivot(part, n, m, s);
            pose.rotation(part, pitch, yaw, roll);
            m += sin(pitch) * 10f;
            s -= cos(yaw) * cos(pitch) * 10f;
            n -= sin(yaw) * cos(pitch) * 10f;
        }
        pose.pivot(DRAGON_HEAD, n, m, s);
        pose.rotation(DRAGON_HEAD, 0, (float) Math.toRadians(wrap(move[0] - referenceYaw)),
                -(float) Math.toRadians(wrap(move[0] - q)));
        pose.rotation(DRAGON_BANK, 0, 0, (float) Math.toRadians(-p * o));

        for (int side = 0; side < 2; side++) {
            int base = side == 0 ? DRAGON_WINGS : DRAGON_LEFT_WINGS;
            float sign = side == 0 ? 1f : -1f;
            pose.rotation(base + WING, 0.125f - cos(cycle) * 0.2f, 0.25f * sign, (sin(cycle) + 0.125f) * 0.8f * sign);
            pose.rotation(base + WING_TIP, 0, 0, -(sin(cycle + 2f) + 0.5f) * 0.75f * sign);
            pose.rotation(base + REAR_LEG, 1f + l * 0.1f, 0, 0);
            pose.rotation(base + REAR_TIP, 0.5f + l * 0.1f, 0, 0);
            pose.rotation(base + REAR_FOOT, 0.75f + l * 0.1f, 0, 0);
            pose.rotation(base + FRONT_LEG, 1.3f + l * 0.1f, 0, 0);
            pose.rotation(base + FRONT_TIP, -0.5f - l * 0.1f, 0, 0);
            pose.rotation(base + FRONT_FOOT, 0.75f + l * 0.1f, 0, 0);
        }

        float w = 0f;
        m = 10f;
        s = 60f;
        n = 0f;
        float tailYaw = move[11 * 2];
        float tailY = move[11 * 2 + 1];
        for (int segment = 0; segment < 12; segment++) {
            int at = (12 + segment) * 2;
            w += sin(segment * 0.45f + cycle) * 0.05f;
            float yaw = (wrap(move[at] - tailYaw) * o + 180f) * PI / 180f;
            float pitch = w + (float) Math.toRadians(move[at + 1] - tailY) * o * 5f;
            float roll = (float) Math.toRadians(wrap(move[at] - q)) * o;
            int part = DRAGON_TAIL + segment;
            pose.pivot(part, n, m, s);
            pose.rotation(part, pitch, yaw, roll);
            m += sin(pitch) * 10f;
            s -= cos(yaw) * cos(pitch) * 10f;
            n -= sin(yaw) * cos(pitch) * 10f;
        }
    }

    /** ModelDragon.updateRotations. */
    private static float wrap(float degrees) {
        while (degrees >= 180f) {
            degrees -= 360f;
        }
        while (degrees < -180f) {
            degrees += 360f;
        }
        return degrees;
    }
}
