package alkaline.renderer.model;

import alkaline.renderer.asset.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * OptiFine's custom item textures (CIT, from MCPatcher): {@code .properties} files under {@code optifine/cit/} or
 * {@code mcpatcher/cit/} in the active resource packs that give matching items another sprite, item model or armor
 * texture. A rule matches by item, damage (or damage percentage, with an optional mask), stack size, enchantments
 * and NBT values (exact, {@code pattern:}, {@code ipattern:}, {@code regex:}, {@code iregex:}); among matching rules
 * the highest {@code weight} wins, then the first by path. Enchantment glint rules ({@code type=enchantment})
 * replace vanilla's glint per enchantment on the stack (see {@link #enchantments}); {@code useGlint=false} in
 * {@code cit.properties} turns vanilla's glint off where no rule applies.
 */
public final class CustomItems {
    public static final CustomItems NONE = new CustomItems(List.of(), true);
    private static final String[] ROOTS = {"assets/minecraft/optifine/cit/", "assets/minecraft/mcpatcher/cit/"};

    public enum Type { ITEM, ARMOR, ENCHANTMENT }

    /** What a rule is matched against, without the game's item classes. */
    public interface Stack {
        int id();

        int damage();

        int count();

        /** The item's maximum damage (0 for items without durability). */
        int maxDamage();

        /** Enchantment level by id, or 0 when absent. */
        int enchantment(int id);

        /** Every enchantment id on the item. */
        int[] enchantments();

        /**
         * Whether the NBT value at a path passes a test, in its string form; list elements go by index, and
         * {@code *} tries every element. False when the path is absent.
         */
        boolean nbt(String[] path, java.util.function.Predicate<String> test);
    }

