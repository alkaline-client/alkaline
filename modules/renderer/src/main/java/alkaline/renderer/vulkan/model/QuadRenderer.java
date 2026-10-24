package alkaline.renderer.vulkan.model;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanSampledImage;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * Textured quads written straight into the frame's stream memory, one draw per {@link #flush}: name tags, whose
 * text and backgrounds vanilla draws as immediate-mode quads, and the world border wall. Positions are
 * camera-relative world coordinates. Texels with alpha at or below 0.1 are discarded (vanilla's alpha test).
 *
 * <p>Usage inside a pass with the scene viewport set: {@link #begin}, then per draw {@link #start}, any number of
 * {@link #quad}, and {@link #flush}.
 */
public final class QuadRenderer implements AutoCloseable {
    /** How a draw blends and depth-tests. */
    public enum Mode {
        /** Straight alpha, depth-tested, no depth writes. */
        ALPHA,
        /** Straight alpha over everything (vanilla's disabled depth test). */
        OVERLAY,
        /** Source alpha added to the scene, depth-tested (the world border). */
        ADDITIVE
    }

    private static final int PUSH_BYTES = 80;
    private static final int VERTEX_BYTES = 24;
    private static final int QUAD_VERTICES = 6;

    private final VulkanPipeline[] pipelines = new VulkanPipeline[Mode.values().length];
    private final boolean linearOutput;
    private final ByteBuffer push = MemoryUtil.memCalloc(PUSH_BYTES);
    private ByteBuffer vertices = MemoryUtil.memAlloc(64 * QUAD_VERTICES * VERTEX_BYTES);
    private VulkanFrame frame;
    private Mode mode;
    private VulkanSampledImage image;
    private int count;

    public QuadRenderer(VulkanRenderer renderer, RenderTargetFormat target) {
        Objects.requireNonNull(renderer, "renderer");
        linearOutput = target == null ? renderer.presentsSrgb() : target != RenderTargetFormat.RGBA8_UNORM;
        try {
            for (Mode value : Mode.values()) {
                pipelines[value.ordinal()] = renderer.createPipeline(spec(value, target));
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static PipelineSpec spec(Mode mode, RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("label.vert", "label.frag")
                .binding(0, VERTEX_BYTES, false)
                .attribute(0, 0, VertexFormat.FLOAT3, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 12)
                .attribute(2, 0, VertexFormat.UNORM8X4, 20)
                .blend(mode == Mode.ADDITIVE ? PipelineSpec.Blend.ADDITIVE : PipelineSpec.Blend.ALPHA)
                .depth(mode != Mode.OVERLAY, false)
                .sampledImage(true)
                .pushConstants(PUSH_BYTES);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder.build();
    }

    /** Starts drawing into {@code target}'s current pass with a camera-relative view-projection matrix. */
    public void begin(VulkanFrame target, float[] viewProjection) {
        frame = Objects.requireNonNull(target, "frame");
        push.clear();
        for (int index = 0; index < 16; index++) {
            push.putFloat(index * 4, viewProjection[index]);
        }
        push.putFloat(64, linearOutput ? 1f : 0f);
        push.putFloat(68, 0.1f);
        push.limit(PUSH_BYTES);
        count = 0;
    }

    /** Begins a draw of quads sampling {@code texture} with vanilla's alpha test; flushes any draw in progress. */
    public void start(Mode drawMode, VulkanSampledImage texture) {
        start(drawMode, texture, 0.1f);
    }

    /** As {@link #start(Mode, VulkanSampledImage)}, discarding texels with alpha at or below {@code cutoff}. */
    public void start(Mode drawMode, VulkanSampledImage texture, float cutoff) {
        flush();
        mode = Objects.requireNonNull(drawMode, "mode");
        image = Objects.requireNonNull(texture, "texture");
        push.putFloat(68, cutoff);
    }

    /** One quad from four (x, y, z, u, v) corners, in order around the quad. */
    public void quad(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1,
                     float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3,
                     float u3, float v3, int argb) {
        if (vertices.remaining() < QUAD_VERTICES * VERTEX_BYTES) {
            ByteBuffer grown = MemoryUtil.memAlloc(vertices.capacity() * 2);
            vertices.flip();
            grown.put(vertices);
            MemoryUtil.memFree(vertices);
            vertices = grown;
        }
        put(x0, y0, z0, u0, v0, argb);
        put(x1, y1, z1, u1, v1, argb);
        put(x2, y2, z2, u2, v2, argb);
        put(x0, y0, z0, u0, v0, argb);
        put(x2, y2, z2, u2, v2, argb);
        put(x3, y3, z3, u3, v3, argb);
        count += QUAD_VERTICES;
    }

    private void put(float x, float y, float z, float u, float v, int argb) {
        vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        vertices.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }

    /** Draws the quads written since {@link #start}. */
    public void flush() {
        if (count > 0 && frame != null) {
            long bytes = (long) count * VERTEX_BYTES;
            long offset = frame.allocate(bytes, 16);
            if (offset >= 0) {
                ByteBuffer stream = frame.stream();
                stream.put((int) offset, vertices, 0, (int) bytes);
                frame.bindPipeline(pipelines[mode.ordinal()]);
                frame.bindImage(image);
                frame.pushConstants(push);
                frame.bindStreamVertices(0, offset);
                frame.draw(count, 1, 0, 0);
            }
        }
        vertices.clear();
        count = 0;
    }

    @Override
    public void close() {
        for (VulkanPipeline pipeline : pipelines) {
            if (pipeline != null) {
                pipeline.close();
            }
        }
        MemoryUtil.memFree(push);
        MemoryUtil.memFree(vertices);
    }
}
