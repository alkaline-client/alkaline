package alkaline.renderer.scene;

import alkaline.renderer.world.SectionMesher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded synchronous cache for immutable compiled sections. Access and compilation are serialized;
 * unchanged input and material/mesher identities reuse the existing mesh without rebuilding it.
 */
public final class WorldMeshCache {
    private final int maximumEntries;
    private final long maximumBytes;
    private final LinkedHashMap<SectionKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    public WorldMeshCache(int maximumEntries, long maximumBytes) {
        if (maximumEntries <= 0 || maximumBytes <= 0) {
            throw new IllegalArgumentException("Cache limits must be positive.");
        }
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
    }

    public synchronized WorldMesh getOrCompile(WorldSectionInput input,
                                               WorldMaterialTable materials,
                                               SectionMesher mesher) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(mesher, "mesher");
        SectionKey key = input.key();
        Entry existing = entries.get(key);
        // Identity first: callers that keep their section inputs avoid an O(voxels) comparison per lookup.
        if (existing != null && (existing.input == input || existing.input.contentEquals(input))
                && existing.materials == materials && existing.mesher == mesher) {
            return existing.mesh;
        }

        if (existing != null) remove(key, existing);
        WorldMesh compiled = WorldMeshCompiler.compile(input, materials, mesher);
        long weight = Math.addExact(compiled.estimatedBytes(), input.estimatedBytes());
        if (weight <= maximumBytes) {
            Entry entry = new Entry(input, materials, mesher, compiled, weight);
            entries.put(key, entry);
            cachedBytes = Math.addExact(cachedBytes, weight);
            evictToLimits();
        }
        return compiled;
    }

    /** Culls by conservative section bounds before compiling; callers still select enabled passes. */
    public synchronized List<WorldMesh> visibleMeshes(Iterable<WorldSectionInput> sections,
                                                       WorldMaterialTable materials,
                                                       SectionMesher mesher,
                                                       SceneView view) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(mesher, "mesher");
        Objects.requireNonNull(view, "view");
        ArrayList<WorldMesh> visible = new ArrayList<>();
        for (WorldSectionInput section : sections) {
            Objects.requireNonNull(section, "section");
            if (section.worldBounds().intersects(view.bounds())) {
                WorldMesh mesh = getOrCompile(section, materials, mesher);
                if (hasVisibleBatch(mesh, view)) visible.add(mesh);
            }
        }
        return List.copyOf(visible);
    }

    public synchronized boolean invalidate(SectionKey section) {
        Objects.requireNonNull(section, "section");
        Entry removed = entries.remove(section);
        if (removed == null) return false;
        cachedBytes -= removed.weight;
        return true;
    }

    public synchronized void clear() {
        entries.clear();
        cachedBytes = 0;
    }

    public synchronized int cachedSectionCount() { return entries.size(); }
    public synchronized long cachedBytes() { return cachedBytes; }

    private static boolean hasVisibleBatch(WorldMesh mesh, SceneView view) {
        for (int batch = 0; batch < mesh.batchCount(); batch++) {
            if (view.includes(mesh.batchPass(batch))) return true;
        }
        return false;
    }

    private void evictToLimits() {
        while (entries.size() > maximumEntries || cachedBytes > maximumBytes) {
            Map.Entry<SectionKey, Entry> eldest = entries.entrySet().iterator().next();
            remove(eldest.getKey(), eldest.getValue());
        }
    }

    private void remove(SectionKey key, Entry entry) {
        entries.remove(key);
        cachedBytes -= entry.weight;
    }

    private static final class Entry {
        private final WorldSectionInput input;
        private final WorldMaterialTable materials;
        private final SectionMesher mesher;
        private final WorldMesh mesh;
        private final long weight;

        private Entry(WorldSectionInput input, WorldMaterialTable materials,
                      SectionMesher mesher, WorldMesh mesh, long weight) {
            this.input = input;
            this.materials = materials;
            this.mesher = mesher;
            this.mesh = mesh;
            this.weight = weight;
        }
    }
}
