package alkaline.renderer.texture;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.model.CustomItems;
import alkaline.renderer.world.vanilla.BiomeNames189;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * OptiFine's random entities (RandomEntities, RandomEntityProperties, RandomEntityRule): an entity texture
 * {@code textures/entity/<path>.png} with numbered copies under {@code optifine/random/entity/<path>N.png} (or
 * MCPatcher's {@code mcpatcher/mob/<path>N.png}) is drawn as one of them, picked by the entity's random id. A
 * {@code <path>.properties} beside the copies instead lists rules, first match wins: {@code textures.N} (or
 * {@code skins.N}) with {@code weights.N}, limited by spawn biome and height, health, custom name, villager
 * profession, wolf collar color, baby, moon phase, time of day and weather. Layer textures ending in a dependent
 * suffix ({@code _eyes}, {@code _armor}, ...) fall back to their parent's properties.
 *
 * <p>Textures are named by EntityView's short paths (under {@code textures/}, without {@code .png}); a picked copy
 * comes back as a full asset path. Lookups allocate nothing.
 */
public final class RandomEntities {
    public static final RandomEntities NONE = new RandomEntities(Map.of());
    private static final String[] ROOTS = {"assets/minecraft/optifine/random/", "assets/minecraft/mcpatcher/mob/"};
    /** What each root replaces: optifine/random/ mirrors textures/, mcpatcher/mob/ mirrors textures/entity/. */
    private static final String[] MIRRORED = {"assets/minecraft/textures/", "assets/minecraft/textures/entity/"};
    private static final String[] DEPENDANT_SUFFIXES = {"_armor", "_eyes", "_exploding", "_shooting", "_fur",
            "_invulnerable", "_angry", "_tame", "_collar"};
    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    public static final int CLEAR = 0;
    public static final int RAIN = 1;
    public static final int THUNDER = 2;

    /** What the rules ask about the entity being drawn. */
    public interface Subject {
        /** RandomEntity.getId: the UUID's low bits, masked to a non-negative int. */
        int randomId();

        /** The biome id where it spawned. */
        int spawnBiome();

        /** The block y where it spawned. */
        int spawnY();

        /** Whole health and maximum health (0 for non-living entities). */
        int health();

        int maxHealth();

        /** The custom name, or null. */
        String name();

        /** A villager's profession (0-5), or -1 for anything else. */
        int profession();

        /** A tamed wolf's collar dye metadata, -1 for an untamed wolf, {@link Integer#MIN_VALUE} for others. */
        int collar();

        /** 1 for a baby, 0 for an adult, -1 when the entity is not living. */
        int baby();
    }

    /**
     * One {@code textures.N} rule. Null or empty conditions match anything.
     *
     * @param textures    the picks: a copy's asset path, or null for the base (index 1)
     * @param sumWeights  running weight totals, or null to pick by {@code id % textures.length}
     * @param professions per entry: the profession, then the careers it allows (none: any)
     * @param collars     a bit per collar dye, or 0 for any
     * @param baby        1 or 0 to require it, -1 for any
     * @param weather     a bit per weather ({@link #CLEAR}, {@link #RAIN}, {@link #THUNDER}), or 0 for any
     */
    record Rule(String[] textures, int[] sumWeights, int sumAllWeights, long[] biomes, List<int[]> heights,
                List<int[]> health, boolean healthPercent, CustomItems.NbtTest name, boolean nameNegated,
                int[][] professions, int collars, int baby, List<int[]> moonPhases, List<int[]> dayTimes,
                int weather) {
        boolean matches(Subject subject, int moonPhase, long worldTime, int currentWeather) {
            if (biomes != null && !BiomeNames189.contains(biomes, subject.spawnBiome())) {
                return false;
            }
            if (heights != null && !inRanges(heights, subject.spawnY())) {
                return false;
            }
            if (health != null) {
                int value = subject.health();
                if (healthPercent && subject.maxHealth() > 0) {
                    value = (int) (value * 100 / (double) subject.maxHealth());
                }
                if (!inRanges(health, value)) {
                    return false;
                }
            }
            if (name != null && name.matches(subject.name()) == nameNegated) {
                return false;
            }
            if (professions != null && subject.profession() >= 0 && !profession(subject.profession())) {
                return false;
            }
            if (collars != 0 && subject.collar() != Integer.MIN_VALUE
                    && (subject.collar() < 0 || (collars >> subject.collar() & 1) == 0)) {
                return false;
            }
            if (baby >= 0 && subject.baby() >= 0 && subject.baby() != baby) {
                return false;
            }
            if (moonPhases != null && !inRanges(moonPhases, moonPhase)) {
                return false;
            }
            // Later OptiFine reads the time of day; this version's raw world time only matches on day one.
            if (dayTimes != null && !inRanges(dayTimes, (int) Math.floorMod(worldTime, 24000L))) {
                return false;
            }
            return weather == 0 || (weather >> currentWeather & 1) != 0;
        }

        /**
         * VillagerProfession.matches. The client never learns a villager's career, which stays 0, so a rule naming
         * careers matches only career 0.
         */
        private boolean profession(int profession) {
            for (int[] entry : professions) {
                if (entry[0] != profession) {
                    continue;
                }
                if (entry.length == 1) {
                    return true;
                }
                for (int index = 1; index < entry.length; index++) {
                    if (entry[index] == 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** RandomEntityRule.getTextureLocation. */
        String pick(int randomId) {
            if (sumWeights == null) {
                return textures[randomId % textures.length];
            }
            int target = randomId % sumAllWeights;
            for (int index = 0; index < sumWeights.length; index++) {
                if (sumWeights[index] > target) {
                    return textures[index];
                }
            }
            return textures[0];
        }
    }

    /** A texture's choices: plain copies (null, the base, first) or rules. */
    record Entry(String[] variants, Rule[] rules) {
        /** The pick, with the caller's own string for the base so its cache key stays the same object. */
        String pick(String path, Subject subject, int moonPhase, long worldTime, int weather) {
            String chosen = null;
            if (rules != null) {
                for (Rule rule : rules) {
                    if (rule.matches(subject, moonPhase, worldTime, weather)) {
                        chosen = rule.pick(subject.randomId());
                        break;
                    }
                }
            } else {
                chosen = variants[subject.randomId() % variants.length];
            }
            return chosen == null ? path : chosen;
        }
    }

    private final Map<String, Entry> entries;

    private RandomEntities(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Whether a texture (short path) has random copies or rules. */
    public boolean has(String path) {
        return entries.containsKey(path);
    }

    /**
     * The texture to draw for an entity in place of {@code path} (a short path such as {@code entity/sheep/sheep}):
     * the same string, or a copy's full asset path.
     *
     * @param weather {@link #CLEAR}, {@link #RAIN} or {@link #THUNDER} (Weather.getWeather: over half strength)
     */
    public String texture(String path, Subject subject, int moonPhase, long worldTime, int weather) {
        Entry entry = entries.get(path);
        return entry == null ? path : entry.pick(path, subject, moonPhase, worldTime, weather);
    }

    /**
     * RandomEntities.initialize: every texture that has a copy or properties under a random folder, with
     * optifine/random/ tried before mcpatcher/mob/ for each.
     */
    public static RandomEntities load(AssetSource assets) {
        if (assets == null) {
            return NONE;
        }
        Set<String> bases = new LinkedHashSet<>();
        for (int root = 0; root < ROOTS.length; root++) {
            List<String> files;
            try {
                files = assets.list(ROOTS[root]);
            } catch (IOException unreadable) {
                continue;
            }
            for (String file : files.stream().sorted().toList()) {
                String stem = file.endsWith(".png") ? file.substring(0, file.length() - 4)
                        : file.endsWith(".properties") ? file.substring(0, file.length() - 11) : null;
                if (stem == null) {
                    continue;
                }
                int end = stem.length();
                while (end > 0 && Character.isDigit(stem.charAt(end - 1))) {
                    end--;
                }
                bases.add(MIRRORED[root] + stem.substring(ROOTS[root].length(), end) + ".png");
            }
        }
        Map<String, Entry> entries = new HashMap<>();
        for (String base : bases) {
            String path = shortPath(base);
            if (path == null || entries.containsKey(path) || !exists(assets, base)) {
                continue;
            }
            for (int root = 0; root < ROOTS.length; root++) {
                Entry entry = entry(assets, base, root);
                if (entry != null) {
                    entries.put(path, entry);
                    break;
                }
            }
        }
        return entries.isEmpty() ? NONE : new RandomEntities(entries);
    }

    /** {@code assets/minecraft/textures/entity/x.png} as {@code entity/x}; null outside entity and painting textures. */
    private static String shortPath(String asset) {
        String prefix = "assets/minecraft/textures/";
        if (!asset.startsWith(prefix + "entity/") && !asset.startsWith(prefix + "painting/")) {
            return null;
        }
        return asset.substring(prefix.length(), asset.length() - 4);
    }

    /** RandomEntities.makeProperties for one root: its properties (or the parent's), else its numbered copies. */
    static Entry entry(AssetSource assets, String base, int root) {
        String random = random(base, root);
        if (random == null) {
            return null;
        }
        String stem = random.substring(0, random.length() - 4);
        Properties properties = properties(assets, stem + ".properties");
        if (properties == null) {
            String parent = parent(stem);
            properties = parent == null ? null : properties(assets, parent + ".properties");
        }
        if (properties != null) {
            return new Entry(null, rules(assets, properties, stem));
        }
        List<String> variants = new ArrayList<>();
        variants.add(null);
        // getLocationsVariants: copies 2, 3, ... with up to ten missing numbers in a row.
        for (int index = 1; index < variants.size() + 10; index++) {
            String copy = stem + (index + 1) + ".png";
            if (exists(assets, copy)) {
                variants.add(copy);
            }
        }
        return variants.size() <= 1 ? null : new Entry(variants.toArray(String[]::new), null);
    }

    /** getLocationRandom: the texture's place under a random root, or null when that root does not mirror it. */
    private static String random(String base, int root) {
        return base.startsWith(MIRRORED[root]) ? ROOTS[root] + base.substring(MIRRORED[root].length()) : null;
    }

    private static String parent(String stem) {
        for (String suffix : DEPENDANT_SUFFIXES) {
            if (stem.endsWith(suffix)) {
                return stem.substring(0, stem.length() - suffix.length());
            }
        }
        return null;
    }

    /**
     * RandomEntityProperties.parseRules: the valid rules, invalid ones skipped. A properties file wins over the
     * numbered copies even when none of its rules is valid; the texture then stays as it is.
     */
    static Rule[] rules(AssetSource assets, Properties properties, String stem) {
        List<Rule> rules = new ArrayList<>();
        for (int index = 1; index <= properties.size(); index++) {
            String textures = properties.getProperty("textures." + index, properties.getProperty("skins." + index));
            if (textures == null) {
                continue;
            }
            Rule rule = rule(assets, properties, index, textures, stem);
            if (rule == null) {
                System.err.println("Random entity rule " + index + " of " + stem + ".properties skipped.");
            } else {
                rules.add(rule);
            }
        }
        return rules.toArray(Rule[]::new);
    }

    private static Rule rule(AssetSource assets, Properties properties, int index, String texturesText, String stem) {
        List<Integer> numbers = numbers(texturesText);
        if (numbers.isEmpty()) {
            return null;
        }
        String[] textures = new String[numbers.size()];
        for (int pick = 0; pick < textures.length; pick++) {
            int number = numbers.get(pick);
            if (number <= 1) {
                continue;
            }
            textures[pick] = stem + number + ".png";
            if (!exists(assets, textures[pick])) {
                return null;
            }
        }
        int[] sumWeights = null;
        int sumAll = 1;
        String weightsText = properties.getProperty("weights." + index);
        if (weightsText != null) {
            List<Integer> given = numbers(weightsText);
            int[] weights = new int[textures.length];
            int average = 0;
            for (int value : given) {
                average += value;
            }
            average = given.isEmpty() ? 0 : average / given.size();
            for (int pick = 0; pick < weights.length; pick++) {
                weights[pick] = pick < given.size() ? given.get(pick) : average;
            }
            sumWeights = new int[weights.length];
            int total = 0;
            for (int pick = 0; pick < weights.length; pick++) {
                if (weights[pick] < 0) {
                    return null;
                }
                total += weights[pick];
                sumWeights[pick] = total;
            }
            sumAll = total <= 0 ? 1 : total;
        }
        List<int[]> heights = ranges(properties.getProperty("heights." + index));
        if (heights == null) {
            heights = minMaxHeight(properties, index);
        }
        String healthText = properties.getProperty("health." + index);
        String nameText = properties.getProperty("name." + index);
        boolean negated = nameText != null && nameText.startsWith("!");
        CustomItems.NbtTest name = nameText == null ? null
                : CustomItems.nbtTest(new String[]{"name"}, negated ? nameText.substring(1) : nameText);
        int[][] professions = professions(properties.getProperty("professions." + index));
        if (professions != null && professions.length == 0) {
            return null;
        }
        int collars = 0;
        String collarText = properties.getProperty("collarColors." + index);
        if (collarText != null) {
            for (String word : collarText.trim().toLowerCase(Locale.ROOT).split("[\\s,]+")) {
                int dye = List.of(DYES).indexOf(word.equals("lightblue") ? "light_blue" : word);
                if (dye < 0) {
                    return null;
                }
                collars |= 1 << dye;
            }
        }
        String babyText = properties.getProperty("baby." + index);
        int baby = babyText == null ? -1 : babyText.trim().equalsIgnoreCase("true") ? 1
                : babyText.trim().equalsIgnoreCase("false") ? 0 : -1;
        int weather = 0;
        String weatherText = properties.getProperty("weather." + index);
        if (weatherText != null) {
            for (String word : weatherText.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                weather |= switch (word) {
                    case "clear" -> 1 << CLEAR;
                    case "rain" -> 1 << RAIN;
                    case "thunder" -> 1 << THUNDER;
                    default -> 0;
                };
            }
        }
        return new Rule(textures, sumWeights, sumAll, BiomeNames189.parse(properties.getProperty("biomes." + index)),
                heights, healthText == null ? null : ranges(healthText.replace("%", "")),
                healthText != null && healthText.contains("%"), name, negated, professions, collars, baby,
                ranges(properties.getProperty("moonPhase." + index)), ranges(properties.getProperty("dayTime." + index)),
                weather);
    }

    /** parseMinMaxHeight: minHeight (default 0) to maxHeight (default 256), or null when neither is given. */
    private static List<int[]> minMaxHeight(Properties properties, int index) {
        String min = properties.getProperty("minHeight." + index);
        String max = properties.getProperty("maxHeight." + index);
        if (min == null && max == null) {
            return null;
        }
        int low = min == null ? 0 : parseInt(min, -1);
        int high = max == null ? 256 : parseInt(max, -1);
        return low < 0 || high < 0 ? null : List.of(new int[]{low, high});
    }

    /**
     * ConnectedParser.parseProfessions: names or ids ({@code farmer librarian priest blacksmith butcher nitwit}),
     * each optionally with careers after a colon; an empty array for an invalid entry, null for none.
     */
    static int[][] professions(String text) {
        if (text == null) {
            return null;
        }
        List<String> names = List.of("farmer", "librarian", "priest", "blacksmith", "butcher", "nitwit");
        List<int[]> professions = new ArrayList<>();
        for (String word : text.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            String[] parts = word.split(":");
            if (word.isEmpty() || parts.length > 2) {
                return new int[0][];
            }
            int profession = parseInt(parts[0], -1);
            profession = profession >= 0 ? profession : names.indexOf(parts[0]);
            if (profession < 0) {
                return new int[0][];
            }
            List<Integer> entry = new ArrayList<>(List.of(profession));
            if (parts.length > 1) {
                for (String career : parts[1].split(",")) {
                    int id = career(profession, career);
                    if (id < 0) {
                        return new int[0][];
                    }
                    entry.add(id);
                }
            }
            professions.add(entry.stream().mapToInt(Integer::intValue).toArray());
        }
        return professions.isEmpty() ? null : professions.toArray(int[][]::new);
    }

    /** ConnectedParser.parseCareerId. */
    private static int career(int profession, String text) {
        int id = parseInt(text, -1);
        if (id >= 0) {
            return id;
        }
        List<String> careers = switch (profession) {
            case 0 -> List.of("farmer", "fisherman", "shepherd", "fletcher");
            case 1 -> List.of("librarian", "cartographer");
            case 2 -> List.of("cleric");
            case 3 -> List.of("armor", "weapon", "tool");
            case 4 -> List.of("butcher", "leather");
            case 5 -> List.of("nitwit");
            default -> List.of();
        };
        int index = careers.indexOf(text);
        return index < 0 ? -1 : index + 1;
    }

    /** ConnectedParser.parseIntList: numbers and {@code a-b} ranges, separated by spaces or commas. */
    static List<Integer> numbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        List<int[]> ranges = ranges(text);
        if (ranges != null) {
            for (int[] range : ranges) {
                for (int value = range[0]; value <= Math.min(range[1], range[0] + 1024); value++) {
                    numbers.add(value);
                }
            }
        }
        return numbers;
    }

    /** RangeListInt: {@code a}, {@code a-b} (and negative numbers), separated by spaces or commas; null for none. */
    static List<int[]> ranges(String text) {
        if (text == null) {
            return null;
        }
        List<int[]> ranges = new ArrayList<>();
        for (String word : text.trim().split("[\\s,]+")) {
            if (word.isEmpty()) {
                continue;
            }
            int dash = word.indexOf('-', 1);
            int low = parseInt(dash < 0 ? word : word.substring(0, dash), Integer.MIN_VALUE);
            int high = dash < 0 ? low : parseInt(word.substring(dash + 1), Integer.MIN_VALUE);
            if (low != Integer.MIN_VALUE && high != Integer.MIN_VALUE) {
                ranges.add(new int[]{low, high});
            }
        }
        return ranges.isEmpty() ? null : ranges;
    }

    private static boolean inRanges(List<int[]> ranges, int value) {
        for (int[] range : ranges) {
            if (value >= range[0] && value <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static boolean exists(AssetSource assets, String path) {
        try (InputStream input = assets.open(path)) {
            return input != null;
        } catch (IOException | RuntimeException unreadable) {
            return false;
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
