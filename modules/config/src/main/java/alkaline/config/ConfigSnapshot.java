package alkaline.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable set of values for one schema. Primitive kinds live in a {@code long[]} so per-frame reads of
 * booleans, ints, floats and colors do not allocate; changes produce a new snapshot.
 */
public final class ConfigSnapshot {
    private final ConfigSchema schema;
    private final long[] primitives;
    private final Object[] references;
    private final Map<String, Object> unknown;

    private ConfigSnapshot(ConfigSchema schema, long[] primitives, Object[] references, Map<String, Object> unknown) {
        this.schema = schema;
        this.primitives = primitives;
        this.references = references;
        this.unknown = unknown;
    }

    public static ConfigSnapshot defaults(ConfigSchema schema) {
        Objects.requireNonNull(schema, "schema");
        long[] primitives = new long[schema.primitiveCount()];
        Object[] references = new Object[schema.settings().size() - schema.primitiveCount()];
        ConfigSnapshot snapshot = new ConfigSnapshot(schema, primitives, references, Map.of());
        for (Setting<?> setting : schema.settings()) {
            snapshot.storeDefault(setting);
        }
        return snapshot;
    }

    private <T> void storeDefault(Setting<T> setting) {
        store(setting, setting.defaultValue());
    }

    public ConfigSchema schema() {
        return schema;
    }

    public boolean get(Setting.BooleanSetting setting) {
        return primitives[slot(setting)] != 0L;
    }

    public int get(Setting.IntSetting setting) {
        return (int) primitives[slot(setting)];
    }

    public float get(Setting.FloatSetting setting) {
        return Float.intBitsToFloat((int) primitives[slot(setting)]);
    }

    public int get(Setting.ColorSetting setting) {
        return (int) primitives[slot(setting)];
    }

    public <E extends Enum<E>> E get(Setting.EnumSetting<E> setting) {
        @SuppressWarnings("unchecked")
        E value = (E) references[slot(setting)];
        return value;
    }

    public String get(Setting.StringSetting setting) {
        return (String) references[slot(setting)];
    }

    public String get(Setting.KeySetting setting) {
        return (String) references[slot(setting)];
    }

    /** Boxed generic read for settings UIs; render code should use the typed overloads. */
    public <T> T value(Setting<T> setting) {
        int slot = slot(setting);
        if (setting.primitive()) {
            return setting.fromBits(primitives[slot]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) references[slot];
        return value;
    }

    /** Persisted keys this schema does not recognize; kept so older builds do not erase newer settings. */
    public Map<String, Object> unknown() {
        return unknown;
    }

    /** Returns a copy with one validated value replaced. */
    public <T> ConfigSnapshot with(Setting<T> setting, T value) {
        T valid = setting.validate(value);
        ConfigSnapshot copy = new ConfigSnapshot(schema, primitives.clone(), references.clone(), unknown);
        copy.store(setting, valid);
        return copy;
    }

    ConfigSnapshot withUnknown(Map<String, Object> values) {
        return new ConfigSnapshot(schema, primitives, references,
                Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** Fresh mutable snapshot copy used while loading; never escapes before construction completes. */
    ConfigSnapshot copy() {
        return new ConfigSnapshot(schema, primitives.clone(), references.clone(), unknown);
    }

    <T> void store(Setting<T> setting, T value) {
        int slot = slot(setting);
        if (setting.primitive()) {
            primitives[slot] = setting.toBits(value);
        } else {
            references[slot] = value;
        }
    }

    /** Serializable map of every known value followed by preserved unknown values. */
    Map<String, Object> encode() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Setting<?> setting : schema.settings()) {
            values.put(setting.id(), encodeOne(setting));
        }
        for (Map.Entry<String, Object> entry : unknown.entrySet()) {
            values.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private <T> Object encodeOne(Setting<T> setting) {
        return setting.encode(value(setting));
    }

    private int slot(Setting<?> setting) {
        if (!schema.owns(setting)) {
            throw new IllegalArgumentException("Setting " + setting.id() + " is not part of this schema.");
        }
        return setting.slot();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigSnapshot snapshot && snapshot.schema == schema
                && Arrays.equals(snapshot.primitives, primitives) && Arrays.equals(snapshot.references, references)
                && snapshot.unknown.equals(unknown);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(primitives) + Arrays.hashCode(references);
    }
}
