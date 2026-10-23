package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import alkaline.renderer.asset.AssetSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Custom colors against OptiFine's CustomColors and CustomColormap (external/KotlinizedMCP-mcp net/optifine). */
class CustomColorsTest {
    private static final String OPTIFINE = "assets/minecraft/optifine/";

    /** Text and PNG files held in memory. */
    private static final class MemoryAssets implements AssetSource {
        private final Map<String, byte[]> files = new HashMap<>();

        MemoryAssets text(String path, String text) {
            files.put(OPTIFINE + path, text.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        MemoryAssets png(String path, int width, int height, IntBinaryOperator rgb) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, rgb.applyAsInt(x, y));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            files.put(OPTIFINE + path, out.toByteArray());
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

    /** Red grows with x and green with y, 100 per step: pixel (2, 2) of a 3x3 map is 0xC8C800. */
    private static int rgb(int x, int y) {
        return (x * 100) << 16 | (y * 100) << 8;
    }

    // ---- hex colors ------------------------------------------------------------------------------------------

    @Test
    void hexColorsAcceptPlainHashAndZeroXPrefixes() {
        assertEquals(0xFF8000, CustomColors.color("ff8000"));
        assertEquals(0x00FF00, CustomColors.color("#00FF00"));
        assertEquals(0x0000FF, CustomColors.color("0x0000ff"));
        assertEquals(0x123456, CustomColors.color("  123456 "));
    }

    @Test
    void blankOrInvalidHexColorsAreNotAColor() {
        assertEquals(-1, CustomColors.color(null));
        assertEquals(-1, CustomColors.color("   "));
        assertEquals(-1, CustomColors.color("xyz"));
        assertEquals(-1, CustomColors.color("0x"));
    }

    // ---- potion and egg keys ---------------------------------------------------------------------------------

    @Test
    void potionEggAndWaterKeysMapToTheirIds() {
        MemoryAssets assets = new MemoryAssets().text("color.properties", """
                potion.moveSpeed=ff0000
                potion.harm=#123456
                potion.water=0000ff
                particle.water=abcdef
                lilypad=00ff00
                egg.shell.Creeper=111111
                egg.spots.Creeper=222222
                egg.shell.Villager=333333
                """);
        CustomColors colors = CustomColors.load(assets);
        assertEquals(0xFF0000, colors.potion(1));
        assertEquals(0x123456, colors.potion(7));
        assertEquals(-1, colors.potion(0));
        assertEquals(-1, colors.potion(2));
        assertEquals(0x0000FF, colors.waterPotion());
        assertEquals(0xABCDEF, colors.waterDrop());
        assertEquals(0x00FF00, colors.lilyPad());
        assertEquals(0x111111, colors.egg(50, 0));
        assertEquals(0x222222, colors.egg(50, 1));
        assertEquals(0x333333, colors.egg(120, 0));
        assertEquals(-1, colors.egg(120, 1));
    }

    // ---- colormaps -------------------------------------------------------------------------------------------

    @Test
    void vanillaColormapLooksUpTemperatureAndRainfallInTheTriangle() {
        CustomColors colors = CustomColors.load(new MemoryAssets().png("colormap/sky0.png", 3, 3, CustomColorsTest::rgb));
        CustomColors.Colormap sky = colors.sky();
        assertNotNull(sky);
        // Hot and wet: the top left corner. Cold and dry: the bottom right.
        assertEquals(0x000000, sky.color(0, 1f, 1f, 64));
        assertEquals(0xC8C800, sky.color(0, 0f, 0f, 64));
        // Half and half: x = 1, and rainfall is scaled by temperature, so y = 1.
        assertEquals(0x646400, sky.color(0, 0.5f, 0.5f, 64));
        // Out of range values clamp to the map's edges.
        assertEquals(0x000000, sky.color(0, 2f, 1f, 64));
    }

    @Test
    void vanillaDefaultColorIsTheMapAtTheCenterClampedToTheEdge() {
        CustomColors colors = CustomColors.load(new MemoryAssets().png("colormap/sky0.png", 3, 3, CustomColorsTest::rgb));
        // Pixel (127, 127) clamps to (2, 2) on a 3x3 map.
        assertEquals(0xC8C800, colors.sky().color());
    }

    @Test
    void gridColormapIndexesByBiomeAndHeightMinusItsOffset() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/fog0.properties", "format=grid\nyOffset=1\n")
                .png("colormap/fog0.png", 4, 4, (x, y) -> (x * 50) << 16 | (y * 50) << 8));
        CustomColors.Colormap fog = colors.fog();
        assertNotNull(fog);
        // Biome 2 at height 3 reads row 2; biome 3 below the map reads row 0.
        assertEquals(0x646400, fog.color(2, 0f, 0f, 3));
        assertEquals(0x960000, fog.color(3, 0f, 0f, 0));
    }

