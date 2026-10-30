package alkaline.renderer.model;

import java.util.Random;

/**
 * Geometry of the world objects that are not mobs: the minecart (ModelMinecart), the boat (ModelBoat), the armor
 * stand (ModelArmorStand) and its poses, the ender crystal (ModelEnderCrystal), one tile of a painting
 * (RenderPainting), the experience orb's quads (RenderXPOrb) and a lightning bolt (RenderLightningBolt). Boxes,
 * pivots, texture layouts and constants are vanilla 1.8.9's.
 */
public final class ObjectModels {
    private static final float PI = (float) Math.PI;

    /** Armor stand parts after the seven biped ones ({@link EntityModels#HEAD} to {@link EntityModels#HAT}). */
    public static final int STAND_RIGHT_SIDE = 7;
    public static final int STAND_LEFT_SIDE = 8;
    public static final int STAND_WAIST = 9;
    public static final int STAND_BASE = 10;

    private ObjectModels() {
    }

    // ---- minecart and boat ---------------------------------------------------------------------------------

    /**
     * ModelMinecart: the floor (part 0), four walls and the inner floor (part 5, which the renderer lifts by
     * 0.1 pixel). Drawn after vanilla's {@code scale(-1, -1, 1)}.
     */
    static CuboidModel minecart() {
        ModelBox wall = box(0, 0, -8, -9, -1, 16, 8, 2);
        return CuboidModel.builder("minecart", 64, 32)
                .part("floor", null, 0, 4, 0, PI / 2, 0, 0, box(0, 10, -10, -8, -1, 20, 16, 2))
                .part("wall_west", null, -9, 4, 0, 0, PI * 3 / 2, 0, wall)
                .part("wall_east", null, 9, 4, 0, 0, PI / 2, 0, wall)
                .part("wall_north", null, 0, 4, -7, 0, PI, 0, wall)
                .part("wall_south", null, 0, 4, 7, wall)
                .part("inner_floor", null, 0, 4.1f, 0, -PI / 2, 0, 0, box(44, 10, -9, -7, -1, 18, 14, 1))
                .build();
    }

    /** ModelBoat: the hull floor and four sides. */
    static CuboidModel boat() {
        ModelBox side = box(0, 0, -10, -7, -1, 20, 6, 2);
        return CuboidModel.builder("boat", 64, 32)
                .part("floor", null, 0, 4, 0, PI / 2, 0, 0, box(0, 8, -12, -8, -3, 24, 16, 4))
                .part("side_west", null, -11, 4, 0, 0, PI * 3 / 2, 0, side)
                .part("side_east", null, 11, 4, 0, 0, PI / 2, 0, side)
                .part("side_north", null, 0, 4, -9, 0, PI, 0, side)
                .part("side_south", null, 0, 4, 9, side)
                .build();
    }

    // ---- armor stand ---------------------------------------------------------------------------------------

    /**
     * ModelArmorStand (texture 64x64): the seven biped parts as the stand's thin ones, then the two uprights, the
     * waist and the base plate.
     */
    static CuboidModel armorStand() {
        return CuboidModel.builder("armor_stand", 64, 64)
                .part("head", null, 0, 1, 0, box(0, 0, -1, -7, -1, 2, 7, 2))
                .part("body", null, 0, 0, 0, box(0, 26, -6, 0, -1.5f, 12, 3, 3))
                .part("right_arm", null, -5, 2, 0, box(24, 0, -2, -2, -1, 2, 12, 2))
                .part("left_arm", null, 5, 2, 0, new ModelBox(32, 16, 0, -2, -1, 2, 12, 2, 0f, true))
                .part("right_leg", null, -1.9f, 12, 0, box(8, 0, -1, 0, -1, 2, 11, 2))
                .part("left_leg", null, 1.9f, 12, 0, new ModelBox(40, 16, -1, 0, -1, 2, 11, 2, 0f, true))
                .part("hat", "head", 0, 0, 0, new ModelBox(32, 0, -4, -8, -4, 8, 8, 8, 0.5f, false))
                .part("right_side", null, 0, 0, 0, box(16, 0, -3, 3, -1, 2, 7, 2))
                .part("left_side", null, 0, 0, 0, box(48, 16, 1, 3, -1, 2, 7, 2))
                .part("waist", null, 0, 0, 0, box(0, 48, -4, 10, -1, 8, 2, 2))
                .part("base", null, 0, 12, 0, box(0, 32, -6, 11, -6, 12, 1, 12))
                .build();
    }

