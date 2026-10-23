package alkaline.renderer.vulkan;

import java.util.List;
import java.util.Objects;

/** Pure selection helpers for surface-dependent swapchain configuration. */
public final class SwapchainSelection {
    private static final int VARIABLE_EXTENT = -1;

    private SwapchainSelection() {
    }

    public static SurfaceFormat chooseSurfaceFormat(List<SurfaceFormat> available, SurfaceFormat preferred) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(preferred, "preferred");
        if (available.isEmpty()) {
            throw new IllegalArgumentException("At least one surface format is required.");
        }
        for (SurfaceFormat candidate : available) {
            if (preferred.equals(candidate)) {
                return candidate;
            }
        }
        return Objects.requireNonNull(available.getFirst(), "surface format");
    }

    /** The first of {@code preferences} the surface offers, else {@code requiredFallback}. */
    public static int choosePresentMode(int[] available, int[] preferences, int requiredFallback) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(preferences, "preferences");
        for (int preferred : preferences) {
            for (int mode : available) {
                if (mode == preferred) {
                    return preferred;
                }
            }
        }
        for (int mode : available) {
            if (mode == requiredFallback) {
                return requiredFallback;
            }
        }
        throw new IllegalArgumentException("The required fallback present mode is unavailable.");
    }

    public static Extent chooseExtent(
            int currentWidth,
            int currentHeight,
            int minimumWidth,
            int minimumHeight,
            int maximumWidth,
            int maximumHeight,
            int framebufferWidth,
            int framebufferHeight
    ) {
        if (minimumWidth < 0 || minimumHeight < 0
                || maximumWidth < minimumWidth || maximumHeight < minimumHeight) {
            throw new IllegalArgumentException("Surface extent bounds are invalid.");
        }
        if (currentWidth == VARIABLE_EXTENT && currentHeight == VARIABLE_EXTENT) {
            if (framebufferWidth <= 0 || framebufferHeight <= 0) {
                throw new IllegalArgumentException("Framebuffer dimensions must be positive for a variable extent.");
            }
            return new Extent(
                    clamp(framebufferWidth, minimumWidth, maximumWidth),
                    clamp(framebufferHeight, minimumHeight, maximumHeight)
            );
        }
        if (currentWidth < 0 || currentHeight < 0) {
            throw new IllegalArgumentException("Current surface extent must be fixed or variable in both dimensions.");
        }
        if (currentWidth < minimumWidth || currentWidth > maximumWidth
                || currentHeight < minimumHeight || currentHeight > maximumHeight) {
            throw new IllegalArgumentException("Fixed surface extent lies outside the advertised bounds.");
        }
        return new Extent(currentWidth, currentHeight);
    }

    public static int chooseImageCount(int minimum, int maximum) {
        return chooseImageCount(minimum, maximum, 0);
    }

    /** One more than the minimum (and at least {@code atLeast}), within the surface's maximum. */
    public static int chooseImageCount(int minimum, int maximum, int atLeast) {
        if (minimum <= 0 || maximum < 0 || maximum != 0 && maximum < minimum) {
            throw new IllegalArgumentException("Surface image-count bounds are invalid.");
        }
        if (maximum != 0 && minimum == maximum) {
            return minimum;
        }
        int desired = Math.max(Math.addExact(minimum, 1), atLeast);
        return maximum == 0 ? desired : Math.min(desired, maximum);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public record SurfaceFormat(int format, int colorSpace) {
    }

    public record Extent(int width, int height) {
    }
}
