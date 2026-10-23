package alkaline.renderer.scene;

import java.util.Arrays;
import java.util.Objects;

/** Immutable pose snapshot for an entity in world space, with an affine column-major local-to-world matrix. */
public final class EntityPose {
    private final float[] localToWorld;

    public EntityPose(float[] localToWorld) {
        Objects.requireNonNull(localToWorld, "localToWorld");
        if (localToWorld.length != 16) {
            throw new IllegalArgumentException("Entity pose transform must contain 16 column-major values.");
        }
        for (float component : localToWorld) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException("Entity pose transform values must be finite.");
            }
        }
        if (localToWorld[3] != 0f || localToWorld[7] != 0f || localToWorld[11] != 0f || localToWorld[15] != 1f) {
            throw new IllegalArgumentException("Entity pose matrix must be affine, with a homogeneous final row.");
        }
        this.localToWorld = Arrays.copyOf(localToWorld, localToWorld.length);
    }

    public static EntityPose identity() {
        return new EntityPose(new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        });
    }

    public float transformComponent(int index) {
        if (index < 0 || index >= localToWorld.length) {
            throw new IndexOutOfBoundsException("Pose matrix component outside 4x4 transform.");
        }
        return localToWorld[index];
    }

    public float[] copyTransform() { return Arrays.copyOf(localToWorld, localToWorld.length); }

    float transformX(float x, float y, float z) {
        return localToWorld[0] * x + localToWorld[4] * y + localToWorld[8] * z + localToWorld[12];
    }

    float transformY(float x, float y, float z) {
        return localToWorld[1] * x + localToWorld[5] * y + localToWorld[9] * z + localToWorld[13];
    }

    float transformZ(float x, float y, float z) {
        return localToWorld[2] * x + localToWorld[6] * y + localToWorld[10] * z + localToWorld[14];
    }
}
