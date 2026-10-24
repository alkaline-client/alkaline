package alkaline.renderer.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable cuboid model: an ordered part hierarchy plus the texture size its UVs are measured against.
 * Models are plain data so they can be authored in code today and loaded from files later.
 */
public final class CuboidModel {
    public static final int MAX_PARTS = 64;

    private final String id;
    private final int textureWidth;
    private final int textureHeight;
    private final List<ModelPart> parts;
    private final Map<String, Integer> indexByName;
    /** Each part's parent index (-1 for roots), flat for the per-frame pose walk. */
    private final int[] parents;

    private CuboidModel(String id, int textureWidth, int textureHeight, List<ModelPart> parts) {
        this.id = id;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.parts = List.copyOf(parts);
        Map<String, Integer> names = new HashMap<>();
        for (int index = 0; index < this.parts.size(); index++) {
            names.put(this.parts.get(index).name(), index);
        }
        indexByName = Map.copyOf(names);
        parents = new int[this.parts.size()];
        for (int index = 0; index < parents.length; index++) {
            parents[index] = this.parts.get(index).parent();
        }
    }

    public static Builder builder(String id, int textureWidth, int textureHeight) {
        return new Builder(id, textureWidth, textureHeight);
    }

    public String id() {
        return id;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    public List<ModelPart> parts() {
        return parts;
    }

    public int partCount() {
        return parents.length;
    }

    /** The parent part's index, or -1 for a root part. */
    public int parent(int part) {
        return parents[part];
    }

    /** Index of a named part; throws when absent so pose code fails at setup, not per frame. */
    public int partIndex(String name) {
        Integer index = indexByName.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Model " + id + " has no part named " + name);
        }
        return index;
    }

    public static final class Builder {
        private final String id;
        private final int textureWidth;
        private final int textureHeight;
        private final List<ModelPart> parts = new ArrayList<>();
        private final Map<String, Integer> names = new HashMap<>();

        private Builder(String id, int textureWidth, int textureHeight) {
            this.id = Objects.requireNonNull(id, "id");
            if (textureWidth <= 0 || textureHeight <= 0) {
                throw new IllegalArgumentException("Texture size must be positive.");
            }
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
        }

        /** Adds a root or child part; pass null as parent for a root. */
        public Builder part(String name, String parent, float pivotX, float pivotY, float pivotZ, ModelBox... boxes) {
            return part(name, parent, pivotX, pivotY, pivotZ, 0f, 0f, 0f, boxes);
        }

        /** Adds a part with an authored rotation (radians), which {@link ModelPose#reset()} restores. */
        public Builder part(String name, String parent, float pivotX, float pivotY, float pivotZ, float rotationX,
                            float rotationY, float rotationZ, ModelBox... boxes) {
            if (names.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate part " + name);
            }
            if (parts.size() == MAX_PARTS) {
                throw new IllegalArgumentException("Models are limited to " + MAX_PARTS + " parts.");
            }
            int parentIndex = -1;
            if (parent != null) {
                Integer found = names.get(parent);
                if (found == null) {
                    throw new IllegalArgumentException("Parent " + parent + " must be added before " + name);
                }
                parentIndex = found;
            }
            names.put(name, parts.size());
            parts.add(new ModelPart(name, parentIndex, pivotX, pivotY, pivotZ, rotationX, rotationY, rotationZ,
                    List.of(boxes)));
            return this;
        }

        public CuboidModel build() {
            if (parts.isEmpty()) {
                throw new IllegalStateException("A model needs at least one part.");
            }
            return new CuboidModel(id, textureWidth, textureHeight, parts);
        }
    }
}
