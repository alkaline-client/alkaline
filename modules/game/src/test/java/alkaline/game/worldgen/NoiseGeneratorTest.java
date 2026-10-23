package alkaline.game.worldgen;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoiseGeneratorTest
{
    @Test
    void improvedNoiseMatchesReferenceForThreeDimensionalAndSingleLayerPaths()
    {
        NoiseGeneratorImproved improved = new NoiseGeneratorImproved(new Random(123456789L));
        double[] threeDimensional = new double[12];
        Arrays.fill(threeDimensional, 7.25D);
        improved.populateNoiseArray(threeDimensional, -3.125D, -2.75D, 6.5D, 2, 3, 2, 0.37D, 0.61D, 0.29D, 0.8D);
        assertArrayEquals(hex(
                "0x1.9de043446256dp2", "0x1.b55cdb28bcb6ep2", "0x1.d46285adf3fcp2", "0x1.ab8313a6eb609p2",
                "0x1.cff0a3ffbda63p2", "0x1.c843f3d38b31ep2", "0x1.afbca23463554p2", "0x1.d01071c8cc781p2",
                "0x1.d8d9e28bce775p2", "0x1.b0a4647328d15p2", "0x1.ef5aa34d90a06p2", "0x1.cb46c880251d2p2"), threeDimensional);

        double[] singleLayer = new double[6];
        Arrays.fill(singleLayer, -1.5D);
        improved.populateNoiseArray(singleLayer, -1.25D, 0.0D, 4.75D, 2, 1, 3, 0.43D, 1.0D, 0.31D, 0.65D);
        assertArrayEquals(hex(
                "-0x1.e89094594336ap0", "-0x1.95eaefee90619p0", "-0x1.56436cc0d35bep0",
                "-0x1.db193ab5b7647p0", "-0x1.67d873415a811p0", "-0x1.060bcc7d282c3p0"), singleLayer);
    }

    @Test
    void simplexMatchesReferenceAndAccumulatesIntoProvidedArray()
    {
        NoiseGeneratorSimplex simplex = new NoiseGeneratorSimplex(new Random(987654321L));
        assertEquals(Double.doubleToLongBits(Double.valueOf("-0x1.5ed83579a78c5p-2")),
                Double.doubleToLongBits(simplex.func_151605_a(-12.375D, 5.625D)));

        double[] values = new double[6];
        Arrays.fill(values, 1.25D);
        simplex.func_151606_a(values, -2.5D, 7.25D, 3, 2, 0.41D, 0.27D, 0.75D);
        assertArrayEquals(hex(
                "0x1.9e8d313b9c0fdp0", "0x1.e801db041ee79p0", "0x1.246d8d6469309p0",
                "0x1.02891681c7ddcp0", "0x1.e15d8e3a1d286p0", "0x1.f8ddb2b318a5fp-1"), values);
    }

    @Test
    void perlinMatchesReferenceAndValidatesItsOutputArray()
    {
        NoiseGeneratorPerlin perlin = new NoiseGeneratorPerlin(new Random(31415926L), 4);
        assertEquals(Double.doubleToLongBits(Double.valueOf("-0x1.6b253b29a1afap2")),
                Double.doubleToLongBits(perlin.func_151601_a(-2.375D, 9.125D)));

        double[] output = new double[8];
        assertSame(output, perlin.func_151600_a(output, -3.5D, 2.25D, 3, 2, 0.31D, 0.47D, 0.62D, 0.5D));
        assertArrayEquals(hex(
                "-0x1.c346d221443e8p-2", "-0x1.b0c0c619f84e8p-2", "-0x1.d06de288ff058p-1", "-0x1.e86d0a301ad32p-1",
                "-0x1.2d460d1a2e75ap0", "-0x1.020f635df1244p0", "0x0.0p0", "0x0.0p0"), output);

        double[] oversized = new double[10];
        Arrays.fill(oversized, 99.0D);
        assertSame(oversized, perlin.func_151600_a(oversized, -3.5D, 2.25D, 3, 2, 0.31D, 0.47D, 0.62D, 0.5D));
        assertEquals(0.0D, oversized[8]);
        assertEquals(0.0D, oversized[9]);
        double[] undersized = new double[5];
        Arrays.fill(undersized, 37.0D);
        double[] undersizedBefore = undersized.clone();
        assertThrows(IllegalArgumentException.class,
                () -> perlin.func_151600_a(undersized, -3.5D, 2.25D, 3, 2, 0.31D, 0.47D, 0.62D, 0.5D));
        assertArrayEquals(undersizedBefore, undersized);

        double[] allocated = perlin.func_151600_a(null, -3.5D, 2.25D, 3, 2, 0.31D, 0.47D, 0.62D, 0.5D);
        assertEquals(6, allocated.length);
        assertArrayEquals(hex(
                "-0x1.c346d221443e8p-2", "-0x1.b0c0c619f84e8p-2", "-0x1.d06de288ff058p-1", "-0x1.e86d0a301ad32p-1",
                "-0x1.2d460d1a2e75ap0", "-0x1.020f635df1244p0"), allocated);
    }

    @Test
    void octaveNoiseMatchesReferenceForBothOverloadsAndResetsReusedArray()
    {
        NoiseGeneratorOctaves octaves = new NoiseGeneratorOctaves(new Random(42424242L), 5);
        double[] threeDimensional = octaves.generateNoiseOctaves(null, -31, -12, 27, 2, 3, 2, 0.017D, 0.031D, 0.023D);
        assertArrayEquals(hex(
                "-0x1.ca6a6f572284p-1", "-0x1.c6e95d8fa56c8p-1", "-0x1.c0fe04f5d309p-1", "-0x1.bd7ff18af0128p-1",
                "-0x1.b9fd54ae7783p-1", "-0x1.b41186d033a4p-1", "-0x1.d6743cf5e254p-1", "-0x1.d2a94b81485bp-1",
                "-0x1.cc7b2e1c47c4p-1", "-0x1.c9fced068452p-1", "-0x1.c62e21dc1543p-1", "-0x1.bffc2fcc7e35p-1"), threeDimensional);

        double[] twoDimensional = octaves.generateNoiseOctaves(null, -31, 27, 3, 2, 0.017D, 0.023D, 123.0D);
        assertArrayEquals(hex(
                "-0x1.bd7c023ee383cp2", "-0x1.bb3f169b1560cp2", "-0x1.beede7e14d4c5p2",
                "-0x1.bcc159a28c59ap2", "-0x1.c06d10514f416p2", "-0x1.be51609a8fe9ap2"), twoDimensional);

        Arrays.fill(threeDimensional, Double.NaN);
        assertSame(threeDimensional, octaves.generateNoiseOctaves(threeDimensional, -31, -12, 27, 2, 3, 2, 0.017D, 0.031D, 0.023D));
        assertArrayEquals(hex(
                "-0x1.ca6a6f572284p-1", "-0x1.c6e95d8fa56c8p-1", "-0x1.c0fe04f5d309p-1", "-0x1.bd7ff18af0128p-1",
                "-0x1.b9fd54ae7783p-1", "-0x1.b41186d033a4p-1", "-0x1.d6743cf5e254p-1", "-0x1.d2a94b81485bp-1",
                "-0x1.cc7b2e1c47c4p-1", "-0x1.c9fced068452p-1", "-0x1.c62e21dc1543p-1", "-0x1.bffc2fcc7e35p-1"), threeDimensional);
    }

    @Test
    void seededGeneratorsProduceIdenticalOutputs()
    {
        NoiseGeneratorOctaves first = new NoiseGeneratorOctaves(new Random(20261010L), 6);
        NoiseGeneratorOctaves second = new NoiseGeneratorOctaves(new Random(20261010L), 6);
        assertArrayEquals(
                first.generateNoiseOctaves(null, 17, -23, -41, 3, 2, 4, 0.018D, 0.027D, 0.013D),
                second.generateNoiseOctaves(null, 17, -23, -41, 3, 2, 4, 0.018D, 0.027D, 0.013D));

        NoiseGeneratorSimplex simplexA = new NoiseGeneratorSimplex(new Random(20261010L));
        NoiseGeneratorSimplex simplexB = new NoiseGeneratorSimplex(new Random(20261010L));
        assertEquals(Double.doubleToLongBits(simplexA.func_151605_a(-18.75D, 31.125D)),
                Double.doubleToLongBits(simplexB.func_151605_a(-18.75D, 31.125D)));
    }

    private static double[] hex(String... values)
    {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; ++i)
        {
            result[i] = Double.valueOf(values[i]);
        }
        return result;
    }
}
