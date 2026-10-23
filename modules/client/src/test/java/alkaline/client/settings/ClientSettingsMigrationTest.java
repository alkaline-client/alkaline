package alkaline.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.config.ConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSettingsMigrationTest {
    @Test
    void version1ThemesBecomeMonetSchemes(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, """
                {"schema": 1, "settings": {"interface.theme": "light", "menu.background": "panorama"}}
                """, StandardCharsets.UTF_8);
        try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            assertEquals(ClientSettings.Theme.MONET_LIGHT, store.snapshot().get(ClientSettings.INTERFACE_THEME));
            assertEquals(ClientSettings.Background.PANORAMA, store.snapshot().get(ClientSettings.MENU_BACKGROUND));
        }
    }

    @Test
    void version2FrameLimitMovesToVanillasRange(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, """
                {"schema": 2, "settings": {"video.frame_limit": 0}}
                """, StandardCharsets.UTF_8);
        try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            assertEquals(ClientSettings.FRAME_LIMIT_UNLIMITED, store.snapshot().get(ClientSettings.VIDEO_FRAME_LIMIT));
        }
        Files.writeString(file, """
                {"schema": 2, "settings": {"video.frame_limit": 480}}
                """, StandardCharsets.UTF_8);
        try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
            store.load();
            assertEquals(260, store.snapshot().get(ClientSettings.VIDEO_FRAME_LIMIT));
        }
        Files.writeString(file, """
                {"schema": 2, "settings": {"video.frame_limit": 144}}
                """, StandardCharsets.UTF_8);
        try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
            store.load();
            assertEquals(144, store.snapshot().get(ClientSettings.VIDEO_FRAME_LIMIT));
        }
    }

    @Test
    void version3DropsTheFpsCounterThatMovedIntoTheHud(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, """
                {"schema": 3, "settings": {"interface.fps_counter": false, "world.fov": 90.0}}
                """, StandardCharsets.UTF_8);
        try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
            ConfigStore.LoadReport report = store.load();
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            assertTrue(store.snapshot().unknown().isEmpty(), store.snapshot().unknown().toString());
            assertEquals(90f, store.snapshot().get(ClientSettings.WORLD_FOV));
            assertEquals(ClientSettings.ViewBobbing.FULL, store.snapshot().get(ClientSettings.WORLD_VIEW_BOBBING));
            assertEquals(ClientSettings.VANILLA_HIT_COLOR, store.snapshot().get(ClientSettings.WORLD_HIT_COLOR));
        }
    }

    @Test
    void version4TurnsFrameSyncIntoTheVSyncSwitch(@TempDir Path directory) throws IOException {
        for (String sync : new String[]{"vsync", "low_latency", "uncapped"}) {
            Path file = directory.resolve(sync + ".json");
            Files.writeString(file, "{\"schema\": 4, \"settings\": {\"video.sync\": \"" + sync + "\"}}",
                    StandardCharsets.UTF_8);
            try (ConfigStore store = new ConfigStore(ClientSettings.SCHEMA, file)) {
                ConfigStore.LoadReport report = store.load();
                assertTrue(report.issues().isEmpty(), report.issues().toString());
                assertTrue(store.snapshot().unknown().isEmpty(), store.snapshot().unknown().toString());
                assertEquals(sync.equals("vsync"), store.snapshot().get(ClientSettings.VIDEO_VSYNC));
                assertEquals(ClientSettings.WindowSystem.AUTO, store.snapshot().get(ClientSettings.WINDOW_SYSTEM));
            }
        }
    }
}
