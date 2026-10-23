package alkaline.renderer.vulkan.background;

import java.util.Arrays;

/**
 * Closed form of vanilla's repeated panorama blur. One vanilla pass maps out(x, y) to the mean of
 * in(y - 1 .. y + 1, x): a transpose plus a three-tap box. Two passes form a 3x3 box with no transpose.
 *
 * <p>Vanilla's raw cube image is the transpose of the upright view, which its odd default (7) cancels. This
 * kernel always yields the upright image: for odd counts it equals vanilla exactly; for even counts it equals
 * vanilla's result transposed back upright instead of leaving the panorama sideways.
 */
final class PanoramaKernel {
    final float[] columnWeights;
    final float[] rowWeights;

    private PanoramaKernel(float[] columnWeights, float[] rowWeights) {
        this.columnWeights = columnWeights;
        this.rowWeights = rowWeights;
    }

    static PanoramaKernel forPasses(int passes) {
        if (passes < 0) {
            throw new IllegalArgumentException("Blur pass count must not be negative.");
        }
        double[] box = repeatedBox(passes / 2);
        if (passes % 2 == 0) {
            return new PanoramaKernel(toFloats(box), toFloats(box));
        }
        // The extra transposing pass blurs what ends up as the upright image's rows.
        return new PanoramaKernel(toFloats(box), toFloats(convolve(box, new double[]{1 / 3d, 1 / 3d, 1 / 3d})));
    }

    private static double[] repeatedBox(int count) {
        double[] kernel = {1d};
        for (int index = 0; index < count; index++) {
            kernel = convolve(kernel, new double[]{1 / 3d, 1 / 3d, 1 / 3d});
        }
        return kernel;
    }

    private static double[] convolve(double[] left, double[] right) {
        double[] result = new double[left.length + right.length - 1];
        for (int a = 0; a < left.length; a++) {
            for (int b = 0; b < right.length; b++) {
                result[a + b] += left[a] * right[b];
            }
        }
        return result;
    }

    private static float[] toFloats(double[] values) {
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (float) values[index];
        }
        return result;
    }
}
