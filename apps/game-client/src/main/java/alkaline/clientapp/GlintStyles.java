package alkaline.clientapp;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.model.CustomItems;
import alkaline.renderer.vulkan.model.GlintEffect;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The GPU side of {@link ItemCatalog#glintStyle}: vanilla's glint effects for style 0, and for a resource pack's
 * enchantment rules (OptiFine CIT {@code type=enchantment}) one effect over items and one over armor, built with
 * the rules' textures once they load. Render-thread state; textures load in {@link #beforeFrame}.
 */
final class GlintStyles implements AutoCloseable {
    private static final TextureOptions GLINT_TEXTURE = new TextureOptions(ImageSampling.LINEAR, false, true, 1);

    private final VulkanRenderer renderer;
    private final ItemCatalog catalog;
    private final Consumer<GlintEffect> prepare;
    private final List<GlintEffect> items = new ArrayList<>();
    private final List<GlintEffect> armor = new ArrayList<>();
    private final Map<String, VulkanTexture> textures = new HashMap<>();
    private final Map<String, Integer> widths = new HashMap<>();
    private int generation = -1;
    /** The highest style asked for that has no effects yet. */
    private int wanted;

    /**
     * @param prepare called outside frames with each new effect, so its user can build the pipelines it needs
     */
    GlintStyles(VulkanRenderer renderer, ItemCatalog catalog, Consumer<GlintEffect> prepare) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.prepare = Objects.requireNonNull(prepare, "prepare");
    }

    /** The effect over an item of this style, or null (no glint, or a pack's still loading). */
    GlintEffect item(int style) {
        return effect(items, style, GlintEffect.ITEM);
    }

    /** The effect over worn armor of this style, or null. */
    GlintEffect armor(int style) {
        return effect(armor, style, GlintEffect.ARMOR);
    }

    private GlintEffect effect(List<GlintEffect> built, int style, GlintEffect vanilla) {
        if (style <= 0) {
            return style == 0 ? vanilla : null;
        }
        if (style > built.size()) {
            wanted = Math.max(wanted, style);
            return null;
        }
        return built.get(style - 1);
    }

    /** Builds the effects of styles asked for since the last call; call outside frames. */
    void beforeFrame() {
        if (catalog.generation() != generation) {
            closeTextures();
            items.clear();
            armor.clear();
            wanted = 0;
            generation = catalog.generation();
        }
        while (items.size() < wanted) {
            List<CustomItems.Rule> rules = catalog.glintRules(items.size() + 1);
            List<GlintEffect.Layer> itemLayers = new ArrayList<>();
            List<GlintEffect.Layer> armorLayers = new ArrayList<>();
            for (CustomItems.Rule rule : rules) {
                String path = rule.textures().get("");
                VulkanTexture texture = path == null ? null : texture(path);
                if (texture == null) {
                    continue;
                }
                CustomItems.Glint glint = rule.glint();
                PipelineSpec.Blend blend = blend(glint.blend());
                // CustomItemProperties.getTextureWidth: the texture's width in pixels.
                float width = widths.get(path);
                itemLayers.add(GlintEffect.Layer.item(texture, blend, width / 2f, glint.rotation(), 3000,
                        glint.speed(), null));
                armorLayers.add(GlintEffect.Layer.armor(texture, blend, width, glint.rotation(), glint.speed()));
            }
            GlintEffect item = GlintEffect.items(itemLayers);
            GlintEffect worn = GlintEffect.armor(armorLayers);
            prepare.accept(item);
            prepare.accept(worn);
            items.add(item);
            armor.add(worn);
        }
    }

    private VulkanTexture texture(String path) {
        VulkanTexture texture = textures.get(path);
        if (texture != null || textures.containsKey(path)) {
            return texture;
        }
        RgbaImage image = image(catalog.assets(), path);
        texture = image == null ? null : renderer.createTexture(image, GLINT_TEXTURE);
        if (image == null) {
            System.err.println("Custom glint texture " + path + " not found.");
        } else {
            widths.put(path, image.width());
        }
        textures.put(path, texture);
        return texture;
    }

    private static RgbaImage image(AssetSource assets, String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** Blender.setupBlend's modes; {@code replace} draws without blending. */
    static PipelineSpec.Blend blend(String name) {
        return switch (name) {
            case "alpha" -> PipelineSpec.Blend.ALPHA;
            case "subtract" -> PipelineSpec.Blend.INVERT;
            case "multiply" -> PipelineSpec.Blend.MULTIPLY;
            case "dodge" -> PipelineSpec.Blend.DODGE;
            case "burn" -> PipelineSpec.Blend.BURN;
            case "screen" -> PipelineSpec.Blend.SCREEN;
            case "overlay" -> PipelineSpec.Blend.OVERLAY;
            case "replace" -> PipelineSpec.Blend.NONE;
            default -> PipelineSpec.Blend.ADDITIVE;
        };
    }

    private void closeTextures() {
        for (VulkanTexture texture : textures.values()) {
            if (texture != null) {
                texture.close();
            }
        }
        textures.clear();
        widths.clear();
    }

    @Override
    public void close() {
        closeTextures();
    }
}
