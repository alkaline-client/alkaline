package alkaline.renderer.model;

import alkaline.renderer.math.Mat4;

/**
 * RenderArrow's geometry for {@code textures/entity/arrow.png}: the fletching square at the back, both ways, and
 * two crossed shaft planes (four quads, each turned 90 degrees about the shaft), in the arrow's own space after
 * vanilla's 45 degree roll, 0.05625 scale and 4-pixel shift. The shaft points along +x; an instance turns it by
 * the arrow's yaw - 90 about y and its pitch about z.
 */
public final class ProjectileModels {
    private static final float SCALE = 0.05625F;
    private static BakedModel arrow;

    private ProjectileModels() {
    }

    public static synchronized BakedModel arrow() {
        if (arrow == null) {
            arrow = buildArrow();
        }
        return arrow;
    }

    private static BakedModel buildArrow() {
        BakedModel.Builder builder = new BakedModel.Builder();
        float[] base = Mat4.identity(new float[16]);
        Mat4.rotate(base, (float) Math.toRadians(45), 1f, 0f, 0f, base);
        Mat4.scale(base, SCALE, SCALE, SCALE, base);
        Mat4.translate(base, -4f, 0f, 0f, base);
        float back = 5f / 32f;
        float[] tailUv = {0f, back, back, back, back, 2 * back, 0f, 2 * back};
        quad(builder, base, new float[]{-7, -2, -2, -7, -2, 2, -7, 2, 2, -7, 2, -2}, tailUv, 1, 0, 0);
        quad(builder, base, new float[]{-7, 2, -2, -7, 2, 2, -7, -2, 2, -7, -2, -2}, tailUv, -1, 0, 0);
        float[] shaftUv = {0f, 0f, 0.5f, 0f, 0.5f, back, 0f, back};
        float[] turn = base.clone();
        for (int plane = 0; plane < 4; plane++) {
            Mat4.rotate(turn, (float) Math.toRadians(90), 1f, 0f, 0f, turn);
            quad(builder, turn, new float[]{-8, -2, 0, 8, -2, 0, 8, 2, 0, -8, 2, 0}, shaftUv, 0, 0, 1);
        }
        return builder.build("arrow", 1);
    }

    private static void quad(BakedModel.Builder builder, float[] m, float[] corners, float[] uvs, float nx, float ny,
                             float nz) {
        float[] out = new float[12];
        for (int corner = 0; corner < 4; corner++) {
            float x = corners[corner * 3];
            float y = corners[corner * 3 + 1];
            float z = corners[corner * 3 + 2];
            out[corner * 3] = m[0] * x + m[4] * y + m[8] * z + m[12];
            out[corner * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
            out[corner * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
        }
        float tx = m[0] * nx + m[4] * ny + m[8] * nz;
        float ty = m[1] * nx + m[5] * ny + m[9] * nz;
        float tz = m[2] * nx + m[6] * ny + m[10] * nz;
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        builder.quad(0, out, uvs, tx / length, ty / length, tz / length);
    }
}
