package alkaline.renderer.model;

import alkaline.config.Json;
import alkaline.renderer.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A vanilla item model definition ({@code assets/minecraft/models/item/<name>.json}): its sprite layers and the
 * display transforms, resolved through the {@code parent} chain the way vanilla's model loader inherits them.
 *
 * @param layers texture paths under {@code assets/minecraft/textures/}, layer0 first (for example
 *               {@code items/diamond_sword}); empty for block-shaped items
 * @param block  whether the model has elements (a block item, drawn in 3D and doubled in the hand) rather than
 *               generated sprite layers
 */
public record ItemDefinition(String name, List<String> layers, Display firstPerson, Display thirdPerson,
                             Display gui, boolean block) {
    /**
     * One display transform as vanilla applies it: translate, rotate about y then x then z (degrees), scale.
     * Translation is already in blocks (the JSON's sixteenths divided by 16 and clamped to +-1.5); scale is
     * clamped to +-4, as vanilla's loader does.
     */
    public record Display(float rotationX, float rotationY, float rotationZ, float translationX, float translationY,
                          float translationZ, float scaleX, float scaleY, float scaleZ) {
        public static final Display IDENTITY = new Display(0, 0, 0, 0, 0, 0, 1, 1, 1);
    }

    public ItemDefinition {
        Objects.requireNonNull(name, "name");
        layers = List.copyOf(layers);
    }

    public ItemDefinition(String name, List<String> layers, Display firstPerson, Display thirdPerson) {
        this(name, layers, firstPerson, thirdPerson, Display.IDENTITY, false);
    }

    /** Loads and resolves an item model; throws when the file is missing, or a generated model has no sprite. */
    public static ItemDefinition load(AssetSource assets, String name) throws IOException {
        return load(assets, name, null);
    }

    /**
     * Loads an item model from a full asset path ({@code assets/.../model.json}), as OptiFine's custom item
     * models are; its parents resolve as usual, and textures starting with {@code ./} sit beside the file (they
     * become full asset paths ending in {@code .png}).
     */
    public static ItemDefinition loadFile(AssetSource assets, String path) throws IOException {
        return load(assets, path, path);
    }

    private static ItemDefinition load(AssetSource assets, String name, String file) throws IOException {
        Map<String, String> textures = new HashMap<>();
        Display firstPerson = null;
        Display thirdPerson = null;
        Display gui = null;
        boolean generated = false;
        String model = file != null ? file : "item/" + name;
        String directory = file != null ? file.substring(0, file.lastIndexOf('/') + 1) : null;
        for (int depth = 0; depth < 8 && model != null; depth++) {
            if (model.startsWith("builtin/")) {
                generated = model.equals("builtin/generated");
                break;
            }
            Map<?, ?> json = read(assets, depth == 0 && file != null ? file
                    : "assets/minecraft/models/" + model + ".json");
            if (depth == 0 && json != null && directory != null && json.get("textures") instanceof Map<?, ?> own) {
                Map<Object, Object> resolved = new HashMap<>();
                for (Map.Entry<?, ?> entry : own.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String text && text.startsWith("./")) {
                        value = directory + text.substring(2) + ".png";
                    }
                    resolved.put(entry.getKey(), value);
                }
                Map<Object, Object> copy = new HashMap<>(json);
                copy.put("textures", resolved);
                json = copy;
            }
            if (json == null) {
                if (depth == 0) {
                    throw new IOException("No item model for " + name);
                }
                break;
            }
            if (json.get("textures") instanceof Map<?, ?> declared) {
                for (Map.Entry<?, ?> entry : declared.entrySet()) {
                    if (entry.getValue() instanceof String value) {
                        textures.putIfAbsent(String.valueOf(entry.getKey()), value);
                    }
                }
            }
            if (json.get("display") instanceof Map<?, ?> display) {
                if (firstPerson == null && display.get("firstperson") instanceof Map<?, ?> transform) {
                    firstPerson = display(transform);
                }
                if (thirdPerson == null && display.get("thirdperson") instanceof Map<?, ?> transform) {
                    thirdPerson = display(transform);
                }
                if (gui == null && display.get("gui") instanceof Map<?, ?> transform) {
                    gui = display(transform);
                }
            }
            model = json.get("parent") instanceof String parent ? parent : null;
        }
        List<String> layers = new ArrayList<>();
        for (int layer = 0; layers.size() == layer && layer < 5; layer++) {
            String texture = textures.get("layer" + layer);
            if (texture != null) {
                layers.add(texture.startsWith("minecraft:") ? texture.substring(10) : texture);
            }
        }
        if (layers.isEmpty() && generated) {
            throw new IOException("Item model " + name + " has no generated sprite layers.");
        }
        return new ItemDefinition(name, layers, firstPerson == null ? Display.IDENTITY : firstPerson,
                thirdPerson == null ? Display.IDENTITY : thirdPerson, gui == null ? Display.IDENTITY : gui,
                !generated && layers.isEmpty());
    }

    private static Map<?, ?> read(AssetSource assets, String path) throws IOException {
        try (InputStream input = assets.open(path)) {
            if (input == null) {
                return null;
            }
            Object parsed = Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> map ? map : null;
        } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException missing) {
            return null;
        }
    }

    private static Display display(Map<?, ?> json) {
        float[] rotation = vector(json.get("rotation"), 0f);
        float[] translation = vector(json.get("translation"), 0f);
        float[] scale = vector(json.get("scale"), 1f);
        for (int axis = 0; axis < 3; axis++) {
            translation[axis] = Math.max(-1.5f, Math.min(1.5f, translation[axis] * 0.0625f));
            scale[axis] = Math.max(-4f, Math.min(4f, scale[axis]));
        }
        return new Display(rotation[0], rotation[1], rotation[2], translation[0], translation[1], translation[2],
                scale[0], scale[1], scale[2]);
    }

    private static float[] vector(Object value, float fallback) {
        float[] result = {fallback, fallback, fallback};
        if (value instanceof List<?> list && list.size() == 3) {
            for (int axis = 0; axis < 3; axis++) {
                if (list.get(axis) instanceof Number number) {
                    result[axis] = number.floatValue();
                }
            }
        }
        return result;
    }
}
