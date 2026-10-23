package alkaline.platform.sdl;

import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.sdl.SDLMain.SDL_SetMainReady;
import static org.lwjgl.sdl.SDLVersion.SDL_GetVersion;
import static org.lwjgl.sdl.SDLVersion.SDL_VERSIONNUM_MAJOR;
import static org.lwjgl.sdl.SDLVersion.SDL_VERSIONNUM_MICRO;
import static org.lwjgl.sdl.SDLVersion.SDL_VERSIONNUM_MINOR;
import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowID;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;
import static org.lwjgl.sdl.SDLVideo.SDL_ShowWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_VULKAN;

/**
 * Owns SDL and one Vulkan window in a dedicated process. Open, use, and close it
 * on the application's main thread. A Vulkan surface must be destroyed before this window.
 * A real graphical desktop session is required.
 */
public final class SdlWindow implements AutoCloseable {
    private static boolean claimed;

    private final Thread owner;
    private long handle;

    private SdlWindow(long handle) {
        this.owner = Thread.currentThread();
        this.handle = handle;
    }

    public static synchronized SdlWindow openHidden(String title, int width, int height) {
        return open(title, width, height, true);
    }

    public static synchronized SdlWindow openVisible(String title, int width, int height) {
        return open(title, width, height, false);
    }

    /** Which window system SDL uses on Linux. Other platforms have one and ignore it. */
    public enum WindowSystem {
        /** Native Wayland on a Wayland desktop (X11 if that fails), X11 elsewhere. */
        AUTO,
        WAYLAND,
        X11
    }

    /**
     * Opens the visible window on {@code system}. SDL3 picks X11 (so XWayland) on desktops whose compositor lacks
     * the Wayland FIFO protocol; native Wayland avoids XWayland's emulated present modes (decision 0009). An
     * explicit SDL_VIDEODRIVER in the environment still wins, for testing.
     */
    public static synchronized SdlWindow openVisible(String title, int width, int height, WindowSystem system) {
        org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_APP_ID, "alkaline");
        String forced = System.getenv("SDL_VIDEODRIVER");
        if (forced == null || forced.isEmpty()) {
            String drivers = switch (system) {
                case AUTO -> waylandDesktop() ? "wayland,x11" : null;
                case WAYLAND -> "wayland";
                case X11 -> "x11";
            };
            if (drivers != null) {
                org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_VIDEO_DRIVER, drivers);
            }
        }
        return open(title, width, height, false);
    }

    /** A Wayland session: the compositor's socket is advertised. */
    private static boolean waylandDesktop() {
        String wayland = System.getenv("WAYLAND_DISPLAY");
        return wayland != null && !wayland.isEmpty();
    }

    private static SdlWindow open(String title, int width, int height, boolean hidden) {
        if (claimed) {
            throw new IllegalStateException("An Alkaline SDL window already owns this process.");
        }
        if (title == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A title and positive window dimensions are required.");
        }
        SDL_SetMainReady();
        long window = 0;
        try {
            if (!SDL_Init(SDL_INIT_VIDEO)) {
                throw sdlFailure("SDL_Init");
            }
            // Visible windows are user-resizable and request full pixel density; callers size swapchains and
            // input from pixelSize(), not the point-based window size.
            long flags = SDL_WINDOW_VULKAN | (hidden ? SDL_WINDOW_HIDDEN
                    : org.lwjgl.sdl.SDLVideo.SDL_WINDOW_RESIZABLE | org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
            window = SDL_CreateWindow(title, width, height, flags);
            if (window == 0) {
                throw sdlFailure("SDL_CreateWindow");
            }
            SdlWindow result = new SdlWindow(window);
            claimed = true;
            return result;
        } catch (RuntimeException | Error failure) {
            if (window != 0) {
                SDL_DestroyWindow(window);
            }
            SDL_Quit();
            throw failure;
        }
    }

    public long handle() {
        requireOwner();
        if (handle == 0) {
            throw new IllegalStateException("The SDL window is closed.");
        }
        return handle;
    }

    public int windowId() {
        long window = handle();
        int id = SDL_GetWindowID(window);
        if (id == 0) {
            throw sdlFailure("SDL_GetWindowID");
        }
        return id;
    }

    public void show() {
        if (!SDL_ShowWindow(handle())) {
            throw sdlFailure("SDL_ShowWindow");
        }
    }

    /** Returns the drawable pixel dimensions, which may differ from logical window units on high-DPI displays. */
    public PixelSize pixelSize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.callocInt(1);
            IntBuffer height = stack.callocInt(1);
            if (!SDL_GetWindowSizeInPixels(handle(), width, height)) {
                throw sdlFailure("SDL_GetWindowSizeInPixels");
            }
            return new PixelSize(width.get(0), height.get(0));
        }
    }

    public record PixelSize(int width, int height) {
        public PixelSize {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("SDL window pixel dimensions must be positive.");
            }
        }
    }

    /** Refresh rate in Hz of the display the window is on, or 0 when SDL cannot tell. */
    public float refreshRate() {
        int display = org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(handle());
        if (display == 0) {
            return 0f;
        }
        org.lwjgl.sdl.SDL_DisplayMode mode = org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode(display);
        return mode == null ? 0f : Math.max(0f, mode.refresh_rate());
    }

    /**
     * Whether the window is an X11 window on a Wayland desktop (XWayland). There the Wayland compositor presents
     * every window itself, so an X11 client's immediate presents already show only the newest frame at each refresh,
     * while the X11 mailbox mode is emulated and holds a client to two frames per refresh.
     */
    public boolean xwayland() {
        return "x11".equals(videoDriver()) && waylandDesktop();
    }

    public String videoDriver() {
        handle();
        return SDL_GetCurrentVideoDriver();
    }

    public String runtimeVersion() {
        handle();
        int version = SDL_GetVersion();
        return SDL_VERSIONNUM_MAJOR(version) + "." + SDL_VERSIONNUM_MINOR(version)
                + "." + SDL_VERSIONNUM_MICRO(version);
    }

    @Override
    public void close() {
        requireOwner();
        synchronized (SdlWindow.class) {
            if (handle == 0) {
                return;
            }
            try {
                SDL_DestroyWindow(handle);
            } finally {
                handle = 0;
                try {
                    SDL_Quit();
                } finally {
                    claimed = false;
                }
            }
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("SDL window access must stay on its creating thread.");
        }
    }

    private static IllegalStateException sdlFailure(String operation) {
        return new IllegalStateException(operation + " failed: " + SDL_GetError());
    }
}
