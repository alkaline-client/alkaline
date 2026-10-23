package alkaline.clientapp;

import alkaline.config.Json;
import alkaline.renderer.asset.AssetSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat components (the JSON the server sends for chat, titles, kick reasons and team display names) as legacy
 * text with § formatting codes, which the HUD draws in color. Translated components ({@code chat.type.text},
 * join and death messages...) use vanilla's English strings from the game's {@code en_US.lang}.
 */
public final class ChatText {
    private static final String COLORS = "black,dark_blue,dark_green,dark_aqua,dark_red,dark_purple,gold,gray,"
            + "dark_gray,blue,green,aqua,red,light_purple,yellow,white";
    private static final List<String> COLOR_NAMES = List.of(COLORS.split(","));
    private static final String COLOR_CODES = "0123456789abcdef";
    private static final Pattern ITEM_NAME = Pattern.compile("Name:\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ITEM_ID = Pattern.compile("id:\"?(?:minecraft:)?([a-z_0-9]+)");

    /**
     * What a stretch of a chat line does (ChatStyle's click and hover events and insertion): {@code start} to
     * {@code end} count the line's visible characters, § codes left out. {@code click} is {@code run_command},
     * {@code suggest_command} or {@code open_url} (http and https only, as GuiScreen.handleComponentClick allows),
     * or null; {@code hover} is § text, or null.
     */
    record Action(int start, int end, String click, String value, String hover, String insertion) {
    }

    /** A component as § text with its actions. */
    record Rich(String text, List<Action> actions) {
    }

    /** The events a component passes to its children, which keep them unless they set their own (ChatStyle). */
    private record Events(String click, String value, String hover, String insertion) {
        static final Events NONE = new Events(null, null, null, null);

        boolean any() {
            return click != null || hover != null || insertion != null;
        }
    }

    private final Map<String, String> lang;

    private ChatText(Map<String, String> lang) {
        this.lang = lang;
    }

    /** Loads vanilla's English strings; without assets, translation keys are shown as they are. */
    static ChatText load(AssetSource assets) {
        Map<String, String> lang = new HashMap<>();
        if (assets != null) {
            try (InputStream input = assets.open("assets/minecraft/lang/en_US.lang")) {
                if (input != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    for (String line; (line = reader.readLine()) != null; ) {
                        int equals = line.indexOf('=');
                        if (equals > 0 && !line.startsWith("#")) {
                            lang.put(line.substring(0, equals), line.substring(equals + 1));
                        }
                    }
                }
            } catch (IOException unreadable) {
                System.err.println("en_US.lang unavailable: " + unreadable.getMessage());
            }
        }
        return new ChatText(lang);
    }

    /** The component as § text; plain strings that are not JSON are returned unchanged. */
    String legacy(String json) {
        return parse(json, null);
    }

    /** The component as § text with its click and hover actions, for chat lines. */
    Rich rich(String json) {
        List<Action> actions = new ArrayList<>();
        String text = parse(json, actions);
        return new Rich(text, List.copyOf(actions));
    }

    private String parse(String json, List<Action> actions) {
        if (json == null) {
            return "";
        }
        try {
            StringBuilder out = new StringBuilder();
            append(Json.parse(json), "", Events.NONE, out, actions);
            return out.toString();
        } catch (IllegalArgumentException notJson) {
            return json;
        }
    }

