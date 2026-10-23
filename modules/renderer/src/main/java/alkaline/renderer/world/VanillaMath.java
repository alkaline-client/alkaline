package alkaline.renderer.world;

/**
 * Minecraft 1.8.9's MathHelper approximations where rendering results depend on them: table sine and cosine,
 * and the table-based atan2 (with its fast inverse square root) that orients flowing liquid textures.
 */
final class VanillaMath {
    private static final float[] SIN_TABLE = new float[65536];
    private static final double ATAN_OFFSET = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN = new double[257];
    private static final double[] COS_OF_ASIN = new double[257];

    static {
        for (int index = 0; index < SIN_TABLE.length; index++) {
            SIN_TABLE[index] = (float) Math.sin(index * Math.PI * 2.0D / 65536.0D);
        }
        for (int index = 0; index < ASIN.length; index++) {
            double angle = Math.asin(index / 256.0D);
            COS_OF_ASIN[index] = Math.cos(angle);
            ASIN[index] = angle;
        }
    }

    private VanillaMath() {
    }

    static float sin(float radians) {
        return SIN_TABLE[(int) (radians * 10430.378F) & 65535];
    }

    static float cos(float radians) {
        return SIN_TABLE[(int) (radians * 10430.378F + 16384.0F) & 65535];
    }

    static double atan2(double y, double x) {
        double lengthSquared = x * x + y * y;
        if (Double.isNaN(lengthSquared)) {
            return Double.NaN;
        }
        boolean negativeY = y < 0.0D;
        if (negativeY) {
            y = -y;
        }
        boolean negativeX = x < 0.0D;
        if (negativeX) {
            x = -x;
        }
        boolean swapped = y > x;
        if (swapped) {
            double swap = x;
            x = y;
            y = swap;
        }
        double inverse = inverseSqrt(lengthSquared);
        x *= inverse;
        y *= inverse;
        double biased = ATAN_OFFSET + y;
        int index = (int) Double.doubleToRawLongBits(biased);
        double base = ASIN[index];
        double cosine = COS_OF_ASIN[index];
        double step = biased - ATAN_OFFSET;
        double delta = y * cosine - x * step;
        double correction = (6.0D + delta * delta) * delta * 0.16666666666666666D;
        double angle = base + correction;
        if (swapped) {
            angle = Math.PI / 2D - angle;
        }
        if (negativeX) {
            angle = Math.PI - angle;
        }
        if (negativeY) {
            angle = -angle;
        }
        return angle;
    }

    private static double inverseSqrt(double value) {
        double half = 0.5D * value;
        long bits = Double.doubleToRawLongBits(value);
        bits = 6910469410427058090L - (bits >> 1);
        double result = Double.longBitsToDouble(bits);
        return result * (1.5D - half * result * result);
    }
}
