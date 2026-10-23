package alkaline.diagnostics;

import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.asset.MinecraftInstallation;
import alkaline.renderer.asset.ZipAssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.math.Mat4;
import alkaline.renderer.model.BakedModel;
import alkaline.renderer.model.FirstPersonItem;
import alkaline.renderer.model.ItemDefinition;
import alkaline.renderer.model.ItemModels;
import alkaline.renderer.vulkan.model.ModelRenderer;
import alkaline.renderer.vulkan.resource.PresentMode;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.world.Lightmap;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

/**
 * Renders a vanilla item held in first person, with Alkaline's gap-free extrusion or vanilla's geometry, for
 * visual checks of the model renderer. Uses the local 1.8.9 jar for the item's model definition and sprite.
 */
final class HeldItemPreview {
    private HeldItemPreview() {
    }

    /** @param zoom magnifies the image around the item's center (1 is vanilla's view) */
    record Options(String item, boolean vanillaMesh, boolean glint, float swing, float zoom, Path screenshot) {
    }

    static int run(Options options) throws IOException {
        Path jar = MinecraftInstallation.locateClientJar()
                .orElseThrow(() -> new IOException("No Minecraft 1.8.9 jar; set ALKALINE_MINECRAFT_JAR."));
        try (AssetSource assets = new ZipAssetSource(jar);
             SdlWindow window = SdlWindow.openVisible("Alkaline held item: " + options.item(), 1280, 720);
             VulkanRenderer renderer = new VulkanRenderer(window, PresentMode.FIFO, false);
             ModelRenderer models = new ModelRenderer(renderer)) {
            ItemDefinition definition = ItemDefinition.load(assets, options.item());
            RgbaImage sprite = firstFrame(assets, definition.layers().getFirst());
            BakedModel baked = options.vanillaMesh() ? ItemModels.vanilla(options.item(), sprite)
                    : ItemModels.extrude(options.item(), sprite);
            ModelRenderer.GpuModel model = models.upload(baked);
            VulkanTexture texture = renderer.createTexture(sprite,
                    new TextureOptions(ImageSampling.NEAREST, true, false, 1));
            VulkanTexture glint = null;
            if (options.glint()) {
                try (InputStream input = assets.open("assets/minecraft/textures/misc/enchanted_item_glint.png")) {
                    if (input == null) {
                        throw new IOException("Missing enchanted_item_glint.png");
                    }
                    glint = renderer.createTexture(ImageDecoder.decode(input),
                            new TextureOptions(ImageSampling.LINEAR, true, true, 1));
                }
            }
            System.out.printf("%s: %d quads (%s geometry)%n", options.item(), baked.vertexCount() / 4,
                    options.vanillaMesh() ? "vanilla" : "Alkaline");
            float[] light0 = new float[3];
            float[] light1 = new float[3];
            FirstPersonItem.lights(20f, 0f, light0, light1);
            models.setLighting(light0[0], light0[1], light0[2], light1[0], light1[1], light1[2], 0.4f);
            int lightRgb = Lightmap.color(15, 0, 1f, 0f, 0f, 0f);
            float[] root = new float[16];
            float[] projection = new float[16];
            AtomicBoolean done = new AtomicBoolean();
            long started = System.nanoTime();
            boolean captureRequested = false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_Event event = SDL_Event.calloc(stack);
                while (!done.get()) {
                    while (SDLEvents.SDL_PollEvent(event)) {
                        if (event.type() == SDLEvents.SDL_EVENT_QUIT
                                || event.type() == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                            done.set(true);
                        }
                    }
                    VulkanFrame frame = renderer.acquireFrame();
                    if (frame == null) {
                        continue;
                    }
                    // Without a screenshot, sweep through a slow swing so moving-edge gaps would show up.
                    double seconds = (System.nanoTime() - started) / 1e9;
                    float swing = options.screenshot() != null ? options.swing()
                            : (float) ((Math.sin(seconds * 0.6) + 1) * 0.5);
                    frame.beginMainPass(0.47f, 0.65f, 1.0f, 1f);
                    frame.setViewport(0, 0, frame.width(), frame.height(), true);
                    Mat4.perspective((float) Math.toRadians(70), frame.width() / (float) frame.height(), 0.05f, 512f,
                            projection);
                    FirstPersonItem.transform(definition.firstPerson(), 0f, swing, 0f, 0f, root);
                    if (options.zoom() != 1f) {
                        magnify(projection, root, options.zoom());
                    }
                    models.setGlint(glint, System.currentTimeMillis());
                    models.begin(frame, projection);
                    models.submit(model, texture, root, 0xFFFFFFFF, 0, lightRgb, glint != null);
                    models.flush();
                    if (options.screenshot() != null && !captureRequested && seconds > 1.0) {
                        captureRequested = renderer.captureNextFrame(image -> {
                            try {
                                writePng(image, options.screenshot());
                                System.out.println("Saved " + options.screenshot());
                            } catch (IOException failure) {
                                System.err.println("Screenshot failed: " + failure.getMessage());
                            }
                            done.set(true);
                        });
                    }
                    renderer.endFrame();
                }
            }
            texture.close();
            if (glint != null) {
                glint.close();
            }
            return 0;
        }
    }

    /** Scales clip space about the projected center of the item's model block, so the item fills the view. */
    private static void magnify(float[] projection, float[] root, float zoom) {
        float[] clip = new float[16];
        Mat4.multiply(projection, root, clip);
        float x = clip[0] * 0.5f + clip[4] * 0.5f + clip[8] * 0.5f + clip[12];
        float y = clip[1] * 0.5f + clip[5] * 0.5f + clip[9] * 0.5f + clip[13];
        float w = clip[3] * 0.5f + clip[7] * 0.5f + clip[11] * 0.5f + clip[15];
        float centerX = x / w;
        float centerY = y / w;
        float[] zoomMatrix = Mat4.identity(new float[16]);
        zoomMatrix[0] = zoom;
        zoomMatrix[5] = zoom;
        // x' = zoom * (x - cx * w), applied in clip space so perspective division stays exact.
        zoomMatrix[12] = 0f;
        zoomMatrix[8] = 0f;
        float[] shift = Mat4.identity(new float[16]);
        shift[8] = 0f;
        float[] result = new float[16];
        Mat4.multiply(zoomMatrix, projection, result);
        for (int column = 0; column < 4; column++) {
            result[column * 4] -= zoom * centerX * projection[column * 4 + 3];
            result[column * 4 + 1] -= zoom * centerY * projection[column * 4 + 3];
        }
        System.arraycopy(result, 0, projection, 0, 16);
    }

    /** The sprite's first square frame (animated items such as the compass are vertical strips). */
    private static RgbaImage firstFrame(AssetSource assets, String texture) throws IOException {
        try (InputStream input = assets.open("assets/minecraft/textures/" + texture + ".png")) {
            if (input == null) {
                throw new IOException("Missing item texture " + texture);
            }
            RgbaImage image = ImageDecoder.decode(input);
            if (image.height() <= image.width()) {
                return image;
            }
            byte[] pixels = image.copyPixels();
            return new RgbaImage(image.width(), image.width(),
                    Arrays.copyOf(pixels, image.width() * image.width() * 4));
        }
    }

    private static void writePng(RgbaImage image, Path destination) throws IOException {
        BufferedImage buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        byte[] pixels = image.copyPixels();
        for (int index = 0; index < image.width() * image.height(); index++) {
            int offset = index * 4;
            buffered.setRGB(index % image.width(), index / image.width(), (pixels[offset + 3] & 0xFF) << 24
                    | (pixels[offset] & 0xFF) << 16 | (pixels[offset + 1] & 0xFF) << 8 | (pixels[offset + 2] & 0xFF));
        }
        if (!ImageIO.write(buffered, "png", destination.toFile())) {
            throw new IOException("No PNG writer available.");
        }
    }
}
