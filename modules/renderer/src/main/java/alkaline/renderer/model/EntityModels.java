package alkaline.renderer.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Minecraft 1.8.9's mob, armor and player geometry as {@link CuboidModel}s, and their animation
 * (setRotationAngles and setLivingAnimations) as poses written into a {@link ModelPose}. Box layouts, pivots,
 * texture sizes and animation constants are vanilla's; the representation (data plus one pose function per rig,
 * no per-frame objects) is Alkaline's.
 *
 * <p>Every biped model lists its first seven parts in the same order (head, body, right arm, left arm, right leg,
 * left leg, hat), so armor layers copy the wearer's pose part for part.
 */
public final class EntityModels {
    /** Parts shared by every biped model, in this order. */
    public static final int BIPED_PARTS = 7;
    public static final int HEAD = 0;
    public static final int BODY = 1;
    public static final int RIGHT_ARM = 2;
    public static final int LEFT_ARM = 3;
    public static final int RIGHT_LEG = 4;
    public static final int LEFT_LEG = 5;
    public static final int HAT = 6;

    private static final float PI = (float) Math.PI;

    /** Every model the entity renderer draws. */
    public enum Kind {
        PLAYER, PLAYER_SLIM, BIPED, BIPED_TALL, ARMOR_OUTER, ARMOR_INNER, SKELETON, ENDERMAN, CREEPER, SPIDER, PIG,
        COW, SHEEP, SHEEP_WOOL, CHICKEN, WOLF, VILLAGER, IRON_GOLEM, SLIME, SLIME_OUTER, BLAZE, SQUID, GHAST,
        WITCH, BAT, RABBIT, OCELOT, SILVERFISH, ENDERMITE,
        MAGMA_CUBE, SNOW_GOLEM, ZOMBIE_VILLAGER, MOOSHROOM_MUSHROOMS, HORSE,
        GUARDIAN, WITHER, ENDER_DRAGON
    }

    /** Per-frame animation inputs, mutable and reused by the caller. Angles in degrees unless noted. */
    public static final class Motion {
        public float limbSwing;
        public float limbSwingAmount;
        public float ageInTicks;
        /** Head yaw relative to the body. */
        public float headYaw;
        public float headPitch;
        /** 0..1 through an arm swing; 0 when idle. */
        public float swingProgress;
        public boolean sneaking;
        /** ModelBiped.heldItemRight: 0 empty, 1 holding an item, 3 blocking with a sword. */
        public int heldItem;
        /** ModelBiped.aimedBow: drawing a bow, both arms raised along the look. */
        public boolean aimedBow;
        /** ModelBiped.isRiding: seated, legs forward. */
        public boolean riding;
        public boolean onGround = true;
        /** Wolves: angry (tail level) or tame. */
        public boolean angry;
        public boolean tame;
        public float health = 20f;
        /** Endermen: carrying a block, screaming. */
        public boolean carrying;
        public boolean screaming;
        /** Entity id and tick count, for animations that run on whole ticks per entity (the witch's nose). */
        public int entityId;
        public int ticksExisted;
        /** Bats hanging from a ceiling; tameable cats sitting; mobs sprinting. */
        public boolean hanging;
        public boolean sitting;
        public boolean sprinting;
        /** Rabbit hop progress, 0..1 through the jump. */
        public float jump;
        /** Horses: foal, saddled, chested, mule-eared (donkey and mule); rearing and grazing 0..1. */
        public boolean foal;
        public boolean saddled;
        public boolean chested;
        public boolean longEars;
        public float rearing;
        public float grazing;
        /** Guardians: spines pulled in, and the eye's offset toward the viewer. */
        public boolean retracted;
        public boolean eyeLow;
        public float eyeX;
        /** Ender dragons: wing-beat phase, and yaw (degrees) and height for history offsets 0..23, interleaved. */
        public float animTime;
        public final float[] movement = new float[48];

        public void reset() {
            limbSwing = limbSwingAmount = ageInTicks = headYaw = headPitch = swingProgress = 0f;
            sneaking = angry = tame = carrying = screaming = hanging = sitting = sprinting = false;
            entityId = ticksExisted = 0;
            jump = rearing = grazing = 0f;
            foal = saddled = chested = longEars = retracted = eyeLow = false;
            eyeX = animTime = 0f;
            heldItem = 0;
            aimedBow = false;
            riding = false;
            onGround = true;
            health = 20f;
        }
    }

