package alkaline.clientapp;

import alkaline.client.ClientServices;
import alkaline.client.Minecraft189;
import alkaline.client.settings.ClientSettings;
import alkaline.client.settings.GuiScale;
import alkaline.config.ConfigSnapshot;
import alkaline.config.ConfigStore;
import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.asset.MinecraftInstallation;
import alkaline.renderer.asset.ZipAssetSource;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.background.BluePlanetBackground;
import alkaline.renderer.vulkan.background.FluidBackground;
import alkaline.renderer.vulkan.background.MenuBackground;
import alkaline.renderer.vulkan.background.OverworldBackground;
import alkaline.renderer.vulkan.background.PanoramaBackground;
import alkaline.renderer.vulkan.background.PanoramaSettings;
import alkaline.renderer.vulkan.ui.ComposeLayerHost;
import alkaline.renderer.vulkan.ui.MenuCompositor;
import alkaline.ui.menu.MenuActions;
import alkaline.ui.menu.MenuController;
import alkaline.ui.theme.AlkalineThemes;
import alkaline.ui.theme.ThemePalette;
import alkaline.renderer.vulkan.resource.PresentMode;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMisc;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;

import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_QUIT;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED;
import static org.lwjgl.sdl.SDLEvents.SDL_PollEvent;

/** Owns the client's main loop: SDL events, the 20 Hz tick clock, and frame recording. */
final class ClientApp implements AutoCloseable {
    private static final long TICK_NANOS = 50_000_000L;
    /** Upper bound on the pre-input GPU wait, so events keep flowing if the GPU or compositor stalls. */
    private static final long SLOT_WAIT_NANOS = 50_000_000L;

    private final SdlWindow window;
    private final VulkanRenderer renderer;
    private final ClientMain.LaunchOptions options;
    private final ConfigStore config;
    /** The loading screen stays at least this long once visible, so a fast start reads as intent, not a flash. */
    private static final long MIN_LOADING_NANOS = 600_000_000L;
    private long loadingShownNanos;

    /** Opened off the render thread so the loading screen keeps animating while the jar is indexed. */
    private final java.util.concurrent.CompletableFuture<AssetSource> assetLoad;
    /** Block models compiled from the assets on the loader thread; null without vanilla assets. */
    private final java.util.concurrent.CompletableFuture<BlockPalette> paletteLoad;
    private AssetSource assets;
    /** The vanilla jar; {@link #assets} layers the selected resource packs over it. */
    private volatile AssetSource vanillaAssets;
    private BlockPalette palette;
    private final java.util.List<alkaline.renderer.asset.ResourcePacks.Folder> packFolders;
    /** The pack selection the startup assets were layered from. */
    private final String startupPacks;
    /** Bumped per Apply, so only the latest reload swaps in. */
    private final java.util.concurrent.atomic.AtomicInteger packReload = new java.util.concurrent.atomic.AtomicInteger();
    /** The player's pack selection as last applied; a server pack layers over it. Render thread. */
    private String appliedPacks;
    private final ServerPacks serverPacks;
    /** The current server's accepted pack, above the player's own; null when none. Render thread. */
    private Path serverPack;
    /** Bumped when a server pack is offered or the player leaves, so older downloads stop and are ignored. */
    private final java.util.concurrent.atomic.AtomicInteger serverPackRound = new java.util.concurrent.atomic.AtomicInteger();
    private MenuBackground background;
    private boolean loaded;
    private float mouseX;
    private float mouseY;
    private PanoramaSettings panoramaSettings = PanoramaSettings.vanilla();
    private ConfigSnapshot appliedSettings;
    private final MenuController menu;
    private final ComposeLayerHost ui;
    private final MenuCompositor compositor;
    private volatile boolean running = true;
    private float uiDensity;
    private float scaleX = 1f;
    private float scaleY = 1f;
    private long frames;
    private final FrameStats stats = new FrameStats();
    /** GPU section ids for --stats: the compositor's blur, the scene under the UI, and the UI layer. */
    private int gpuBlur;
    private int gpuBackdrop;
    private int gpuUi;
    private final java.util.ArrayDeque<ScriptedClick> script = new java.util.ArrayDeque<>();
    private final long startNanos = System.nanoTime();
    private boolean captureRequested;
    private final boolean canRestart = ClientMain.relaunchCommand().isPresent();
    private volatile boolean restartRequested;
    private long lastFrameNanos;
    private final ClientServices services;
    /** Work the UI thread hands to the main thread, for SDL calls that must run there. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> mainThreadTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final float[] surfaceLinear = new float[3];
    private ClientSettings.Theme surfaceTheme;
    private FluidBackground.Look fluidLook;
    private ClientSettings.Theme fluidLookTheme;
    /** The multiplayer game in progress (render thread), or null in the menus. */
    private PlaySession play;
    /** The menu background, kept while a game view replaces it. */
    private MenuBackground menuBackground;
    /** Bumped to abandon a connection attempt the player cancelled. */
    private final java.util.concurrent.atomic.AtomicInteger joinAttempt = new java.util.concurrent.atomic.AtomicInteger();
    private boolean mouseCaptured;
    /** Keys and mouse buttons held, from events (so scripted input drives the game like real input). */
    private final boolean[] heldKeys = new boolean[512];
    private final boolean[] heldButtons = new boolean[8];
    /** Module settings by profile (the HUD's placements among them). */
    private final alkaline.config.ProfileStore profiles;
    private final Skins skins;
    private final alkaline.client.hud.FrameRateMeter fps = new alkaline.client.hud.FrameRateMeter();
    /** Click times for the CPS readouts (milliseconds), left and right. */
    private final java.util.ArrayDeque<Long> leftClicks = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> rightClicks = new java.util.ArrayDeque<>();
    private ChatText chatText;
    private HudBridge hudBridge;
    /** Item models, meshes and icons for the world and the HUD; render-thread state. */
    private ItemCatalog itemCatalog;
    /** The enchantment glint over item icons, drawn over the UI layer (null until the assets load). */
    private HudGlint hudGlint;
    private alkaline.ui.hud.HudFrame publishedHud;
    private volatile String joinedAddress = "";
    private boolean textInput;
    private boolean fullscreen;
    /**
     * The window is minimized, hidden or occluded: nothing is drawn, only ticks run. Presenting to a hidden Wayland
     * window under FIFO blocked for about a second per frame, which would also starve the 20 Hz game tick.
     */
    private boolean windowHidden;
    /** The window's display refresh rate in whole Hz, 0 when SDL cannot tell, and exactly. */
    private int displayRate;
    private float displayHz;
    private final VsyncPacer pacer = new VsyncPacer();
    /** Sound output; its own threads open the device and decode, so nothing here waits on audio. */
    private final alkaline.audio.AudioEngine audio = alkaline.audio.AudioEngine.start();
    /** Input sampling time by present id (ring), to measure input to screen with --stats. */
    private final long[] inputNanos = new long[64];
    private long measuredPresent;

