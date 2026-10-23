package alkaline.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {
    @TempDir
    Path directory;

    private ConfigSchema schema() {
        ConfigSchema.Builder builder = ConfigSchema.builder(1);
        builder.add(Setting.bool("fps.enabled", "HUD", "FPS", "", true));
        builder.add(Setting.decimal("fps.hud.x", "HUD", "X", "", 4f, -4096f, 4096f));
        return builder.build();
    }

    @Test
    void firstOpenCreatesTheDefaultProfile() throws IOException {
        ConfigSchema schema = schema();
        try (ProfileStore profiles = new ProfileStore(schema, directory)) {
            profiles.open();
            assertEquals(ProfileStore.DEFAULT, profiles.active());
            assertEquals(List.of(ProfileStore.DEFAULT), profiles.names());
            assertTrue(Files.exists(directory.resolve("Default.json")));
            assertTrue(Files.exists(directory.resolve("profiles.json")));
        }
    }

    @Test
    void profilesKeepTheirOwnValuesAndTheActiveOneSurvivesReopening() throws IOException {
        ConfigSchema schema = schema();
        Setting.BooleanSetting fps = (Setting.BooleanSetting) schema.find("fps.enabled");
        Setting.FloatSetting position = (Setting.FloatSetting) schema.find("fps.hud.x");
        AtomicInteger notified = new AtomicInteger();
        try (ProfileStore profiles = new ProfileStore(schema, directory)) {
            profiles.open();
            profiles.addListener(notified::incrementAndGet);
            profiles.store().set(position, 120f);
            profiles.create("Bedwars", true);
            assertEquals("Bedwars", profiles.active());
            assertEquals(120f, profiles.store().snapshot().get(position));
            profiles.store().set(fps, false);
            profiles.create("Clean", false);
            assertTrue(profiles.store().snapshot().get(fps));
            assertEquals(4f, profiles.store().snapshot().get(position));
            profiles.activate("bedwars");
            assertFalse(profiles.store().snapshot().get(fps));
            profiles.activate("Default");
            assertTrue(profiles.store().snapshot().get(fps));
            assertEquals(120f, profiles.store().snapshot().get(position));
            profiles.activate("Bedwars");
            assertEquals(List.of("Bedwars", "Clean", "Default"), profiles.names());
            assertTrue(notified.get() >= 5);
        }
        try (ProfileStore reopened = new ProfileStore(schema, directory)) {
            reopened.open();
            assertEquals("Bedwars", reopened.active());
            assertFalse(reopened.store().snapshot().get(fps));
            // Opening must not overwrite any profile with defaults.
            reopened.activate("Default");
            assertEquals(120f, reopened.store().snapshot().get(position));
        }
        try (ProfileStore again = new ProfileStore(schema, directory)) {
            again.open();
            assertEquals("Default", again.active());
            assertEquals(120f, again.store().snapshot().get(position));
        }
    }

    @Test
    void renameAndDeleteMoveTheActiveProfile() throws IOException {
        ConfigSchema schema = schema();
        Setting.FloatSetting position = (Setting.FloatSetting) schema.find("fps.hud.x");
        try (ProfileStore profiles = new ProfileStore(schema, directory)) {
            profiles.open();
            profiles.store().set(position, 33f);
            profiles.rename("Default", "Main");
            assertEquals("Main", profiles.active());
            assertEquals(List.of("Main"), profiles.names());
            assertEquals(33f, profiles.store().snapshot().get(position));
            assertThrows(IllegalArgumentException.class, () -> profiles.delete("Main"));
            profiles.create("Second", false);
            profiles.delete("Second");
            assertEquals("Main", profiles.active());
            assertEquals(33f, profiles.store().snapshot().get(position));
            assertThrows(IllegalArgumentException.class, () -> profiles.create("main", false));
            assertThrows(IllegalArgumentException.class, () -> profiles.create("../escape", false));
            assertThrows(IllegalArgumentException.class, () -> profiles.create("", false));
        }
    }
}