    /** DEFAULT_HEAD_ROTATION and the stand's other defaults, used until the server sends its own. */
    public static final float[] DEFAULT_HEAD = {0f, 0f, 0f};
    public static final float[] DEFAULT_BODY = {0f, 0f, 0f};
    public static final float[] DEFAULT_LEFT_ARM = {-10f, 0f, -10f};
    public static final float[] DEFAULT_RIGHT_ARM = {-15f, 0f, 10f};
    public static final float[] DEFAULT_LEFT_LEG = {-1f, 0f, -1f};
    public static final float[] DEFAULT_RIGHT_LEG = {1f, 0f, 1f};

    /**
     * ModelArmorStandArmor.setRotationAngles (and ModelArmorStand's on top of it): every part follows the stand's
     * rotation triples (degrees). {@code stand} poses the stand model itself, with its legs at pivot 12 and its
     * uprights, waist and base; otherwise it poses an armor model, whose legs sit at 11. {@code yaw} is the entity's
     * yaw, which the base plate undoes so it stays square to the block.
     */
    public static void poseArmorStand(ModelPose pose, boolean stand, float[] head, float[] body, float[] leftArm,
                                      float[] rightArm, float[] leftLeg, float[] rightLeg, boolean showArms,
                                      boolean basePlate, float yaw) {
        pose.reset();
        rotate(pose, EntityModels.HEAD, head);
        pose.pivot(EntityModels.HEAD, 0f, 1f, 0f);
        rotate(pose, EntityModels.BODY, body);
        rotate(pose, EntityModels.LEFT_ARM, leftArm);
        rotate(pose, EntityModels.RIGHT_ARM, rightArm);
        rotate(pose, EntityModels.LEFT_LEG, leftLeg);
        rotate(pose, EntityModels.RIGHT_LEG, rightLeg);
        float legY = stand ? 12f : 11f;
        pose.pivot(EntityModels.LEFT_LEG, 1.9f, legY, 0f);
        pose.pivot(EntityModels.RIGHT_LEG, -1.9f, legY, 0f);
        if (stand) {
            pose.visible(EntityModels.LEFT_ARM, showArms);
            pose.visible(EntityModels.RIGHT_ARM, showArms);
            rotate(pose, STAND_RIGHT_SIDE, body);
            rotate(pose, STAND_LEFT_SIDE, body);
            rotate(pose, STAND_WAIST, body);
            pose.rotation(STAND_BASE, 0f, (float) Math.toRadians(-yaw), 0f);
            pose.visible(STAND_BASE, basePlate);
        }
    }

    private static void rotate(ModelPose pose, int part, float[] degrees) {
        pose.rotation(part, (float) Math.toRadians(degrees[0]), (float) Math.toRadians(degrees[1]),
                (float) Math.toRadians(degrees[2]));
    }

    // ---- ender crystal -------------------------------------------------------------------------------------

    /** Ender crystal parts: the base plate and the two cubes, each drawn on its own by the renderer. */
    public static final int CRYSTAL_BASE = 0;
    public static final int CRYSTAL_GLASS = 1;
    public static final int CRYSTAL_CUBE = 2;

    /**
     * ModelEnderCrystal (texture 64x32, the 1.8.9 image being twice that): the base plate, the glass cube (drawn
     * twice) and the inner cube. RenderEnderCrystal stacks a separate turn and scale before each, so every part is
     * a root at the origin and the renderer gives each draw its own matrix.
     */
    static CuboidModel enderCrystal() {
        return CuboidModel.builder("ender_crystal", 64, 32)
                .part("base", null, 0, 0, 0, box(0, 16, -6, 0, -6, 12, 4, 12))
                .part("glass", null, 0, 0, 0, box(0, 0, -4, -4, -4, 8, 8, 8))
                .part("cube", null, 0, 0, 0, box(32, 0, -4, -4, -4, 8, 8, 8))
                .build();
    }

