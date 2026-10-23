package alkaline.renderer.vulkan.background;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Minecraft 1.8.9 title-screen panorama on Vulkan. Behavior follows the vanilla GuiMainMenu: the six
 * {@code panorama_N.png} faces on a unit cube, a 120 degree camera that pitches by {@code sin(t/400)*25+20}
 * and yaws by {@code -t*0.1} degrees ({@code t} in ticks), 8x8 jittered renders at 256x256, seven
 * rotate-and-blur passes, and a stretched composite under two gradients.
 *
 * <p>Cost differs: vanilla issues 384 cube-face draws and 7 framebuffer copies per frame; this records three
 * full-screen passes (two at 256x256) with no copies and no per-frame allocation.
 */
public final class PanoramaBackground implements MenuBackground {
    public static final String ID = "panorama";
    private static final int SIZE = 256;
    private static final String FACE_PATH = "assets/minecraft/textures/gui/title/background/panorama_%d.png";
    private static final float TAN_HALF_FOV = (float) Math.tan(Math.toRadians(60.0));

    private final VulkanRenderer renderer;
    private final Supplier<PanoramaSettings> settings;
    private final VulkanTexture faces;
    private final VulkanRenderTarget cube;
    private final VulkanRenderTarget blurred;
    private final VulkanPipeline cubePipeline;
    private final VulkanPipeline blurPipeline;
    private final VulkanPipeline compositePipeline;
    private final ByteBuffer push;
    private int timer;
    private int kernelPasses = -1;
    private PanoramaKernel kernel;

    private PanoramaBackground(VulkanRenderer renderer, Supplier<PanoramaSettings> settings, List<RgbaImage> images) {
        this.renderer = renderer;
        this.settings = settings;
        List<AutoCloseable> created = new ArrayList<>();
        try {
            // Vanilla binds panorama faces with GL_NEAREST and repeat, and does its math on encoded values.
            faces = track(created, renderer.createTextureArray(images,
                    new TextureOptions(ImageSampling.NEAREST, false, true)));
            cube = track(created, renderer.createRenderTarget(SIZE, SIZE, RenderTargetFormat.RGBA8_UNORM, false, true));
            blurred = track(created, renderer.createRenderTarget(SIZE, SIZE, RenderTargetFormat.RGBA8_UNORM, true, false));
            cubePipeline = track(created, renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "panorama_cube.frag")
                    .offscreen(RenderTargetFormat.RGBA8_UNORM).sampledImage(true).pushConstants(80).build()));
            blurPipeline = track(created, renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "panorama_blur.frag")
                    .offscreen(RenderTargetFormat.RGBA8_UNORM).sampledImage(true).pushConstants(112).build()));
            compositePipeline = track(created, renderer.createPipeline(
                    PipelineSpec.builder("fullscreen.vert", "panorama_composite.frag")
                            .offscreen(SCENE_FORMAT).sampledImage(true).pushConstants(80).build()));
            push = MemoryUtil.memCalloc(PipelineSpec.MAX_PUSH_CONSTANT_BYTES);
        } catch (RuntimeException | Error failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    /** Loads the six faces from the given assets; call outside a frame. */
    public static PanoramaBackground load(VulkanRenderer renderer, AssetSource assets,
                                          Supplier<PanoramaSettings> settings) throws IOException {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(settings, "settings");
        List<RgbaImage> images = new ArrayList<>(6);
        for (int face = 0; face < 6; face++) {
            String path = String.format(FACE_PATH, face);
            try (InputStream input = assets.open(path)) {
                if (input == null) {
                    throw new IOException("Missing panorama face " + path + " in " + assets.describe());
                }
                images.add(ImageDecoder.decode(input));
            }
        }
        RgbaImage first = images.getFirst();
        for (RgbaImage image : images) {
            if (image.width() != first.width() || image.height() != first.height()) {
                throw new IOException("Panorama faces must share one size.");
            }
        }
        return new PanoramaBackground(renderer, settings, images);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        timer++;
    }

    @Override
    public void render(VulkanFrame frame, VulkanRenderTarget scene, float partialTick) {
        PanoramaSettings current = settings.get();
        double time = (timer + partialTick) * current.speed();
        double pitch = Math.toRadians(Math.sin(time / 400.0) * 25.0 + 20.0);
        double yaw = Math.toRadians(-time * 0.1);

        frame.beginRendering(cube, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(cubePipeline);
        frame.bindImage(faces);
        push.clear();
        writeInverseOrbit(push, pitch, yaw);
        push.putInt(64, current.samplesPerAxis());
        push.putFloat(68, 1f / 64f);
        push.putFloat(72, TAN_HALF_FOV);
        push.putFloat(76, SIZE);
        push.limit(80);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();

        if (kernelPasses != current.blurPasses()) {
            kernel = PanoramaKernel.forPasses(current.blurPasses());
            kernelPasses = current.blurPasses();
        }
        frame.beginRendering(blurred, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(blurPipeline);
        frame.bindImage(cube);
        push.clear();
        MemoryUtil.memSet(push, 0);
        push.putInt(0, SIZE);
        push.putInt(4, 0);
        push.putInt(8, kernel.columnWeights.length);
        push.putInt(12, kernel.rowWeights.length);
        int offset = 16;
        for (float weight : kernel.columnWeights) {
            push.putFloat(offset, weight);
            offset += 4;
        }
        for (float weight : kernel.rowWeights) {
            push.putFloat(offset, weight);
            offset += 4;
        }
        push.limit(112);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();

        float width = scene.width();
        float height = scene.height();
        float scale = width > height ? 120f / width : 120f / height;
        push.clear();
        putColor(push, 0, current.overlayTopA());
        putColor(push, 16, current.overlayBottomA());
        putColor(push, 32, current.overlayTopB());
        putColor(push, 48, current.overlayBottomB());
        push.putFloat(64, height * scale / SIZE);
        push.putFloat(68, width * scale / SIZE);
        push.putInt(72, 1); // the sRGB scene target re-encodes
        push.putInt(76, 0);
        push.limit(80);
        frame.beginRendering(scene, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(compositePipeline);
        frame.bindImage(blurred);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
    }

    @Override
    public void close() {
        compositePipeline.close();
        blurPipeline.close();
        cubePipeline.close();
        blurred.close();
        cube.close();
        faces.close();
        MemoryUtil.memFree(push);
    }

    /** Column-major (Rx(pitch) * Ry(yaw))^-1 = Ry(-yaw) * Rx(-pitch), padded to a mat4. */
    static void writeInverseOrbit(ByteBuffer target, double pitch, double yaw) {
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);
        // Ry(-yaw) = [cy 0 -sy; 0 1 0; sy 0 cy], Rx(-pitch) = [1 0 0; 0 cp sp; 0 -sp cp]
        float[] m = {
                cy, 0f, sy, 0f,                 // column 0
                sy * sp, cp, -cy * sp, 0f,      // column 1
                -sy * cp, sp, cy * cp, 0f,      // column 2
                0f, 0f, 0f, 1f,
        };
        for (int index = 0; index < 16; index++) {
            target.putFloat(index * 4, m[index]);
        }
    }

    private static void putColor(ByteBuffer target, int offset, int argb) {
        target.putFloat(offset, ((argb >>> 16) & 0xFF) / 255f);
        target.putFloat(offset + 4, ((argb >>> 8) & 0xFF) / 255f);
        target.putFloat(offset + 8, (argb & 0xFF) / 255f);
        target.putFloat(offset + 12, (argb >>> 24) / 255f);
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
        created.add(resource);
        return resource;
    }
}
