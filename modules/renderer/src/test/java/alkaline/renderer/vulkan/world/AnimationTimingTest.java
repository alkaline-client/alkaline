package alkaline.renderer.vulkan.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import alkaline.renderer.asset.AssetSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AnimationTimingTest {
    private static AssetSource meta(String json) {
        return new AssetSource() {
            @Override
            public InputStream open(String path) {
                return json == null ? null : new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String describe() {
                return "test";
            }
        };
    }

    @Test
    void defaultsToEveryFrameInOrder() {
        int[][] timing = WorldRenderer.animationTiming(meta("{\"animation\":{\"frametime\":2}}"), "x", 3);
        assertArrayEquals(new int[]{0, 1, 2}, timing[0]);
        assertArrayEquals(new int[]{2, 2, 2}, timing[1]);
    }

    @Test
    void honorsExplicitFramesAndPerFrameTimes() {
        int[][] timing = WorldRenderer.animationTiming(
                meta("{\"animation\":{\"frames\":[0,1,{\"index\":2,\"time\":5},1,9]}}"), "x", 3);
        assertArrayEquals(new int[]{0, 1, 2, 1}, timing[0]);
        assertArrayEquals(new int[]{1, 1, 5, 1}, timing[1]);
    }

    @Test
    void stripsWithoutAnimationMetadataAreStill() {
        assertNull(WorldRenderer.animationTiming(meta(null), "x", 4));
        assertNull(WorldRenderer.animationTiming(meta("{\"texture\":{}}"), "x", 4));
    }
}
