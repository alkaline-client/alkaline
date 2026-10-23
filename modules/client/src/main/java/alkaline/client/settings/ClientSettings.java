package alkaline.client.settings;

import alkaline.config.ConfigSchema;
import alkaline.config.Setting;

/**
 * Alkaline's persisted client settings, schema version 5. Adding a setting is compatible; renaming or
 * re-interpreting one requires a new schema version and a migration in {@link #SCHEMA}.
 *
 * <p>Entries that Minecraft 1.8.9 (or OptiFine, for the sky and fog parts) also has take its ranges, defaults
 * and value labels: render distance 2-32 chunks, max framerate 10-260 in steps of 10 with 260 unlimited,
 * sensitivity 0-200 %, FOV 30-110, brightness Moody to Bright.
 *
 * <p>Version 2 renamed the themes {@code dark}/{@code light} to the generated Monet schemes. Version 3 moved
 * the frame limit to vanilla's range, where the maximum (260) rather than 0 means unlimited. Version 4 moved the FPS
 * counter into the HUD's FPS module (profiles), so {@code interface.fps_counter} is dropped. Version 5 replaced the
 * three-way {@code video.sync} (V-Sync, Low latency, Uncapped) with the {@code video.vsync} switch: the present mode
 * behind V-Sync off is chosen for the platform (decision 0010).
 */
public final class ClientSettings {
    public enum Background { FLUID, PANORAMA, BLUE_PLANET, OVERWORLD }

    /** The window system on Linux: native Wayland on Wayland desktops (X11 if it fails), or one forced. */
    public enum WindowSystem { AUTO, WAYLAND, X11 }

    /**
     * Interface color themes. Each names a scheme generator and a brightness; today both are Material You
     * ("Monet") Expressive schemes, 2025 color spec, generated from Alkaline's seed color.
     */
    public enum Theme {
        MONET_LIGHT(false), MONET_DARK(true);

        private final boolean dark;

        Theme(boolean dark) {
            this.dark = dark;
        }

        public boolean dark() {
            return dark;
        }
    }

    /** Vanilla's view bobbing, plus Minimal: the hand bobs while the camera stays level. */
    public enum ViewBobbing { OFF, MINIMAL, FULL }

    /** Vanilla's cloud modes: flat (Fast) or volumetric (Fancy) clouds. */
    public enum Clouds { OFF, FAST, FANCY }

    /** Vanilla's particle levels: Decreased skips a third of spawns, Minimal keeps only some. */
    public enum Particles { ALL, DECREASED, MINIMAL }

    /** Vanilla's hurt overlay on entities: red at 30% (RendererLivingEntity's 1.0, 0.0, 0.0, 0.3). */
    public static final int VANILLA_HIT_COLOR = 0x4CFF0000;

    /** The frame limit that means unlimited, as vanilla's slider ends. */
    public static final int FRAME_LIMIT_UNLIMITED = 260;

    private static final ConfigSchema.Builder BUILDER = ConfigSchema.builder(5)
            .migration(1, settings -> settings.computeIfPresent("interface.theme",
                    (key, value) -> "light".equals(value) ? "monet_light" : "monet_dark"))
            .migration(2, settings -> settings.computeIfPresent("video.frame_limit", (key, value) -> {
                if (!(value instanceof Long limit)) {
                    return value;
                }
                return limit <= 0 ? (long) FRAME_LIMIT_UNLIMITED : Math.max(10L, Math.min(FRAME_LIMIT_UNLIMITED, limit));
            }))
            .migration(3, settings -> settings.remove("interface.fps_counter"))
            .migration(4, settings -> {
                Object sync = settings.remove("video.sync");
                if (sync != null) {
                    settings.put("video.vsync", "vsync".equals(sync));
                }
            });

    // Categories are generic game-settings sections; menu-only options sit beside the in-game ones they
    // belong with, and tuning knobs live under Advanced.

