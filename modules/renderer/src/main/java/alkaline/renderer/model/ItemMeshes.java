package alkaline.renderer.model;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.world.vanilla.BlockModels189;
import alkaline.renderer.world.vanilla.BlockStates189;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything an item model draws with, built once per model name: generated items (sprites) become one extruded
 * mesh per sprite layer, each with its own texture and tint slot (potion overlays, dyed leather, spawn eggs);
 * block items become the block model's quads in 3D, with the textures they use packed side by side into one small
 * atlas and the quads grouped by tint index (grass, leaves). Both bake to {@link BakedModel} in block units 0..1,
 * the space every item renderer already places.
 */
public final class ItemMeshes {
    /** One drawable part: geometry, its texture, and which tint it takes (-1 for none). */
    public record Layer(BakedModel model, RgbaImage texture, int tintIndex) {
    }

    /**
     * @param quads    the block model's quads for block items (for GUI icons), else empty
     * @param textures the decoded textures those quads name
     */
    public record Mesh(ItemDefinition definition, List<Layer> layers, List<BlockModels189.Quad> quads,
                       Map<String, RgbaImage> textures) {
        public boolean block() {
            return definition.block();
        }
    }

    private ItemMeshes() {
    }

    /** Builds the mesh of item model {@code name}; throws when the model or its textures are missing. */
    public static Mesh build(AssetSource assets, BlockModels189 blocks, String name) throws IOException {
        return build(assets, blocks, name, null);
    }

    /**
     * Builds item model {@code name} as a custom item rule changes it (null for none): its own model file (sprites
     * or elements), or the item's sprites swapped for the rule's textures.
     */
    public static Mesh build(AssetSource assets, BlockModels189 blocks, String name, CustomItems.Rule rule)
            throws IOException {
        ItemDefinition definition = rule != null && rule.model() != null
                ? ItemDefinition.loadFile(assets, rule.model()) : ItemDefinition.load(assets, name);
        if (!definition.block()) {
            List<Layer> layers = new ArrayList<>();
            for (int index = 0; index < definition.layers().size(); index++) {
                String original = definition.layers().get(index);
                String replacement = rule == null || rule.model() != null ? null : rule.texture(original, index == 0);
                RgbaImage sprite = replacement != null ? texture(assets, replacement) : null;
                if (sprite == null) {
                    sprite = texture(assets, original);
                }
                if (sprite == null) {
                    if (index == 0) {
                        throw new IOException("Missing item sprite " + definition.layers().get(index));
                    }
                    continue;
                }
                BakedModel extruded = ItemModels.extrude(name + "#" + index, sprite);
                if (extruded.indexCount() > 0) {
                    // A fully clear sprite (an empty overlay) draws nothing.
                    layers.add(new Layer(extruded, sprite, index));
                }
            }
            if (layers.isEmpty()) {
                throw new IOException("Item " + name + " has no visible sprite.");
            }
            return new Mesh(definition, List.copyOf(layers), List.of(), Map.of());
        }
        BlockModels189.Model model = rule != null && rule.model() != null ? blocks.itemFile(rule.model())
                : blocks.item(name);
        List<BlockModels189.Quad> elements;
        if (model != null && !model.quads().isEmpty()) {
            elements = model.quads();
        } else if (ChestModels.itemTexture(name) != null) {
            // Chests are built-in entity items: TileEntityItemStackRenderer draws the chest model.
            elements = ChestModels.itemQuads(ChestModels.itemTexture(name));
        } else {
            throw new IOException("Item model " + name + " has no elements.");
        }
        return blockMesh(assets, definition, name, elements);
    }

    /**
     * A block state's own model (a minecart's load, a falling block, primed TNT, an item frame) as the world draws it,
     * not the block's item model: the first alternative of the state's variants, in 0..1 block space. Chests, which
     * vanilla draws with the chest model, come out of {@link ChestModels} as they sit in a cart (not turned the half
     * turn the item path adds). Throws when the state has no model.
     *
     * @param chestTexture the chest texture under {@code textures/} for chest-like blocks, else null
     */
    public static Mesh state(AssetSource assets, BlockModels189 blocks, BlockStates189.Location location,
                             String chestTexture, String name) throws IOException {
        ItemDefinition definition = new ItemDefinition(name, List.of(), ItemDefinition.Display.IDENTITY,
                ItemDefinition.Display.IDENTITY, ItemDefinition.Display.IDENTITY, true);
        if (chestTexture != null) {
            return blockMesh(assets, definition, name, ChestModels.itemQuads(chestTexture, false));
        }
        List<BlockModels189.Choice> choices = blocks.variants(location);
        if (choices.isEmpty() || choices.get(0).model().quads().isEmpty()) {
            throw new IOException("Block model " + location + " has no elements.");
        }
        return blockMesh(assets, definition, name, choices.get(0).model().quads());
    }

