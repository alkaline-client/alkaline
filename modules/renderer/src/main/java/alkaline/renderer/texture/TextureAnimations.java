package alkaline.renderer.texture;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * OptiFine / MCPatcher texture animations (TextureAnimations, TextureAnimation): {@code anim/*.properties} copy
 * frames of a {@code from} strip into the {@code x, y, w, h} region of a standalone {@code to} texture, one step per
 * game tick, with {@code tile.N} frame order, {@code duration} and {@code duration.N} in ticks, and {@code
 * interpolate} (blending toward the next frame, every {@code skip} ticks).
 *
 * <p>The renderer never rewrites a texture while drawing: each distinct look of a target ({@link Target#state}) is
 * composed once ({@link Target#compose}) and kept, and drawing picks the one for the current state.
 */
public final class TextureAnimations {
    public static final TextureAnimations NONE = new TextureAnimations(Map.of());
    private static final String[] ROOTS = {"assets/minecraft/optifine/anim/", "assets/minecraft/mcpatcher/anim/"};

    /** One animation's frames and its place in them. */
    static final class Animation {
        final int x;
        final int y;
        final int width;
        final int height;
        /** Source frames, RGBA, {@code width * height * 4} bytes each. */
        final byte[] frames;
        final int[] tiles;
        final int[] durations;
        final boolean interpolate;
        final int skip;
        int current;
        int counter;
        /**
         * What the region shows: -1 for the target's own pixels (until the first frame ends), else the frame
         * index times 65536 plus the interpolation step (0 for the plain frame).
         */
        int shown = -1;

        Animation(int x, int y, int width, int height, byte[] frames, int[] tiles, int[] durations,
                  boolean interpolate, int skip) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.frames = frames;
            this.tiles = tiles;
            this.durations = durations;
            this.interpolate = interpolate;
            this.skip = skip;
        }

        /** TextureAnimation.nextFrame and updateTexture's choice of what to upload. */
        void tick() {
            if (tiles.length == 0) {
                return;
            }
            int frameBytes = width * height * 4;
            counter++;
            if (counter < durations[current]) {
                if (interpolate && (skip <= 1 || counter % skip == 0) && valid(tiles[current], frameBytes)
                        && valid(tiles[(current + 1) % tiles.length], frameBytes)) {
                    shown = current << 16 | counter;
                }
                return;
            }
            counter = 0;
            current = current + 1 >= tiles.length ? 0 : current + 1;
            if (valid(tiles[current], frameBytes)) {
                shown = current << 16;
            }
        }

        private boolean valid(int tile, int frameBytes) {
            return tile >= 0 && (long) frameBytes * (tile + 1) <= frames.length;
        }

        /** Writes what the region shows into a copy of the target's pixels. */
        void draw(byte[] target, int targetWidth) {
            if (shown < 0) {
                return;
            }
            int frame = shown >>> 16;
            int step = shown & 0xFFFF;
            int frameBytes = width * height * 4;
            int from = tiles[frame] * frameBytes;
            int next = tiles[(frame + 1) % tiles.length] * frameBytes;
            double share = step / (double) durations[frame];
            for (int row = 0; row < height; row++) {
                int out = ((y + row) * targetWidth + x) * 4;
                int in = row * width * 4;
                if (step == 0) {
                    System.arraycopy(frames, from + in, target, out, width * 4);
                    continue;
                }
                for (int index = 0; index < width * 4; index++) {
                    // TextureAnimation.mix, per byte.
                    int a = frames[from + in + index] & 255;
                    int b = frames[next + in + index] & 255;
                    target[out + index] = (byte) (int) (a * (1.0 - share) + b * share);
                }
            }
        }
    }

    /** The animations drawn into one texture. */
    public static final class Target {
        private final String path;
        private final Animation[] animations;

        Target(String path, Animation[] animations) {
            this.path = path;
            this.animations = animations;
        }

        /** The target texture's asset path. */
        public String path() {
            return path;
        }

        /**
         * The current look: equal states compose equal images. Exact for up to two animations on one texture,
         * a 64-bit mix beyond that.
         */
        public long state() {
            if (animations.length == 1) {
                return animations[0].shown;
            }
            if (animations.length == 2) {
                return (long) animations[0].shown << 32 | animations[1].shown & 0xFFFFFFFFL;
            }
            long state = 0x9E3779B97F4A7C15L;
            for (Animation animation : animations) {
                state = (state ^ animation.shown) * 0xBF58476D1CE4E5B9L;
                state ^= state >>> 31;
            }
            return state;
        }

        /** The target as it looks now: its pixels with every animation's region drawn over them, in file order. */
        public RgbaImage compose(RgbaImage base) {
            byte[] pixels = base.copyPixels();
            for (Animation animation : animations) {
                if (animation.x + animation.width <= base.width() && animation.y + animation.height <= base.height()) {
                    animation.draw(pixels, base.width());
                }
            }
            return new RgbaImage(base.width(), base.height(), pixels);
        }
    }

    private final Map<String, Target> targets;
    private final Target[] all;

    private TextureAnimations(Map<String, Target> targets) {
        this.targets = Map.copyOf(targets);
        all = targets.values().toArray(Target[]::new);
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    /** The animations of a texture by asset path ({@code assets/minecraft/textures/...png}), or null. */
    public Target target(String path) {
        return targets.get(path);
    }

    /** Advances every animation one game tick (TextureAnimations.updateAnimations). */
    public void tick() {
        for (Target target : all) {
            for (Animation animation : target.animations) {
                animation.tick();
            }
        }
    }

    /** Reads {@code optifine/anim/} and {@code mcpatcher/anim/} (OptiFine's ResUtils.collectFiles order: by name). */
    public static TextureAnimations load(AssetSource assets) {
        if (assets == null) {
            return NONE;
        }
        Map<String, List<Animation>> byTarget = new LinkedHashMap<>();
        for (String root : ROOTS) {
            List<String> files;
            try {
                files = assets.list(root);
            } catch (IOException unreadable) {
                continue;
            }
            for (String file : files.stream().filter(name -> name.endsWith(".properties")).sorted().toList()) {
                Properties properties = properties(assets, file);
                String[] target = new String[1];
                Animation animation = properties == null ? null : animation(assets, file, properties, target);
                if (animation == null) {
                    System.err.println("Texture animation " + file + " skipped.");
                } else {
                    byTarget.computeIfAbsent(target[0], key -> new ArrayList<>()).add(animation);
                }
            }
        }
        Map<String, Target> targets = new LinkedHashMap<>();
        byTarget.forEach((path, list) -> targets.put(path, new Target(path, list.toArray(Animation[]::new))));
        return targets.isEmpty() ? NONE : new TextureAnimations(targets);
    }

    /** TextureAnimations.makeTextureAnimation; {@code target[0]} receives the target's asset path. */
    static Animation animation(AssetSource assets, String file, Properties properties, String[] target) {
        String from = properties.getProperty("from");
        String to = properties.getProperty("to");
        int x = parseInt(properties.getProperty("x"), -1);
        int y = parseInt(properties.getProperty("y"), -1);
        int width = parseInt(properties.getProperty("w"), -1);
        int height = parseInt(properties.getProperty("h"), -1);
        if (from == null || to == null || x < 0 || y < 0 || width <= 0 || height <= 0) {
            return null;
        }
        String directory = file.substring(0, file.lastIndexOf('/'));
        String source = path(from.trim(), directory);
        String destination = path(to.trim(), directory);
        RgbaImage strip = image(assets, source);
        if (strip == null) {
            // getCustomTextureData's second try, under /anim.
            strip = image(assets, "assets/minecraft/anim/" + source.substring("assets/minecraft/".length()));
        }
        RgbaImage targetImage = image(assets, destination);
        if (strip == null || targetImage == null || x + width > targetImage.width() || y + height > targetImage.height()) {
            return null;
        }
        if (strip.width() != width) {
            // loadImage: scaled to the frame width, the height by the whole-number ratio of the original.
            strip = scale(strip, width, width * (strip.height() / strip.width()));
        }
        byte[] frames = strip.copyPixels();
        int frameBytes = width * height * 4;
        if (frames.length == 0 || frames.length % frameBytes != 0) {
            return null;
        }
        int count = frames.length / frameBytes;
        if (properties.getProperty("tile.0") != null) {
            count = 0;
            while (properties.getProperty("tile." + count) != null) {
                count++;
            }
        }
        int duration = Math.max(parseInt(properties.getProperty("duration"), 1), 1);
        int[] tiles = new int[count];
        int[] durations = new int[count];
        for (int index = 0; index < count; index++) {
            tiles[index] = parseInt(properties.getProperty("tile." + index), index);
            durations[index] = Math.max(parseInt(properties.getProperty("duration." + index), duration), 1);
        }
        boolean interpolate = "true".equalsIgnoreCase(properties.getProperty("interpolate", "false").trim());
        target[0] = destination;
        return new Animation(x, y, width, height, frames, tiles, durations, interpolate,
                parseInt(properties.getProperty("skip"), 0));
    }

    /**
     * TextureUtils.fixResourcePath as an asset path: {@code ./} beside the properties, {@code ~/} and a leading
     * {@code /} under mcpatcher/, a full {@code assets/minecraft/} path as is, anything else from assets/minecraft.
     */
    static String path(String path, String directory) {
        String prefix = "assets/minecraft/";
        if (path.startsWith(prefix)) {
            return path;
        }
        if (path.startsWith("./")) {
            return directory + "/" + path.substring(2);
        }
        if (path.startsWith("/~")) {
            path = path.substring(1);
        }
        if (path.startsWith("~/")) {
            return prefix + "mcpatcher/" + path.substring(2);
        }
        if (path.startsWith("/")) {
            return prefix + "mcpatcher/" + path.substring(1);
        }
        return prefix + (path.startsWith("minecraft:") ? path.substring(10) : path);
    }

    /** Bilinear scaling (Java2D's VALUE_INTERPOLATION_BILINEAR, sampled at pixel centers). */
    static RgbaImage scale(RgbaImage image, int width, int height) {
        byte[] in = image.copyPixels();
        byte[] out = new byte[width * height * 4];
        double scaleX = image.width() / (double) width;
        double scaleY = image.height() / (double) height;
        for (int row = 0; row < height; row++) {
            double sourceY = Math.max(0, (row + 0.5) * scaleY - 0.5);
            int y0 = Math.min(image.height() - 1, (int) sourceY);
            int y1 = Math.min(image.height() - 1, y0 + 1);
            double fy = sourceY - y0;
            for (int column = 0; column < width; column++) {
                double sourceX = Math.max(0, (column + 0.5) * scaleX - 0.5);
                int x0 = Math.min(image.width() - 1, (int) sourceX);
                int x1 = Math.min(image.width() - 1, x0 + 1);
                double fx = sourceX - x0;
                for (int channel = 0; channel < 4; channel++) {
                    double top = (in[(y0 * image.width() + x0) * 4 + channel] & 255) * (1 - fx)
                            + (in[(y0 * image.width() + x1) * 4 + channel] & 255) * fx;
                    double bottom = (in[(y1 * image.width() + x0) * 4 + channel] & 255) * (1 - fx)
                            + (in[(y1 * image.width() + x1) * 4 + channel] & 255) * fx;
                    out[(row * width + column) * 4 + channel] = (byte) Math.round(top * (1 - fy) + bottom * fy);
                }
            }
        }
        return new RgbaImage(width, height, out);
    }

    private static RgbaImage image(AssetSource assets, String path) {
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static Properties properties(AssetSource assets, String path) {
        try (InputStream input = assets.open(path)) {
            if (input == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
            return properties;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