    // Video
    public static final Setting.BooleanSetting VIDEO_VSYNC = BUILDER.add(Setting.bool(
            "video.vsync", "Video", "V-Sync",
            "Locks the frame rate to the display. Off draws as fast as the computer can, without tearing where the "
                    + "display allows it.", false));
    public static final Setting.IntSetting VIDEO_FRAME_LIMIT = BUILDER.add(Setting.integer(
            "video.frame_limit", "Video", "Max framerate", "Caps the frame rate to save power.",
            FRAME_LIMIT_UNLIMITED, 10, FRAME_LIMIT_UNLIMITED));
    public static final Setting.IntSetting WORLD_RENDER_DISTANCE = BUILDER.add(Setting.integer(
            "world.render_distance", "Video", "Render distance", "Radius of loaded terrain, in chunks.",
            8, 2, 32));
    public static final Setting.FloatSetting WORLD_FOV = BUILDER.add(Setting.decimal(
            "world.fov", "Video", "FOV", "Vertical field of view in game, in degrees.", 70f, 30f, 110f));
    public static final Setting.FloatSetting WORLD_BRIGHTNESS = BUILDER.add(Setting.decimal(
            "world.brightness", "Video", "Brightness", "Lifts dark areas of the world.", 0f, 0f, 1f));
    public static final Setting.BooleanSetting WORLD_AMBIENT_OCCLUSION = BUILDER.add(Setting.bool(
            "world.ambient_occlusion", "Video", "Smooth lighting", "Ambient occlusion in block corners.", true));
    public static final Setting.BooleanSetting WORLD_FOG = BUILDER.add(Setting.bool(
            "world.fog", "Video", "Fog", "Fades terrain into the sky at the render distance.", true));
    public static final Setting.FloatSetting WORLD_FOG_START = BUILDER.add(Setting.decimal(
            "world.fog_start", "Video", "Fog start", "Share of the render distance where fog begins. Vanilla uses 0.75.",
            0.75f, 0.2f, 0.8f));
    public static final Setting.BooleanSetting WORLD_SKY = BUILDER.add(Setting.bool(
            "world.sky", "Video", "Sky", "The sky dome above the horizon; off shows the fog color.", true));
    public static final Setting.BooleanSetting WORLD_SUN_MOON = BUILDER.add(Setting.bool(
            "world.sun_moon", "Video", "Sun & moon", "The sun, the moon and the sunrise glow.", true));
    public static final Setting.BooleanSetting WORLD_STARS = BUILDER.add(Setting.bool(
            "world.stars", "Video", "Stars", "Stars in the night sky.", true));
    public static final Setting.BooleanSetting WORLD_RAIN = BUILDER.add(Setting.bool(
            "world.rain", "Video", "Rain & snow", "Falling rain and snow. The sky still darkens in bad weather.",
            true));
    public static final Setting.EnumSetting<Clouds> WORLD_CLOUDS = BUILDER.add(Setting.choice(
            "world.clouds", "Video", "Clouds", "Fast draws flat clouds; Fancy gives them depth.", Clouds.class,
            Clouds.FANCY));
    public static final Setting.FloatSetting WORLD_CLOUD_HEIGHT = BUILDER.add(Setting.decimal(
            "world.cloud_height", "Video", "Cloud height", "Raises clouds above vanilla's y = 128, up to y = 256.",
            0f, 0f, 1f));
    public static final Setting.EnumSetting<Particles> WORLD_PARTICLES = BUILDER.add(Setting.choice(
            "world.particles", "Video", "Particles", "How many particles effects, blocks and the weather spawn.",
            Particles.class, Particles.ALL));
    public static final Setting.EnumSetting<ViewBobbing> WORLD_VIEW_BOBBING = BUILDER.add(Setting.choice(
            "world.view_bobbing", "Video", "View bobbing",
            "The view sways as you walk. Minimal sways only the hand and keeps the camera level.",
            ViewBobbing.class, ViewBobbing.FULL));
    public static final Setting.BooleanSetting WORLD_HURT_SHAKE = BUILDER.add(Setting.bool(
            "world.hurt_shake", "Video", "Hurt shake", "The camera tilts when you take damage.", true));
    public static final Setting.ColorSetting WORLD_HIT_COLOR = BUILDER.add(Setting.color(
            "world.hit_color", "Video", "Hit color",
            "Tint over players and mobs while they are hurt. Vanilla is red at 30% (#4CFF0000).", VANILLA_HIT_COLOR));
    public static final Setting.BooleanSetting VIDEO_FULLSCREEN = BUILDER.add(Setting.bool(
            "video.fullscreen", "Video", "Fullscreen", "Fills the screen with the game. Toggle fullscreen switches it too.",
            false));

