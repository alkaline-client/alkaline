package alkaline.clientapp;

import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.model.GlintEffect;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanDynamicTexture;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.vulkan.ui.MenuCompositor;
import alkaline.renderer.vulkan.ui.SceneAnchor;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryUtil;

/**
 * The animated enchantment glint over HUD and inventory item icons (RenderItem.renderItemIntoGUI's renderEffect).
 *
 * <p>Icons are rasterized once on the CPU and the Compose layer is re-rasterized only when it changes, so drawing
 * a scrolling glint inside Compose would re-raster and re-upload the HUD every frame. Instead each glinted icon
 * publishes a scene anchor ({@code glint:<id>:<style>:<icon key>}) and this pass draws the glint on the GPU over the
 * composited layer, one small quad per icon and layer. The fragment shader keeps only pixels where the layer still
 * shows the icon's own opaque texel, so counts, durability bars, tooltips and dimmed or translucent copies mask it
 * as vanilla's later overlays do. Per frame this costs a few draws and no allocation; the anchor list is rebuilt
 * only when the UI publishes a new frame.
 */
final class HudGlint implements AutoCloseable {
    private static final String PREFIX = "glint:";
    private static final int PUSH_BYTES = 80;
    private static final int MAX_ICONS = 256;
    private static final TextureOptions ICON = new TextureOptions(ImageSampling.NEAREST, false, false, 1);

    private final VulkanRenderer renderer;
    private final ItemCatalog catalog;
    private final GlintStyles styles;
    private final EnumMap<PipelineSpec.Blend, VulkanPipeline[]> pipelines = new EnumMap<>(PipelineSpec.Blend.class);
    private final ByteBuffer push = MemoryUtil.memCalloc(PUSH_BYTES);
    /** Parsed anchors by name; names are stable while the icon, its style and its slot stay. */
    private final Map<String, Icon> parsed = new HashMap<>();
    private final Map<String, VulkanTexture> iconTextures = new HashMap<>();
    private final Icon[] active = new Icon[MAX_ICONS];
    private final BiConsumer<String, SceneAnchor> collect = this::collect;
    private int activeCount;
    private Map<String, SceneAnchor> anchors;
    private VulkanTexture glintTexture;
    private boolean glintLoaded;
    private int generation = -1;

    /** One glinted icon on screen: its rectangle in UI layer pixels, style and icon texture. */
    private static final class Icon {
        final String key;
        final int style;
        float left;
        float top;
        float right;
        float bottom;
        VulkanTexture texture;

        Icon(String key, int style) {
            this.key = key;
            this.style = style;
        }
    }

