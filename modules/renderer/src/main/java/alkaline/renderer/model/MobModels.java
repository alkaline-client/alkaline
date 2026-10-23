package alkaline.renderer.model;

import alkaline.renderer.model.EntityModels.Kind;
import alkaline.renderer.model.EntityModels.Motion;

/**
 * Geometry and animation for the small and medium mobs beyond {@link EntityModels}' original set: witch, bat,
 * rabbit, ocelot, silverfish, endermite, magma cube, snow golem and zombie villager. Boxes, pivots, texture sizes
 * and animation constants come from the matching 1.8.9 {@code Model*} classes (named in each method's Javadoc).
 */
final class MobModels {
    private static final float PI = (float) Math.PI;
    private static final float DEGREES = (float) (Math.PI / 180.0);

    // Part indices: the order parts are added below.
    static final int WITCH_HEAD = 0, WITCH_NOSE = 1, WITCH_BODY = 7, WITCH_RIGHT_LEG = 8, WITCH_LEFT_LEG = 9,
            WITCH_ARMS = 10;
    static final int BAT_HEAD = 0, BAT_BODY = 3, BAT_RIGHT_WING = 4, BAT_OUTER_RIGHT_WING = 5, BAT_LEFT_WING = 6,
            BAT_OUTER_LEFT_WING = 7;
    static final int RABBIT_LEFT_FOOT = 0, RABBIT_RIGHT_FOOT = 1, RABBIT_LEFT_THIGH = 2, RABBIT_RIGHT_THIGH = 3,
            RABBIT_BODY = 4, RABBIT_LEFT_ARM = 5, RABBIT_RIGHT_ARM = 6, RABBIT_HEAD = 7, RABBIT_RIGHT_EAR = 8,
            RABBIT_LEFT_EAR = 9, RABBIT_TAIL = 10, RABBIT_NOSE = 11;
    static final int OCELOT_HEAD = 0, OCELOT_BODY = 1, OCELOT_TAIL = 2, OCELOT_TAIL2 = 3, OCELOT_BACK_LEFT = 4,
            OCELOT_BACK_RIGHT = 5, OCELOT_FRONT_LEFT = 6, OCELOT_FRONT_RIGHT = 7;

    private MobModels() {
    }

    static CuboidModel build(Kind kind) {
        return switch (kind) {
            case WITCH -> witch();
            case BAT -> bat();
            case RABBIT -> rabbit();
            case OCELOT -> ocelot();
            case SILVERFISH -> segments("silverfish", SILVERFISH_BOXES, SILVERFISH_UV, true);
            case ENDERMITE -> segments("endermite", ENDERMITE_BOXES, ENDERMITE_UV, false);
            case MAGMA_CUBE -> magmaCube();
            case SNOW_GOLEM -> snowGolem();
            case ZOMBIE_VILLAGER -> zombieVillager();
            case MOOSHROOM_MUSHROOMS -> mushrooms();
            default -> throw new IllegalArgumentException("No mob model for " + kind);
        };
    }

    static void animate(Kind kind, Motion motion, ModelPose pose) {
        float headYaw = (float) Math.toRadians(motion.headYaw);
        float headPitch = (float) Math.toRadians(motion.headPitch);
        switch (kind) {
            case WITCH -> witch(motion, pose, headYaw, headPitch);
            case BAT -> bat(motion, pose, headYaw, headPitch);
            case RABBIT -> rabbit(motion, pose, headYaw, headPitch);
            case OCELOT -> ocelot(motion, pose, headYaw, headPitch);
            case SILVERFISH -> wriggle(motion, pose, SILVERFISH_BOXES.length, 0.05f, 0.2f, true);
            case ENDERMITE -> wriggle(motion, pose, ENDERMITE_BOXES.length, 0.01f, 0.1f, false);
            case SNOW_GOLEM -> snowGolem(pose, headYaw, headPitch);
            case MAGMA_CUBE, MOOSHROOM_MUSHROOMS -> {
            }
            default -> throw new IllegalArgumentException("No mob animation for " + kind);
        }
    }