    // Interface
    public static final Setting.EnumSetting<Theme> INTERFACE_THEME = BUILDER.add(Setting.choice(
            "interface.theme", "Interface", "Theme", "Material You color scheme for menus and the HUD.", Theme.class,
            Theme.MONET_DARK));
    public static final Setting.IntSetting GUI_SCALE = BUILDER.add(Setting.integer(
            "interface.gui_scale", "Interface", "GUI scale",
            "Auto uses the largest size the window fits. Larger sizes are capped to fit.",
            GuiScale.AUTO, GuiScale.AUTO, GuiScale.MAX_STEP));
    public static final Setting.EnumSetting<Background> MENU_BACKGROUND = BUILDER.add(Setting.choice(
            "menu.background", "Interface", "Menu background", "Animated scene behind the menus.",
            Background.class, Background.FLUID));
    public static final Setting.BooleanSetting MENU_GLASS = BUILDER.add(Setting.bool(
            "menu.glass", "Interface", "Frosted surfaces",
            "Blur the scene behind menu panels and buttons. HUD elements have their own Blur option.", true));
    /** The HUD's redraw cap, in Hz; {@link #HUD_RATE_DISPLAY} follows the display's refresh rate. */
    public static final Setting.IntSetting HUD_RATE = BUILDER.add(Setting.integer(
            "interface.hud_rate", "Interface", "HUD frame rate",
            "How often the HUD and its blur redraw. Frames in between reuse the cached HUD.",
            0, 0, 240));
    public static final int HUD_RATE_DISPLAY = 0;

