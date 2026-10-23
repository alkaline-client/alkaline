package alkaline.renderer.vulkan.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanStaticBuffer;
import alkaline.renderer.world.Sky;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * Minecraft 1.8.9's clouds from {@code environment/clouds.png} (one texel per cloud cell), with vanilla's two
 * looks: Fast, a flat layer of 8-block cells; Fancy, 12 x 4 x 12-block boxes shaded per face (top 1.0, bottom
 * 0.7, x sides 0.9, z sides 0.8), drawn as vanilla does with a depth pass first so the translucent faces of one
 * cloud never blend over each other. Both drift 0.03 blocks per tick and are fogged with the terrain.
 *
 * <p>Vanilla re-tessellates the clouds around the camera every frame (Fancy: up to 64 chunks of 8x8 cells, each
 * cell with six faces, discarding most of them through the alpha test). Here the exact surface of the whole
 * 256x256 cloud map, top and bottom per cloud cell and sides only where a cloud meets clear sky, is built once
 * into a static buffer in 16x16-cell chunks; a frame draws the few chunks within fog distance, wrapped around the
 * camera, from push constants alone.
 */
public final class CloudRenderer implements AutoCloseable {
    /** Vanilla's look; see {@link CloudRenderer}. */
    public enum Mode { OFF, FAST, FANCY }

    private static final int PUSH_BYTES = 128;
    private static final int MAP = 256;
    private static final int CHUNK = 16;
    private static final int CHUNKS = MAP / CHUNK;
    private static final float FANCY_CELL = 12f;
    private static final float FAST_CELL = 8f;
    private static final float FANCY_THICKNESS = 4f - 9.765625E-4F;
    private static final double DRIFT_PER_TICK = 0.03;

    private final VulkanPipeline depth;
    private final VulkanPipeline color;
    private final VulkanPipeline colorLinear;
    private final VulkanStaticBuffer vertices;
    private final VulkanStaticBuffer indices;
    private final int vertexBytes;
    /** Per chunk: first quad, then bottom, top and side quad counts. */
    private final int[] chunkFirst = new int[CHUNKS * CHUNKS];
    private final int[] chunkBottom = new int[CHUNKS * CHUNKS];
    private final int[] chunkTop = new int[CHUNKS * CHUNKS];
    private final int[] chunkSides = new int[CHUNKS * CHUNKS];
    private final ByteBuffer push = MemoryUtil.memCalloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final float[] cloudColor = new float[3];

    /** @param target offscreen color format the world renders into (with depth), or null for the main pass */
    public CloudRenderer(VulkanRenderer renderer, AssetSource assets, RenderTargetFormat target) throws IOException {
        Objects.requireNonNull(renderer, "renderer");
        boolean[] cloud = loadMap(assets);
        ByteBuffer mesh = build(cloud);
        int quads = mesh.remaining() / (4 * Integer.BYTES);
        vertexBytes = mesh.remaining();
        List<AutoCloseable> created = new ArrayList<>();
        try {
            vertices = track(created, renderer.createStaticBuffer(mesh));
            indices = track(created, renderer.createStaticBuffer(quadIndices(Math.max(1, quads))));
            depth = track(created, renderer.createPipeline(spec(target).blend(PipelineSpec.Blend.DEPTH_ONLY)
                    .depth(true, true).build()));
            color = track(created, renderer.createPipeline(spec(target).blend(PipelineSpec.Blend.ALPHA)
                    .depth(true, false).gammaSpace().build()));
            colorLinear = track(created, renderer.createPipeline(spec(target).blend(PipelineSpec.Blend.ALPHA)
                    .depth(true, false).build()));
        } catch (RuntimeException | Error failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            MemoryUtil.memFree(mesh);
            MemoryUtil.memFree(push);
            throw failure;
        }
        MemoryUtil.memFree(mesh);
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
        created.add(resource);
        return resource;
    }

    private static PipelineSpec.Builder spec(RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("cloud.vert", "cloud.frag")
                .binding(0, Integer.BYTES, false)
                .attribute(0, 0, VertexFormat.UINT1, 0)
                // Fast clouds show from above and below; Fancy's depth pass hides the far faces.
                .pushConstants(PUSH_BYTES);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder;
    }

