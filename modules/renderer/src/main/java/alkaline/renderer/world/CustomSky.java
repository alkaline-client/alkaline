package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.world.vanilla.BiomeNames189;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * OptiFine / MCPatcher custom sky for the overworld, as OptiFine for 1.8.9 reads it: {@code sky/world0/sky<N>.properties}
 * layers in number order, each a skybox texture faded in and out over the day (all three of startFadeIn, endFadeIn
 * and endFadeOut are required, and the four phases must add up to 24 hours), shown on chosen days and heights and in
 * chosen weather, in chosen biomes and at chosen heights (faded over {@code transition} seconds), blended over the sky
 * below it and turned with the sun.
 */
public final class CustomSky {
    public static final CustomSky NONE = new CustomSky(List.of());
    private static final String[] ROOTS = {"assets/minecraft/optifine/sky/world0/", "assets/minecraft/mcpatcher/sky/world0/"};

    /** How a layer combines with what is drawn below it (OptiFine's Blender). */
    public enum Blend { ALPHA, ADD, SUBTRACT, MULTIPLY, DODGE, BURN, SCREEN, OVERLAY, REPLACE }

    /**
     * One layer. Times are world time of day (0 = 6:00).
     *
     * @param source  the texture as a full asset path
     * @param axis    the rotation axis in the frame the layers draw in (after the sky's -90 degree turn)
     * @param heights    inclusive ranges of the viewer's block y, or empty for any
     * @param biomes     the biome set (BiomeNames189 bits) the viewer must stand in, or null for any
     * @param transition seconds the biomes and heights conditions take to fade the layer in or out
     * @param days       inclusive ranges of days within {@code daysLoop}, or empty for every day
     */
    public record Layer(String source, int startFadeIn, int endFadeIn, int startFadeOut, int endFadeOut, Blend blend,
                        boolean rotate, float speed, float[] axis, boolean clear, boolean rain, boolean thunder,
                        List<int[]> heights, long[] biomes, float transition, List<int[]> days, int daysLoop) {
        /** CustomSkyLayer.isActive: not between fading out and fading in, and on one of its days. */
        public boolean active(long worldTime) {
            int time = (int) Math.floorMod(worldTime, 24000L);
            if (between(time, endFadeOut, startFadeIn)) {
                return false;
            }
            if (!days.isEmpty()) {
                long since = worldTime - startFadeIn;
                while (since < 0L) {
                    since += 24000L * daysLoop;
                }
                return inRanges(days, (int) (since / 24000L % daysLoop));
            }
            return true;
        }

        /** getFadeBrightness: up between the fade-in times, full until the fade-out, down to 0 after it. */
        public float fade(long worldTime) {
            int time = (int) Math.floorMod(worldTime, 24000L);
            if (between(time, startFadeIn, endFadeIn)) {
                return normalize(time - startFadeIn) / (float) normalize(endFadeIn - startFadeIn);
            }
            if (between(time, endFadeIn, startFadeOut)) {
                return 1f;
            }
            if (between(time, startFadeOut, endFadeOut)) {
                return 1f - normalize(time - startFadeOut) / (float) normalize(endFadeOut - startFadeOut);
            }
            return 0f;
        }

        /**
         * getWeatherBrightness: clear sky weighs 1 - rain, rain weighs rain - thunder and thunder weighs thunder,
         * summed and clamped.
         */
        public float weather(float rainStrength, float thunderStrength) {
            float strength = 0f;
            if (clear) {
                strength += 1f - rainStrength;
            }
            if (rain) {
                strength += rainStrength - thunderStrength;
            }
            if (thunder) {
                strength += thunderStrength;
            }
            return Math.max(0f, Math.min(1f, strength));
        }

        /** Whether the layer has biomes or heights, whose brightness is smoothed over {@code transition}. */
        public boolean positional() {
            return biomes != null || !heights.isEmpty();
        }

        /**
         * getPositionBrightnessRaw for the viewer's Entity.getPosition (block y of feet + 0.5) and the biome there,
         * before smoothing.
         */
        public float position(int blockY, int biome) {
            if (biomes != null && !BiomeNames189.contains(biomes, biome)) {
                return 0f;
            }
            return heights.isEmpty() || inRanges(heights, blockY) ? 1f : 0f;
        }

        /** The turn in degrees: the celestial angle times the speed, plus a per-day offset for fractional speeds. */
        public float angle(long worldTime, float celestialAngle) {
            float offset = 0f;
            if (speed != Math.round(speed)) {
                long day = (worldTime + 18000L) / 24000L;
                offset = (float) (day * (double) (speed % 1.0F) % 1.0D);
            }
            return 360f * (offset + celestialAngle * speed);
        }

        private static boolean between(int time, int start, int end) {
            return start <= end ? time >= start && time <= end : time >= start || time <= end;
        }

        private static boolean inRanges(List<int[]> ranges, int value) {
            for (int[] range : ranges) {
                if (value >= range[0] && value <= range[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * SmoothFloat.getSmoothValue: moves {@code previous} toward {@code value} over {@code fadeSeconds}, by a share of
     * the gap that grows with the time since the last step; jumps there when the step is longer than the fade.
     */
    public static float smooth(float previous, float value, float seconds, float fadeSeconds) {
        if (seconds <= 0f) {
            return previous;
        }
        float gap = value - previous;
        if (fadeSeconds > 0f && seconds < fadeSeconds && Math.abs(gap) > 1.0E-6F) {
            float steps = fadeSeconds / seconds;
            float scale = 4.61F - 1.0F / (0.13F + steps / 10.0F);
            float share = Math.max(0f, Math.min(1f, seconds / fadeSeconds * scale));
            return previous + gap * share;
        }
        return value;
    }

    private final List<Layer> layers;

    private CustomSky(List<Layer> layers) {
        this.layers = List.copyOf(layers);
    }

    public List<Layer> layers() {
        return layers;
    }

    /** Reads the overworld's layers (optifine/ first, then mcpatcher/; the first folder with any wins). */
    public static CustomSky load(AssetSource assets) {
        for (String root : ROOTS) {
            List<Layer> layers = new ArrayList<>();
            for (int number = 1; number <= 64; number++) {
                Properties properties = properties(assets, root + "sky" + number + ".properties");
                if (properties == null) {
                    continue;
                }
                Layer layer = layer(root, "sky" + number, properties);
                if (layer == null) {
                    System.err.println("Custom sky layer " + root + "sky" + number + " skipped: invalid fade times.");
                } else {
                    layers.add(layer);
                }
            }
            if (!layers.isEmpty()) {
                return new CustomSky(layers);
            }
        }
        return NONE;
    }

    /** CustomSkyLayer's parsing and isValid; null for an invalid layer. */
    static Layer layer(String root, String name, Properties properties) {
        String source = properties.getProperty("source");
        int startFadeIn = time(properties.getProperty("startFadeIn"));
        int endFadeIn = time(properties.getProperty("endFadeIn"));
        int startFadeOut = time(properties.getProperty("startFadeOut"));
        int endFadeOut = time(properties.getProperty("endFadeOut"));
        if (startFadeIn < 0 || endFadeIn < 0 || endFadeOut < 0) {
            return null;
        }
        int fadeIn = normalize(endFadeIn - startFadeIn);
        if (startFadeOut < 0) {
            startFadeOut = normalize(endFadeOut - fadeIn);
            if (Layer.between(startFadeOut, startFadeIn, endFadeIn)) {
                startFadeOut = endFadeIn;
            }
        }
        int total = fadeIn + normalize(startFadeOut - endFadeIn) + normalize(endFadeOut - startFadeOut)
                + normalize(startFadeIn - endFadeOut);
        float speed = parseFloat(properties.getProperty("speed"), 1f);
        int daysLoop = parseInt(properties.getProperty("daysLoop"), 8);
        if (total != 24000 || speed < 0f || daysLoop <= 0) {
            return null;
        }
        String weather = properties.getProperty("weather", "clear").toLowerCase(Locale.ROOT);
        return new Layer(source == null ? root + name + ".png" : path(root, source), startFadeIn, endFadeIn,
                startFadeOut, endFadeOut, blend(properties.getProperty("blend")),
                !"false".equalsIgnoreCase(properties.getProperty("rotate", "true").trim()), speed,
                axis(properties.getProperty("axis")), weather.contains("clear"), weather.contains("rain"),
                weather.contains("thunder"), ConnectedTextures.ranges(properties.getProperty("heights", "")),
                BiomeNames189.parse(properties.getProperty("biomes")),
                parseFloat(properties.getProperty("transition"), 1f),
                ConnectedTextures.ranges(properties.getProperty("days", "")), daysLoop);
    }

    /**
     * The axis as given (x y z, each within -1..1) turned into the layer's frame: (z, y, -x), which the sky's -90
     * degree turn about y brings back to the world's x, y, z. Default: the frame's x (the world's south).
     */
    static float[] axis(String text) {
        float[] fallback = {1f, 0f, 0f};
        if (text == null) {
            return fallback;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            return fallback;
        }
        float[] values = new float[3];
        for (int index = 0; index < 3; index++) {
            values[index] = parseFloat(parts[index], Float.NaN);
            if (Float.isNaN(values[index]) || values[index] < -1f || values[index] > 1f) {
                return fallback;
            }
        }
        if (values[0] * values[0] + values[1] * values[1] + values[2] * values[2] < 1.0E-5F) {
            return fallback;
        }
        return new float[]{values[2], values[1], -values[0]};
    }

    /** {@code hh:mm} as world time of day (6:00 is 0), or -1. */
    static int time(String text) {
        if (text == null) {
            return -1;
        }
        String[] parts = text.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        int hours = parseInt(parts[0], -1);
        int minutes = parseInt(parts[1], -1);
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return -1;
        }
        return normalize((hours - 6) * 1000 + (int) (minutes / 60.0 * 1000.0));
    }

    private static int normalize(int time) {
        return Math.floorMod(time, 24000);
    }

    private static Blend blend(String text) {
        if (text == null) {
            return Blend.ADD;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "alpha" -> Blend.ALPHA;
            case "subtract" -> Blend.SUBTRACT;
            case "multiply" -> Blend.MULTIPLY;
            case "dodge" -> Blend.DODGE;
            case "burn" -> Blend.BURN;
            case "screen" -> Blend.SCREEN;
            case "overlay" -> Blend.OVERLAY;
            case "replace" -> Blend.REPLACE;
            default -> Blend.ADD;
        };
    }

    /** A source path: {@code ./} and bare names beside the file, {@code ~/} the optifine folder, else assets/minecraft. */
    private static String path(String root, String source) {
        String path = source.trim();
        if (path.startsWith("./")) {
            return root + path.substring(2);
        }
        if (path.startsWith("~/")) {
            return "assets/minecraft/optifine/" + path.substring(2);
        }
        if (!path.contains("/")) {
            return root + path;
        }
        return "assets/minecraft/" + (path.startsWith("minecraft:") ? path.substring(10) : path);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return text == null ? fallback : Float.parseFloat(text.trim());
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
