package alkaline.platform.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibinputClickFixTest {
    @Test
    void scriptIsBundledAndWritesTheMarkerStatusLooksFor() throws IOException {
        try (InputStream input = LibinputClickFix.class.getResourceAsStream("alkaline-click-fix.sh")) {
            assertNotNull(input);
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(script.contains("BEGIN=\"" + LibinputClickFix.MARKER + "\""));
            assertTrue(script.contains("ModelBouncingKeys=1"));
        }
    }

    @Test
    void statusReadsTheMarkedSection(@TempDir Path directory) throws IOException {
        Path overrides = directory.resolve("local-overrides.quirks");
        assertEquals(LibinputClickFix.Status.NOT_INSTALLED, LibinputClickFix.status(overrides));
        Files.writeString(overrides, "[Mine]\nMatchName=x\nModelBouncingKeys=1\n");
        assertEquals(LibinputClickFix.Status.NOT_INSTALLED, LibinputClickFix.status(overrides));
        Files.writeString(overrides, LibinputClickFix.MARKER + "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(LibinputClickFix.Status.INSTALLED, LibinputClickFix.status(overrides));
    }

    @Test
    void privilegedCommandUsesTheSystemShell() {
        assertEquals(List.of("pkexec", "/bin/sh", "/tmp/fix.sh", "remove"),
                LibinputClickFix.command(Path.of("/tmp/fix.sh"), false));
    }
}
