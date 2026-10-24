package alkaline.renderer.vulkan.model;

import alkaline.renderer.model.BakedModel;
import alkaline.renderer.model.CuboidModel;
import alkaline.renderer.model.ModelMesher;
import alkaline.renderer.model.ModelPose;
import alkaline.renderer.model.PoseMath;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanSampledImage;
import alkaline.renderer.vulkan.resource.VulkanStaticBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU-instanced renderer for every model that is not terrain: posed cuboid models (entities, and later chests,
 * signs and skulls) and rigid baked models (held, dropped and framed items).
 *
 * <p>Vanilla 1.8.9 re-tessellates (or replays a display list for) every box of every entity, and every quad of
 * every item, each frame with matrix-stack calls in between. Here a model's geometry is uploaded once
 * ({@link BakedModel}); per frame, each submitted instance costs one pass over its part hierarchy on the CPU (12
 * floats per part written straight into mapped memory) and one 16-byte instance record. Instances sharing a
 * model and texture become a single instanced draw. Like vanilla's item rendering, texels with alpha at or
 * below 0.1 are discarded and the rest blend over the scene.
 *
 * <p>Usage per frame: {@link #begin}, any number of {@link #submit}, then {@link #flush} inside the main pass
 * with the viewport already set (normally flipped, y-up).
 */
public final class ModelRenderer implements AutoCloseable {
    private static final int PUSH_BYTES = 120;
    private static final int FOG_BYTES = 32;
    private static final int INSTANCE_BYTES = 16;
    private static final int MATRIX_ROW_BYTES = 16;
    private static final int MAX_INSTANCES = 16_384;

    private final VulkanRenderer renderer;
    private static final int GLINT_PUSH_BYTES = 128;

    private final VulkanPipeline pipeline;
    /** The same shaders with additive blending and no depth writes, for models uploaded with {@link #uploadAdditive}. */
    private VulkanPipeline additivePipeline;
    private final RenderTargetFormat target;
    /**
     * Glint pipelines by blend: [0] on the color attachment's UNORM alias (vanilla's blend), [1] the linear
     * fallback without one. Vanilla's are built up front, a pack's other blends by {@link #prepare}.
     */
    private final java.util.EnumMap<PipelineSpec.Blend, VulkanPipeline[]> glintPipelines =
            new java.util.EnumMap<>(PipelineSpec.Blend.class);
    private final GlintEffect[] instanceGlint = new GlintEffect[MAX_INSTANCES];
    private final float[] instanceGlintTime = new float[MAX_INSTANCES];
    /** This frame's distinct glint effects; a glinted record's sort key carries its index here. */
    private final List<GlintEffect> frameGlints = new ArrayList<>();
    /** Distinct glint effects one frame draws; the sort key has 7 bits for them. */
    private static final int MAX_FRAME_GLINTS = 127;
    private VulkanSampledImage glintTexture;
    private long glintTime;
    private final ByteBuffer push;
    private final float[] matrices = new float[CuboidModel.MAX_PARTS * PoseMath.FLOATS_PER_PART];
    private final List<GpuModel> models = new ArrayList<>();
    private final List<VulkanSampledImage> frameTextures = new ArrayList<>();
    /** Sort keys of this frame's instances, then (for the glint pass) of its glinted instances. */
    private final long[] sortKeys = new long[MAX_INSTANCES * 2];
    private final int[] instanceModel = new int[MAX_INSTANCES];
    private final int[] instanceMatrixRow = new int[MAX_INSTANCES];
    private final int[] instanceTint = new int[MAX_INSTANCES];
    private final int[] instanceOverlay = new int[MAX_INSTANCES];
    private final int[] instanceLight = new int[MAX_INSTANCES];
    private final float[] light0 = {0.2f, 1.0f, -0.7f};
    private final float[] light1 = {-0.2f, 1.0f, 0.7f};
    private float ambient = 0.4f;
    private final float[] viewProjection = new float[16];
    private VulkanFrame frame;
    private int instanceCount;
    private int droppedInstances;
    /** Whether the models submitted now ignore the two lights (flat color, like fixed-function with lighting off). */
    private boolean unlit;

    public ModelRenderer(VulkanRenderer renderer) {
        this(renderer, null);
    }

    /**
     * @param target offscreen color format the models render into (with depth), or null for the main pass
     */
    public ModelRenderer(VulkanRenderer renderer, RenderTargetFormat target) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        linearOutput = target == null ? renderer.presentsSrgb() : target != RenderTargetFormat.RGBA8_UNORM;
        pipeline = renderer.createPipeline(modelSpec("model.vert", "model.frag", target)
                .depth(true, true)
                .blend(PipelineSpec.Blend.ALPHA)
                .pushConstants(PUSH_BYTES)
                .build());
        this.target = target;
        prepare(GlintEffect.ITEM);
        push = MemoryUtil.memCalloc(Math.max(PUSH_BYTES, GLINT_PUSH_BYTES));
        normalize(light0);
        normalize(light1);
    }

    private final boolean linearOutput;
    /** Linear fog color, start, end, enabled, exponential density (0 for linear fog). */
    private final float[] fog = new float[7];

    /**
     * Fog for models in the world, matching the terrain's: radial, linear from start to end, or exponential with
     * a density in water and lava. Values from {@link alkaline.renderer.vulkan.world.WorldRenderer#fog}; off by
     * default (held items and menus).
     */
    public void setFog(float[] values) {
        System.arraycopy(values, 0, fog, 0, fog.length);
    }

    public void clearFog() {
        java.util.Arrays.fill(fog, 0f);
    }

    private static PipelineSpec.Builder modelSpec(String vertex, String fragment, RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder(vertex, fragment);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder
                .binding(0, ModelMesher.VERTEX_BYTES, false)
                .attribute(0, 0, VertexFormat.FLOAT3, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 12)
                .attribute(2, 0, VertexFormat.SNORM8X4, 20)
                .attribute(3, 0, VertexFormat.UINT1, 24)
                .sampledImage(true);
    }

    /**
     * The enchantment glint texture ({@code textures/misc/enchanted_item_glint.png}, repeat addressing) and the
     * clock that scrolls it, in milliseconds (vanilla uses the system time). Without a texture, glint is skipped.
     */
    public void setGlint(VulkanSampledImage texture, long timeMillis) {
        glintTexture = texture;
        glintTime = timeMillis;
    }

    /** Builds the pipelines an effect's blends need; call outside a frame before submitting it. */
    public void prepare(GlintEffect effect) {
        for (GlintEffect.Layer layer : effect.layers) {
            if (!glintPipelines.containsKey(layer.blend())) {
                // Glint redraws the same geometry with the same transform: depth matches, so test without writing.
                VulkanPipeline gamma = renderer.createPipeline(glintSpec(layer.blend()).gammaSpace().build());
                VulkanPipeline linear;
                try {
                    linear = renderer.createPipeline(glintSpec(layer.blend()).build());
                } catch (RuntimeException | Error failure) {
                    gamma.close();
                    throw failure;
                }
                glintPipelines.put(layer.blend(), new VulkanPipeline[]{gamma, linear});
            }
        }
    }

    private PipelineSpec.Builder glintSpec(PipelineSpec.Blend blend) {
        return modelSpec("model_glint.vert", "model_glint.frag", target)
                .depth(true, false)
                .blend(blend)
                .sampledImages(2)
                .pushConstants(GLINT_PUSH_BYTES);
    }

    /** Uploads a cuboid model's static geometry for posed instances; call outside a frame. */
    public GpuModel upload(CuboidModel model) {
        return upload(ModelMesher.bake(Objects.requireNonNull(model, "model")), model);
    }

    /**
     * Draws the rigid instances submitted after this call at their flat color, without the two directional
     * lights; {@link #begin} turns it off again.
     */
    public void setUnlit(boolean value) {
        unlit = value;
    }

    /**
     * Uploads rigid geometry drawn additively (source * alpha + destination, no depth writes) after everything else,
     * as vanilla draws lightning with {@code blendFunc(SRC_ALPHA, ONE)}; call outside a frame.
     */
    public GpuModel uploadAdditive(BakedModel model) {
        if (model.partCount() != 1) {
            throw new IllegalArgumentException("Additive geometry is a single rigid part.");
        }
        if (additivePipeline == null) {
            additivePipeline = renderer.createPipeline(modelSpec("model.vert", "model.frag", target)
                    .depth(true, false)
                    .blend(PipelineSpec.Blend.ADDITIVE)
                    .pushConstants(PUSH_BYTES)
                    .build());
        }
        GpuModel gpu = upload(model, null);
        gpu.additive = true;
        return gpu;
    }

    /** Uploads baked geometry for rigid (single-transform) instances, such as items; call outside a frame. */
    public GpuModel upload(BakedModel model) {
        if (model.partCount() != 1) {
            throw new IllegalArgumentException("Multi-part geometry needs its cuboid model for posing.");
        }
        return upload(model, null);
    }

    private GpuModel upload(BakedModel baked, CuboidModel cuboid) {
        VulkanStaticBuffer buffer = renderer.createStaticBuffer(baked.data());
        GpuModel gpu = new GpuModel(this, models.size(), cuboid, baked.partCount(), buffer, baked.vertexBytes(),
                baked.indexCount());
        models.add(gpu);
        return gpu;
    }

    /**
     * Overrides the two directional lights (world space, pointing toward the light) and ambient level. Defaults
     * match Minecraft's standard item lighting.
     */
    public void setLighting(float x0, float y0, float z0, float x1, float y1, float z1, float ambientLevel) {
        light0[0] = x0;
        light0[1] = y0;
        light0[2] = z0;
        light1[0] = x1;
        light1[1] = y1;
        light1[2] = z1;
        normalize(light0);
        normalize(light1);
        ambient = ambientLevel;
    }

    public void begin(VulkanFrame target, float[] viewProjectionMatrix) {
        frame = Objects.requireNonNull(target, "target");
        System.arraycopy(viewProjectionMatrix, 0, viewProjection, 0, 16);
        instanceCount = 0;
        frameTextures.clear();
        unlit = false;
    }

    /**
     * Queues one entity. {@code root} is the column-major model-to-world matrix; colors are ARGB. The overlay's
     * alpha blends its color over the texture (vanilla's hurt flash is 0x4CFF0000); light is a 0xRRGGBB
     * brightness multiplier, 0xFFFFFF for full bright.
     *
     * @return false when this frame's instance or stream budget is exhausted
     */
    public boolean submit(GpuModel model, VulkanSampledImage texture, ModelPose pose, float[] root,
                          int tintArgb, int overlayArgb, int lightRgb) {
        if (frame == null) {
            throw new IllegalStateException("Call begin() before submitting entities.");
        }
        if (model.owner != this || model.model == null || pose.model() != model.model) {
            throw new IllegalArgumentException("Pose and model must match a cuboid model uploaded by this renderer.");
        }
        PoseMath.resolve(pose, root, matrices);
        return queue(model, texture, model.partCount, tintArgb, overlayArgb, lightRgb, null, 0f);
    }

    /**
     * A posed entity with a glint over it (enchanted armor), or none when {@code glint} is null. {@code glintTime}
     * drives the effect's per-instance scroll: vanilla armor uses the wearer's ticksExisted plus the partial tick.
     */
    public boolean submit(GpuModel model, VulkanSampledImage texture, ModelPose pose, float[] root,
                          int tintArgb, int overlayArgb, int lightRgb, GlintEffect glint, float glintTime) {
        if (frame == null) {
            throw new IllegalStateException("Call begin() before submitting entities.");
        }
        if (model.owner != this || model.model == null || pose.model() != model.model) {
            throw new IllegalArgumentException("Pose and model must match a cuboid model uploaded by this renderer.");
        }
        PoseMath.resolve(pose, root, matrices);
        return queue(model, texture, model.partCount, tintArgb, overlayArgb, lightRgb, glint, glintTime);
    }

    /**
     * Queues one rigid instance (an item): {@code root} is the column-major model-to-world (or model-to-eye)
     * matrix. Colors as for posed models.
     *
     * @return false when this frame's instance or stream budget is exhausted
     */
    public boolean submit(GpuModel model, VulkanSampledImage texture, float[] root, int tintArgb, int overlayArgb,
                          int lightRgb) {
        return submit(model, texture, root, tintArgb, overlayArgb, lightRgb, null);
    }

    /** A rigid instance, with vanilla's enchantment glint over it when {@code glint} is set. */
    public boolean submit(GpuModel model, VulkanSampledImage texture, float[] root, int tintArgb, int overlayArgb,
                          int lightRgb, boolean glint) {
        return submit(model, texture, root, tintArgb, overlayArgb, lightRgb, glint ? GlintEffect.ITEM : null);
    }

    /** A rigid instance with a glint over it, or none when {@code glint} is null. */
    public boolean submit(GpuModel model, VulkanSampledImage texture, float[] root, int tintArgb, int overlayArgb,
                          int lightRgb, GlintEffect glint) {
        if (frame == null) {
            throw new IllegalStateException("Call begin() before submitting models.");
        }
        if (model.owner != this || model.partCount != 1 || root.length != 16) {
            throw new IllegalArgumentException("Rigid instances need a single-part model and a 4x4 matrix.");
        }
        matrices[0] = root[0];
        matrices[1] = root[4];
        matrices[2] = root[8];
        matrices[3] = root[12];
        matrices[4] = root[1];
        matrices[5] = root[5];
        matrices[6] = root[9];
        matrices[7] = root[13];
        matrices[8] = root[2];
        matrices[9] = root[6];
        matrices[10] = root[10];
        matrices[11] = root[14];
        return queue(model, texture, 1, tintArgb, overlayArgb, lightRgb, glint, 0f);
    }

    private boolean queue(GpuModel model, VulkanSampledImage texture, int parts, int tintArgb, int overlayArgb,
                          int lightRgb, GlintEffect glint, float glintTime) {
        long offset = instanceCount < MAX_INSTANCES
                ? frame.allocate((long) parts * PoseMath.FLOATS_PER_PART * Float.BYTES, MATRIX_ROW_BYTES) : -1L;
        if (offset < 0) {
            droppedInstances++;
            return false;
        }
        long pointer = frame.streamPointer(offset);
        for (int index = 0; index < parts * PoseMath.FLOATS_PER_PART; index++) {
            MemoryUtil.memPutFloat(pointer + (long) index * Float.BYTES, matrices[index]);
        }
        int textureSlot = textureSlot(texture);
        int instance = instanceCount++;
        instanceModel[instance] = model.id;
        instanceMatrixRow[instance] = (int) (offset / MATRIX_ROW_BYTES);
        instanceTint[instance] = abgr(tintArgb);
        instanceOverlay[instance] = abgr(overlayArgb);
        // The light word's alpha byte tells the shader to skip the directional lights (zero) or not (opaque).
        instanceLight[instance] = abgr((unlit ? 0 : 0xFF000000) | lightRgb);
        instanceGlint[instance] = glint;
        instanceGlintTime[instance] = glintTime;
        sortKeys[instance] = ((long) model.id << 48) | ((long) textureSlot << 32) | instance;
        return true;
    }

    /** Records instanced draws for everything submitted since {@link #begin}. */
    public void flush() {
        if (frame == null) {
            throw new IllegalStateException("Call begin() before flush().");
        }
        VulkanFrame target = frame;
        frame = null;
        if (instanceCount == 0) {
            return;
        }
        long instancesOffset = target.allocate((long) instanceCount * INSTANCE_BYTES, INSTANCE_BYTES);
        if (instancesOffset < 0) {
            droppedInstances += instanceCount;
            return;
        }
        Arrays.sort(sortKeys, 0, instanceCount);
        long instances = target.streamPointer(instancesOffset);
        for (int sorted = 0; sorted < instanceCount; sorted++) {
            int instance = (int) (sortKeys[sorted] & 0xFFFF_FFFFL);
            long position = instances + (long) sorted * INSTANCE_BYTES;
            MemoryUtil.memPutInt(position, instanceMatrixRow[instance]);
            MemoryUtil.memPutInt(position + 4, instanceTint[instance]);
            MemoryUtil.memPutInt(position + 8, instanceOverlay[instance]);
            MemoryUtil.memPutInt(position + 12, instanceLight[instance]);
        }

        push.clear();
        for (int index = 0; index < 16; index++) {
            push.putFloat(index * 4, viewProjection[index]);
        }
        push.putLong(72, target.streamAddress(0));
        push.putFloat(80, light0[0]);
        push.putFloat(84, light0[1]);
        push.putFloat(88, light0[2]);
        push.putFloat(92, linearOutput ? 1f : 0f);
        push.putFloat(96, light1[0]);
        push.putFloat(100, light1[1]);
        push.putFloat(104, light1[2]);
        push.putFloat(108, ambient);
        long fogOffset = target.allocate(FOG_BYTES, 16);
        if (fogOffset < 0) {
            droppedInstances += instanceCount;
            return;
        }
        ByteBuffer fogBlock = target.stream();
        for (int index = 0; index < fog.length; index++) {
            fogBlock.putFloat((int) fogOffset + index * Float.BYTES, fog[index]);
        }
        fogBlock.putFloat((int) fogOffset + 28, 0f);
        push.putLong(112, target.streamAddress(fogOffset));
        push.limit(PUSH_BYTES);

        // Opaque and alpha-tested models first; additive ones last, over the finished scene.
        for (int pass = 0; pass < 2; pass++) {
            boolean additive = pass == 1;
            boolean bound = false;
            int runStart = 0;
            while (runStart < instanceCount) {
                long groupKey = sortKeys[runStart] >>> 32;
                int runEnd = runStart + 1;
                while (runEnd < instanceCount && (sortKeys[runEnd] >>> 32) == groupKey) {
                    runEnd++;
                }
                GpuModel model = models.get((int) (groupKey >>> 16));
                if (model.additive == additive) {
                    if (!bound) {
                        target.bindPipeline(additive ? additivePipeline : pipeline);
                        bound = true;
                    }
                    VulkanSampledImage texture = frameTextures.get((int) (groupKey & 0xFFFF));
                    push.putLong(64, target.streamAddress(instancesOffset + (long) runStart * INSTANCE_BYTES));
                    target.bindImage(texture);
                    target.pushConstants(push);
                    target.bindVertices(0, model.buffer, 0);
                    target.bindIndices(model.buffer, model.vertexBytes);
                    target.drawIndexed(model.indexCount, runEnd - runStart, 0, 0, 0);
                }
                runStart = runEnd;
            }
        }
        flushGlint(target);
    }

    /**
     * Redraws glinted instances with each layer of their effect: grouped by effect, then model, so every layer of
     * every effect is a few instanced draws.
     */
    private void flushGlint(VulkanFrame target) {
        if (glintTexture == null) {
            return;
        }
        frameGlints.clear();
        int glinted = 0;
        for (int sorted = 0; sorted < instanceCount; sorted++) {
            int instance = (int) (sortKeys[sorted] & 0xFFFF_FFFFL);
            GlintEffect effect = instanceGlint[instance];
            if (effect == null) {
                continue;
            }
            int slot = frameGlints.indexOf(effect);
            if (slot < 0) {
                if (frameGlints.size() == MAX_FRAME_GLINTS) {
                    continue;
                }
                slot = frameGlints.size();
                frameGlints.add(effect);
            }
            // The spare half of sortKeys holds each glinted instance as effect, model, texture, and its index.
            sortKeys[instanceCount + glinted] = (long) slot << 56 | (sortKeys[sorted] >>> 32) << 24 | instance;
            glinted++;
        }
        if (glinted == 0) {
            return;
        }
        long offset = target.allocate((long) glinted * INSTANCE_BYTES, INSTANCE_BYTES);
        if (offset < 0) {
            return;
        }
        Arrays.sort(sortKeys, instanceCount, instanceCount + glinted);
        ByteBuffer stream = target.stream();
        for (int index = 0; index < glinted; index++) {
            int instance = (int) (sortKeys[instanceCount + index] & 0xFF_FFFFL);
            int position = (int) offset + index * INSTANCE_BYTES;
            stream.putInt(position, instanceMatrixRow[instance]);
            stream.putFloat(position + 4, instanceGlintTime[instance]);
            stream.putInt(position + 8, instanceTint[instance]);
            stream.putInt(position + 12, instanceLight[instance]);
        }
        boolean gamma = target.beginGammaSpace();
        push.clear();
        push.limit(GLINT_PUSH_BYTES);
        int effectStart = 0;
        while (effectStart < glinted) {
            long slot = sortKeys[instanceCount + effectStart] >>> 56;
            int effectEnd = effectStart + 1;
            while (effectEnd < glinted && sortKeys[instanceCount + effectEnd] >>> 56 == slot) {
                effectEnd++;
            }
            GlintEffect effect = frameGlints.get((int) slot);
            // Vanilla draws the layers as separate passes over the item; so does this, layer by layer.
            for (GlintEffect.Layer layer : effect.layers) {
                VulkanPipeline[] pipelines = glintPipelines.get(layer.blend());
                if (pipelines == null) {
                    continue;
                }
                target.bindPipeline(pipelines[gamma ? 0 : 1]);
                target.bindImage(layer.texture() == null ? glintTexture : layer.texture());
                float scroll = layer.scroll(glintTime);
                int flags = (gamma ? 0 : 1) | (effect.modelUv ? 2 : 0)
                        | (layer.blend() == PipelineSpec.Blend.SOURCE_SQUARED ? 4 : 0);
                push.putFloat(80, layer.m00()).putFloat(84, layer.m10()).putFloat(88, layer.m01())
                        .putFloat(92, layer.m11())
                        .putFloat(96, layer.scrollX() * scroll).putFloat(100, layer.scrollY() * scroll)
                        .putFloat(104, layer.driftX()).putFloat(108, layer.driftY())
                        .putFloat(112, layer.tint()[0]).putFloat(116, layer.tint()[1])
                        .putFloat(120, layer.tint()[2]).putFloat(124, flags);
                int runStart = effectStart;
                while (runStart < effectEnd) {
                    long group = sortKeys[instanceCount + runStart] >>> 24 & 0xFFFF_FFFFL;
                    int runEnd = runStart + 1;
                    while (runEnd < effectEnd && (sortKeys[instanceCount + runEnd] >>> 24 & 0xFFFF_FFFFL) == group) {
                        runEnd++;
                    }
                    GpuModel gpu = models.get((int) (group >>> 16));
                    target.bindImage(1, frameTextures.get((int) (group & 0xFFFF)));
                    // The view-projection and matrix stream address stay as flush() pushed them.
                    push.putLong(64, target.streamAddress(offset + (long) runStart * INSTANCE_BYTES));
                    target.pushConstants(push);
                    target.bindVertices(0, gpu.buffer, 0);
                    target.bindIndices(gpu.buffer, gpu.vertexBytes);
                    target.drawIndexed(gpu.indexCount, runEnd - runStart, 0, 0, 0);
                    runStart = runEnd;
                }
            }
            effectStart = effectEnd;
        }
        if (gamma) {
            target.endGammaSpace();
        }
        push.limit(PUSH_BYTES);
    }

    /** Entities skipped because a frame's instance or stream budget ran out (diagnostic counter). */
    public int droppedInstances() {
        return droppedInstances;
    }

    /**
     * Frees one uploaded model outside a frame (its buffer goes once the GPU is done with it); its id is not reused.
     * Drawing it afterwards is an error.
     */
    public void release(GpuModel model) {
        if (model.owner != this || models.get(model.id) != model) {
            throw new IllegalArgumentException("Model was not uploaded by this renderer or is already released.");
        }
        models.set(model.id, null);
        model.buffer.close();
    }

    @Override
    public void close() {
        for (GpuModel model : models) {
            if (model != null) {
                model.buffer.close();
            }
        }
        models.clear();
        for (VulkanPipeline[] pipelines : glintPipelines.values()) {
            pipelines[0].close();
            pipelines[1].close();
        }
        pipeline.close();
        if (additivePipeline != null) {
            additivePipeline.close();
        }
        MemoryUtil.memFree(push);
    }

    private int textureSlot(VulkanSampledImage texture) {
        Objects.requireNonNull(texture, "texture");
        for (int index = 0; index < frameTextures.size(); index++) {
            if (frameTextures.get(index) == texture) {
                return index;
            }
        }
        if (frameTextures.size() == 0xFFFF) {
            throw new IllegalStateException("Too many distinct entity textures in one frame.");
        }
        frameTextures.add(texture);
        return frameTextures.size() - 1;
    }

    /** ARGB to the little-endian RGBA8 word that unpackUnorm4x8 reads as (r, g, b, a). */
    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        if (length > 0f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

    /** A model uploaded to one {@link ModelRenderer}. */
    public static final class GpuModel {
        private final ModelRenderer owner;
        private final int id;
        private final CuboidModel model;
        private final int partCount;
        private final VulkanStaticBuffer buffer;
        private final int vertexBytes;
        private final int indexCount;
        private boolean additive;

        private GpuModel(ModelRenderer owner, int id, CuboidModel model, int partCount, VulkanStaticBuffer buffer,
                         int vertexBytes, int indexCount) {
            this.owner = owner;
            this.id = id;
            this.model = model;
            this.partCount = partCount;
            this.buffer = buffer;
            this.vertexBytes = vertexBytes;
            this.indexCount = indexCount;
        }

        /** The cuboid model instances are posed with, or null for rigid geometry. */
        public CuboidModel model() {
            return model;
        }
    }
}
