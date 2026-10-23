package alkaline.renderer.vulkan.ui;

import alkaline.renderer.vulkan.background.MenuBackground;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanDynamicTexture;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * Layers a menu frame: background scene, frosted glass under declared UI surfaces, caller-drawn 3D content,
 * then the Compose layer.
 *
 * <p>Frosted glass uses a four-level dual-filter (Kawase) blur of the scene. Each level halves resolution, so
 * the whole chain touches about a third of the screen's pixels once, versus a CPU Skia blur that would have to
 * read the GPU scene back every frame.
 *
 * <p>Two things keep it cheaper than that in game. Each pass draws only rectangles around the glass regions,
 * padded by how far the passes after it sample, so a few HUD pills blur a few small patches instead of the
 * screen. And the blur is part of the HUD cache: it is refreshed at the {@linkplain #cacheRate HUD rate} (or
 * when the regions move), and frames in between frost with the last result.
 */
public final class MenuCompositor implements AutoCloseable {
    private static final int GLASS_INSTANCE_BYTES = 28;
    private static final int RECT_BYTES = 16;
    private static final int DOWN_LEVELS = 4;
    private static final int UP_LEVELS = 2;
    private static final int PASSES = DOWN_LEVELS + UP_LEVELS;
    /** Beyond this many padded rectangles a pass blurs its whole target. */
    private static final int MAX_RECTS = 48;
    /** How far a pass samples around a pixel, in source texels: the offset (1.5, doubled when upsampling) plus bilinear. */
    private static final float DOWN_REACH = 2.5f;
    private static final float UP_REACH = 4f;
    /** Diagnostics: {@code -Dalkaline.glass.fullBlur=true} blurs whole targets, to check the padding against. */
    private static final boolean FULL_BLUR = Boolean.getBoolean("alkaline.glass.fullBlur");

    private final VulkanRenderer renderer;
    private final ComposeLayerHost host;
    private final VulkanPipeline blit;
    private final VulkanPipeline down;
    private final VulkanPipeline up;
    private final VulkanPipeline glass;
    private final VulkanPipeline ui;
    private final ByteBuffer push = MemoryUtil.memCalloc(PipelineSpec.MAX_PUSH_CONSTANT_BYTES);
    private final VulkanRenderTarget[] downTargets = new VulkanRenderTarget[DOWN_LEVELS];
    private final VulkanRenderTarget[] upTargets = new VulkanRenderTarget[UP_LEVELS];
    private VulkanRenderTarget scene;
    private VulkanDynamicTexture uiTexture;
    private ComposeLayerHost.Frame pendingUi;
    /** The current UI frame's glass regions, and those drawn (HUD ones only without Frosted surfaces). */
    private List<GlassRegion> allRegions = List.of();
    private List<GlassRegion> glassRegions = List.of();
    private boolean menuGlass = true;
    /** Padding per blur pass, in scene pixels: how far the later passes and the glass sample around a region. */
    private final float[] pads = new float[PASSES];
    private final float[] rects = new float[MAX_RECTS * 4];
    private long blurIntervalNanos = 1_000_000_000L / 60;
    private long lastBlurNanos;
    /** Whether the up chain holds a blur of the current regions. */
    private boolean blurValid;
    private long blurRefreshes;
    private long blurredPixels;
    private java.util.Map<String, SceneAnchor> anchors = java.util.Map.of();
    private boolean uiReady;
    private boolean glassReady;
    private final float[] clear = {0.0044f, 0.0075f, 0.0070f};