    // ---- painting ------------------------------------------------------------------------------------------

    /** The painting atlas is 256 pixels square. */
    private static final float ATLAS = 256f;

    /**
     * RenderPainting.renderPainting for one 16x16 tile, centered on its own origin in blocks (the instance places
     * it): the art on the front, the plain back, and the four edges, all from the painting atlas. {@code u} and
     * {@code v} count tiles from the painting's left and bottom as vanilla's loops do.
     */
    public static BakedModel paintingTile(int artU, int artV, int width, int height, int u, int v) {
        float unit = 1f / 16f;
        float xHi = 8f * unit;
        float xLo = -8f * unit;
        float yHi = 8f * unit;
        float yLo = -8f * unit;
        float depth = 0.5f * unit;
        float aa = (artU + width - u * 16) / ATLAS;
        float ab = (artU + width - (u + 1) * 16) / ATLAS;
        float ac = (artV + height - v * 16) / ATLAS;
        float ad = (artV + height - (v + 1) * 16) / ATLAS;
        float back0 = 0.75f;
        float back1 = 0.8125f;
        float edge = 0.001953125f;
        float sideLo = 0.7519531f;
        BakedModel.Builder builder = new BakedModel.Builder();
        builder.quad(0, new float[]{xHi, yLo, -depth, xLo, yLo, -depth, xLo, yHi, -depth, xHi, yHi, -depth},
                new float[]{ab, ac, aa, ac, aa, ad, ab, ad}, 0, 0, -1);
        builder.quad(0, new float[]{xHi, yHi, depth, xLo, yHi, depth, xLo, yLo, depth, xHi, yLo, depth},
                new float[]{back0, 0f, back1, 0f, back1, 0.0625f, back0, 0.0625f}, 0, 0, 1);
        builder.quad(0, new float[]{xHi, yHi, -depth, xLo, yHi, -depth, xLo, yHi, depth, xHi, yHi, depth},
                new float[]{back0, edge, back1, edge, back1, edge, back0, edge}, 0, 1, 0);
        builder.quad(0, new float[]{xHi, yLo, depth, xLo, yLo, depth, xLo, yLo, -depth, xHi, yLo, -depth},
                new float[]{back0, edge, back1, edge, back1, edge, back0, edge}, 0, -1, 0);
        builder.quad(0, new float[]{xHi, yHi, depth, xHi, yLo, depth, xHi, yLo, -depth, xHi, yHi, -depth},
                new float[]{sideLo, 0f, sideLo, 0.0625f, sideLo, 0.0625f, sideLo, 0f}, -1, 0, 0);
        builder.quad(0, new float[]{xLo, yHi, -depth, xLo, yLo, -depth, xLo, yLo, depth, xLo, yHi, depth},
                new float[]{sideLo, 0f, sideLo, 0.0625f, sideLo, 0.0625f, sideLo, 0f}, 1, 0, 0);
        return builder.build("painting_tile", 1);
    }

    // ---- experience orb ------------------------------------------------------------------------------------

    /**
     * RenderXPOrb's quad for icon {@code icon} (0 to 10) of {@code experience_orb.png}, one block wide with its
     * origin at (0.5, 0.25), facing +z; the instance billboards and scales it.
     */
    public static BakedModel experienceOrb(int icon) {
        float u0 = (icon % 4 * 16) / 64f;
        float u1 = (icon % 4 * 16 + 16) / 64f;
        float v0 = (icon / 4 * 16) / 64f;
        float v1 = (icon / 4 * 16 + 16) / 64f;
        BakedModel.Builder builder = new BakedModel.Builder();
        builder.quad(0, new float[]{-0.5f, -0.25f, 0f, 0.5f, -0.25f, 0f, 0.5f, 0.75f, 0f, -0.5f, 0.75f, 0f},
                new float[]{u0, v1, u1, v1, u1, v0, u0, v0}, 0, 1, 0);
        return builder.build("experience_orb_" + icon, 1);
    }

