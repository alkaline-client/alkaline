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
 * text and backgrounds vanilla draws as immediate-mode quads, the world border wall and the dragon's crystal beam.
 * Positions are camera-relative world coordinates. Texels with alpha at or below 0.1 are discarded (vanilla's alpha test).
 *
 * <p>Usage inside a pass with the scene viewport set: {@link #begin}, then per draw {@link #start}, any number of
 * {@link #quad}, and {@link #flush}.
 */
public final class QuadRenderer implements AutoCloseable {
    /** How a draw blends and depth-tests. */
    public enum Mode {
        /** Straight alpha, depth-tested, no depth writes. */
        ALPHA,
        /**
         * {@link #ALPHA} blended on sRGB-encoded values, as in vanilla's framebuffer (entity shadows); only
         * between {@link VulkanFrame#beginGammaSpace} and {@link VulkanFrame#endGammaSpace}.
         */
        GAMMA_ALPHA,
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
    /** Vertices since {@link #start}, written by address: name tags write tens of thousands of floats a frame. */
    private long vertices = MemoryUtil.nmemAllocChecked(64 * QUAD_VERTICES * VERTEX_BYTES);
    private long capacity = 64 * QUAD_VERTICES * VERTEX_BYTES;
    private long written;
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
        if (mode == Mode.GAMMA_ALPHA) {
            builder.gammaSpace();
        }
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
        // A gamma-space draw writes encoded colors straight to the UNORM alias.
        push.putFloat(64, linearOutput && drawMode != Mode.GAMMA_ALPHA ? 1f : 0f);
        push.putFloat(68, cutoff);
    }

    /** One quad from four (x, y, z, u, v) corners, in order around the quad. */
    public void quad(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1,
                     float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3,
                     float u3, float v3, int argb) {
        quad(x0, y0, z0, u0, v0, argb, x1, y1, z1, u1, v1, argb, x2, y2, z2, u2, v2, argb, x3, y3, z3, u3, v3,
                argb);
    }

    /** One quad with a color per corner, blended across it (vanilla's smooth shading: the crystal beam). */
    public void quad(float x0, float y0, float z0, float u0, float v0, int argb0, float x1, float y1, float z1,
                     float u1, float v1, int argb1, float x2, float y2, float z2, float u2, float v2, int argb2,
                     float x3, float y3, float z3, float u3, float v3, int argb3) {
        if (capacity - written < QUAD_VERTICES * VERTEX_BYTES) {
            capacity *= 2;
            vertices = MemoryUtil.nmemReallocChecked(vertices, capacity);
        }
        int rgba0 = rgba(argb0);
        int rgba2 = rgba(argb2);
        long at = vertices + written;
        put(at, x0, y0, z0, u0, v0, rgba0);
        put(at + VERTEX_BYTES, x1, y1, z1, u1, v1, rgba(argb1));
        put(at + 2 * VERTEX_BYTES, x2, y2, z2, u2, v2, rgba2);
        put(at + 3 * VERTEX_BYTES, x0, y0, z0, u0, v0, rgba0);
        put(at + 4 * VERTEX_BYTES, x2, y2, z2, u2, v2, rgba2);
        put(at + 5 * VERTEX_BYTES, x3, y3, z3, u3, v3, rgba(argb3));
        written += QUAD_VERTICES * VERTEX_BYTES;
        count += QUAD_VERTICES;
    }

    private static final boolean BIG_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;

    /** UNORM8X4 reads red, green, blue, alpha in memory order. */
    private static int rgba(int argb) {
        int rgba = (argb >> 16 & 0xFF) | (argb & 0xFF00) | (argb & 0xFF) << 16 | (argb & 0xFF000000);
        return BIG_ENDIAN ? Integer.reverseBytes(rgba) : rgba;
    }

    private static void put(long at, float x, float y, float z, float u, float v, int rgba) {
        MemoryUtil.memPutFloat(at, x);
        MemoryUtil.memPutFloat(at + 4, y);
        MemoryUtil.memPutFloat(at + 8, z);
        MemoryUtil.memPutFloat(at + 12, u);
        MemoryUtil.memPutFloat(at + 16, v);
        MemoryUtil.memPutInt(at + 20, rgba);
    }

    /** Draws the quads written since {@link #start}. */
    public void flush() {
        if (count > 0 && frame != null) {
            long bytes = (long) count * VERTEX_BYTES;
            long offset = frame.allocate(bytes, 16);
            if (offset >= 0) {
                MemoryUtil.memCopy(vertices, frame.streamPointer(offset), bytes);
                frame.bindPipeline(pipelines[mode.ordinal()]);
                frame.bindImage(image);
                frame.pushConstants(push);
                frame.bindStreamVertices(0, offset);
                frame.draw(count, 1, 0, 0);
            }
        }
        written = 0;
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
        MemoryUtil.nmemFree(vertices);
        vertices = 0L;
    }
}
