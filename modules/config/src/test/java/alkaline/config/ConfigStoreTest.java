package alkaline.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    enum Quality { LOW, HIGH }

    private static final class Fixture {
        final Setting.BooleanSetting enabled = Setting.bool("menu.enabled", "Menu", "Enabled", "", true);
        final Setting.IntSetting passes = Setting.integer("panorama.blur_passes", "Panorama", "Blur", "", 7, 0, 15);
        final Setting.FloatSetting speed = Setting.decimal("panorama.speed", "Panorama", "Speed", "", 1f, 0f, 4f);
        final Setting.EnumSetting<Quality> quality =
                Setting.choice("render.quality", "Render", "Quality", "", Quality.class, Quality.HIGH);
        final Setting.ColorSetting tint = Setting.color("menu.tint", "Menu", "Tint", "", 0x80FFFFFF);
        final Setting.StringSetting name = Setting.text("profile.name", "Profile", "Name", "", "Alkaline", 16);
        final ConfigSchema schema;

        Fixture(int version, ConfigSchema.Migration... migrations) {
            ConfigSchema.Builder builder = ConfigSchema.builder(version);
            builder.add(enabled);
            builder.add(passes);
            builder.add(speed);
            builder.add(quality);
            builder.add(tint);
            builder.add(name);
            for (int index = 0; index < migrations.length; index++) {
                builder.migration(index + 1, migrations[index]);
            }
            schema = builder.build();
        }
    }

    @TempDir
    Path directory;

    @Test
    void missingFileUsesDefaultsAndRoundTripsEveryKind() throws IOException {
        Fixture fixture = new Fixture(1);
        Path file = directory.resolve("settings.json");
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertFalse(report.fileExisted());
            assertEquals(7, store.snapshot().get(fixture.passes));
            store.set(fixture.enabled, false);
            store.set(fixture.passes, 3);
            store.set(fixture.speed, 2.5f);
            store.set(fixture.quality, Quality.LOW);
            store.set(fixture.tint, 0x12345678);
            store.set(fixture.name, "Steve");
        }
        Fixture reloaded = new Fixture(1);
        try (ConfigStore store = new ConfigStore(reloaded.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            ConfigSnapshot snapshot = store.snapshot();
            assertFalse(snapshot.get(reloaded.enabled));
            assertEquals(3, snapshot.get(reloaded.passes));
            assertEquals(2.5f, snapshot.get(reloaded.speed));
            assertEquals(Quality.LOW, snapshot.get(reloaded.quality));
            assertEquals(0x12345678, snapshot.get(reloaded.tint));
            assertEquals("Steve", snapshot.get(reloaded.name));
        }
        assertTrue(Files.readString(file).contains("\"menu.tint\": \"#12345678\""));
    }

    @Test
    void invalidValuesFallBackToDefaultsAndAreReported() throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, """
                {"schema": 1, "settings": {"panorama.blur_passes": 99, "render.quality": "ultra",
                 "menu.enabled": "yes", "panorama.speed": 1, "menu.tint": "#FF000000",
                 "profile.name": "Alex"}}
                """, StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(1);
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertEquals(3, report.issues().size(), report.issues().toString());
            assertEquals(7, store.snapshot().get(fixture.passes));
            assertEquals(Quality.HIGH, store.snapshot().get(fixture.quality));
            assertEquals(1f, store.snapshot().get(fixture.speed));
            assertEquals("Alex", store.snapshot().get(fixture.name));
        }
    }

    @Test
    void migrationsRunInOrderAndRewriteTheFile() throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"schema\": 1, \"settings\": {\"panorama.blur\": 5}}", StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(2, settings -> settings.put("panorama.blur_passes", settings.remove("panorama.blur")));
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.migrated());
            assertEquals(5, store.snapshot().get(fixture.passes));
        }
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"schema\": 2"));
        assertFalse(saved.contains("\"panorama.blur\""));
    }

    @Test
    void schemaWithoutRequiredMigrationIsRejected() {
        assertThrows(IllegalStateException.class, () -> new Fixture(3, settings -> { }));
    }

    @Test
    void unknownKeysArePreserved() throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"schema\": 1, \"settings\": {\"future.feature\": [1, 2]}}", StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(1);
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            store.load();
            store.set(fixture.passes, 2);
        }
        Object root = Json.parse(Files.readString(file));
        Map<?, ?> settings = (Map<?, ?>) ((Map<?, ?>) root).get("settings");
        assertEquals(List.of(1L, 2L), settings.get("future.feature"));
    }

    @Test
    void corruptFileIsBackedUpAndDefaultsRestored() throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"schema\": 1, \"settings\": {", StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(1);
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertFalse(report.readOnly());
            assertEquals(1, report.issues().size());
            store.set(fixture.passes, 1);
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("settings.json.corrupt-")));
        }
    }

    @Test
    void newerSchemaFilesAreNeverOverwritten() throws IOException {
        Path file = directory.resolve("settings.json");
        String original = "{\"schema\": 9, \"settings\": {\"panorama.blur_passes\": 4}}";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Fixture fixture = new Fixture(1);
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.readOnly());
            assertEquals(4, store.snapshot().get(fixture.passes));
            store.set(fixture.passes, 6);
        }
        assertEquals(original, Files.readString(file));
    }

    @Test
    void listenersSeeChangesButNotNoOps() {
        Fixture fixture = new Fixture(1);
        ConfigStore store = new ConfigStore(fixture.schema, directory.resolve("settings.json"));
        AtomicInteger calls = new AtomicInteger();
        store.addListener((previous, current) -> calls.incrementAndGet());
        store.set(fixture.passes, 7);
        assertEquals(0, calls.get());
        store.set(fixture.passes, 4);
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> store.set(fixture.passes, 16));
    }

    @Test
    void settingsBelongToOneSchema() {
        Fixture fixture = new Fixture(1);
        ConfigSchema other = ConfigSchema.builder(1).build();
        assertThrows(IllegalArgumentException.class, () -> ConfigSnapshot.defaults(other).get(fixture.passes));
        assertThrows(IllegalStateException.class, () -> ConfigSchema.builder(1).add(fixture.passes));
    }

    @Test
    void jsonRejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": 1,}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(40) + "]".repeat(40)));
        assertEquals(Map.of("a", "é\n"), Json.parse("{\"a\": \"\\u00e9\\n\"}"));
    }

    @Test
    void restartRequiredIsSchemaMetadataOnly() {
        Setting.BooleanSetting live = Setting.bool("video.live", "Video", "Live", "", true);
        Setting.BooleanSetting startup = Setting.bool("video.startup", "Video", "Startup", "", false);
        ConfigSchema.Builder builder = ConfigSchema.builder(1);
        builder.add(live);
        builder.addRestartRequired(startup);
        builder.build();
        assertFalse(live.restartRequired());
        assertTrue(startup.restartRequired());
    }

    @Test
    void saveStateReportsPendingThenSaved() throws Exception {
        Fixture fixture = new Fixture(1);
        Path file = directory.resolve("settings.json");
        try (ConfigStore store = new ConfigStore(fixture.schema, file)) {
            store.load();
            List<ConfigStore.SaveState> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            store.addSaveListener(seen::add);
            store.set(fixture.passes, 2);
            assertEquals(ConfigStore.SaveState.PENDING, store.saveState());
            store.flush();
            assertEquals(ConfigStore.SaveState.SAVED, store.saveState());
            assertEquals(List.of(ConfigStore.SaveState.PENDING, ConfigStore.SaveState.SAVED), seen);
        }
    }
}
