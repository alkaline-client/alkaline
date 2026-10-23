package alkaline.renderer.vulkan.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.world.CustomSky;
import alkaline.renderer.world.Lightmap;
import alkaline.renderer.world.Sky;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.lwjgl.system.MemoryUtil;

/**
 * Minecraft 1.8.9's overworld sky and weather: the clear to the fog color, the sky plane and the sea-level plane
 * (traced per pixel), the sunrise fan, sun, moon (with its phase) and stars (vanilla's 1500 seeded quads), all
 * in gamma space so the blending matches vanilla's framebuffer; and rain and snow around the eye, built per
 * column as EntityRenderer.renderRainSnow builds them.
 */
public final class SkyRenderer implements AutoCloseable {
    /** The world around the eye, as rain and snow read it. */
    public interface Weather {
        /** The y above the topmost block that stops precipitation (blocks movement or is liquid). */
        int precipitationHeight(int x, int z);

        /** Whether the column's biome can rain or snow. */
        boolean precipitates(int x, int z);

        /** The biome temperature at a position (vanilla's getFloatTemperature). */
        float temperature(int x, int y, int z);

        /** Light at a position, {@code sky << 4 | block}, without emission (getCombinedLight(pos, 0)). */
        int light(int x, int y, int z);
    }

    private static final int PLANES_PUSH = 112;
    private static final int GEOMETRY_PUSH = 80;
    private static final int VERTEX_BYTES = 24;
    private static final int STAR_SEED = 10842;
    private static final int WEATHER_RANGE = 10;

    private final VulkanRenderer renderer;
    private final VulkanPipeline planes;
    private final VulkanPipeline planesLinear;
    private final VulkanPipeline blended;
    private final VulkanPipeline additive;
    private final VulkanPipeline weather;
    private final VulkanTexture white;
    private final VulkanTexture sun;
    private final VulkanTexture moon;
    private final VulkanTexture rain;
    private final VulkanTexture snow;
    /** Vanilla's 1500 stars as static triangles, turned by the celestial angle in the vertex shader. */
    private final alkaline.renderer.vulkan.resource.VulkanStaticBuffer stars;
    private final int starVertices;
    private final float[] starMatrix = new float[16];
    private final float[] celestial4 = new float[16];
    private final ByteBuffer planesPush = MemoryUtil.memCalloc(PLANES_PUSH).order(ByteOrder.nativeOrder());
    private final ByteBuffer geometryPush = MemoryUtil.memCalloc(GEOMETRY_PUSH).order(ByteOrder.nativeOrder());
    private final float[] matrix = new float[9];
    private final float[] rainX = new float[1024];
    private final float[] rainZ = new float[1024];
    private final Random random = new Random();
    /** The resource packs' custom sky: layer textures (null where missing) and a pipeline per blend in use. */
    private final CustomSky custom;
    private final VulkanTexture[] layerTextures;
    private final java.util.EnumMap<CustomSky.Blend, VulkanPipeline> layerPipelines = new java.util.EnumMap<>(CustomSky.Blend.class);

