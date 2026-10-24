package alkaline.renderer.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.RgbaImage;
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

/** Texture animations against OptiFine's TextureAnimations and TextureAnimation. */
class TextureAnimationsTest {
    private static final String TARGET = "assets/minecraft/textures/entity/thing.png";
    private static final String ANIM = "assets/minecraft/mcpatcher/anim/";

    private static final class Pack implements AssetSource {
        private final Map<String, byte[]> files = new HashMap<>();

        Pack text(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        Pack png(String path, int width, int height, IntBinaryOperator argb) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(x, y, argb.applyAsInt(x, y));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            files.put(path, out.toByteArray());
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

    /** A 4x4 target (gray) and a 2x6 strip of three 2x2 frames: red 10, 20, 30. */
    private static Pack pack(String properties) {
        return new Pack().png(TARGET, 4, 4, (x, y) -> 0xFF808080)
                .png(ANIM + "strip.png", 2, 6, (x, y) -> 0xFF000000 | (y / 2 + 1) * 10 << 16)
                .text(ANIM + "thing.properties", "from=./strip.png\nto=textures/entity/thing.png\nx=1\ny=2\nw=2\nh=2\n"
                        + properties);
    }

    /** The red channel at (1, 2), inside the animated region. */
    private static int red(TextureAnimations.Target target, RgbaImage base) {
        return target.compose(base).copyPixels()[(2 * 4 + 1) * 4] & 255;
    }

    private static RgbaImage base() {
        byte[] pixels = new byte[4 * 4 * 4];
        java.util.Arrays.fill(pixels, (byte) 0x80);
        return new RgbaImage(4, 4, pixels);
    }

    @Test
    void framesAdvanceAfterTheirDurationAndTheTargetKeepsItsPixelsUntilThen() {
        TextureAnimations animations = TextureAnimations.load(pack("duration=2\n"));
        TextureAnimations.Target target = animations.target(TARGET);
        assertNotNull(target);
        RgbaImage base = base();
        assertEquals(0x80, red(target, base));
        animations.tick();
        // Frame 0 is never uploaded before its duration ends; frame 1 shows after two ticks.
        assertEquals(0x80, red(target, base));
        animations.tick();
        assertEquals(20, red(target, base));
        animations.tick();
        animations.tick();
        assertEquals(30, red(target, base));
        animations.tick();
        animations.tick();
        assertEquals(10, red(target, base));
        // Outside the region the target is untouched.
        assertEquals(0x80, target.compose(base).copyPixels()[0] & 255);
    }

    @Test
    void tilesAndPerFrameDurationsChooseTheOrder() {
        TextureAnimations animations = TextureAnimations.load(pack("tile.0=2\ntile.1=0\nduration.0=3\n"));
        TextureAnimations.Target target = animations.target(TARGET);
        RgbaImage base = base();
        for (int tick = 0; tick < 3; tick++) {
            animations.tick();
        }
        // After tile 2's three ticks comes tile 0.
        assertEquals(10, red(target, base));
        animations.tick();
        assertEquals(30, red(target, base));
    }

    @Test
    void interpolationBlendsTowardTheNextFrameEverySkipTicks() {
        TextureAnimations animations = TextureAnimations.load(pack("duration=4\ninterpolate=true\nskip=2\n"));
        TextureAnimations.Target target = animations.target(TARGET);
        RgbaImage base = base();
        animations.tick();
        // Step 1 is skipped (1 % 2 != 0).
        assertEquals(0x80, red(target, base));
        animations.tick();
        // Step 2 of 4: halfway from 10 to 20.
        assertEquals(15, red(target, base));
        long halfway = target.state();
        animations.tick();
        assertEquals(15, red(target, base));
        assertEquals(halfway, target.state());
        animations.tick();
        assertEquals(20, red(target, base));
        assertNotEquals(halfway, target.state());
    }

    @Test
    void statesRepeatOverACycle() {
        TextureAnimations animations = TextureAnimations.load(pack("duration=1\n"));
        TextureAnimations.Target target = animations.target(TARGET);
        animations.tick();
        long first = target.state();
        for (int tick = 0; tick < 3; tick++) {
            animations.tick();
        }
        assertEquals(first, target.state());
    }

    @Test
    void invalidAnimationsAreSkipped() {
        // The region does not fit the target.
        assertNull(TextureAnimations.load(pack("").text(ANIM + "thing.properties",
                "from=./strip.png\nto=textures/entity/thing.png\nx=3\ny=3\nw=2\nh=2\n")).target(TARGET));
        // The strip is not a whole number of frames.
        assertTrue(TextureAnimations.load(pack("").text(ANIM + "thing.properties",
                "from=./strip.png\nto=textures/entity/thing.png\nx=0\ny=0\nw=2\nh=4\n")).isEmpty());
        // A missing source.
        assertTrue(TextureAnimations.load(pack("").text(ANIM + "thing.properties",
                "from=./missing.png\nto=textures/entity/thing.png\nx=0\ny=0\nw=2\nh=2\n")).isEmpty());
    }

    @Test
    void pathsResolveLikeTextureUtilsFixResourcePath() {
        String directory = "assets/minecraft/mcpatcher/anim";
        assertEquals("assets/minecraft/mcpatcher/anim/a.png", TextureAnimations.path("./a.png", directory));
        assertEquals("assets/minecraft/mcpatcher/a.png", TextureAnimations.path("~/a.png", directory));
        assertEquals("assets/minecraft/mcpatcher/a.png", TextureAnimations.path("/a.png", directory));
        assertEquals("assets/minecraft/textures/a.png", TextureAnimations.path("textures/a.png", directory));
        assertEquals("assets/minecraft/textures/a.png",
                TextureAnimations.path("assets/minecraft/textures/a.png", directory));
    }

    @Test
    void stripsOfAnotherWidthAreScaledToTheFrame() {
        RgbaImage strip = TextureAnimations.scale(new RgbaImage(2, 2, new byte[]{
                10, 0, 0, (byte) 255, 30, 0, 0, (byte) 255,
                10, 0, 0, (byte) 255, 30, 0, 0, (byte) 255}), 1, 1);
        assertEquals(20, strip.copyPixels()[0]);
    }
}
