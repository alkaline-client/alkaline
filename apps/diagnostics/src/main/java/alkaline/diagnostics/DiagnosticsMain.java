package alkaline.diagnostics;

import alkaline.client.Minecraft189;
import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.vulkan.DeviceReport;
import alkaline.renderer.vulkan.VulkanApiSupport;
import alkaline.renderer.vulkan.VulkanProbe;
import alkaline.renderer.vulkan.resource.VulkanMesh;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.util.List;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_PollEvent;

/** Exercises SDL + Vulkan capability, finite frame, and interactive preview paths. */
public final class DiagnosticsMain {
    private DiagnosticsMain() {
    }

    public static void main(String[] args) {
        int status;
        try {
            if (args.length == 0) {
                status = runProbe();
            } else if (args.length == 1 && "--frame-smoke".equals(args[0])) {
                status = runFrameSmoke();
            } else if (args.length == 1 && "--frame-preview".equals(args[0])) {
                status = runFramePreview();
            } else if (args.length >= 1 && "--click-test".equals(args[0])) {
                status = ClickTest.run(args.length > 1 ? Integer.parseInt(args[1]) : 10);
            } else if (args.length >= 1 && "--click-fix".equals(args[0])) {
                status = ClickTest.fix(args.length > 1 ? args[1] : "status");
            } else if (args.length >= 2 && "--connect".equals(args[0])) {
                status = ConnectTest.run(args[1], args.length > 2 ? args[2] : "Alkaline",
                        args.length > 3 ? Integer.parseInt(args[3]) : 6);
            } else if (args.length >= 1 && "--block-gallery".equals(args[0])) {
                int view = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                java.nio.file.Path shot = args.length > 3 && "--screenshot".equals(args[2])
                        ? java.nio.file.Path.of(args[3]) : null;
                status = BlockGallery.run(view, shot);
            } else if (args.length >= 2 && "--held-item".equals(args[0])) {
                status = runHeldItem(args);
            } else {
                System.err.println("Usage: ./gradlew :apps:diagnostics:run [--args=--frame-smoke|--frame-preview"
                        + "|\"--click-test [seconds]\"|\"--click-fix [status|install|remove]\"|\"--block-gallery [view] [--screenshot <png>]\"|\"--connect <host:port> [name]\"|\"--held-item <item> [--vanilla-mesh] [--glint] [--swing <0..1>] [--zoom <factor>] [--screenshot <png>]\"]");
                status = 2;
            }
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("Graphics diagnostic failed: " + failure.getMessage());
            failure.printStackTrace(System.err);
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    private static int runHeldItem(String[] args) {
        boolean vanilla = false;
        boolean glint = false;
        float swing = 0f;
        float zoom = 1f;
        java.nio.file.Path screenshot = null;
        for (int index = 2; index < args.length; index++) {
            switch (args[index]) {
                case "--vanilla-mesh" -> vanilla = true;
                case "--glint" -> glint = true;
                case "--swing" -> swing = Float.parseFloat(args[++index]);
                case "--zoom" -> zoom = Float.parseFloat(args[++index]);
                case "--screenshot" -> screenshot = java.nio.file.Path.of(args[++index]);
                default -> throw new IllegalArgumentException("Unknown held-item option " + args[index]);
            }
        }
        try {
            return HeldItemPreview.run(new HeldItemPreview.Options(args[1], vanilla, glint, swing, zoom, screenshot));
        } catch (java.io.IOException failure) {
            System.err.println("Held-item preview failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int runProbe() {
        try (SdlWindow window = SdlWindow.openHidden("Alkaline " + Minecraft189.VERSION
                + " graphics diagnostic", 640, 480)) {
            System.out.println("Alkaline " + Minecraft189.VERSION + " / protocol " + Minecraft189.PROTOCOL);
            System.out.println("SDL " + window.runtimeVersion() + " video driver: " + window.videoDriver());
            System.out.println("Required Vulkan API: 1.3 (no older-API fallback)");
            List<DeviceReport> reports = VulkanProbe.inspect(window);
            if (reports.isEmpty()) {
                System.err.println("No Vulkan physical devices were reported.");
                return 1;
            }
            boolean usable = false;
            for (DeviceReport report : reports) {
                System.out.printf("GPU: %s | Vulkan %s | vendor 0x%04x device 0x%04x%n",
                        report.name(), VulkanApiSupport.format(report.apiVersion()), report.vendorId(), report.deviceId());
                System.out.printf("  graphics queue %d; present queue %d; swapchain %s; "
                                + "surface formats %d; present modes %d%n",
                        report.graphicsQueueFamily(), report.presentQueueFamily(),
                        report.swapchainExtension(), report.surfaceFormats().size(),
                        report.presentModes().size());
                System.out.println("  Probe: " + (report.usableForPrototype()
                        ? "Vulkan 1.3 minimum and required surface capabilities present" : report.failure()));
                usable |= report.usableForPrototype();
            }
            System.out.println("No swapchain or frame was created; GPU model and target support "
                    + "require separate qualification.");
            return usable ? 0 : 1;
        }
    }

    private static int runFramePreview() {
        try (SdlWindow window = SdlWindow.openHidden("Alkaline Vulkan frame preview", 960, 640);
             VulkanRenderer renderer = new VulkanRenderer(window);
             VulkanMesh mesh = renderer.createIndexedMesh(
                     new float[]{
                             -0.65f, -0.55f, 0.0f, 1.0f, 0.2f, 0.2f,
                              0.65f, -0.55f, 0.0f, 0.2f, 1.0f, 0.2f,
                              0.0f,   0.65f, 0.0f, 0.2f, 0.3f, 1.0f
                     },
                     new int[]{0, 1, 2})) {
            int windowId = window.windowId();
            System.out.println("Alkaline Vulkan frame preview on " + window.videoDriver()
                    + " / SDL " + window.runtimeVersion() + "; close the window to exit.");
            int presentedFrames = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_Event event = SDL_Event.calloc(stack);
                boolean running = true;
                while (running) {
                    while (SDL_PollEvent(event)) {
                        int type = event.type();
                        if (type == SDL_EVENT_QUIT || (type == SDL_EVENT_WINDOW_CLOSE_REQUESTED
                                && event.window().windowID() == windowId)) {
                            running = false;
                            break;
                        }
                    }
                    if (!running) {
                        break;
                    }
                    long frameStarted = System.nanoTime();
                    if (!renderer.beginFrame()) {
                        sleepForPreview(4_000_000L);
                        continue;
                    }
                    renderer.drawIndexed(mesh);
                    if (renderer.endFrame()) {
                        presentedFrames++;
                        if (presentedFrames == 1) {
                            window.show();
                            System.out.println("Presented first indexed-mesh Vulkan frame; preview window shown.");
                        }
                        sleepForPreview(Math.max(0L, 16_666_667L - (System.nanoTime() - frameStarted)));
                    } else {
                        sleepForPreview(4_000_000L);
                    }
                }
            }
            System.out.println("Preview closed after presenting " + presentedFrames + " indexed-mesh Vulkan frames.");
            return 0;
        }
    }

    private static void sleepForPreview(long nanoseconds) {
        if (nanoseconds <= 0L) {
            return;
        }
        try {
            long milliseconds = nanoseconds / 1_000_000L;
            int remainder = (int) (nanoseconds % 1_000_000L);
            Thread.sleep(milliseconds, remainder);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Frame preview was interrupted.", interrupted);
        }
    }

    private static int runFrameSmoke() {
        try (SdlWindow window = SdlWindow.openHidden("Alkaline Vulkan frame smoke", 640, 480);
             VulkanRenderer renderer = new VulkanRenderer(window);
             VulkanMesh mesh = renderer.createIndexedMesh(
                     new float[]{
                             -0.65f, -0.55f, 0.0f, 1.0f, 0.2f, 0.2f,
                              0.65f, -0.55f, 0.0f, 0.2f, 1.0f, 0.2f,
                              0.0f,   0.65f, 0.0f, 0.2f, 0.3f, 1.0f
                     },
                     new int[]{0, 1, 2})) {
            System.out.println("Alkaline Vulkan frame smoke on " + window.videoDriver()
                    + " / SDL " + window.runtimeVersion());
            float[] translatedScale = {
                    0.5f, 0, 0, 0,
                    0, 0.5f, 0, 0,
                    0, 0, 0.5f, 0,
                    0.35f, 0.0f, 0.0f, 1
            };
            int presentedFrames = 0;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (presentedFrames < 3 && System.nanoTime() < deadline) {
                if (!renderer.beginFrame()) {
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Frame smoke was interrupted.", interrupted);
                    }
                    continue;
                }
                RuntimeException frameFailure = null;
                try {
                    renderer.drawIndexed(mesh);
                    if (presentedFrames == 1) {
                        renderer.drawIndexed(mesh, translatedScale);
                    }
                } catch (RuntimeException failure) {
                    frameFailure = failure;
                }
                try {
                    if (renderer.endFrame()) {
                        presentedFrames++;
                    }
                } catch (RuntimeException endFailure) {
                    if (frameFailure != null) {
                        frameFailure.addSuppressed(endFailure);
                    } else {
                        frameFailure = endFailure;
                    }
                }
                if (frameFailure != null) {
                    throw frameFailure;
                }
            }
            if (presentedFrames != 3) {
                throw new IllegalStateException("Only presented " + presentedFrames + " of 3 smoke frames.");
            }
            System.out.println("Presented " + presentedFrames + " indexed-mesh Vulkan frames.");
            return 0;
        }
    }
}
