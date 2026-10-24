package alkaline.renderer.vulkan.model;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanSampledImage;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * The vignette texture across the whole view, subtracted from the scene in a color (GuiIngame.renderVignette's
 * (ZERO, ONE_MINUS_SRC_COLOR) blend): the world border's warning draws it as a red wash.
 */
public final class ScreenTint implements AutoCloseable {
    private final VulkanPipeline pipeline;
    private final ByteBuffer push = MemoryUtil.memCalloc(16);

    public ScreenTint(VulkanRenderer renderer, RenderTargetFormat target) {
        Objects.requireNonNull(renderer, "renderer");
        PipelineSpec.Builder builder = PipelineSpec.builder("fullscreen.vert", "vignette.frag")
                .blend(PipelineSpec.Blend.BURN)
                .sampledImage(true)
                .pushConstants(16);
        if (target != null) {
            builder.offscreen(target, true);
        }
        pipeline = renderer.createPipeline(builder.build());
    }

    /** Draws {@code vignette} scaled by (red, green, blue) over the current pass. */
    public void draw(VulkanFrame frame, VulkanSampledImage vignette, float red, float green, float blue) {
        push.putFloat(0, red).putFloat(4, green).putFloat(8, blue).putFloat(12, 1f);
        push.clear();
        frame.bindPipeline(pipeline);
        frame.bindImage(vignette);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
    }

    @Override
    public void close() {
        pipeline.close();
        MemoryUtil.memFree(push);
    }
}
