package alkaline.renderer.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.renderer.asset.AssetSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Random entity textures against OptiFine's RandomEntities, RandomEntityProperties and RandomEntityRule. */
class RandomEntitiesTest {
    private static final String TEXTURES = "assets/minecraft/textures/";
    private static final String RANDOM = "assets/minecraft/optifine/random/";
    private static final String MOB = "assets/minecraft/mcpatcher/mob/";

    /** Files held in memory; images are placeholders, only their presence matters. */
    private static final class Pack implements AssetSource {
        private final Map<String, byte[]> files = new HashMap<>();

        Pack file(String path) {
            files.put(path, new byte[]{1});
            return this;
        }

        Pack text(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        @Override
        public InputStream open(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public List<String> list(String prefix) {
            return files.keySet().stream().filter(path -> path.startsWith(prefix)).sorted().toList();
        }

        @Override
        public String describe() {
            return "memory";
        }
    }

    /** A subject with settable answers. */
    private static final class Mob implements RandomEntities.Subject {
        int id;
        int biome = 1;
        int y = 64;
        int health = 20;
        int maxHealth = 20;
        String name;
        int profession = -1;
        int collar = Integer.MIN_VALUE;
        int baby = 0;

        Mob(int id) {
            this.id = id;
        }

        @Override
        public int randomId() {
            return id;
        }

        @Override
        public int spawnBiome() {
            return biome;
        }

        @Override
        public int spawnY() {
            return y;
        }

        @Override
        public int health() {
            return health;
        }

        @Override
        public int maxHealth() {
            return maxHealth;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int profession() {
            return profession;
        }

        @Override
        public int collar() {
            return collar;
        }

        @Override
        public int baby() {
            return baby;
        }
    }

    private static String pick(RandomEntities random, String path, RandomEntities.Subject mob) {
        return random.texture(path, mob, 0, 6000, RandomEntities.CLEAR);
    }

    private static Pack creepers() {
        return new Pack().file(TEXTURES + "entity/creeper/creeper.png");
    }

    @Test
    void numberedCopiesArePickedByIdModuloTheirCount() {
        RandomEntities random = RandomEntities.load(creepers()
                .file(RANDOM + "entity/creeper/creeper2.png").file(RANDOM + "entity/creeper/creeper3.png"));
        assertEquals("entity/creeper/creeper", pick(random, "entity/creeper/creeper", new Mob(0)));
        assertEquals(RANDOM + "entity/creeper/creeper2.png", pick(random, "entity/creeper/creeper", new Mob(1)));
        assertEquals(RANDOM + "entity/creeper/creeper3.png", pick(random, "entity/creeper/creeper", new Mob(5)));
        // Index 1 is the base itself: the same string comes back, so the texture cache key does not change.
        String path = "entity/creeper/creeper";
        assertSame(path, pick(random, path, new Mob(3)));
    }

    @Test
    void copiesMayHaveGapsOfUpToTenNumbers() {
        RandomEntities random = RandomEntities.load(creepers()
                .file(RANDOM + "entity/creeper/creeper2.png").file(RANDOM + "entity/creeper/creeper9.png")
                .file(RANDOM + "entity/creeper/creeper30.png"));
        // Copies 2 and 9 are found; 30 is past the ten-number window after 9.
        assertEquals(RANDOM + "entity/creeper/creeper9.png", pick(random, "entity/creeper/creeper", new Mob(2)));
        assertEquals("entity/creeper/creeper", pick(random, "entity/creeper/creeper", new Mob(3)));
    }

    @Test
    void texturesWithoutTheVanillaBaseOrOutsideEntitiesAreIgnored() {
        RandomEntities random = RandomEntities.load(new Pack()
                .file(RANDOM + "entity/missing/missing2.png")
                .file(TEXTURES + "blocks/stone.png").file(RANDOM + "blocks/stone2.png"));
        assertTrue(random.isEmpty());
    }

    @Test
    void mcpatcherMobFolderMirrorsTheEntityFolder() {
        RandomEntities random = RandomEntities.load(creepers().file(MOB + "creeper/creeper2.png"));
        assertEquals(MOB + "creeper/creeper2.png", pick(random, "entity/creeper/creeper", new Mob(1)));
    }

    @Test
    void rulesPickByWeightAndTheFirstMatchingRuleWins() {
        RandomEntities random = RandomEntities.load(creepers()
                .file(RANDOM + "entity/creeper/creeper2.png").file(RANDOM + "entity/creeper/creeper3.png")
                .text(RANDOM + "entity/creeper/creeper.properties", """
                        textures.1=2
                        biomes.1=desert
                        textures.2=1 3
                        weights.2=1 3
                        """));
        Mob desert = new Mob(7);
        desert.biome = 2;
        assertEquals(RANDOM + "entity/creeper/creeper2.png", pick(random, "entity/creeper/creeper", desert));
        // Weights 1 and 3: id % 4 == 0 is the first, 1-3 the second.
        assertEquals("entity/creeper/creeper", pick(random, "entity/creeper/creeper", new Mob(4)));
        assertEquals(RANDOM + "entity/creeper/creeper3.png", pick(random, "entity/creeper/creeper", new Mob(6)));
    }

    @Test
    void heightsHealthAndNameLimitRules() {
        RandomEntities random = RandomEntities.load(creepers()
                .file(RANDOM + "entity/creeper/creeper2.png").file(RANDOM + "entity/creeper/creeper3.png")
                .file(RANDOM + "entity/creeper/creeper4.png")
                .text(RANDOM + "entity/creeper/creeper.properties", """
                        textures.1=2
                        minHeight.1=0
                        maxHeight.1=40
                        textures.2=3
                        health.2=0-50%
                        textures.3=4
                        name.3=ipattern:*bob*
                        """));
        Mob deep = new Mob(0);
        deep.y = 12;
        assertEquals(RANDOM + "entity/creeper/creeper2.png", pick(random, "entity/creeper/creeper", deep));
        Mob hurt = new Mob(0);
        hurt.health = 8;
        assertEquals(RANDOM + "entity/creeper/creeper3.png", pick(random, "entity/creeper/creeper", hurt));
        Mob named = new Mob(0);
        named.name = "Big BOB";
        assertEquals(RANDOM + "entity/creeper/creeper4.png", pick(random, "entity/creeper/creeper", named));
        // No rule matches and there are no plain copies: the base.
        assertEquals("entity/creeper/creeper", pick(random, "entity/creeper/creeper", new Mob(0)));
    }

    @Test
    void professionsCollarsBabiesAndWorldConditions() {
        RandomEntities random = RandomEntities.load(new Pack().file(TEXTURES + "entity/wolf/wolf.png")
                .file(RANDOM + "entity/wolf/wolf2.png").file(RANDOM + "entity/wolf/wolf3.png")
                .file(RANDOM + "entity/wolf/wolf4.png").file(RANDOM + "entity/wolf/wolf5.png")
                .text(RANDOM + "entity/wolf/wolf.properties", """
                        textures.1=2
                        collarColors.1=red lightBlue
                        textures.2=3
                        baby.2=true
                        textures.3=4
                        weather.3=rain thunder
                        textures.4=5
                        moonPhase.4=4
                        dayTime.4=12000-23999
                        """));
        Mob red = new Mob(0);
        red.collar = 14;
        assertEquals(RANDOM + "entity/wolf/wolf2.png", pick(random, "entity/wolf/wolf", red));
        Mob untamed = new Mob(0);
        untamed.collar = -1;
        assertEquals("entity/wolf/wolf", pick(random, "entity/wolf/wolf", untamed));
        Mob baby = new Mob(0);
        baby.collar = -1;
        baby.baby = 1;
        assertEquals(RANDOM + "entity/wolf/wolf3.png", pick(random, "entity/wolf/wolf", baby));
        Mob wet = new Mob(0);
        wet.collar = -1;
        assertEquals(RANDOM + "entity/wolf/wolf4.png",
                random.texture("entity/wolf/wolf", wet, 0, 6000, RandomEntities.RAIN));
        // Moon phase 4 at night, on any day (the time of day, not the raw world time).
        assertEquals(RANDOM + "entity/wolf/wolf5.png",
                random.texture("entity/wolf/wolf", wet, 4, 24000L * 3 + 15000, RandomEntities.CLEAR));
        assertEquals("entity/wolf/wolf", random.texture("entity/wolf/wolf", wet, 4, 6000, RandomEntities.CLEAR));
    }

    @Test
    void villagerProfessionsMatchByNameAndCareerZero() {
        assertEquals(1, RandomEntities.professions("librarian").length);
        assertEquals(2, RandomEntities.professions("farmer:fisherman,shepherd")[0][1]);
        assertEquals(0, RandomEntities.professions("unknown").length);
        RandomEntities random = RandomEntities.load(new Pack().file(TEXTURES + "entity/villager/farmer.png")
                .file(RANDOM + "entity/villager/farmer2.png").file(RANDOM + "entity/villager/farmer3.png")
                .text(RANDOM + "entity/villager/farmer.properties", """
                        textures.1=2
                        professions.1=farmer
                        textures.2=3
                        professions.2=farmer:fisherman
                        """));
        Mob farmer = new Mob(0);
        farmer.profession = 0;
        assertEquals(RANDOM + "entity/villager/farmer2.png", pick(random, "entity/villager/farmer", farmer));
    }

    @Test
    void dependentTexturesUseTheirParentsProperties() {
        RandomEntities random = RandomEntities.load(new Pack().file(TEXTURES + "entity/spider/spider.png")
                .file(TEXTURES + "entity/spider_eyes.png")
                .file(RANDOM + "entity/spider_eyes2.png")
                .text(RANDOM + "entity/spider.properties", "textures.1=2\n"));
        assertEquals(RANDOM + "entity/spider_eyes2.png", pick(random, "entity/spider_eyes", new Mob(0)));
    }

    @Test
    void invalidRulesAreSkippedAndAPropertiesFileWinsOverCopies() {
        RandomEntities random = RandomEntities.load(creepers().file(RANDOM + "entity/creeper/creeper2.png")
                .text(RANDOM + "entity/creeper/creeper.properties", """
                        textures.1=7
                        textures.2=2
                        heights.2=100-200
                        """));
        assertTrue(random.has("entity/creeper/creeper"));
        // Rule 1 names a missing copy and is dropped; rule 2 does not match; the copies are not used.
        assertEquals("entity/creeper/creeper", pick(random, "entity/creeper/creeper", new Mob(1)));
        assertFalse(RandomEntities.NONE.has("entity/creeper/creeper"));
    }
}