    public MenuCompositor(VulkanRenderer renderer, ComposeLayerHost host) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.host = Objects.requireNonNull(host, "host");
        blit = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "blit.frag")
                .sampledImage(true).build());
        down = blurPipeline(renderer, "blur_down.frag");
        up = blurPipeline(renderer, "blur_up.frag");
        glass = renderer.createPipeline(PipelineSpec.builder("glass.vert", "glass.frag")
                .binding(0, GLASS_INSTANCE_BYTES, true)
                .attribute(0, 0, VertexFormat.FLOAT4, 0)
                .attribute(1, 0, VertexFormat.FLOAT1, 16)
                .attribute(2, 0, VertexFormat.UNORM8X4, 20)
                .attribute(3, 0, VertexFormat.FLOAT1, 24)
                .topology(PipelineSpec.Topology.TRIANGLE_STRIP)
                .blend(PipelineSpec.Blend.ALPHA).sampledImage(true).pushConstants(16).build());
        ui = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "ui.frag")
                .blend(PipelineSpec.Blend.PREMULTIPLIED).sampledImage(true).pushConstants(4).build());
    }

    private static VulkanPipeline blurPipeline(VulkanRenderer renderer, String fragment) {
        return renderer.createPipeline(PipelineSpec.builder("blur_rect.vert", fragment)
                .binding(0, RECT_BYTES, true)
                .attribute(0, 0, VertexFormat.FLOAT4, 0)
                .topology(PipelineSpec.Topology.TRIANGLE_STRIP)
                .offscreen(MenuBackground.SCENE_FORMAT).sampledImage(true).pushConstants(24).build());
    }

    /**
     * The HUD cache rate in Hz: the blur under glass is recomputed at most this often while its regions stay put.
     * Call outside a frame.
     */
    public void cacheRate(int hertz) {
        blurIntervalNanos = 1_000_000_000L / Math.max(1, hertz);
    }

    /**
     * Call once per loop iteration outside a frame: resizes targets to the framebuffer and takes the newest
     * Compose frame, recreating the UI texture if its size changed.
     */
    public void update(int width, int height) {
        if (width > 0 && height > 0 && (scene == null || scene.width() != width || scene.height() != height)) {
            recreateTargets(width, height);
        }
        ComposeLayerHost.Frame next = host.poll();
        if (next == null) {
            return;
        }
        int[] bands = next.getRowBands();
        if (pendingUi != null) {
            // The host counts a polled frame as delivered: its changed rows must ride along with the newer one.
            bands = mergeBands(pendingBands, bands, next.getHeight());
            host.release(pendingUi);
        }
        pendingUi = next;
        pendingBands = bands;
        if (uiTexture == null || uiTexture.width() != next.getWidth() || uiTexture.height() != next.getHeight()) {
            if (uiTexture != null) {
                uiTexture.close();
            }
            uiTexture = renderer.createDynamicTexture(next.getWidth(), next.getHeight(), false, false);
            uiReady = false;
            pendingBands = null;
        }
    }

    /** Linear color shown while no background is drawn (before the loading screen is up). */
    public void clearColor(float[] linearRgb) {
        clear[0] = linearRgb[0];
        clear[1] = linearRgb[1];
        clear[2] = linearRgb[2];
    }

    /**
     * Records the background, blur chain, and UI upload; call before {@link VulkanFrame#beginMainPass}.
     * {@code menuGlass} is the Frosted surfaces setting: without it only HUD elements that ask for blur get it.
     */
    public void prepare(VulkanFrame frame, MenuBackground background, float partialTick, boolean menuGlass) {
        if (pendingUi != null) {
            uiUploads++;
            frame.upload(uiTexture, pendingUi.getPixels(), pendingBands);
            uploadedRows += pendingBands == null ? pendingUi.getHeight() : rows(pendingBands);
            setRegions(pendingUi.getGlass(), menuGlass);
            anchors = pendingUi.getAnchors();
            host.release(pendingUi);
            pendingUi = null;
            uiReady = true;
        } else if (menuGlass != this.menuGlass) {
            setRegions(allRegions, menuGlass);
        }
        if (scene == null) {
            return;
        }
        if (background != null) {
            background.render(frame, scene, partialTick);
        } else {
            // The theme surface: matches the loading screen and the main-pass clear.
            frame.beginRendering(scene, true, clear[0], clear[1], clear[2], 1f);
            frame.endRendering();
        }
        glassReady = !glassRegions.isEmpty() && uiTexture != null;
        long now = System.nanoTime();
        if (glassReady && (!blurValid || now - lastBlurNanos >= blurIntervalNanos)) {
            blur(frame);
            // Keep the cadence (frames rarely land on the interval) but never bank time across a pause.
            lastBlurNanos = blurValid ? Math.max(lastBlurNanos + blurIntervalNanos, now - blurIntervalNanos) : now;
            blurValid = true;
        }
    }

    /** Takes a UI frame's glass regions; a moved, added or removed region invalidates the cached blur. */
    private void setRegions(List<GlassRegion> regions, boolean menuGlass) {
        allRegions = regions;
        this.menuGlass = menuGlass;
        List<GlassRegion> shown = regions;
        if (!menuGlass) {
            shown = new java.util.ArrayList<>(regions.size());
            for (GlassRegion region : regions) {
                if (region.getHud()) {
                    shown.add(region);
                }
            }
        }
        if (!sameGeometry(shown, glassRegions)) {
            blurValid = false;
        }
        glassRegions = shown;
    }

    private static boolean sameGeometry(List<GlassRegion> first, List<GlassRegion> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            GlassRegion a = first.get(index);
            GlassRegion b = second.get(index);
            if (a.getLeft() != b.getLeft() || a.getTop() != b.getTop() || a.getRight() != b.getRight()
                    || a.getBottom() != b.getBottom()) {
                return false;
            }
        }
        return true;
    }

    /** Records the blur chain over padded rectangles around the glass regions. */
    private void blur(VulkanFrame frame) {
        float scaleX = scene.width() / (float) uiTexture.width();
        float scaleY = scene.height() / (float) uiTexture.height();
        blurRefreshes++;
        for (int pass = 0; pass < PASSES; pass++) {
            VulkanRenderTarget target = pass < DOWN_LEVELS ? downTargets[pass] : upTargets[pass - DOWN_LEVELS];
            VulkanRenderTarget source = pass == 0 ? scene
                    : pass <= DOWN_LEVELS ? downTargets[pass - 1] : upTargets[pass - DOWN_LEVELS - 1];
            int count = padRects(glassRegions, scaleX, scaleY, pads[pass], scene.width(), scene.height(), rects);
            long offset = frame.allocate((long) count * RECT_BYTES, 16);
            if (offset < 0) {
                blurValid = false;
                return;
            }
            ByteBuffer stream = frame.stream();
            for (int index = 0; index < count * 4; index += 2) {
                // Pixels to the target's normalized coordinates.
                stream.putFloat((int) offset + index * 4, rects[index] / scene.width());
                stream.putFloat((int) offset + index * 4 + 4, rects[index + 1] / scene.height());
            }
            for (int index = 0; index < count; index++) {
                blurredPixels += (long) ((rects[index * 4 + 2] - rects[index * 4]) / scene.width() * target.width()
                        * (rects[index * 4 + 3] - rects[index * 4 + 1]) / scene.height() * target.height());
            }
            frame.beginRendering(target, false, 0f, 0f, 0f, 1f);
            frame.bindPipeline(pass < DOWN_LEVELS ? down : up);
            frame.bindImage(source);
            push.clear();
            push.putFloat(0, 1f / source.width());
            push.putFloat(4, 1f / source.height());
            push.putFloat(8, 1.5f);
            push.putFloat(16, 1f / target.width());
            push.putFloat(20, 1f / target.height());
            push.limit(24);
            frame.pushConstants(push);
            frame.bindStreamVertices(0, offset);
            frame.draw(4, count, 0, 0);
            frame.endRendering();
        }
    }

    /**
     * Fills {@code out} with the regions (UI pixels, scaled to scene pixels) padded by {@code pad} and clamped to
     * the scene, merging any that overlap so no pixel is blurred twice; returns how many rectangles there are.
     * More than {@link #MAX_RECTS} collapse into the whole scene.
     */
    static int padRects(List<GlassRegion> regions, float scaleX, float scaleY, float pad, int width, int height,
                        float[] out) {
        if (regions.size() > MAX_RECTS || FULL_BLUR) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = width;
            out[3] = height;
            return 1;
        }
        int count = 0;
        for (GlassRegion region : regions) {
            out[count * 4] = Math.max(0f, (float) Math.floor(region.getLeft() * scaleX - pad));
            out[count * 4 + 1] = Math.max(0f, (float) Math.floor(region.getTop() * scaleY - pad));
            out[count * 4 + 2] = Math.min(width, (float) Math.ceil(region.getRight() * scaleX + pad));
            out[count * 4 + 3] = Math.min(height, (float) Math.ceil(region.getBottom() * scaleY + pad));
            if (out[count * 4] < out[count * 4 + 2] && out[count * 4 + 1] < out[count * 4 + 3]) {
                count++;
            }
        }
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int first = 0; first < count && !merged; first++) {
                for (int second = first + 1; second < count; second++) {
                    int a = first * 4;
                    int b = second * 4;
                    if (out[a] <= out[b + 2] && out[b] <= out[a + 2] && out[a + 1] <= out[b + 3] && out[b + 1] <= out[a + 3]) {
                        out[a] = Math.min(out[a], out[b]);
                        out[a + 1] = Math.min(out[a + 1], out[b + 1]);
                        out[a + 2] = Math.max(out[a + 2], out[b + 2]);
                        out[a + 3] = Math.max(out[a + 3], out[b + 3]);
                        count--;
                        System.arraycopy(out, count * 4, out, b, 4);
                        merged = true;
                        break;
                    }
                }
            }
        }
        return count;
    }

    /** Draws the scene and frosted glass; call first in the main pass. */
    public void drawBackdrop(VulkanFrame frame) {
        if (scene == null) {
            return;
        }
        frame.bindPipeline(blit);
        frame.bindImage(scene);
        frame.draw(3, 1, 0, 0);
        if (!glassReady || !blurValid) {
            return;
        }
        List<GlassRegion> regions = glassRegions;
        long offset = frame.allocate((long) regions.size() * GLASS_INSTANCE_BYTES, 16);
        if (offset < 0) {
            return;
        }
        ByteBuffer stream = frame.stream();
        for (int index = 0; index < regions.size(); index++) {
            GlassRegion region = regions.get(index);
            int position = (int) offset + index * GLASS_INSTANCE_BYTES;
            stream.putFloat(position, region.getLeft());
            stream.putFloat(position + 4, region.getTop());
            stream.putFloat(position + 8, region.getRight());
            stream.putFloat(position + 12, region.getBottom());
            stream.putFloat(position + 16, region.getCornerRadius());
            int argb = region.getTint();
            stream.putInt(position + 20, (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16));
            stream.putFloat(position + 24, region.getOpacity());
        }
        push.clear();
        push.putFloat(0, frame.width());
        push.putFloat(4, frame.height());
        push.putFloat(8, frame.width() / (float) uiTexture.width());
        push.putFloat(12, frame.height() / (float) uiTexture.height());
        push.limit(16);
        frame.bindPipeline(glass);
        frame.bindImage(upTargets[UP_LEVELS - 1]);
        frame.pushConstants(push);
        frame.bindStreamVertices(0, offset);
        frame.draw(4, regions.size(), 0, 0);
    }

    /**
     * Anchor laid out by the UI frame currently on screen, scaled to framebuffer pixels, or null. Lets 3D
     * content follow the Compose layout instead of hard-coded positions.
     */
    public SceneAnchor anchor(String name, int framebufferWidth, int framebufferHeight) {
        SceneAnchor anchor = anchors.get(name);
        if (anchor == null || uiTexture == null) {
            return null;
        }
        float scaleX = framebufferWidth / (float) uiTexture.width();
        float scaleY = framebufferHeight / (float) uiTexture.height();
        return new SceneAnchor(anchor.getLeft() * scaleX, anchor.getTop() * scaleY,
                anchor.getRight() * scaleX, anchor.getBottom() * scaleY);
    }

    private long uiUploads;
    private long uploadedRows;
    /** Rows of the pending frame to upload, or null for all of them. */
    private int[] pendingBands;

    /** Rows uploaded from Compose frames so far (diagnostics: compare with frames times the height). */
    public long uploadedRows() {
        return uploadedRows;
    }

    private static int rows(int[] bands) {
        int rows = 0;
        for (int index = 0; index + 1 < bands.length; index += 2) {
            rows += bands[index + 1] - bands[index];
        }
        return rows;
    }

    /** The union of two band lists over {@code height} rows; null (everything) when either is. */
    private static int[] mergeBands(int[] first, int[] second, int height) {
        if (first == null || second == null) {
            return null;
        }
        boolean[] rows = new boolean[height];
        for (int[] bands : new int[][]{first, second}) {
            for (int index = 0; index + 1 < bands.length; index += 2) {
                for (int row = Math.max(0, bands[index]); row < Math.min(height, bands[index + 1]); row++) {
                    rows[row] = true;
                }
            }
        }
        int[] merged = new int[height + 1];
        int count = 0;
        for (int row = 0; row < height; ) {
            if (!rows[row]) {
                row++;
                continue;
            }
            merged[count++] = row;
            while (row < height && rows[row]) {
                row++;
            }
            merged[count++] = row;
        }
        return java.util.Arrays.copyOf(merged, count);
    }

    /** Times the blur chain ran (diagnostics: compare with frames to see the HUD cache at work). */
    public long blurRefreshes() {
        return blurRefreshes;
    }

    /** Target pixels the blur passes covered so far, against {@link #fullBlurPixels()} per refresh. */
    public long blurredPixels() {
        return blurredPixels;
    }

    /** Target pixels one unrestricted run of the blur chain covers at the current size. */
    public long fullBlurPixels() {
        long total = 0;
        for (VulkanRenderTarget target : downTargets) {
            total += target == null ? 0 : (long) target.width() * target.height();
        }
        for (VulkanRenderTarget target : upTargets) {
            total += target == null ? 0 : (long) target.width() * target.height();
        }
        return total;
    }

    /** Compose frames uploaded so far (diagnostics: each is a full-surface texture upload). */
    public long uiUploads() {
        return uiUploads;
    }

    /** True once a Compose frame has been uploaded, so the UI layer covers whatever the scene shows. */
    public boolean uiPresented() {
        return uiReady;
    }

    /** Composites the Compose layer over everything; call last in the main pass. */
    public void drawUi(VulkanFrame frame) {
        if (!uiReady || uiTexture == null) {
            return;
        }
        push.clear();
        push.putInt(0, renderer.presentsSrgb() ? 1 : 0);
        push.limit(4);
        frame.bindPipeline(ui);
        frame.bindImage(uiTexture);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
    }

    private void recreateTargets(int width, int height) {
        closeTargets();
        scene = renderer.createRenderTarget(width, height, MenuBackground.SCENE_FORMAT, true, false);
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < DOWN_LEVELS; level++) {
            levelWidth = Math.max(1, levelWidth / 2);
            levelHeight = Math.max(1, levelHeight / 2);
            downTargets[level] = renderer.createRenderTarget(levelWidth, levelHeight, MenuBackground.SCENE_FORMAT,
                    true, false);
        }
        for (int level = 0; level < UP_LEVELS; level++) {
            VulkanRenderTarget larger = downTargets[DOWN_LEVELS - 2 - level];
            upTargets[level] = renderer.createRenderTarget(larger.width(), larger.height(),
                    MenuBackground.SCENE_FORMAT, true, false);
        }
        // Work back from the glass, which reads the last target bilinearly (plus a pixel of antialiasing): each
        // pass needs its source valid as far out as it samples, and its own coverage rounds out by a texel.
        float[] texel = new float[PASSES];
        for (int pass = 0; pass < PASSES; pass++) {
            VulkanRenderTarget target = pass < DOWN_LEVELS ? downTargets[pass] : upTargets[pass - DOWN_LEVELS];
            texel[pass] = Math.max(width / (float) target.width(), height / (float) target.height());
        }
        pads[PASSES - 1] = 1f + texel[PASSES - 1];
        for (int pass = PASSES - 2; pass >= 0; pass--) {
            float reach = pass + 1 < DOWN_LEVELS ? DOWN_REACH : UP_REACH;
            pads[pass] = pads[pass + 1] + reach * texel[pass] + texel[pass + 1];
        }
        blurValid = false;
    }

    private void closeTargets() {
        for (int level = 0; level < UP_LEVELS; level++) {
            if (upTargets[level] != null) {
                upTargets[level].close();
                upTargets[level] = null;
            }
        }
        for (int level = 0; level < DOWN_LEVELS; level++) {
            if (downTargets[level] != null) {
                downTargets[level].close();
                downTargets[level] = null;
            }
        }
        if (scene != null) {
            scene.close();
            scene = null;
        }
    }

    @Override
    public void close() {
        if (pendingUi != null) {
            host.release(pendingUi);
            pendingUi = null;
        }
        closeTargets();
        if (uiTexture != null) {
            uiTexture.close();
        }
        ui.close();
        glass.close();
        up.close();
        down.close();
        blit.close();
        MemoryUtil.memFree(push);
    }
}
