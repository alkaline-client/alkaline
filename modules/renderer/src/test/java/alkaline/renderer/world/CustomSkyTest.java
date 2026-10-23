package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.renderer.asset.AssetSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Custom sky layers against OptiFine's CustomSkyLayer (external/KotlinizedMCP-mcp net/optifine). */
class CustomSkyTest {
    private static final String ROOT = "assets/minecraft/optifine/sky/world0/";
    private static final float DELTA = 1e-6f;

    private static CustomSky.Layer layer(String... pairs) {
        Properties properties = new Properties();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.setProperty(pairs[index], pairs[index + 1]);
        }
        return CustomSky.layer(ROOT, "sky1", properties);
    }

    /** Fades in from 06:00 to 08:00 and out from 18:00 to 20:00, with the extra pairs added. */
    private static CustomSky.Layer daytime(String... extra) {
        List<String> pairs = new ArrayList<>(List.of("startFadeIn", "06:00", "endFadeIn", "08:00",
                "startFadeOut", "18:00", "endFadeOut", "20:00"));
        pairs.addAll(List.of(extra));
        return layer(pairs.toArray(String[]::new));
    }

    /** Fades in from 20:00 to 02:00 and out from 04:00 to 06:00, so the fades cross midnight. */
    private static CustomSky.Layer overnight() {
        return layer("startFadeIn", "20:00", "endFadeIn", "02:00", "startFadeOut", "04:00", "endFadeOut", "06:00");
    }

    // ---- time parsing ----------------------------------------------------------------------------------------

    @Test
    void clockTimeIsHoursSinceSixInThousandthsOfADay() {
        assertEquals(0, CustomSky.time("06:00"));
        assertEquals(6000, CustomSky.time("12:00"));
        assertEquals(6500, CustomSky.time("12:30"));
        assertEquals(17500, CustomSky.time("23:30"));
    }

    @Test
    void hoursBeforeSixWrapIntoTheDayBeforeTheSunrise() {
        assertEquals(18000, CustomSky.time("00:00"));
    }

    @Test
    void malformedClockTimesAreRejected() {
        assertEquals(-1, CustomSky.time(null));
        assertEquals(-1, CustomSky.time("24:00"));
        assertEquals(-1, CustomSky.time("12:60"));
        assertEquals(-1, CustomSky.time("12"));
        assertEquals(-1, CustomSky.time("noon"));
    }

    // ---- fades -----------------------------------------------------------------------------------------------

    @Test
    void fadeInRisesBetweenItsTimesAndFadeOutFallsAfterItsStart() {
        CustomSky.Layer layer = daytime();
        assertEquals(0f, layer.fade(0), DELTA);
        assertEquals(0.5f, layer.fade(1000), DELTA);
        assertEquals(1f, layer.fade(2000), DELTA);
        assertEquals(1f, layer.fade(7000), DELTA);
        assertEquals(1f, layer.fade(12000), DELTA);
        assertEquals(0.5f, layer.fade(13000), DELTA);
        assertEquals(0f, layer.fade(14000), DELTA);
        assertEquals(0f, layer.fade(20000), DELTA);
    }

    @Test
    void fadeReadsTheTimeOfDayModuloADay() {
        CustomSky.Layer layer = daytime();
        assertEquals(layer.fade(1000), layer.fade(1000 + 24000), DELTA);
        assertEquals(layer.fade(13000), layer.fade(13000 - 24000), DELTA);
    }

    @Test
    void fadesWrapPastMidnight() {
        CustomSky.Layer layer = overnight();
        assertEquals(0.5f, layer.fade(17000), DELTA);
        assertEquals(1f, layer.fade(21000), DELTA);
        assertEquals(0.5f, layer.fade(23000), DELTA);
        assertEquals(0f, layer.fade(1000), DELTA);
        assertEquals(0f, layer.fade(12000), DELTA);
    }

    @Test
    void missingStartFadeOutIsDerivedFromTheFadeInLength() {
        CustomSky.Layer layer = layer("startFadeIn", "06:00", "endFadeIn", "08:00", "endFadeOut", "20:00");
        assertEquals(12000, layer.startFadeOut());
    }

    // ---- validation ------------------------------------------------------------------------------------------

    @Test
    void layerNeedsStartFadeInEndFadeInAndEndFadeOut() {
        assertNull(layer("startFadeIn", "06:00", "endFadeIn", "08:00"));
        assertNull(layer("endFadeIn", "08:00", "endFadeOut", "20:00"));
    }

    @Test
    void fadeTimesMustAddUpToADay() {
        // The phases here add up to 48 hours.
        assertNull(layer("startFadeIn", "06:00", "endFadeIn", "18:00", "startFadeOut", "09:00", "endFadeOut", "15:00"));
    }

    @Test
    void fadeTimesThatAllCoincideAreRejected() {
        assertNull(layer("startFadeIn", "06:00", "endFadeIn", "06:00", "startFadeOut", "06:00", "endFadeOut", "06:00"));
    }

    @Test
    void negativeSpeedAndNonPositiveDaysLoopAreRejected() {
        assertNull(daytime("speed", "-1"));
        assertNull(daytime("daysLoop", "0"));
    }

    @Test
    void defaultsMatchOptiFine() {
        CustomSky.Layer layer = daytime();
        assertEquals(ROOT + "sky1.png", layer.source());
        assertEquals(CustomSky.Blend.ADD, layer.blend());
        assertTrue(layer.rotate());
        assertEquals(1f, layer.speed(), DELTA);
        assertEquals(8, layer.daysLoop());
        assertTrue(layer.days().isEmpty());
        assertTrue(layer.heights().isEmpty());
    }

    @Test
    void loadKeepsValidLayersInNumberOrderAndSkipsInvalidOnes() {
        Map<String, byte[]> files = new HashMap<>();
        files.put(ROOT + "sky3.properties", ("startFadeIn=06:00\nendFadeIn=08:00\nstartFadeOut=18:00\n"
                + "endFadeOut=20:00\n").getBytes(StandardCharsets.ISO_8859_1));
        files.put(ROOT + "sky1.properties", ("startFadeIn=06:00\nendFadeIn=08:00\nendFadeOut=20:00\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        files.put(ROOT + "sky2.properties", ("startFadeIn=06:00\nendFadeIn=08:00\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        AssetSource assets = new AssetSource() {
            @Override
            public InputStream open(String path) {
                byte[] bytes = files.get(path);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }

            @Override
            public String describe() {
                return "memory";
            }
        };
        List<CustomSky.Layer> layers = CustomSky.load(assets).layers();
        assertEquals(2, layers.size());
        assertEquals(ROOT + "sky1.png", layers.get(0).source());
        assertEquals(ROOT + "sky3.png", layers.get(1).source());
    }

    // ---- axis ------------------------------------------------------------------------------------------------

    @Test
    void axisIsReadAsZYMinusXIntoTheLayerFrame() {
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis(null), DELTA);
        assertArrayEquals(new float[]{0f, 1f, 0f}, CustomSky.axis("0 1 0"), DELTA);
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis("0 0 1"), DELTA);
        assertArrayEquals(new float[]{0f, 0f, -1f}, CustomSky.axis("1 0 0"), DELTA);
    }

    @Test
    void invalidAxesFallBackToTheDefault() {
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis("1 2 3"), DELTA);
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis("0 0 0"), DELTA);
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis("1 0"), DELTA);
        assertArrayEquals(new float[]{1f, 0f, 0f}, CustomSky.axis("x y z"), DELTA);
    }

    // ---- days, heights and weather -------------------------------------------------------------------------

    @Test
    void daysCountFromTheFadeInAndLoop() {
        CustomSky.Layer layer = daytime("days", "0-1", "daysLoop", "4");
        assertTrue(layer.active(1000));
        assertTrue(layer.active(25000));
        assertFalse(layer.active(49000));
        assertFalse(layer.active(73000));
        assertTrue(layer.active(97000));
        assertFalse(layer.active(-23000));
    }

    @Test
    void layerIsInactiveBetweenItsFadeOutAndFadeIn() {
        CustomSky.Layer layer = daytime();
        assertFalse(layer.active(14000));
        assertFalse(layer.active(0));
        assertTrue(layer.active(1000));
        assertTrue(layer.active(13999));
    }

    @Test
    void heightsGiveFullBrightnessInsideTheRange() {
        CustomSky.Layer layer = daytime("heights", "10-20");
        assertEquals(1f, layer.position(15), DELTA);
        assertEquals(0f, layer.position(5), DELTA);
        assertEquals(1f, daytime().position(5), DELTA);
    }

    @Test
    void clearSkyWeighsOneMinusRain() {
        assertEquals(0.75f, daytime().weather(0.25f, 0f), DELTA);
    }

    @Test
    void rainWithoutThunderFollowsTheRainStrength() {
        assertEquals(0.5f, daytime("weather", "rain").weather(0.5f, 0f), DELTA);
        assertEquals(0f, daytime("weather", "rain").weather(0f, 0f), DELTA);
    }

    @Test
    void allWeatherTogetherSumToOne() {
        assertEquals(1f, daytime("weather", "clear rain thunder").weather(0.5f, 0.25f), DELTA);
    }

    @Test
    void thunderWithoutRainIsTheThunderStrength() {
        assertEquals(0.3f, daytime("weather", "thunder").weather(0f, 0.3f), DELTA);
    }

    @Test
    void rainWithoutThunderSubtractsTheThunderStrength() {
        // OptiFine: rain - thunder = 0.5 - 0.25.
        assertEquals(0.25f, daytime("weather", "rain").weather(0.5f, 0.25f), DELTA);
    }

    @Test
    void thunderOnlyWeighsTheThunderStrength() {
        // OptiFine: thunder = 0.25, whatever the rain is.
        assertEquals(0.25f, daytime("weather", "thunder").weather(0.5f, 0.25f), DELTA);
    }
}