    /** Opaque cells of clouds.png (alpha above 10%); the vanilla-like default when the texture is missing. */
    private static boolean[] loadMap(AssetSource assets) throws IOException {
        boolean[] cloud = new boolean[MAP * MAP];
        try (InputStream input = assets == null ? null : assets.open("assets/minecraft/textures/environment/clouds.png")) {
            if (input == null) {
                throw new IOException("Missing environment/clouds.png");
            }
            RgbaImage image = ImageDecoder.decode(input);
            if (image.width() != MAP || image.height() != MAP) {
                throw new IOException("clouds.png must be 256x256, found " + image.width() + "x" + image.height());
            }
            byte[] pixels = image.copyPixels();
            for (int index = 0; index < MAP * MAP; index++) {
                cloud[index] = (pixels[index * 4 + 3] & 0xFF) > 25;
            }
        }
        return cloud;
    }

    /** The cloud surface by chunk: bottoms, tops, then sides where a cloud cell borders a clear one. */
    private ByteBuffer build(boolean[] cloud) {
        int quadCount = 0;
        ByteBuffer data = MemoryUtil.memAlloc(4 * Integer.BYTES * MAP * MAP * 6);
        data.order(ByteOrder.nativeOrder());
        for (int chunkZ = 0; chunkZ < CHUNKS; chunkZ++) {
            for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
                int chunk = chunkZ * CHUNKS + chunkX;
                chunkFirst[chunk] = quadCount;
                for (int face = 0; face < 6; face++) {
                    int faceQuads = 0;
                    for (int z = 0; z < CHUNK; z++) {
                        for (int x = 0; x < CHUNK; x++) {
                            int cellX = chunkX * CHUNK + x;
                            int cellZ = chunkZ * CHUNK + z;
                            if (!cloud[cellZ * MAP + cellX] || !exposed(cloud, cellX, cellZ, face)) {
                                continue;
                            }
                            quad(data, x, z, face);
                            faceQuads++;
                        }
                    }
                    quadCount += faceQuads;
                    if (face == 0) {
                        chunkBottom[chunk] = faceQuads;
                    } else if (face == 1) {
                        chunkTop[chunk] = faceQuads;
                    } else {
                        chunkSides[chunk] += faceQuads;
                    }
                }
            }
        }
        return data.flip();
    }

    /** Tops and bottoms always show; a side shows where the neighbor cell (wrapping) is clear. */
    private static boolean exposed(boolean[] cloud, int x, int z, int face) {
        return switch (face) {
            case 2 -> !cloud[z * MAP + Math.floorMod(x - 1, MAP)];
            case 3 -> !cloud[z * MAP + Math.floorMod(x + 1, MAP)];
            case 4 -> !cloud[Math.floorMod(z - 1, MAP) * MAP + x];
            case 5 -> !cloud[Math.floorMod(z + 1, MAP) * MAP + x];
            default -> true;
        };
    }

    /**
     * Four corners wound counter-clockwise seen from outside. Vertex word: corner x (5 bits) and z (5 bits) within
     * the chunk, the top bit, and the face (3 bits: bottom, top, -x, +x, -z, +z).
     */
    private static void quad(ByteBuffer data, int x, int z, int face) {
        // Corners as (dx, dy, dz) in cell units.
        int[][] corners = switch (face) {
            case 0 -> new int[][]{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
            case 1 -> new int[][]{{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}};
            case 2 -> new int[][]{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}};
            case 3 -> new int[][]{{1, 1, 0}, {1, 1, 1}, {1, 0, 1}, {1, 0, 0}};
            case 4 -> new int[][]{{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}};
            default -> new int[][]{{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}};
        };
        for (int[] corner : corners) {
            data.putInt((x + corner[0]) | (z + corner[2]) << 5 | corner[1] << 10 | face << 11);
        }
    }

    private static ByteBuffer quadIndices(int quads) {
        ByteBuffer data = MemoryUtil.memAlloc(quads * 6 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < quads; quad++) {
            int base = quad * 4;
            data.putInt(base).putInt(base + 1).putInt(base + 2).putInt(base + 2).putInt(base + 3).putInt(base);
        }
        ByteBuffer flipped = data.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(flipped.remaining()).order(ByteOrder.nativeOrder());
        copy.put(flipped).flip();
        MemoryUtil.memFree(data);
        return copy;
    }

    /**
     * Draws the clouds into the current pass (after opaque terrain). Fancy suspends linear rendering for its color
     * pass so the blend matches vanilla's framebuffer.
     *
     * @param ticks          the world tick counter driving the drift, plus the partial tick
     * @param cameraX        the eye position in the world
     * @param heightShare    OptiFine's cloud height: clouds at y = 128 + 128 x share
     * @param viewProjection the camera-relative view-projection
     */
    public void draw(VulkanFrame frame, Mode mode, Sky sky, double ticks, double cameraX, double cameraY,
                     double cameraZ, float heightShare, float[] viewProjection) {
        if (mode == Mode.OFF) {
            return;
        }
        boolean fancy = mode == Mode.FANCY;
        float cell = fancy ? FANCY_CELL : FAST_CELL;
        // RenderGlobal.renderClouds(Fancy): texel u at x = cell * u - drift; Fancy's map starts a third of a cell in.
        double mapX = (cameraX + ticks * DRIFT_PER_TICK) / cell;
        double mapZ = cameraZ / cell + (fancy ? 0.33000001311302185 : 0.0);
        float height = (float) (128.33 + heightShare * 128.0 - cameraY);
        sky.cloudColor(cloudColor);
        for (int index = 0; index < 16; index++) {
            push.putFloat(index * 4, viewProjection[index]);
        }
        push.putFloat(76, cell);
        push.putFloat(80, cloudColor[0]).putFloat(84, cloudColor[1]).putFloat(88, cloudColor[2]).putFloat(92, 0.8f);
        push.putFloat(96, sky.fogColor[0]).putFloat(100, sky.fogColor[1]).putFloat(104, sky.fogColor[2])
                .putFloat(108, sky.fogStart);
        boolean exponential = sky.medium != Sky.Medium.AIR;
        push.putFloat(112, sky.fogEnd).putFloat(116, 1f).putFloat(120, exponential ? sky.fogDensity : 0f)
                .putFloat(124, fancy ? FANCY_THICKNESS : 0f);
        // Chunks within the fog (and never more than the map) around the camera's chunk.
        float reach = Math.min(sky.fogEnd + cell * CHUNK, cell * MAP / 2f);
        int radius = (int) Math.ceil(reach / (cell * CHUNK));
        int centerX = (int) Math.floor(mapX / CHUNK);
        int centerZ = (int) Math.floor(mapZ / CHUNK);
        frame.bindVertices(0, vertices, 0);
        frame.bindIndices(indices, 0);
        if (fancy) {
            frame.bindPipeline(depth);
            drawChunks(frame, centerX, centerZ, radius, mapX, mapZ, height, cell, true);
        }
        boolean gamma = frame.beginGammaSpace();
        if (!gamma) {
            // Without a UNORM alias the target encodes linear output: hand the shader linear colors.
            for (int offset : new int[]{80, 84, 88, 96, 100, 104}) {
                push.putFloat(offset, srgbToLinear(push.getFloat(offset)));
            }
        }
        frame.bindPipeline(gamma ? color : colorLinear);
        frame.bindVertices(0, vertices, 0);
        frame.bindIndices(indices, 0);
        drawChunks(frame, centerX, centerZ, radius, mapX, mapZ, height, cell, fancy);
        if (gamma) {
            frame.endGammaSpace();
        }
    }

    private void drawChunks(VulkanFrame frame, int centerX, int centerZ, int radius, double mapX, double mapZ,
                            float height, float cell, boolean allFaces) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                int chunk = Math.floorMod(chunkZ, CHUNKS) * CHUNKS + Math.floorMod(chunkX, CHUNKS);
                int quads = allFaces ? chunkBottom[chunk] + chunkTop[chunk] + chunkSides[chunk] : chunkBottom[chunk];
                if (quads == 0) {
                    continue;
                }
                push.putFloat(64, (float) ((chunkX * CHUNK - mapX) * cell)).putFloat(68, height)
                        .putFloat(72, (float) ((chunkZ * CHUNK - mapZ) * cell));
                push.clear();
                frame.pushConstants(push);
                frame.drawIndexed(quads * 6, 1, chunkFirst[chunk] * 6, 0, 0);
            }
        }
    }

    private static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    @Override
    public void close() {
        colorLinear.close();
        color.close();
        depth.close();
        indices.close();
        vertices.close();
        MemoryUtil.memFree(push);
    }
}