    // ---- witch ---------------------------------------------------------------------------------------------

    /** ModelWitch (a ModelVillager with a 64x128 texture, a wart on the nose and a four-piece hat). */
    private static CuboidModel witch() {
        return CuboidModel.builder("witch", 64, 128)
                .part("head", null, 0, 0, 0, box(0, 0, -4, -10, -4, 8, 10, 8, 0))
                .part("nose", "head", 0, -2, 0, box(24, 0, -1, -1, -6, 2, 4, 2, 0))
                .part("wart", "nose", 0, -2, 0, box(0, 0, 0, 3, -6.75f, 1, 1, 1, -0.25f))
                .part("hat", "head", -5, -10.03125f, -5, box(0, 64, 0, 0, 0, 10, 2, 10, 0))
                .part("hat2", "hat", 1.75f, -4, 2, -0.05235988f, 0, 0.02617994f, box(0, 76, 0, 0, 0, 7, 4, 7, 0))
                .part("hat3", "hat2", 1.75f, -4, 2, -0.10471976f, 0, 0.05235988f, box(0, 87, 0, 0, 0, 4, 4, 4, 0))
                .part("hat4", "hat3", 1.75f, -2, 2, -PI / 15f, 0, 0.10471976f, box(0, 95, 0, 0, 0, 1, 2, 1, 0.25f))
                .part("body", null, 0, 0, 0, box(16, 20, -4, 0, -3, 8, 12, 6, 0),
                        box(0, 38, -4, 0, -3, 8, 18, 6, 0.5f))
                .part("right_leg", null, -2, 12, 0, box(0, 22, -2, 0, -2, 4, 12, 4, 0))
                .part("left_leg", null, 2, 12, 0, mirrored(0, 22, -2, 0, -2, 4, 12, 4, 0))
                .part("arms", null, 0, 2, 0, box(44, 22, -8, -2, -2, 4, 8, 4, 0),
                        box(44, 22, 4, -2, -2, 4, 8, 4, 0), box(40, 38, -4, 2, -2, 8, 4, 4, 0))
                .build();
    }

    private static void witch(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        float swing = motion.limbSwing * 0.6662f;
        float amount = motion.limbSwingAmount;
        pose.rotation(WITCH_HEAD, headPitch, headYaw, 0);
        pose.pivot(WITCH_ARMS, 0, 3, -1);
        pose.rotation(WITCH_ARMS, -0.75f, 0, 0);
        pose.rotation(WITCH_RIGHT_LEG, cos(swing) * 1.4f * amount * 0.5f, 0, 0);
        pose.rotation(WITCH_LEFT_LEG, cos(swing + PI) * 1.4f * amount * 0.5f, 0, 0);
        float wobble = 0.01f * (motion.entityId % 10);
        pose.rotation(WITCH_NOSE, sin(motion.ticksExisted * wobble) * 4.5f * PI / 180f, 0,
                cos(motion.ticksExisted * wobble) * 2.5f * PI / 180f);
        if (motion.heldItem != 0) {
            // The nose lifts to hold the potion: offsetY 0.1875 and offsetZ -0.09375 blocks.
            pose.rotation(WITCH_NOSE, -0.9f, 0, 0);
            pose.pivot(WITCH_NOSE, 0, -2 + 3f, -1.5f);
        }
    }

    // ---- bat -----------------------------------------------------------------------------------------------

    /** ModelBat. */
    private static CuboidModel bat() {
        return CuboidModel.builder("bat", 64, 64)
                .part("head", null, 0, 0, 0, box(0, 0, -3, -3, -3, 6, 6, 6, 0))
                .part("right_ear", "head", 0, 0, 0, box(24, 0, -4, -6, -2, 3, 4, 1, 0))
                .part("left_ear", "head", 0, 0, 0, mirrored(24, 0, 1, -6, -2, 3, 4, 1, 0))
                .part("body", null, 0, 0, 0, box(0, 16, -3, 4, -3, 6, 12, 6, 0), box(0, 34, -5, 16, 0, 10, 6, 1, 0))
                .part("right_wing", "body", 0, 0, 0, box(42, 0, -12, 1, 1.5f, 10, 16, 1, 0))
                .part("outer_right_wing", "right_wing", -12, 1, 1.5f, box(24, 16, -8, 1, 0, 8, 12, 1, 0))
                .part("left_wing", "body", 0, 0, 0, mirrored(42, 0, 2, 1, 1.5f, 10, 16, 1, 0))
                .part("outer_left_wing", "left_wing", 12, 1, 1.5f, mirrored(24, 16, 0, 1, 0, 8, 12, 1, 0))
                .build();
    }

