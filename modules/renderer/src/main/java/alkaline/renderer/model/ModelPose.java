package alkaline.renderer.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mutable, reusable per-instance pose: rotation angles (radians), pivot positions, and visibility per part.
 * Animators write into it every frame; nothing here allocates after construction.
 */
public final class ModelPose {
    private final CuboidModel model;
    final float[] rotation;
    final float[] pivot;
    final boolean[] visible;

    public ModelPose(CuboidModel model) {
        this.model = Objects.requireNonNull(model, "model");
        rotation = new float[model.partCount() * 3];
        pivot = new float[model.partCount() * 3];
        visible = new boolean[model.partCount()];
        reset();
    }

    public CuboidModel model() {
        return model;
    }

    /** Restores the model's authored pivots and rotations and shows every part. */
    public void reset() {
        for (int index = 0; index < model.partCount(); index++) {
            ModelPart part = model.parts().get(index);
            pivot[index * 3] = part.pivotX();
            pivot[index * 3 + 1] = part.pivotY();
            pivot[index * 3 + 2] = part.pivotZ();
            rotation[index * 3] = part.rotationX();
            rotation[index * 3 + 1] = part.rotationY();
            rotation[index * 3 + 2] = part.rotationZ();
        }
        Arrays.fill(visible, true);
    }

    public void rotation(int part, float x, float y, float z) {
        rotation[part * 3] = x;
        rotation[part * 3 + 1] = y;
        rotation[part * 3 + 2] = z;
    }

    public void pivot(int part, float x, float y, float z) {
        pivot[part * 3] = x;
        pivot[part * 3 + 1] = y;
        pivot[part * 3 + 2] = z;
    }

    public float rotationX(int part) { return rotation[part * 3]; }
    public float rotationY(int part) { return rotation[part * 3 + 1]; }
    public float rotationZ(int part) { return rotation[part * 3 + 2]; }
    public float pivotX(int part) { return pivot[part * 3]; }
    public float pivotY(int part) { return pivot[part * 3 + 1]; }
    public float pivotZ(int part) { return pivot[part * 3 + 2]; }

    /** Copies the first {@code parts} parts' rotation and pivot from another pose (armor follows its wearer). */
    public void copyFrom(ModelPose other, int parts) {
        System.arraycopy(other.rotation, 0, rotation, 0, parts * 3);
        System.arraycopy(other.pivot, 0, pivot, 0, parts * 3);
    }

    /** Hides or shows every part. */
    public void visibleAll(boolean value) {
        Arrays.fill(visible, value);
    }

    public void visible(int part, boolean value) {
        visible[part] = value;
    }

    public boolean visible(int part) {
        return visible[part];
    }
}