    HudGlint(VulkanRenderer renderer, ItemCatalog catalog) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        styles = new GlintStyles(renderer, catalog, this::prepare);
        prepare(GlintEffect.ITEM);
    }

    private void prepare(GlintEffect effect) {
        for (int index = 0; index < effect.layerCount(); index++) {
            PipelineSpec.Blend blend = effect.layer(index).blend();
            if (!pipelines.containsKey(blend)) {
                VulkanPipeline gamma = renderer.createPipeline(spec(blend).gammaSpace().build());
                VulkanPipeline linear;
                try {
                    linear = renderer.createPipeline(spec(blend).build());
                } catch (RuntimeException | Error failure) {
                    gamma.close();
                    throw failure;
                }
                pipelines.put(blend, new VulkanPipeline[]{gamma, linear});
            }
        }
    }

    private static PipelineSpec.Builder spec(PipelineSpec.Blend blend) {
        return PipelineSpec.builder("hud_glint.vert", "hud_glint.frag")
                .topology(PipelineSpec.Topology.TRIANGLE_STRIP)
                .blend(blend)
                .sampledImages(3)
                .pushConstants(PUSH_BYTES);
    }

    /** Follows the UI's newest anchors and uploads the icons they name; call outside frames. */
    void update(MenuCompositor compositor) {
        if (!glintLoaded && catalog.assets() != null) {
            glintLoaded = true;
            glintTexture = loadGlint();
        }
        if (catalog.generation() != generation) {
            generation = catalog.generation();
            closeIcons();
            parsed.clear();
            anchors = null;
        }
        styles.beforeFrame();
        Map<String, SceneAnchor> current = compositor.anchors();
        if (current == anchors) {
            return;
        }
        anchors = current;
        activeCount = 0;
        current.forEach(collect);
    }

    private void collect(String name, SceneAnchor anchor) {
        if (!name.startsWith(PREFIX) || activeCount == MAX_ICONS) {
            return;
        }
        Icon icon = parsed.get(name);
        if (icon == null) {
            int idEnd = name.indexOf(':', PREFIX.length());
            int styleEnd = idEnd < 0 ? -1 : name.indexOf(':', idEnd + 1);
            if (styleEnd < 0) {
                return;
            }
            icon = new Icon(name.substring(styleEnd + 1), Integer.parseInt(name.substring(idEnd + 1, styleEnd)));
            if (parsed.size() > 4 * MAX_ICONS) {
                parsed.clear();
            }
            parsed.put(name, icon);
        }
        if (icon.texture == null) {
            icon.texture = iconTexture(icon.key);
            if (icon.texture == null) {
                return;
            }
        }
        icon.left = anchor.getLeft();
        icon.top = anchor.getTop();
        icon.right = anchor.getRight();
        icon.bottom = anchor.getBottom();
        active[activeCount++] = icon;
    }

    private VulkanTexture iconTexture(String key) {
        VulkanTexture texture = iconTextures.get(key);
        if (texture == null) {
            RgbaImage image = catalog.iconImage(key);
            if (image == null) {
                return null;
            }
            if (iconTextures.size() >= MAX_ICONS) {
                return null; // hundreds of distinct glinted icons since the last pack change: the rest go without
            }
            texture = renderer.createTexture(image, ICON);
            iconTextures.put(key, texture);
        }
        return texture;
    }

    private VulkanTexture loadGlint() {
        alkaline.renderer.asset.AssetSource assets = catalog.assets();
        if (assets == null) {
            return null;
        }
        try (java.io.InputStream input = assets.open("assets/minecraft/textures/misc/enchanted_item_glint.png")) {
            return input == null ? null : renderer.createTexture(
                    alkaline.renderer.image.ImageDecoder.decode(input),
                    new TextureOptions(ImageSampling.LINEAR, false, true, 1));
        } catch (java.io.IOException | RuntimeException failure) {
            return null;
        }
    }

    /** Draws the glint over every glinted icon; call in the main pass right after the UI layer. */
    void draw(VulkanFrame frame, MenuCompositor compositor, long timeMillis) {
        VulkanDynamicTexture ui = compositor.uiTexture();
        if (activeCount == 0 || ui == null || glintTexture == null) {
            return;
        }
        float scaleX = frame.width() / (float) ui.width();
        float scaleY = frame.height() / (float) ui.height();
        boolean gamma = frame.beginGammaSpace();
        push.clear();
        push.putFloat(16, frame.width()).putFloat(20, frame.height()).putFloat(24, 1f / scaleX)
                .putFloat(28, 1f / scaleY);
        for (int index = 0; index < activeCount; index++) {
            Icon icon = active[index];
            GlintEffect effect = styles.item(icon.style);
            if (effect == null) {
                continue;
            }
            push.putFloat(0, icon.left * scaleX).putFloat(4, icon.top * scaleY).putFloat(8, icon.right * scaleX)
                    .putFloat(12, icon.bottom * scaleY);
            for (int layerIndex = 0; layerIndex < effect.layerCount(); layerIndex++) {
                GlintEffect.Layer layer = effect.layer(layerIndex);
                VulkanPipeline[] pair = pipelines.get(layer.blend());
                if (pair == null) {
                    continue;
                }
                float scroll = layer.scroll(timeMillis);
                int flags = (gamma ? 0 : 1) | (layer.blend() == PipelineSpec.Blend.SOURCE_SQUARED ? 4 : 0);
                push.putFloat(32, layer.m00()).putFloat(36, layer.m10()).putFloat(40, layer.m01())
                        .putFloat(44, layer.m11())
                        .putFloat(48, layer.scrollX() * scroll).putFloat(52, layer.scrollY() * scroll)
                        .putFloat(56, flags)
                        .putFloat(64, layer.tint()[0]).putFloat(68, layer.tint()[1]).putFloat(72, layer.tint()[2]);
                frame.bindPipeline(pair[gamma ? 0 : 1]);
                frame.bindImage(0, ui);
                frame.bindImage(1, icon.texture);
                frame.bindImage(2, layer.texture() == null ? glintTexture : layer.texture());
                frame.pushConstants(push);
                frame.draw(4, 1, 0, 0);
            }
        }
        if (gamma) {
            frame.endGammaSpace();
        }
    }

    private void closeIcons() {
        for (VulkanTexture texture : iconTextures.values()) {
            texture.close();
        }
        iconTextures.clear();
        activeCount = 0;
    }

    @Override
    public void close() {
        closeIcons();
        styles.close();
        if (glintTexture != null) {
            glintTexture.close();
        }
        for (VulkanPipeline[] pair : pipelines.values()) {
            pair[0].close();
            pair[1].close();
        }
        MemoryUtil.memFree(push);
    }
}
