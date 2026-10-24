package alkaline.renderer.vulkan.world;

import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanStaticBuffer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.ChunkMesh;
import alkaline.renderer.world.ChunkMesher;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The cracks on blocks being dug (RenderGlobal.drawBlockDamageTexture): each block's own terrain quads, meshed
 * the way its section draws them (connections, offsets, culled faces), redrawn with the destroy_stage texture
 * for its stage, blended as vanilla does (src * dst + dst * src) a few millimeters in front of the block. The
 * quads are meshed once per block and neighborhood and cached; a frame only restamps the texture of each quad
 * into the stream.
 */
public final class DamageOverlay implements AutoCloseable {
    /** The blocks around the ones being dug. */
    public interface Blocks {
        /** The block state ({@code id << 4 | metadata}) at a position. */
        int state(int x, int y, int z);
    }

    private static final int PUSH_BYTES = 112;
    /** Blocks around the dug one that can change its shape: the mesher's two-block halo. */
    private static final int REACH = BlockSource.HALO;

    private final VulkanPipeline gamma;
    private final VulkanPipeline linear;
    private final BlockPalette palette;
    private final ChunkMesher mesher = new ChunkMesher();
    private final char[] ids = new char[BlockSource.VOLUME];
    private final byte[] light = new byte[BlockSource.VOLUME];
    private final Map<Long, Cached> cache = new HashMap<>();
    private int frameStamp;
    private Cached[] drawn = new Cached[8];

    /** A dug block's quads, valid while the states around it are the same. */
    private static final class Cached {
        int signature;
        int sectionX;
        int sectionY;
        int sectionZ;
        int[] words = new int[0];
        int stamp;
    }

    /**
     * @param target the offscreen format the world renders into, or null for the swapchain pass
     */
    public DamageOverlay(VulkanRenderer renderer, BlockPalette palette, RenderTargetFormat target) {
        this.palette = Objects.requireNonNull(palette, "palette");
        gamma = renderer.createPipeline(spec(target).gammaSpace().build());
        VulkanPipeline created;
        try {
            created = renderer.createPipeline(spec(target).build());
        } catch (RuntimeException | Error failure) {
            gamma.close();
            throw failure;
        }
        linear = created;
    }

    private static PipelineSpec.Builder spec(RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("damage.vert", "damage.frag")
                .binding(0, ChunkMesh.INTS_PER_VERTEX * Integer.BYTES, false)
                .attribute(0, 0, VertexFormat.UINT4, 0)
                .sampledImages(2)
                .pushConstants(PUSH_BYTES)
                .depth(true, false)
                .blend(PipelineSpec.Blend.OVERLAY);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder;
    }

