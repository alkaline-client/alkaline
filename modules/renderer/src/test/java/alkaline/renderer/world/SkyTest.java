package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyTest {
    @Test
    void plainsSkyIsVanillasBlue() {
        // BiomeGenBase.getSkyColorByTemp(0.8F): the familiar 1.8.9 plains sky.
        assertEquals(0x78A7FF, Sky.skyColorByTemperature(0.8F));
    }

    @Test
    void skylightLosesElevenLevelsAtMidnight() {
        assertEquals(0, Sky.skylightSubtracted(6000L, 0F, 0F));
        assertEquals(11, Sky.skylightSubtracted(18000L, 0F, 0F));
        // Rain alone takes noon light down by 5/16: (int) (5/16 * 11) = 3.
        assertEquals(3, Sky.skylightSubtracted(6000L, 1F, 0F));
    }

    @Test
    void starsAndSunFollowTheTimeOfDay() {
        Sky sky = new Sky();
        sky.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(0F, sky.starBrightness, 1e-6F);
        assertEquals(1F, sky.sunBrightness, 1e-6F);
        assertEquals(0F, sky.sunrise[3], 1e-6F);
        sky.update(18000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(0.5F, sky.starBrightness, 1e-6F);
        assertEquals(0.2F, sky.sunBrightness, 1e-6F);
        assertEquals(0F, sky.skyColor[0] + sky.skyColor[1] + sky.skyColor[2], 1e-6F);
        sky.update(12300L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertTrue(sky.sunrise[3] > 0F, "a sunset fan at 12300");
    }

    @Test
    void moonPhaseAdvancesDaily() {
        Sky sky = new Sky();
        sky.update(18000L + 24000L * 3, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(3, sky.moonPhase);
        sky.update(18000L + 24000L * 9, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(1, sky.moonPhase);
    }

    @Test
    void underwaterFogIsExponentialAndBlue() {
        Sky sky = new Sky();
        sky.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.WATER);
        assertEquals(0.1F, sky.fogDensity, 1e-6F);
        assertTrue(sky.fogColor[2] > sky.fogColor[0]);
    }

    /** A pack whose sky, fog and underwater colormaps are each one fixed color (CustomColormap format=fixed). */
    private static CustomColors fixedColormaps(String sky, String fog, String underwater) {
        Map<String, String> files = Map.of(
                "assets/minecraft/optifine/colormap/sky0.properties", "format=fixed\ncolor=" + sky + "\n",
                "assets/minecraft/optifine/colormap/fog0.properties", "format=fixed\ncolor=" + fog + "\n",
                "assets/minecraft/optifine/colormap/underwater.properties", "format=fixed\ncolor=" + underwater + "\n");
        return CustomColors.load(new AssetSource() {
            @Override
            public InputStream open(String path) {
                String text = files.get(path);
                return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
            }

            @Override
            public String describe() {
                return "memory";
            }
        });
    }

    @Test
    void skyColormapScalesWithTheVanillaSkyOverPlains() {
        Sky vanilla = new Sky();
        vanilla.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        Sky custom = new Sky();
        custom.customColors = fixedColormaps("ff8000", "000000", "000000");
        custom.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        // CustomColors.getSkyColor: color * (vanilla / (0.5, 0.66275, 1)).
        assertEquals(vanilla.skyColor[0] / 0.5F, custom.skyColor[0], 1e-5F);
        assertEquals(128 / 255F * vanilla.skyColor[1] / 0.66275F, custom.skyColor[1], 1e-5F);
        assertEquals(0F, custom.skyColor[2], 1e-6F);
        // At midnight the vanilla sky is black, and so is the colormap's.
        custom.update(18000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(0F, custom.skyColor[0] + custom.skyColor[1] + custom.skyColor[2], 1e-6F);
    }

    @Test
    void fogColormapReplacesTheHorizonColorAtFullRenderDistance() {
        Sky custom = new Sky();
        custom.customColors = fixedColormaps("000000", "ff0000", "000000");
        // 32 chunks: no blend toward the sky color, so the fog color is the colormap's alone.
        custom.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 32, Sky.Medium.AIR);
        assertEquals(1F, custom.fogColor[0], 1e-3F);
        assertEquals(0F, custom.fogColor[1], 1e-6F);
        assertEquals(0F, custom.fogColor[2], 1e-6F);
        // At dusk (13000) the vanilla fog is darker, and the colormap darkens with it.
        custom.update(13000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 32, Sky.Medium.AIR);
        assertTrue(custom.fogColor[0] > 0F && custom.fogColor[0] < 0.6F, "dusk fog red " + custom.fogColor[0]);
    }

    @Test
    void underwaterColormapReplacesTheFogOnlyInWater() {
        Sky custom = new Sky();
        custom.customColors = fixedColormaps("000000", "000000", "00ff00");
        custom.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.WATER);
        assertEquals(0F, custom.fogColor[0], 1e-6F);
        assertEquals(1F, custom.fogColor[1], 1e-6F);
        assertEquals(0F, custom.fogColor[2], 1e-6F);
        custom.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertTrue(custom.fogColor[1] < 0.5F, "air keeps the horizon fog");
    }

    @Test
    void voidFogFollowsTheFeetHeightNotTheEyes() {
        // EntityRenderer.updateFogColor: (posY * 1/32)^2 darkens the fog below y = 32; posY is the feet, not the eye.
        Sky feet = new Sky();
        feet.feetY = 16.0;
        feet.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        Sky eyes = new Sky();
        eyes.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 70.0, 12, Sky.Medium.AIR);
        assertEquals(eyes.fogColor[2] * 0.25F, feet.fogColor[2], 1e-5F);
        // Without feetY the eye height less 1.62 stands in: 17.62 - 1.62 = 16.
        Sky derived = new Sky();
        derived.update(6000L, 0F, 0F, 0F, 0.8F, 1F, 0F, 0F, 17.62, 12, Sky.Medium.AIR);
        assertEquals(feet.fogColor[2], derived.fogColor[2], 1e-5F);
    }
}
