package alkaline.game.world;

import alkaline.game.worldgen.NoiseGeneratorPerlin;
import java.util.Arrays;
import java.util.Random;

/**
 * Minecraft 1.8.9's biome climate by biome id, as the client needs it for weather and sky color: temperature,
 * whether the biome rains, and whether it snows (BiomeGenBase registrations, mutated variants copying their
 * parent's climate except mutated savannas, which average theirs with 1). Unknown ids fall back to plains.
 */
public final class Biomes189 {
    private static final float[] TEMPERATURE = new float[256];
    private static final boolean[] RAIN = new boolean[256];
    private static final boolean[] SNOW = new boolean[256];
    /** BiomeGenBase.rainfall: the humidity that indexes the vanilla colormaps and custom sky colors. */
    private static final float[] RAINFALL = new float[256];
    /** BiomeGenBase.temperatureNoise: varies temperature above y = 64. */
    private static final NoiseGeneratorPerlin TEMPERATURE_NOISE = new NoiseGeneratorPerlin(new Random(1234L), 1);

    static {
        Arrays.fill(TEMPERATURE, 0.8F);
        Arrays.fill(RAIN, true);
        biome(0, 0.5F, true, false);    // ocean
        biome(1, 0.8F, true, false);    // plains
        biome(2, 2.0F, false, false);   // desert
        biome(3, 0.2F, true, false);    // extreme hills
        biome(4, 0.7F, true, false);    // forest
        biome(5, 0.25F, true, false);   // taiga
        biome(6, 0.8F, true, false);    // swampland
        biome(7, 0.5F, true, false);    // river
        biome(8, 2.0F, false, false);   // hell
        biome(9, 0.5F, false, false);   // the end
        biome(10, 0.0F, true, true);    // frozen ocean
        biome(11, 0.0F, true, true);    // frozen river
        biome(12, 0.0F, true, true);    // ice plains
        biome(13, 0.0F, true, true);    // ice mountains
        biome(14, 0.9F, true, false);   // mushroom island
        biome(15, 0.9F, true, false);   // mushroom island shore
        biome(16, 0.8F, true, false);   // beach
        biome(17, 2.0F, false, false);  // desert hills
        biome(18, 0.7F, true, false);   // forest hills
        biome(19, 0.25F, true, false);  // taiga hills
        biome(20, 0.2F, true, false);   // extreme hills edge
        biome(21, 0.95F, true, false);  // jungle
        biome(22, 0.95F, true, false);  // jungle hills
        biome(23, 0.95F, true, false);  // jungle edge
        biome(24, 0.5F, true, false);   // deep ocean
        biome(25, 0.2F, true, false);   // stone beach
        biome(26, 0.05F, true, true);   // cold beach
        biome(27, 0.6F, true, false);   // birch forest
        biome(28, 0.6F, true, false);   // birch forest hills
        biome(29, 0.7F, true, false);   // roofed forest
        biome(30, -0.5F, true, true);   // cold taiga
        biome(31, -0.5F, true, true);   // cold taiga hills
        biome(32, 0.3F, true, false);   // mega taiga
        biome(33, 0.3F, true, false);   // mega taiga hills
        biome(34, 0.2F, true, false);   // extreme hills+
        biome(35, 1.2F, false, false);  // savanna
        biome(36, 1.0F, false, false);  // savanna plateau
        biome(37, 2.0F, false, false);  // mesa
        biome(38, 2.0F, false, false);  // mesa plateau F
        biome(39, 2.0F, false, false);  // mesa plateau
        // Mutated variants (id + 128) copy their parent's climate.
        for (int parent : new int[]{1, 2, 3, 4, 5, 6, 21, 23, 27, 28, 29, 30, 32, 34, 37, 38, 39}) {
            biome(parent + 128, TEMPERATURE[parent], RAIN[parent], SNOW[parent]);
        }
        biome(140, 0.0F, true, true);   // ice plains spikes
        biome(160, 0.25F, true, false); // mega spruce taiga
        biome(161, 0.25F, true, false); // redwood taiga hills M
        biome(163, (1.2F + 1.0F) * 0.5F, false, false); // savanna M
        biome(164, (1.0F + 1.0F) * 0.5F, false, false); // savanna plateau M
        Arrays.fill(RAINFALL, 0.5F);
        rainfall(0.4F, 1, 16);                          // plains, beach
        rainfall(0.0F, 2, 8, 17, 35, 36, 37, 38, 39);   // desert, hell, desert hills, savannas, mesas
        rainfall(0.3F, 3, 20, 25, 26, 34);              // extreme hills, edge, stone beach, cold beach, hills+
        rainfall(0.8F, 4, 5, 18, 19, 23, 29, 32, 33);   // forests, taigas, jungle edge, roofed forest, mega taigas
        rainfall(0.9F, 6, 21, 22);                      // swampland, jungle, jungle hills
        rainfall(1.0F, 14, 15);                         // mushroom island and shore
        rainfall(0.6F, 27, 28);                         // birch forest and hills
        rainfall(0.4F, 30, 31);                         // cold taiga and hills
        rainfall(0.8F, 160, 161);                       // mega spruce taiga, redwood taiga hills M
        // Mutated variants (id + 128) copy their parent's rainfall.
        for (int parent : new int[]{1, 2, 3, 4, 5, 6, 21, 23, 27, 28, 29, 30, 32, 34, 35, 36, 37, 38, 39}) {
            RAINFALL[parent + 128] = RAINFALL[parent];
        }
    }

    private Biomes189() {
    }

    private static void biome(int id, float temperature, boolean rain, boolean snow) {
        TEMPERATURE[id] = temperature;
        RAIN[id] = rain;
        SNOW[id] = snow;
    }

    private static void rainfall(float value, int... ids) {
        for (int id : ids) {
            RAINFALL[id] = value;
        }
    }

    /** BiomeGenBase.getFloatTemperature: the base temperature, lowered with height above y = 64. */
    public static float temperature(int biome, int x, int y, int z) {
        float base = TEMPERATURE[biome & 255];
        if (y > 64) {
            float noise = (float) (TEMPERATURE_NOISE.func_151601_a(x / 8.0D, z / 8.0D) * 4.0D);
            return base - (noise + y - 64.0F) * 0.05F / 30.0F;
        }
        return base;
    }

    /** BiomeGenBase.getRainfall: the biome's humidity; ids no biome registers read 0.5. */
    public static float rainfall(int biome) {
        return biome >= 0 && biome < RAINFALL.length ? RAINFALL[biome] : 0.5F;
    }

    /** {@code canRain() || getEnableSnow()}: whether rain or snow falls in the biome at all. */
    public static boolean precipitates(int biome) {
        return RAIN[biome & 255] || SNOW[biome & 255];
    }
}
