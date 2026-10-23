package alkaline.renderer.model;

/**
 * Poses the six biped base parts with Minecraft 1.8.9's walk, idle-breathing, head-look, arm-swing and sneak
 * rules ({@code ModelBiped.setRotationAngles}). Part indices are resolved once; posing allocates nothing.
 */
public final class BipedAnimator {
    private static final float PI = (float) Math.PI;

    /** Per-frame biped inputs, mutable and reused by the caller. */
    public static final class State {
        public float limbSwing;
        public float limbSwingAmount;
        public float ageInTicks;
        /** Head yaw relative to the body, degrees. */
        public float headYaw;
        /** Head pitch, degrees. */
        public float headPitch;
        /** 0..1 while swinging the main arm; negative when idle. */
        public float swingProgress = -1f;
        public boolean sneaking;
    }

    private final int head;
    private final int body;
    private final int rightArm;
    private final int leftArm;
    private final int rightLeg;
    private final int leftLeg;

    public BipedAnimator(CuboidModel model) {
        head = model.partIndex(PlayerModels.HEAD);
        body = model.partIndex(PlayerModels.BODY);
        rightArm = model.partIndex(PlayerModels.RIGHT_ARM);
        leftArm = model.partIndex(PlayerModels.LEFT_ARM);
        rightLeg = model.partIndex(PlayerModels.RIGHT_LEG);
        leftLeg = model.partIndex(PlayerModels.LEFT_LEG);
    }

    public void apply(State state, ModelPose pose) {
        pose.reset();
        float swing = state.limbSwing * 0.6662f;
        float amount = state.limbSwingAmount;
        pose.rotation(head, (float) Math.toRadians(state.headPitch), (float) Math.toRadians(state.headYaw), 0f);
        float rightArmX = (float) Math.cos(swing + PI) * 2f * amount * 0.5f;
        float leftArmX = (float) Math.cos(swing) * 2f * amount * 0.5f;
        float rightArmY = 0f;
        float leftArmY = 0f;
        float rightArmZ = 0f;
        float leftArmZ = 0f;
        pose.rotation(rightLeg, (float) Math.cos(swing) * 1.4f * amount, 0f, 0f);
        pose.rotation(leftLeg, (float) Math.cos(swing + PI) * 1.4f * amount, 0f, 0f);

        float bodyYaw = 0f;
        if (state.swingProgress > -9990f && state.swingProgress >= 0f) {
            float progress = state.swingProgress;
            bodyYaw = (float) Math.sin(Math.sqrt(progress) * PI * 2f) * 0.2f;
            pose.pivot(rightArm, -(float) Math.cos(bodyYaw) * 5f, pose.pivotY(rightArm), (float) Math.sin(bodyYaw) * 5f);
            pose.pivot(leftArm, (float) Math.cos(bodyYaw) * 5f, pose.pivotY(leftArm), -(float) Math.sin(bodyYaw) * 5f);
            rightArmY += bodyYaw;
            leftArmY += bodyYaw;
            leftArmX += bodyYaw;
            float eased = 1f - progress;
            eased *= eased;
            eased *= eased;
            eased = 1f - eased;
            float lift = (float) Math.sin(eased * PI);
            float headTerm = (float) Math.sin(progress * PI) * -(pose.rotationX(head) - 0.7f) * 0.75f;
            rightArmX -= lift * 1.2f + headTerm;
            rightArmY += bodyYaw * 2f;
            rightArmZ += (float) Math.sin(progress * PI) * -0.4f;
        }

        float bodyX = 0f;
        if (state.sneaking) {
            bodyX = 0.5f;
            rightArmX += 0.4f;
            leftArmX += 0.4f;
            pose.pivot(rightLeg, pose.pivotX(rightLeg), 9f, 4f);
            pose.pivot(leftLeg, pose.pivotX(leftLeg), 9f, 4f);
            pose.pivot(head, 0f, 1f, 0f);
        } else {
            pose.pivot(rightLeg, pose.pivotX(rightLeg), 12f, 0.1f);
            pose.pivot(leftLeg, pose.pivotX(leftLeg), 12f, 0.1f);
        }
        pose.rotation(body, bodyX, bodyYaw, 0f);

        float breathing = (float) Math.cos(state.ageInTicks * 0.09f) * 0.05f + 0.05f;
        float sway = (float) Math.sin(state.ageInTicks * 0.067f) * 0.05f;
        pose.rotation(rightArm, rightArmX + sway, rightArmY, rightArmZ + breathing);
        pose.rotation(leftArm, leftArmX - sway, leftArmY, leftArmZ - breathing);
    }
}
