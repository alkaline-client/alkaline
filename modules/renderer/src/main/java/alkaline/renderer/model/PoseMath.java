package alkaline.renderer.model;

/**
 * Resolves a {@link ModelPose} into one affine 3x4 row-major matrix per part (12 floats: three rows of
 * {@code [r0 r1 r2 t]}), the layout the entity shader reads. Hidden parts, and children of hidden parts,
 * receive a zero matrix so their triangles collapse.
 */
public final class PoseMath {
    public static final int FLOATS_PER_PART = 12;

    private PoseMath() {
    }

    /**
     * @param root column-major 4x4 model-to-world transform (block units)
     * @param out receives {@code partCount * 12} floats
     */
    public static void resolve(ModelPose pose, float[] root, float[] out) {
        CuboidModel model = pose.model();
        int parts = model.partCount();
        if (out.length < parts * FLOATS_PER_PART || root.length != 16) {
            throw new IllegalArgumentException("Pose output needs 12 floats per part and a 16-float root.");
        }
        for (int part = 0; part < parts; part++) {
            int parent = model.parts().get(part).parent();
            int target = part * FLOATS_PER_PART;
            if (!pose.visible[part] || (parent >= 0 && isZero(out, parent * FLOATS_PER_PART))) {
                java.util.Arrays.fill(out, target, target + FLOATS_PER_PART, 0f);
                continue;
            }
            // Local = T(pivot / 16) * Rz * Ry * Rx
            float rx = pose.rotation[part * 3];
            float ry = pose.rotation[part * 3 + 1];
            float rz = pose.rotation[part * 3 + 2];
            float cx = (float) Math.cos(rx), sx = (float) Math.sin(rx);
            float cy = (float) Math.cos(ry), sy = (float) Math.sin(ry);
            float cz = (float) Math.cos(rz), sz = (float) Math.sin(rz);
            float l00 = cz * cy, l01 = cz * sy * sx - sz * cx, l02 = cz * sy * cx + sz * sx;
            float l10 = sz * cy, l11 = sz * sy * sx + cz * cx, l12 = sz * sy * cx - cz * sx;
            float l20 = -sy, l21 = cy * sx, l22 = cy * cx;
            float tx = pose.pivot[part * 3] / ModelMesher.UNITS_PER_BLOCK;
            float ty = pose.pivot[part * 3 + 1] / ModelMesher.UNITS_PER_BLOCK;
            float tz = pose.pivot[part * 3 + 2] / ModelMesher.UNITS_PER_BLOCK;

            float p00, p01, p02, p03, p10, p11, p12, p13, p20, p21, p22, p23;
            if (parent < 0) {
                p00 = root[0]; p01 = root[4]; p02 = root[8]; p03 = root[12];
                p10 = root[1]; p11 = root[5]; p12 = root[9]; p13 = root[13];
                p20 = root[2]; p21 = root[6]; p22 = root[10]; p23 = root[14];
            } else {
                int source = parent * FLOATS_PER_PART;
                p00 = out[source]; p01 = out[source + 1]; p02 = out[source + 2]; p03 = out[source + 3];
                p10 = out[source + 4]; p11 = out[source + 5]; p12 = out[source + 6]; p13 = out[source + 7];
                p20 = out[source + 8]; p21 = out[source + 9]; p22 = out[source + 10]; p23 = out[source + 11];
            }
            out[target] = p00 * l00 + p01 * l10 + p02 * l20;
            out[target + 1] = p00 * l01 + p01 * l11 + p02 * l21;
            out[target + 2] = p00 * l02 + p01 * l12 + p02 * l22;
            out[target + 3] = p00 * tx + p01 * ty + p02 * tz + p03;
            out[target + 4] = p10 * l00 + p11 * l10 + p12 * l20;
            out[target + 5] = p10 * l01 + p11 * l11 + p12 * l21;
            out[target + 6] = p10 * l02 + p11 * l12 + p12 * l22;
            out[target + 7] = p10 * tx + p11 * ty + p12 * tz + p13;
            out[target + 8] = p20 * l00 + p21 * l10 + p22 * l20;
            out[target + 9] = p20 * l01 + p21 * l11 + p22 * l21;
            out[target + 10] = p20 * l02 + p21 * l12 + p22 * l22;
            out[target + 11] = p20 * tx + p21 * ty + p22 * tz + p23;
        }
    }

    private static boolean isZero(float[] values, int offset) {
        for (int index = offset; index < offset + FLOATS_PER_PART; index++) {
            if (values[index] != 0f) {
                return false;
            }
        }
        return true;
    }
}
