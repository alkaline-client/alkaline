package alkaline.renderer.vulkan.resource;

import alkaline.renderer.image.ImageSampling;
import java.util.Objects;

/**
 * Sampling and color-encoding choices for an immutable texture.
 *
 * @param srgb decode texels from sRGB to linear when sampled; false keeps encoded values (vanilla-style math)
 * @param repeat wrap coordinates instead of clamping to the edge
 * @param mipLevels levels to generate on the CPU at creation (1 = none); each level halves both dimensions
 */
public record TextureOptions(ImageSampling sampling, boolean srgb, boolean repeat, int mipLevels) {
    public TextureOptions {
        Objects.requireNonNull(sampling, "sampling");
        if (mipLevels < 1 || mipLevels > 16) {
            throw new IllegalArgumentException("Mip levels must be between 1 and 16.");
        }
    }

    public TextureOptions(ImageSampling sampling, boolean srgb, boolean repeat) {
        this(sampling, srgb, repeat, 1);
    }

    public static TextureOptions pixelArt() {
        return new TextureOptions(ImageSampling.NEAREST, true, false);
    }
}