    /**
     * One properties file.
     *
     * @param textures replacement texture (full asset path) by the original texture's base name, and "" for the
     *                 default
     * @param model    a replacement item model (full asset path), or null
     * @param glint    an enchantment rule's blend, speed, rotation and layer; null for other types
     */
    public record Rule(int index, String path, Type type, int weight, int[] items, List<int[]> damage,
                       boolean damagePercent, int damageMask, List<int[]> stackSize, int[] enchantmentIds,
                       List<int[]> enchantmentLevels, List<NbtTest> nbt, Map<String, String> textures, String model,
                       Glint glint) {
        /** The replacement for an original texture path (for example {@code items/bow_pulling_0}), or null. */
        public String texture(String original, boolean primary) {
            String base = original.substring(original.lastIndexOf('/') + 1);
            String specific = textures.get(base);
            if (specific != null) {
                return specific;
            }
            return primary ? textures.get("") : null;
        }

        boolean matches(Stack stack) {
            if (!damage.isEmpty()) {
                int value = stack.damage() & damageMask;
                if (damagePercent) {
                    if (stack.maxDamage() <= 0) {
                        return false;
                    }
                    value = value * 100 / stack.maxDamage();
                }
                if (!inRanges(damage, value)) {
                    return false;
                }
            }
            if (!stackSize.isEmpty() && !inRanges(stackSize, stack.count())) {
                return false;
            }
            if (enchantmentIds.length > 0 || !enchantmentLevels.isEmpty()) {
                boolean found = false;
                int[] present = enchantmentIds.length > 0 ? enchantmentIds : stack.enchantments();
                for (int id : present) {
                    int level = stack.enchantment(id);
                    if (level > 0 && (enchantmentLevels.isEmpty() || inRanges(enchantmentLevels, level))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            for (NbtTest test : nbt) {
                if (!stack.nbt(test.path(), test::matches)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * CustomItemProperties for {@code type=enchantment}: the blend (Blender.parseBlend's names, {@code add} by
     * default), scroll speed, rotation in degrees, and the layer rules sort by.
     */
    public record Glint(String blend, float speed, float rotation, int layer) { }

    /** {@code nbt.<path>=<value>}: the value at the path compared exactly or by pattern. */
    public record NbtTest(String[] path, String value, Pattern pattern) {
        public boolean matches(String actual) {
            if (actual == null) {
                return false;
            }
            return pattern != null ? pattern.matcher(actual).matches() : value.equals(actual);
        }
    }

    private final List<Rule> rules;
    private final Map<Integer, List<Rule>> itemRules = new HashMap<>();
    private final Map<Integer, List<Rule>> armorRules = new HashMap<>();
    private final Map<Integer, List<Rule>> enchantmentRules = new HashMap<>();
    private final boolean useGlint;

    private CustomItems(List<Rule> rules, boolean useGlint) {
        this.rules = List.copyOf(rules);
        this.useGlint = useGlint;
        for (Rule rule : this.rules) {
            Map<Integer, List<Rule>> index = switch (rule.type()) {
                case ITEM -> itemRules;
                case ARMOR -> armorRules;
                case ENCHANTMENT -> enchantmentRules;
            };
            // CustomItems.addToEnchantmentList: enchantment rules are listed under each enchantment id they name.
            for (int item : rule.type() == Type.ENCHANTMENT ? rule.enchantmentIds() : rule.items()) {
                index.computeIfAbsent(item, key -> new ArrayList<>()).add(rule);
            }
        }
        Comparator<Rule> order = Comparator.comparingInt(Rule::weight).reversed().thenComparing(Rule::path);
        itemRules.values().forEach(list -> list.sort(order));
        armorRules.values().forEach(list -> list.sort(order));
        // CustomItems.getPropertiesComparator: enchantment rules go by layer first.
        Comparator<Rule> layered = Comparator.comparingInt((Rule rule) -> rule.glint().layer()).thenComparing(order);
        enchantmentRules.values().forEach(list -> list.sort(layered));
    }

    public List<Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return itemRules.isEmpty() && armorRules.isEmpty();
    }

    /** Whether any {@code type=enchantment} rule is loaded. */
    public boolean hasEnchantmentRules() {
        return !enchantmentRules.isEmpty();
    }

    /** {@code useGlint} in {@code cit.properties}: whether vanilla's glint draws where no enchantment rule does. */
    public boolean useGlint() {
        return useGlint;
    }

    /**
     * The enchantment rules drawn over a glinted stack, in order (CustomItems.renderCustomEffect): for each
     * enchantment on it, the first rule listed under that id, if it matches. Empty means vanilla's glint draws
     * instead. As in OptiFine, a rule's {@code items} are not checked, and a later rule under the same id is not
     * tried when the first does not match.
     */
    public List<Rule> enchantments(Stack stack) {
        if (stack == null || enchantmentRules.isEmpty()) {
            return List.of();
        }
        List<Rule> drawn = new ArrayList<>();
        java.util.Set<Integer> tried = new java.util.HashSet<>();
        for (int id : stack.enchantments()) {
            List<Rule> candidates = enchantmentRules.get(id);
            if (candidates != null && tried.add(id)) {
                Rule rule = candidates.get(0);
                if (rule.matches(stack)) {
                    drawn.add(rule);
                }
            }
        }
        return drawn;
    }

    /** The item rule for a stack, or null. */
    public Rule item(Stack stack) {
        return first(itemRules, stack);
    }

    /** The armor rule for a worn stack, or null. */
    public Rule armor(Stack stack) {
        return first(armorRules, stack);
    }

    public Rule rule(int index) {
        return rules.get(index);
    }

    private static Rule first(Map<Integer, List<Rule>> index, Stack stack) {
        if (stack == null) {
            return null;
        }
        List<Rule> candidates = index.get(stack.id());
        if (candidates == null) {
            return null;
        }
        for (Rule rule : candidates) {
            if (rule.matches(stack)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Reads every CIT properties file in the assets.
     *
     * @param itemIds item or block id by registry name (with or without {@code minecraft:}), or -1
     */
    public static CustomItems load(AssetSource assets, ToIntFunction<String> itemIds) {
        List<Rule> rules = new ArrayList<>();
        boolean useGlint = true;
        for (String file : new String[]{"assets/minecraft/mcpatcher/cit.properties",
                "assets/minecraft/optifine/cit.properties"}) {
            try (InputStream input = assets.open(file)) {
                if (input != null) {
                    Properties properties = new Properties();
                    properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                    useGlint = !"false".equalsIgnoreCase(properties.getProperty("useGlint", "true").trim());
                }
            } catch (IOException | RuntimeException failure) {
                System.err.println("Custom item " + file + " skipped: " + failure.getMessage());
            }
        }
        for (String root : ROOTS) {
            List<String> paths;
            try {
                paths = assets.list(root);
            } catch (IOException failure) {
                continue;
            }
            for (String path : paths.stream().sorted().toList()) {
                if (!path.endsWith(".properties")) {
                    continue;
                }
                try (InputStream input = assets.open(path)) {
                    if (input == null) {
                        continue;
                    }
                    Properties properties = new Properties();
                    properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                    Rule rule = parse(rules.size(), path, properties, itemIds);
                    if (rule != null) {
                        rules.add(rule);
                    }
                } catch (IOException | RuntimeException failure) {
                    System.err.println("Custom item " + path + " skipped: " + failure.getMessage());
                }
            }
        }
        return rules.isEmpty() && useGlint ? NONE : new CustomItems(rules, useGlint);
    }

    static Rule parse(int index, String path, Properties properties, ToIntFunction<String> itemIds) {
        Type type = switch (properties.getProperty("type", "item").trim().toLowerCase(Locale.ROOT)) {
            case "item" -> Type.ITEM;
            case "armor" -> Type.ARMOR;
            case "enchantment" -> Type.ENCHANTMENT;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        String fileName = path.substring(directory.length(), path.length() - ".properties".length());
        List<Integer> items = new ArrayList<>();
        for (String item : words(properties.getProperty("items", properties.getProperty("matchItems", "")))) {
            int id = item.matches("\\d+") ? Integer.parseInt(item) : itemIds.applyAsInt(item);
            if (id >= 0) {
                items.add(id);
            }
        }
        if (items.isEmpty() && type != Type.ENCHANTMENT) {
            return null;
        }
        String damageText = properties.getProperty("damage", "").trim();
        boolean percent = damageText.contains("%");
        List<int[]> damage = ranges(damageText.replace("%", ""));
        int mask = parseInt(properties.getProperty("damageMask"), -1);
        List<int[]> stackSize = ranges(properties.getProperty("stackSize", ""));
        List<Integer> enchantments = new ArrayList<>();
        for (String enchantment : words(properties.getProperty("enchantmentIDs", properties.getProperty("enchantments", "")))) {
            int id = enchantment.matches("\\d+") ? Integer.parseInt(enchantment) : enchantmentId(enchantment);
            if (id >= 0) {
                enchantments.add(id);
            }
        }
        List<int[]> levels = ranges(properties.getProperty("enchantmentLevels", ""));
        List<NbtTest> nbt = new ArrayList<>();
        Map<String, String> textures = new HashMap<>();
        String model = null;
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key).trim();
            if (key.startsWith("nbt.")) {
                nbt.add(nbtTest(key.substring(4).split("\\."), value));
            } else if (key.equals("texture") || key.equals("tile")) {
                textures.put("", resolve(directory, value, ".png"));
            } else if (key.startsWith("texture.")) {
                textures.put(key.substring(8), resolve(directory, value, ".png"));
            } else if (key.equals("model")) {
                model = resolve(directory, value, ".json");
            }
        }
        if (textures.isEmpty() && model == null) {
            // Without a texture the file names its own: sword.properties draws sword.png beside it.
            textures.put("", directory + fileName + ".png");
        }
        Glint glint = null;
        if (type == Type.ENCHANTMENT) {
            if (enchantments.isEmpty()) {
                return null; // CustomItemProperties.isValid: "No enchantmentIDs specified"
            }
            glint = new Glint(properties.getProperty("blend", "add").trim().toLowerCase(Locale.ROOT),
                    parseFloat(properties.getProperty("speed"), 0f), parseFloat(properties.getProperty("rotation"), 0f),
                    parseInt(properties.getProperty("layer"), 0));
        }
        return new Rule(index, path, type, parseInt(properties.getProperty("weight"), 0),
                items.stream().mapToInt(Integer::intValue).toArray(), damage, percent, mask, stackSize,
                enchantments.stream().mapToInt(Integer::intValue).toArray(), levels, List.copyOf(nbt),
                Map.copyOf(textures), model, glint);
    }

    /**
     * A texture or model path as OptiFine resolves it: {@code ~/} is the optifine folder, a bare name or {@code ./}
     * is beside the properties file, anything else is under {@code assets/minecraft/}.
     */
    static String resolve(String directory, String value, String extension) {
        String path = value.trim();
        if (path.startsWith("minecraft:")) {
            path = path.substring(10);
        }
        if (path.endsWith(extension)) {
            path = path.substring(0, path.length() - extension.length());
        }
        String resolved;
        if (path.startsWith("~/")) {
            resolved = "assets/minecraft/optifine/" + path.substring(2);
        } else if (path.startsWith("./")) {
            resolved = directory + path.substring(2);
        } else if (!path.contains("/")) {
            resolved = directory + path;
        } else {
            resolved = "assets/minecraft/" + path;
        }
        return resolved + extension;
    }

    public static NbtTest nbtTest(String[] path, String value) {
        Pattern pattern = null;
        if (value.startsWith("pattern:")) {
            pattern = Pattern.compile(glob(value.substring(8)), Pattern.DOTALL);
        } else if (value.startsWith("ipattern:")) {
            pattern = Pattern.compile(glob(value.substring(9)), Pattern.DOTALL | Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE);
        } else if (value.startsWith("regex:")) {
            pattern = Pattern.compile(value.substring(6), Pattern.DOTALL);
        } else if (value.startsWith("iregex:")) {
            pattern = Pattern.compile(value.substring(7), Pattern.DOTALL | Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE);
        }
        return new NbtTest(path, value, pattern);
    }

    /** OptiFine's wildcards: {@code *} any run, {@code ?} one character; everything else literal. */
    private static String glob(String text) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    /** Space-separated values, {@code a-b}, {@code a-} (and up) or single numbers. */
    static List<int[]> ranges(String text) {
        List<int[]> ranges = new ArrayList<>();
        for (String word : words(text)) {
            int dash = word.indexOf('-', 1);
            if (dash < 0) {
                int value = Integer.parseInt(word);
                ranges.add(new int[]{value, value});
            } else {
                int low = Integer.parseInt(word.substring(0, dash));
                String high = word.substring(dash + 1);
                ranges.add(new int[]{low, high.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(high)});
            }
        }
        return ranges;
    }

    private static boolean inRanges(List<int[]> ranges, int value) {
        for (int[] range : ranges) {
            if (value >= range[0] && value <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.trim().split("[\\s,]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.decode(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static float parseFloat(String text, float fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** 1.8.9 enchantment ids by resource name. */
    private static int enchantmentId(String name) {
        String key = name.startsWith("minecraft:") ? name.substring(10) : name;
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "protection" -> 0;
            case "fire_protection" -> 1;
            case "feather_falling" -> 2;
            case "blast_protection" -> 3;
            case "projectile_protection" -> 4;
            case "respiration" -> 5;
            case "aqua_affinity" -> 6;
            case "thorns" -> 7;
            case "depth_strider" -> 8;
            case "sharpness" -> 16;
            case "smite" -> 17;
            case "bane_of_arthropods" -> 18;
            case "knockback" -> 19;
            case "fire_aspect" -> 20;
            case "looting" -> 21;
            case "efficiency" -> 32;
            case "silk_touch" -> 33;
            case "unbreaking" -> 34;
            case "fortune" -> 35;
            case "power" -> 48;
            case "punch" -> 49;
            case "flame" -> 50;
            case "infinity" -> 51;
            case "luck_of_the_sea" -> 61;
            case "lure" -> 62;
            default -> -1;
        };
    }
}
