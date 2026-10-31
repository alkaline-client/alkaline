package alkaline.client.modules;

import alkaline.config.ConfigSchema;
import alkaline.config.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every module Alkaline ships, and the profile schema that stores them (version 1). A profile holds, per module,
 * {@code <id>.enabled}, its options as {@code <id>.<option>}, and for HUD modules {@code <id>.hud.anchor},
 * {@code .x}, {@code .y} and {@code .scale}. Adding a module or an option is compatible; renaming or
 * re-interpreting one needs a new schema version and a migration.
 *
 * <p>The HUD draws in dp; vanilla-sized elements (hotbar, status bars) are two dp per vanilla GUI pixel at scale
 * 1, which matches vanilla's Normal GUI scale at the GUI scale Auto picks for a 1280x720 window.
 */
public final class Modules {
    private static final ConfigSchema.Builder BUILDER = ConfigSchema.builder(1);
    private static final List<Module> ALL = new ArrayList<>();
    private static final Map<Module, Setting.BooleanSetting> BLUR = new java.util.HashMap<>();

    // ---- HUD: readouts ------------------------------------------------------------------------------------

    public static final Module FPS = hud("fps", "FPS", "Frames per second.", true,
            HudAnchor.TOP_LEFT, 6, 6);
    public static final Setting.BooleanSetting FPS_BLUR = blur(FPS, true);
    public static final Module COORDINATES = hud("coordinates", "Coordinates", "Your block position.", false,
            HudAnchor.TOP_LEFT, 6, 34);
    public static final Setting.BooleanSetting COORDINATES_BLUR = blur(COORDINATES, true);
    public static final Setting.BooleanSetting COORDINATES_FACING = option(COORDINATES, Setting.bool(
            "coordinates.facing", COORDINATES.title(), "Facing", "The direction you look, as N, NE, E…", true));
    public static final Module CPS = hud("cps", "CPS", "Clicks per second over the last second.", false,
            HudAnchor.TOP_LEFT, 6, 62);
    public static final Setting.BooleanSetting CPS_BLUR = blur(CPS, true);
    public static final Setting.BooleanSetting CPS_RIGHT = option(CPS, Setting.bool(
            "cps.right_button", CPS.title(), "Right button", "Counts right clicks beside left clicks.", true));
    public static final Module SPEED = hud("speed", "Speed", "How fast you move, in blocks per second.", false,
            HudAnchor.TOP_LEFT, 6, 90);
    public static final Setting.BooleanSetting SPEED_BLUR = blur(SPEED, true);
    public static final Module KEYSTROKES = hud("keystrokes", "Keystrokes",
            "Movement keys and mouse buttons, lit while held.", false, HudAnchor.TOP_LEFT, 6, 118);
    public static final Setting.BooleanSetting KEYSTROKES_BLUR = blur(KEYSTROKES, true);
    public static final Setting.BooleanSetting KEYSTROKES_MOUSE = option(KEYSTROKES, Setting.bool(
            "keystrokes.mouse", KEYSTROKES.title(), "Mouse buttons", "Left and right buttons below the keys.", true));
    public static final Setting.BooleanSetting KEYSTROKES_SPACE = option(KEYSTROKES, Setting.bool(
            "keystrokes.space", KEYSTROKES.title(), "Jump", "A bar for the jump key.", true));
    public static final Setting.BooleanSetting KEYSTROKES_CPS = option(KEYSTROKES, Setting.bool(
            "keystrokes.cps", KEYSTROKES.title(), "Clicks per second", "Shown on the mouse buttons.", true));
    public static final Module PING = hud("ping", "Ping", "Your latency as the server reports it.", false,
            HudAnchor.TOP_RIGHT, -6, 6);
    public static final Setting.BooleanSetting PING_BLUR = blur(PING, true);
    public static final Module CLOCK = hud("clock", "Clock", "The time on this computer.", false,
            HudAnchor.TOP_RIGHT, -6, 34);
    public static final Setting.BooleanSetting CLOCK_BLUR = blur(CLOCK, true);
    public static final Setting.BooleanSetting CLOCK_24_HOUR = option(CLOCK, Setting.bool(
            "clock.twenty_four_hour", CLOCK.title(), "24-hour clock", "Off shows AM and PM.", true));
    public static final Module SERVER = hud("server", "Server", "The address you joined.", false,
            HudAnchor.TOP_RIGHT, -6, 62);
    public static final Setting.BooleanSetting SERVER_BLUR = blur(SERVER, true);
    public static final Module MUSIC = hud("music", "Now playing", "The track the music player plays, with its progress.",
            false, HudAnchor.TOP_RIGHT, -6, 90);
    public static final Setting.BooleanSetting MUSIC_BLUR = blur(MUSIC, true);
    public static final Setting.BooleanSetting MUSIC_COVER = option(MUSIC, Setting.bool(
            "music.cover", MUSIC.title(), "Cover", "Shows the track's cover beside its title.", true));
    public static final Module POTIONS = hud("potions", "Potion effects", "Active effects and their time left.", false,
            HudAnchor.TOP_RIGHT, -6, 90);
    public static final Setting.BooleanSetting POTIONS_BLUR = blur(POTIONS, true);
    public static final Module NOTIFICATIONS = hud("notifications", "Notifications",
            "Short messages from Alkaline and your plugins, such as a resource pack finishing.", true,
            HudAnchor.BOTTOM_RIGHT, -6, -6);
    public static final Setting.BooleanSetting NOTIFICATIONS_BLUR = blur(NOTIFICATIONS, true);
    public static final Setting.IntSetting NOTIFICATIONS_SECONDS = option(NOTIFICATIONS, Setting.integer(
            "notifications.seconds", NOTIFICATIONS.title(), "Duration", "Seconds each notification stays.", 5, 2, 15));