    private static final Map<Kind, CuboidModel> MODELS = new EnumMap<>(Kind.class);

    private EntityModels() {
    }

    /** The model for a kind, built once. */
    public static synchronized CuboidModel model(Kind kind) {
        return MODELS.computeIfAbsent(kind, EntityModels::build);
    }

    // ---- geometry ------------------------------------------------------------------------------------------

    private static CuboidModel build(Kind kind) {
        return switch (kind) {
            case PLAYER -> PlayerModels.player(false);
            case PLAYER_SLIM -> PlayerModels.player(true);
            case BIPED -> biped("biped", 64, 64, 0f);
            case BIPED_TALL -> biped("biped_64x32", 64, 32, 0f);
            case ARMOR_OUTER -> biped("armor_outer", 64, 32, 1f);
            case ARMOR_INNER -> biped("armor_inner", 64, 32, 0.5f);
            case SKELETON -> CuboidModel.builder("skeleton", 64, 32)
                    .part("head", null, 0, 0, 0, box(0, 0, -4, -8, -4, 8, 8, 8, 0))
                    .part("body", null, 0, 0, 0, box(16, 16, -4, 0, -2, 8, 12, 4, 0))
                    .part("right_arm", null, -5, 2, 0, box(40, 16, -1, -2, -1, 2, 12, 2, 0))
                    .part("left_arm", null, 5, 2, 0, mirrored(40, 16, -1, -2, -1, 2, 12, 2, 0))
                    .part("right_leg", null, -2, 12, 0, box(0, 16, -1, 0, -1, 2, 12, 2, 0))
                    .part("left_leg", null, 2, 12, 0, mirrored(0, 16, -1, 0, -1, 2, 12, 2, 0))
                    .part("hat", "head", 0, 0, 0, box(32, 0, -4, -8, -4, 8, 8, 8, 0.5f))
                    .build();
            case ENDERMAN -> CuboidModel.builder("enderman", 64, 32)
                    .part("head", null, 0, -14, 0, box(0, 0, -4, -8, -4, 8, 8, 8, 0))
                    .part("body", null, 0, -14, 0, box(32, 16, -4, 0, -2, 8, 12, 4, 0))
                    .part("right_arm", null, -3, -12, 0, box(56, 0, -1, -2, -1, 2, 30, 2, 0))
                    .part("left_arm", null, 5, -12, 0, mirrored(56, 0, -1, -2, -1, 2, 30, 2, 0))
                    .part("right_leg", null, -2, -2, 0, box(56, 0, -1, 0, -1, 2, 30, 2, 0))
                    .part("left_leg", null, 2, -2, 0, mirrored(56, 0, -1, 0, -1, 2, 30, 2, 0))
                    .part("hat", "head", 0, 0, 0, box(0, 16, -4, -8, -4, 8, 8, 8, -0.5f))
                    .build();
            case CREEPER -> CuboidModel.builder("creeper", 64, 32)
                    .part("head", null, 0, 6, 0, box(0, 0, -4, -8, -4, 8, 8, 8, 0))
                    .part("body", null, 0, 6, 0, box(16, 16, -4, 0, -2, 8, 12, 4, 0))
                    .part("leg1", null, -2, 18, 4, box(0, 16, -2, 0, -2, 4, 6, 4, 0))
                    .part("leg2", null, 2, 18, 4, box(0, 16, -2, 0, -2, 4, 6, 4, 0))
                    .part("leg3", null, -2, 18, -4, box(0, 16, -2, 0, -2, 4, 6, 4, 0))
                    .part("leg4", null, 2, 18, -4, box(0, 16, -2, 0, -2, 4, 6, 4, 0))
                    .build();
            case SPIDER -> spider();
            case PIG -> quadruped("pig", 6, 0f, new ModelBox[]{box(0, 0, -4, -4, -8, 8, 8, 8, 0),
                    box(16, 16, -2, 0, -9, 4, 3, 1, 0)}, 0, 12, -6, new ModelBox[]{box(28, 8, -5, -10, -7, 10, 16, 8, 0)},
                    0, 11, 2, -3, 3, 7, -5);
            case COW -> quadruped("cow", 12, 0f, new ModelBox[]{box(0, 0, -4, -4, -6, 8, 8, 6, 0),
                    box(22, 0, -5, -5, -4, 1, 3, 1, 0), box(22, 0, 4, -5, -4, 1, 3, 1, 0)}, 0, 4, -8,
                    new ModelBox[]{box(18, 4, -6, -10, -7, 12, 18, 10, 0), box(52, 0, -2, 2, -8, 4, 6, 1, 0)},
                    0, 5, 2, -4, 4, 7, -6);
            case SHEEP -> quadruped("sheep", 12, 0f, new ModelBox[]{box(0, 0, -3, -4, -6, 6, 6, 8, 0)}, 0, 6, -8,
                    new ModelBox[]{box(28, 8, -4, -10, -7, 8, 16, 6, 0)}, 0, 5, 2, -3, 3, 7, -5);
            case SHEEP_WOOL -> CuboidModel.builder("sheep_wool", 64, 32)
                    .part("head", null, 0, 6, -8, box(0, 0, -3, -4, -4, 6, 6, 6, 0.6f))
                    .part("body", null, 0, 5, 2, PI / 2, 0, 0, box(28, 8, -4, -10, -7, 8, 16, 6, 1.75f))
                    .part("leg1", null, -3, 12, 7, box(0, 16, -2, 0, -2, 4, 6, 4, 0.5f))
                    .part("leg2", null, 3, 12, 7, box(0, 16, -2, 0, -2, 4, 6, 4, 0.5f))
                    .part("leg3", null, -3, 12, -5, box(0, 16, -2, 0, -2, 4, 6, 4, 0.5f))
                    .part("leg4", null, 3, 12, -5, box(0, 16, -2, 0, -2, 4, 6, 4, 0.5f))
                    .build();
            case CHICKEN -> CuboidModel.builder("chicken", 64, 32)
                    .part("head", null, 0, 15, -4, box(0, 0, -2, -6, -2, 4, 6, 3, 0),
                            box(14, 0, -2, -4, -4, 4, 2, 2, 0), box(14, 4, -1, -2, -3, 2, 2, 2, 0))
                    .part("body", null, 0, 16, 0, PI / 2, 0, 0, box(0, 9, -3, -4, -3, 6, 8, 6, 0))
                    .part("right_leg", null, -2, 19, 1, box(26, 0, -1, 0, -3, 3, 5, 3, 0))
                    .part("left_leg", null, 1, 19, 1, box(26, 0, -1, 0, -3, 3, 5, 3, 0))
                    .part("right_wing", null, -4, 13, 0, box(24, 13, 0, 0, -3, 1, 4, 6, 0))
                    .part("left_wing", null, 4, 13, 0, box(24, 13, -1, 0, -3, 1, 4, 6, 0))
                    .build();
            case WOLF -> CuboidModel.builder("wolf", 64, 32)
                    .part("head", null, -1, 13.5f, -7, box(0, 0, -3, -3, -2, 6, 6, 4, 0),
                            box(16, 14, -3, -5, 0, 2, 2, 1, 0), box(16, 14, 1, -5, 0, 2, 2, 1, 0),
                            box(0, 10, -1.5f, 0, -5, 3, 3, 4, 0))
                    .part("body", null, 0, 14, 2, PI / 2, 0, 0, box(18, 14, -4, -2, -3, 6, 9, 6, 0))
                    .part("mane", null, -1, 14, -3, PI / 2, 0, 0, box(21, 0, -4, -3, -3, 8, 6, 7, 0))
                    .part("leg1", null, -2.5f, 16, 7, box(0, 18, -1, 0, -1, 2, 8, 2, 0))
                    .part("leg2", null, 0.5f, 16, 7, box(0, 18, -1, 0, -1, 2, 8, 2, 0))
                    .part("leg3", null, -2.5f, 16, -4, box(0, 18, -1, 0, -1, 2, 8, 2, 0))
                    .part("leg4", null, 0.5f, 16, -4, box(0, 18, -1, 0, -1, 2, 8, 2, 0))
                    .part("tail", null, -1, 12, 8, box(9, 18, -1, 0, -1, 2, 8, 2, 0))
                    .build();
            case VILLAGER -> CuboidModel.builder("villager", 64, 64)
                    .part("head", null, 0, 0, 0, box(0, 0, -4, -10, -4, 8, 10, 8, 0))
                    .part("nose", "head", 0, -2, 0, box(24, 0, -1, -1, -6, 2, 4, 2, 0))
                    .part("body", null, 0, 0, 0, box(16, 20, -4, 0, -3, 8, 12, 6, 0),
                            box(0, 38, -4, 0, -3, 8, 18, 6, 0.5f))
                    .part("arms", null, 0, 3, -1, -0.75f, 0, 0, box(44, 22, -8, -2, -2, 4, 8, 4, 0),
                            box(44, 22, 4, -2, -2, 4, 8, 4, 0), box(40, 38, -4, 2, -2, 8, 4, 4, 0))
                    .part("right_leg", null, -2, 12, 0, box(0, 22, -2, 0, -2, 4, 12, 4, 0))
                    .part("left_leg", null, 2, 12, 0, mirrored(0, 22, -2, 0, -2, 4, 12, 4, 0))
                    .build();
            case IRON_GOLEM -> CuboidModel.builder("iron_golem", 128, 128)
                    .part("head", null, 0, -7, -2, box(0, 0, -4, -12, -5.5f, 8, 10, 8, 0),
                            box(24, 0, -1, -5, -7.5f, 2, 4, 2, 0))
                    .part("body", null, 0, -7, 0, box(0, 40, -9, -2, -6, 18, 12, 11, 0),
                            box(0, 70, -4.5f, 10, -3, 9, 5, 6, 0.5f))
                    .part("right_arm", null, 0, -7, 0, box(60, 21, -13, -2.5f, -3, 4, 30, 6, 0))
                    .part("left_arm", null, 0, -7, 0, box(60, 58, 9, -2.5f, -3, 4, 30, 6, 0))
                    .part("left_leg", null, -4, 11, 0, box(37, 0, -3.5f, -3, -3, 6, 16, 5, 0))
                    .part("right_leg", null, 5, 11, 0, mirrored(60, 0, -3.5f, -3, -3, 6, 16, 5, 0))
                    .build();
            case SLIME -> CuboidModel.builder("slime_inner", 64, 32)
                    .part("body", null, 0, 0, 0, box(0, 16, -3, 17, -3, 6, 6, 6, 0),
                            box(32, 0, -3.25f, 18, -3.5f, 2, 2, 2, 0), box(32, 4, 1.25f, 18, -3.5f, 2, 2, 2, 0),
                            box(32, 8, 0, 21, -3.5f, 1, 1, 1, 0))
                    .build();
            case SLIME_OUTER -> CuboidModel.builder("slime_outer", 64, 32)
                    .part("body", null, 0, 0, 0, box(0, 0, -4, 16, -4, 8, 8, 8, 0))
                    .build();
            case BLAZE -> blaze();
            case SQUID -> squid();
            case GHAST -> ghast();
            default -> MobModels.build(kind);
        };
    }

