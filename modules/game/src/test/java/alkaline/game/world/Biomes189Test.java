package alkaline.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Biomes189Test {
    @Test
    void rainfallMatchesBiomeRegistrations() {
        assertEquals(0.4F, Biomes189.rainfall(1));
        assertEquals(0.0F, Biomes189.rainfall(2));
        assertEquals(0.9F, Biomes189.rainfall(21));
        assertEquals(1.0F, Biomes189.rainfall(14));
        assertEquals(0.6F, Biomes189.rainfall(27));
    }

    @Test
    void mutatedVariantsCopyTheirParent() {
        assertEquals(Biomes189.rainfall(4), Biomes189.rainfall(132));
        assertEquals(Biomes189.rainfall(35), Biomes189.rainfall(163));
        assertEquals(Biomes189.rainfall(39), Biomes189.rainfall(167));
    }

    @Test
    void unknownIdsDefaultToHalf() {
        assertEquals(0.5F, Biomes189.rainfall(100));
        assertEquals(0.5F, Biomes189.rainfall(-1));
        assertEquals(0.5F, Biomes189.rainfall(300));
    }
}
