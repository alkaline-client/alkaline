package alkaline.renderer.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightmapTest {
    @Test
    void levelBrightnessMatchesVanillasTable() {
        assertEquals(0f, Lightmap.levelBrightness(0), 1e-6f);
        assertEquals(1f, Lightmap.levelBrightness(15), 1e-6f);
        // f = 7/15: (8/15) / (21/15 + 1) = 2/9
        assertEquals(2f / 9f, Lightmap.levelBrightness(8), 1e-6f);
    }

    @Test
    void fullDaylightIsAlmostWhiteAndDarknessAlmostBlack() {
        int day = Lightmap.color(15, 0, 1f, 0f, 0f, 0f);
        // 0.99 after the first 0.96x + 0.03, then 0.9804 after the second: (int) (0.9804 * 255) = 250.
        assertEquals(250, day >> 16 & 0xFF);
        assertEquals(250, day & 0xFF);
        int dark = Lightmap.color(0, 0, 1f, 0f, 0f, 0f);
        assertTrue((dark >> 16 & 0xFF) < 16, "level 0 keeps only vanilla's small floor");
    }

    @Test
    void blockLightIsWarmAndBrightnessLiftsShadows() {
        int torch = Lightmap.color(0, 7, 0.2f, 0f, 0f, 0f); // level 14 saturates to white
        assertTrue((torch >> 16 & 0xFF) > (torch & 0xFF), "torch light is redder than it is blue");
        int moody = Lightmap.color(4, 0, 1f, 0f, 0f, 0f);
        int bright = Lightmap.color(4, 0, 1f, 0f, 1f, 0f);
        assertTrue((bright >> 8 & 0xFF) > (moody >> 8 & 0xFF));
    }

    @Test
    void sunBrightnessFollowsTheDay() {
        assertEquals(1f, Lightmap.sunBrightness(Lightmap.celestialAngle(6000, 0f)), 1e-3f);
        assertEquals(0.2f, Lightmap.sunBrightness(Lightmap.celestialAngle(18000, 0f)), 1e-3f);
    }
}
