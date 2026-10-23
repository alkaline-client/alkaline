package alkaline.renderer.scene;

import alkaline.renderer.world.MaterialPass;

import java.util.Objects;

/** Immutable visibility and render-pass view used to select cached section output. */
public final class SceneView {
    private final Aabb bounds;
    private final boolean opaque;
    private final boolean cutout;
    private final boolean translucent;

    public SceneView(Aabb bounds, boolean opaque, boolean cutout, boolean translucent) {
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.opaque = opaque;
        this.cutout = cutout;
        this.translucent = translucent;
    }

    public Aabb bounds() { return bounds; }
    public boolean includes(MaterialPass pass) {
        return switch (Objects.requireNonNull(pass, "pass")) {
            case OPAQUE -> opaque;
            case CUTOUT -> cutout;
            case TRANSLUCENT -> translucent;
        };
    }
}