    /** @param target offscreen color format the world renders into, or null for the main pass */
    public SkyRenderer(VulkanRenderer renderer, AssetSource assets, RenderTargetFormat target) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        List<AutoCloseable> created = new ArrayList<>();
        try {
            planes = track(created, renderer.createPipeline(fullscreen(target).gammaSpace().build()));
            planesLinear = track(created, renderer.createPipeline(fullscreen(target).build()));
            blended = track(created, renderer.createPipeline(geometry("sky.frag", target, PipelineSpec.Blend.ALPHA, false)));
            additive = track(created, renderer.createPipeline(geometry("sky.frag", target, PipelineSpec.Blend.ADDITIVE, false)));
            weather = track(created, renderer.createPipeline(geometry("weather.frag", target, PipelineSpec.Blend.ALPHA, true)));
            white = track(created, texture(renderer, null, null));
            sun = track(created, texture(renderer, assets, "environment/sun"));
            moon = track(created, texture(renderer, assets, "environment/moon_phases"));
            rain = track(created, texture(renderer, assets, "environment/rain"));
            snow = track(created, texture(renderer, assets, "environment/snow"));
            custom = assets == null ? CustomSky.NONE : CustomSky.load(assets);
            layerTextures = new VulkanTexture[custom.layers().size()];
            for (int index = 0; index < layerTextures.length; index++) {
                CustomSky.Layer layer = custom.layers().get(index);
                layerTextures[index] = track(created, skyTexture(renderer, assets, layer.source()));
                if (layerTextures[index] != null && !layerPipelines.containsKey(layer.blend())) {
                    // "replace" draws unblended, alpha-tested at 0.1 as OptiFine's does.
                    layerPipelines.put(layer.blend(), track(created, renderer.createPipeline(geometry(
                            layer.blend() == CustomSky.Blend.REPLACE ? "sky_cutout.frag" : "sky.frag", target,
                            pipelineBlend(layer.blend()), false))));
                }
            }
        } catch (RuntimeException | Error failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            MemoryUtil.memFree(planesPush);
            MemoryUtil.memFree(geometryPush);
            throw failure;
        }
        float[] quads = generateStars();
        starVertices = quads.length / 12 * 6;
        ByteBuffer starData = ByteBuffer.allocateDirect(starVertices * VERTEX_BYTES).order(ByteOrder.nativeOrder());
        int opaque = 0xFFFFFFFF;
        for (int base = 0; base < quads.length; base += 12) {
            quad(starData, starData.position(), null, quads[base], quads[base + 1], quads[base + 2], 0, 0,
                    quads[base + 3], quads[base + 4], quads[base + 5], 0, 0, quads[base + 6], quads[base + 7],
                    quads[base + 8], 0, 0, quads[base + 9], quads[base + 10], quads[base + 11], 0, 0, opaque);
            starData.position(starData.position() + 6 * VERTEX_BYTES);
        }
        starData.flip();
        alkaline.renderer.vulkan.resource.VulkanStaticBuffer createdStars;
        try {
            createdStars = renderer.createStaticBuffer(starData);
        } catch (RuntimeException | Error failure) {
            snow.close();
            rain.close();
            moon.close();
            sun.close();
            white.close();
            weather.close();
            additive.close();
            blended.close();
            planesLinear.close();
            planes.close();
            MemoryUtil.memFree(planesPush);
            MemoryUtil.memFree(geometryPush);
            throw failure;
        }
        stars = createdStars;
        // EntityRenderer's rainXCoords / rainYCoords: each column's quad faces the eye's column.
        for (int i = 0; i < 32; i++) {
            for (int j = 0; j < 32; j++) {
                float dx = j - 16;
                float dz = i - 16;
                float length = (float) Math.sqrt(dx * dx + dz * dz);
                rainX[i << 5 | j] = -dz / length;
                rainZ[i << 5 | j] = dx / length;
            }
        }
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
        if (resource != null) {
            created.add(resource);
        }
        return resource;
    }

    private static PipelineSpec.Builder fullscreen(RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("fullscreen.vert", "sky_planes.frag")
                .pushConstants(PLANES_PUSH);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder;
    }

    private static PipelineSpec geometry(String fragment, RenderTargetFormat target, PipelineSpec.Blend blend,
                                         boolean depthTest) {
        PipelineSpec.Builder builder = PipelineSpec.builder("sky.vert", fragment)
                .binding(0, VERTEX_BYTES, false)
                .attribute(0, 0, VertexFormat.FLOAT3, 0)
                .attribute(1, 0, VertexFormat.FLOAT2, 12)
                .attribute(2, 0, VertexFormat.UNORM8X4, 20)
                .sampledImage(true)
                .blend(blend)
                .depth(depthTest, false)
                .pushConstants(GEOMETRY_PUSH)
                .gammaSpace();
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder.build();
    }

    /** A texture sampled as raw (gamma) values, repeating; white when absent. */
    private static VulkanTexture texture(VulkanRenderer renderer, AssetSource assets, String path) {
        RgbaImage image = null;
        if (assets != null && path != null) {
            try (InputStream input = assets.open("assets/minecraft/textures/" + path + ".png")) {
                if (input != null) {
                    image = ImageDecoder.decode(input);
                }
            } catch (IOException missing) {
                image = null;
            }
        }
        if (image == null) {
            byte[] pixels = new byte[4];
            java.util.Arrays.fill(pixels, (byte) 0xFF);
            image = new RgbaImage(1, 1, pixels);
        }
        return renderer.createTexture(image, new TextureOptions(ImageSampling.NEAREST, false, true, 1));
    }

    /** RenderGlobal.renderStars: 1500 tries, quads on a radius-100 sphere, each turned randomly. */
    private static float[] generateStars() {
        Random random = new Random(STAR_SEED);
        float[] quads = new float[1500 * 12];
        int count = 0;
        for (int star = 0; star < 1500; star++) {
            double x = random.nextFloat() * 2.0F - 1.0F;
            double y = random.nextFloat() * 2.0F - 1.0F;
            double z = random.nextFloat() * 2.0F - 1.0F;
            double size = 0.15F + random.nextFloat() * 0.1F;
            double length = x * x + y * y + z * z;
            if (length < 1.0D && length > 0.01D) {
                length = 1.0D / Math.sqrt(length);
                x *= length;
                y *= length;
                z *= length;
                double cx = x * 100.0D;
                double cy = y * 100.0D;
                double cz = z * 100.0D;
                double yaw = Math.atan2(x, z);
                double sinYaw = Math.sin(yaw);
                double cosYaw = Math.cos(yaw);
                double pitch = Math.atan2(Math.sqrt(x * x + z * z), y);
                double sinPitch = Math.sin(pitch);
                double cosPitch = Math.cos(pitch);
                double spin = random.nextDouble() * Math.PI * 2.0D;
                double sinSpin = Math.sin(spin);
                double cosSpin = Math.cos(spin);
                for (int corner = 0; corner < 4; corner++) {
                    double a = ((corner & 2) - 1) * size;
                    double b = ((corner + 1 & 2) - 1) * size;
                    double u = a * cosSpin - b * sinSpin;
                    double v = b * cosSpin + a * sinSpin;
                    double up = u * sinPitch;
                    double out = -u * cosPitch;
                    double px = out * sinYaw - v * cosYaw;
                    double pz = v * sinYaw + out * cosYaw;
                    quads[count * 12 + corner * 3] = (float) (cx + px);
                    quads[count * 12 + corner * 3 + 1] = (float) (cy + up);
                    quads[count * 12 + corner * 3 + 2] = (float) (cz + pz);
                }
                count++;
            }
        }
        return java.util.Arrays.copyOf(quads, count * 12);
    }

    // ---- per frame ------------------------------------------------------------------------------------------

    /**
     * Draws the sky behind the world into the current pass: planes, sunrise, sun, moon and stars.
     *
     * @param basis           right, up and forward camera vectors (9 floats), right and up scaled by the half-fov
     *                        tangents, as the plane shader expects
     * @param viewProjection  rotation-and-projection matrix (column-major), camera at the origin
     * @param eyeY            the eye's height in the world
     * @param viewportWidth   the pass's viewport, for the world-space geometry's y-up viewport
     */
    public void drawBackground(VulkanFrame frame, Sky sky, float[] basis, float[] viewProjection, double eyeY,
                               int renderDistanceChunks, int viewportWidth, int viewportHeight) {
        boolean gamma = frame.beginGammaSpace();
        float extent = (renderDistanceChunks * 16 / 64 + 1) * 64 + 64;
        planesPush.clear();
        for (int index = 0; index < 9; index++) {
            planesPush.putFloat((index / 3) * 16 + (index % 3) * 4, basis[index]);
        }
        planesPush.putFloat(44, 0f);
        // Without the upper sky the view clears to the fog color, as OptiFine's Sky: OFF leaves it.
        float[] upper = sky.upperSky ? sky.skyColor : sky.fogColor;
        planesPush.putFloat(48, upper[0]).putFloat(52, upper[1]).putFloat(56, upper[2]).putFloat(60, extent);
        planesPush.putFloat(64, sky.fogColor[0]).putFloat(68, sky.fogColor[1]).putFloat(72, sky.fogColor[2])
                .putFloat(76, sky.fogEnd);
        boolean far = renderDistanceChunks > 4;
        float lowerRed = far ? sky.skyColor[0] * 0.2F + 0.04F : sky.fogColor[0];
        float lowerGreen = far ? sky.skyColor[1] * 0.2F + 0.04F : sky.fogColor[1];
        float lowerBlue = far ? sky.skyColor[2] * 0.6F + 0.1F : sky.fogColor[2];
        planesPush.putFloat(80, lowerRed).putFloat(84, lowerGreen).putFloat(88, lowerBlue)
                .putFloat(92, (float) (eyeY - (sky.flat ? 0.0D : 63.0D)));
        planesPush.putFloat(96, sky.fogDensity).putFloat(100, renderDistanceChunks >= 4 ? 1f : 0f)
                .putFloat(104, 0f).putFloat(108, 0f);
        planesPush.limit(PLANES_PUSH);
        frame.bindPipeline(gamma ? planes : planesLinear);
        frame.pushConstants(planesPush);
        frame.draw(3, 1, 0, 0);
        if (gamma && renderDistanceChunks >= 4) {
            geometryPush.clear();
            for (int index = 0; index < 16; index++) {
                geometryPush.putFloat(index * 4, viewProjection[index]);
            }
            tint(1f);
            geometryPush.limit(GEOMETRY_PUSH);
            // The planes draw with an unflipped viewport (their uv follows the screen); world-space geometry
            // needs the world's y-up one, or it mirrors about the screen center.
            frame.setViewport(0, 0, viewportWidth, viewportHeight, true);
            drawCelestial(frame, sky, viewProjection);
            frame.setViewport(0, 0, viewportWidth, viewportHeight, false);
        }
        // The sea-level plane and void box cover what lies below them, sun and moon included.
        planesPush.putFloat(44, 1f);
        frame.bindPipeline(gamma ? planes : planesLinear);
        frame.pushConstants(planesPush);
        frame.draw(3, 1, 0, 0);
        if (gamma) {
            frame.endGammaSpace();
        }
    }

    private final float[] layerMatrix = new float[9];
    private final float[] sideMatrix = new float[9];
    private final float[] scratchMatrix = new float[9];
    private static final float[] TURN_SKY = axis(0, 1, 0, -90.0F);
    private static final float[] X_PLUS_90 = axis(1, 0, 0, 90.0F);
    private static final float[] X_MINUS_90 = axis(1, 0, 0, -90.0F);
    private static final float[] Z_PLUS_90 = axis(0, 0, 1, 90.0F);
    private static final float[] Z_MINUS_90 = axis(0, 0, 1, -90.0F);

    /**
     * OptiFine's custom sky layers (CustomSky.renderSky), over the vanilla sky after the sunrise glow and before the
     * sun and moon: in the sky's -90 degree turn about y, each active layer turned by its angle about its axis, then
     * CustomSkyLayer.render's six sides, each the 3x2 texture cell {@code side} on the plane y = -100 under its own
     * rotation.
     */
    private void drawCustomLayers(VulkanFrame frame, Sky sky) {
        for (int index = 0; index < layerTextures.length; index++) {
            CustomSky.Layer layer = custom.layers().get(index);
            if (layerTextures[index] == null || !layer.active(sky.worldTime)) {
                continue;
            }
            float brightness = layer.position((int) Math.floor(sky.eyeY)) * layer.weather(sky.rain, sky.thunder)
                    * layer.fade(sky.worldTime);
            brightness = Math.max(0f, Math.min(1f, brightness));
            if (brightness < 1.0E-4F) {
                continue;
            }
            long offset = frame.allocate(36L * VERTEX_BYTES, 16);
            if (offset < 0) {
                return;
            }
            System.arraycopy(TURN_SKY, 0, layerMatrix, 0, 9);
            if (layer.rotate()) {
                multiply(layerMatrix, axis(layer.axis()[0], layer.axis()[1], layer.axis()[2],
                        layer.angle(sky.worldTime, sky.celestialAngle)), scratchMatrix);
                System.arraycopy(scratchMatrix, 0, layerMatrix, 0, 9);
            }
            multiply(layerMatrix, X_PLUS_90, scratchMatrix);
            multiply(scratchMatrix, Z_MINUS_90, layerMatrix);
            // Blender.setupBlend's colors per mode.
            int color = switch (layer.blend()) {
                case ALPHA, ADD, REPLACE -> color(1, 1, 1, brightness);
                case MULTIPLY -> color(brightness, brightness, brightness, brightness);
                default -> color(brightness, brightness, brightness, 1);
            };
            ByteBuffer stream = frame.stream();
            int position = (int) offset;
            position = side(stream, position, layerMatrix, 4, color);
            multiply(layerMatrix, X_PLUS_90, sideMatrix);
            position = side(stream, position, sideMatrix, 1, color);
            multiply(layerMatrix, X_MINUS_90, sideMatrix);
            position = side(stream, position, sideMatrix, 0, color);
            multiply(layerMatrix, Z_PLUS_90, sideMatrix);
            position = side(stream, position, sideMatrix, 5, color);
            multiply(sideMatrix, Z_PLUS_90, scratchMatrix);
            position = side(stream, position, scratchMatrix, 2, color);
            multiply(scratchMatrix, Z_PLUS_90, sideMatrix);
            side(stream, position, sideMatrix, 3, color);
            frame.bindPipeline(layerPipelines.get(layer.blend()));
            frame.bindImage(layerTextures[index]);
            frame.pushConstants(geometryPush);
            frame.bindStreamVertices(0, offset);
            frame.draw(36, 1, 0, 0);
        }
    }

    /** CustomSkyLayer.renderSide: cell {@code side} of the 3x2 texture on the plane y = -100. */
    private static int side(ByteBuffer stream, int position, float[] m, int side, int color) {
        float u = (side % 3) / 3.0F;
        float v = (side / 3) / 2.0F;
        float w = 1.0F / 3.0F;
        return quad(stream, position, m, -100, -100, -100, u, v, -100, -100, 100, u, v + 0.5F,
                100, -100, 100, u + w, v + 0.5F, 100, -100, -100, u + w, v, color);
    }

    private static PipelineSpec.Blend pipelineBlend(CustomSky.Blend blend) {
        return switch (blend) {
            case ADD -> PipelineSpec.Blend.ADDITIVE;
            case ALPHA -> PipelineSpec.Blend.ALPHA;
            case REPLACE -> PipelineSpec.Blend.NONE;
            case SUBTRACT -> PipelineSpec.Blend.INVERT;
            case MULTIPLY -> PipelineSpec.Blend.MULTIPLY;
            case DODGE -> PipelineSpec.Blend.DODGE;
            case BURN -> PipelineSpec.Blend.BURN;
            case SCREEN -> PipelineSpec.Blend.SCREEN;
            case OVERLAY -> PipelineSpec.Blend.OVERLAY;
        };
    }

    /** A custom sky texture by full asset path, filtered smoothly; null when missing. */
    private static VulkanTexture skyTexture(VulkanRenderer renderer, AssetSource assets, String path) {
        try (InputStream input = assets.open(path)) {
            if (input == null) {
                return null;
            }
            return renderer.createTexture(ImageDecoder.decode(input),
                    new TextureOptions(ImageSampling.LINEAR, false, true, 1));
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("Custom sky texture " + path + " unavailable: " + unreadable.getMessage());
            return null;
        }
    }

    private void tint(float value) {
        geometryPush.putFloat(64, value).putFloat(68, value).putFloat(72, value).putFloat(76, value);
    }

    private void drawCelestial(VulkanFrame frame, Sky sky, float[] viewProjection) {
        // The sunrise fan: rotate 90 about X, 180 about Z when the sun is in the other half, then 90 about Z.
        if (sky.sunMoon && sky.sunrise[3] > 0.0F) {
            long offset = frame.allocate(48L * VERTEX_BYTES, 16);
            if (offset >= 0) {
                rotation(matrix, 90, 0, sky.sunriseFlipped ? 270 : 90);
                ByteBuffer stream = frame.stream();
                int position = (int) offset;
                int center = color(sky.sunrise[0], sky.sunrise[1], sky.sunrise[2], sky.sunrise[3]);
                int edge = color(sky.sunrise[0], sky.sunrise[1], sky.sunrise[2], 0.0F);
                float previousX = 0;
                float previousY = 0;
                float previousZ = 0;
                for (int step = 0; step <= 16; step++) {
                    float angle = step * (float) Math.PI * 2.0F / 16.0F;
                    float sin = sin(angle);
                    float cos = cos(angle);
                    float x = sin * 120.0F;
                    float y = cos * 120.0F;
                    float z = -cos * 40.0F * sky.sunrise[3];
                    if (step > 0) {
                        position = vertex(stream, position, matrix, 0, 100, 0, 0, 0, center);
                        position = vertex(stream, position, matrix, previousX, previousY, previousZ, 0, 0, edge);
                        position = vertex(stream, position, matrix, x, y, z, 0, 0, edge);
                    }
                    previousX = x;
                    previousY = y;
                    previousZ = z;
                }
                frame.bindPipeline(blended);
                frame.bindImage(white);
                frame.pushConstants(geometryPush);
                frame.bindStreamVertices(0, offset);
                frame.draw(48, 1, 0, 0);
            }
        }
        drawCustomLayers(frame, sky);
        // Sun, moon and stars turn with the celestial angle: rotate -90 about Y, then the angle about X.
        celestialRotation(matrix, sky.celestialAngle * 360.0F);
        int alpha = color(1, 1, 1, sky.celestialAlpha);
        long offset = sky.sunMoon ? frame.allocate(12L * VERTEX_BYTES, 16) : -1;
        if (offset >= 0) {
            ByteBuffer stream = frame.stream();
            int position = (int) offset;
            float s = 30.0F;
            position = quad(stream, position, matrix, -s, 100, -s, 0, 0, s, 100, -s, 1, 0, s, 100, s, 1, 1,
                    -s, 100, s, 0, 1, alpha);
            frame.bindPipeline(additive);
            frame.bindImage(sun);
            frame.pushConstants(geometryPush);
            frame.bindStreamVertices(0, offset);
            frame.draw(6, 1, 0, 0);
            int phase = sky.moonPhase;
            float u0 = (phase % 4) / 4.0F;
            float v0 = (phase / 4 % 2) / 2.0F;
            float u1 = (phase % 4 + 1) / 4.0F;
            float v1 = (phase / 4 % 2 + 1) / 2.0F;
            float m = 20.0F;
            quad(stream, position, matrix, -m, -100, m, u1, v1, m, -100, m, u0, v1, m, -100, -m, u0, v0,
                    -m, -100, -m, u1, v0, alpha);
            frame.bindImage(moon);
            frame.bindStreamVertices(0, offset + 6L * VERTEX_BYTES);
            frame.draw(6, 1, 0, 0);
        }
        float starBrightness = sky.starBrightness * sky.celestialAlpha;
        if (sky.stars && starBrightness > 0.0F) {
            // The celestial rotation (row-major 3x3) as a column-major 4x4 after the view-projection.
            java.util.Arrays.fill(celestial4, 0f);
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    celestial4[column * 4 + row] = matrix[row * 3 + column];
                }
            }
            celestial4[15] = 1f;
            alkaline.renderer.math.Mat4.multiply(viewProjection, celestial4, starMatrix);
            for (int index = 0; index < 16; index++) {
                geometryPush.putFloat(index * 4, starMatrix[index]);
            }
            tint(starBrightness);
            // RenderGlobal draws the stars with the sun's additive blend.
            frame.bindPipeline(additive);
            frame.bindImage(white);
            frame.pushConstants(geometryPush);
            frame.bindVertices(0, stars, 0);
            frame.draw(starVertices, 1, 0, 0);
            for (int index = 0; index < 16; index++) {
                geometryPush.putFloat(index * 4, viewProjection[index]);
            }
            tint(1f);
        }
    }

    /**
     * Rain and snow around the eye (EntityRenderer.renderRainSnow), drawn last in the world pass with depth test.
     *
     * @param ticks        the renderer's tick counter (rendererUpdateCount), driving the fall animation
     * @param feetX        the viewer's interpolated feet position (columns and fades use it, as vanilla does)
     */
    public void drawWeather(VulkanFrame frame, Weather world, float strength, long ticks, float partialTicks,
                            double feetX, double feetY, double feetZ, double eyeX, double eyeY, double eyeZ,
                            float[] viewProjection, int[] lightmap) {
        if (strength <= 0.0F) {
            return;
        }
        int columns = (2 * WEATHER_RANGE + 1) * (2 * WEATHER_RANGE + 1);
        long offset = frame.allocate((long) columns * 6 * VERTEX_BYTES, 16);
        if (offset < 0) {
            return;
        }
        if (!frame.beginGammaSpace()) {
            return;
        }
        ByteBuffer stream = frame.stream();
        int rainStart = (int) offset;
        int rainEnd = rainStart;
        // Rain first, then snow into the same allocation from the top end, so each draws in one call.
        int snowEnd = (int) offset + columns * 6 * VERTEX_BYTES;
        int snowStart = snowEnd;
        int originX = (int) Math.floor(feetX);
        int originY = (int) Math.floor(feetY);
        int originZ = (int) Math.floor(feetZ);
        int floorY = (int) Math.floor(feetY);
        float time = ticks + partialTicks;
        for (int z = originZ - WEATHER_RANGE; z <= originZ + WEATHER_RANGE; z++) {
            for (int x = originX - WEATHER_RANGE; x <= originX + WEATHER_RANGE; x++) {
                int index = (z - originZ + 16) * 32 + x - originX + 16;
                double dx = rainX[index] * 0.5D;
                double dz = rainZ[index] * 0.5D;
                if (!world.precipitates(x, z) || Double.isNaN(dx)) {
                    continue;
                }
                int height = world.precipitationHeight(x, z);
                int bottom = Math.max(originY - WEATHER_RANGE, height);
                int top = Math.max(originY + WEATHER_RANGE, height);
                int lightY = Math.max(height, floorY);
                if (bottom == top) {
                    continue;
                }
                random.setSeed((long) x * x * 3121 + x * 45238971L ^ (long) z * z * 418711 + z * 13761L);
                double fx = x + 0.5F - feetX;
                double fz = z + 0.5F - feetZ;
                float distance = (float) Math.sqrt(fx * fx + fz * fz) / WEATHER_RANGE;
                int packed = world.light(x, lightY, z);
                double x0 = x - dx + 0.5D - eyeX;
                double z0 = z - dz + 0.5D - eyeZ;
                double x1 = x + dx + 0.5D - eyeX;
                double z1 = z + dz + 0.5D - eyeZ;
                float y0 = (float) (bottom - eyeY);
                float y1 = (float) (top - eyeY);
                if (world.temperature(x, bottom, z) >= 0.15F) {
                    double scroll = ((ticks + x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761 & 31)
                            + (double) partialTicks) / 32.0D * (3.0D + random.nextDouble());
                    float alpha = ((1.0F - distance * distance) * 0.5F + 0.5F) * strength;
                    int color = lightColor(packed >> 4 & 15, packed & 15, false, alpha, lightmap);
                    rainEnd = quad(stream, rainEnd, null, (float) x0, y0, (float) z0, 0, (float) (bottom * 0.25D + scroll),
                            (float) x1, y0, (float) z1, 1, (float) (bottom * 0.25D + scroll),
                            (float) x1, y1, (float) z1, 1, (float) (top * 0.25D + scroll),
                            (float) x0, y1, (float) z0, 0, (float) (top * 0.25D + scroll), color);
                } else {
                    double fall = ((ticks & 511) + partialTicks) / 512.0F;
                    double drift = random.nextDouble() + time * 0.01D * (float) random.nextGaussian();
                    double sway = random.nextDouble() + (time * (float) random.nextGaussian()) * 0.001D;
                    float alpha = ((1.0F - distance * distance) * 0.3F + 0.5F) * strength;
                    int color = lightColor(packed >> 4 & 15, packed & 15, true, alpha, lightmap);
                    snowStart -= 6 * VERTEX_BYTES;
                    quad(stream, snowStart, null, (float) x0, y0, (float) z0, (float) drift,
                            (float) (bottom * 0.25D + fall + sway), (float) x1, y0, (float) z1, (float) (1 + drift),
                            (float) (bottom * 0.25D + fall + sway), (float) x1, y1, (float) z1, (float) (1 + drift),
                            (float) (top * 0.25D + fall + sway), (float) x0, y1, (float) z0, (float) drift,
                            (float) (top * 0.25D + fall + sway), color);
                }
            }
        }
        geometryPush.clear();
        for (int index = 0; index < 16; index++) {
            geometryPush.putFloat(index * 4, viewProjection[index]);
        }
        tint(1f);
        geometryPush.limit(GEOMETRY_PUSH);
        frame.bindPipeline(weather);
        frame.pushConstants(geometryPush);
        if (rainEnd > rainStart) {
            frame.bindImage(rain);
            frame.bindStreamVertices(0, rainStart);
            frame.draw((rainEnd - rainStart) / VERTEX_BYTES, 1, 0, 0);
        }
        if (snowEnd > snowStart) {
            frame.bindImage(snow);
            frame.bindStreamVertices(0, snowStart);
            frame.draw((snowEnd - snowStart) / VERTEX_BYTES, 1, 0, 0);
        }
        frame.endGammaSpace();
    }

    /**
     * The lightmap texel a column's precipitation takes: rain at the column's light, snow lifted three quarters
     * of the way from it toward full light ({@code (light * 3 + 0xF000F0) / 4}), filtered between levels.
     */
    private static int lightColor(int sky, int block, boolean lifted, float alpha, int[] lightmap) {
        float skyLevel = sky;
        float blockLevel = block;
        if (lifted) {
            skyLevel = (sky * 16 * 3 + 240) / 4 / 16.0F;
            blockLevel = (block * 16 * 3 + 240) / 4 / 16.0F;
        }
        int s0 = (int) Math.floor(skyLevel);
        int b0 = (int) Math.floor(blockLevel);
        int s1 = Math.min(15, s0 + 1);
        int b1 = Math.min(15, b0 + 1);
        float ts = skyLevel - s0;
        float tb = blockLevel - b0;
        // The frame's lightmap table (Lightmap.color per level pair, 0xRRGGBB) instead of recomputing it.
        int c00 = lightmap[s0 * 16 + b0];
        int c01 = lightmap[s0 * 16 + b1];
        int c10 = lightmap[s1 * 16 + b0];
        int c11 = lightmap[s1 * 16 + b1];
        int red = Math.round(bilinear(c00 >> 16 & 255, c01 >> 16 & 255, c10 >> 16 & 255, c11 >> 16 & 255, tb, ts));
        int green = Math.round(bilinear(c00 >> 8 & 255, c01 >> 8 & 255, c10 >> 8 & 255, c11 >> 8 & 255, tb, ts));
        int blue = Math.round(bilinear(c00 & 255, c01 & 255, c10 & 255, c11 & 255, tb, ts));
        return red | green << 8 | blue << 16 | Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24;
    }

    private static float bilinear(int a, int b, int c, int d, float tx, float ty) {
        float low = a + (b - a) * tx;
        float high = c + (d - c) * tx;
        return low + (high - low) * ty;
    }

    private static int color(float red, float green, float blue, float alpha) {
        return channel(red) | channel(green) << 8 | channel(blue) << 16 | channel(alpha) << 24;
    }

    private static int channel(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private static float sin(float radians) {
        return (float) Math.sin(radians);
    }

    private static float cos(float radians) {
        return (float) Math.cos(radians);
    }

    /** Rotation matrix (row-major 3x3) for GL rotations applied in order about X, then Y... as listed: Rx(a) Rz(c). */
    private static void rotation(float[] out, float xDegrees, float unusedY, float zDegrees) {
        float[] rx = axis(1, 0, 0, xDegrees);
        float[] rz = axis(0, 0, 1, zDegrees);
        multiply(rx, rz, out);
    }

    /** Ry(-90) * Rx(angle): the sun, moon and stars' frame. */
    private static void celestialRotation(float[] out, float degrees) {
        multiply(axis(0, 1, 0, -90.0F), axis(1, 0, 0, degrees), out);
    }

    private static float[] axis(float x, float y, float z, float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        float t = 1 - c;
        return new float[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    private static void multiply(float[] a, float[] b, float[] out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column]
                        + a[row * 3 + 2] * b[6 + column];
            }
        }
    }

    private static int vertex(ByteBuffer stream, int position, float[] m, float x, float y, float z, float u, float v,
                              int color) {
        float px = x;
        float py = y;
        float pz = z;
        if (m != null) {
            px = m[0] * x + m[1] * y + m[2] * z;
            py = m[3] * x + m[4] * y + m[5] * z;
            pz = m[6] * x + m[7] * y + m[8] * z;
        }
        stream.putFloat(position, px).putFloat(position + 4, py).putFloat(position + 8, pz)
                .putFloat(position + 12, u).putFloat(position + 16, v).putInt(position + 20, color);
        return position + VERTEX_BYTES;
    }

    /** A quad as two triangles (0, 1, 2) and (0, 2, 3). */
    private static int quad(ByteBuffer stream, int position, float[] m,
                            float x0, float y0, float z0, float u0, float v0,
                            float x1, float y1, float z1, float u1, float v1,
                            float x2, float y2, float z2, float u2, float v2,
                            float x3, float y3, float z3, float u3, float v3, int color) {
        position = vertex(stream, position, m, x0, y0, z0, u0, v0, color);
        position = vertex(stream, position, m, x1, y1, z1, u1, v1, color);
        position = vertex(stream, position, m, x2, y2, z2, u2, v2, color);
        position = vertex(stream, position, m, x0, y0, z0, u0, v0, color);
        position = vertex(stream, position, m, x2, y2, z2, u2, v2, color);
        return vertex(stream, position, m, x3, y3, z3, u3, v3, color);
    }

    @Override
    public void close() {
        for (VulkanTexture texture : layerTextures) {
            if (texture != null) {
                texture.close();
            }
        }
        for (VulkanPipeline pipeline : layerPipelines.values()) {
            pipeline.close();
        }
        stars.close();
        snow.close();
        rain.close();
        moon.close();
        sun.close();
        white.close();
        weather.close();
        additive.close();
        blended.close();
        planesLinear.close();
        planes.close();
        MemoryUtil.memFree(planesPush);
        MemoryUtil.memFree(geometryPush);
    }
}
