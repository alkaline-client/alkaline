package alkaline.renderer.vulkan.background;

import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;

/**
 * A full-screen animated menu backdrop. Backgrounds are interchangeable: the menu drives their clock and hands
 * them an offscreen scene target, which it then presents and blurs for frosted UI surfaces.
 */
public interface MenuBackground extends AutoCloseable {
    /** Format of the scene target backgrounds draw into: linear shader output, sRGB storage. */
    RenderTargetFormat SCENE_FORMAT = RenderTargetFormat.RGBA8_SRGB;

    /** Stable id used by settings, for example {@code panorama}. */
    String id();

    /** False while the background is still preparing content; the menu keeps its loading screen up until true. */
    default boolean ready() {
        return true;
    }

    /**
     * Called each loop iteration before the frame is acquired, with the scene size: the place to create or
     * resize GPU resources and to start uploads, which are not allowed inside a frame.
     */
    default void beforeFrame(int width, int height) {
    }

    /**
     * The pointer moved, in window-normalized coordinates (0..1, origin top left). Called on the render thread
     * for interactive backgrounds; others ignore it.
     */
    default void pointer(float x, float y) {
    }

    /**
     * Whether the scene target should carry a depth attachment, so a 3D background draws into it directly instead
     * of into its own target and a full-screen copy.
     */
    default boolean needsDepth() {
        return false;
    }

    /**
     * Whether {@link #renderToSwapchain} works: the background draws its whole scene in one pass with depth, with
     * pipelines built for {@link #SCENE_FORMAT}.
     */
    default boolean rendersToSwapchain() {
        return false;
    }

    /**
     * Records the background straight into the swapchain image, through {@link VulkanFrame#beginSwapchainScene}
     * and {@link VulkanFrame#endRendering}, so the main pass shows it without a copy. The compositor calls it
     * instead of {@link #render}, before the main pass, on frames where nothing samples the scene.
     */
    default void renderToSwapchain(VulkanFrame frame, float partialTick) {
        throw new UnsupportedOperationException(id() + " renders into a scene target only.");
    }

    /** Advances fixed-rate state at the game's 20 Hz tick cadence. */
    default void tick() {
    }

    /**
     * Records the background into {@code scene} (cover every pixel). Called before the main pass, so the
     * background may record its own offscreen passes first.
     */
    void render(VulkanFrame frame, VulkanRenderTarget scene, float partialTick);

    @Override
    void close();
}
