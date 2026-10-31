package alkaline.clientapp;

import alkaline.client.settings.ClientSettings;
import alkaline.config.ConfigStore;
import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Launches the visible Alkaline 1.8.9 client: title screen over a Vulkan menu background. */
public final class ClientMain {
    private static final int INITIAL_WIDTH = 1_280;
    private static final int INITIAL_HEIGHT = 760;

    private ClientMain() {
    }

    private static volatile List<String> launchArguments;

    public static void main(String[] args) {
        launchArguments = List.of(args);
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        // Settings load before the renderer so startup-only values (validation, present mode) apply.
        ConfigStore config = new ConfigStore(ClientSettings.SCHEMA, options.settingsFile());
        config.load().issues().forEach(issue -> System.err.println("Settings: " + issue));
        boolean restart = false;
        try (config;
             SdlWindow window = SdlWindow.openVisible("Alkaline 1.8.9", INITIAL_WIDTH, INITIAL_HEIGHT,
                     SdlWindow.WindowSystem.valueOf(config.snapshot().get(ClientSettings.WINDOW_SYSTEM).name()));
             VulkanRenderer renderer = new VulkanRenderer(window,
                     ClientApp.presentMode(config.snapshot().get(ClientSettings.VIDEO_VSYNC), window.xwayland()),
                     config.snapshot().get(ClientSettings.DEVELOPER_VALIDATION));
             ClientApp app = new ClientApp(window, renderer, options, config)) {
            System.out.println("Alkaline 1.8.9 client on " + window.videoDriver() + " / SDL " + window.runtimeVersion()
                    + (renderer.hostImageCopy() ? "; textures upload by Vulkan 1.4 host image copy" : ""));
            app.run();
            restart = app.restartRequested();
        } catch (IOException failure) {
            System.err.println("Saving settings failed: " + failure.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("Alkaline client failed: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
        if (restart) {
            relaunch();
        }
    }

    /**
     * The command that relaunches this client: the running JVM, its input arguments and class path, this main
     * class, and the original program arguments. Empty when the class path is unavailable (for example, when
     * launched from a module image), in which case the menu offers only Quit.
     */
    static Optional<List<String>> relaunchCommand() {
        String classPath = System.getProperty("java.class.path", "");
        Path javaLauncher = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        if (classPath.isEmpty() || !javaLauncher.toFile().canExecute() || launchArguments == null) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>();
        command.add(javaLauncher.toString());
        command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(classPath);
        command.add(ClientMain.class.getName());
        command.addAll(launchArguments);
        return Optional.of(command);
    }

    /** Starts a fresh client after this one has released the window, GPU, and settings file. */
    private static void relaunch() {
        Optional<List<String>> command = relaunchCommand();
        if (command.isEmpty()) {
            System.err.println("Restart unavailable: the platform does not report this process's command line.");
            return;
        }
        try {
            new ProcessBuilder(command.get()).inheritIO().start();
            System.out.println("Restarting Alkaline.");
        } catch (IOException failure) {
            System.err.println("Restart failed: " + failure.getMessage());
        }
    }

    /** Command-line switches; {@code --screenshot} exists for scripted visual checks. */
    record LaunchOptions(Path settingsFile, Path screenshot, int screenshotDelayMillis, String page, String inputScript,
                         boolean stats, String join, Path world) {
        static final String USAGE = "Usage: game-client [--settings <file>] [--screenshot <png> [--after-ms <n>]] [--page title|singleplayer|multiplayer|settings]"
                + " [--input-script <ms>:click|move|resize:<x>,<y>;<ms>:key:<name>;...] [--stats] [--join <host[:port]>] [--world <save folder>]";

        static LaunchOptions parse(String[] args) {
            Path settings = Path.of(System.getProperty("user.home"), ".alkaline", "legacy", "settings.json");
            Path screenshot = null;
            int delay = 3000;
            String page = "title";
            String inputScript = "";
            boolean stats = false;
            String join = null;
            Path world = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--settings" -> settings = Path.of(value(args, ++index, argument));
                    case "--screenshot" -> screenshot = Path.of(value(args, ++index, argument));
                    case "--after-ms" -> {
                        try {
                            delay = Integer.parseInt(value(args, ++index, argument));
                        } catch (NumberFormatException invalid) {
                            throw new IllegalArgumentException("--after-ms needs an integer.");
                        }
                        if (delay < 0) {
                            throw new IllegalArgumentException("--after-ms must not be negative.");
                        }
                    }
                    case "--page" -> page = value(args, ++index, argument);
                    case "--input-script" -> inputScript = value(args, ++index, argument);
                    case "--stats" -> stats = true;
                    case "--join" -> join = value(args, ++index, argument);
                    case "--world" -> world = Path.of(value(args, ++index, argument)).toAbsolutePath();
                    default -> throw new IllegalArgumentException("Unknown argument " + argument);
                }
            }
            return new LaunchOptions(settings, screenshot, delay, page, inputScript, stats, join, world);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            return args[index];
        }
    }
}
