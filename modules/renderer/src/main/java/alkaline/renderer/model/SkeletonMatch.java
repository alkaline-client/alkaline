package alkaline.renderer.model;

/**
 * The last skeleton a renderer resolved: the parent chain, rotations, pivots and root that produced its part
 * matrices. A later pose that matches it part for part resolves to the same matrices, so it can reuse them; armor
 * copies the wearer's pose exactly, which makes the comparison exact rather than approximate.
 */
public final class SkeletonMatch {
    private final float[] rotation = new float[CuboidModel.MAX_PARTS * 3];
    private final float[] pivot = new float[CuboidModel.MAX_PARTS * 3];
    private final float[] root = new float[16];
    private CuboidModel model;
    private int parts;
    private boolean valid;

    /** Records the skeleton {@code pose} and {@code root} resolve to. */
    public void remember(ModelPose pose, float[] root) {
        model = pose.model();
        parts = model.partCount();
        System.arraycopy(pose.rotation, 0, rotation, 0, parts * 3);
        System.arraycopy(pose.pivot, 0, pivot, 0, parts * 3);
        System.arraycopy(root, 0, this.root, 0, 16);
        valid = true;
    }

    public void clear() {
        valid = false;
        model = null;
    }

    /** Whether {@code pose} under {@code root} resolves to the first matrices of the remembered skeleton. */
    public boolean matches(ModelPose pose, float[] root) {
        CuboidModel candidate = pose.model();
        int count = candidate.partCount();
        if (!valid || count > parts) {
            return false;
        }
        for (int index = 0; index < 16; index++) {
            if (root[index] != this.root[index]) {
                return false;
            }
        }
        if (candidate != model) {
            for (int part = 0; part < count; part++) {
                if (candidate.parent(part) != model.parent(part)) {
                    return false;
                }
            }
        }
        for (int index = 0; index < count * 3; index++) {
            if (pose.rotation[index] != rotation[index] || pose.pivot[index] != pivot[index]) {
                return false;
            }
        }
        return true;
    }
}