    static CuboidModel biped(String id, int width, int height, float inflate) {
        return CuboidModel.builder(id, width, height)
                .part("head", null, 0, 0, 0, box(0, 0, -4, -8, -4, 8, 8, 8, inflate))
                .part("body", null, 0, 0, 0, box(16, 16, -4, 0, -2, 8, 12, 4, inflate))
                .part("right_arm", null, -5, 2, 0, box(40, 16, -3, -2, -2, 4, 12, 4, inflate))
                .part("left_arm", null, 5, 2, 0, mirrored(40, 16, -1, -2, -2, 4, 12, 4, inflate))
                .part("right_leg", null, -1.9f, 12, 0, box(0, 16, -2, 0, -2, 4, 12, 4, inflate))
                .part("left_leg", null, 1.9f, 12, 0, mirrored(0, 16, -2, 0, -2, 4, 12, 4, inflate))
                .part("hat", "head", 0, 0, 0, box(32, 0, -4, -8, -4, 8, 8, 8, inflate + 0.5f))
                .build();
    }

    /** ModelQuadruped with its head, body and leg layout replaced as each animal's constructor does. */
    private static CuboidModel quadruped(String id, int legHeight, float inflate, ModelBox[] head, float headX,
                                         float headY, float headZ, ModelBox[] body, float bodyX, float bodyY,
                                         float bodyZ, float leftX, float rightX, float backZ, float frontZ) {
        float legY = 24 - legHeight;
        return CuboidModel.builder(id, 64, 32)
                .part("head", null, headX, headY, headZ, head)
                .part("body", null, bodyX, bodyY, bodyZ, PI / 2, 0, 0, body)
                .part("leg1", null, leftX, legY, backZ, box(0, 16, -2, 0, -2, 4, legHeight, 4, inflate))
                .part("leg2", null, rightX, legY, backZ, box(0, 16, -2, 0, -2, 4, legHeight, 4, inflate))
                .part("leg3", null, leftX, legY, frontZ, box(0, 16, -2, 0, -2, 4, legHeight, 4, inflate))
                .part("leg4", null, rightX, legY, frontZ, box(0, 16, -2, 0, -2, 4, legHeight, 4, inflate))
                .build();
    }

