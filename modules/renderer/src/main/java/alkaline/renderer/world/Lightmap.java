package alkaline.renderer.world;

/**
 * Vanilla 1.8.9's overworld lightmap: the color a surface takes at a (sky, block) light pair, given the sun's
 * brightness, the torch flicker, the gamma (brightness) setting and night vision. The world shader evaluates
 * the same formula per fragment (world_light.glsl) and filters between the four nearest light levels, which is
 * what sampling vanilla's 16x16 lightmap texture with linear filtering does; this class is the reference and
 * serves CPU-lit content such as models.
 */
public final class Lightmap {
    private Lightmap() {
    }

    /** Vanilla's overworld light level brightness table: (1 - f) / (3f + 1) with f = 1 - level / 15. */
    public static float levelBrightness(int level) {
        float f = 1.0F - level / 15.0F;
        return (1.0F - f) / (f * 3.0F + 1.0F);
    }

    /**
     * Lightmap color as 0xRRGGBB, quantized like vanilla's texture.
     *
     * @param sunBrightness {@link #sunBrightness} of the current sky
     * @param torchFlicker  vanilla's smoothed random walk, near 0
     * @param gamma         the brightness setting, 0 (moody) to 1 (bright)
     * @param nightVision   0 without the effect, up to 1
     */
    public static int color(int sky, int block, float sunBrightness, float torchFlicker, float gamma,
                            float nightVision) {
        float daylight = sunBrightness * 0.95F + 0.05F;
        float skyLight = levelBrightness(sky) * daylight;
        float blockLight = levelBrightness(block) * (torchFlicker * 0.1F + 1.5F);
        float skyRed = skyLight * (sunBrightness * 0.65F + 0.35F);
        float skyGreen = skyLight * (sunBrightness * 0.65F + 0.35F);
        float blockGreen = blockLight * ((blockLight * 0.6F + 0.4F) * 0.6F + 0.4F);
        float blockBlue = blockLight * (blockLight * blockLight * 0.6F + 0.4F);
        float red = (skyRed + blockLight) * 0.96F + 0.03F;
        float green = (skyGreen + blockGreen) * 0.96F + 0.03F;
        float blue = (skyLight + blockBlue) * 0.96F + 0.03F;
        if (nightVision > 0.0F) {
            float scale = Math.min(1.0F / red, Math.min(1.0F / green, 1.0F / blue));
            red = red * (1.0F - nightVision) + red * scale * nightVision;
            green = green * (1.0F - nightVision) + green * scale * nightVision;
            blue = blue * (1.0F - nightVision) + blue * scale * nightVision;
        }
        red = Math.min(red, 1.0F);
        green = Math.min(green, 1.0F);
        blue = Math.min(blue, 1.0F);
        red = finish(red, gamma);
        green = finish(green, gamma);
        blue = finish(blue, gamma);
        return (int) (red * 255.0F) << 16 | (int) (green * 255.0F) << 8 | (int) (blue * 255.0F);
    }

    private static float finish(float value, float gamma) {
        float inverse = 1.0F - value;
        float lifted = 1.0F - inverse * inverse * inverse * inverse;
        float result = (value * (1.0F - gamma) + lifted * gamma) * 0.96F + 0.03F;
        return Math.max(0.0F, Math.min(1.0F, result));
    }

    /** Vanilla's celestial angle (0 at noon, 0.5 at midnight) for a world time in ticks. */
    public static float celestialAngle(long worldTime, float partialTicks) {
        float linear = ((int) (worldTime % 24000L) + partialTicks) / 24000.0F - 0.25F;
        if (linear < 0.0F) {
            linear++;
        }
        if (linear > 1.0F) {
            linear--;
        }
        float eased = 1.0F - (float) ((Math.cos(linear * Math.PI) + 1.0D) / 2.0D);
        return linear + (eased - linear) / 3.0F;
    }

    /** Vanilla's sun brightness (0.2 at night .. 1.0 by day) without rain or thunder. */
    public static float sunBrightness(float celestialAngle) {
        float f = 1.0F - ((float) Math.cos(celestialAngle * (float) Math.PI * 2.0F) * 2.0F + 0.2F);
        f = Math.max(0.0F, Math.min(1.0F, f));
        return (1.0F - f) * 0.8F + 0.2F;
    }
}