    private static void bat(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        if (motion.hanging) {
            pose.rotation(BAT_HEAD, headPitch, PI - headYaw, PI);
            pose.pivot(BAT_HEAD, 0, -2, 0);
            pose.pivot(BAT_RIGHT_WING, -3, 0, 3);
            pose.pivot(BAT_LEFT_WING, 3, 0, 3);
            pose.rotation(BAT_BODY, PI, 0, 0);
            pose.rotation(BAT_RIGHT_WING, -PI / 20f, -PI * 2f / 5f, 0);
            pose.rotation(BAT_OUTER_RIGHT_WING, 0, -1.7278761f, 0);
            pose.rotation(BAT_LEFT_WING, -PI / 20f, PI * 2f / 5f, 0);
            pose.rotation(BAT_OUTER_LEFT_WING, 0, 1.7278761f, 0);
        } else {
            float flap = cos(motion.ageInTicks * 1.3f) * PI * 0.25f;
            pose.rotation(BAT_HEAD, headPitch, headYaw, 0);
            pose.rotation(BAT_BODY, PI / 4f + cos(motion.ageInTicks * 0.1f) * 0.15f, 0, 0);
            pose.rotation(BAT_RIGHT_WING, 0, flap, 0);
            pose.rotation(BAT_LEFT_WING, 0, -flap, 0);
            pose.rotation(BAT_OUTER_RIGHT_WING, 0, flap * 0.5f, 0);
            pose.rotation(BAT_OUTER_LEFT_WING, 0, -flap * 0.5f, 0);
        }
    }

    // ---- rabbit --------------------------------------------------------------------------------------------

    /** ModelRabbit: no box is mirrored (the model sets {@code mirror} only after adding each box). */
    private static CuboidModel rabbit() {
        return CuboidModel.builder("rabbit", 64, 32)
                .part("left_foot", null, 3, 17.5f, 3.7f, box(26, 24, -1, 5.5f, -3.7f, 2, 1, 7, 0))
                .part("right_foot", null, -3, 17.5f, 3.7f, box(8, 24, -1, 5.5f, -3.7f, 2, 1, 7, 0))
                .part("left_thigh", null, 3, 17.5f, 3.7f, -PI / 9f, 0, 0, box(30, 15, -1, 0, 0, 2, 4, 5, 0))
                .part("right_thigh", null, -3, 17.5f, 3.7f, -PI / 9f, 0, 0, box(16, 15, -1, 0, 0, 2, 4, 5, 0))
                .part("body", null, 0, 19, 8, -PI / 9f, 0, 0, box(0, 0, -3, -2, -10, 6, 5, 10, 0))
                .part("left_arm", null, 3, 17, -1, -PI / 18f, 0, 0, box(8, 15, -1, 0, -1, 2, 7, 2, 0))
                .part("right_arm", null, -3, 17, -1, -PI / 18f, 0, 0, box(0, 15, -1, 0, -1, 2, 7, 2, 0))
                .part("head", null, 0, 16, -1, box(32, 0, -2.5f, -4, -5, 5, 4, 5, 0))
                .part("right_ear", null, 0, 16, -1, 0, -PI / 12f, 0, box(52, 0, -2.5f, -9, -1, 2, 5, 1, 0))
                .part("left_ear", null, 0, 16, -1, 0, PI / 12f, 0, box(58, 0, 0.5f, -9, -1, 2, 5, 1, 0))
                .part("tail", null, 0, 20, 7, -0.3490659f, 0, 0, box(52, 6, -1.5f, -1.5f, 0, 3, 3, 2, 0))
                .part("nose", null, 0, 16, -1, box(32, 9, -0.5f, -2.5f, -5.5f, 1, 1, 1, 0))
                .build();
    }

