package alkaline.clientapp;

import alkaline.config.Json;
import alkaline.renderer.asset.AssetSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        if (json == null) {
            return "";
        }
        try {
            StringBuilder out = new StringBuilder();
            append(Json.parse(json), "", out);
            return out.toString();
        } catch (IllegalArgumentException notJson) {
            return json;
        }
    }

    /** A translation from en_US.lang, or null when the key is unknown. */
    String translation(String key) {
        return lang.get(key);
    }

    /** The component without formatting codes. */
    String plain(String json) {
        return legacy(json).replaceAll("§.", "");
    }

    private void append(Object component, String inherited, StringBuilder out) {
        if (component instanceof String text) {
            out.append(inherited.isEmpty() ? "" : "§r" + inherited).append(text);
            return;
        }
        if (component instanceof List<?> list) {
            for (Object part : list) {
                append(part, inherited, out);
            }
            return;
        }
        if (!(component instanceof Map<?, ?> map)) {
            return;
        }
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
            out.append(prefix).append(text);
        } else if (map.get("translate") instanceof String key) {
            translate(key, map.get("with") instanceof List<?> with ? with : List.of(), style.toString(), out);
        } else if (map.get("score") instanceof Map<?, ?> score && score.get("value") instanceof String value) {
            out.append(prefix).append(value);
        } else if (map.get("selector") instanceof String selector) {
            out.append(prefix).append(selector);
        }
        if (map.get("extra") instanceof List<?> extra) {
            for (Object part : extra) {
                append(part, style.toString(), out);
            }
        }
    }

    /** ChatComponentTranslation: %s and %n$s take the arguments in order or by position, %% is a percent sign. */
    private void translate(String key, List<?> with, String style, StringBuilder out) {
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
                    out.append(prefix).append(literal);
                    literal.setLength(0);
                }
                int argument = position >= 0 ? position : next++;
                if (argument >= 0 && argument < with.size()) {
                    append(with.get(argument), style, out);
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
            out.append(prefix).append(literal);
        }
    }

    private static void format(Map<?, ?> map, String key, char code, StringBuilder style) {
        if (Boolean.TRUE.equals(map.get(key))) {
            style.append('§').append(code);
        }
    }
}