    // ---- HUD: vanilla's elements --------------------------------------------------------------------------

    public static final Module SCOREBOARD = hud("scoreboard", "Scoreboard", "The server's sidebar objective.", true,
            HudAnchor.RIGHT, -2, 0);
    public static final Setting.BooleanSetting SCOREBOARD_BLUR = blur(SCOREBOARD, true);
    /** Port of KotlinizedMCP's "Score Point Display": the red numbers beside sidebar entries. */
    public static final Setting.BooleanSetting SCOREBOARD_NUMBERS = option(SCOREBOARD, Setting.bool(
            "scoreboard.numbers", SCOREBOARD.title(), "Score numbers", "The red points beside each line.", true));
    public static final Module CHAT = hud("chat", "Chat", "Messages from the server and other players.", true,
            HudAnchor.BOTTOM_LEFT, 4, -60);
    public static final Setting.BooleanSetting CHAT_BLUR = blur(CHAT, false);
    public static final Setting.IntSetting CHAT_LINES = option(CHAT, Setting.integer(
            "chat.lines", CHAT.title(), "Lines", "Recent messages shown at once.", 10, 3, 20));
    public static final Setting.IntSetting CHAT_FADE_SECONDS = option(CHAT, Setting.integer(
            "chat.fade_seconds", CHAT.title(), "Fade after", "Seconds a message stays. Vanilla uses 10.", 10, 3, 60));
    public static final Module STATUS = hud("status", "Health & hunger",
            "Health, armor, hunger, air and experience above the hotbar.", true, HudAnchor.BOTTOM, 0, -50);
    public static final Module HOTBAR = hud("hotbar", "Hotbar", "Your nine hotbar slots.", true,
            HudAnchor.BOTTOM, 0, -2);
    public static final Setting.BooleanSetting HOTBAR_BLUR = blur(HOTBAR, true);

    // ---- gameplay -----------------------------------------------------------------------------------------

    public static final Module ZOOM = gameplay("zoom", "Zoom", "Narrows the view while the zoom key is held.", true);
    public static final Setting.KeySetting ZOOM_KEY = option(ZOOM, Setting.key(
            "zoom.key", ZOOM.title(), "Zoom key", "", "key:C"));
    public static final Setting.FloatSetting ZOOM_FACTOR = option(ZOOM, Setting.decimal(
            "zoom.factor", ZOOM.title(), "Magnification", "How many times closer the view gets.", 4f, 2f, 10f));
    public static final Setting.BooleanSetting ZOOM_SMOOTH = option(ZOOM, Setting.bool(
            "zoom.smooth", ZOOM.title(), "Smooth", "Eases in and out instead of snapping.", true));

    public static final Module ANIMATIONS = gameplay("animations", "Animations",
            "How the held item moves: 1.7-style block-hitting, and where the item sits in view.", false);
    public static final Setting.BooleanSetting ANIMATIONS_BLOCK_HIT = option(ANIMATIONS, Setting.bool(
            "animations.block_hit", ANIMATIONS.title(), "Block-hit",
            "Attack swings stay visible while blocking with a sword, as in 1.7; blocking never hides a swing.",
            true));
    public static final Setting.BooleanSetting ANIMATIONS_USE_SWING = option(ANIMATIONS, Setting.bool(
            "animations.use_swing", ANIMATIONS.title(), "Swing while using",
            "Attack swings stay visible while eating, drinking or drawing a bow.", false));
    public static final Setting.FloatSetting ANIMATIONS_X = option(ANIMATIONS, Setting.decimal(
            "animations.x", ANIMATIONS.title(), "Item left / right", "Moves the held item sideways.", 0f, -0.5f,
            0.5f));
    public static final Setting.FloatSetting ANIMATIONS_Y = option(ANIMATIONS, Setting.decimal(
            "animations.y", ANIMATIONS.title(), "Item down / up", "Moves the held item up or down.", 0f, -0.5f, 0.5f));
    public static final Setting.FloatSetting ANIMATIONS_Z = option(ANIMATIONS, Setting.decimal(
            "animations.z", ANIMATIONS.title(), "Item near / far", "Moves the held item toward or away from view.",
            0f, -0.5f, 0.5f));
    public static final Setting.FloatSetting ANIMATIONS_SCALE = option(ANIMATIONS, Setting.decimal(
            "animations.scale", ANIMATIONS.title(), "Item size", "Scales the held item.", 1f, 0.5f, 1.5f));

