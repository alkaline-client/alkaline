package alkaline.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned, ordered set of settings plus the explicit migrations that upgrade older files. Changing a
 * persisted key or meaning requires bumping {@link #version()} and registering a migration from the
 * previous version; the store refuses to guess.
 */
public final class ConfigSchema {
    /** Rewrites raw persisted values from one schema version to the next. */
    @FunctionalInterface
    public interface Migration {
        void migrate(Map<String, Object> settings);
    }

    private final int version;
    private final List<Setting<?>> settings;
    private final Map<String, Setting<?>> byId;
    private final Map<Integer, Migration> migrations;
    private final int primitiveCount;
    private final Object token;

    private ConfigSchema(Builder builder) {
        token = builder.token;
        version = builder.version;
        settings = List.copyOf(builder.settings);
        byId = Map.copyOf(builder.byId);
        migrations = Map.copyOf(builder.migrations);
        for (int from = 1; from < version; from++) {
            if (!migrations.containsKey(from)) {
                throw new IllegalStateException("Schema version " + version + " lacks a migration from " + from + ".");
            }
        }
        int primitives = 0;
        for (Setting<?> setting : settings) {
            if (setting.primitive()) {
                primitives++;
            }
        }
        primitiveCount = primitives;
    }

    public static Builder builder(int version) {
        return new Builder(version);
    }

    public int version() {
        return version;
    }

    public List<Setting<?>> settings() {
        return settings;
    }

    public Setting<?> find(String id) {
        return byId.get(id);
    }

    /** Settings grouped by category in declaration order. */
    public Map<String, List<Setting<?>>> categories() {
        Map<String, List<Setting<?>>> grouped = new LinkedHashMap<>();
        for (Setting<?> setting : settings) {
            grouped.computeIfAbsent(setting.category(), ignored -> new ArrayList<>()).add(setting);
        }
        return grouped;
    }

    boolean owns(Setting<?> setting) {
        return setting.boundTo(token);
    }

    int primitiveCount() {
        return primitiveCount;
    }

    Migration migrationFrom(int fromVersion) {
        return migrations.get(fromVersion);
    }

    public static final class Builder {
        private final int version;
        private final List<Setting<?>> settings = new ArrayList<>();
        private final Map<String, Setting<?>> byId = new HashMap<>();
        private final Map<Integer, Migration> migrations = new HashMap<>();
        private final Object token = new Object();
        private int primitiveSlots;
        private int referenceSlots;

        private Builder(int version) {
            if (version < 1) {
                throw new IllegalArgumentException("Schema versions start at 1.");
            }
            this.version = version;
        }

        public <S extends Setting<?>> S add(S setting) {
            Objects.requireNonNull(setting, "setting");
            if (byId.putIfAbsent(setting.id(), setting) != null) {
                throw new IllegalArgumentException("Duplicate setting id " + setting.id());
            }
            setting.bind(setting.primitive() ? primitiveSlots++ : referenceSlots++, token);
            settings.add(setting);
            return setting;
        }

        /** Registers a setting that the client applies only at startup; see {@link Setting#restartRequired()}. */
        public <S extends Setting<?>> S addRestartRequired(S setting) {
            Objects.requireNonNull(setting, "setting").markRestartRequired();
            return add(setting);
        }

        /** Registers the upgrade from {@code fromVersion} to {@code fromVersion + 1}. */
        public Builder migration(int fromVersion, Migration migration) {
            if (fromVersion < 1 || fromVersion >= version) {
                throw new IllegalArgumentException("Migrations must start below the schema version.");
            }
            if (migrations.putIfAbsent(fromVersion, Objects.requireNonNull(migration, "migration")) != null) {
                throw new IllegalArgumentException("Duplicate migration from version " + fromVersion);
            }
            return this;
        }

        public ConfigSchema build() {
            return new ConfigSchema(this);
        }
    }
}