    private static void rabbit(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        pose.rotation(RABBIT_NOSE, headPitch, headYaw, 0);
        pose.rotation(RABBIT_HEAD, headPitch, headYaw, 0);
        pose.rotation(RABBIT_RIGHT_EAR, headPitch, headYaw - PI / 12f, 0);
        pose.rotation(RABBIT_LEFT_EAR, headPitch, headYaw + PI / 12f, 0);
        float hop = sin(motion.jump * PI);
        float thigh = (hop * 50f - 21f) * DEGREES;
        float foot = hop * 50f * DEGREES;
        float arm = (hop * -40f - 11f) * DEGREES;
        pose.rotation(RABBIT_LEFT_THIGH, thigh, 0, 0);
        pose.rotation(RABBIT_RIGHT_THIGH, thigh, 0, 0);
        pose.rotation(RABBIT_LEFT_FOOT, foot, 0, 0);
        pose.rotation(RABBIT_RIGHT_FOOT, foot, 0, 0);
        pose.rotation(RABBIT_LEFT_ARM, arm, 0, 0);
        pose.rotation(RABBIT_RIGHT_ARM, arm, 0, 0);
    }

    // ---- ocelot --------------------------------------------------------------------------------------------

    /** ModelOcelot (the head's named boxes use texture offsets main 0,0, nose 0,24 and ears 0,10 / 6,10). */
    private static CuboidModel ocelot() {
        return CuboidModel.builder("ocelot", 64, 32)
                .part("head", null, 0, 15, -9, box(0, 0, -2.5f, -2, -3, 5, 4, 5, 0), box(0, 24, -1.5f, 0, -4, 3, 2, 2, 0),
                        box(0, 10, -2, -3, 0, 1, 1, 2, 0), box(6, 10, 1, -3, 0, 1, 1, 2, 0))
                .part("body", null, 0, 12, -10, box(20, 0, -2, 3, -8, 4, 16, 6, 0))
                .part("tail", null, 0, 15, 8, 0.9f, 0, 0, box(0, 15, -0.5f, 0, 0, 1, 8, 1, 0))
                .part("tail2", null, 0, 20, 14, box(4, 15, -0.5f, 0, 0, 1, 8, 1, 0))
                .part("back_left_leg", null, 1.1f, 18, 5, box(8, 13, -1, 0, 1, 2, 6, 2, 0))
                .part("back_right_leg", null, -1.1f, 18, 5, box(8, 13, -1, 0, 1, 2, 6, 2, 0))
                .part("front_left_leg", null, 1.2f, 13.8f, -5, box(40, 0, -1, 0, 0, 2, 10, 2, 0))
                .part("front_right_leg", null, -1.2f, 13.8f, -5, box(40, 0, -1, 0, 0, 2, 10, 2, 0))
                .build();
    }