    /** Characters the HUD shows of § text: a § and the character after it are a code, not shown. */
    static int visibleLength(CharSequence text) {
        int visible = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '§' && index + 1 < text.length()) {
                index++;
            } else {
                visible++;
            }
        }
        return visible;
    }

    /** A translation from en_US.lang, or null when the key is unknown. */
    String translation(String key) {
        return lang.get(key);
    }

    /** The component without formatting codes. */
    String plain(String json) {
        return legacy(json).replaceAll("§.", "");
    }

    private void append(Object component, String inherited, Events events, StringBuilder out, List<Action> actions) {
        if (component instanceof String text) {
            out.append(inherited.isEmpty() ? "" : "§r" + inherited);
            appendText(text, events, out, actions);
            return;
        }
        if (component instanceof List<?> list) {
            for (Object part : list) {
                append(part, inherited, events, out, actions);
            }
            return;
        }
        if (!(component instanceof Map<?, ?> map)) {
            return;
        }
        Events own = actions == null ? events : events(map, events);
        StringBuilder style = new StringBuilder(inherited);
        if (map.get("color") instanceof String color) {
            int index = COLOR_NAMES.indexOf(color);
            if (index >= 0) {
                style.setLength(0);
                style.append('§').append(COLOR_CODES.charAt(index));
            } else if (color.equals("reset")) {
                style.setLength(0);
            }
        }
        format(map, "bold", 'l', style);
        format(map, "italic", 'o', style);
        format(map, "underlined", 'n', style);
        format(map, "strikethrough", 'm', style);
        format(map, "obfuscated", 'k', style);
        String prefix = "§r" + style;
        if (map.get("text") instanceof String text && !text.isEmpty()) {
            out.append(prefix);
            appendText(text, own, out, actions);
        } else if (map.get("translate") instanceof String key) {
            translate(key, map.get("with") instanceof List<?> with ? with : List.of(), style.toString(), own, out,
                    actions);
        } else if (map.get("score") instanceof Map<?, ?> score && score.get("value") instanceof String value) {
            out.append(prefix);
            appendText(value, own, out, actions);
        } else if (map.get("selector") instanceof String selector) {
            out.append(prefix);
            appendText(selector, own, out, actions);
        }
        if (map.get("extra") instanceof List<?> extra) {
            for (Object part : extra) {
                append(part, style.toString(), own, out, actions);
            }
        }
    }

    /** Appends shown text, and records what it does when it does anything. */
    private static void appendText(String text, Events events, StringBuilder out, List<Action> actions) {
        if (actions == null || !events.any()) {
            out.append(text);
            return;
        }
        int start = visibleLength(out);
        out.append(text);
        int end = visibleLength(out);
        if (end > start) {
            actions.add(new Action(start, end, events.click(), events.value(), events.hover(), events.insertion()));
        }
    }

    /**
     * A component's events over the ones it inherits. Only the click actions chat allows are kept (ClickEvent.Action's
     * allowedInChat drops open_file); open_url needs http or https.
     */
    private Events events(Map<?, ?> map, Events inherited) {
        String click = inherited.click();
        String value = inherited.value();
        if (map.get("clickEvent") instanceof Map<?, ?> event && event.get("action") instanceof String action
                && event.get("value") instanceof String target) {
            boolean web = action.equals("open_url") && (target.regionMatches(true, 0, "http://", 0, 7)
                    || target.regionMatches(true, 0, "https://", 0, 8));
            if (web || action.equals("run_command") || action.equals("suggest_command")) {
                click = action;
                value = target;
            }
        }
        String hover = inherited.hover();
        if (map.get("hoverEvent") instanceof Map<?, ?> event && event.get("action") instanceof String action) {
            Object shown = event.get("value");
            String text = switch (action) {
                case "show_text" -> parse(shown, null);
                case "show_item" -> shown instanceof String nbt ? itemName(nbt) : null;
                case "show_entity" -> shown instanceof String nbt ? entityName(nbt) : null;
                case "show_achievement" -> shown instanceof String id ? lang.getOrDefault(id, id) : null;
                default -> null;
            };
            if (text != null && !text.isEmpty()) {
                hover = text;
            }
        }
        String insertion = map.get("insertion") instanceof String inserted ? inserted : inherited.insertion();
        return new Events(click, value, hover, insertion);
    }

    /** A component that is already parsed JSON (a hover's text), as § text. */
    private String parse(Object component, List<Action> actions) {
        StringBuilder out = new StringBuilder();
        append(component, "", Events.NONE, out, actions);
        return out.toString();
    }

    /** show_item's value is the stack's NBT as text: its display name, or the item's id. */
    private static String itemName(String nbt) {
        Matcher name = ITEM_NAME.matcher(nbt);
        if (name.find()) {
            return name.group(1).replace("\\\"", "\"");
        }
        Matcher id = ITEM_ID.matcher(nbt);
        return id.find() ? id.group(1) : null;
    }

    /** show_entity's value: {name:..., type:..., id:...}; the name and type are shown, as GuiScreen does. */
    private static String entityName(String nbt) {
        Matcher name = Pattern.compile("name:\"?([^\",}]*)").matcher(nbt);
        Matcher type = Pattern.compile("type:\"?([^\",}]*)").matcher(nbt);
        String shown = name.find() ? name.group(1) : null;
        String kind = type.find() ? type.group(1) : null;
        return shown == null ? kind : kind == null ? shown : shown + "\n§7" + kind;
    }

    /** ChatComponentTranslation: %s and %n$s take the arguments in order or by position, %% is a percent sign. */
    private void translate(String key, List<?> with, String style, Events events, StringBuilder out,
            List<Action> actions) {
        String pattern = lang.getOrDefault(key, key);
        String prefix = "§r" + style;
        int next = 0;
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character != '%' || index + 1 >= pattern.length()) {
                literal.append(character);
                continue;
            }
            int end = index + 1;
            int position = -1;
            while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
                end++;
            }
            if (end > index + 1 && end < pattern.length() && pattern.charAt(end) == '$') {
                position = Integer.parseInt(pattern.substring(index + 1, end)) - 1;
                end++;
            }
            if (end < pattern.length() && (pattern.charAt(end) == 's' || pattern.charAt(end) == 'd')) {
                if (!literal.isEmpty()) {
                    out.append(prefix);
                    appendText(literal.toString(), events, out, actions);
                    literal.setLength(0);
                }
                int argument = position >= 0 ? position : next++;
                if (argument >= 0 && argument < with.size()) {
                    append(with.get(argument), style, events, out, actions);
                }
                index = end;
            } else if (pattern.charAt(index + 1) == '%') {
                literal.append('%');
                index++;
            } else {
                literal.append(character);
            }
        }
        if (!literal.isEmpty()) {
            out.append(prefix);
            appendText(literal.toString(), events, out, actions);
        }
    }

    private static void format(Map<?, ?> map, String key, char code, StringBuilder style) {
        if (Boolean.TRUE.equals(map.get(key))) {
            style.append('§').append(code);
        }
    }
}
