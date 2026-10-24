package alkaline.renderer.world.vanilla;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minecraft 1.8.9's biome names by id (BiomeGenBase.setBiomeName, with the mutated variants' names), for the
 * resource-pack conditions that name biomes. OptiFine's ConnectedParser.findBiome compares names lower-cased with
 * their spaces removed, and reads {@code nether} as Hell.
 */
public final class BiomeNames189 {
    public static final int COUNT = 256;
    private static final Map<String, Integer> IDS = new HashMap<>();

    static {
        String[] names = {"Ocean", "Plains", "Desert", "Extreme Hills", "Forest", "Taiga", "Swampland", "River", "Hell",
                "The End", "FrozenOcean", "FrozenRiver", "Ice Plains", "Ice Mountains", "MushroomIsland",
                "MushroomIslandShore", "Beach", "DesertHills", "ForestHills", "TaigaHills", "Extreme Hills Edge",
                "Jungle", "JungleHills", "JungleEdge", "Deep Ocean", "Stone Beach", "Cold Beach", "Birch Forest",
                "Birch Forest Hills", "Roofed Forest", "Cold Taiga", "Cold Taiga Hills", "Mega Taiga",
                "Mega Taiga Hills", "Extreme Hills+", "Savanna", "Savanna Plateau", "Mesa", "Mesa Plateau F",
                "Mesa Plateau"};
        for (int id = 0; id < names.length; id++) {
            put(names[id], id);
        }
        put("Sunflower Plains", 129);
        put("Desert M", 130);
        put("Extreme Hills M", 131);
        put("Flower Forest", 132);
        put("Taiga M", 133);
        put("Swampland M", 134);
        put("Ice Plains Spikes", 140);
        put("Jungle M", 149);
        put("JungleEdge M", 151);
        put("Birch Forest M", 155);
        put("Birch Forest Hills M", 156);
        put("Roofed Forest M", 157);
        put("Cold Taiga M", 158);
        put("Mega Spruce Taiga", 160);
        put("Redwood Taiga Hills M", 161);
        put("Extreme Hills+ M", 162);
        put("Savanna M", 163);
        put("Savanna Plateau M", 164);
        put("Mesa (Bryce)", 165);
        put("Mesa Plateau F M", 166);
        put("Mesa Plateau M", 167);
        IDS.put("nether", 8);
    }

    private BiomeNames189() {
    }

    private static void put(String name, int id) {
        IDS.put(name.replace(" ", "").toLowerCase(Locale.ROOT), id);
    }

    /** The biome a pack names (a biome name, or its id), or -1. */
    public static int id(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        Integer id = IDS.get(key);
        if (id != null) {
            return id;
        }
        if (key.matches("\\d{1,3}")) {
            int number = Integer.parseInt(key);
            return number < COUNT ? number : -1;
        }
        return -1;
    }

    /**
     * ConnectedParser.parseBiomes: space-separated names, or with a leading {@code !} every biome but those; unknown
     * names are skipped. Returns a bit per biome id (four longs), or null when the text is null.
     */
    public static long[] parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        boolean invert = value.startsWith("!");
        if (invert) {
            value = value.substring(1);
        }
        long[] bits = new long[COUNT / 64];
        for (String word : value.trim().split("\\s+")) {
            int id = word.isEmpty() ? -1 : id(word);
            if (id >= 0) {
                bits[id >> 6] |= 1L << id;
            }
        }
        if (invert) {
            // OptiFine inverts against the registered biomes only.
            long[] registered = new long[COUNT / 64];
            for (int id : IDS.values()) {
                registered[id >> 6] |= 1L << id;
            }
            for (int word = 0; word < bits.length; word++) {
                bits[word] = registered[word] & ~bits[word];
            }
        }
        return bits;
    }

    /** Whether a biome is in a {@link #parse} set (null: any biome). */
    public static boolean contains(long[] biomes, int biome) {
        return biomes == null || biome >= 0 && biome < COUNT && (biomes[biome >> 6] >> biome & 1) != 0;
    }
}