    // Controls: vanilla 1.8.9's default bindings, by physical key. Ids name the group the page shows them under.
    public static final Setting.FloatSetting MOUSE_SENSITIVITY = BUILDER.add(Setting.decimal(
            "controls.mouse.sensitivity", "Controls", "Mouse sensitivity", "How far the camera turns as the mouse moves.",
            0.5f, 0f, 1f));
    public static final Setting.BooleanSetting INVERT_MOUSE = BUILDER.add(Setting.bool(
            "controls.mouse.invert", "Controls", "Invert mouse", "Moving the mouse up looks down.", false));
    public static final Setting.KeySetting KEY_FORWARD = BUILDER.add(Setting.key(
            "controls.movement.forward", "Controls", "Walk forward", "", "key:W"));
    public static final Setting.KeySetting KEY_BACK = BUILDER.add(Setting.key(
            "controls.movement.back", "Controls", "Walk backward", "", "key:S"));
    public static final Setting.KeySetting KEY_LEFT = BUILDER.add(Setting.key(
            "controls.movement.left", "Controls", "Strafe left", "", "key:A"));
    public static final Setting.KeySetting KEY_RIGHT = BUILDER.add(Setting.key(
            "controls.movement.right", "Controls", "Strafe right", "", "key:D"));
    public static final Setting.KeySetting KEY_JUMP = BUILDER.add(Setting.key(
            "controls.movement.jump", "Controls", "Jump", "", "key:Space"));
    public static final Setting.KeySetting KEY_SNEAK = BUILDER.add(Setting.key(
            "controls.movement.sneak", "Controls", "Sneak", "", "key:Left Shift"));
    public static final Setting.KeySetting KEY_SPRINT = BUILDER.add(Setting.key(
            "controls.movement.sprint", "Controls", "Sprint", "", "key:Left Ctrl"));
    public static final Setting.BooleanSetting AUTO_SPRINT = BUILDER.add(Setting.bool(
            "controls.movement.auto_sprint", "Controls", "Auto sprint",
            "Sprints whenever you walk forward, without holding Sprint.", false));
    public static final Setting.BooleanSetting JUMP_DELAY = BUILDER.add(Setting.bool(
            "controls.movement.jump_delay", "Controls", "Jump delay",
            "Vanilla waits 10 ticks between jumps while Jump is held. Off jumps again on landing.", true));
    public static final Setting.KeySetting KEY_ATTACK = BUILDER.add(Setting.key(
            "controls.gameplay.attack", "Controls", "Attack / destroy", "", "mouse:left"));
    public static final Setting.KeySetting KEY_USE = BUILDER.add(Setting.key(
            "controls.gameplay.use", "Controls", "Use item / place block", "", "mouse:right"));
    public static final Setting.KeySetting KEY_PICK_BLOCK = BUILDER.add(Setting.key(
            "controls.gameplay.pick_block", "Controls", "Pick block", "", "mouse:middle"));
    public static final Setting.KeySetting KEY_INVENTORY = BUILDER.add(Setting.key(
            "controls.inventory.inventory", "Controls", "Open inventory", "", "key:E"));
    public static final Setting.KeySetting KEY_DROP = BUILDER.add(Setting.key(
            "controls.inventory.drop", "Controls", "Drop item", "", "key:Q"));
    public static final Setting.KeySetting KEY_HOTBAR_1 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_1", "Controls", "Hotbar slot 1", "", "key:1"));
    public static final Setting.KeySetting KEY_HOTBAR_2 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_2", "Controls", "Hotbar slot 2", "", "key:2"));
    public static final Setting.KeySetting KEY_HOTBAR_3 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_3", "Controls", "Hotbar slot 3", "", "key:3"));
    public static final Setting.KeySetting KEY_HOTBAR_4 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_4", "Controls", "Hotbar slot 4", "", "key:4"));
    public static final Setting.KeySetting KEY_HOTBAR_5 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_5", "Controls", "Hotbar slot 5", "", "key:5"));
    public static final Setting.KeySetting KEY_HOTBAR_6 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_6", "Controls", "Hotbar slot 6", "", "key:6"));
    public static final Setting.KeySetting KEY_HOTBAR_7 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_7", "Controls", "Hotbar slot 7", "", "key:7"));
    public static final Setting.KeySetting KEY_HOTBAR_8 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_8", "Controls", "Hotbar slot 8", "", "key:8"));
    public static final Setting.KeySetting KEY_HOTBAR_9 = BUILDER.add(Setting.key(
            "controls.inventory.hotbar_9", "Controls", "Hotbar slot 9", "", "key:9"));
    public static final Setting.KeySetting KEY_CHAT = BUILDER.add(Setting.key(
            "controls.multiplayer.chat", "Controls", "Open chat", "", "key:T"));
    public static final Setting.KeySetting KEY_COMMAND = BUILDER.add(Setting.key(
            "controls.multiplayer.command", "Controls", "Open command", "", "key:/"));
    public static final Setting.KeySetting KEY_PLAYER_LIST = BUILDER.add(Setting.key(
            "controls.multiplayer.player_list", "Controls", "List players", "", "key:Tab"));
    public static final Setting.KeySetting KEY_PERSPECTIVE = BUILDER.add(Setting.key(
            "controls.miscellaneous.perspective", "Controls", "Toggle perspective", "", "key:F5"));
    public static final Setting.KeySetting KEY_SMOOTH_CAMERA = BUILDER.add(Setting.key(
            "controls.miscellaneous.smooth_camera", "Controls", "Cinematic camera", "", "key:F8"));
    public static final Setting.KeySetting KEY_SCREENSHOT = BUILDER.add(Setting.key(
            "controls.miscellaneous.screenshot", "Controls", "Take screenshot", "", "key:F2"));
    public static final Setting.KeySetting KEY_FULLSCREEN = BUILDER.add(Setting.key(
            "controls.miscellaneous.fullscreen", "Controls", "Toggle fullscreen", "", "key:F11"));
    public static final Setting.KeySetting KEY_CLICK_GUI = BUILDER.add(Setting.key(
            "controls.miscellaneous.click_gui", "Controls", "Modules", "Opens modules, profiles and the HUD editor in game.",
            "key:Right Shift"));

