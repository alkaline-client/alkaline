package alkaline.renderer.model;

/**
 * Minecraft 1.8 player geometry for 64x64 skins (classic 4-pixel and slim 3-pixel arms). Overlay layers are
 * children of their base part with an identity local transform, which is exactly what vanilla's per-frame
 * {@code copyModelAngles} achieves, so animators only pose the six base parts.
 */
public final class PlayerModels {
    public static final String HEAD = "head";
    public static final String BODY = "body";
    public static final String RIGHT_ARM = "right_arm";
    public static final String LEFT_ARM = "left_arm";
    public static final String RIGHT_LEG = "right_leg";
    public static final String LEFT_LEG = "left_leg";
    public static final String HAT = "hat";
    public static final String JACKET = "jacket";
    public static final String RIGHT_SLEEVE = "right_sleeve";
    public static final String LEFT_SLEEVE = "left_sleeve";
    public static final String RIGHT_PANTS = "right_pants";
    public static final String LEFT_PANTS = "left_pants";

    private PlayerModels() {
    }

    public static CuboidModel player(boolean slim) {
        int arm = slim ? 3 : 4;
        float armY = slim ? 2.5f : 2f;
        float rightArmX = slim ? -2f : -3f;
        return CuboidModel.builder(slim ? "player_slim" : "player", 64, 64)
                .part(HEAD, null, 0f, 0f, 0f, box(0, 0, -4, -8, -4, 8, 8, 8, 0f))
                .part(BODY, null, 0f, 0f, 0f, box(16, 16, -4, 0, -2, 8, 12, 4, 0f))
                .part(RIGHT_ARM, null, -5f, armY, 0f, box(40, 16, rightArmX, -2, -2, arm, 12, 4, 0f))
                .part(LEFT_ARM, null, 5f, armY, 0f, box(32, 48, -1, -2, -2, arm, 12, 4, 0f))
                .part(RIGHT_LEG, null, -1.9f, 12f, 0f, box(0, 16, -2, 0, -2, 4, 12, 4, 0f))
                .part(LEFT_LEG, null, 1.9f, 12f, 0f, box(16, 48, -2, 0, -2, 4, 12, 4, 0f))
                .part(HAT, HEAD, 0f, 0f, 0f, box(32, 0, -4, -8, -4, 8, 8, 8, 0.5f))
                .part(JACKET, BODY, 0f, 0f, 0f, box(16, 32, -4, 0, -2, 8, 12, 4, 0.25f))
                .part(RIGHT_SLEEVE, RIGHT_ARM, 0f, 0f, 0f, box(40, 32, rightArmX, -2, -2, arm, 12, 4, 0.25f))
                .part(LEFT_SLEEVE, LEFT_ARM, 0f, 0f, 0f, box(48, 48, -1, -2, -2, arm, 12, 4, 0.25f))
                .part(RIGHT_PANTS, RIGHT_LEG, 0f, 0f, 0f, box(0, 32, -2, 0, -2, 4, 12, 4, 0.25f))
                .part(LEFT_PANTS, LEFT_LEG, 0f, 0f, 0f, box(0, 48, -2, 0, -2, 4, 12, 4, 0.25f))
                .build();
    }

    private static ModelBox box(int u, int v, float x, float y, float z, int w, int h, int d, float inflate) {
        return new ModelBox(u, v, x, y, z, w, h, d, inflate, false);
    }
}
