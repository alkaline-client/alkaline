package alkaline.renderer.vulkan.model;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.VulkanSampledImage;
import java.util.List;
import java.util.Objects;

/**
 * An enchantment glint: one or more texture layers redrawn over a model with depth equal, each scrolling through
 * its own texture matrix. Built once (vanilla's two, or a resource pack's CIT {@code type=enchantment} rules) and
 * submitted with every glinted instance by reference.
 *
 * <p>Every layer's pattern is {@code M * (c + offset + instanceTime * drift)}: {@code c} is the texture
 * coordinate the glint scrolls through (the sprite's block-atlas coordinate for items, the model's own uv for
 * armor), {@code M} the matrix's fixed 2x2 part, {@code offset} the scroll at the current time and {@code drift}
 * the per-instance scroll (vanilla armor scrolls with the wearer's age).
 */
public final class GlintEffect {
    /** RenderItem.renderEffect's vertex color 0x8040CC. */
    private static final float[] ITEM_TINT = {128f / 255f, 64f / 255f, 204f / 255f};
    /** LayerArmorBase.renderGlint: color(0.5, 0.25, 0.8) times 0.76. */
    private static final float[] ARMOR_TINT = {0.5f * 0.76f, 0.25f * 0.76f, 0.8f * 0.76f};
    private static final float[] WHITE = {1f, 1f, 1f};

    /**
     * RenderItem.renderEffect: scale(8), translate(scroll, 0), rotate(-50 or 10), the second layer scrolling the
     * other way on a 4873 ms period.
     */
    public static final GlintEffect ITEM = new GlintEffect(false, List.of(
            Layer.item(null, PipelineSpec.Blend.SOURCE_SQUARED, 8f, -50f, 3000, 1f, ITEM_TINT),
            Layer.item(null, PipelineSpec.Blend.SOURCE_SQUARED, 8f, 10f, 4873, -1f, ITEM_TINT)));

    /**
     * LayerArmorBase.renderGlint: scale(1/3), rotate(30 - 60 i), translate(0, age * (0.001 + 0.003 i) * 20), with
     * age the wearer's ticksExisted plus the partial tick.
     */
    public static final GlintEffect ARMOR = new GlintEffect(true, List.of(
            Layer.vanillaArmor(0), Layer.vanillaArmor(1)));

    final boolean modelUv;
    final Layer[] layers;

    private GlintEffect(boolean modelUv, List<Layer> layers) {
        this.modelUv = modelUv;
        this.layers = layers.toArray(new Layer[0]);
    }

    /** Custom glint over items: layers built with {@link Layer#item}, drawn in order. */
    public static GlintEffect items(List<Layer> layers) {
        return new GlintEffect(false, layers);
    }

    /** Custom glint over armor: layers built with {@link Layer#armor}, drawn in order. */
    public static GlintEffect armor(List<Layer> layers) {
        return new GlintEffect(true, layers);
    }

    /** The layers, for passes that draw them outside {@link ModelRenderer} (HUD icons). */
    public int layerCount() {
        return layers.length;
    }

    public Layer layer(int index) {
        return layers[index];
    }

    /**
     * One pass. {@code texture} null means the default {@code enchanted_item_glint.png}. The 2x2 matrix is column
     * major; {@code scroll} is the offset direction per unit of the time-based scroll, which advances
     * {@code speed / 8} per {@code periodMillis}.
     */
    public record Layer(VulkanSampledImage texture, PipelineSpec.Blend blend, float m00, float m10, float m01,
                        float m11, float scrollX, float scrollY, int periodMillis, float speed, float driftX,
                        float driftY, float[] tint) {
        public Layer {
            Objects.requireNonNull(blend, "blend");
            if (periodMillis <= 0) {
                throw new IllegalArgumentException("Scroll period must be positive.");
            }
        }

        /**
         * An item pass (RenderItem.renderEffect, OptiFine CustomItems.renderCustomEffect): scale, translate(scroll,
         * 0), rotate(degrees). OptiFine's custom textures scale by their width / 2 and are drawn white.
         */
        public static Layer item(VulkanSampledImage texture, PipelineSpec.Blend blend, float scale, float degrees,
                                 int periodMillis, float speed, float[] tint) {
            float c = (float) Math.cos(Math.toRadians(degrees));
            float s = (float) Math.sin(Math.toRadians(degrees));
            // S * (R * c + (t, 0)) = (S * R) * (c + R^T * (t, 0)).
            return new Layer(texture, blend, scale * c, scale * s, -scale * s, scale * c, c, -s, periodMillis, speed,
                    0f, 0f, tint == null ? WHITE : tint);
        }

        /**
         * OptiFine CustomItems.renderCustomArmorEffect: rotate(degrees), scale(width / 8, width / 16),
         * translate(0, scroll), drawn white.
         */
        public static Layer armor(VulkanSampledImage texture, PipelineSpec.Blend blend, float width, float degrees,
                                  float speed) {
            float c = (float) Math.cos(Math.toRadians(degrees));
            float s = (float) Math.sin(Math.toRadians(degrees));
            float sx = width / 8f;
            float sy = width / 16f;
            return new Layer(texture, blend, c * sx, s * sx, -s * sy, c * sy, 0f, 1f, 3000, speed, 0f, 0f, WHITE);
        }

        private static Layer vanillaArmor(int index) {
            double angle = Math.toRadians(30.0 - index * 60.0);
            float c = (float) Math.cos(angle) / 3f;
            float s = (float) Math.sin(angle) / 3f;
            return new Layer(null, PipelineSpec.Blend.SOURCE_SQUARED, c, s, -s, c, 0f, 0f, 3000, 0f, 0f,
                    (0.001f + index * 0.003f) * 20f, ARMOR_TINT);
        }

        /** The time-based scroll at a clock reading, as vanilla and OptiFine compute it. */
        public float scroll(long timeMillis) {
            return speed * (float) (timeMillis % periodMillis) / periodMillis / 8f;
        }
    }
}
