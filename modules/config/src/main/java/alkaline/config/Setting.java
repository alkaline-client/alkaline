package alkaline.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One typed, validated setting. Settings are declared once in a {@link ConfigSchema}; values are read from an
 * immutable {@link ConfigSnapshot}. Primitive kinds read without boxing so render code can poll them per frame.
 *
 * @param <T> the boxed value type
 */
public abstract sealed class Setting<T>
        permits Setting.BooleanSetting, Setting.IntSetting, Setting.FloatSetting, Setting.EnumSetting,
        Setting.ColorSetting, Setting.StringSetting, Setting.KeySetting {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

    private final String id;
    private final String category;
    private final String title;
    private final String description;
    private int slot = -1;
    private Object schemaToken;
    private boolean restartRequired;

    Setting(String id, String category, String title, String description) {
        this.id = Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches() || id.length() > 96) {
            throw new IllegalArgumentException("Setting id must be dotted lower_snake_case: " + id);
        }
        this.category = Objects.requireNonNull(category, "category");
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
    }

    /** Stable persisted key, for example {@code panorama.blur_passes}. */
    public final String id() {
        return id;
    }

    public final String category() {
        return category;
    }

    public final String title() {
        return title;
    }

    public final String description() {
        return description;
    }

    /**
     * True when the client reads this setting only at startup, so a change is saved immediately but takes
     * effect after a restart. Declared with {@link ConfigSchema.Builder#addRestartRequired}.
     */
    public final boolean restartRequired() {
        return restartRequired;
    }

    public abstract T defaultValue();

    /** Returns a valid value or throws {@link IllegalArgumentException}. */
    public abstract T validate(T value);

    /** Converts a parsed JSON value; returns null when the value has the wrong type or is out of range. */
    abstract T decode(Object json);

    abstract Object encode(T value);

    final int slot() {
        if (slot < 0) {
            throw new IllegalStateException("Setting " + id + " is not registered in a schema.");
        }
        return slot;
    }

    final void bind(int index, Object token) {
        if (slot >= 0) {
            throw new IllegalStateException("Setting " + id + " already belongs to a schema.");
        }
        slot = index;
        schemaToken = token;
    }

    final void markRestartRequired() {
        if (slot >= 0) {
            throw new IllegalStateException("Setting " + id + " is already registered.");
        }
        restartRequired = true;
    }

    final boolean boundTo(Object token) {
        return schemaToken == token;
    }

    /** Whether the snapshot stores this setting in its primitive array. */
    boolean primitive() {
        return false;
    }

    long toBits(T value) {
        throw new UnsupportedOperationException();
    }

    T fromBits(long bits) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final String toString() {
        return id;
    }

    public static BooleanSetting bool(String id, String category, String title, String description,
                                      boolean defaultValue) {
        return new BooleanSetting(id, category, title, description, defaultValue);
    }

    public static IntSetting integer(String id, String category, String title, String description,
                                     int defaultValue, int min, int max) {
        return new IntSetting(id, category, title, description, defaultValue, min, max);
    }

    public static FloatSetting decimal(String id, String category, String title, String description,
                                       float defaultValue, float min, float max) {
        return new FloatSetting(id, category, title, description, defaultValue, min, max);
    }

    public static <E extends Enum<E>> EnumSetting<E> choice(String id, String category, String title,
                                                           String description, Class<E> type, E defaultValue) {
        return new EnumSetting<>(id, category, title, description, type, defaultValue);
    }

    public static ColorSetting color(String id, String category, String title, String description, int argb) {
        return new ColorSetting(id, category, title, description, argb);
    }

    /**
     * An input binding: {@code none}, {@code key:<physical key name>} (the platform's name for the key at that
     * position, such as {@code key:W} or {@code key:Left Shift}), or {@code mouse:<button>} (left, right, middle,
     * x1, x2).
     */
    public static KeySetting key(String id, String category, String title, String description, String defaultValue) {
        return new KeySetting(id, category, title, description, defaultValue);
    }

    public static StringSetting text(String id, String category, String title, String description,
                                     String defaultValue, int maxLength) {
        return new StringSetting(id, category, title, description, defaultValue, maxLength);
    }

    public static final class BooleanSetting extends Setting<Boolean> {
        private final boolean defaultValue;

        private BooleanSetting(String id, String category, String title, String description, boolean defaultValue) {
            super(id, category, title, description);
            this.defaultValue = defaultValue;
        }

        @Override public Boolean defaultValue() { return defaultValue; }
        @Override public Boolean validate(Boolean value) { return Objects.requireNonNull(value, "value"); }
        @Override Boolean decode(Object json) { return json instanceof Boolean value ? value : null; }
        @Override Object encode(Boolean value) { return value; }
        @Override boolean primitive() { return true; }
        @Override long toBits(Boolean value) { return value ? 1L : 0L; }
        @Override Boolean fromBits(long bits) { return bits != 0L; }
    }

    public static final class IntSetting extends Setting<Integer> {
        private final int defaultValue;
        private final int min;
        private final int max;

        private IntSetting(String id, String category, String title, String description,
                           int defaultValue, int min, int max) {
            super(id, category, title, description);
            if (min > max || defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException("Integer setting " + id + " default must lie in [min, max].");
            }
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        public int min() { return min; }
        public int max() { return max; }

        @Override public Integer defaultValue() { return defaultValue; }

        @Override
        public Integer validate(Integer value) {
            Objects.requireNonNull(value, "value");
            if (value < min || value > max) {
                throw new IllegalArgumentException(id() + " must be between " + min + " and " + max + ".");
            }
            return value;
        }

        @Override
        Integer decode(Object json) {
            if (json instanceof Long value && value >= min && value <= max) {
                return value.intValue();
            }
            return null;
        }

        @Override Object encode(Integer value) { return value.longValue(); }
        @Override boolean primitive() { return true; }
        @Override long toBits(Integer value) { return value; }
        @Override Integer fromBits(long bits) { return (int) bits; }
    }

    public static final class FloatSetting extends Setting<Float> {
        private final float defaultValue;
        private final float min;
        private final float max;

        private FloatSetting(String id, String category, String title, String description,
                             float defaultValue, float min, float max) {
            super(id, category, title, description);
            if (!Float.isFinite(min) || !Float.isFinite(max) || min > max
                    || !(defaultValue >= min && defaultValue <= max)) {
                throw new IllegalArgumentException("Decimal setting " + id + " default must lie in [min, max].");
            }
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        public float min() { return min; }
        public float max() { return max; }

        @Override public Float defaultValue() { return defaultValue; }

        @Override
        public Float validate(Float value) {
            Objects.requireNonNull(value, "value");
            if (!(value >= min && value <= max)) {
                throw new IllegalArgumentException(id() + " must be between " + min + " and " + max + ".");
            }
            return value;
        }

        @Override
        Float decode(Object json) {
            double value;
            if (json instanceof Double number) {
                value = number;
            } else if (json instanceof Long number) {
                value = number;
            } else {
                return null;
            }
            float result = (float) value;
            return result >= min && result <= max ? result : null;
        }

        @Override Object encode(Float value) { return value.doubleValue(); }
        @Override boolean primitive() { return true; }
        @Override long toBits(Float value) { return Float.floatToRawIntBits(value); }
        @Override Float fromBits(long bits) { return Float.intBitsToFloat((int) bits); }
    }

    public static final class EnumSetting<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;
        private final E defaultValue;

        private EnumSetting(String id, String category, String title, String description,
                            Class<E> type, E defaultValue) {
            super(id, category, title, description);
            this.type = Objects.requireNonNull(type, "type");
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        }

        public List<E> values() {
            return List.of(type.getEnumConstants());
        }

        @Override public E defaultValue() { return defaultValue; }
        @Override public E validate(E value) { return type.cast(Objects.requireNonNull(value, "value")); }

        @Override
        E decode(Object json) {
            if (json instanceof String name) {
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().toLowerCase(Locale.ROOT).equals(name)) {
                        return constant;
                    }
                }
            }
            return null;
        }

        @Override Object encode(E value) { return value.name().toLowerCase(Locale.ROOT); }
    }

    /** 32-bit ARGB color persisted as {@code "#AARRGGBB"}. */
    public static final class ColorSetting extends Setting<Integer> {
        private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{8}");
        private final int defaultValue;

        private ColorSetting(String id, String category, String title, String description, int argb) {
            super(id, category, title, description);
            this.defaultValue = argb;
        }

        @Override public Integer defaultValue() { return defaultValue; }
        @Override public Integer validate(Integer value) { return Objects.requireNonNull(value, "value"); }

        @Override
        Integer decode(Object json) {
            return json instanceof String text && HEX.matcher(text).matches()
                    ? (int) Long.parseLong(text.substring(1), 16) : null;
        }

        @Override Object encode(Integer value) { return String.format(Locale.ROOT, "#%08X", value); }
        @Override boolean primitive() { return true; }
        @Override long toBits(Integer value) { return value; }
        @Override Integer fromBits(long bits) { return (int) bits; }
    }

    public static final class StringSetting extends Setting<String> {
        private final String defaultValue;
        private final int maxLength;

        private StringSetting(String id, String category, String title, String description,
                              String defaultValue, int maxLength) {
            super(id, category, title, description);
            if (maxLength <= 0 || defaultValue.length() > maxLength) {
                throw new IllegalArgumentException("Text setting " + id + " default exceeds its maximum length.");
            }
            this.defaultValue = defaultValue;
            this.maxLength = maxLength;
        }

        public int maxLength() { return maxLength; }
        @Override public String defaultValue() { return defaultValue; }

        @Override
        public String validate(String value) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(id() + " exceeds " + maxLength + " characters.");
            }
            return value;
        }

        @Override
        String decode(Object json) {
            return json instanceof String text && text.length() <= maxLength ? text : null;
        }

        @Override Object encode(String value) { return value; }
    }

    public static final class KeySetting extends Setting<String> {
        public static final String NONE = "none";
        private static final Pattern BINDING =
                Pattern.compile("none|key:[^\\p{Cntrl}]{1,32}|mouse:(left|right|middle|x1|x2)");
        private final String defaultValue;

        private KeySetting(String id, String category, String title, String description, String defaultValue) {
            super(id, category, title, description);
            if (!valid(defaultValue)) {
                throw new IllegalArgumentException("Key setting " + id + " has an invalid default: " + defaultValue);
            }
            this.defaultValue = defaultValue;
        }

        /** Whether a value names no input, a key, or a mouse button in the binding format. */
        public static boolean valid(String value) {
            return value != null && BINDING.matcher(value).matches();
        }

        @Override public String defaultValue() { return defaultValue; }

        @Override
        public String validate(String value) {
            if (!valid(value)) {
                throw new IllegalArgumentException(id() + " is not a key binding: " + value);
            }
            return value;
        }

        @Override
        String decode(Object json) {
            return json instanceof String text && valid(text) ? text : null;
        }

        @Override Object encode(String value) { return value; }
    }
}
