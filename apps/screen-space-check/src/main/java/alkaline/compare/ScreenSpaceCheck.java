package alkaline.compare;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Exact pixel comparator for matching OpenGL and Vulkan captures of one deterministic frame. */
public final class ScreenSpaceCheck {
    public static final int MATCH = 0;
    public static final int DIFFERENT = 1;
    public static final int INVALID_INPUT = 2;

    private static final int MATCH_PIXEL = 0xFF404040;
    private static final int DIFFERENCE_PIXEL = 0xFFFF20C0;

    private ScreenSpaceCheck() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != MATCH) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length != 6 || !"--opengl".equals(args[0]) || !"--vulkan".equals(args[2])
                || !"--output".equals(args[4])) {
            err.println("Usage: screen-space-check --opengl <original.png> --vulkan <vulkan.png> "
                    + "--output <result.png>");
            return INVALID_INPUT;
        }

        try {
            Path openGlPath = Path.of(args[1]).toAbsolutePath().normalize();
            Path vulkanPath = Path.of(args[3]).toAbsolutePath().normalize();
            Path outputPath = Path.of(args[5]).toAbsolutePath().normalize();
            if (outputPath.getParent() == null || !Files.isDirectory(outputPath.getParent())) {
                throw new IOException("Result parent directory does not exist: " + outputPath.getParent());
            }
            try (PngImages.ValidatedPng openGlPng = PngImages.open(openGlPath);
                 PngImages.ValidatedPng vulkanPng = PngImages.open(vulkanPath)) {
                if (Files.exists(outputPath)) {
                    throw new IOException("Refusing to overwrite existing result: " + outputPath);
                }
                if (Files.isSameFile(openGlPath, vulkanPath)) {
                    throw new IOException("OpenGL and Vulkan captures must be separate files.");
                }
                if (!outputPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                    throw new IOException("Result path must end in .png.");
                }
                if (openGlPng.width() != vulkanPng.width() || openGlPng.height() != vulkanPng.height()) {
                    throw new IOException("Capture dimensions differ: OpenGL " + dimensions(openGlPng.width(),
                            openGlPng.height()) + ", Vulkan " + dimensions(vulkanPng.width(), vulkanPng.height()));
                }
                BufferedImage openGl = openGlPng.read();
                BufferedImage vulkan = vulkanPng.read();
                BufferedImage result = new BufferedImage(openGl.getWidth(), openGl.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                long changed = compare(openGl, vulkan, result);
                writeNewPng(result, outputPath);
                out.printf("Dimensions: %d x %d%n", result.getWidth(), result.getHeight());
                out.printf("Different pixels: %d / %d%n", changed,
                        (long) result.getWidth() * result.getHeight());
                out.println("Result image: " + outputPath);
                out.println("Mask: gray = identical; magenta = different (including alpha).");
                out.println(changed == 0 ? "PASS: captures match exactly." : "FAIL: captures differ.");
                return changed == 0 ? MATCH : DIFFERENT;
            }
        } catch (IOException | InvalidPathException | SecurityException failure) {
            err.println("Comparison failed: " + failure.getMessage());
            return INVALID_INPUT;
        }
    }

    static long compare(BufferedImage openGl, BufferedImage vulkan, BufferedImage result) {
        Objects.requireNonNull(openGl, "openGl");
        Objects.requireNonNull(vulkan, "vulkan");
        Objects.requireNonNull(result, "result");
        int width = openGl.getWidth();
        int height = openGl.getHeight();
        if (width <= 0 || height <= 0 || vulkan.getWidth() != width || vulkan.getHeight() != height
                || result.getWidth() != width || result.getHeight() != height) {
            throw new IllegalArgumentException("Capture and result dimensions must match and be nonzero.");
        }

        long changed = 0;
        int[] expected = new int[width];
        int[] actual = new int[width];
        int[] mask = new int[width];
        for (int y = 0; y < height; y++) {
            openGl.getRGB(0, y, width, 1, expected, 0, width);
            vulkan.getRGB(0, y, width, 1, actual, 0, width);
            for (int x = 0; x < width; x++) {
                boolean same = expected[x] == actual[x];
                mask[x] = same ? MATCH_PIXEL : DIFFERENCE_PIXEL;
                if (!same) {
                    changed++;
                }
            }
            result.setRGB(0, y, width, 1, mask, 0, width);
        }
        return changed;
    }

    private static String dimensions(int width, int height) {
        return width + " x " + height;
    }

    static void writeNewPng(BufferedImage image, Path path) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".screen-space-check-", ".tmp");
        Object fileKey = Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
        IOException pendingFailure = null;
        try {
            try (var stream = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                if (!ImageIO.write(image, "PNG", stream)) {
                    throw new IOException("No PNG writer is available.");
                }
            }
            Files.createLink(path, temporary);
        } catch (IOException | RuntimeException failure) {
            pendingFailure = failure instanceof IOException ioFailure ? ioFailure
                    : new IOException("Could not encode or publish result PNG.", failure);
            throw pendingFailure;
        } finally {
            try {
                if (isOwnedTemporary(temporary, fileKey)) {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException cleanupFailure) {
                if (pendingFailure != null) {
                    pendingFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static boolean isOwnedTemporary(Path temporary, Object originalFileKey) throws IOException {
        if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        BasicFileAttributes current = Files.readAttributes(temporary, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!current.isRegularFile()) {
            return false;
        }
        return originalFileKey != null && originalFileKey.equals(current.fileKey());
    }
}
