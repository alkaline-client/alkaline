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
    private static final int GLINT_PUSH_BYTES = 96;

    private final VulkanPipeline pipeline;
    /** Glint on the color attachment's UNORM alias (vanilla's blend), and the linear fallback without one. */
    private final VulkanPipeline glintPipeline;
    private final VulkanPipeline glintLinearPipeline;
    private final boolean[] instanceGlint = new boolean[MAX_INSTANCES];
    private VulkanSampledImage glintTexture;
    private float glintScrollA;
    private float glintScrollB;
    /** Vanilla's item sprites are 16 px in its 512x512 block atlas (logged as such by unmodded 1.8.9). */
    private static final float GLINT_SPRITE_SPAN = 16f / 512f;
    private static final float[] GLINT_ANGLES = {(float) Math.toRadians(-50), (float) Math.toRadians(10)};
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
        // Glint redraws the same geometry with the same transform: depth matches, so test without writing.
        glintPipeline = renderer.createPipeline(modelSpec("model_glint.vert", "model_glint.frag", target)
                .depth(true, false)
                .blend(PipelineSpec.Blend.SOURCE_SQUARED)
                .pushConstants(GLINT_PUSH_BYTES)
                .gammaSpace()
                .build());
        glintLinearPipeline = renderer.createPipeline(modelSpec("model_glint.vert", "model_glint.frag", target)
                .depth(true, false)
                .blend(PipelineSpec.Blend.SOURCE_SQUARED)
                .pushConstants(GLINT_PUSH_BYTES)
                .build());
        push = MemoryUtil.memCalloc(PUSH_BYTES);
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
        glintScrollA = (timeMillis % 3000L) / 3000.0F / 8.0F;
        glintScrollB = (timeMillis % 4873L) / 4873.0F / 8.0F;
    }

    /** Uploads a cuboid model's static geometry for posed instances; call outside a frame. */
    public GpuModel upload(CuboidModel model) {
        return upload(ModelMesher.bake(Objects.requireNonNull(model, "model")), model);
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
        return queue(model, texture, model.partCount, tintArgb, overlayArgb, lightRgb, false);
    }

    /** A posed entity with the enchantment glint over it (enchanted armor). */
    public boolean submit(GpuModel model, VulkanSampledImage texture, ModelPose pose, float[] root,
                          int tintArgb, int overlayArgb, int lightRgb, boolean glint) {
        if (frame == null) {
            throw new IllegalStateException("Call begin() before submitting entities.");
        }
        if (model.owner != this || model.model == null || pose.model() != model.model) {
            throw new IllegalArgumentException("Pose and model must match a cuboid model uploaded by this renderer.");
        }
        PoseMath.resolve(pose, root, matrices);
        return queue(model, texture, model.partCount, tintArgb, overlayArgb, lightRgb, glint);
    }

    /**
     * Queues one rigid instance (an item): {@code root} is the column-major model-to-world (or model-to-eye)
     * matrix. Colors as for posed models.
     *
     * @return false when this frame's instance or stream budget is exhausted
     */
    public boolean submit(GpuModel model, VulkanSampledImage texture, float[] root, int tintArgb, int overlayArgb,
                          int lightRgb) {
        return submit(model, texture, root, tintArgb, overlayArgb, lightRgb, false);
    }

    /** A rigid instance, with vanilla's enchantment glint over it when {@code glint} is set. */
    public boolean submit(GpuModel model, VulkanSampledImage texture, float[] root, int tintArgb, int overlayArgb,
                          int lightRgb, boolean glint) {
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
        return queue(model, texture, 1, tintArgb, overlayArgb, lightRgb, glint);
    }

    private boolean queue(GpuModel model, VulkanSampledImage texture, int parts, int tintArgb, int overlayArgb,
                          int lightRgb, boolean glint) {
        long offset = instanceCount < MAX_INSTANCES
                ? frame.allocate((long) parts * PoseMath.FLOATS_PER_PART * Float.BYTES, MATRIX_ROW_BYTES) : -1L;
        if (offset < 0) {
            droppedInstances++;
            return false;
        }
        ByteBuffer stream = frame.stream();
        int position = (int) offset;
        for (int index = 0; index < parts * PoseMath.FLOATS_PER_PART; index++) {
            stream.putFloat(position + index * Float.BYTES, matrices[index]);
        }
        int textureSlot = textureSlot(texture);
        int instance = instanceCount++;
        instanceModel[instance] = model.id;
        instanceMatrixRow[instance] = (int) (offset / MATRIX_ROW_BYTES);
        instanceTint[instance] = abgr(tintArgb);
        instanceOverlay[instance] = abgr(overlayArgb);
        instanceLight[instance] = abgr(0xFF000000 | lightRgb);
        instanceGlint[instance] = glint;
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
        ByteBuffer stream = target.stream();
        for (int sorted = 0; sorted < instanceCount; sorted++) {
            int instance = (int) (sortKeys[sorted] & 0xFFFF_FFFFL);
            int position = (int) instancesOffset + sorted * INSTANCE_BYTES;
            stream.putInt(position, instanceMatrixRow[instance]);
            stream.putInt(position + 4, instanceTint[instance]);
            stream.putInt(position + 8, instanceOverlay[instance]);
            stream.putInt(position + 12, instanceLight[instance]);
        }

        target.bindPipeline(pipeline);
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

        int runStart = 0;
        while (runStart < instanceCount) {
            long groupKey = sortKeys[runStart] >>> 32;
            int runEnd = runStart + 1;
            while (runEnd < instanceCount && (sortKeys[runEnd] >>> 32) == groupKey) {
                runEnd++;
            }
            GpuModel model = models.get((int) (groupKey >>> 16));
            VulkanSampledImage texture = frameTextures.get((int) (groupKey & 0xFFFF));
            push.putLong(64, target.streamAddress(instancesOffset + (long) runStart * INSTANCE_BYTES));
            target.bindImage(texture);
            target.pushConstants(push);
            target.bindVertices(0, model.buffer, 0);
            target.bindIndices(model.buffer, model.vertexBytes);
            target.drawIndexed(model.indexCount, runEnd - runStart, 0, 0, 0);
            runStart = runEnd;
        }
        flushGlint(target);
    }

    /** Redraws glinted instances, in the same sorted order, with the glint pipeline. */
    private void flushGlint(VulkanFrame target) {
        if (glintTexture == null) {
            return;
        }
        int glinted = 0;
        for (int sorted = 0; sorted < instanceCount; sorted++) {
            if (instanceGlint[(int) (sortKeys[sorted] & 0xFFFF_FFFFL)]) {
                glinted++;
            }
        }
        if (glinted == 0) {
            return;
        }
        long offset = target.allocate((long) glinted * INSTANCE_BYTES, INSTANCE_BYTES);
        if (offset < 0) {
            return;
        }
        ByteBuffer stream = target.stream();
        int written = 0;
        for (int sorted = 0; sorted < instanceCount; sorted++) {
            int instance = (int) (sortKeys[sorted] & 0xFFFF_FFFFL);
            if (instanceGlint[instance]) {
                stream.putInt((int) offset + written * INSTANCE_BYTES, instanceMatrixRow[instance]);
                stream.putInt((int) offset + written * INSTANCE_BYTES + 12, instanceLight[instance]);
                // The spare half of sortKeys records each glint record's model, in draw order.
                sortKeys[instanceCount + written] = sortKeys[sorted] >>> 48 << 32 | written;
                written++;
            }
        }
        boolean gamma = target.beginGammaSpace();
        target.bindPipeline(gamma ? glintPipeline : glintLinearPipeline);
        target.bindImage(glintTexture);
        push.clear();
        push.limit(GLINT_PUSH_BYTES);
        // Vanilla draws the two layers as two passes over the item; so does this, layer by layer.
        for (int layer = 0; layer < 2; layer++) {
            push.putFloat(80, layer == 0 ? glintScrollA : -glintScrollB).putFloat(84, GLINT_ANGLES[layer])
                    .putFloat(88, GLINT_SPRITE_SPAN).putFloat(92, gamma ? 0f : 1f);
            int runStart = 0;
            while (runStart < glinted) {
                long model = sortKeys[instanceCount + runStart] >>> 32;
                int runEnd = runStart + 1;
                while (runEnd < glinted && sortKeys[instanceCount + runEnd] >>> 32 == model) {
                    runEnd++;
                }
                GpuModel gpu = models.get((int) model);
                push.putLong(64, target.streamAddress(offset + (long) runStart * INSTANCE_BYTES));
                target.pushConstants(push);
                target.bindVertices(0, gpu.buffer, 0);
                target.bindIndices(gpu.buffer, gpu.vertexBytes);
                target.drawIndexed(gpu.indexCount, runEnd - runStart, 0, 0, 0);
                runStart = runEnd;
            }
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
        glintPipeline.close();
        glintLinearPipeline.close();
        pipeline.close();
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
