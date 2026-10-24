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

    // ---- smoothing and fading --------------------------------------------------------------------------------

    /** Biome = x for x 0..3 (grid columns), plains' climate. */
    private static final CustomColors.Biomes COLUMNS = new CustomColors.Biomes() {
        @Override
        public int biome(int x, int z) {
            return Math.max(0, Math.min(3, x));
        }

        @Override
        public float temperature(int biome, int x, int y, int z) {
            return 0.8F;
        }

        @Override
        public float rainfall(int biome) {
            return 0.4F;
        }
    };

    @Test
    void smoothingAveragesTheSevenBySevenColumnsAroundTheBlock() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/fog0.properties", "format=grid\n")
                .png("colormap/fog0.png", 4, 1, (x, y) -> (x * 50) << 16 | 7));
        // Columns -3..3 read biomes 0,0,0,0,1,2,3: red (0*4 + 50 + 100 + 150) / 7 = 42, rounded down.
        assertEquals(42 << 16 | 7, colors.fog().smooth(COLUMNS, 0, 64, 0, 3));
        // Radius 0 is the block's own column.
        assertEquals(100 << 16 | 7, colors.fog().smooth(COLUMNS, 2, 64, 0, 0));
        CustomColors.Colormap fixed = new CustomColors.Colormap(CustomColors.Colormap.FIXED, 0x123456, null, 0, 0, 0);
        assertEquals(0x123456, fixed.smooth(COLUMNS, 0, 64, 0, 3));
    }

    @Test
    void faderStartsAtItsFirstColorAndMovesByTheShareOfASecond() {
        CustomColors.Fader fader = new CustomColors.Fader();
        fader.fade(1f, 0f, 0f, 1000);
        assertEquals(1f, fader.red, 1e-6f);
        fader.fade(0f, 0f, 1f, 1250);
        assertEquals(0.75f, fader.red, 1e-6f);
        assertEquals(0.25f, fader.blue, 1e-6f);
        // No time passed: no step.
        fader.fade(0f, 0f, 1f, 1250);
        assertEquals(0.75f, fader.red, 1e-6f);
        // A second or more jumps to the target.
        fader.fade(0f, 0f, 1f, 5000);
        assertEquals(0f, fader.red, 1e-6f);
        assertEquals(1f, fader.blue, 1e-6f);
        // Changes under 0.004 in every channel are ignored.
        fader.fade(0.003f, 0f, 1f, 5500);
        assertEquals(0f, fader.red, 1e-6f);
    }

    @Test
    void skyFogAndUnderwaterUseTheSmoothedColormap() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("colormap/fog0.properties", "format=grid\n")
                .png("colormap/fog0.png", 4, 1, (x, y) -> (x * 50) << 16 | 7));
        Sky sky = new Sky();
        sky.customColors = colors;
        sky.biome = 3;
        sky.update(6000, 0f, 0f, 0f, 0.8f, 1f, 0f, 0f, 70.0, 32, Sky.Medium.AIR);
        float alone = sky.fogColor[0];
        sky.biomes = COLUMNS;
        sky.feetX = 0.5;
        sky.feetZ = 0.5;
        sky.update(6000, 0f, 0f, 0f, 0.8f, 1f, 0f, 0f, 70.0, 32, Sky.Medium.AIR);
        // At 32 chunks the fog takes none of the sky color: biome 3's own 150 red against the 7x7 average of 42.
        assertEquals(42f / 150f, sky.fogColor[0] / alone, 0.01f);
    }

    // ---- redstone, XP orbs, dyes, text and maps --------------------------------------------------------------

    @Test
    void redstonePaletteIsIndexedByPower() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .png("colormap/redstone.png", 16, 1, (x, y) -> x * 16 << 16));
        assertEquals(0, colors.redstone(0));
        assertEquals(15 * 16 << 16, colors.redstone(15));
        assertEquals(15 * 16 << 16, colors.redstone(40));
        assertEquals(-1, CustomColors.NONE.redstone(3));
        CustomColors older = CustomColors.load(new MemoryAssets()
                .png("colormap/redstonecolor.png", 16, 1, (x, y) -> 0x0000FF));
        assertEquals(0x0000FF, older.redstone(7));
    }

    @Test
    void xpOrbCyclesThroughItsPaletteWithTheSine() {
        CustomColors colors = CustomColors.load(new MemoryAssets()
                .text("color.properties", "xporb.time=1256\n")
                .png("colormap/xporb.png", 5, 1, (x, y) -> x + 1));
        // sin 0 = 0: the middle entry, round(1 * 4 / 2) = 2.
        assertEquals(3, colors.xpOrb(0f));
        assertEquals(5, colors.xpOrb((float) Math.PI / 2));
        assertEquals(1, colors.xpOrb((float) -Math.PI / 2));
        // 1256 ms per cycle halves the timer's speed: 628 / 1256.
        assertEquals(5f, colors.xpOrbTimer(10f), 1e-5f);
        assertEquals(-1, CustomColors.NONE.xpOrb(0f));
        assertEquals(10f, CustomColors.NONE.xpOrbTimer(10f), 0f);
    }

    @Test
    void sheepAndCollarColorsAreKeyedByDyeName() {
        CustomColors colors = CustomColors.load(new MemoryAssets().text("color.properties",
                "sheep.white=ff0000\nsheep.lightBlue=00ff00\ncollar.black=0000ff\nsheep.nonsense=ffffff\n"));
        assertEquals(0xFF0000, colors.sheep(0));
        assertEquals(0x00FF00, colors.sheep(3));
        assertEquals(-1, colors.sheep(1));
        assertEquals(0x0000FF, colors.collar(15));
        assertEquals(-1, colors.collar(0));
    }

    @Test
    void textCodesCoverTheFormatColorsAndTheirShadows() {
        CustomColors colors = CustomColors.load(new MemoryAssets().text("color.properties",
                "text.code.0=111111\ntext.code.31=222222\ntext.code.32=333333\ntext.xpbar=444444\n"
                        + "text.boss=555555\ntext.sign=666666\n"));
        assertEquals(0x111111, colors.textCode(0));
        assertEquals(0x222222, colors.textCode(31));
        assertEquals(-1, colors.textCode(1));
        assertEquals(-1, colors.textCode(32));
        assertEquals(0x444444, colors.xpBarText());
        assertEquals(0x555555, colors.bossText());
        assertEquals(0x666666, colors.signText());
    }

    @Test
    void mapColorsAreKeyedByOptiFinesNames() {
        CustomColors colors = CustomColors.load(new MemoryAssets().text("color.properties",
                "map.grass=010101\nmap.white=020202\nmap.orange=030303\nmap.lightBlue=040404\n"
                        + "map.podzol=050505\nmap.netherrack=060606\nmap.unknown=070707\n"));
        assertEquals(0x010101, colors.map(1));
        assertEquals(0x020202, colors.map(8));
        assertEquals(0x030303, colors.map(15));
        assertEquals(0x040404, colors.map(17));
        assertEquals(0x050505, colors.map(34));
        assertEquals(0x060606, colors.map(35));
        assertEquals(-1, colors.map(2));
        assertEquals(-1, colors.map(64));
    }
}
