package alkaline.renderer.vulkan.background;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanoramaKernelTest {
    private static final int SIZE = 16;

    /** Literal model of one vanilla pass: out(x, y) = mean of in(y + j - 1, x) with wrap-around. */
    private static double[][] vanillaPass(double[][] in) {
        double[][] out = new double[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                double sum = 0;
                for (int j = 0; j < 3; j++) {
                    sum += in[Math.floorMod(y + j - 1, SIZE)][x];
                }
                out[x][y] = sum / 3;
            }
        }
        return out;
    }

    /** The closed form the blur shader evaluates; arrays are indexed [column][row]. */
    private static double[][] closedForm(double[][] in, PanoramaKernel kernel) {
        double[][] out = new double[SIZE][SIZE];
        int columnRadius = kernel.columnWeights.length / 2;
        int rowRadius = kernel.rowWeights.length / 2;
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                double sum = 0;
                for (int a = 0; a < kernel.columnWeights.length; a++) {
                    for (int b = 0; b < kernel.rowWeights.length; b++) {
                        sum += kernel.columnWeights[a] * kernel.rowWeights[b]
                                * in[Math.floorMod(x + a - columnRadius, SIZE)][Math.floorMod(y + b - rowRadius, SIZE)];
                    }
                }
                out[x][y] = sum;
            }
        }
        return out;
    }

    private static double[][] transpose(double[][] in) {
        double[][] out = new double[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                out[x][y] = in[y][x];
            }
        }
        return out;
    }

    @Test
    void closedFormMatchesVanillaPassesOnTheTransposedRawImage() {
        double[][] upright = new double[SIZE][SIZE];
        java.util.Random random = new java.util.Random(189);
        for (double[] column : upright) {
            for (int row = 0; row < SIZE; row++) {
                column[row] = random.nextDouble();
            }
        }
        // Vanilla renders the transposed view, then blurs with transposing passes.
        double[][] vanilla = transpose(upright);
        for (int passes = 0; passes <= PanoramaSettings.MAX_BLUR_PASSES; passes++) {
            double[][] expected = passes % 2 == 1 ? vanilla : transpose(vanilla);
            double[][] actual = closedForm(upright, PanoramaKernel.forPasses(passes));
            for (int x = 0; x < SIZE; x++) {
                assertArrayEquals(expected[x], actual[x], 1e-5, "passes=" + passes);
            }
            vanilla = vanillaPass(vanilla);
        }
    }

    @Test
    void vanillaSevenPassesUseSevenByNineTaps() {
        PanoramaKernel kernel = PanoramaKernel.forPasses(7);
        assertEquals(7, kernel.columnWeights.length);
        assertEquals(9, kernel.rowWeights.length);
        PanoramaKernel largest = PanoramaKernel.forPasses(PanoramaSettings.MAX_BLUR_PASSES);
        assertTrue(largest.columnWeights.length + largest.rowWeights.length <= 24);
    }

    @Test
    void inverseOrbitIsTheTransposeOfTheOrbit() {
        double pitch = 0.7;
        double yaw = -1.3;
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        PanoramaBackground.writeInverseOrbit(buffer, pitch, yaw);
        // Orbit = Rx(pitch) * Ry(yaw); its inverse applied to Orbit * v must return v.
        double[] v = {0.3, -0.5, 0.8};
        double[] ry = {Math.cos(yaw) * v[0] + Math.sin(yaw) * v[2], v[1], -Math.sin(yaw) * v[0] + Math.cos(yaw) * v[2]};
        double[] orbit = {ry[0], Math.cos(pitch) * ry[1] - Math.sin(pitch) * ry[2],
                Math.sin(pitch) * ry[1] + Math.cos(pitch) * ry[2]};
        double[] back = new double[3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                back[row] += buffer.getFloat((column * 4 + row) * 4) * orbit[column];
            }
        }
        assertArrayEquals(v, back, 1e-6);
    }
}
