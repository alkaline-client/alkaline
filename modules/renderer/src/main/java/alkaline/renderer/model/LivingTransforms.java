package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;

/** Root transforms Minecraft applies before drawing a living entity's model. */
public final class LivingTransforms {
    /** RenderPlayer scales the player model by 15/16. */
    public static final float PLAYER_SCALE = 0.9375f;

    private LivingTransforms() {
    }

    /**
     * {@code translate(x, y, z) * Ry(180 - bodyYaw) * scale(-1, -1, 1) * scale(s) * translate(0, -1.5078125, 0)}:
     * feet at (x, y, z), facing the body yaw in degrees, model space flipped from y-down to y-up.
     */
    public static float[] root(float x, float y, float z, float bodyYawDegrees, float modelScale, float[] out) {
        Mat4.identity(out);
        Mat4.translate(out, x, y, z, out);
        Mat4.rotate(out, (float) Math.toRadians(180.0 - bodyYawDegrees), 0f, 1f, 0f, out);
        Mat4.scale(out, -modelScale, -modelScale, modelScale, out);
        return Mat4.translate(out, 0f, -1.5078125f, 0f, out);
    }
}
