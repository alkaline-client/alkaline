package alkaline.renderer.world;

import java.util.Arrays;
import java.util.Objects;

/** Immutable lookup table for visibility and pass classification of compact material ids. */
public final class MaterialTable {
    private final MaterialPass[] passes;
    private final boolean[] occludesFaces;

    public MaterialTable(MaterialPass[] passesById, boolean[] occludesFacesById) {
        Objects.requireNonNull(passesById, "passesById");
        Objects.requireNonNull(occludesFacesById, "occludesFacesById");
        if (passesById.length != occludesFacesById.length || passesById.length < 2) {
            throw new IllegalArgumentException("Material lookup arrays must have equal lengths and include at least one material.");
        }
        if (passesById[0] != null || occludesFacesById[0]) {
            throw new IllegalArgumentException("Material id zero is reserved for empty space.");
        }
        for (int id = 1; id < passesById.length; id++) {
            if (passesById[id] == null) {
                throw new IllegalArgumentException("Every non-empty material id requires a render pass.");
            }
        }
        passes = Arrays.copyOf(passesById, passesById.length);
        occludesFaces = Arrays.copyOf(occludesFacesById, occludesFacesById.length);
    }

    public MaterialPass pass(char materialId) {
        requireKnown(materialId);
        return passes[materialId];
    }

    public boolean occludesFaces(char materialId) {
        requireKnown(materialId);
        return materialId != 0 && occludesFaces[materialId];
    }

    private void requireKnown(char materialId) {
        if (materialId >= passes.length) {
            throw new IllegalArgumentException("Unknown material id: " + (int) materialId);
        }
    }
}