    /** ModelOcelot.setLivingAnimations (the pose per state) followed by setRotationAngles. */
    private static void ocelot(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        float swing = motion.limbSwing * 0.6662f;
        float amount = motion.limbSwingAmount;
        // 0 sneaking, 1 walking, 2 sprinting, 3 sitting.
        int state = motion.sneaking ? 0 : motion.sprinting ? 2 : motion.sitting ? 3 : 1;
        if (state == 0) {
            pose.pivot(OCELOT_BODY, 0, 13, -10);
            pose.pivot(OCELOT_HEAD, 0, 17, -9);
            pose.pivot(OCELOT_TAIL, 0, 16, 8);
            pose.pivot(OCELOT_TAIL2, 0, 16, 16);
            pose.rotation(OCELOT_TAIL, PI / 2f, 0, 0);
        } else if (state == 2) {
            pose.pivot(OCELOT_TAIL2, 0, 15, 16);
            pose.rotation(OCELOT_TAIL, PI / 2f, 0, 0);
        } else if (state == 3) {
            pose.pivot(OCELOT_BODY, 0, 8, -5);
            pose.rotation(OCELOT_BODY, PI / 4f, 0, 0);
            pose.pivot(OCELOT_HEAD, 0, 11.7f, -8);
            pose.pivot(OCELOT_TAIL, 0, 23, 6);
            pose.rotation(OCELOT_TAIL, 1.7278761f, 0, 0);
            pose.pivot(OCELOT_TAIL2, 0, 22, 13.2f);
            pose.rotation(OCELOT_TAIL2, 2.670354f, 0, 0);
            pose.rotation(OCELOT_FRONT_LEFT, -PI / 20f, 0, 0);
            pose.rotation(OCELOT_FRONT_RIGHT, -PI / 20f, 0, 0);
            pose.pivot(OCELOT_FRONT_LEFT, 1.2f, 15.8f, -7);
            pose.pivot(OCELOT_FRONT_RIGHT, -1.2f, 15.8f, -7);
            pose.rotation(OCELOT_BACK_LEFT, -PI / 2f, 0, 0);
            pose.rotation(OCELOT_BACK_RIGHT, -PI / 2f, 0, 0);
            pose.pivot(OCELOT_BACK_LEFT, 1.1f, 21, 1);
            pose.pivot(OCELOT_BACK_RIGHT, -1.1f, 21, 1);
        }
        pose.rotation(OCELOT_HEAD, headPitch, headYaw, 0);
        if (state == 3) {
            return;
        }
        pose.rotation(OCELOT_BODY, PI / 2f, 0, 0);
        if (state == 2) {
            pose.rotation(OCELOT_BACK_LEFT, cos(swing) * amount, 0, 0);
            pose.rotation(OCELOT_BACK_RIGHT, cos(swing + 0.3f) * amount, 0, 0);
            pose.rotation(OCELOT_FRONT_LEFT, cos(swing + PI + 0.3f) * amount, 0, 0);
            pose.rotation(OCELOT_FRONT_RIGHT, cos(swing + PI) * amount, 0, 0);
            pose.rotation(OCELOT_TAIL2, 1.7278761f + PI / 10f * cos(motion.limbSwing) * amount, 0, 0);
        } else {
            pose.rotation(OCELOT_BACK_LEFT, cos(swing) * amount, 0, 0);
            pose.rotation(OCELOT_BACK_RIGHT, cos(swing + PI) * amount, 0, 0);
            pose.rotation(OCELOT_FRONT_LEFT, cos(swing + PI) * amount, 0, 0);
            pose.rotation(OCELOT_FRONT_RIGHT, cos(swing) * amount, 0, 0);
            float tail = state == 1 ? PI / 4f : 0.47123894f;
            pose.rotation(OCELOT_TAIL2, 1.7278761f + tail * cos(motion.limbSwing) * amount, 0, 0);
        }
    }

    // ---- silverfish and endermite --------------------------------------------------------------------------

    /** ModelSilverfish: {width, height, depth} of each body segment, and the wings' UV origins below. */
    private static final int[][] SILVERFISH_BOXES = {{3, 2, 2}, {4, 3, 2}, {6, 4, 3}, {3, 3, 3}, {2, 2, 3}, {2, 1, 2},
            {1, 1, 2}};
    private static final int[][] SILVERFISH_UV = {{0, 0}, {0, 4}, {0, 9}, {0, 16}, {0, 22}, {11, 0}, {13, 4}};
    /** ModelEnderMite. */
    private static final int[][] ENDERMITE_BOXES = {{4, 3, 2}, {6, 4, 5}, {3, 3, 1}, {1, 2, 1}};
    private static final int[][] ENDERMITE_UV = {{0, 0}, {0, 5}, {0, 14}, {0, 18}};