    private static CuboidModel spider() {
        CuboidModel.Builder builder = CuboidModel.builder("spider", 64, 32)
                .part("head", null, 0, 15, -3, box(32, 4, -4, -4, -8, 8, 8, 8, 0))
                .part("neck", null, 0, 15, 0, box(0, 0, -3, -3, -3, 6, 6, 6, 0))
                .part("body", null, 0, 15, 9, box(0, 12, -5, -4, -6, 10, 8, 12, 0));
        float[] z = {2, 2, 1, 1, 0, 0, -1, -1};
        for (int leg = 0; leg < 8; leg++) {
            boolean left = leg % 2 == 1;
            builder.part("leg" + (leg + 1), null, left ? 4 : -4, 15, z[leg],
                    box(18, 0, left ? -1 : -15, -1, -1, 16, 2, 2, 0));
        }
        return builder.build();
    }

    private static CuboidModel blaze() {
        CuboidModel.Builder builder = CuboidModel.builder("blaze", 64, 32)
                .part("head", null, 0, 0, 0, box(0, 0, -4, -4, -4, 8, 8, 8, 0));
        for (int stick = 0; stick < 12; stick++) {
            builder.part("stick" + stick, null, 0, 0, 0, box(0, 16, 0, 0, 0, 2, 8, 2, 0));
        }
        return builder.build();
    }

