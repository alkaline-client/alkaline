package alkaline.renderer.math;

/**
 * Allocation-free operations on column-major 4x4 float matrices ({@code m[column * 4 + row]}), the layout GLSL
 * reads from push constants. Projections target Vulkan's [0, 1] depth with a y-up clip space, meant for a
 * viewport flipped with {@code VulkanFrame.setViewport(..., true)}.
 */
public final class Mat4 {
    private Mat4() {
    }

    public static float[] identity(float[] out) {
        for (int index = 0; index < 16; index++) {
            out[index] = (index % 5 == 0) ? 1f : 0f;
        }
        return out;
    }

    /** out = left * right; out may alias either input. */
    public static float[] multiply(float[] left, float[] right, float[] out) {
        float a00 = left[0], a10 = left[1], a20 = left[2], a30 = left[3];
        float a01 = left[4], a11 = left[5], a21 = left[6], a31 = left[7];
        float a02 = left[8], a12 = left[9], a22 = left[10], a32 = left[11];
        float a03 = left[12], a13 = left[13], a23 = left[14], a33 = left[15];
        for (int column = 0; column < 4; column++) {
            float b0 = right[column * 4];
            float b1 = right[column * 4 + 1];
            float b2 = right[column * 4 + 2];
            float b3 = right[column * 4 + 3];
            out[column * 4] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3;
            out[column * 4 + 1] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3;
            out[column * 4 + 2] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3;
            out[column * 4 + 3] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3;
        }
        return out;
    }

    /** Right-handed perspective looking down -Z, depth mapped to [0, 1]. */
    public static float[] perspective(float verticalFovRadians, float aspect, float near, float far, float[] out) {
        if (!(near > 0f) || !(far > near) || !(aspect > 0f)) {
            throw new IllegalArgumentException("Perspective needs 0 < near < far and a positive aspect.");
        }
        float f = (float) (1.0 / Math.tan(verticalFovRadians / 2.0));
        java.util.Arrays.fill(out, 0f);
        out[0] = f / aspect;
        out[5] = f;
        out[10] = far / (near - far);
        out[11] = -1f;
        out[14] = near * far / (near - far);
        return out;
    }

    /** out = m * translate(x, y, z). */
    public static float[] translate(float[] m, float x, float y, float z, float[] out) {
        if (out != m) {
            System.arraycopy(m, 0, out, 0, 16);
        }
        for (int row = 0; row < 4; row++) {
            out[12 + row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        return out;
    }

    /** out = m * scale(x, y, z). */
    public static float[] scale(float[] m, float x, float y, float z, float[] out) {
        for (int row = 0; row < 4; row++) {
            out[row] = m[row] * x;
            out[4 + row] = m[4 + row] * y;
            out[8 + row] = m[8 + row] * z;
            out[12 + row] = m[12 + row];
        }
        return out;
    }

    /** out = m * R(axis, angle) for a unit axis; matches glRotatef. */
    public static float[] rotate(float[] m, float radians, float axisX, float axisY, float axisZ, float[] out) {
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float t = 1f - c;
        float r00 = t * axisX * axisX + c, r01 = t * axisX * axisY - s * axisZ, r02 = t * axisX * axisZ + s * axisY;
        float r10 = t * axisX * axisY + s * axisZ, r11 = t * axisY * axisY + c, r12 = t * axisY * axisZ - s * axisX;
        float r20 = t * axisX * axisZ - s * axisY, r21 = t * axisY * axisZ + s * axisX, r22 = t * axisZ * axisZ + c;
        for (int row = 0; row < 4; row++) {
            float m0 = m[row];
            float m1 = m[4 + row];
            float m2 = m[8 + row];
            out[row] = m0 * r00 + m1 * r10 + m2 * r20;
            out[4 + row] = m0 * r01 + m1 * r11 + m2 * r21;
            out[8 + row] = m0 * r02 + m1 * r12 + m2 * r22;
            out[12 + row] = m[12 + row];
        }
        return out;
    }
}