    /**
     * A row of segments, each standing on the ground and spaced front to back by the mean of their depths; the
     * silverfish adds three wing slabs over segments 2, 4 and 1.
     */
    private static CuboidModel segments(String id, int[][] sizes, int[][] uv, boolean wings) {
        CuboidModel.Builder builder = CuboidModel.builder(id, 64, 32);
        float[] z = new float[sizes.length];
        float cursor = -3.5f;
        for (int index = 0; index < sizes.length; index++) {
            int[] size = sizes[index];
            builder.part("segment" + index, null, 0, 24 - size[1], cursor,
                    box(uv[index][0], uv[index][1], size[0] * -0.5f, 0, size[2] * -0.5f, size[0], size[1], size[2], 0));
            z[index] = cursor;
            if (index < sizes.length - 1) {
                cursor += (size[2] + sizes[index + 1][2]) * 0.5f;
            }
        }
        if (wings) {
            builder.part("wing0", null, 0, 16, z[2], box(20, 0, -5, 0, sizes[2][2] * -0.5f, 10, 8, sizes[2][2], 0))
                    .part("wing1", null, 0, 20, z[4], box(20, 11, -3, 0, sizes[4][2] * -0.5f, 6, 4, sizes[4][2], 0))
                    .part("wing2", null, 0, 19, z[1], box(20, 18, -3, 0, sizes[4][2] * -0.5f, 6, 5, sizes[1][2], 0));
        }
        return builder.build();
    }

    /** The shared wriggle of ModelSilverfish / ModelEnderMite; {@code yaw} and {@code sway} differ per mob. */
    private static void wriggle(Motion motion, ModelPose pose, int segments, float yaw, float sway, boolean wings) {
        for (int index = 0; index < segments; index++) {
            float phase = motion.ageInTicks * 0.9f + index * 0.15f * PI;
            int distance = Math.abs(index - 2);
            pose.rotation(index, 0, cos(phase) * PI * yaw * (1 + distance), 0);
            pose.pivot(index, sway * PI * sin(phase) * distance, pose.pivotY(index), pose.pivotZ(index));
        }
        if (wings) {
            pose.rotation(segments, 0, pose.rotationY(2), 0);
            pose.rotation(segments + 1, 0, pose.rotationY(4), 0);
            pose.pivot(segments + 1, pose.pivotX(4), pose.pivotY(segments + 1), pose.pivotZ(segments + 1));
            pose.rotation(segments + 2, 0, pose.rotationY(1), 0);
            pose.pivot(segments + 2, pose.pivotX(1), pose.pivotY(segments + 2), pose.pivotZ(segments + 2));
        }
    }

    // ---- magma cube, snow golem, zombie villager, mooshroom mushrooms -------------------------------------------

    /** ModelMagmaCube: the core, then eight one-texel slabs (its squish offsets are zero without a squish factor). */
    private static CuboidModel magmaCube() {
        CuboidModel.Builder builder = CuboidModel.builder("magma_cube", 64, 32)
                .part("core", null, 0, 0, 0, box(0, 16, -2, 18, -2, 4, 4, 4, 0));
        for (int index = 0; index < 8; index++) {
            int u = index == 2 || index == 3 ? 24 : 0;
            int v = index == 2 ? 10 : index == 3 ? 19 : index;
            builder.part("segment" + index, null, 0, 0, 0, box(u, v, -4, 16 + index, -4, 8, 1, 8, 0));
        }
        return builder.build();
    }

    static final int SNOW_BODY = 0, SNOW_BOTTOM = 1, SNOW_HEAD = 2, SNOW_RIGHT_HAND = 3, SNOW_LEFT_HAND = 4;

    /** ModelSnowMan: three inflate -0.5 snowballs and two stick arms, on a 64x64 texture. */
    private static CuboidModel snowGolem() {
        return CuboidModel.builder("snow_golem", 64, 64)
                .part("body", null, 0, 13, 0, box(0, 16, -5, -10, -5, 10, 10, 10, -0.5f))
                .part("bottom_body", null, 0, 24, 0, box(0, 36, -6, -12, -6, 12, 12, 12, -0.5f))
                .part("head", null, 0, 4, 0, box(0, 0, -4, -8, -4, 8, 8, 8, -0.5f))
                .part("right_hand", null, 0, 6, 0, box(32, 0, -1, 0, -1, 12, 2, 2, -0.5f))
                .part("left_hand", null, 0, 6, 0, box(32, 0, -1, 0, -1, 12, 2, 2, -0.5f))
                .build();
    }