    ClientApp(SdlWindow window, VulkanRenderer renderer, ClientMain.LaunchOptions options, ConfigStore config) {
        this.window = Objects.requireNonNull(window, "window");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.options = Objects.requireNonNull(options, "options");
        this.config = Objects.requireNonNull(config, "config");
        Path packsHome = options.settingsFile().toAbsolutePath().getParent();
        packFolders = java.util.List.of(
                new alkaline.renderer.asset.ResourcePacks.Folder("alkaline", packsHome.resolve("resourcepacks")),
                new alkaline.renderer.asset.ResourcePacks.Folder("minecraft",
                        MinecraftInstallation.gameDirectory().resolve("resourcepacks")));
        String selection = config.snapshot().get(ClientSettings.RESOURCE_PACKS);
        startupPacks = selection;
        appliedPacks = selection;
        serverPacks = new ServerPacks(packsHome.resolve("server-resource-packs"));
        assetLoad = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            vanillaAssets = openVanillaAssets();
            return vanillaAssets == null ? null : alkaline.renderer.asset.ResourcePacks.layer(selected(selection),
                    vanillaAssets);
        }, runnable -> {
            Thread thread = new Thread(runnable, "alkaline-asset-load");
            thread.setDaemon(true);
            thread.start();
        });
        paletteLoad = assetLoad.thenApplyAsync(opened -> opened == null ? null : BlockPalette.load(opened),
                runnable -> {
                    Thread thread = new Thread(runnable, "alkaline-block-models");
                    thread.setDaemon(true);
                    thread.start();
                });
        Path alkalineDirectory = options.settingsFile().toAbsolutePath().getParent();
        services = ClientServices.open(alkalineDirectory, MinecraftInstallation.gameDirectory());
        services.accounts().restore();
        profiles = new alkaline.config.ProfileStore(alkaline.client.modules.Modules.SCHEMA, alkalineDirectory.resolve("profiles"));
        try {
            alkaline.config.ConfigStore.LoadReport report = profiles.open();
            report.issues().forEach(issue -> System.err.println("Profile: " + issue));
        } catch (IOException failure) {
            System.err.println("Profiles unavailable, using defaults: " + failure.getMessage());
        }
        skins = new Skins(alkalineDirectory.resolve("skins"));
        menu = new MenuController(config, new MenuActions() {
            @Override
            public void quit() {
                running = false;
            }

            @Override
            public void restart() {
                restartRequested = canRestart;
                running = false;
            }

            @Override
            public boolean getCanRestart() {
                return canRestart;
            }

            @Override
            public String getVersionLabel() {
                return "Alkaline 0.1.0-dev · Minecraft " + Minecraft189.VERSION + " (protocol " + Minecraft189.PROTOCOL + ")";
            }

            @Override
            public void join(String address) {
                joinedAddress = address;
                int attempt = joinAttempt.incrementAndGet();
                Thread.ofVirtual().name("alkaline-join").start(() -> connect(address, attempt));
            }

            @Override
            public void resumeGame() {
            }

            @Override
            public void leaveGame() {
                joinAttempt.incrementAndGet();
                mainThreadTasks.add(() -> endGame(null));
            }

            @Override
            public void inventoryClosed() {
                mainThreadTasks.add(() -> {
                    if (play != null) {
                        play.closeContainer();
                    }
                });
            }

            @Override
            public void signDone(java.util.List<String> lines) {
                String[] text = lines.toArray(String[]::new);
                mainThreadTasks.add(() -> {
                    if (play != null) {
                        play.signDone(text);
                    }
                });
            }

            @Override
            public void scanResourcePacks() {
                Thread.ofVirtual().name("alkaline-pack-scan").start(() -> {
                    java.util.List<alkaline.renderer.asset.ResourcePacks.Pack> found =
                            alkaline.renderer.asset.ResourcePacks.scan(packFolders);
                    ui.post(() -> {
                        menu.setResourcePacks(found.stream().map(ClientApp::packInfo).toList());
                        return kotlin.Unit.INSTANCE;
                    });
                });
            }

            @Override
            public void openResourcePacksFolder() {
                Path folder = packFolders.getFirst().path();
                try {
                    java.nio.file.Files.createDirectories(folder);
                } catch (IOException failure) {
                    System.err.println("Cannot create " + folder + ": " + failure.getMessage());
                }
                openUrl(folder.toUri().toString());
            }

            @Override
            public void applyResourcePacks() {
                reloadResourcePacks(config.snapshot().get(ClientSettings.RESOURCE_PACKS));
            }

            @Override
            public void openUrl(String url) {
                // SDL expects its calls on the main thread; the menu calls this from the UI thread.
                mainThreadTasks.add(() -> {
                    if (!SDLMisc.SDL_OpenURL(url)) {
                        System.err.println("Could not open a browser; open this address to continue: " + url);
                    }
                });
            }
        }, services, true, profiles);
        menu.setInventoryInput(new InventoryPointer());
        menu.setChatHost(new ChatBridge(() -> play, mainThreadTasks::add));
        menu.setLoadingStatus("Loading vanilla assets");
        try {
            menu.open(alkaline.ui.menu.MenuPage.valueOf(options.page().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            System.err.println("Unknown menu page " + options.page());
        }
        parseScript(options.inputScript());
        refreshPixelScale();
        SdlWindow.PixelSize size = window.pixelSize();
        uiDensity = density(size);
        ui = MenuUi.createHost(size.width(), size.height(), uiDensity, menu);
        compositor = new MenuCompositor(renderer, ui);
        // GuiButton.playPressSound: PositionedSoundRecord.create(gui.button.press, 1.0) plays at volume 0.25.
        ui.setOnControlPressed(() -> {
            audio.playUi("gui.button.press", 0.25F, 1.0F);
            return kotlin.Unit.INSTANCE;
        });
        audio.logging(options.stats());
        renderer.gpuProfiling(options.stats());
        gpuBlur = renderer.gpuSection("blur");
        gpuBackdrop = renderer.gpuSection("backdrop");
        gpuUi = renderer.gpuSection("ui");
        textInput(true);
        refreshDisplayRate();
    }

    /** Reads the display's refresh rate (at start and when the window changes display or mode). */
    private void refreshDisplayRate() {
        displayHz = window.refreshRate();
        int rate = Math.round(displayHz);
        if (rate != displayRate) {
            displayRate = rate;
            ui.post(() -> {
                menu.setDisplayRate(rate);
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    /**
     * Caps how often the UI renders and the glass blur refreshes: the HUD frame rate while only the HUD is up
     * (Refresh rate follows the display), and at least the display's rate in menus so their motion stays smooth.
     */
    private void applyUiRate(ConfigSnapshot settings) {
        int display = displayRate > 0 ? displayRate : 60;
        int setting = settings.get(ClientSettings.HUD_RATE);
        int hud = setting == ClientSettings.HUD_RATE_DISPLAY ? display : setting;
        int rate = play != null && menu.gameHasInput() ? hud : Math.max(hud, display);
        ui.setMaxFramesPerSecond(rate);
        compositor.cacheRate(rate);
    }

    /**
     * The inventory screen's pointer, handed from the UI thread to this one, where GuiContainer's rules run beside
     * the inventory; each event republishes the HUD frame so the screen follows at once rather than at the next tick.
     */
    private final class InventoryPointer implements alkaline.ui.menu.InventoryInput {
        @Override
        public void hover(int slot) {
            onWindow(input -> input.hover(slot));
        }

        @Override
        public void press(int slot, int button) {
            long now = System.currentTimeMillis();
            onWindow(input -> input.press(slot, button, shiftHeld(), pickBlock(button), now));
        }

        @Override
        public void drag(int slot) {
            onWindow(input -> input.drag(slot));
        }

        @Override
        public void release(int slot, int button) {
            onWindow(input -> input.release(slot, button, shiftHeld(), pickBlock(button)));
        }

        @Override
        public void enchant(int option) {
            onPlay(session -> session.enchant(option));
        }

        @Override
        public void selectTrade(int index) {
            onPlay(session -> session.selectTrade(index));
        }

        @Override
        public void rename(String name) {
            onPlay(session -> session.rename(name));
        }

        @Override
        public void beacon(int primary, int secondary) {
            onPlay(session -> {
                session.beacon(primary, secondary);
                ui.post(() -> {
                    menu.inventoryClosedByServer();
                    return kotlin.Unit.INSTANCE;
                });
            });
        }

        private void onWindow(java.util.function.Consumer<alkaline.game.inventory.WindowInput> action) {
            onPlay(session -> action.accept(session.windowInput()));
        }

        private void onPlay(java.util.function.Consumer<PlaySession> action) {
            mainThreadTasks.add(() -> {
                if (play != null && play.containerOpen()) {
                    action.accept(play);
                    publishHud(config.snapshot());
                }
            });
        }
    }

    private boolean shiftHeld() {
        return heldKeys[org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_LSHIFT] || heldKeys[org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RSHIFT];
    }

    private boolean ctrlHeld() {
        return heldKeys[org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_LCTRL] || heldKeys[org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RCTRL];
    }

    /** Whether a GuiContainer mouse button (0 left, 1 right, 2 middle) is the pick block binding. */
    private boolean pickBlock(int button) {
        String binding = config.snapshot().get(ClientSettings.KEY_PICK_BLOCK);
        return binding.equals(switch (button) {
            case 0 -> "mouse:left";
            case 1 -> "mouse:right";
            case 2 -> "mouse:middle";
            default -> "";
        });
    }

    /** UI density from the GUI scale setting and the framebuffer size. */
    private float density(SdlWindow.PixelSize size) {
        return GuiScale.scale(config.snapshot().get(ClientSettings.GUI_SCALE), size.width(), size.height());
    }

    /**
     * Startup steps run between frames so the loading screen stays live: assets arrive from their thread, the
     * background is created on this one, and the reveal waits until the background reports it is ready.
     */
    private void advanceLoading() {
        if (loaded) {
            return;
        }
        if (background == null) {
            if (!assetLoad.isDone() || !paletteLoad.isDone()) {
                return;
            }
            assets = assetLoad.join();
            palette = paletteLoad.join();
            if (assets != null) {
                audio.assets(GameSounds.assets(assets));
            }
            chatText = ChatText.load(assets);
            itemCatalog = new ItemCatalog(assets);
            hudGlint = new HudGlint(renderer, itemCatalog);
            alkaline.ui.hud.HudSprites sprites = new alkaline.ui.hud.HudSprites(assets);
            hudBridge = new HudBridge(itemCatalog, sprites, chatText);
            String loadedPacks = startupPacks;
            ui.post(() -> {
                menu.setHudSprites(sprites);
                menu.setAppliedResourcePacks(loadedPacks);
                return kotlin.Unit.INSTANCE;
            });
            background = createBackground(config.snapshot().get(ClientSettings.MENU_BACKGROUND));
            ui.post(() -> {
                menu.setLoadingStatus("Preparing " + backgroundName(background));
                return kotlin.Unit.INSTANCE;
            });
        }
        if (loadingShownNanos == 0 && compositor.uiPresented()) {
            loadingShownNanos = System.nanoTime();
        }
        if (background.ready() && loadingShownNanos != 0 && System.nanoTime() - loadingShownNanos >= MIN_LOADING_NANOS) {
            loaded = true;
            ui.post(() -> {
                menu.finishLoading();
                if (options.join() != null) {
                    menu.join(options.join());
                }
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    private static String backgroundName(MenuBackground background) {
        return switch (background.id()) {
            case PanoramaBackground.ID -> "panorama";
            case BluePlanetBackground.ID -> "Blue Planet";
            case FluidBackground.ID -> "fluid";
            default -> "world";

        };
    }

    void run() {
        long previousTick = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Event event = SDL_Event.calloc(stack);
            while (running) {
                // Wait for the GPU before sampling input, so a GPU-bound frame does not add its wait to the
                // age of the input it presents.
                long waitStart = System.nanoTime();
                boolean paced = !windowHidden && config.snapshot().get(ClientSettings.VIDEO_VSYNC)
                        && renderer.presentWaitSupported() && pacer.pace(renderer, displayHz);
                if (paced && options.stats()) {
                    stats.shown(lastShownLatency());
                }
                renderer.awaitFrameSlot(SLOT_WAIT_NANOS);
                stats.waited(System.nanoTime() - waitStart);
                if (options.stats() && !paced) {
                    measurePresents();
                }
                inputNanos[(int) ((renderer.lastPresentId() + 1) % inputNanos.length)] = System.nanoTime();
                injectScriptedInput();
                for (Runnable task; (task = mainThreadTasks.poll()) != null; ) {
                    task.run();
                }
                while (SDL_PollEvent(event)) {
                    handleEvent(event);
                }
                long now = System.nanoTime();
                int ticks = 0;
                while (now - previousTick >= TICK_NANOS && ticks < 10) {
                    tick();
                    previousTick += TICK_NANOS;
                    ticks++;
                }
                if (now - previousTick >= TICK_NANOS) {
                    previousTick = now; // drop backlog after a long stall instead of spiralling
                }
                float partialTick = Math.min(1f, (now - previousTick) / (float) TICK_NANOS);
                long frameStart = System.nanoTime();
                if (windowHidden) {
                    sleepMillis(4); // hidden: keep ticking, draw nothing
                } else if (renderFrame(partialTick) && running) {
                    stats.recorded(System.nanoTime() - frameStart, renderer.gpuFrameNanos());
                } else if (window.pixelSize().width() <= 0 || window.pixelSize().height() <= 0) {
                    sleepMillis(4); // minimized: nothing to present, so do not spin
                }
                limitFrameRate();
            }
        }
    }

    /** Input to screen of the frame the pacer just saw shown. */
    private long lastShownLatency() {
        long id = pacer.waited();
        measuredPresent = id;
        return System.nanoTime() - inputNanos[(int) (id % inputNanos.length)];
    }

    /** Without pacing: notes, without blocking, which earlier frames have reached the screen since the last look. */
    private void measurePresents() {
        if (!renderer.presentWaitSupported()) {
            return;
        }
        long last = renderer.lastPresentId();
        measuredPresent = Math.max(measuredPresent, last - inputNanos.length + 1);
        while (measuredPresent < last && renderer.waitForPresent(measuredPresent + 1, 0L)) {
            measuredPresent++;
            stats.shown(System.nanoTime() - inputNanos[(int) (measuredPresent % inputNanos.length)]);
        }
    }

    /** The world options the menu's Overworld and the game view share. */
    static OverworldBackground.Settings worldSettings(ConfigSnapshot values) {
        return new OverworldBackground.Settings(values.get(ClientSettings.WORLD_RENDER_DISTANCE),
                values.get(ClientSettings.WORLD_AMBIENT_OCCLUSION), values.get(ClientSettings.WORLD_FOG),
                values.get(ClientSettings.WORLD_FOG_START), values.get(ClientSettings.WORLD_SKY),
                values.get(ClientSettings.WORLD_SUN_MOON), values.get(ClientSettings.WORLD_STARS));
    }

    /** Sleeps off the remainder of the frame budget when a frame limit is set; spins only the last 0.5 ms. */
    private void limitFrameRate() {
        int limit = config.snapshot().get(ClientSettings.VIDEO_FRAME_LIMIT);
        if (config.snapshot().get(ClientSettings.VIDEO_VSYNC) && window.xwayland() && !renderer.presentWaitSupported()
                && displayRate > 0) {
            // XWayland's FIFO is not refresh-locked (it accepts two frames per refresh): hold V-Sync to the display.
            limit = Math.min(limit, displayRate);
        }
        long now = System.nanoTime();
        if (limit >= ClientSettings.FRAME_LIMIT_UNLIMITED) {
            lastFrameNanos = now;
            return;
        }
        long deadline = lastFrameNanos + 1_000_000_000L / limit;
        long remaining = deadline - now;
        if (remaining > 1_000_000L) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining - 500_000L);
        }
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        // After a slow frame, restart the cadence instead of rushing to catch up.
        lastFrameNanos = Math.max(deadline, now - 1_000_000_000L / limit);
    }

    boolean restartRequested() {
        return restartRequested;
    }

    /**
     * The present mode, chosen for the platform. V-Sync is FIFO. Without it the game draws uncapped: mailbox where the
     * display has a real one (it never tears), immediate where it has not, and immediate under XWayland, whose X11
     * mailbox is emulated and holds the game to two frames per refresh while immediate presents go to the Wayland
     * compositor, which shows the newest frame at each refresh as mailbox does (decisions 0009 and 0010).
     */
    static PresentMode presentMode(boolean vsync, boolean xwayland) {
        if (vsync) {
            return PresentMode.FIFO;
        }
        return xwayland ? PresentMode.IMMEDIATE : PresentMode.MAILBOX;
    }

    private void tick() {
        if (background != null) {
            background.tick();
        }
        if (play != null) {
            ConfigSnapshot settings = config.snapshot();
            ConfigSnapshot toggles = profiles.store().snapshot();
            boolean toggling = toggles.get(alkaline.client.modules.Modules.TOGGLE_SPRINT.enabled());
            play.movementToggles(toggling && toggles.get(alkaline.client.modules.Modules.TOGGLE_SPRINT_SPRINT),
                    toggling && toggles.get(alkaline.client.modules.Modules.TOGGLE_SPRINT_SNEAK));
            play.tick(menu.gameHasInput(), settings, heldKeys, heldButtons);
            if (background instanceof GameView view) {
                ConfigSnapshot modules = profiles.store().snapshot();
                boolean zooming = modules.get(alkaline.client.modules.Modules.ZOOM.enabled()) && menu.gameHasInput()
                        && PlaySession.held(modules.get(alkaline.client.modules.Modules.ZOOM_KEY), heldKeys, heldButtons);
                view.zoom(zooming ? modules.get(alkaline.client.modules.Modules.ZOOM_FACTOR) : 1f,
                        modules.get(alkaline.client.modules.Modules.ZOOM_SMOOTH));
                view.handStyle(handStyle(modules));
                view.followSelf();
            }
            publishHud(settings);
        }
    }

    /** Clicks in the last second, dropping older ones. */
    private static int cps(java.util.ArrayDeque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.pollFirst();
        }
        return clicks.size();
    }

    /** The Animations module as a first-person style, kept while its values stay the same. */
    private alkaline.renderer.model.FirstPersonItem.Style handStyle(ConfigSnapshot modules) {
        alkaline.renderer.model.FirstPersonItem.Style style = !modules.get(alkaline.client.modules.Modules.ANIMATIONS.enabled())
                ? alkaline.renderer.model.FirstPersonItem.Style.VANILLA
                : new alkaline.renderer.model.FirstPersonItem.Style(
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_BLOCK_HIT),
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_USE_SWING),
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_X),
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_Y),
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_Z),
                        modules.get(alkaline.client.modules.Modules.ANIMATIONS_SCALE));
        if (!style.equals(lastHandStyle)) {
            lastHandStyle = style;
        }
        return lastHandStyle;
    }

    private alkaline.renderer.model.FirstPersonItem.Style lastHandStyle =
            alkaline.renderer.model.FirstPersonItem.Style.VANILLA;

    /** Hands the HUD this tick's values when they changed (the UI renders a frame only then). */
    private void publishHud(ConfigSnapshot settings) {
        if (hudBridge == null || chatText == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean input = menu.gameHasInput();
        int keys = 0;
        if (input) {
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_FORWARD), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_FORWARD : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_LEFT), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_LEFT : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_BACK), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_BACK : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_RIGHT), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_RIGHT : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_JUMP), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_JUMP : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_ATTACK), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_ATTACK : 0;
            keys |= PlaySession.held(settings.get(ClientSettings.KEY_USE), heldKeys, heldButtons) ? alkaline.ui.hud.HudFrame.KEY_USE : 0;
        }
        boolean players = input && PlaySession.held(settings.get(ClientSettings.KEY_PLAYER_LIST), heldKeys, heldButtons);
        alkaline.ui.hud.HudFrame frame = hudBridge.frame(play, settings, fps.fps(), joinedAddress, cps(leftClicks, now),
                cps(rightClicks, now), keys, players, chatText, play.movementStatus(settings, heldKeys, heldButtons));
        if (!frame.equals(publishedHud)) {
            publishedHud = frame;
            ui.post(() -> {
                menu.getHud().setFrame(frame);
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    // ---- multiplayer ---------------------------------------------------------------------------------------

    /** Resolves, connects and logs in off the render thread; hands the session to the render thread. */
    private void connect(String typed, int attempt) {
        try {
            alkaline.client.servers.ServerAddress address = alkaline.client.servers.ServerAddress.parse(typed);
            alkaline.client.servers.ServerAddress target = address.resolve();
            alkaline.client.account.MinecraftSession account = services.accounts().session();
            String username = account != null ? account.profile().name() : "Player";
            alkaline.game.net.Login.SessionJoin join = account == null ? null
                    : hash -> new alkaline.client.account.SessionService().join(account, hash);
            alkaline.game.net.ServerSession session = alkaline.game.net.ServerSession.connect(target.host(),
                    target.port(), address.host(), username, join);
            if (attempt != joinAttempt.get()) {
                session.close();
                return;
            }
            mainThreadTasks.add(() -> startGame(session, attempt));
        } catch (java.io.IOException | RuntimeException failure) {
            if (attempt == joinAttempt.get()) {
                String reason = failure instanceof alkaline.game.net.Login.RefusedException refused
                        ? chatText(refused.reasonJson()) : failure.getMessage();
                ui.post(() -> {
                    menu.left(reason == null ? failure.getClass().getSimpleName() : reason);
                    return kotlin.Unit.INSTANCE;
                });
            }
        }
    }

    private void startGame(alkaline.game.net.ServerSession session, int attempt) {
        BlockPalette palette = this.palette;
        if (attempt != joinAttempt.get() || palette == null) {
            session.close();
            if (palette == null) {
                ui.post(() -> {
                    menu.left("Vanilla 1.8.9 assets are needed to draw the world.");
                    return kotlin.Unit.INSTANCE;
                });
            }
            return;
        }
        PlaySession created = new PlaySession(session, chatText != null ? chatText : ChatText.load(assets),
                reason -> mainThreadTasks.add(() -> endGame(reason)));
        created.sounds(new GameSounds(audio));
        try {
            GameView view = new GameView(renderer, assets, palette, created, skins, itemCatalog, config::snapshot);
            created.view(view);
            created.onWindowClosedByServer(() -> ui.post(() -> {
                menu.inventoryClosedByServer();
                return kotlin.Unit.INSTANCE;
            }));
            created.onWindowOpenedByServer(() -> {
                publishHud(config.snapshot());
                ui.post(() -> {
                    menu.openWindow();
                    return kotlin.Unit.INSTANCE;
                });
            });
            created.onSignEditorOpened(lines -> ui.post(() -> {
                menu.openSign(java.util.List.of(lines));
                return kotlin.Unit.INSTANCE;
            }));
            created.onTabComplete(matches -> ui.post(() -> {
                menu.getChat().completed(matches);
                return kotlin.Unit.INSTANCE;
            }));
            created.onResourcePackOffered((url, hash) -> serverPackOffered(created, url, hash));
            session.sendClientSettings("en_US", config.snapshot().get(ClientSettings.WORLD_RENDER_DISTANCE));
            play = created;
            menuBackground = background;
            background = view;
            ui.post(() -> {
                menu.joined();
                return kotlin.Unit.INSTANCE;
            });
        } catch (java.io.IOException | RuntimeException failure) {
            created.close();
            ui.post(() -> {
                menu.left(failure.getMessage());
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    /** Leaves the game (or reports that it ended) and restores the menu. Render thread, outside frames. */
    private void endGame(String reason) {
        audio.stopAll();
        // Minecraft.loadWorld(null) clears the server's pack: stop its download and drop it from the assets.
        serverPackRound.incrementAndGet();
        if (serverPack != null) {
            serverPack = null;
            reloadResourcePacks(appliedPacks);
        }
        if (play != null) {
            play.close();
            play = null;
            if (background instanceof GameView view) {
                view.close();
            }
            background = menuBackground;
            menuBackground = null;
        }
        captureMouse(false);
        String shown = reason == null ? null : chatText(reason);
        ui.post(() -> {
            menu.left(shown);
            return kotlin.Unit.INSTANCE;
        });
    }

    /** Plain text of a chat component (kick messages), via the server list's formatter without § codes. */
    private static String chatText(String json) {
        try {
            StringBuilder text = new StringBuilder();
            alkaline.client.servers.ServerPinger.flatten(alkaline.config.Json.parse(json), "", text);
            return text.toString().replaceAll("§.", "").strip();
        } catch (RuntimeException notJson) {
            return json;
        }
    }

    private void captureMouse(boolean capture) {
        if (capture != mouseCaptured) {
            if (!org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), capture)) {
                System.err.println("Relative mouse mode unavailable: " + org.lwjgl.sdl.SDLError.SDL_GetError());
            }
            mouseCaptured = capture;
            textInput(!capture);
            if (!capture) {
                ui.pointerMoved(mouseX * pixelScaleX(), mouseY * pixelScaleY());
            }
        }
    }

    private boolean renderFrame(float partialTick) {
        captureMouse(play != null && menu.gameHasInput());
        advanceLoading();
        applySettings();
        ui.checkHealthy();
        SdlWindow.PixelSize size = window.pixelSize();
        compositor.update(size.width(), size.height(), background != null && background.needsDepth());
        if (hudGlint != null) {
            hudGlint.update(compositor);
        }
        if (background != null) {
            background.beforeFrame(size.width(), size.height());
        }
        if (play != null) {
            // SoundManager.setListener: the player's interpolated eyes and look, every frame.
            alkaline.game.player.LocalPlayer self = play.player();
            audio.listener(self.eyeX(partialTick), self.eyeY(partialTick), self.eyeZ(partialTick), self.yaw, self.pitch);
        }
        VulkanFrame frame = renderer.acquireFrame();
        if (frame == null) {
            return false;
        }
        ConfigSnapshot settings = config.snapshot();
        applyUiRate(settings);
        // Until the first UI frame (the opaque loading screen) is on screen, show only the clear color, so the
        // background never flashes in before the loading screen.
        compositor.clearColor(themeSurface(settings.get(ClientSettings.INTERFACE_THEME)));
        compositor.prepare(frame, compositor.uiPresented() ? background : null, partialTick,
                settings.get(ClientSettings.MENU_GLASS));
        // The theme surface, so the first frames match the loading screen.
        float[] surface = themeSurface(settings.get(ClientSettings.INTERFACE_THEME));
        renderer.gpuMark(frame, gpuBlur);
        frame.beginMainPass(surface[0], surface[1], surface[2], 1f);
        compositor.drawBackdrop(frame);
        renderer.gpuMark(frame, gpuBackdrop);
        compositor.drawUi(frame);
        renderer.gpuMark(frame, gpuUi);
        if (hudGlint != null) {
            hudGlint.draw(frame, compositor, System.currentTimeMillis());
        }
        frames++;
        if (options.screenshot() != null && !captureRequested
                && System.nanoTime() - startNanos >= options.screenshotDelayMillis() * 1_000_000L) {
            captureRequested = renderer.captureNextFrame(this::saveScreenshotAndExit);
        }
        renderer.endFrame();
        if (running) { // the screenshot frame encodes a PNG inside endFrame; it is not a representative frame
            stats.framePresented();
            fps.frame(System.nanoTime());
        }
        return true;
    }

    /** Linear RGB of the theme's surface color; recomputed only when the theme changes. */
    private float[] themeSurface(ClientSettings.Theme theme) {
        if (theme != surfaceTheme) {
            int argb = AlkalineThemes.palette(theme).getSurface();
            surfaceLinear[0] = srgbToLinear(argb >> 16 & 0xFF);
            surfaceLinear[1] = srgbToLinear(argb >> 8 & 0xFF);
            surfaceLinear[2] = srgbToLinear(argb & 0xFF);
            surfaceTheme = theme;
        }
        return surfaceLinear;
    }

    private static float srgbToLinear(int channel) {
        float c = channel / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private void applySettings() {
        ConfigSnapshot snapshot = config.snapshot();
        if (snapshot == appliedSettings) {
            return;
        }
        if (loaded && appliedSettings != null
                && appliedSettings.get(ClientSettings.MENU_BACKGROUND) != snapshot.get(ClientSettings.MENU_BACKGROUND)) {
            background.close();
            background = createBackground(snapshot.get(ClientSettings.MENU_BACKGROUND));
        }
        if (snapshot.get(ClientSettings.VIDEO_FULLSCREEN) != fullscreen) {
            fullscreen = snapshot.get(ClientSettings.VIDEO_FULLSCREEN);
            if (!SDLVideo.SDL_SetWindowFullscreen(window.handle(), fullscreen)) {
                System.err.println("Fullscreen unavailable: " + org.lwjgl.sdl.SDLError.SDL_GetError());
            }
        }
        if (appliedSettings != null && appliedSettings.get(ClientSettings.VIDEO_VSYNC) != snapshot.get(ClientSettings.VIDEO_VSYNC)) {
            renderer.presentMode(presentMode(snapshot.get(ClientSettings.VIDEO_VSYNC), window.xwayland()));
        }
        appliedSettings = snapshot;
        GameSounds.volumes(audio, snapshot);
        float density = density(window.pixelSize());
        if (density != uiDensity) {
            uiDensity = density;
            SdlWindow.PixelSize size = window.pixelSize();
            ui.resize(size.width(), size.height(), density);
        }
        panoramaSettings = new PanoramaSettings(
                snapshot.get(ClientSettings.PANORAMA_SAMPLES), snapshot.get(ClientSettings.PANORAMA_BLUR),
                snapshot.get(ClientSettings.PANORAMA_SPEED),
                snapshot.get(ClientSettings.PANORAMA_TOP_A), snapshot.get(ClientSettings.PANORAMA_BOTTOM_A),
                snapshot.get(ClientSettings.PANORAMA_TOP_B), snapshot.get(ClientSettings.PANORAMA_BOTTOM_B));
    }

    /** Backgrounds are swapped outside frames; the panorama falls back to Blue Planet without vanilla assets. */
    private MenuBackground createBackground(ClientSettings.Background choice) {
        if (choice == ClientSettings.Background.FLUID) {
            return new FluidBackground(renderer, this::fluidLook);
        }
        if (choice == ClientSettings.Background.OVERWORLD && assets != null) {
            try {
                MenuTerrain terrain = new MenuTerrain(1_8_9L);
                return new OverworldBackground(renderer, assets, palette, terrain, terrain.eyeHeight(36.0),
                        () -> worldSettings(config.snapshot()));
            } catch (IOException failure) {
                System.err.println("Overworld unavailable: " + failure.getMessage());
            }
        }
        if (choice == ClientSettings.Background.PANORAMA && assets != null) {
            try {
                return PanoramaBackground.load(renderer, assets, () -> panoramaSettings);
            } catch (IOException failure) {
                System.err.println("Panorama unavailable: " + failure.getMessage());
            }
        }
        return new BluePlanetBackground(renderer);
    }

    /**
     * The fluid background draws with the theme: on dark themes a near-white ink over the surface, on light
     * themes the primary color, as the original effect pairs white-on-black and blue-on-white (a little
     * stronger than the original there, since Monet primaries carry less chroma than its blue). Dither cells
     * follow the GUI scale so the pattern keeps its size on dense displays.
     */
    private FluidBackground.Look fluidLook() {
        ClientSettings.Theme theme = config.snapshot().get(ClientSettings.INTERFACE_THEME);
        int cell = Math.max(2, Math.round(uiDensity * 1.5f));
        if (fluidLook == null || theme != fluidLookTheme || cell != fluidLook.cellSize()) {
            ThemePalette palette = AlkalineThemes.palette(theme);
            fluidLook = new FluidBackground.Look(palette.getSurface(),
                    palette.getDark() ? palette.getOnSurface() : palette.getPrimary(),
                    palette.getDark() ? 0.28f : 0.34f, cell);
            fluidLookTheme = theme;
        }
        return fluidLook;
    }

    /** The packs a selection names, in its order; ids no longer found are skipped. */
    private java.util.List<alkaline.renderer.asset.ResourcePacks.Pack> selected(String selection) {
        java.util.List<String> ids = selection.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        java.util.Map<String, alkaline.renderer.asset.ResourcePacks.Pack> byId = new java.util.HashMap<>();
        for (alkaline.renderer.asset.ResourcePacks.Pack pack : alkaline.renderer.asset.ResourcePacks.scan(packFolders)) {
            byId.put(pack.id(), pack);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static alkaline.ui.menu.ResourcePackInfo packInfo(alkaline.renderer.asset.ResourcePacks.Pack pack) {
        return new alkaline.ui.menu.ResourcePackInfo(pack.id(), pack.name(), pack.description(), pack.icon(),
                pack.compatible());
    }

    /**
     * The server offered a resource pack (NetHandlerPlayClient.handleResourcePack): a saved server's choice
     * applies, otherwise the player is asked. Render thread.
     */
    private void serverPackOffered(PlaySession session, String url, String hash) {
        if (!ServerPacks.downloadable(url)) {
            // level:// names a singleplayer world's pack, and Alkaline has no saves to read it from.
            System.out.println("[resource pack] cannot use " + url);
            session.resourcePackStatus(hash, alkaline.game.net.ServerSession.PACK_FAILED);
            return;
        }
        alkaline.client.servers.ServerList list = services.servers();
        alkaline.client.servers.ServerEntry entry = list.find(joinedAddress);
        switch (entry == null ? alkaline.client.servers.ServerEntry.Packs.PROMPT : entry.packs()) {
            case ENABLED -> useServerPack(session, url, hash);
            case DISABLED -> session.resourcePackStatus(hash, alkaline.game.net.ServerSession.PACK_DECLINED);
            case PROMPT -> {
                String name = entry != null ? entry.name() : joinedAddress;
                ui.post(() -> {
                    menu.offerServerPack(name, use -> {
                        mainThreadTasks.add(() -> serverPackAnswered(session, entry, use, url, hash));
                        return kotlin.Unit.INSTANCE;
                    });
                    return kotlin.Unit.INSTANCE;
                });
            }
        }
    }

    /** The player chose; a saved server remembers it (ServerList.func_147414_b). Render thread. */
    private void serverPackAnswered(PlaySession session, alkaline.client.servers.ServerEntry entry, boolean use,
            String url, String hash) {
        if (entry != null) {
            services.servers().replace(entry, entry.withPacks(use ? alkaline.client.servers.ServerEntry.Packs.ENABLED
                    : alkaline.client.servers.ServerEntry.Packs.DISABLED));
        }
        if (play != session) {
            return;
        }
        if (use) {
            useServerPack(session, url, hash);
        } else {
            session.resourcePackStatus(hash, alkaline.game.net.ServerSession.PACK_DECLINED);
        }
    }

    /**
     * Accepts the pack, fetches it off the render thread (or reuses the stored copy whose hash matches), layers it
     * over the player's packs, and reports loaded or failed. Render thread.
     */
    private void useServerPack(PlaySession session, String url, String hash) {
        session.resourcePackStatus(hash, alkaline.game.net.ServerSession.PACK_ACCEPTED);
        int round = serverPackRound.incrementAndGet();
        serverPackProgress("Downloading server pack", -1f);
        alkaline.client.account.MinecraftSession account = services.accounts().session();
        String username = account != null ? account.profile().name() : "Player";
        String uuid = account != null ? account.profile().uuid() : "";
        Thread.ofVirtual().name("alkaline-server-pack").start(() -> {
            try {
                Path file = serverPacks.cached(hash);
                if (file == null) {
                    int[] shown = {-1};
                    file = serverPacks.download(url, hash, username, uuid, (received, total) -> {
                        // Only whole percents (or megabytes, size unknown) reach the UI.
                        int step = total > 0 ? (int) (received * 100 / total) : (int) (received >> 20);
                        if (step != shown[0]) {
                            shown[0] = step;
                            serverPackProgress(total > 0 ? "Downloading server pack" : "Downloading server pack · "
                                    + step + " MB", total > 0 ? step / 100f : -1f);
                        }
                    }, () -> round != serverPackRound.get());
                }
                Path ready = file;
                mainThreadTasks.add(() -> {
                    if (round != serverPackRound.get() || play != session) {
                        return;
                    }
                    serverPack = ready;
                    serverPackProgress("Applying server pack", -1f);
                    reloadResourcePacks(appliedPacks, loaded -> {
                        if (play == session) {
                            session.resourcePackStatus(hash, loaded ? alkaline.game.net.ServerSession.PACK_LOADED
                                    : alkaline.game.net.ServerSession.PACK_FAILED);
                        }
                        serverPackProgress(null, 0f);
                    });
                });
            } catch (IOException | RuntimeException failure) {
                System.err.println("[resource pack] " + url + " failed: " + failure.getMessage());
                mainThreadTasks.add(() -> {
                    if (round == serverPackRound.get() && play == session) {
                        session.resourcePackStatus(hash, alkaline.game.net.ServerSession.PACK_FAILED);
                        serverPackProgress(null, 0f);
                    }
                });
            }
        });
    }

    /** Shows the server pack's progress ({@code status} null hides it); any thread. */
    private void serverPackProgress(String status, float progress) {
        ui.post(() -> {
            if (status == null) {
                menu.setServerPackProgress(null);
            } else {
                menu.setServerPackStatus(status);
                menu.setServerPackProgress(progress);
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Applies a pack selection: the layered assets, block palette and fonts load off the render thread, then
     * {@link #swapAssets} replaces everything drawn from the old ones. A newer Apply supersedes an older one.
     */
    private void reloadResourcePacks(String selection) {
        reloadResourcePacks(selection, loaded -> { });
    }

    /** As {@link #reloadResourcePacks(String)}, then tells {@code done} on the render thread whether it applied. */
    private void reloadResourcePacks(String selection, java.util.function.Consumer<Boolean> done) {
        AssetSource vanilla = vanillaAssets;
        if (vanilla == null) {
            done.accept(false);
            return;
        }
        Path server = serverPack;
        int reload = packReload.incrementAndGet();
        ui.post(() -> {
            menu.setResourcePackStatus("Reloading assets…");
            return kotlin.Unit.INSTANCE;
        });
        Thread.ofPlatform().name("alkaline-pack-reload").daemon().start(() -> {
            java.util.List<alkaline.renderer.asset.ResourcePacks.Pack> packs = new java.util.ArrayList<>();
            if (server != null) {
                packs.add(new alkaline.renderer.asset.ResourcePacks.Pack("server/" + server.getFileName(), server,
                        "Server resource pack", "", null, alkaline.renderer.asset.ResourcePacks.FORMAT));
            }
            packs.addAll(selected(selection));
            AssetSource layered = alkaline.renderer.asset.ResourcePacks.layer(packs, vanilla);
            try {
                BlockPalette loadedPalette = BlockPalette.load(layered);
                ChatText loadedText = ChatText.load(layered);
                if (reload != packReload.get()) {
                    layered.close();
                    mainThreadTasks.add(() -> done.accept(false));
                    return;
                }
                mainThreadTasks.add(() -> {
                    swapAssets(layered, loadedPalette, loadedText, selection);
                    done.accept(true);
                });
            } catch (IOException | RuntimeException failure) {
                try {
                    layered.close();
                } catch (IOException ignored) {
                    // Reported below.
                }
                String reason = failure.getMessage();
                mainThreadTasks.add(() -> done.accept(false));
                ui.post(() -> {
                    menu.setResourcePackStatus("Could not load the packs: " + reason);
                    return kotlin.Unit.INSTANCE;
                });
            }
        });
    }

    /** Render thread, outside frames: everything drawn from the assets is rebuilt from the new ones. */
    private void swapAssets(AssetSource layered, BlockPalette loadedPalette, ChatText loadedText, String selection) {
        AssetSource previous = assets;
        audio.assets(GameSounds.assets(layered));
        assets = layered;
        appliedPacks = selection;
        palette = loadedPalette;
        chatText = loadedText;
        itemCatalog.assets(layered);
        alkaline.ui.hud.HudSprites sprites = new alkaline.ui.hud.HudSprites(layered);
        hudBridge = new HudBridge(itemCatalog, sprites, chatText);
        ClientSettings.Background choice = config.snapshot().get(ClientSettings.MENU_BACKGROUND);
        if (play != null && background instanceof GameView old) {
            try {
                GameView view = new GameView(renderer, layered, loadedPalette, play, skins, itemCatalog,
                        config::snapshot);
                view.inherit(old);
                old.close();
                play.view(view);
                background = view;
            } catch (IOException | RuntimeException failure) {
                System.err.println("World reload failed: " + failure.getMessage());
            }
            if (menuBackground != null) {
                menuBackground.close();
                menuBackground = createBackground(choice);
            }
        } else if (background != null && loaded) {
            background.close();
            background = createBackground(choice);
        }
        try {
            if (previous != null) {
                previous.close();
            }
        } catch (IOException failure) {
            System.err.println("Closing the previous packs failed: " + failure.getMessage());
        }
        ui.post(() -> {
            menu.setHudSprites(sprites);
            menu.setAppliedResourcePacks(selection);
            menu.setResourcePackStatus("");
            return kotlin.Unit.INSTANCE;
        });
    }

    private static AssetSource openVanillaAssets() {
        Optional<Path> jar = MinecraftInstallation.locateClientJar();
        if (jar.isEmpty()) {
            System.err.println("No Minecraft " + MinecraftInstallation.VERSION
                    + " client jar found; set ALKALINE_MINECRAFT_JAR to use vanilla assets.");
            return null;
        }
        try {
            // The jar, with the launcher's asset objects (sounds.json and every sound) under it, as 1.8.9 reads them.
            AssetSource vanilla = new ZipAssetSource(jar.get());
            AssetSource index = alkaline.renderer.asset.AssetIndexSource.open(MinecraftInstallation.gameDirectory());
            return index == null ? vanilla : AssetSource.layered(vanilla, index);
        } catch (IOException failure) {
            System.err.println("Cannot open " + jar.get() + ": " + failure.getMessage());
            return null;
        }
    }

    private void handleEvent(SDL_Event event) {
        int type = event.type();
        if (type == SDLEvents.SDL_EVENT_WINDOW_MINIMIZED || type == SDLEvents.SDL_EVENT_WINDOW_HIDDEN
                || type == SDLEvents.SDL_EVENT_WINDOW_OCCLUDED) {
            windowHidden = true;
        } else if (type == SDLEvents.SDL_EVENT_WINDOW_RESTORED || type == SDLEvents.SDL_EVENT_WINDOW_SHOWN
                || type == SDLEvents.SDL_EVENT_WINDOW_EXPOSED || type == SDLEvents.SDL_EVENT_WINDOW_MAXIMIZED
                || type == SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED || type == SDLEvents.SDL_EVENT_WINDOW_MOUSE_ENTER) {
            // Wayland cannot report a window coming back from minimized; focus or the pointer arriving says so.
            windowHidden = false;
        }
        int windowId = window.windowId();
        if (type == SDLEvents.SDL_EVENT_KEY_DOWN || type == SDLEvents.SDL_EVENT_KEY_UP) {
            int scancode = event.key().scancode();
            if (scancode >= 0 && scancode < heldKeys.length) {
                heldKeys[scancode] = type == SDLEvents.SDL_EVENT_KEY_DOWN;
            }
        } else if (type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP) {
            int button = Byte.toUnsignedInt(event.button().button());
            if (button < heldButtons.length) {
                heldButtons[button] = type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
            }
        } else if (type == SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST) {
            java.util.Arrays.fill(heldKeys, false);
            java.util.Arrays.fill(heldButtons, false);
        }
        if (type == SDL_EVENT_QUIT
                || (type == SDL_EVENT_WINDOW_CLOSE_REQUESTED && event.window().windowID() == windowId)) {
            running = false;
        } else if ((type == SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED || type == SDLEvents.SDL_EVENT_WINDOW_RESIZED)
                && event.window().windowID() == windowId) {
            refreshPixelScale();
            renderer.windowResized();
            SdlWindow.PixelSize size = window.pixelSize();
            uiDensity = density(size);
            ui.resize(size.width(), size.height(), uiDensity);
        } else if ((type == SDLEvents.SDL_EVENT_WINDOW_DISPLAY_CHANGED && event.window().windowID() == windowId)
                || type == SDLEvents.SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED) {
            refreshDisplayRate();
        } else if (type == SDLEvents.SDL_EVENT_MOUSE_MOTION && event.motion().windowID() == windowId
                && mouseCaptured && play != null) {
            play.look(event.motion().xrel(), event.motion().yrel(), config.snapshot(),
                    background instanceof GameView view ? view.magnification() : 1f);
        } else if (type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP
                || type == SDLEvents.SDL_EVENT_MOUSE_WHEEL) {
            if (!mouseCaptured) {
                handlePointerEvent(event, type, windowId);
            } else if (play != null) {
                handleGameMouse(event, type);
            }
        } else if (type == SDLEvents.SDL_EVENT_TEXT_INPUT && event.text().windowID() == windowId && !mouseCaptured) {
            String text = event.text().textString();
            if (text != null && !text.isEmpty()) {
                ui.typed(text);
            }
        } else if (type == SDLEvents.SDL_EVENT_MOUSE_MOTION && event.motion().windowID() == windowId) {
            mouseX = event.motion().x();
            mouseY = event.motion().y();
            ui.pointerMoved(mouseX * pixelScaleX(), mouseY * pixelScaleY());
            if (background != null) {
                SdlWindow.PixelSize pixels = window.pixelSize();
                background.pointer(mouseX * pixelScaleX() / Math.max(1, pixels.width()),
                        mouseY * pixelScaleY() / Math.max(1, pixels.height()));
            }
        } else if (type == SDLEvents.SDL_EVENT_WINDOW_MOUSE_LEAVE && event.window().windowID() == windowId) {
            ui.pointerLeft();
        } else if (type == SDLEvents.SDL_EVENT_KEY_DOWN && event.key().windowID() == windowId
                && !event.key().repeat() && menu.isCapturingBinding()) {
            // Bindings follow physical key positions; Esc cancels and Backspace leaves the action unbound.
            int scancode = event.key().scancode();
            String name = org.lwjgl.sdl.SDLKeyboard.SDL_GetScancodeName(scancode);
            finishBindingCapture(scancode == org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_ESCAPE ? null
                    : scancode == org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_BACKSPACE ? "none"
                    : name == null || name.isEmpty() ? null : "key:" + name);
        } else if (type == SDLEvents.SDL_EVENT_KEY_DOWN && event.key().windowID() == windowId
                && event.key().key() == SDLKeycode.SDLK_ESCAPE && !event.key().repeat()) {
            ui.post(() -> {
                menu.back();
                return kotlin.Unit.INSTANCE;
            });
        } else if ((type == SDLEvents.SDL_EVENT_KEY_DOWN || type == SDLEvents.SDL_EVENT_KEY_UP)
                && event.key().windowID() == windowId) {
            handleKey(event, type == SDLEvents.SDL_EVENT_KEY_DOWN);
        }
    }

    /** Keys that are not navigation: game bindings while playing, editing keys for text fields otherwise. */
    private void handleKey(SDL_Event event, boolean down) {
        ConfigSnapshot settings = config.snapshot();
        String binding = "key:" + org.lwjgl.sdl.SDLKeyboard.SDL_GetScancodeName(event.key().scancode());
        // A text field in game (the anvil's name, a sign) takes every key, the bound ones included.
        if (down && !event.key().repeat() && !menu.getTyping()) {
            if (binding.equals(settings.get(ClientSettings.KEY_FULLSCREEN))) {
                boolean next = !settings.get(ClientSettings.VIDEO_FULLSCREEN);
                config.set(ClientSettings.VIDEO_FULLSCREEN, next);
                return;
            }
            if (play != null && binding.equals(settings.get(ClientSettings.KEY_CLICK_GUI))
                    && (mouseCaptured || menu.getOverlay() == alkaline.ui.menu.Overlay.CLICK_GUI)) {
                ui.post(() -> {
                    menu.toggleClickGui();
                    return kotlin.Unit.INSTANCE;
                });
                return;
            }
            if (play != null && binding.equals(settings.get(ClientSettings.KEY_INVENTORY))) {
                if (mouseCaptured) {
                    play.openInventory();
                    publishHud(settings);
                    ui.post(() -> {
                        menu.openInventory();
                        return kotlin.Unit.INSTANCE;
                    });
                    return;
                }
                if (play.containerOpen()) {
                    ui.post(() -> {
                        menu.closeOverlay();
                        return kotlin.Unit.INSTANCE;
                    });
                    return;
                }
            }
            String chatStart = binding.equals(settings.get(ClientSettings.KEY_CHAT)) ? ""
                    : binding.equals(settings.get(ClientSettings.KEY_COMMAND)) ? "/" : null;
            if (play != null && mouseCaptured && chatStart != null) {
                ui.post(() -> {
                    menu.openChat(chatStart);
                    return kotlin.Unit.INSTANCE;
                });
                return;
            }
            alkaline.config.Setting.KeySetting[] hotbar = {ClientSettings.KEY_HOTBAR_1, ClientSettings.KEY_HOTBAR_2,
                    ClientSettings.KEY_HOTBAR_3, ClientSettings.KEY_HOTBAR_4, ClientSettings.KEY_HOTBAR_5,
                    ClientSettings.KEY_HOTBAR_6, ClientSettings.KEY_HOTBAR_7, ClientSettings.KEY_HOTBAR_8,
                    ClientSettings.KEY_HOTBAR_9};
            if (play != null && mouseCaptured) {
                pressedMovementKey(binding, settings);
                for (int slot = 0; slot < hotbar.length; slot++) {
                    if (binding.equals(settings.get(hotbar[slot]))) {
                        play.select(slot);
                        return;
                    }
                }
            }
            if (play != null && play.containerOpen()) {
                // GuiContainer.keyTyped: number keys swap with the hotbar, drop throws, pick block clones.
                for (int slot = 0; slot < hotbar.length; slot++) {
                    if (binding.equals(settings.get(hotbar[slot]))) {
                        play.windowInput().hotbarKey(slot);
                        publishHud(settings);
                        return;
                    }
                }
                if (binding.equals(settings.get(ClientSettings.KEY_DROP))) {
                    play.windowInput().throwKey(ctrlHeld());
                    publishHud(settings);
                    return;
                }
                if (binding.equals(settings.get(ClientSettings.KEY_PICK_BLOCK))) {
                    play.windowInput().pickKey();
                    publishHud(settings);
                    return;
                }
            }
        }
        if (!mouseCaptured) {
            int awt = switch (event.key().key()) {
                case SDLKeycode.SDLK_BACKSPACE -> java.awt.event.KeyEvent.VK_BACK_SPACE;
                case SDLKeycode.SDLK_DELETE -> java.awt.event.KeyEvent.VK_DELETE;
                case SDLKeycode.SDLK_RETURN, SDLKeycode.SDLK_KP_ENTER -> java.awt.event.KeyEvent.VK_ENTER;
                case SDLKeycode.SDLK_LEFT -> java.awt.event.KeyEvent.VK_LEFT;
                case SDLKeycode.SDLK_RIGHT -> java.awt.event.KeyEvent.VK_RIGHT;
                case SDLKeycode.SDLK_HOME -> java.awt.event.KeyEvent.VK_HOME;
                case SDLKeycode.SDLK_END -> java.awt.event.KeyEvent.VK_END;
                // Chat's history, completion and scrollback; elsewhere these keys would only move the focus.
                case SDLKeycode.SDLK_UP -> chatOpen() ? java.awt.event.KeyEvent.VK_UP : -1;
                case SDLKeycode.SDLK_DOWN -> chatOpen() ? java.awt.event.KeyEvent.VK_DOWN : -1;
                case SDLKeycode.SDLK_TAB -> chatOpen() ? java.awt.event.KeyEvent.VK_TAB : -1;
                case SDLKeycode.SDLK_PAGEUP -> chatOpen() ? java.awt.event.KeyEvent.VK_PAGE_UP : -1;
                case SDLKeycode.SDLK_PAGEDOWN -> chatOpen() ? java.awt.event.KeyEvent.VK_PAGE_DOWN : -1;
                default -> -1;
            };
            if (awt >= 0) {
                int mods = event.key().mod();
                int modifiers = ((mods & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_SHIFT) != 0 ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0)
                        | ((mods & org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL) != 0 ? java.awt.event.InputEvent.CTRL_DOWN_MASK : 0);
                ui.key(awt, down, modifiers);
            }
        }
    }

    private boolean chatOpen() {
        return menu.getOverlay() == alkaline.ui.menu.Overlay.CHAT;
    }

    /**
     * A game key was pressed: perspective cycles the camera, and sprint or sneak flip the toggle sprint & sneak
     * module's toggles.
     */
    private void pressedMovementKey(String binding, ConfigSnapshot settings) {
        if (binding.equals(settings.get(ClientSettings.KEY_PERSPECTIVE)) && background instanceof GameView view) {
            view.cyclePerspective();
        }
        if (binding.equals(settings.get(ClientSettings.KEY_SPRINT))) {
            play.sprintPressed();
        }
        if (binding.equals(settings.get(ClientSettings.KEY_SNEAK))) {
            play.sneakPressed();
        }
    }

    /** The mouse in game: attack and CPS on the bound buttons, the wheel through the hotbar. */
    private void handleGameMouse(SDL_Event event, int type) {
        ConfigSnapshot settings = config.snapshot();
        if (type == SDLEvents.SDL_EVENT_MOUSE_WHEEL) {
            float delta = event.wheel().y();
            if (delta != 0f) {
                // GuiIngame / InventoryPlayer.changeCurrentItem: scrolling down moves right.
                play.select(play.inventory().selected() + (delta < 0 ? 1 : -1));
            }
            return;
        }
        if (type != SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN) {
            return;
        }
        int button = Byte.toUnsignedInt(event.button().button());
        long now = System.currentTimeMillis();
        String name = switch (button) {
            case SDLMouse.SDL_BUTTON_LEFT -> "mouse:left";
            case SDLMouse.SDL_BUTTON_RIGHT -> "mouse:right";
            case SDLMouse.SDL_BUTTON_MIDDLE -> "mouse:middle";
            case SDLMouse.SDL_BUTTON_X1 -> "mouse:x1";
            default -> "mouse:x2";
        };
        pressedMovementKey(name, settings);
        String attack = settings.get(ClientSettings.KEY_ATTACK);
        String use = settings.get(ClientSettings.KEY_USE);
        if (attack.startsWith("mouse:") && PlaySession.mouseButton(attack) == button) {
            leftClicks.addLast(now);
            play.attack();
        } else if (use.startsWith("mouse:") && PlaySession.mouseButton(use) == button) {
            rightClicks.addLast(now);
            // Minecraft.runTick: every press uses at once; holding repeats every four ticks in the tick.
            play.use();
        }
    }

    /** Text input (and the IME) runs only while the interface has the keyboard. */
    private void textInput(boolean enabled) {
        if (enabled != textInput) {
            textInput = enabled;
            if (enabled) {
                if (!org.lwjgl.sdl.SDLKeyboard.SDL_StartTextInput(window.handle())) {
                    System.err.println("Text input unavailable: " + org.lwjgl.sdl.SDLError.SDL_GetError());
                }
            } else {
                org.lwjgl.sdl.SDLKeyboard.SDL_StopTextInput(window.handle());
            }
        }
    }


    /** Mouse buttons and the wheel for the interface (and key-binding capture) while the mouse is free. */
    private void handlePointerEvent(SDL_Event event, int type, int windowId) {
        if (type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN && event.button().windowID() == windowId
                && menu.isCapturingBinding()) {
            // A key binding is listening: the press is its new input, not a click on the interface.
            String binding = switch (Byte.toUnsignedInt(event.button().button())) {
                case SDLMouse.SDL_BUTTON_LEFT -> "mouse:left";
                case SDLMouse.SDL_BUTTON_RIGHT -> "mouse:right";
                case SDLMouse.SDL_BUTTON_MIDDLE -> "mouse:middle";
                case SDLMouse.SDL_BUTTON_X1 -> "mouse:x1";
                case SDLMouse.SDL_BUTTON_X2 -> "mouse:x2";
                default -> null;
            };
            finishBindingCapture(binding);
        } else if ((type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP)
                && event.button().windowID() == windowId) {
            int button = switch (Byte.toUnsignedInt(event.button().button())) {
                case SDLMouse.SDL_BUTTON_LEFT -> 0;
                case SDLMouse.SDL_BUTTON_RIGHT -> 1;
                case SDLMouse.SDL_BUTTON_MIDDLE -> 2;
                default -> -1;
            };
            if (button >= 0) {
                ui.pointerButton(event.button().x() * pixelScaleX(), event.button().y() * pixelScaleY(), button,
                        type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN);
            }
        } else if (type == SDLEvents.SDL_EVENT_MOUSE_WHEEL && event.wheel().windowID() == windowId) {
            // SDL wheel +y scrolls up; Compose expects positive deltas to scroll content down.
            ui.scroll(event.wheel().mouse_x() * pixelScaleX(), event.wheel().mouse_y() * pixelScaleY(),
                    -event.wheel().x(), -event.wheel().y());
        }
    }

    /** Hands a captured input (or null to cancel) to the listening key binding, on the UI thread. */
    private void finishBindingCapture(String binding) {
        String valid = binding == null || alkaline.config.Setting.KeySetting.valid(binding) ? binding : null;
        ui.post(() -> {
            menu.finishCapture(valid);
            return kotlin.Unit.INSTANCE;
        });
    }

    /** Scripted clicks for unattended UI checks: pushed as real SDL events so the full input path runs. */
    private record ScriptedClick(long atMillis, String action, float x, float y, String key) {
    }

    private void parseScript(String text) {
        for (String entry : text.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 3);
            if (parts.length == 3 && parts[1].equals("text")) {
                // Typed text, as the window system's text input delivers it (1500:text:hello).
                script.add(new ScriptedClick(Long.parseLong(parts[0]), "text", 0f, 0f, parts[2]));
                continue;
            }
            if (parts.length == 3 && List.of("key", "keydown", "keyup").contains(parts[1])) {
                // A key press (or one half of it, to hold a key) by physical key name, as SDL names scancodes
                // ("W", "Escape", "Left Shift").
                script.add(new ScriptedClick(Long.parseLong(parts[0]), parts[1], 0f, 0f, parts[2]));
                continue;
            }
            String[] position = parts.length == 3 ? parts[2].split(",") : new String[0];
            if (parts.length != 3 || !List.of("click", "move", "resize", "down", "up", "rclick", "rdown", "rup", "minimize",
                    "restore").contains(parts[1])
                    || position.length != 2) {
                throw new IllegalArgumentException("Script entries look like 1500:click:120,300, 1600:move:120,300, "
                        + "1700:down:120,300, 1800:up:200,300 (rclick, rdown, rup for the right button), "
                        + "2000:resize:900,600 or 2500:key:Q, not " + entry);
            }
            script.add(new ScriptedClick(Long.parseLong(parts[0]), parts[1],
                    Float.parseFloat(position[0]), Float.parseFloat(position[1]), null));
        }
    }

    private void injectScriptedInput() {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        while (!script.isEmpty() && script.peek().atMillis() <= elapsed) {
            ScriptedClick click = script.poll();
            if (click.action().equals("text")) {
                ui.typed(click.key());
                continue;
            }
            if (click.action().startsWith("key")) {
                int scancode = org.lwjgl.sdl.SDLKeyboard.SDL_GetScancodeFromName(click.key());
                int keycode = org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false);
                int[] types = switch (click.action()) {
                    case "keydown" -> new int[]{SDLEvents.SDL_EVENT_KEY_DOWN};
                    case "keyup" -> new int[]{SDLEvents.SDL_EVENT_KEY_UP};
                    default -> new int[]{SDLEvents.SDL_EVENT_KEY_DOWN, SDLEvents.SDL_EVENT_KEY_UP};
                };
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    SDL_Event synthetic = SDL_Event.calloc(stack);
                    for (int type : types) {
                        synthetic.clear();
                        synthetic.key().type(type).windowID(window.windowId()).scancode(scancode).key(keycode)
                                .down(type == SDLEvents.SDL_EVENT_KEY_DOWN);
                        SDLEvents.SDL_PushEvent(synthetic);
                    }
                }
                continue;
            }
            if (click.action().equals("minimize")) {
                SDLVideo.SDL_MinimizeWindow(window.handle());
                continue;
            }
            if (click.action().equals("restore")) {
                SDLVideo.SDL_RestoreWindow(window.handle());
                continue;
            }
            if (click.action().equals("resize")) {
                SDLVideo.SDL_SetWindowSize(window.handle(), Math.round(click.x()), Math.round(click.y()));
                continue;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_Event synthetic = SDL_Event.calloc(stack);
                synthetic.motion().type(SDLEvents.SDL_EVENT_MOUSE_MOTION).windowID(window.windowId())
                        .x(click.x()).y(click.y());
                SDLEvents.SDL_PushEvent(synthetic);
                if (click.action().equals("move")) {
                    continue;
                }
                boolean right = click.action().startsWith("r");
                int[] buttonTypes = switch (right ? click.action().substring(1) : click.action()) {
                    case "down" -> new int[]{SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN};
                    case "up" -> new int[]{SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP};
                    default -> new int[]{SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN, SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP};
                };
                byte mouseButton = (byte) (right ? SDLMouse.SDL_BUTTON_RIGHT : SDLMouse.SDL_BUTTON_LEFT);
                for (int type : buttonTypes) {
                    synthetic.clear();
                    synthetic.button().type(type).windowID(window.windowId()).button(mouseButton)
                            .down(type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN).clicks((byte) 1)
                            .x(click.x()).y(click.y());
                    SDLEvents.SDL_PushEvent(synthetic);
                }
            }
        }
    }

    /** SDL reports pointer positions in window points; the UI and framebuffer use pixels. */
    private void refreshPixelScale() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer width = stack.mallocInt(1);
            java.nio.IntBuffer height = stack.mallocInt(1);
            if (!SDLVideo.SDL_GetWindowSize(window.handle(), width, height) || width.get(0) <= 0 || height.get(0) <= 0) {
                return;
            }
            SdlWindow.PixelSize pixels = window.pixelSize();
            scaleX = pixels.width() / (float) width.get(0);
            scaleY = pixels.height() / (float) height.get(0);
        }
    }

    private float pixelScaleX() {
        return scaleX;
    }

    private float pixelScaleY() {
        return scaleY;
    }

    private void saveScreenshotAndExit(RgbaImage image) {
        try {
            writePng(image, options.screenshot());
            System.out.printf("Saved screenshot %s after %d frames (%.0f fps average)%n", options.screenshot(), frames,
                    frames / ((System.nanoTime() - startNanos) / 1e9));
        } catch (IOException failure) {
            System.err.println("Screenshot failed: " + failure.getMessage());
        }
        running = false;
    }

    static void writePng(RgbaImage image, Path destination) throws IOException {
        BufferedImage buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        byte[] pixels = image.copyPixels();
        int[] argb = new int[image.width() * image.height()];
        for (int index = 0; index < argb.length; index++) {
            int offset = index * 4;
            argb[index] = (pixels[offset + 3] & 0xFF) << 24 | (pixels[offset] & 0xFF) << 16
                    | (pixels[offset + 1] & 0xFF) << 8 | (pixels[offset + 2] & 0xFF);
        }
        buffered.setRGB(0, 0, image.width(), image.height(), argb, 0, image.width());
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(buffered, "png", destination.toFile())) {
            throw new IOException("No PNG writer available.");
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (options.stats()) {
            System.out.println("Presenting with " + renderer.activePresentMode()
                    + (window.xwayland() ? " (XWayland)" : "")
                    + (renderer.presentWaitSupported() ? ", present wait available" : "")
                    + (pacer.paced() > 0 ? String.format(", V-Sync paced %d frames (%d queued), margin %.2f ms, %d missed refreshes",
                            pacer.paced(), pacer.lag(), pacer.marginNanos() / 1e6, pacer.misses()) : ""));
            System.out.println(stats.report());
            System.out.println(renderer.textureReport());
            String sections = renderer.gpuSectionReport();
            if (sections != null) {
                System.out.println(sections);
            }
            System.out.println(audio.describe());
            System.out.printf("UI: %d Compose frames uploaded, %d rows%n", compositor.uiUploads(),
                    compositor.uploadedRows());
            long refreshes = compositor.blurRefreshes();
            System.out.printf("Glass: blurred %d times in %d frames, %.1f%% of the full chain's pixels per refresh%n",
                    refreshes, frames, refreshes == 0 ? 0.0
                            : 100.0 * compositor.blurredPixels() / refreshes / Math.max(1, compositor.fullBlurPixels()));
            if (background instanceof OverworldBackground overworld) {
                System.out.println(overworld.describe());
            }
        }
        if (hudGlint != null) {
            hudGlint.close();
        }
        compositor.close();
        ui.close();
        audio.close();
        services.close();
        skins.close();
        try {
            profiles.close();
        } catch (IOException failure) {
            System.err.println("Saving the profile failed: " + failure.getMessage());
        }
        if (background != null) {
            background.close();
            background = null;
        }
        try {
            AssetSource opened = assets != null ? assets : assetLoad.getNow(null);
            if (opened != null) {
                opened.close();
            }
            if (vanillaAssets != null) {
                vanillaAssets.close();
            }
        } catch (IOException failure) {
            System.err.println("Closing assets failed: " + failure.getMessage());
        }
    }
}