    // ---- lightning -----------------------------------------------------------------------------------------

    /**
     * RenderLightningBolt: four widths of three jagged forks, each a column of 16-block segments with a square
     * cross-section, as quads (in both windings, since the strips draw both sides) around the bolt's foot at the
     * origin. {@code seed} is the entity's boltVertex.
     */
    public static BakedModel lightning(long seed) {
        double[] ds = new double[8];
        double[] es = new double[8];
        double d = 0.0;
        double e = 0.0;
        Random random = new Random(seed);
        for (int i = 7; i >= 0; i--) {
            ds[i] = d;
            es[i] = e;
            d += random.nextInt(11) - 5;
            e += random.nextInt(11) - 5;
        }
        BakedModel.Builder builder = new BakedModel.Builder();
        float[] uv = new float[8];
        float[] corners = new float[12];
        float[] reversed = new float[12];
        double[] bottom = new double[15];
        double[] top = new double[15];
        for (int j = 0; j < 4; j++) {
            Random fork = new Random(seed);
            for (int k = 0; k < 3; k++) {
                int from = 7;
                int to = 0;
                if (k > 0) {
                    from = 7 - k;
                    to = from - 2;
                }
                double f = ds[from] - d;
                double g = es[from] - e;
                for (int n = from; n >= to; n--) {
                    double h = f;
                    double o = g;
                    if (k == 0) {
                        f += fork.nextInt(11) - 5;
                        g += fork.nextInt(11) - 5;
                    } else {
                        f += fork.nextInt(31) - 15;
                        g += fork.nextInt(31) - 15;
                    }
                    double t = 0.1 + j * 0.2;
                    if (k == 0) {
                        t *= n * 0.1 + 1.0;
                    }
                    double u = 0.1 + j * 0.2;
                    if (k == 0) {
                        u *= (n - 1) * 0.1 + 1.0;
                    }
                    for (int v = 0; v < 5; v++) {
                        double w = 0.5 - t;
                        double aa = 0.5 - t;
                        if (v == 1 || v == 2) {
                            w += t * 2.0;
                        }
                        if (v == 2 || v == 3) {
                            aa += t * 2.0;
                        }
                        double ab = 0.5 - u;
                        double ac = 0.5 - u;
                        if (v == 1 || v == 2) {
                            ab += u * 2.0;
                        }
                        if (v == 2 || v == 3) {
                            ac += u * 2.0;
                        }
                        bottom[v * 3] = ab + f;
                        bottom[v * 3 + 1] = n * 16;
                        bottom[v * 3 + 2] = ac + g;
                        top[v * 3] = w + h;
                        top[v * 3 + 1] = (n + 1) * 16;
                        top[v * 3 + 2] = aa + o;
                    }
                    for (int v = 0; v < 4; v++) {
                        for (int corner = 0; corner < 3; corner++) {
                            corners[corner] = (float) bottom[v * 3 + corner];
                            corners[3 + corner] = (float) top[v * 3 + corner];
                            corners[6 + corner] = (float) top[(v + 1) * 3 + corner];
                            corners[9 + corner] = (float) bottom[(v + 1) * 3 + corner];
                        }
                        builder.quad(0, corners, uv, 0, 1, 0);
                        for (int corner = 0; corner < 4; corner++) {
                            System.arraycopy(corners, corner * 3, reversed, (3 - corner) * 3, 3);
                        }
                        builder.quad(0, reversed, uv, 0, 1, 0);
                    }
                }
            }
        }
        return builder.build("lightning", 1);
    }

    private static ModelBox box(int u, int v, float x, float y, float z, int width, int height, int depth) {
        return new ModelBox(u, v, x, y, z, width, height, depth, 0f, false);
    }
}
