package alkaline.platform.linux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

/**
 * The one-click fix for libinput's mouse button debouncing on Linux.
 *
 * <p>Alkaline reads the mouse the way Minecraft does, through the window system's events. On Linux the display
 * server (an X server or a Wayland compositor) reads the mouse through libinput, whose debouncing drops a release
 * and the following press when they arrive within 25 ms of the previous button event. Fast butterfly and drag
 * clicking produce exactly that, so those clicks never reach any application. libinput runs inside the display
 * server, so no application can substitute a patched copy; its supported switch is the {@code ModelBouncingKeys}
 * device quirk, which upstream itself sets for some mice. The bundled script adds that quirk for all mice to
 * {@code /etc/libinput/local-overrides.quirks} between markers, and removes exactly that section again. Both need
 * administrator rights, requested through polkit's {@code pkexec}; a launcher can offer them as one button.
 */
public final class LibinputClickFix {
    public enum Status {
        /** The quirk section is present; libinput applies it to mice added after it was written. */
        INSTALLED,
        NOT_INSTALLED,
        /** Not Linux, or no libinput quirks database: nothing to fix. */
        UNSUPPORTED
    }

    public static final Path OVERRIDES = Path.of("/etc/libinput/local-overrides.quirks");
    /** The marker line the script writes; must match {@code alkaline-click-fix.sh}. */
    static final String MARKER = "# >>> Alkaline click fix >>>";
    private static final Path QUIRKS_DATABASE = Path.of("/usr/share/libinput");
    private static final String SCRIPT = "alkaline-click-fix.sh";

    private LibinputClickFix() {
    }

    public static Status status() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")
                || !Files.isDirectory(QUIRKS_DATABASE)) {
            return Status.UNSUPPORTED;
        }
        return status(OVERRIDES);
    }

    static Status status(Path overrides) {
        try {
            return Files.isRegularFile(overrides) && Files.readAllLines(overrides).contains(MARKER)
                    ? Status.INSTALLED : Status.NOT_INSTALLED;
        } catch (IOException unreadable) {
            return Status.NOT_INSTALLED;
        }
    }

    /**
     * Installs ({@code true}) or removes the quirk through {@code pkexec}, which asks for an administrator's
     * password in the desktop's own dialog. Blocks until the script exits; returns its exit code (126 or 127 when
     * the request was dismissed or polkit is unavailable). Output goes to this process's standard streams.
     */
    public static int apply(boolean install) throws IOException, InterruptedException {
        Path script = Files.createTempFile("alkaline-click-fix", ".sh",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (InputStream input = LibinputClickFix.class.getResourceAsStream(SCRIPT)) {
                if (input == null) {
                    throw new IOException("The click fix script is missing from the build.");
                }
                Files.copy(input, script, StandardCopyOption.REPLACE_EXISTING);
            }
            Process process = new ProcessBuilder(command(script, install)).inheritIO().start();
            return process.waitFor();
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /** The privileged command line: the system shell runs the script, never a user-writable interpreter. */
    static List<String> command(Path script, boolean install) {
        return List.of("pkexec", "/bin/sh", script.toAbsolutePath().toString(), install ? "install" : "remove");
    }
}
