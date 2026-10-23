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
