package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * OptiFine / MCPatcher custom colors: {@code color.properties} (potion liquid colors, spawn egg shell and spot
 * colors, the lily pad, water drops, sheep wool and wolf collar dye colors, text color codes and the XP bar, boss and
 * sign text, map colors, the XP orb cycle time) and the {@code colormap/} images and block colormaps: biome-indexed
 * (vanilla's temperature and rainfall grid, or a grid of biome ids by height) or one fixed color, for grass, leaves,
 * water, the sky, fog and the underwater fog (the last three averaged over a 7x7 block area and faded over about a
 * second, see {@link Fader}), plus the redstone wire palette by power and the XP orb palette. Paths are read under
 * {@code optifine/} first, then {@code mcpatcher/}.
 */
public final class CustomColors {
    private static final String[] ROOTS = {"assets/minecraft/optifine/", "assets/minecraft/mcpatcher/"};
    /** Potion.getName without its "potion." prefix, by potion id. */
    private static final String[] POTIONS = {null, "moveSpeed", "moveSlowdown", "digSpeed", "digSlowDown",
            "damageBoost", "heal", "harm", "jump", "confusion", "regeneration", "resistance", "fireResistance",
            "waterBreathing", "invisibility", "blindness", "nightVision", "hunger", "weakness", "poison", "wither",
            "healthBoost", "absorption", "saturation"};
    /** EntityList names of the entities with spawn eggs, by entity id. */
    private static final Map<String, Integer> EGG_ENTITIES = Map.ofEntries(Map.entry("Creeper", 50),
            Map.entry("Skeleton", 51), Map.entry("Spider", 52), Map.entry("Zombie", 54), Map.entry("Slime", 55),
            Map.entry("Ghast", 56), Map.entry("PigZombie", 57), Map.entry("Enderman", 58), Map.entry("CaveSpider", 59),
            Map.entry("Silverfish", 60), Map.entry("Blaze", 61), Map.entry("LavaSlime", 62), Map.entry("Bat", 65),
            Map.entry("Witch", 66), Map.entry("Endermite", 67), Map.entry("Guardian", 68), Map.entry("Pig", 90),
            Map.entry("Sheep", 91), Map.entry("Cow", 92), Map.entry("Chicken", 93), Map.entry("Squid", 94),
            Map.entry("Wolf", 95), Map.entry("MushroomCow", 96), Map.entry("Ozelot", 98),
            Map.entry("EntityHorse", 100), Map.entry("Rabbit", 101), Map.entry("Villager", 120));
    /** EnumDyeColor names by metadata (the order of sheep fleece and collar colors). */
    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    /** MapColor names OptiFine accepts after {@code map.}, by map color index (getMapColorIndex). */
    private static final Map<String, Integer> MAP_COLORS = Map.ofEntries(Map.entry("air", 0), Map.entry("grass", 1),
            Map.entry("sand", 2), Map.entry("cloth", 3), Map.entry("tnt", 4), Map.entry("ice", 5),
            Map.entry("iron", 6), Map.entry("foliage", 7), Map.entry("snow", 8), Map.entry("white", 8),
            Map.entry("clay", 9), Map.entry("dirt", 10), Map.entry("stone", 11), Map.entry("water", 12),
            Map.entry("wood", 13), Map.entry("quartz", 14), Map.entry("adobe", 15), Map.entry("orange", 15),
            Map.entry("magenta", 16), Map.entry("light_blue", 17), Map.entry("lightBlue", 17),
            Map.entry("yellow", 18), Map.entry("lime", 19), Map.entry("pink", 20), Map.entry("gray", 21),
            Map.entry("silver", 22), Map.entry("cyan", 23), Map.entry("purple", 24), Map.entry("blue", 25),
            Map.entry("brown", 26), Map.entry("green", 27), Map.entry("red", 28), Map.entry("black", 29),
            Map.entry("gold", 30), Map.entry("diamond", 31), Map.entry("lapis", 32), Map.entry("emerald", 33),
            Map.entry("podzol", 34), Map.entry("netherrack", 35));
    /** MapColor.mapColorArray's length. */
    public static final int MAP_COLOR_COUNT = 64;

    /** Declared after the tables above: the constructor reads them. */
    public static final CustomColors NONE = new CustomColors();

    /** The world's biomes for {@link Colormap#smooth}: the biome of a column and the vanilla climate of a biome. */
    public interface Biomes {
        int biome(int x, int z);

        /** BiomeGenBase.getFloatTemperature at a block (colder with height above 64). */
        float temperature(int biome, int x, int y, int z);

        float rainfall(int biome);
    }

    /** A biome colormap: vanilla's temperature/rainfall triangle, biome-id columns by height, or a fixed color. */
    public record Colormap(int format, int color, int[] pixels, int width, int height, int yOffset) {
        static final int VANILLA = 0;
        static final int GRID = 1;
        static final int FIXED = 2;

        /**
         * CustomColormap.getColorSmooth: the average color (per channel, rounded down) of the {@code 2 * radius + 1}
         * square of columns around a block, each at its own biome; a fixed color as is.
         */
        public int smooth(Biomes biomes, int x, int y, int z, int radius) {
            if (format == FIXED) {
                return color;
            }
            int red = 0;
            int green = 0;
            int blue = 0;
            int count = 0;
            for (int column = x - radius; column <= x + radius; column++) {
                for (int row = z - radius; row <= z + radius; row++) {
                    int biome = biomes.biome(column, row);
                    int sample = color(biome, biomes.temperature(biome, column, y, row), biomes.rainfall(biome), y);
                    red += sample >> 16 & 255;
                    green += sample >> 8 & 255;
                    blue += sample & 255;
                    count++;
                }
            }
            return red / count << 16 | green / count << 8 | blue / count;
        }

        /** The color for a biome at a height (CustomColormap.getColor without smoothing). */
        public int color(int biome, float temperature, float rainfall, int y) {
            return switch (format) {
                case VANILLA -> {
                    double t = Math.max(0f, Math.min(1f, temperature));
                    double r = Math.max(0f, Math.min(1f, rainfall)) * t;
                    yield pixel((int) ((1.0 - t) * (width - 1)), (int) ((1.0 - r) * (height - 1)));
                }
                case GRID -> pixel(biome, y - yOffset);
                default -> color;
            };
        }

        int pixel(int x, int y) {
            x = Math.max(0, Math.min(width - 1, x));
            y = Math.max(0, Math.min(height - 1, y));
            return pixels[y * width + x] & 0xFFFFFF;
        }
    }

    /**
     * CustomColorFader: a color that follows its target by the share of a second since the last step (all of it
     * after a second or more), and ignores changes under 0.004 in every channel. The first color is taken as is.
     */
    public static final class Fader {
        public float red;
        public float green;
        public float blue;
        private boolean started;
        private long lastMillis;

        /** Steps toward a target at a time in milliseconds; read the result from the fields. */
        public void fade(float targetRed, float targetGreen, float targetBlue, long millis) {
            if (!started) {
                started = true;
                lastMillis = millis;
                red = targetRed;
                green = targetGreen;
                blue = targetBlue;
                return;
            }
            long elapsed = millis - lastMillis;
            if (elapsed == 0L) {
                return;
            }
            lastMillis = millis;
            if (Math.abs(targetRed - red) < 0.004F && Math.abs(targetGreen - green) < 0.004F
                    && Math.abs(targetBlue - blue) < 0.004F) {
                return;
            }
            float share = (float) Math.max(0.0, Math.min(1.0, elapsed * 0.001));
            red += (targetRed - red) * share;
            green += (targetGreen - green) * share;
            blue += (targetBlue - blue) * share;
        }

        public void reset() {
            started = false;
        }
    }

    private final int[] potionColors = new int[POTIONS.length];
    private int waterPotion = -1;
    private int lilyPad = -1;
    private int waterDrop = -1;
    private final Map<Integer, Integer> eggShell = new HashMap<>();
    private final Map<Integer, Integer> eggSpots = new HashMap<>();
    /** Block colormaps by block id, each with a bit per metadata it covers. */
    private final Map<Integer, Colormap> blocks = new HashMap<>();
    private final Map<Integer, Integer> blockMetadata = new HashMap<>();
    private Colormap water;
    private Colormap sky;
    private Colormap fog;
    private Colormap underwater;
    private Colormap pine;
    private Colormap birch;
    /** colormap/redstone.png (16x1, by power) and xporb.png as plain color lists, or null. */
    private int[] redstone;
    private int[] xpOrb;
    private int xpOrbTime = -1;
    private final int[] sheep = new int[16];
    private final int[] collar = new int[16];
    /** text.code.0-31: the 16 format colors, then their shadows. */
    private final int[] textCodes = new int[32];
    private int xpBarText = -1;
    private int bossText = -1;
    private int signText = -1;
    private final int[] mapColors = new int[MAP_COLOR_COUNT];

    private CustomColors() {
        Arrays.fill(potionColors, -1);
        Arrays.fill(sheep, -1);
        Arrays.fill(collar, -1);
        Arrays.fill(textCodes, -1);
        Arrays.fill(mapColors, -1);
    }

    public boolean isEmpty() {
        return this == NONE;
    }

    /** A potion effect's liquid color (0xRRGGBB), or -1 for vanilla's. */
    public int potion(int effect) {
        return effect >= 0 && effect < potionColors.length ? potionColors[effect] : -1;
    }

    /** The water bottle's color, or -1. */
    public int waterPotion() {
        return waterPotion;
    }

    /** A spawn egg's shell (layer 0) or spot (layer 1) color for an entity id, or -1. */
    public int egg(int entity, int layer) {
        return (layer == 0 ? eggShell : eggSpots).getOrDefault(entity, -1);
    }

    public int lilyPad() {
        return lilyPad;
    }

    public int waterDrop() {
        return waterDrop;
    }

    /**
     * CustomColors.updateWaterFX: the color of a water particle spawned in a biome at a height, or -1 when the pack
     * sets neither a water colormap (or one for the water block) nor {@code drop.water}. It is the water colormap's
     * color (white without one) times {@code drop.water}.
     */
    public int waterParticle(int biome, float temperature, float rainfall, int y) {
        int flowing = block(8, 0, biome, temperature, rainfall, y);
        if (water == null && flowing < 0 && waterDrop < 0) {
            return -1;
        }
        int color = flowing >= 0 ? flowing : water != null ? water.color(biome, temperature, rainfall, y) : 0xFFFFFF;
        if (waterDrop >= 0) {
            int red = (color >> 16 & 255) * (waterDrop >> 16 & 255) / 255;
            int green = (color >> 8 & 255) * (waterDrop >> 8 & 255) / 255;
            int blue = (color & 255) * (waterDrop & 255) / 255;
            color = red << 16 | green << 8 | blue;
        }
        return color;
    }

    /** A block colormap's color for a block state in a biome, or -1 when no colormap covers it. */
    public int block(int id, int meta, int biome, float temperature, float rainfall, int y) {
        Colormap colormap = blocks.get(id);
        if (colormap == null || (blockMetadata.get(id) >> meta & 1) == 0) {
            return -1;
        }
        return colormap.color(biome, temperature, rainfall, y);
    }

    public Colormap water() {
        return water;
    }

    public Colormap sky() {
        return sky;
    }

    public Colormap fog() {
        return fog;
    }

    public Colormap underwater() {
        return underwater;
    }

    public Colormap pine() {
        return pine;
    }

    public Colormap birch() {
        return birch;
    }

    /** CustomColors.getRedstoneColor: the wire color at a power (0-15; 15 for anything but a wire), or -1. */
    public int redstone(int power) {
        return redstone == null ? -1 : redstone[Math.max(0, Math.min(redstone.length - 1, power))];
    }

    /**
     * CustomColors.getXpOrbColor for RenderXPOrb's timer (after {@link #xpOrbTimer}): the palette entry at
     * {@code round((sin(timer) + 1) * (length - 1) / 2)}, or -1 for vanilla's green-yellow pulse.
     */
    public int xpOrb(float timer) {
        if (xpOrb == null) {
            return -1;
        }
        int index = (int) Math.round((VanillaMath.sin(timer) + 1.0F) * (float) (xpOrb.length - 1) / 2.0D);
        return xpOrb[Math.max(0, Math.min(xpOrb.length - 1, index))];
    }

    /** CustomColors.getXpOrbTimer: {@code xporb.time} (ms per cycle) rescales RenderXPOrb's timer. */
    public float xpOrbTimer(float timer) {
        return xpOrbTime <= 0 ? timer : timer * (628.0F / xpOrbTime);
    }

    /** {@code sheep.<dye>}: a fleece color (0xRRGGBB) for a dye metadata, or -1 for EntitySheep's. */
    public int sheep(int dye) {
        return sheep[dye & 15];
    }

    /** {@code collar.<dye>}: a wolf collar color for a dye metadata, or -1. */
    public int collar(int dye) {
        return collar[dye & 15];
    }

    /**
     * {@code text.code.N} (FontRenderer.colorCode via getTextColor): format color N (0-15) or its shadow (16-31),
     * or -1 for vanilla's.
     */
    public int textCode(int index) {
        return index >= 0 && index < textCodes.length ? textCodes[index] : -1;
    }

    /** {@code text.xpbar}: the experience level number's color, or -1. */
    public int xpBarText() {
        return xpBarText;
    }

    /** {@code text.boss}: the boss name's color, or -1. */
    public int bossText() {
        return bossText;
    }

    /** {@code text.sign}: sign text's color, or -1. */
    public int signText() {
        return signText;
    }

    /** {@code map.<name>}: a MapColor's base color by its index (0-63), or -1 for vanilla's. */
    public int map(int index) {
        return index >= 0 && index < mapColors.length ? mapColors[index] : -1;
    }

    /** Reads the active packs' custom colors (NONE when they have none). */
    public static CustomColors load(AssetSource assets) {
        CustomColors colors = new CustomColors();
        boolean any = false;
        for (String root : ROOTS) {
            Properties properties = properties(assets, root + "color.properties");
            if (properties != null) {
                colors.readProperties(properties);
                any = true;
                break;
            }
        }
        for (String root : ROOTS) {
            String maps = root + "colormap/";
            colors.water = first(colors.water, colormap(assets, maps, "water", "watercolorX"));
            colors.sky = first(colors.sky, colormap(assets, maps, "sky0", "skycolor0"));
            colors.fog = first(colors.fog, colormap(assets, maps, "fog0", "fogcolor0"));
            colors.underwater = first(colors.underwater, colormap(assets, maps, "underwater", "underwatercolor"));
            colors.pine = first(colors.pine, colormap(assets, maps, "pine", "pinecolor"));
            colors.birch = first(colors.birch, colormap(assets, maps, "birch", "birchcolor"));
            if (colors.redstone == null) {
                colors.redstone = palette(assets, maps + "redstone.png");
                colors.redstone = colors.redstone != null ? colors.redstone : palette(assets, maps + "redstonecolor.png");
            }
            colors.xpOrb = colors.xpOrb != null ? colors.xpOrb : palette(assets, maps + "xporb.png");
            for (String folder : new String[]{"custom/", "blocks/"}) {
                List<String> paths;
                try {
                    paths = assets.list(maps + folder);
                } catch (IOException failure) {
                    continue;
                }
                for (String path : paths.stream().sorted().toList()) {
                    if (path.endsWith(".properties")) {
                        any |= colors.readBlockColormap(assets, path);
                    }
                }
            }
        }
        any |= colors.water != null || colors.sky != null || colors.fog != null || colors.underwater != null
                || colors.pine != null || colors.birch != null || colors.redstone != null || colors.xpOrb != null;
        return any ? colors : NONE;
    }

    private static Colormap first(Colormap existing, Colormap candidate) {
        return existing != null ? existing : candidate;
    }

    private void readProperties(Properties properties) {
        waterDrop = color(properties.getProperty("particle.water", properties.getProperty("drop.water")));
        lilyPad = color(properties.getProperty("lilypad"));
        waterPotion = color(properties.getProperty("potion.water"));
        for (int effect = 1; effect < POTIONS.length; effect++) {
            potionColors[effect] = color(properties.getProperty("potion." + POTIONS[effect]));
        }
        for (Map.Entry<String, Integer> entity : EGG_ENTITIES.entrySet()) {
            int shell = color(properties.getProperty("egg.shell." + entity.getKey()));
            int spots = color(properties.getProperty("egg.spots." + entity.getKey()));
            if (shell >= 0) {
                eggShell.put(entity.getValue(), shell);
            }
            if (spots >= 0) {
                eggSpots.put(entity.getValue(), spots);
            }
        }
        xpOrbTime = parseInt(properties.getProperty("xporb.time"), -1);
        xpBarText = color(properties.getProperty("text.xpbar"));
        bossText = color(properties.getProperty("text.boss"));
        signText = color(properties.getProperty("text.sign"));
        for (String key : properties.stringPropertyNames()) {
            int value = color(properties.getProperty(key));
            if (value < 0) {
                continue;
            }
            if (key.startsWith("sheep.") || key.startsWith("collar.")) {
                String dye = key.substring(key.indexOf('.') + 1);
                int meta = Arrays.asList(DYES).indexOf(dye.equals("lightBlue") ? "light_blue" : dye);
                if (meta >= 0) {
                    (key.startsWith("sheep.") ? sheep : collar)[meta] = value;
                }
            } else if (key.startsWith("text.code.")) {
                int code = parseInt(key.substring("text.code.".length()), -1);
                if (code >= 0 && code < textCodes.length) {
                    textCodes[code] = value;
                }
            } else if (key.startsWith("map.")) {
                Integer index = MAP_COLORS.get(key.substring("map.".length()));
                if (index != null) {
                    mapColors[index] = value;
                }
            }
        }
    }

    /** An indexed palette image (any size; colors in reading order), or null. */
    private static int[] palette(AssetSource assets, String path) {
        RgbaImage image = image(assets, path);
        if (image == null || image.width() <= 0 || image.height() <= 0) {
            return null;
        }
        byte[] bytes = image.copyPixels();
        int[] colors = new int[image.width() * image.height()];
        for (int index = 0; index < colors.length; index++) {
            colors[index] = (bytes[index * 4] & 255) << 16 | (bytes[index * 4 + 1] & 255) << 8 | bytes[index * 4 + 2] & 255;
        }
        return colors;
    }

    /** A block colormap's properties: its blocks (or the file's name), its format and color or image. */
    private boolean readBlockColormap(AssetSource assets, String path) {
        Properties properties = properties(assets, path);
        if (properties == null) {
            return false;
        }
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        String name = path.substring(directory.length(), path.length() - ".properties".length());
        String blocksText = properties.getProperty("blocks", name);
        String source = properties.getProperty("source", name + ".png");
        source = source.startsWith("./") ? directory + source.substring(2)
                : source.contains("/") ? "assets/minecraft/" + source : directory + source;
        if (!source.endsWith(".png")) {
            source += ".png";
        }
        Colormap colormap = colormap(assets, properties, source);
        if (colormap == null) {
            return false;
        }
        boolean any = false;
        for (String word : blocksText.trim().split("\\s+")) {
            String[] parts = word.split(":");
            int nameIndex = parts.length > 1 && parts[0].equals("minecraft") ? 1 : 0;
            int id = blockId(parts[nameIndex]);
            if (id < 0) {
                continue;
            }
            int metadata = 0;
            for (int index = nameIndex + 1; index < parts.length; index++) {
                for (int[] range : ConnectedTextures.ranges(parts[index])) {
                    for (int meta = range[0]; meta <= Math.min(15, range[1]); meta++) {
                        metadata |= 1 << meta;
                    }
                }
            }
            blocks.put(id, colormap);
            blockMetadata.put(id, metadata == 0 ? 0xFFFF : metadata);
            any = true;
        }
        return any;
    }

    /** A plain colormap image ({@code <name>.png}, with optional {@code <name>.properties}; a fixed color needs no image), or null. */
    private static Colormap colormap(AssetSource assets, String directory, String... names) {
        for (String name : names) {
            String image = directory + name + ".png";
            Properties properties = properties(assets, directory + name + ".properties");
            Colormap colormap = colormap(assets, properties == null ? new Properties() : properties, image);
            if (colormap != null) {
                return colormap;
            }
        }
        return null;
    }

    /**
     * CustomColormap: {@code format} vanilla (default) or grid reads the image; fixed takes {@code color} (white by
     * default). Without a color, image formats default theirs to the image's (127, 127) or plains' grid cell.
     */
    private static Colormap colormap(AssetSource assets, Properties properties, String source) {
        String format = properties.getProperty("format", "vanilla").trim().toLowerCase(Locale.ROOT);
        int color = color(properties.getProperty("color"));
        if (format.equals("fixed")) {
            return new Colormap(Colormap.FIXED, color < 0 ? 0xFFFFFF : color, null, 0, 0, 0);
        }
        RgbaImage image = image(assets, source);
        if (image == null) {
            return null;
        }
        byte[] bytes = image.copyPixels();
        int[] pixels = new int[image.width() * image.height()];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = (bytes[index * 4] & 255) << 16 | (bytes[index * 4 + 1] & 255) << 8 | bytes[index * 4 + 2] & 255;
        }
        int kind = format.equals("grid") ? Colormap.GRID : Colormap.VANILLA;
        int yOffset = parseInt(properties.getProperty("yOffset"), 0);
        Colormap colormap = new Colormap(kind, color, pixels, image.width(), image.height(), yOffset);
        if (color < 0) {
            color = kind == Colormap.VANILLA ? colormap.pixel(127, 127) : colormap.pixel(1, 64 - yOffset);
            colormap = new Colormap(kind, color, pixels, image.width(), image.height(), yOffset);
        }
        return colormap;
    }

    private static RgbaImage image(AssetSource assets, String path) {
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static int blockId(String name) {
        if (name.matches("\\d+")) {
            return Integer.parseInt(name);
        }
        for (int id = 0; id < Blocks189.COUNT; id++) {
            if (name.equals(Blocks189.name(id))) {
                return id;
            }
        }
        return -1;
    }

    /** A hex color (rrggbb, optionally with # or 0x), or -1. */
    static int color(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.startsWith("0x")) {
            value = value.substring(2);
        }
        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException invalid) {
            return -1;
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
