package alkaline.renderer.model;

import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.math.Mat4;
import alkaline.renderer.world.vanilla.BlockModels189;

/**
 * Inventory icons drawn on the CPU, once per item and tint combination: block items as vanilla's
 * RenderItem.renderItemIntoGUI draws them (the isometric 30/225 degree view, the model's GUI transform, lit by the
 * two GUI item lights with 40% ambient), and generated items as their sprite layers composited with their tints.
 * Icons are {@code size} pixels square for a 16-pixel slot.
 */
public final class ItemIcons {
    private ItemIcons() {
    }

    /**
     * @param tints ARGB tint per tint index (or per layer for generated items); -1 or missing entries mean none
     */
    public static RgbaImage icon(ItemMeshes.Mesh mesh, int[] tints, int size) {
        return mesh.block() ? block(mesh, tints, size) : layers(mesh, tints, size);
    }

    /** Generated items: each layer scaled to the icon (nearest) and blended over the last, tinted. */
    private static RgbaImage layers(ItemMeshes.Mesh mesh, int[] tints, int size) {
        byte[] out = new byte[size * size * 4];
        for (ItemMeshes.Layer layer : mesh.layers()) {
            RgbaImage texture = layer.texture();
            byte[] source = texture.copyPixels();
            int tint = tint(tints, layer.tintIndex());
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int sx = x * texture.width() / size;
                    int sy = y * texture.height() / size;
                    int s = (sy * texture.width() + sx) * 4;
                    int alpha = source[s + 3] & 255;
                    if (alpha == 0) {
                        continue;
                    }
                    int r = (source[s] & 255) * (tint >> 16 & 255) / 255;
                    int g = (source[s + 1] & 255) * (tint >> 8 & 255) / 255;
                    int b = (source[s + 2] & 255) * (tint & 255) / 255;
                    blend(out, (y * size + x) * 4, r, g, b, alpha);
                }
            }
        }
        return new RgbaImage(size, size, out);
    }

    private static void blend(byte[] out, int index, int r, int g, int b, int alpha) {
        int baseAlpha = out[index + 3] & 255;
        int outAlpha = alpha + baseAlpha * (255 - alpha) / 255;
        if (outAlpha == 0) {
            return;
        }
        out[index] = (byte) ((r * alpha + (out[index] & 255) * baseAlpha * (255 - alpha) / 255) / outAlpha);
        out[index + 1] = (byte) ((g * alpha + (out[index + 1] & 255) * baseAlpha * (255 - alpha) / 255) / outAlpha);
        out[index + 2] = (byte) ((b * alpha + (out[index + 2] & 255) * baseAlpha * (255 - alpha) / 255) / outAlpha);
        out[index + 3] = (byte) outAlpha;
    }

    /** Block items: every quad rasterized with depth, nearest texels, alpha-tested, Lambert-lit. */
    private static RgbaImage block(ItemMeshes.Mesh mesh, int[] tints, int size) {
        float[] transform = guiTransform(mesh.definition().gui());
        float pixel = size / 16f;
        // RenderHelper.enableGUIStandardItemLighting: the item lights turned by -30 degrees about y, 165 about x.
        float[] lights = Mat4.identity(new float[16]);
        Mat4.rotate(lights, (float) Math.toRadians(-30), 0f, 1f, 0f, lights);
        Mat4.rotate(lights, (float) Math.toRadians(165), 1f, 0f, 0f, lights);
        float[] light0 = direction(lights, 0.2f, 1.0f, -0.7f);
        float[] light1 = direction(lights, -0.2f, 1.0f, 0.7f);
        byte[] out = new byte[size * size * 4];
        float[] depth = new float[size * size];
        java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
        for (BlockModels189.Quad quad : mesh.quads()) {
            RgbaImage texture = mesh.textures().get(quad.texture());
            if (texture == null) {
                continue;
            }
            byte[] texels = texture.copyPixels();
            float[] screen = new float[12];
            for (int corner = 0; corner < 4; corner++) {
                transform(transform, quad.positions()[corner * 3], quad.positions()[corner * 3 + 1],
                        quad.positions()[corner * 3 + 2], screen, corner * 3);
                screen[corner * 3] *= pixel;
                screen[corner * 3 + 1] *= pixel;
            }
            float[] normal = ItemMeshes.normal(quad.positions());
            float[] eye = direction(transform, normal[0], normal[1], normal[2]);
            float light = Math.min(1f, 0.4f + 0.6f * Math.max(0f, dot(eye, light0)) + 0.6f * Math.max(0f, dot(eye, light1)));
            int tint = tint(tints, quad.tintIndex());
            triangle(screen, quad.uvs(), 0, 1, 2, texture, texels, light, tint, out, depth, size);
            triangle(screen, quad.uvs(), 0, 2, 3, texture, texels, light, tint, out, depth, size);
        }
        return new RgbaImage(size, size, out);
    }

    /**
     * RenderItem.setupGuiTransform for 3D items, the model's GUI display transform, then renderItem's half scale
     * around the model's center: model units (0..1) to slot pixels (0..16, y down, z toward the viewer).
     */
    static float[] guiTransform(ItemDefinition.Display gui) {
        float[] m = Mat4.identity(new float[16]);
        Mat4.translate(m, 8f, 8f, 0f, m);
        Mat4.scale(m, 1f, 1f, -1f, m);
        Mat4.scale(m, 0.5f * 40f, 0.5f * 40f, 0.5f * 40f, m);
        Mat4.rotate(m, (float) Math.toRadians(210), 1f, 0f, 0f, m);
        Mat4.rotate(m, (float) Math.toRadians(-135), 0f, 1f, 0f, m);
        Mat4.translate(m, gui.translationX(), gui.translationY(), gui.translationZ(), m);
        Mat4.rotate(m, (float) Math.toRadians(gui.rotationY()), 0f, 1f, 0f, m);
        Mat4.rotate(m, (float) Math.toRadians(gui.rotationX()), 1f, 0f, 0f, m);
        Mat4.rotate(m, (float) Math.toRadians(gui.rotationZ()), 0f, 0f, 1f, m);
        Mat4.scale(m, gui.scaleX(), gui.scaleY(), gui.scaleZ(), m);
        Mat4.scale(m, 0.5f, 0.5f, 0.5f, m);
        return Mat4.translate(m, -0.5f, -0.5f, -0.5f, m);
    }

    private static void triangle(float[] screen, float[] uvs, int a, int b, int c, RgbaImage texture, byte[] texels,
                                 float light, int tint, byte[] out, float[] depth, int size) {
        float ax = screen[a * 3];
        float ay = screen[a * 3 + 1];
        float bx = screen[b * 3];
        float by = screen[b * 3 + 1];
        float cx = screen[c * 3];
        float cy = screen[c * 3 + 1];
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float wa = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                float wb = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                float wc = 1f - wa - wb;
                if (wa < -1e-4f || wb < -1e-4f || wc < -1e-4f) {
                    continue;
                }
                float z = wa * screen[a * 3 + 2] + wb * screen[b * 3 + 2] + wc * screen[c * 3 + 2];
                int index = y * size + x;
                if (z <= depth[index]) {
                    continue;
                }
                float u = wa * uvs[a * 2] + wb * uvs[b * 2] + wc * uvs[c * 2];
                float v = wa * uvs[a * 2 + 1] + wb * uvs[b * 2 + 1] + wc * uvs[c * 2 + 1];
                int tx = Math.min(texture.width() - 1, Math.max(0, (int) (u * texture.width())));
                int ty = Math.min(texture.height() - 1, Math.max(0, (int) (v * texture.height())));
                int t = (ty * texture.width() + tx) * 4;
                int alpha = texels[t + 3] & 255;
                if (alpha < 26) {
                    // GlStateManager.alphaFunc(GREATER, 0.1).
                    continue;
                }
                depth[index] = z;
                int o = index * 4;
                out[o] = (byte) Math.min(255, (texels[t] & 255) * (tint >> 16 & 255) / 255 * light);
                out[o + 1] = (byte) Math.min(255, (texels[t + 1] & 255) * (tint >> 8 & 255) / 255 * light);
                out[o + 2] = (byte) Math.min(255, (texels[t + 2] & 255) * (tint & 255) / 255 * light);
                out[o + 3] = (byte) alpha;
            }
        }
    }

    private static int tint(int[] tints, int index) {
        if (index < 0 || tints == null || index >= tints.length || tints[index] == -1) {
            return 0xFFFFFFFF;
        }
        return tints[index];
    }

    private static void transform(float[] m, float x, float y, float z, float[] out, int offset) {
        out[offset] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[offset + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[offset + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    /** A direction through the matrix's linear part, normalized. */
    private static float[] direction(float[] m, float x, float y, float z) {
        float dx = m[0] * x + m[4] * y + m[8] * z;
        float dy = m[1] * x + m[5] * y + m[9] * z;
        float dz = m[2] * x + m[6] * y + m[10] * z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return length < 1e-9f ? new float[]{0, 0, 0} : new float[]{dx / length, dy / length, dz / length};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
