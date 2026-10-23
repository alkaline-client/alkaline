package alkaline.renderer.vulkan.resource;

import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;

/**
 * Presentation preference. A surface that lacks the mode falls back in order: mailbox and immediate to each other
 * (native Wayland has no immediate mode, AMD on Windows no mailbox), then to FIFO, the one mode every surface has.
 */
public enum PresentMode {
    /** Waits for vertical blank; never tears. */
    FIFO(VK_PRESENT_MODE_FIFO_KHR),
    /** Replaces the queued image each refresh: no tearing, lowest latency without it. */
    MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
    /** Presents as soon as possible; may tear. */
    IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR);

    private final int vulkanMode;

    PresentMode(int vulkanMode) {
        this.vulkanMode = vulkanMode;
    }

    int vulkanMode() {
        return vulkanMode;
    }

    /** Vulkan modes to try, best first. */
    int[] preference() {
        return switch (this) {
            case FIFO -> new int[]{VK_PRESENT_MODE_FIFO_KHR};
            case MAILBOX -> new int[]{VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_FIFO_KHR};
            case IMMEDIATE -> new int[]{VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_FIFO_KHR};
        };
    }

    /** The mode for a Vulkan present mode value, or null for modes Alkaline does not request. */
    static PresentMode of(int vulkanMode) {
        for (PresentMode mode : values()) {
            if (mode.vulkanMode == vulkanMode) {
                return mode;
            }
        }
        return null;
    }
}
