package alkaline.renderer.vulkan.background;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

/**
 * "Fluid": Alkaline's own menu background, ported from the nice-elements fluid/Bayer effect. A small
 * stable-fluids simulation (semi-Lagrangian advection, Jacobi pressure solve, projection) carries dye streams
 * that enter from the left, and warps a turbulence field; the result is drawn as an ordered dither of the
 * theme's ink over its surface. Moving the pointer stirs the flow.
 *
 * <p>The original runs the solver in compute shaders over several float images. Here every field is packed
 * into one half-float RGBA texture (velocity, dye, pressure) and each solver stage is a full-screen fragment
 * pass that ping-pongs between two targets, which reuses the renderer's pass encoder without a compute path.
 * The solver steps at a fixed 60 Hz regardless of the frame rate, so its cost and behavior do not change with
 * an uncapped or a capped frame rate: about 20 small passes per step over at most 384 rows.
 */
public final class FluidBackground implements MenuBackground {
    public static final String ID = "fluid";
    private static final int STEP_BYTES = 48;
    private static final int COMPOSITE_BYTES = 56;
    private static final int STEPS_PER_SECOND = 60;
    private static final int MAX_STEPS_PER_FRAME = 3;
    private static final int PRESSURE_ITERATIONS = 18;
    private static final int MAX_SIMULATION_ROWS = 384;

    private static final int MODE_INIT = 0;
    private static final int MODE_ADVECT = 1;
    private static final int MODE_PRESSURE = 2;
    private static final int MODE_PROJECT = 3;

    /**
     * Colors as sRGB ARGB: the theme surface and the ink drawn over it, with the ink's strength (0..1), and the
     * dither cell size in pixels.
     */
    public record Look(int surface, int ink, float inkStrength, int cellSize) {
    }

    private final VulkanRenderer renderer;
    private final Supplier<Look> look;
    private final VulkanPipeline step;
    private final VulkanPipeline composite;
    private final ByteBuffer push = MemoryUtil.memCalloc(PipelineSpec.MAX_PUSH_CONSTANT_BYTES);
    private final VulkanRenderTarget[] fields = new VulkanRenderTarget[2];
    private int current;
    private boolean seeded;
    private long ticks;
    private long stepsDone = -1;
    private float aspect = 1f;

    // Pointer, written by the render thread from input events; normalized to the window, y down.
    private float pointerX = -1f;
    private float pointerY = -1f;
    private float pendingDeltaX;
    private float pendingDeltaY;

