package alkaline.renderer.vulkan.resource;

/**
 * CPU mip generation for small immutable textures (block and item art), run once at texture creation.
 * Colors are averaged weighted by alpha, so transparent texels in cutout art do not darken the edges of what
 * remains, and in linear light when the texture is sRGB-encoded so distant terrain does not turn muddy.
 */
final class MipChain {
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int value = 0; value < 256; value++) {
            float c = value / 255f;
            SRGB_TO_LINEAR[value] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
    }

    private MipChain() {
    }

    /** Halves an RGBA8 image (each dimension, minimum 1) with a 2x2 box filter. */
    static byte[] downsample(byte[] source, int width, int height, boolean srgb) {
        int outWidth = Math.max(1, width >> 1);
        int outHeight = Math.max(1, height >> 1);
        byte[] out = new byte[outWidth * outHeight * 4];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                float alphaSum = 0f;
                int samples = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min(width - 1, x * 2 + dx);
                        int sy = Math.min(height - 1, y * 2 + dy);
                        int offset = (sy * width + sx) * 4;
                        float alpha = (source[offset + 3] & 0xFF) / 255f;
                        r += decode(source[offset], srgb) * alpha;
                        g += decode(source[offset + 1], srgb) * alpha;
                        b += decode(source[offset + 2], srgb) * alpha;
                        alphaSum += alpha;
                        samples++;
                    }
                }
                int target = (y * outWidth + x) * 4;
                if (alphaSum > 0f) {
                    out[target] = encode(r / alphaSum, srgb);
                    out[target + 1] = encode(g / alphaSum, srgb);
                    out[target + 2] = encode(b / alphaSum, srgb);
                }
                out[target + 3] = (byte) Math.round(alphaSum / samples * 255f);
            }
        }
        return out;
    }

    private static float decode(byte value, boolean srgb) {
        int unsigned = value & 0xFF;
        return srgb ? SRGB_TO_LINEAR[unsigned] : unsigned / 255f;
    }

    private static byte encode(float linear, boolean srgb) {
        float c = Math.max(0f, Math.min(1f, linear));
        if (srgb) {
            c = c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4) - 0.055f;
        }
        return (byte) Math.round(c * 255f);
    }
}
