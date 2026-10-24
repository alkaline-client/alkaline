package alkaline.clientapp;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.font.VanillaFont;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.model.QuadRenderer;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Render.renderLivingLabel: a name tag as a camera-facing sheet of text on a translucent black plate, in the vanilla
 * font. Standing tags are drawn twice, first faint through everything and then in full where nothing covers them;
 * sneaking tags only faint and only where visible. The font and its textures come from the active assets.
 *
 * <p>Usage inside the world pass: {@link #begin}, any number of {@link #label}, then {@link #end}. Textures load in
 * {@link #beforeFrame}, outside frames; a tag whose font is not loaded yet is skipped.
 */
final class NameTagRenderer implements AutoCloseable {
    /** Render.renderLivingLabel's scale: 0.016666668 * 1.6. */
    static final float SCALE = 0.02666667F;
    private static final int PLATE = 0x40000000;
    private static final int FAINT = 0x20FFFFFF;
    private static final int UNICODE_PAGES_PER_FRAME = 2;

    private final VulkanRenderer renderer;
    private final AssetSource assets;
    private final QuadRenderer quads;
    private final Map<Integer, VulkanTexture> pageTextures = new HashMap<>();
    private final Map<Integer, RgbaImage> pageImages = new HashMap<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private VulkanTexture ascii;
    private VulkanTexture white;
    private VanillaFont font;
    private boolean loaded;

    // The label being drawn: its origin and the right and down vectors of one pixel, camera-relative.
    private float originX;
    private float originY;
    private float originZ;
    private float rightX;
    private float rightY;
    private float rightZ;
    private float downX;
    private float downY;
    private float downZ;
    private float yaw;
    private float pitch;

    /** The glyph page and color being drawn (one texture per draw), and the underline and strike bars to follow. */
    private int page;
    private int pageGlyphs;
    private float[] bars = new float[64];
    private int[] barColors = new int[16];
    private int barCount;

    private final VanillaFont.Sink sink = new VanillaFont.Sink() {
        @Override
        public void glyph(int glyphPage, float x0, float y0, float x1, float y1, float x2, float y2, float x3,
                          float y3, float u0, float v0, float u1, float v1, int argb) {
            if (glyphPage != page) {
                seen[glyphPage + 1] = true;
                return;
            }
            pageGlyphs++;
            quads.quad(wx(x0, y0), wy(x0, y0), wz(x0, y0), u0, v0, wx(x1, y1), wy(x1, y1), wz(x1, y1), u1, v0,
                    wx(x2, y2), wy(x2, y2), wz(x2, y2), u1, v1, wx(x3, y3), wy(x3, y3), wz(x3, y3), u0, v1, argb);
        }

        @Override
        public void rect(float left, float top, float right, float bottom, int argb) {
            if (page != VanillaFont.ASCII_PAGE) {
                return;
            }
            if (barCount * 4 + 4 > bars.length) {
                bars = java.util.Arrays.copyOf(bars, bars.length * 2);
                barColors = java.util.Arrays.copyOf(barColors, barColors.length * 2);
            }
            bars[barCount * 4] = left;
            bars[barCount * 4 + 1] = top;
            bars[barCount * 4 + 2] = right;
            bars[barCount * 4 + 3] = bottom;
            barColors[barCount++] = argb;
        }
    };
    /** Glyph pages the text uses, by page number plus one (the ASCII atlas is entry 0). */
    private final boolean[] seen = new boolean[257];

    NameTagRenderer(VulkanRenderer renderer, AssetSource assets, QuadRenderer quads) {
        this.renderer = renderer;
        this.assets = assets;
        this.quads = quads;
    }

    // ---- assets -------------------------------------------------------------------------------------------

    /** Loads the font on first use and a couple of queued unicode pages per call; call outside frames. */
    void beforeFrame() {
        if (!loaded) {
            loaded = true;
            RgbaImage atlas = image("assets/minecraft/textures/font/ascii.png");
            if (atlas != null) {
                TextureOptions nearest = new TextureOptions(ImageSampling.NEAREST, false, false, 1);
                ascii = renderer.createTexture(atlas, nearest);
                white = renderer.createTexture(new RgbaImage(1, 1, new byte[]{-1, -1, -1, -1}), nearest);
                font = new VanillaFont(atlas, bytes("assets/minecraft/font/glyph_sizes.bin"), pageImages::get);
            }
        }
        for (int count = 0; count < UNICODE_PAGES_PER_FRAME && !pending.isEmpty(); count++) {
            int number = pending.poll();
            RgbaImage image = image(String.format("assets/minecraft/textures/font/unicode_page_%02x.png", number));
            if (image == null || image.width() != 256 || image.height() != 256) {
                failed.add(number);
                continue;
            }
            pageImages.put(number, image);
            pageTextures.put(number, renderer.createTexture(image,
                    new TextureOptions(ImageSampling.NEAREST, false, false, 1)));
        }
    }

    private RgbaImage image(String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private byte[] bytes(String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** Whether the font is loaded. */
    boolean ready() {
        return font != null;
    }

    /** FontRenderer.getStringWidth. */
    int width(String text) {
        return font == null ? 0 : font.width(text);
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /**
     * @param yaw   the camera's yaw in degrees (RenderManager.playerViewY)
     * @param pitch the camera's pitch in degrees (playerViewX)
     */
    void begin(VulkanFrame frame, float[] viewProjection, float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        quads.begin(frame, viewProjection);
    }

    /**
     * Draws one tag.
     *
     * @param x        the label origin relative to the camera (above the entity's head)
     * @param sneaking the sneaking branch of RendererLivingEntity.renderName: the tag sits a quarter block lower and
     *                 is drawn only faintly, where visible
     */
    void label(String text, float x, float y, float z, boolean sneaking) {
        if (font == null || text.isEmpty()) {
            return;
        }
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        float sinYaw = (float) Math.sin(yawRadians);
        float cosYaw = (float) Math.cos(yawRadians);
        float sinPitch = (float) Math.sin(pitchRadians);
        float cosPitch = (float) Math.cos(pitchRadians);
        // One pixel to the right of, and below, the label as seen from the camera.
        rightX = -cosYaw * SCALE;
        rightY = 0f;
        rightZ = -sinYaw * SCALE;
        downX = sinYaw * sinPitch * SCALE;
        downY = -cosPitch * SCALE;
        downZ = -cosYaw * sinPitch * SCALE;
        originX = x;
        originY = y;
        originZ = z;
        int top = sneaking ? 0 : text.equals("deadmau5") ? -10 : 0;
        float lift = sneaking ? 9.374999F : 0f;
        int half = font.width(text) / 2;
        int left = -half;
        // The plate: 1 pixel around the text, over everything for a standing tag.
        quads.start(sneaking ? QuadRenderer.Mode.ALPHA : QuadRenderer.Mode.OVERLAY, white);
        plate(-half - 1, -1 + top + lift, half + 1, 8 + top + lift);
        if (sneaking) {
            text(text, left, top + lift, FAINT, QuadRenderer.Mode.ALPHA);
        } else {
            text(text, left, top, FAINT, QuadRenderer.Mode.OVERLAY);
            text(text, left, top, 0xFFFFFFFF, QuadRenderer.Mode.ALPHA);
        }
    }

    private void plate(float left, float top, float right, float bottom) {
        quads.quad(wx(left, top), wy(left, top), wz(left, top), 0f, 0f, wx(right, top), wy(right, top),
                wz(right, top), 1f, 0f, wx(right, bottom), wy(right, bottom), wz(right, bottom), 1f, 1f,
                wx(left, bottom), wy(left, bottom), wz(left, bottom), 0f, 1f, PLATE);
    }

    /** Draws the text's glyphs page by page and its bars, in {@code mode}. */
    private void text(String text, float x, float y, int argb, QuadRenderer.Mode mode) {
        java.util.Arrays.fill(seen, false);
        int next = VanillaFont.ASCII_PAGE;
        while (next != Integer.MAX_VALUE) {
            VulkanTexture texture = next == VanillaFont.ASCII_PAGE ? ascii : pageTextures.get(next);
            page = next;
            barCount = 0;
            if (texture != null) {
                quads.start(mode, texture);
                font.layout(text, x, y, argb, sink);
                quads.flush();
                if (barCount > 0) {
                    quads.start(mode, white);
                    for (int bar = 0; bar < barCount; bar++) {
                        plateBar(bars[bar * 4], bars[bar * 4 + 1], bars[bar * 4 + 2], bars[bar * 4 + 3],
                                barColors[bar]);
                    }
                    quads.flush();
                }
            } else if (next != VanillaFont.ASCII_PAGE) {
                request(next);
            }
            next = nextPage(next);
        }
    }

    private void plateBar(float left, float top, float right, float bottom, int argb) {
        quads.quad(wx(left, top), wy(left, top), wz(left, top), 0f, 0f, wx(right, top), wy(right, top),
                wz(right, top), 1f, 0f, wx(right, bottom), wy(right, bottom), wz(right, bottom), 1f, 1f,
                wx(left, bottom), wy(left, bottom), wz(left, bottom), 0f, 1f, argb);
    }

    /** The lowest glyph page above {@code after} the layouts met, or MAX_VALUE. */
    private int nextPage(int after) {
        for (int index = after + 2; index < seen.length; index++) {
            if (seen[index]) {
                return index - 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void request(int number) {
        if (!failed.contains(number) && !pending.contains(number)) {
            pending.add(number);
        }
    }

    void end() {
        quads.flush();
    }

    private float wx(float px, float py) {
        return originX + rightX * px + downX * py;
    }

    private float wy(float px, float py) {
        return originY + rightY * px + downY * py;
    }

    private float wz(float px, float py) {
        return originZ + rightZ * px + downZ * py;
    }

    @Override
    public void close() {
        for (VulkanTexture texture : pageTextures.values()) {
            texture.close();
        }
        if (ascii != null) {
            ascii.close();
        }
        if (white != null) {
            white.close();
        }
    }
}