    @Test
    void gridDefaultColorIsThePlainsBiomeAtSeaLevel() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/fog0.properties", "format=grid\nyOffset=1\n")
                .png("colormap/fog0.png", 4, 4, (x, y) -> (x * 50) << 16 | (y * 50) << 8));
        // Plains is biome 1 and the height is 64 - 1 = 63, which clamps to row 3: 50 red, 150 green.
        assertEquals(0x329600, colors.fog().color());
    }

    @Test
    void fixedColormapReturnsItsColorForEveryBiomeAndHeight() {
        CustomColors.Colormap fixed = new CustomColors.Colormap(CustomColors.Colormap.FIXED, 0xFF8000, null, 0, 0, 0);
        assertEquals(0xFF8000, fixed.color(3, 0.1f, 0.9f, 70));
        assertEquals(0xFF8000, fixed.color(0, 0f, 0f, 0));
    }

    @Test
    void fixedBlockColormapCoversItsBlocksAndMetadata() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/blocks/leaves.properties", "blocks=18:0-3\nformat=fixed\ncolor=00ff00\n"));
        assertEquals(0x00FF00, colors.block(18, 2, 1, 0.5f, 0.5f, 64));
        assertEquals(-1, colors.block(18, 5, 1, 0.5f, 0.5f, 64));
        assertEquals(-1, colors.block(1, 0, 1, 0.5f, 0.5f, 64));
    }

    @Test
    void fixedSkyColormapIsLoaded() {
        // format=fixed with a color and no image: OptiFine's CustomColormap is valid and draws the color.
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/sky0.properties", "format=fixed\ncolor=ff00ff\n"));
        assertNotNull(colors.sky());
        assertEquals(0xFF00FF, colors.sky().color());
    }

    @Test
    void waterParticlesMultiplyTheWaterColormapByDropWater() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("color.properties", "drop.water=ff8000\n")
                .png("colormap/water.png", 3, 3, CustomColorsTest::rgb));
        int plain = colors.water().color(1, 0.8F, 0.4F, 64);
        int expected = (plain >> 16 & 255) << 16 | (plain >> 8 & 255) * 128 / 255 << 8;
        assertEquals(expected, colors.waterParticle(1, 0.8F, 0.4F, 64));
    }

    @Test
    void waterParticlesWithOnlyDropWaterUseItAlone() {
        CustomColors colors = CustomColors.load(new MemoryAssets().text("color.properties", "drop.water=336699\n"));
        assertEquals(0x336699, colors.waterParticle(1, 0.8F, 0.4F, 64));
    }

    @Test
    void waterParticlesWithOnlyAWaterColormapUseTheColormap() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/water.properties", "format=fixed\ncolor=112233\n"));
        assertEquals(0x112233, colors.waterParticle(1, 0.8F, 0.4F, 64));
    }

    @Test
    void waterParticlesAreLeftAloneWithoutAnyWaterColor() {
        CustomColors colors = CustomColors.load(new MemoryAssets().text("color.properties", "lilypad=5ea334\n"));
        assertEquals(-1, colors.waterParticle(1, 0.8F, 0.4F, 64));
    }
}
