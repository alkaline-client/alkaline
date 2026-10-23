package alkaline.renderer.vulkan.particle;

import alkaline.renderer.particle.ParticleSystem;
import alkaline.renderer.particle.ParticleType;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanSampledImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws a {@link ParticleSystem} as instanced billboards: one instanced draw per texture sheet and blend
 * family, with the lit particle records streamed straight into mapped memory. Depth-tested without depth writes,
 * like vanilla's particle layers. Call inside a pass after setting the scene viewport.
 */
public final class ParticleRenderer implements AutoCloseable {
    private static final int PUSH_BYTES = 96;

    private final VulkanPipeline alpha;
    private final VulkanPipeline additive;
    private final VulkanPipeline blocks;
    private final boolean linearOutput;
    private final ByteBuffer push;

    public ParticleRenderer(VulkanRenderer renderer) {
        this(renderer, null);
    }

    /** @param target offscreen color format (with depth) the particles render into, or null for the main pass */
    public ParticleRenderer(VulkanRenderer renderer, RenderTargetFormat target) {
        Objects.requireNonNull(renderer, "renderer");
        linearOutput = target == null ? renderer.presentsSrgb() : target != RenderTargetFormat.RGBA8_UNORM;
        List<VulkanPipeline> created = new ArrayList<>();
        try {
            alpha = track(created, renderer.createPipeline(spec("particle.frag", PipelineSpec.Blend.ALPHA, target)));
            additive = track(created, renderer.createPipeline(spec("particle.frag", PipelineSpec.Blend.ADDITIVE, target)));
            blocks = track(created, renderer.createPipeline(spec("particle_block.frag", PipelineSpec.Blend.ALPHA, target)));
        } catch (RuntimeException | Error failure) {
            for (VulkanPipeline pipeline : created) {
                pipeline.close();
            }
            throw failure;
        }
        push = MemoryUtil.memCalloc(PUSH_BYTES);
    }

    private static VulkanPipeline track(List<VulkanPipeline> created, VulkanPipeline pipeline) {
        created.add(pipeline);
        return pipeline;
    }

    private static PipelineSpec spec(String fragment, PipelineSpec.Blend blend, RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("particle.vert", fragment)
                .binding(0, ParticleSystem.INSTANCE_BYTES, true)
                .attribute(0, 0, VertexFormat.FLOAT3, 0)
                .attribute(1, 0, VertexFormat.FLOAT1, 12)
                .attribute(2, 0, VertexFormat.UNORM16X2, 16)
                .attribute(3, 0, VertexFormat.UNORM16X2, 20)
                .attribute(4, 0, VertexFormat.UNORM8X4, 24)
                .attribute(5, 0, VertexFormat.UINT1, 28)
                .topology(PipelineSpec.Topology.TRIANGLE_STRIP)
                .blend(blend)
                .depth(true, false)
                .sampledImage(true)
                .pushConstants(PUSH_BYTES);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder.build();
    }

    /**
     * @param sheet          {@code particles.png}
     * @param explosion      {@code entity/explosion.png}, or null to skip explosion particles
     * @param blockTextures  the world's block texture array, or null to skip block fragments
     * @param lightmap       256 lightmap texels (0xRRGGBB, sky * 16 + block), or null for full light
     * @param viewProjection column-major matrix for camera-relative positions
     * @param camera         world position subtracted from particle positions
     * @param right          camera right vector (world space)
     * @param up             camera up vector (world space)
     * @return particles drawn
     */
    public int draw(VulkanFrame frame, ParticleSystem system, VulkanSampledImage sheet, VulkanSampledImage explosion,
                    VulkanSampledImage blockTextures, int[] lightmap, float partialTick, float[] viewProjection,
                    double[] camera, float[] right, float[] up) {
        if (system.count() == 0) {
            return 0;
        }
        push.clear();
        for (int index = 0; index < 16; index++) {
            push.putFloat(index * 4, viewProjection[index]);
        }
        push.putFloat(64, right[0]);
        push.putFloat(68, right[1]);
        push.putFloat(72, right[2]);
        push.putFloat(76, 0f);
        push.putFloat(80, up[0]);
        push.putFloat(84, up[1]);
        push.putFloat(88, up[2]);
        push.putFloat(92, linearOutput ? 1f : 0f);
        push.limit(PUSH_BYTES);
        int drawn = drawFamily(frame, system, alpha, sheet, ParticleType.Sheet.PARTICLES, false, lightmap, partialTick, camera)
                + drawFamily(frame, system, additive, sheet, ParticleType.Sheet.PARTICLES, true, lightmap, partialTick, camera);
        if (explosion != null) {
            drawn += drawFamily(frame, system, alpha, explosion, ParticleType.Sheet.EXPLOSION, false, lightmap,
                    partialTick, camera);
        }
        if (blockTextures != null) {
            drawn += drawFamily(frame, system, blocks, blockTextures, ParticleType.Sheet.BLOCKS, false, lightmap,
                    partialTick, camera);
        }
        return drawn;
    }

    private int drawFamily(VulkanFrame frame, ParticleSystem system, VulkanPipeline pipeline,
                           VulkanSampledImage texture, ParticleType.Sheet sheet, boolean additiveFamily,
                           int[] lightmap, float partialTick, double[] camera) {
        int count = system.count(sheet, additiveFamily);
        if (count == 0) {
            return 0;
        }
        long offset = frame.allocate((long) count * ParticleSystem.INSTANCE_BYTES, 16);
        if (offset < 0) {
            return 0;
        }
        int written = system.writeInstances(frame.stream(), (int) offset, count, partialTick, camera[0], camera[1],
                camera[2], sheet, additiveFamily, lightmap);
        if (written == 0) {
            return 0;
        }
        frame.bindPipeline(pipeline);
        frame.bindImage(texture);
        frame.pushConstants(push);
        frame.bindStreamVertices(0, offset);
        frame.draw(4, written, 0, 0);
        return written;
    }

    @Override
    public void close() {
        blocks.close();
        additive.close();
        alpha.close();
        MemoryUtil.memFree(push);
    }
}
