package alkaline.renderer.model;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemsTest {
    private static final String DIR = "assets/minecraft/mcpatcher/cit/swords/";

    /** A diamond sword (276) with a name, damage and enchantments. */
    private record Sword(String name, int damage, int count, Map<Integer, Integer> levels)
            implements CustomItems.Stack {
        @Override public int id() { return 276; }
        @Override public int maxDamage() { return 1561; }
        @Override public int enchantment(int id) { return levels.getOrDefault(id, 0); }
        @Override public int[] enchantments() { return levels.keySet().stream().mapToInt(Integer::intValue).toArray(); }

        @Override
        public boolean nbt(String[] path, Predicate<String> test) {
            return name != null && path.length == 2 && path[0].equals("display") && path[1].equals("Name")
                    && test.test(name);
        }
    }

    private static CustomItems.Rule rule(String path, String text) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        return CustomItems.parse(0, path, properties, name -> name.endsWith("diamond_sword") ? 276 : -1);
    }

    @Test
    void namesItemsAndDefaultsTheTextureToTheFileName() throws IOException {
        CustomItems.Rule rule = rule(DIR + "fire.properties", "items=minecraft:diamond_sword 267\n");
        assertEquals(List.of(276, 267), java.util.Arrays.stream(rule.items()).boxed().toList());
        assertEquals(DIR + "fire.png", rule.texture("items/diamond_sword", true));
        assertNull(rule.texture("items/diamond_sword", false));
    }

    @Test
    void resolvesTexturesAsOptiFineDoes() {
        assertEquals(DIR + "a.png", CustomItems.resolve(DIR, "a", ".png"));
        assertEquals(DIR + "b/c.png", CustomItems.resolve(DIR, "./b/c.png", ".png"));
        assertEquals("assets/minecraft/optifine/x.png", CustomItems.resolve(DIR, "~/x", ".png"));
        assertEquals("assets/minecraft/textures/items/y.png", CustomItems.resolve(DIR, "textures/items/y", ".png"));
        assertEquals(DIR + "gun.json", CustomItems.resolve(DIR, "gun", ".json"));
    }

    @Test
    void matchesNamesByPatternCaseInsensitively() throws IOException {
        CustomItems.Rule rule = rule(DIR + "deadshot.properties",
                "items=diamond_sword\nnbt.display.Name=ipattern:*dead?hot*\n");
        assertTrue(rule.matches(new Sword("§cThe DEADSHOT", 0, 1, Map.of())));
        assertTrue(rule.matches(new Sword("deadshot", 0, 1, Map.of())));
        assertFalse(rule.matches(new Sword("Sniper", 0, 1, Map.of())));
        assertFalse(rule.matches(new Sword(null, 0, 1, Map.of())));
    }

    @Test
    void exactNbtValuesAndRegexes() throws IOException {
        assertTrue(rule(DIR + "a.properties", "items=diamond_sword\nnbt.display.Name=Excalibur\n")
                .matches(new Sword("Excalibur", 0, 1, Map.of())));
        assertFalse(rule(DIR + "a.properties", "items=diamond_sword\nnbt.display.Name=Excalibur\n")
                .matches(new Sword("excalibur", 0, 1, Map.of())));
        assertTrue(rule(DIR + "a.properties", "items=diamond_sword\nnbt.display.Name=iregex:ex.*bur\n")
                .matches(new Sword("EXCALIBUR", 0, 1, Map.of())));
    }

    @Test
    void damageRangesAndPercentages() throws IOException {
        CustomItems.Rule worn = rule(DIR + "worn.properties", "items=diamond_sword\ndamage=50%-\n");
        assertFalse(worn.matches(new Sword(null, 100, 1, Map.of())));
        assertTrue(worn.matches(new Sword(null, 1000, 1, Map.of())));
        CustomItems.Rule exact = rule(DIR + "exact.properties", "items=diamond_sword\ndamage=0 5-7\n");
        assertTrue(exact.matches(new Sword(null, 6, 1, Map.of())));
        assertFalse(exact.matches(new Sword(null, 4, 1, Map.of())));
    }

    @Test
    void enchantmentsByNameAndLevel() throws IOException {
        CustomItems.Rule sharp = rule(DIR + "sharp.properties",
                "items=diamond_sword\nenchantmentIDs=sharpness\nenchantmentLevels=4-\n");
        assertTrue(sharp.matches(new Sword(null, 0, 1, Map.of(16, 5))));
        assertFalse(sharp.matches(new Sword(null, 0, 1, Map.of(16, 3))));
        assertFalse(sharp.matches(new Sword(null, 0, 1, Map.of(19, 5))));
    }

    @Test
    void namedTexturesReplaceOnlyTheirLayer() throws IOException {
        CustomItems.Rule bow = rule(DIR + "bow.properties",
                "items=diamond_sword\ntexture=bow_idle\ntexture.bow_pulling_2=bow_full\n");
        assertEquals(DIR + "bow_full.png", bow.texture("items/bow_pulling_2", true));
        assertEquals(DIR + "bow_idle.png", bow.texture("items/bow_pulling_0", true));
    }

    @Test
    void ruleWithoutItemsIsIgnored() throws IOException {
        assertNull(rule(DIR + "none.properties", "texture=x\n"));
    }
}
