package alkaline.clientapp;

import alkaline.game.world.WorldBorder;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.model.QuadRenderer;
import alkaline.renderer.vulkan.model.ScreenTint;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import java.io.IOException;
import java.io.InputStream;

/**
 * The world border: RenderGlobal.renderWorldBorder's wall of scrolling force-field texture within a render distance of
 * it, tinted by whether the border is growing, shrinking or still and fading in as the player nears it, and
 * GuiIngame.renderVignette's red warning wash as the edge comes within the warning distance.
 */
final class BorderView implements AutoCloseable {
    private final QuadRenderer quads;
    private final ScreenTint tint;
    private VulkanTexture forcefield;
    private VulkanTexture vignette;

    BorderView(VulkanRenderer renderer, AssetSource assets, RenderTargetFormat target) {
        quads = new QuadRenderer(renderer, target);
        tint = new ScreenTint(renderer, target);
        forcefield = texture(renderer, assets, "assets/minecraft/textures/misc/forcefield.png", ImageSampling.NEAREST,
                true);
        // vignette.png.mcmeta asks for blur.
        vignette = texture(renderer, assets, "assets/minecraft/textures/misc/vignette.png", ImageSampling.LINEAR,
                false);
    }

    private static VulkanTexture texture(VulkanRenderer renderer, AssetSource assets, String path,
                                         ImageSampling sampling, boolean repeat) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            if (input == null) {
                return null;
            }
            RgbaImage image = ImageDecoder.decode(input);
            return renderer.createTexture(image, new TextureOptions(sampling, false, repeat, 1));
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Draws the wall in the current world pass.
     *
     * @param renderDistance chunks
     * @param feetX          the player's interpolated position
     * @param playerX        the player's position this tick (what decides whether the wall is near)
     * @param millis         the clock that scrolls the texture
     */
    void drawWall(VulkanFrame frame, float[] viewProjection, WorldBorder border, int renderDistance, double cameraX,
                  double cameraY, double cameraZ, double feetX, double feetZ, double playerX, double playerZ,
                  long millis) {
        if (forcefield == null) {
            return;
        }
        double range = renderDistance * 16;
        double minX = border.minX();
        double maxX = border.maxX();
        double minZ = border.minZ();
        double maxZ = border.maxZ();
        if (!(playerX >= maxX - range || playerX <= minX + range || playerZ >= maxZ - range
                || playerZ <= minZ + range)) {
            return;
        }
        double fade = 1.0 - border.closestDistance(playerX, playerZ) / range;
        fade = Math.pow(fade, 4.0);
        int rgb = border.status().color();
        int argb = Math.min(255, Math.max(0, (int) Math.round(fade * 255.0))) << 24 | rgb;
        float scroll = (float) (millis % 3000L) / 3000.0F;
        quads.begin(frame, viewProjection);
        quads.start(QuadRenderer.Mode.ADDITIVE, forcefield);
        double from = Math.max(Math.floor(feetZ - range), minZ);
        double to = Math.min(Math.ceil(feetZ + range), maxZ);
        if (feetX > maxX - range) {
            wall(maxX, from, to, true, scroll, argb, cameraX, cameraY, cameraZ);
        }
        if (feetX < minX + range) {
            wall(minX, from, to, true, scroll, argb, cameraX, cameraY, cameraZ);
        }
        from = Math.max(Math.floor(feetX - range), minX);
        to = Math.min(Math.ceil(feetX + range), maxX);
        if (feetZ > maxZ - range) {
            wall(maxZ, from, to, false, scroll, argb, cameraX, cameraY, cameraZ);
        }
        if (feetZ < minZ + range) {
            wall(minZ, from, to, false, scroll, argb, cameraX, cameraY, cameraZ);
        }
        quads.flush();
    }

    /** One wall, in blocks along it: 256 high, a half turn of the texture per block. */
    private void wall(double fixed, double from, double to, boolean alongZ, float scroll, int argb, double cameraX,
                      double cameraY, double cameraZ) {
        float offset = 0.0F;
        for (double at = from; at < to; offset += 0.5F) {
            double span = Math.min(1.0, to - at);
            float width = (float) span * 0.5F;
            float x0 = (float) ((alongZ ? fixed : at) - cameraX);
            float x1 = (float) ((alongZ ? fixed : at + span) - cameraX);
            float z0 = (float) ((alongZ ? at : fixed) - cameraZ);
            float z1 = (float) ((alongZ ? at + span : fixed) - cameraZ);
            float top = (float) (256.0 - cameraY);
            float bottom = (float) (0.0 - cameraY);
            quads.quad(x0, top, z0, scroll + offset, scroll, x1, top, z1, scroll + width + offset, scroll, x1, bottom,
                    z1, scroll + width + offset, scroll + 128.0F, x0, bottom, z0, scroll + offset, scroll + 128.0F,
                    argb);
            at++;
        }
    }

    /** The warning vignette: nothing at 0, a red wash growing to full at the edge. */
    void drawWarning(VulkanFrame frame, float warning) {
        if (warning > 0f && vignette != null) {
            tint.draw(frame, vignette, 0f, warning, warning);
        }
    }

    @Override
    public void close() {
        quads.close();
        tint.close();
        if (forcefield != null) {
            forcefield.close();
        }
        if (vignette != null) {
            vignette.close();
        }
    }
}