    private static CuboidModel squid() {
        CuboidModel.Builder builder = CuboidModel.builder("squid", 64, 32)
                .part("body", null, 0, 8, 0, box(0, 0, -6, -8, -6, 12, 16, 12, 0));
        for (int tentacle = 0; tentacle < 8; tentacle++) {
            double angle = tentacle * Math.PI * 2.0 / 8;
            builder.part("tentacle" + tentacle, null, (float) Math.cos(angle) * 5f, 15, (float) Math.sin(angle) * 5f,
                    0, (float) (tentacle * Math.PI * -2.0 / 8 + Math.PI / 2), 0, box(48, 0, -1, 0, -1, 2, 18, 2, 0));
        }
        return builder.build();
    }

    private static CuboidModel ghast() {
        CuboidModel.Builder builder = CuboidModel.builder("ghast", 64, 32)
                .part("body", null, 0, 8, 0, box(0, 0, -8, -8, -8, 16, 16, 16, 0));
        Random random = new Random(1660L);
        for (int tentacle = 0; tentacle < 9; tentacle++) {
            float x = (((tentacle % 3) - (tentacle / 3 % 2) * 0.5f + 0.25f) / 2.0f * 2.0f - 1.0f) * 5.0f;
            float z = ((tentacle / 3) / 2.0f * 2.0f - 1.0f) * 5.0f;
            int length = random.nextInt(7) + 8;
            // ModelGhast.render translates the tentacles 0.6 blocks down (9.6 model units).
            builder.part("tentacle" + tentacle, null, x, 15 + 9.6f, z, box(0, 0, -1, 0, -1, 2, length, 2, 0));
        }
        return builder.build();
    }

    private static ModelBox box(int u, int v, float x, float y, float z, int w, int h, int d, float inflate) {
        return new ModelBox(u, v, x, y, z, w, h, d, inflate, false);
    }

    private static ModelBox mirrored(int u, int v, float x, float y, float z, int w, int h, int d, float inflate) {
        return new ModelBox(u, v, x, y, z, w, h, d, inflate, true);
    }

    // ---- animation -----------------------------------------------------------------------------------------

