package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;

/**
 * Vanilla 1.8.9's empty first-person hand (ItemRenderer.renderPlayerArm, then RenderPlayer.renderRightArm) as
 * the root matrix of the player model in eye space (y up, looking down -z): only the right arm and its sleeve
 * are drawn, posed as setRotationAngles leaves them at rest. Draw with the hand projection, as
 * {@link FirstPersonItem}.
 */
public final class FirstPersonArm {
    private FirstPersonArm() {
    }

    /**
     * @param equipProgress 0 when fully raised, 1 when lowered out of view
     * @param swingProgress 0..1 through an attack swing, 0 when idle
     * @param armPitchLag   view pitch minus the arm's lagging pitch, degrees
     * @param armYawLag     view yaw minus the arm's lagging yaw, degrees
     */
    public static float[] transform(float equipProgress, float swingProgress, float armPitchLag, float armYawLag,
                                    float[] out) {
        Mat4.identity(out);
        Mat4.rotate(out, (float) Math.toRadians(armPitchLag * 0.1f), 1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(armYawLag * 0.1f), 0f, 1f, 0f, out);
        float root = (float) Math.sqrt(swingProgress);
        Mat4.translate(out, -0.3f * sin(root * (float) Math.PI), 0.4f * sin(root * (float) Math.PI * 2f),
                -0.4f * sin(swingProgress * (float) Math.PI), out);
        Mat4.translate(out, 0.64000005f, -0.6f, -0.71999997f, out);
        Mat4.translate(out, 0f, equipProgress * -0.6f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(45), 0f, 1f, 0f, out);
        float swing = sin(swingProgress * swingProgress * (float) Math.PI);
        float swingRoot = sin(root * (float) Math.PI);
        Mat4.rotate(out, (float) Math.toRadians(swingRoot * 70f), 0f, 1f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(swing * -20f), 0f, 0f, 1f, out);
        Mat4.translate(out, -1f, 3.6f, 3.5f, out);
        Mat4.rotate(out, (float) Math.toRadians(120), 0f, 0f, 1f, out);
        Mat4.rotate(out, (float) Math.toRadians(200), 1f, 0f, 0f, out);
        Mat4.rotate(out, (float) Math.toRadians(-135), 0f, 1f, 0f, out);
        return Mat4.translate(out, 5.6f, 0f, 0f, out);
    }

    /** RenderPlayer.renderRightArm's pose: setRotationAngles with everything at rest, only the right arm shown. */
    public static void pose(ModelPose pose) {
        EntityModels.Motion rest = new EntityModels.Motion();
        EntityModels.animate(EntityModels.Kind.PLAYER, rest, pose);
        pose.visibleAll(false);
        CuboidModel model = pose.model();
        pose.visible(EntityModels.RIGHT_ARM, true);
        pose.visible(model.partIndex(PlayerModels.RIGHT_SLEEVE), true);
    }

    private static float sin(float radians) {
        return (float) Math.sin(radians);
    }
}
