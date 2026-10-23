package alkaline.renderer.scene;

import alkaline.renderer.world.MaterialPass;

import java.util.Objects;

/** Immutable renderer-neutral entity draw input for one model at one captured pose. */
public record EntityInput(long entityId, EntityModelSnapshot model, EntityPose pose, MaterialPass pass) {
    public EntityInput {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(pass, "pass");
    }
}