    public FluidBackground(VulkanRenderer renderer, Supplier<Look> look) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.look = Objects.requireNonNull(look, "look");
        VulkanPipeline createdStep = null;
        try {
            createdStep = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "fluid_step.frag")
                    .offscreen(RenderTargetFormat.RGBA16_FLOAT).sampledImage(true).pushConstants(STEP_BYTES).build());
            composite = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "fluid.frag")
                    .offscreen(SCENE_FORMAT).sampledImage(true).pushConstants(COMPOSITE_BYTES).build());
        } catch (RuntimeException | Error failure) {
            if (createdStep != null) {
                createdStep.close();
            }
            MemoryUtil.memFree(push);
            throw failure;
        }
        step = createdStep;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public void pointer(float x, float y) {
        if (pointerX >= 0f) {
            pendingDeltaX += x - pointerX;
            pendingDeltaY += y - pointerY;
        }
        pointerX = x;
        pointerY = y;
    }

    @Override
    public void beforeFrame(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int rows = Math.min(MAX_SIMULATION_ROWS, Math.max(64, height / 2));
        int columns = Math.max(64, Math.round(rows * width / (float) height));
        if (fields[0] == null || fields[0].width() != columns || fields[0].height() != rows) {
            closeFields();
            for (int index = 0; index < 2; index++) {
                fields[index] = renderer.createRenderTarget(columns, rows, RenderTargetFormat.RGBA16_FLOAT, true, false);
            }
            aspect = width / (float) height;
            seeded = false;
        }
    }

    @Override
    public void render(VulkanFrame frame, VulkanRenderTarget scene, float partialTick) {
        float seconds = (ticks + partialTick) / 20f;
        if (fields[0] == null) {
            frame.beginRendering(scene, true, 0f, 0f, 0f, 1f);
            frame.endRendering();
            return;
        }
        long due = (long) (seconds * STEPS_PER_SECOND);
        if (!seeded) {
            // The seeding pass does not read its input, but the bound image must still be in a sampled layout.
            frame.beginRendering(fields[current], true, 0f, 0f, 0f, 0f);
            frame.endRendering();
            pass(frame, MODE_INIT, seconds, false);
            seeded = true;
            stepsDone = due;
        }
        long steps = due - stepsDone;
        if (steps > MAX_STEPS_PER_FRAME) {
            stepsDone = due - MAX_STEPS_PER_FRAME; // after a stall, catch up a little and drop the rest
            steps = MAX_STEPS_PER_FRAME;
        }
        for (long index = 0; index < steps; index++) {
            boolean stir = index == 0 && (pendingDeltaX != 0f || pendingDeltaY != 0f);
            float stepSeconds = (stepsDone + index + 1) / (float) STEPS_PER_SECOND;
            pass(frame, MODE_ADVECT, stepSeconds, stir);
            for (int iteration = 0; iteration < PRESSURE_ITERATIONS; iteration++) {
                pass(frame, MODE_PRESSURE, stepSeconds, false);
            }
            pass(frame, MODE_PROJECT, stepSeconds, stir);
            if (stir) {
                pendingDeltaX = 0f;
                pendingDeltaY = 0f;
            }
        }
        stepsDone += steps;

        Look colors = look.get();
        push.clear();
        putLinear(0, colors.surface(), 1f);
        putLinear(16, colors.ink(), colors.inkStrength());
        push.putFloat(32, scene.width()).putFloat(36, scene.height()).putFloat(40, seconds)
                .putFloat(44, Math.max(1, colors.cellSize())).putFloat(48, 0.14f).putFloat(52, 0.74f);
        push.limit(COMPOSITE_BYTES);
        frame.beginRendering(scene, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(composite);
        frame.bindImage(fields[current]);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
    }

    /** One solver pass from the current field into the other, which then becomes current. */
    private void pass(VulkanFrame frame, int mode, float seconds, boolean stir) {
        VulkanRenderTarget source = fields[current];
        VulkanRenderTarget target = fields[1 - current];
        push.clear();
        push.putFloat(0, 1f / source.width()).putFloat(4, 1f / source.height())
                .putFloat(8, pointerX).putFloat(12, pointerY)
                .putFloat(16, pendingDeltaX).putFloat(20, pendingDeltaY)
                .putFloat(24, 1f / STEPS_PER_SECOND).putFloat(28, seconds).putFloat(32, aspect)
                .putInt(36, mode).putFloat(40, stir ? 1f : 0f).putFloat(44, 0f);
        push.limit(STEP_BYTES);
        frame.beginRendering(target, false, 0f, 0f, 0f, 0f);
        frame.bindPipeline(step);
        frame.bindImage(source);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
        current = 1 - current;
    }

    private void putLinear(int offset, int argb, float alpha) {
        push.putFloat(offset, linear(argb >> 16 & 0xFF)).putFloat(offset + 4, linear(argb >> 8 & 0xFF))
                .putFloat(offset + 8, linear(argb & 0xFF)).putFloat(offset + 12, alpha);
    }

    private static float linear(int channel) {
        float c = channel / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private void closeFields() {
        for (int index = 0; index < 2; index++) {
            if (fields[index] != null) {
                fields[index].close();
                fields[index] = null;
            }
        }
    }

    @Override
    public void close() {
        closeFields();
        composite.close();
        step.close();
        MemoryUtil.memFree(push);
    }
}
