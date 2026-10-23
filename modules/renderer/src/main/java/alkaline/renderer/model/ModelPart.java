package alkaline.renderer.model;

import java.util.List;
import java.util.Objects;

/**
 * Named node of a model hierarchy. Its local transform is {@code translate(pivot) * Rz * Ry * Rx}, the order
 * Minecraft applies to model parts; children inherit the parent's transform.
 *
 * @param parent index of the parent part, or -1 for a root part; parents always precede children
 */
public record ModelPart(String name, int parent, float pivotX, float pivotY, float pivotZ,
                        float rotationX, float rotationY, float rotationZ, List<ModelBox> boxes) {
    public ModelPart {
        Objects.requireNonNull(name, "name");
        boxes = List.copyOf(boxes);
        if (!Float.isFinite(pivotX) || !Float.isFinite(pivotY) || !Float.isFinite(pivotZ)
                || !Float.isFinite(rotationX) || !Float.isFinite(rotationY) || !Float.isFinite(rotationZ)) {
            throw new IllegalArgumentException("Model part " + name + " has a non-finite transform.");
        }
    }
}
