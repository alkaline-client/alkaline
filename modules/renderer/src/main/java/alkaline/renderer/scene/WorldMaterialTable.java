package alkaline.renderer.scene;

import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.world.MaterialTable;

import java.util.Arrays;
import java.util.Objects;

/** Immutable material id lookup, retaining the occlusion and pass rules used by the section mesher. */
public final class WorldMaterialTable {
    private final MaterialTable mesherMaterials;
    private final WorldMaterial[] renderMaterials;

    public WorldMaterialTable(WorldMaterial[] materialsById, MaterialPass[] passesById,
                              boolean[] occludesFacesById) {
        Objects.requireNonNull(materialsById, "materialsById");
        Objects.requireNonNull(passesById, "passesById");
        Objects.requireNonNull(occludesFacesById, "occludesFacesById");
        if (materialsById.length != passesById.length || materialsById.length != occludesFacesById.length
                || materialsById.length < 2 || materialsById[0] != null) {
            throw new IllegalArgumentException("World material arrays must have equal lengths and reserve id zero.");
        }
        renderMaterials = Arrays.copyOf(materialsById, materialsById.length);
        for (int id = 1; id < renderMaterials.length; id++) {
            WorldMaterial material = renderMaterials[id];
            if (material == null || material.materialId() != id) {
                throw new IllegalArgumentException("Every material id must have a matching render material.");
            }
        }
        mesherMaterials = new MaterialTable(passesById, occludesFacesById);
    }

    public int size() { return renderMaterials.length; }

    MaterialTable mesherTable() { return mesherMaterials; }

    public WorldMaterial material(char id) {
        if (id >= renderMaterials.length) throw new IllegalArgumentException("Unknown material id: " + (int) id);
        if (id == 0) throw new IllegalArgumentException("Empty space has no render material.");
        return renderMaterials[id];
    }

    public MaterialPass pass(char id) { return mesherMaterials.pass(id); }
    public boolean occludesFaces(char id) { return mesherMaterials.occludesFaces(id); }
}