    /**
     * Draws the cracks of {@code count} blocks (stage 0..9 each) into the current pass, after the terrain.
     *
     * @param scene the world's push constants as {@code renderSolid} left them
     */
    void draw(VulkanFrame frame, ByteBuffer scene, VulkanTexture blocks, VulkanTexture tiles,
              VulkanStaticBuffer quadIndices, double cameraX, double cameraY, double cameraZ, int viewportWidth,
              int viewportHeight, int count, int[] x, int[] y, int[] z, int[] stage, Blocks states) {
        frameStamp++;
        if (drawn.length < count) {
            drawn = new Cached[count];
        }
        int quads = 0;
        for (int index = 0; index < count; index++) {
            Cached block = cached(x[index], y[index], z[index], states);
            drawn[index] = block;
            quads += block.words.length / ChunkMesh.INTS_PER_QUAD;
        }
        if (cache.size() > count) {
            cache.values().removeIf(entry -> entry.stamp != frameStamp);
        }
        if (quads == 0) {
            return;
        }
        long vertexBytes = (long) quads * ChunkMesh.BYTES_PER_QUAD;
        long vertices = frame.allocate(vertexBytes, 16);
        long origins = frame.allocate((long) count * 16, 16);
        if (vertices < 0 || origins < 0) {
            return;
        }
        ByteBuffer stream = frame.stream();
        int position = (int) vertices;
        for (int index = 0; index < count; index++) {
            Cached block = drawn[index];
            int id = palette.destroyLayer(stage[index]);
            int high = (id >> 11) << 27;
            int low = (id & 0x7FF) << 13;
            int[] words = block.words;
            for (int word = 0; word < words.length; word += ChunkMesh.INTS_PER_VERTEX) {
                stream.putInt(position, words[word]);
                stream.putInt(position + 4, words[word + 1] & 0x07FF_FFFF | high);
                stream.putInt(position + 8, words[word + 2] & ~(0x7FF << 13) | low);
                stream.putInt(position + 12, words[word + 3]);
                position += 16;
            }
            int slot = (int) origins + index * 16;
            stream.putFloat(slot, (float) (block.sectionX * 16.0 - cameraX))
                    .putFloat(slot + 4, (float) (block.sectionY * 16.0 - cameraY))
                    .putFloat(slot + 8, (float) (block.sectionZ * 16.0 - cameraZ))
                    .putFloat(slot + 12, 0f);
        }
        boolean inGamma = frame.beginGammaSpace();
        frame.setViewport(0, 0, viewportWidth, viewportHeight, true);
        frame.bindPipeline(inGamma ? gamma : linear);
        frame.bindImage(0, blocks);
        frame.bindImage(1, tiles);
        scene.clear();
        scene.putLong(96, frame.streamAddress(origins));
        scene.putFloat(92, inGamma ? 0f : 1f);
        frame.pushConstants(scene);
        frame.bindIndices(quadIndices, 0);
        frame.bindStreamVertices(0, vertices);
        int firstVertex = 0;
        for (int index = 0; index < count; index++) {
            int blockQuads = drawn[index].words.length / ChunkMesh.INTS_PER_QUAD;
            if (blockQuads > 0) {
                frame.drawIndexed(blockQuads * 6, 1, 0, firstVertex, index);
            }
            firstVertex += blockQuads * 4;
            drawn[index] = null;
        }
        scene.putFloat(92, 0f);
        if (inGamma) {
            frame.endGammaSpace();
        }
    }

    /** The block's quads, meshed again when the states around it changed. */
    private Cached cached(int x, int y, int z, Blocks states) {
        int signature = 1;
        for (int dz = -REACH; dz <= REACH; dz++) {
            for (int dy = -REACH; dy <= REACH; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    signature = signature * 31 + states.state(x + dx, y + dy, z + dz);
                }
            }
        }
        long key = WorldRenderer.key(x, y, z);
        Cached block = cache.get(key);
        if (block == null) {
            block = new Cached();
            cache.put(key, block);
        } else if (block.signature == signature) {
            block.stamp = frameStamp;
            return block;
        }
        block.signature = signature;
        block.stamp = frameStamp;
        block.sectionX = Math.floorDiv(x, 16);
        block.sectionY = Math.floorDiv(y, 16);
        block.sectionZ = Math.floorDiv(z, 16);
        int localX = Math.floorMod(x, 16);
        int localY = Math.floorMod(y, 16);
        int localZ = Math.floorMod(z, 16);
        java.util.Arrays.fill(ids, (char) 0);
        for (int dz = -REACH; dz <= REACH; dz++) {
            for (int dy = -REACH; dy <= REACH; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    ids[BlockSource.index(localX + dx, localY + dy, localZ + dz)] =
                            (char) states.state(x + dx, y + dy, z + dz);
                }
            }
        }
        // Flat lighting: the overlay ignores light and color, so only the shape matters.
        block.words = mesher.meshBlock(block.sectionX, block.sectionY, block.sectionZ, localX, localY, localZ, ids,
                light, palette, false).words();
        return block;
    }

    @Override
    public void close() {
        linear.close();
        gamma.close();
    }
}
