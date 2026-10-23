package alkaline.diagnostics;

import alkaline.platform.linux.LibinputClickFix;
import alkaline.platform.sdl.SdlWindow;
import java.io.IOException;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

/**
 * Counts left clicks as the game receives them (window-system events, as Minecraft reads them) and reports the
 * libinput click fix's state, so a player can compare their click rate with and without it.
 */
final class ClickTest {
    private ClickTest() {
    }

    static int run(int seconds) {
        LibinputClickFix.Status fix = LibinputClickFix.status();
        if (fix == LibinputClickFix.Status.NOT_INSTALLED) {
            System.out.println("libinput debounces mouse buttons here: a release and press within 25 ms of the previous"
                    + " button event are dropped.\nTo turn that off for all mice (asks for an administrator"
                    + " password): --click-fix install");
        } else if (fix == LibinputClickFix.Status.INSTALLED) {
            System.out.println("Click fix installed: libinput does not debounce mice added since it was written.");
        }
        try (SdlWindow window = SdlWindow.openVisible("Alkaline click test: click here", 640, 360)) {
            window.show();
            System.out.println("Click (left button) inside the window for " + seconds + " s.");
            long clicks = 0;
            long lastPress = 0;
            long shortestGap = Long.MAX_VALUE;
            long secondStart = System.nanoTime();
            long end = secondStart + seconds * 1_000_000_000L;
            long clicksAtSecond = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_Event event = SDL_Event.calloc(stack);
                while (System.nanoTime() < end) {
                    while (SDLEvents.SDL_PollEvent(event)) {
                        if (event.type() == SDLEvents.SDL_EVENT_QUIT
                                || event.type() == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                            end = 0;
                        } else if (event.type() == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN
                                && Byte.toUnsignedInt(event.button().button()) == SDLMouse.SDL_BUTTON_LEFT) {
                            long time = event.button().timestamp();
                            if (clicks > 0) {
                                shortestGap = Math.min(shortestGap, time - lastPress);
                            }
                            lastPress = time;
                            clicks++;
                        }
                    }
                    long now = System.nanoTime();
                    if (now - secondStart >= 1_000_000_000L) {
                        if (clicks != clicksAtSecond) {
                            System.out.printf("%3d CPS%n", clicks - clicksAtSecond);
                        }
                        clicksAtSecond = clicks;
                        secondStart = now;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            System.out.printf("Total: %d clicks%s.%n", clicks, shortestGap == Long.MAX_VALUE ? ""
                    : String.format("; shortest press-to-press interval %.1f ms", shortestGap / 1e6));
            return 0;
        }
    }

    /** {@code status}, {@code install} or {@code remove} the libinput click fix. */
    static int fix(String action) {
        LibinputClickFix.Status status = LibinputClickFix.status();
        if (status == LibinputClickFix.Status.UNSUPPORTED) {
            System.out.println("No libinput here: clicks are not debounced, nothing to fix.");
            return 0;
        }
        switch (action) {
            case "status" -> {
                System.out.println(status == LibinputClickFix.Status.INSTALLED ? "Click fix: installed"
                        : "Click fix: not installed");
                return 0;
            }
            case "install", "remove" -> {
                try {
                    return LibinputClickFix.apply("install".equals(action));
                } catch (IOException failure) {
                    System.err.println("Could not run the click fix: " + failure.getMessage());
                    return 1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return 1;
                }
            }
            default -> {
                System.err.println("--click-fix takes status, install or remove.");
                return 2;
            }
        }
    }
}
