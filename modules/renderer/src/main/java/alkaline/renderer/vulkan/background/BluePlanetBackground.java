package alkaline.renderer.vulkan.background;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * "Blue Planet": deep space, a procedurally generated Earth with a scattering atmosphere, and a large satellite
 * in the foreground. Needs no game assets. On the first frame the planet is baked into one 2048x2048 map (surface
 * and clouds on top, ocean/elevation/lights/ice below); afterwards each frame is one full-screen pass of analytic
 * ray tracing, a few texture reads, an 8-sample atmosphere, and a 4x-supersampled satellite near its silhouette.
 *
 * <p>Composition: the menu sits on the left, so the planet fills the lower right and the satellite floats above
 * its limb, slowly turning its wings toward the sun.
 */
public final class BluePlanetBackground implements MenuBackground {
    public static final String ID = "blue_planet";
    private static final int MAP_SIZE = 2048;
    private static final int PUSH_BYTES = 128;
    private static final float TAN_HALF_FOV = (float) Math.tan(Math.toRadians(25.0));
    private static final float EARTH_RADIUS = 3.4f;
    private static final float EARTH_DISTANCE = 8.2f;
    private static final float SATELLITE_DISTANCE = 4.2f;
    private static final float SATELLITE_SCALE = 0.62f;
    /** Farthest model point from the satellite origin, in model units (wing tips). */
    private static final float SATELLITE_BOUNDS = 3.3f;
    /** Where the sun sits on screen (normalized device coordinates, y up): upper left, clear of the menu. */
    private static final float SUN_NDC_X = -0.70f;
    private static final float SUN_NDC_Y = 0.62f;

    private final VulkanRenderTarget surface;
    private final VulkanPipeline bake;
    private final VulkanPipeline scene;
    private final ByteBuffer push = MemoryUtil.memCalloc(PipelineSpec.MAX_PUSH_CONSTANT_BYTES);
    private final float[] sun = new float[3];
    private final float[] earth = new float[3];
    private final float[] satellite = new float[16];
    private boolean baked;
    private long ticks;

    public BluePlanetBackground(VulkanRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer");
        surface = renderer.createRenderTarget(MAP_SIZE, MAP_SIZE, RenderTargetFormat.RGBA8_UNORM, true, true);
        VulkanPipeline createdBake = null;
        try {
            createdBake = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "blue_planet_bake.frag")
                    .offscreen(RenderTargetFormat.RGBA8_UNORM).pushConstants(4).build());
            scene = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "blue_planet.frag")
                    .offscreen(SCENE_FORMAT).sampledImage(true).pushConstants(PUSH_BYTES).build());
        } catch (RuntimeException | Error failure) {
            if (createdBake != null) {
                createdBake.close();
            }
            surface.close();
            MemoryUtil.memFree(push);
            throw failure;
        }
        bake = createdBake;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public void render(VulkanFrame frame, VulkanRenderTarget target, float partialTick) {
        if (!baked) {
            frame.beginRendering(surface, false, 0f, 0f, 0f, 0f);
            frame.bindPipeline(bake);
            push.clear();
            push.putFloat(0, 0.1871f);
            push.limit(4);
            frame.pushConstants(push);
            frame.draw(3, 1, 0, 0);
            frame.endRendering();
            baked = true;
        }
        float time = (ticks + partialTick) / 20f;
        float aspect = target.width() / (float) target.height();

        // The sun is pinned to a screen position, so it stays in frame at any aspect ratio.
        sun[0] = SUN_NDC_X * aspect * TAN_HALF_FOV;
        sun[1] = SUN_NDC_Y * TAN_HALF_FOV;
        sun[2] = -1f;
        normalizeInPlace(sun);
        // The planet's center sits below and right of the frame; a slow drift keeps the scene alive.
        float drift = (float) Math.sin(time * 0.021) * 0.10f;
        earth[0] = 2.1f * aspect + drift;
        earth[1] = -2.9f + drift * 0.4f;
        earth[2] = -EARTH_DISTANCE;
        float wingAngle = writeSatellite(time, aspect);

        push.clear();
        for (int index = 0; index < 16; index++) {
            push.putFloat(index * 4, satellite[index]);
        }
        push.putFloat(64, sun[0]).putFloat(68, sun[1]).putFloat(72, sun[2]).putFloat(76, time);
        push.putFloat(80, earth[0]).putFloat(84, earth[1]).putFloat(88, earth[2]).putFloat(92, EARTH_RADIUS);
        push.putFloat(96, aspect).putFloat(100, TAN_HALF_FOV);
        push.putFloat(104, time * 0.010f).putFloat(108, time * 0.0035f);
        push.putFloat(112, SATELLITE_BOUNDS).putFloat(116, 1.1f).putFloat(120, 1f / target.height())
                .putFloat(124, wingAngle);
        push.limit(PUSH_BYTES);

        frame.beginRendering(target, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(scene);
        frame.bindImage(surface);
        frame.pushConstants(push);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
    }

    /**
     * Satellite pose, as a geostationary satellite holds it: the Earth deck (+Z) points at the planet's center,
     * north (+Y, the wing axis) leans up the screen, with a slow sway about the Earth axis. Writes the rotation
     * columns and position/scale, and returns the wing angle about +Y that turns the cells toward the sun.
     */
    private float writeSatellite(float time, float aspect) {
        float halfHeight = TAN_HALF_FOV * SATELLITE_DISTANCE;
        float x = 0.52f * aspect * halfHeight + 0.03f * (float) Math.sin(time * 0.07);
        float y = 0.30f * halfHeight + 0.04f * (float) Math.sin(time * 0.11);
        float z = -SATELLITE_DISTANCE;
        float[] axisZ = normalize(new float[]{earth[0] - x, earth[1] - y, earth[2] - z});
        float sway = 0.12f * (float) Math.sin(time * 0.045);
        float[] north = normalize(new float[]{-0.55f + sway, 1f, 0.35f});
        float[] axisX = normalize(cross(north, axisZ));
        float[] axisY = cross(axisZ, axisX);
        float[] m = satellite;
        m[0] = axisX[0];
        m[1] = axisX[1];
        m[2] = axisX[2];
        m[3] = 0f;
        m[4] = axisY[0];
        m[5] = axisY[1];
        m[6] = axisY[2];
        m[7] = 0f;
        m[8] = axisZ[0];
        m[9] = axisZ[1];
        m[10] = axisZ[2];
        m[11] = 0f;
        m[12] = x;
        m[13] = y;
        m[14] = z;
        m[15] = SATELLITE_SCALE;
        float sunX = sun[0] * axisX[0] + sun[1] * axisX[1] + sun[2] * axisX[2];
        float sunZ = sun[0] * axisZ[0] + sun[1] * axisZ[1] + sun[2] * axisZ[2];
        return (float) Math.atan2(sunX, sunZ);
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalizeInPlace(float[] vector) {
        float length = (float) Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        vector[0] /= length;
        vector[1] /= length;
        vector[2] /= length;
    }

    private static float[] normalize(float[] vector) {
        float length = (float) Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        return new float[]{vector[0] / length, vector[1] / length, vector[2] / length};
    }

    @Override
    public void close() {
        scene.close();
        bake.close();
        surface.close();
        MemoryUtil.memFree(push);
    }
}
