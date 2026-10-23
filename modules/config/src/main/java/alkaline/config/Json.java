package alkaline.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON reader/writer for settings files. Objects become insertion-ordered maps, arrays become
 * lists, integral numbers become {@link Long} and other numbers {@link Double}. Input size and nesting are
 * bounded so a damaged or hostile file cannot exhaust memory or the stack.
 */
public final class Json {
    public static final int MAX_CHARS = 1024 * 1024;
    private static final int MAX_DEPTH = 32;

    private final String source;
    private int position;

    private Json(String source) {
        this.source = source;
    }

    public static Object parse(String source) {
        if (source.length() > MAX_CHARS) {
            throw new JsonException("JSON document exceeds " + MAX_CHARS + " characters.");
        }
        Json parser = new Json(source);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position != source.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Pretty-prints maps, lists, strings, booleans, null, and finite numbers with two-space indentation. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(512);
        writeValue(out, value, 0);
        return out.append('\n').toString();
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("Nesting exceeds " + MAX_DEPTH + " levels");
        }
        if (position >= source.length()) {
            throw error("Unexpected end of input");
        }
        char next = source.charAt(position);
        return switch (next) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (next == '-' || (next >= '0' && next <= '9')) {
                    yield readNumber();
                }
                throw error("Unexpected character '" + next + "'");
            }
        };
    }

    private Map<String, Object> readObject(int depth) {
        position++;
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (!peek('"')) {
                throw error("Expected object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (result.containsKey(key)) {
                throw error("Duplicate key '" + key + "'");
            }
            result.put(key, readValue(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                position++;
                continue;
            }
            expect('}');
            return result;
        }
    }

    private List<Object> readArray(int depth) {
        position++;
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue(depth + 1));
            skipWhitespace();
            if (peek(',')) {
                position++;
                continue;
            }
            expect(']');
            return result;
        }
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (position < source.length()) {
            char character = source.charAt(position++);
            if (character == '"') {
                return value.toString();
            }
            if (character < 0x20) {
                throw error("Control character in string");
            }
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (position >= source.length()) {
                break;
            }
            char escaped = source.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > source.length()) {
                        throw error("Truncated unicode escape");
                    }
                    int code = 0;
                    for (int index = 0; index < 4; index++) {
                        int digit = Character.digit(source.charAt(position++), 16);
                        if (digit < 0) {
                            throw error("Invalid unicode escape");
                        }
                        code = code * 16 + digit;
                    }
                    value.append((char) code);
                }
                default -> throw error("Invalid escape '\\" + escaped + "'");
            }
        }
        throw error("Unterminated string");
    }

    private Object readNumber() {
        int start = position;
        if (peek('-')) {
            position++;
        }
        boolean integral = true;
        while (position < source.length()) {
            char character = source.charAt(position);
            if (character >= '0' && character <= '9') {
                position++;
            } else if (character == '.' || character == 'e' || character == 'E' || character == '+' || character == '-') {
                integral = false;
                position++;
            } else {
                break;
            }
        }
        String text = source.substring(start, position);
        try {
            if (integral) {
                return Long.parseLong(text);
            }
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) {
                throw error("Number out of range");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw error("Invalid number '" + text + "'");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, position)) {
            throw error("Invalid literal");
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < source.length()) {
            char character = source.charAt(position);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            position++;
        }
    }

    private boolean peek(char expected) {
        return position < source.length() && source.charAt(position) == expected;
    }

    private void expect(char expected) {
        if (!peek(expected)) {
            throw error("Expected '" + expected + "'");
        }
        position++;
    }

    private JsonException error(String message) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < Math.min(position, source.length()); index++) {
            if (source.charAt(index) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(message + " at line " + line + ", column " + column + ".");
    }

    private static void writeValue(StringBuilder out, Object value, int indent) {
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(out, text);
            case Boolean bool -> out.append(bool.booleanValue());
            case Long number -> out.append(number.longValue());
            case Integer number -> out.append(number.intValue());
            case Double number -> writeDouble(out, number);
            case Float number -> writeDouble(out, number.doubleValue());
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int written = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    indent(out, indent + 1);
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(": ");
                    writeValue(out, entry.getValue(), indent + 1);
                    out.append(++written < map.size() ? ",\n" : "\n");
                }
                indent(out, indent);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int index = 0; index < list.size(); index++) {
                    indent(out, indent + 1);
                    writeValue(out, list.get(index), indent + 1);
                    out.append(index + 1 < list.size() ? ",\n" : "\n");
                }
                indent(out, indent);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value type " + value.getClass().getName());
        }
    }

    private static void writeDouble(StringBuilder out, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON numbers must be finite.");
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            out.append((long) value).append(".0");
        } else {
            out.append(value);
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    /** Thrown for malformed JSON with a line/column location. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }
}