    private static void snowGolem(ModelPose pose, float headYaw, float headPitch) {
        pose.rotation(SNOW_HEAD, headPitch, headYaw, 0);
        float bodyYaw = headYaw * 0.25f;
        float s = sin(bodyYaw);
        float c = cos(bodyYaw);
        pose.rotation(SNOW_BODY, 0, bodyYaw, 0);
        pose.rotation(SNOW_RIGHT_HAND, 0, bodyYaw, 1f);
        pose.rotation(SNOW_LEFT_HAND, 0, PI + bodyYaw, -1f);
        pose.pivot(SNOW_RIGHT_HAND, c * 5f, 6, -s * 5f);
        pose.pivot(SNOW_LEFT_HAND, -c * 5f, 6, s * 5f);
    }

    /**
     * ModelZombieVillager: a biped on a 64x64 sheet whose head is a villager's, with the nose, then the usual hat
     * part so armor and child layouts treat it as any biped.
     */
    private static CuboidModel zombieVillager() {
        return CuboidModel.builder("zombie_villager", 64, 64)
                .part("head", null, 0, 0, 0, box(0, 32, -4, -10, -4, 8, 10, 8, 0), box(24, 32, -1, -3, -6, 2, 4, 2, 0))
                .part("body", null, 0, 0, 0, box(16, 16, -4, 0, -2, 8, 12, 4, 0))
                .part("right_arm", null, -5, 2, 0, box(40, 16, -3, -2, -2, 4, 12, 4, 0))
                .part("left_arm", null, 5, 2, 0, mirrored(40, 16, -1, -2, -2, 4, 12, 4, 0))
                .part("right_leg", null, -1.9f, 12, 0, box(0, 16, -2, 0, -2, 4, 12, 4, 0))
                .part("left_leg", null, 1.9f, 12, 0, mirrored(0, 16, -2, 0, -2, 4, 12, 4, 0))
                .part("hat", "head", 0, 0, 0, box(32, 0, -4, -8, -4, 8, 8, 8, 0.5f))
                .build();
    }

    /**
     * LayerMooshroomMushroom as geometry: three red mushrooms (two crossed planes each, the block texture's 16x16
     * sheet), two standing on the back and one on the head. Positions are the layer's translations and 42, 84 and
     * 12 degree turns re-expressed in model units; only part 0 (the head) takes the cow's pose.
     */
    private static CuboidModel mushrooms() {
        float quarter = PI / 4f;
        float back = 42f * DEGREES;
        CuboidModel.Builder builder = CuboidModel.builder("mooshroom_mushrooms", 16, 16)
                .part("head", null, 0, 4, -8);
        ModelBox plane = box(0, 0, -8, -16, 0, 16, 16, 0, 0);
        builder.part("head_a", "head", 0, -3.2f, -3.2f, 0, 12f * DEGREES + quarter, 0, plane)
                .part("head_b", "head", 0, -3.2f, -3.2f, 0, 12f * DEGREES - quarter, 0, plane)
                .part("back1_a", null, 3.2f, 2.4f, 8, 0, back + quarter, 0, plane)
                .part("back1_b", null, 3.2f, 2.4f, 8, 0, back - quarter, 0, plane)
                .part("back2_a", null, -2.03f, 2.4f, -0.2f, 0, 2 * back + quarter, 0, plane)
                .part("back2_b", null, -2.03f, 2.4f, -0.2f, 0, 2 * back - quarter, 0, plane);
        return builder.build();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    static ModelBox box(int u, int v, float x, float y, float z, int w, int h, int d, float inflate) {
        return new ModelBox(u, v, x, y, z, w, h, d, inflate, false);
    }

    static ModelBox mirrored(int u, int v, float x, float y, float z, int w, int h, int d, float inflate) {
        return new ModelBox(u, v, x, y, z, w, h, d, inflate, true);
    }

    static float sin(float value) {
        return (float) Math.sin(value);
    }

    static float cos(float value) {
        return (float) Math.cos(value);
    }
}