    // Advanced
    public static final Setting.IntSetting PANORAMA_SAMPLES = BUILDER.add(Setting.integer(
            "panorama.samples_per_axis", "Advanced", "Panorama jitter samples",
            "Vanilla renders the cube 8x8 times for a soft edge.", 8, 1, 8));
    public static final Setting.IntSetting PANORAMA_BLUR = BUILDER.add(Setting.integer(
            "panorama.blur_passes", "Advanced", "Panorama blur passes", "Vanilla uses 7.", 7, 0, 11));
    public static final Setting.FloatSetting PANORAMA_SPEED = BUILDER.add(Setting.decimal(
            "panorama.speed", "Advanced", "Panorama rotation speed", "1 matches vanilla.", 1f, 0f, 4f));
    public static final Setting.ColorSetting PANORAMA_TOP_A = BUILDER.add(Setting.color(
            "panorama.overlay_a_top", "Advanced", "Panorama light gradient, top", "Vanilla #80FFFFFF.", 0x80FFFFFF));
    public static final Setting.ColorSetting PANORAMA_BOTTOM_A = BUILDER.add(Setting.color(
            "panorama.overlay_a_bottom", "Advanced", "Panorama light gradient, bottom", "Vanilla #00FFFFFF.", 0x00FFFFFF));
    public static final Setting.ColorSetting PANORAMA_TOP_B = BUILDER.add(Setting.color(
            "panorama.overlay_b_top", "Advanced", "Panorama dark gradient, top", "Vanilla #00000000.", 0x00000000));
    public static final Setting.ColorSetting PANORAMA_BOTTOM_B = BUILDER.add(Setting.color(
            "panorama.overlay_b_bottom", "Advanced", "Panorama dark gradient, bottom", "Vanilla #80000000.", 0x80000000));
    public static final Setting.EnumSetting<WindowSystem> WINDOW_SYSTEM = BUILDER.addRestartRequired(Setting.choice(
            "video.window_system", "Advanced", "Window system",
            "Linux only. Auto uses native Wayland on Wayland desktops and X11 elsewhere.",
            WindowSystem.class, WindowSystem.AUTO));
    public static final Setting.BooleanSetting DEVELOPER_VALIDATION = BUILDER.addRestartRequired(Setting.bool(
            "developer.vulkan_validation", "Advanced", "Vulkan validation",
            "Loads the Khronos validation layer when installed. Slow; for debugging the renderer.", false));

    /** Selected resource packs by id ({@code <folder>/<file>}), one per line, the first overriding the rest. */
    public static final Setting.StringSetting RESOURCE_PACKS = BUILDER.add(Setting.text(
            "resource_packs.selected", "Resource packs", "Selected packs",
            "Packs layered over the vanilla assets, first one winning.", "", 16384));

    // Audio: vanilla's sound categories (SoundCategory), 0..1. Music and records share one slider, off by default.
    public static final Setting.FloatSetting AUDIO_MASTER = BUILDER.add(Setting.decimal(
            "audio.master", "Audio", "Master volume", "Scales every sound.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_PLAYERS = BUILDER.add(Setting.decimal(
            "audio.players", "Audio", "Players", "Hits and hurt sounds, eating, drinking, item pickups.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_HOSTILE = BUILDER.add(Setting.decimal(
            "audio.hostile", "Audio", "Hostile creatures", "Zombies, skeletons, creepers and other monsters.",
            1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_NEUTRAL = BUILDER.add(Setting.decimal(
            "audio.neutral", "Audio", "Friendly creatures",
            "Footsteps, bows and arrows, splash potions, animals and villagers.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_BLOCKS = BUILDER.add(Setting.decimal(
            "audio.blocks", "Audio", "Blocks", "Breaking and placing blocks, doors, chests, explosions.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_WEATHER = BUILDER.add(Setting.decimal(
            "audio.weather", "Audio", "Weather", "Rain and thunder.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_AMBIENT = BUILDER.add(Setting.decimal(
            "audio.ambient", "Audio", "Ambient", "Cave sounds and fireworks.", 1f, 0f, 1f));
    public static final Setting.FloatSetting AUDIO_MUSIC = BUILDER.add(Setting.decimal(
            "audio.music", "Audio", "Music & jukeboxes", "Jukebox records and note blocks.", 0f, 0f, 1f));
    /** The volume sliders, for their percentage labels. */
    public static final java.util.List<Setting.FloatSetting> AUDIO_VOLUMES = java.util.List.of(AUDIO_MASTER,
            AUDIO_PLAYERS, AUDIO_HOSTILE, AUDIO_NEUTRAL, AUDIO_BLOCKS, AUDIO_WEATHER, AUDIO_AMBIENT, AUDIO_MUSIC);

    public static final ConfigSchema SCHEMA = BUILDER.build();

    private ClientSettings() {
    }
}