    /** Packs the textures a block model's quads use into one small atlas, one layer per tint index. */
    private static Mesh blockMesh(AssetSource assets, ItemDefinition definition, String name,
                                  List<BlockModels189.Quad> elements) throws IOException {
        Map<String, RgbaImage> textures = new LinkedHashMap<>();
        for (BlockModels189.Quad quad : elements) {
            if (!textures.containsKey(quad.texture())) {
                RgbaImage image = texture(assets, quad.texture());
                textures.put(quad.texture(), image == null ? missing() : image);
            }
        }
        // One atlas row: each texture at its own size, side by side.
        int width = 0;
        int height = 0;
        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (Map.Entry<String, RgbaImage> entry : textures.entrySet()) {
            offsets.put(entry.getKey(), width);
            width += entry.getValue().width();
            height = Math.max(height, entry.getValue().height());
        }
        byte[] pixels = new byte[width * height * 4];
        for (Map.Entry<String, RgbaImage> entry : textures.entrySet()) {
            RgbaImage image = entry.getValue();
            byte[] source = image.copyPixels();
            int x0 = offsets.get(entry.getKey());
            for (int row = 0; row < image.height(); row++) {
                System.arraycopy(source, row * image.width() * 4, pixels, (row * width + x0) * 4, image.width() * 4);
            }
        }
        RgbaImage atlas = new RgbaImage(width, height, pixels);
        Map<Integer, BakedModel.Builder> byTint = new TreeMap<>();
        for (BlockModels189.Quad quad : elements) {
            RgbaImage image = textures.get(quad.texture());
            float scaleU = image.width() / (float) width;
            float scaleV = image.height() / (float) height;
            float offsetU = offsets.get(quad.texture()) / (float) width;
            float[] uvs = new float[8];
            for (int corner = 0; corner < 4; corner++) {
                // Quad uvs are 0..1 across their texture.
                uvs[corner * 2] = offsetU + quad.uvs()[corner * 2] * scaleU;
                uvs[corner * 2 + 1] = quad.uvs()[corner * 2 + 1] * scaleV;
            }
            float[] normal = normal(quad.positions());
            byTint.computeIfAbsent(quad.tintIndex(), key -> new BakedModel.Builder())
                    .quad(0, quad.positions(), uvs, normal[0], normal[1], normal[2]);
        }
        List<Layer> layers = new ArrayList<>();
        for (Map.Entry<Integer, BakedModel.Builder> entry : byTint.entrySet()) {
            layers.add(new Layer(entry.getValue().build(name + "@" + entry.getKey(), 1), atlas, entry.getKey()));
        }
        return new Mesh(definition, List.copyOf(layers), elements, Map.copyOf(textures));
    }

    /** A quad's face normal from its corners (counter-clockwise when seen from the front). */
    static float[] normal(float[] p) {
        float ax = p[3] - p[0];
        float ay = p[4] - p[1];
        float az = p[5] - p[2];
        float bx = p[6] - p[0];
        float by = p[7] - p[1];
        float bz = p[8] - p[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6f) {
            return new float[]{0, 1, 0};
        }
        return new float[]{nx / length, ny / length, nz / length};
    }

    /** A texture's first frame (animated textures are vertical strips), or null when missing. */
    static RgbaImage texture(AssetSource assets, String path) throws IOException {
        String file = path.startsWith("minecraft:") ? path.substring(10) : path;
        // Custom item textures name full asset paths; model textures are under textures/.
        String asset = file.startsWith("assets/") ? file : "assets/minecraft/textures/" + file + ".png";
        try (InputStream input = assets.open(asset)) {
            if (input == null) {
                return null;
            }
            RgbaImage image = ImageDecoder.decode(input);
            if (image.height() > image.width()) {
                image = new RgbaImage(image.width(), image.width(),
                        Arrays.copyOf(image.copyPixels(), image.width() * image.width() * 4));
            }
            return image;
        }
    }

    /** Vanilla's missing texture: magenta and black checks. */
    private static RgbaImage missing() {
        byte[] pixels = new byte[16 * 16 * 4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean magenta = (x < 8) ^ (y < 8);
                int index = (y * 16 + x) * 4;
                pixels[index] = (byte) (magenta ? 0xF8 : 0);
                pixels[index + 2] = (byte) (magenta ? 0xF8 : 0);
                pixels[index + 3] = (byte) 0xFF;
            }
        }
        return new RgbaImage(16, 16, pixels);
    }
}
