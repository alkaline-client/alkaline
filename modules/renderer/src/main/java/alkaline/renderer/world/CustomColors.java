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
 * colors, the lily pad, water drops) and the {@code colormap/} images and block colormaps: biome-indexed (vanilla's
 * temperature and rainfall grid, or a grid of biome ids by height) or one fixed color, for grass, leaves, water,
 * the sky, fog and the underwater fog. Paths are read under {@code optifine/} first, then {@code mcpatcher/}.
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

    /** Declared after the tables above: the constructor reads them. */
    public static final CustomColors NONE = new CustomColors();

    /** A biome colormap: vanilla's temperature/rainfall triangle, biome-id columns by height, or a fixed color. */
    public record Colormap(int format, int color, int[] pixels, int width, int height, int yOffset) {
        static final int VANILLA = 0;
        static final int GRID = 1;
        static final int FIXED = 2;

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

    private CustomColors() {
        Arrays.fill(potionColors, -1);
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
                || colors.pine != null || colors.birch != null;
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
