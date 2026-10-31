package alkaline.clientapp;

import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.model.QuadRenderer;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;

/**
 * Lines in the world: the block selection box (RenderGlobal.drawSelectionBoundingBox) and entity hitboxes
 * (RenderManager.renderDebugBoundingBox). Vulkan has no wide lines on every device, and GL line widths are in
 * pixels, so each segment is a quad facing the camera whose width grows with its distance: a constant pixel width
 * on screen, as glLineWidth gives. Depth-tested without depth writes, as vanilla draws both.
 *
 * <p>Usage inside the world pass: {@link #begin}, any number of {@link #box} and {@link #line}, then {@link #end}.
 * Coordinates are camera-relative.
 */
final class OutlineView implements AutoCloseable {
    private final QuadRenderer quads;
    private final VulkanTexture white;
    /** World units per screen pixel at distance 1 along the view: 2 tan(fov / 2) / viewport height. */
    private float pixel;

    OutlineView(VulkanRenderer renderer, RenderTargetFormat target) {
        quads = new QuadRenderer(renderer, target);
        try {
            white = renderer.createTexture(new RgbaImage(1, 1, new byte[]{-1, -1, -1, -1}), ImageSampling.NEAREST);
        } catch (RuntimeException failure) {
            quads.close();
            throw failure;
        }
    }

    void begin(VulkanFrame frame, float[] viewProjection, float verticalFovRadians, int viewportHeight) {
        pixel = (float) (2.0 * Math.tan(verticalFovRadians / 2.0) / Math.max(1, viewportHeight));
        quads.begin(frame, viewProjection);
        quads.start(QuadRenderer.Mode.ALPHA, white, 0f);
    }

    /** The twelve edges of a box. */
    void box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int argb, float widthPixels) {
        line(minX, minY, minZ, maxX, minY, minZ, argb, widthPixels);
        line(minX, minY, maxZ, maxX, minY, maxZ, argb, widthPixels);
        line(minX, maxY, minZ, maxX, maxY, minZ, argb, widthPixels);
        line(minX, maxY, maxZ, maxX, maxY, maxZ, argb, widthPixels);
        line(minX, minY, minZ, minX, minY, maxZ, argb, widthPixels);
        line(maxX, minY, minZ, maxX, minY, maxZ, argb, widthPixels);
        line(minX, maxY, minZ, minX, maxY, maxZ, argb, widthPixels);
        line(maxX, maxY, minZ, maxX, maxY, maxZ, argb, widthPixels);
        line(minX, minY, minZ, minX, maxY, minZ, argb, widthPixels);
        line(maxX, minY, minZ, maxX, maxY, minZ, argb, widthPixels);
        line(minX, minY, maxZ, minX, maxY, maxZ, argb, widthPixels);
        line(maxX, minY, maxZ, maxX, maxY, maxZ, argb, widthPixels);
    }

    /** One segment {@code widthPixels} wide on screen, whatever its distance. */
    void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb, float widthPixels) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        // Perpendicular to the segment and to the view ray through its middle (the camera is the origin).
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float mz = (z0 + z1) * 0.5f;
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float length = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (length < 1e-9f) {
            return;
        }
        float half = widthPixels * 0.5f * pixel / length;
        float w0 = half * (float) Math.sqrt(x0 * x0 + y0 * y0 + z0 * z0);
        float w1 = half * (float) Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1);
        quads.quad(x0 - sx * w0, y0 - sy * w0, z0 - sz * w0, 0f, 0f,
                x0 + sx * w0, y0 + sy * w0, z0 + sz * w0, 1f, 0f,
                x1 + sx * w1, y1 + sy * w1, z1 + sz * w1, 1f, 1f,
                x1 - sx * w1, y1 - sy * w1, z1 - sz * w1, 0f, 1f, argb);
    }

    void end() {
        quads.flush();
    }

    @Override
    public void close() {
        white.close();
        quads.close();
    }
}