    /** Writes a kind's pose for the motion into {@code pose} (which must belong to that kind's model). */
    public static void animate(Kind kind, Motion motion, ModelPose pose) {
        pose.reset();
        float headYaw = (float) Math.toRadians(motion.headYaw);
        float headPitch = (float) Math.toRadians(motion.headPitch);
        float swing = motion.limbSwing * 0.6662f;
        float amount = motion.limbSwingAmount;
        switch (kind) {
            case PLAYER, PLAYER_SLIM, BIPED, BIPED_TALL, ARMOR_OUTER, ARMOR_INNER, ZOMBIE_VILLAGER -> biped(motion, pose);
            case SKELETON -> {
                biped(motion, pose);
                zombieArms(motion, pose);
            }
            case ENDERMAN -> enderman(motion, pose);
            case CREEPER -> {
                pose.rotation(0, headPitch, headYaw, 0);
                pose.rotation(2, cos(swing) * 1.4f * amount, 0, 0);
                pose.rotation(3, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(4, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(5, cos(swing) * 1.4f * amount, 0, 0);
            }
            case SPIDER -> spider(motion, pose, headYaw, headPitch);
            case PIG, COW, SHEEP, SHEEP_WOOL -> {
                pose.rotation(0, headPitch, headYaw, 0);
                pose.rotation(2, cos(swing) * 1.4f * amount, 0, 0);
                pose.rotation(3, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(4, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(5, cos(swing) * 1.4f * amount, 0, 0);
            }
            case CHICKEN -> {
                pose.rotation(0, headPitch, headYaw, 0);
                pose.rotation(2, cos(swing) * 1.4f * amount, 0, 0);
                pose.rotation(3, cos(swing + PI) * 1.4f * amount, 0, 0);
                // RenderChicken: the wings flap while the chicken is off the ground.
                float flap = motion.onGround ? 0f : (sin(motion.ageInTicks * 1.2f) + 1f);
                pose.rotation(4, 0, 0, flap);
                pose.rotation(5, 0, 0, -flap);
            }
            case WOLF -> {
                pose.rotation(0, headPitch, headYaw, 0);
                pose.rotation(3, cos(swing) * 1.4f * amount, 0, 0);
                pose.rotation(4, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(5, cos(swing + PI) * 1.4f * amount, 0, 0);
                pose.rotation(6, cos(swing) * 1.4f * amount, 0, 0);
                // EntityWolf.getTailRotation: level when angry, lower with lost health when tame.
                float tail = motion.angry ? 1.5393804f : motion.tame
                        ? (0.55f - (20f - motion.health) * 0.02f) * PI : PI / 5f;
                pose.rotation(7, tail, motion.angry ? 0f : cos(swing) * 1.4f * amount, 0);
            }
            case VILLAGER -> {
                pose.rotation(0, headPitch, headYaw, 0);
                pose.rotation(4, cos(swing) * 1.4f * amount * 0.5f, 0, 0);
                pose.rotation(5, cos(swing + PI) * 1.4f * amount * 0.5f, 0, 0);
            }
            case IRON_GOLEM -> {
                pose.rotation(0, headPitch, headYaw, 0);
                float walk = triangle(motion.limbSwing, 13f);
                pose.rotation(4, -1.5f * walk * amount, 0, 0);
                pose.rotation(5, 1.5f * walk * amount, 0, 0);
                pose.rotation(2, (-0.2f + 1.5f * walk) * amount, 0, 0);
                pose.rotation(3, (-0.2f - 1.5f * walk) * amount, 0, 0);
            }
            case SLIME, SLIME_OUTER -> {
            }
            case BLAZE -> blaze(motion, pose, headYaw, headPitch);
            case SQUID -> {
                // EntitySquid's tentacle angle, approximated by a steady swim cycle.
                float angle = (sin(motion.ageInTicks * 0.15f) + 1f) * 0.3f;
                for (int tentacle = 1; tentacle <= 8; tentacle++) {
                    pose.rotation(tentacle, angle, pose.rotationY(tentacle), 0);
                }
            }
            case GHAST -> {
                for (int tentacle = 1; tentacle <= 9; tentacle++) {
                    pose.rotation(tentacle, 0.2f * sin(motion.ageInTicks * 0.3f + (tentacle - 1)) + 0.4f, 0, 0);
                }
            }
            default -> MobModels.animate(kind, motion, pose);
        }
    }

    /** ModelBiped.setRotationAngles. */
    private static void biped(Motion motion, ModelPose pose) {
        float swing = motion.limbSwing * 0.6662f;
        float amount = motion.limbSwingAmount;
        pose.rotation(HEAD, (float) Math.toRadians(motion.headPitch), (float) Math.toRadians(motion.headYaw), 0f);
        float rightArmX = cos(swing + PI) * 2f * amount * 0.5f;
        float leftArmX = cos(swing) * 2f * amount * 0.5f;
        float rightArmY = 0f;
        float leftArmY = 0f;
        float rightArmZ = 0f;
        float rightLegX = cos(swing) * 1.4f * amount;
        float leftLegX = cos(swing + PI) * 1.4f * amount;
        float rightLegY = 0f;
        float leftLegY = 0f;
        if (motion.riding) {
            rightArmX += -PI / 5f;
            leftArmX += -PI / 5f;
            rightLegX = -PI * 2f / 5f;
            leftLegX = -PI * 2f / 5f;
            rightLegY = PI / 10f;
            leftLegY = -PI / 10f;
        }
        pose.rotation(RIGHT_LEG, rightLegX, rightLegY, 0f);
        pose.rotation(LEFT_LEG, leftLegX, leftLegY, 0f);
        if (motion.heldItem == 1 || motion.heldItem == 3) {
            rightArmX = rightArmX * 0.5f - PI / 10f * motion.heldItem;
            if (motion.heldItem == 3) {
                rightArmY = -0.5235988f;
            }
        }
        float bodyYaw = 0f;
        if (motion.swingProgress > 0f) {
            float progress = motion.swingProgress;
            bodyYaw = sin((float) Math.sqrt(progress) * PI * 2f) * 0.2f;
            pose.pivot(RIGHT_ARM, -cos(bodyYaw) * 5f, pose.pivotY(RIGHT_ARM), sin(bodyYaw) * 5f);
            pose.pivot(LEFT_ARM, cos(bodyYaw) * 5f, pose.pivotY(LEFT_ARM), -sin(bodyYaw) * 5f);
            rightArmY += bodyYaw;
            leftArmY += bodyYaw;
            leftArmX += bodyYaw;
            float eased = 1f - progress;
            eased *= eased;
            eased *= eased;
            eased = 1f - eased;
            float lift = sin(eased * PI);
            float headTerm = sin(progress * PI) * -(pose.rotationX(HEAD) - 0.7f) * 0.75f;
            rightArmX -= lift * 1.2f + headTerm;
            rightArmY += bodyYaw * 2f;
            rightArmZ += sin(progress * PI) * -0.4f;
        }
        float bodyX = 0f;
        if (motion.sneaking) {
            bodyX = 0.5f;
            rightArmX += 0.4f;
            leftArmX += 0.4f;
            pose.pivot(RIGHT_LEG, pose.pivotX(RIGHT_LEG), 9f, 4f);
            pose.pivot(LEFT_LEG, pose.pivotX(LEFT_LEG), 9f, 4f);
            pose.pivot(HEAD, 0f, 1f, 0f);
        } else {
            pose.pivot(RIGHT_LEG, pose.pivotX(RIGHT_LEG), 12f, 0.1f);
            pose.pivot(LEFT_LEG, pose.pivotX(LEFT_LEG), 12f, 0.1f);
        }
        pose.rotation(BODY, bodyX, bodyYaw, 0f);
        float breathing = cos(motion.ageInTicks * 0.09f) * 0.05f + 0.05f;
        float sway = sin(motion.ageInTicks * 0.067f) * 0.05f;
        pose.rotation(RIGHT_ARM, rightArmX + sway, rightArmY, rightArmZ + breathing);
        pose.rotation(LEFT_ARM, leftArmX - sway, leftArmY, -breathing);
        if (motion.aimedBow) {
            // Both arms along the look, the left one turned in to hold the string.
            float headYaw = pose.rotationY(HEAD);
            float headPitch = pose.rotationX(HEAD);
            pose.rotation(RIGHT_ARM, -PI / 2f + headPitch + sway, -0.1f + headYaw, breathing);
            pose.rotation(LEFT_ARM, -PI / 2f + headPitch - sway, 0.1f + headYaw + 0.4f, -breathing);
        }
    }

    /** ModelZombie: both arms held forward, raised further through an attack. */
    public static void zombieArms(Motion motion, ModelPose pose) {
        float progress = motion.swingProgress;
        float f = sin(progress * PI);
        float f1 = sin((1f - (1f - progress) * (1f - progress)) * PI);
        float breathing = cos(motion.ageInTicks * 0.09f) * 0.05f + 0.05f;
        float sway = sin(motion.ageInTicks * 0.067f) * 0.05f;
        float raised = -PI / 2f - (f * 1.2f - f1 * 0.4f);
        pose.rotation(RIGHT_ARM, raised + sway, -(0.1f - f * 0.6f), breathing);
        pose.rotation(LEFT_ARM, raised - sway, 0.1f - f * 0.6f, -breathing);
    }

    private static void enderman(Motion motion, ModelPose pose) {
        biped(motion, pose);
        pose.rotation(BODY, 0f, pose.rotationY(BODY), pose.rotationZ(BODY));
        pose.pivot(BODY, 0f, -14f, 0f);
        for (int part : new int[]{RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG}) {
            float x = Math.max(-0.4f, Math.min(0.4f, pose.rotationX(part) * 0.5f));
            pose.rotation(part, x, pose.rotationY(part), pose.rotationZ(part));
        }
        if (motion.carrying) {
            pose.rotation(RIGHT_ARM, -0.5f, pose.rotationY(RIGHT_ARM), 0.05f);
            pose.rotation(LEFT_ARM, -0.5f, pose.rotationY(LEFT_ARM), -0.05f);
        }
        pose.pivot(RIGHT_ARM, pose.pivotX(RIGHT_ARM), pose.pivotY(RIGHT_ARM), 0f);
        pose.pivot(LEFT_ARM, pose.pivotX(LEFT_ARM), pose.pivotY(LEFT_ARM), 0f);
        pose.pivot(RIGHT_LEG, pose.pivotX(RIGHT_LEG), -5f, 0f);
        pose.pivot(LEFT_LEG, pose.pivotX(LEFT_LEG), -5f, 0f);
        pose.pivot(HEAD, 0f, motion.screaming ? -18f : -13f, 0f);
    }

    private static void spider(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        pose.rotation(0, headPitch, headYaw, 0);
        float quarter = PI / 4f;
        float eighth = 0.3926991f;
        float[] baseZ = {-quarter, quarter, -quarter * 0.74f, quarter * 0.74f, -quarter * 0.74f, quarter * 0.74f,
                -quarter, quarter};
        float[] baseY = {eighth * 2f, -eighth * 2f, eighth, -eighth, -eighth, eighth, -eighth * 2f, eighth * 2f};
        float swing = motion.limbSwing * 0.6662f;
        float amount = motion.limbSwingAmount;
        float[] phase = {0f, PI, PI / 2f, PI * 3f / 2f};
        for (int pair = 0; pair < 4; pair++) {
            float yaw = -(cos(swing * 2f + phase[pair]) * 0.4f) * amount;
            float roll = Math.abs(sin(swing + phase[pair]) * 0.4f) * amount;
            int right = 3 + pair * 2;
            int left = right + 1;
            pose.rotation(right, 0, baseY[pair * 2] + yaw, baseZ[pair * 2] + roll);
            pose.rotation(left, 0, baseY[pair * 2 + 1] - yaw, baseZ[pair * 2 + 1] - roll);
        }
    }

    private static void blaze(Motion motion, ModelPose pose, float headYaw, float headPitch) {
        float age = motion.ageInTicks;
        float angle = age * PI * -0.1f;
        for (int stick = 0; stick < 4; stick++) {
            pose.pivot(1 + stick, cos(angle) * 9f, -2f + cos((stick * 2 + age) * 0.25f), sin(angle) * 9f);
            angle++;
        }
        angle = PI / 4f + age * PI * 0.03f;
        for (int stick = 4; stick < 8; stick++) {
            pose.pivot(1 + stick, cos(angle) * 7f, 2f + cos((stick * 2 + age) * 0.25f), sin(angle) * 7f);
            angle++;
        }
        angle = 0.47123894f + age * PI * -0.05f;
        for (int stick = 8; stick < 12; stick++) {
            pose.pivot(1 + stick, cos(angle) * 5f, 11f + cos((stick * 1.5f + age) * 0.5f), sin(angle) * 5f);
            angle++;
        }
        pose.rotation(0, headPitch, headYaw, 0);
    }

    /** ModelIronGolem.func_78172_a: a triangle wave in -1..1. */
    private static float triangle(float value, float period) {
        return (Math.abs(value % period - period * 0.5f) - period * 0.25f) / (period * 0.25f);
    }

    private static float sin(float value) {
        return (float) Math.sin(value);
    }

    private static float cos(float value) {
        return (float) Math.cos(value);
    }
}