    public static final Module TOGGLE_SPRINT = hud("toggle_sprint", "Toggle sprint & sneak",
            "Press sprint or sneak once to keep it on; the readout says how you are moving.", false,
            HudAnchor.BOTTOM_LEFT, 6, -6);
    public static final Setting.BooleanSetting TOGGLE_SPRINT_BLUR = blur(TOGGLE_SPRINT, true);
    public static final Setting.BooleanSetting TOGGLE_SPRINT_SPRINT = option(TOGGLE_SPRINT, Setting.bool(
            "toggle_sprint.sprint", TOGGLE_SPRINT.title(), "Toggle sprint",
            "The sprint key switches sprinting on and off instead of being held.", true));
    public static final Setting.BooleanSetting TOGGLE_SPRINT_SNEAK = option(TOGGLE_SPRINT, Setting.bool(
            "toggle_sprint.sneak", TOGGLE_SPRINT.title(), "Toggle sneak",
            "The sneak key switches sneaking on and off instead of being held.", false));
    public static final Setting.BooleanSetting TOGGLE_SPRINT_READOUT = option(TOGGLE_SPRINT, Setting.bool(
            "toggle_sprint.readout", TOGGLE_SPRINT.title(), "Show status",
            "Shows Sprinting or Sneaking, and whether it is toggled or held.", true));

    public static final ConfigSchema SCHEMA = BUILDER.build();

    private static final Map<String, Module> BY_ID = ALL.stream().collect(Collectors.toMap(Module::id, Function.identity()));

    private Modules() {
    }

    /** Every module in declaration order (the module page's order). */
    public static List<Module> all() {
        return List.copyOf(ALL);
    }

    public static Module find(String id) {
        return BY_ID.get(id);
    }

    /** The module's Blur option (frosts the scene behind its surface), or null when it has no surface to frost. */
    public static Setting.BooleanSetting blur(Module module) {
        return BLUR.get(module);
    }

    private static Module hud(String id, String title, String description, boolean enabledByDefault,
                              HudAnchor anchor, float x, float y) {
        Setting.BooleanSetting enabled = BUILDER.add(Setting.bool(id + ".enabled", title, "Enabled", description,
                enabledByDefault));
        HudPlacement placement = new HudPlacement(
                BUILDER.add(Setting.choice(id + ".hud.anchor", title, "Anchor", "", HudAnchor.class, anchor)),
                BUILDER.add(Setting.decimal(id + ".hud.x", title, "X", "", x, -HudPlacement.MAX_OFFSET,
                        HudPlacement.MAX_OFFSET)),
                BUILDER.add(Setting.decimal(id + ".hud.y", title, "Y", "", y, -HudPlacement.MAX_OFFSET,
                        HudPlacement.MAX_OFFSET)),
                BUILDER.add(Setting.decimal(id + ".hud.scale", title, "Scale", "Size of the element.", 1f,
                        HudPlacement.MIN_SCALE, HudPlacement.MAX_SCALE)));
        Module module = new Module(id, title, description, Module.Kind.HUD, enabled, placement);
        ALL.add(module);
        return module;
    }

    private static Module gameplay(String id, String title, String description, boolean enabledByDefault) {
        Setting.BooleanSetting enabled = BUILDER.add(Setting.bool(id + ".enabled", title, "Enabled", description,
                enabledByDefault));
        Module module = new Module(id, title, description, Module.Kind.GAMEPLAY, enabled, null);
        ALL.add(module);
        return module;
    }

    private static Setting.BooleanSetting blur(Module module, boolean enabledByDefault) {
        Setting.BooleanSetting blur = option(module, Setting.bool(module.id() + ".blur", module.title(), "Blur",
                "Frosts the scene behind the element. Redrawn at the HUD frame rate.", enabledByDefault));
        BLUR.put(module, blur);
        return blur;
    }

    /** Registers an option and appends it to its module (modules are built while this class initializes). */
    private static <S extends Setting<?>> S option(Module module, S setting) {
        BUILDER.add(setting);
        module.addOption(setting);
        return setting;
    }
}
