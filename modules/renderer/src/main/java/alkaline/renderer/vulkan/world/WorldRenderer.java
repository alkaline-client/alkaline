package alkaline.renderer.vulkan.world;

import alkaline.config.Json;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.math.Mat4;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.TextureData;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VertexFormat;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanStaticBuffer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.world.ChunkMesh;
import alkaline.renderer.world.Lightmap;
import alkaline.renderer.world.MaterialPass;
import alkaline.renderer.world.Sky;
import alkaline.renderer.world.TranslucentSorter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU terrain renderer for meshed 16^3 sections.
 *
 * <p>Geometry: each section's quads live in a {@link GeometryPool} page as 16-byte vertices; one shared static
 * index buffer serves opaque and cutout draws, so those sections carry no index data. Translucent quads are
 * drawn through per-section index lists sorted back to front ({@link TranslucentSorter}), re-sorted only when
 * the camera moves to a cell that changes their order, and double-buffered in a second pool so a re-sort never
 * waits for frames in flight. Uploads go through the frame's stream region and recorded buffer copies, never a
 * blocking staging submit. Textures: one 2D array with a layer per block texture and a CPU-built mip chain;
 * repeat addressing lets merged faces tile.
 *
 * <p>Visibility: sections sit in a ring grid around the camera. Each frame a breadth-first walk starts in the
 * camera's section and only steps away from the camera, into sections inside the view frustum, through faces
 * the previous section connects to the one it was entered by (the mesher's flood-fill graph). Sections hidden
 * behind solid terrain are never reached, and the walk visits sections level by level in Manhattan distance,
 * which is exactly the order translucent geometry needs (reversed) and a good front-to-back order for opaque
 * geometry, with no sorting.
 *
 * <p>Drawing: per frame, the visible list becomes indirect draw records in the stream region (per section, only
 * face directions that can point at the camera); each pass then costs one indirect call per geometry page.
 * Section origins are camera-relative, subtracted in double precision on the CPU and read per draw through the
 * instance index, so far-from-origin worlds keep sub-millimeter vertex precision. Devices without
 * multi-draw-indirect replay the same records as direct draws.
 *
 * <p>Usage per frame, on the render thread: {@link #prepareUploads} before acquiring the frame; then, after
 * acquiring and before any pass, {@link #prepare} (after {@link #animate} for the tick); then {@link #render} inside a pass whose
 * target has depth.
 */
public final class WorldRenderer implements AutoCloseable {
    private static final int PUSH_BYTES = 112;
    /** Frame table slots: one per frame in flight, plus one spare. */
    private static final int TABLE_SLOTS = 3;
    private static final int MAX_QUADS_PER_DRAW = 16 * 16 * 16 * 3;
    private static final int COMMAND_BYTES = 20;
    /** Stream bytes per frame spent on new section geometry; the rest of the region stays free for others. */
    private static final long UPLOAD_BUDGET_BYTES = 3L << 20;
    /** Translucent quads re-sorted per frame at most (24 bytes each through the stream). */
    private static final int SORT_BUDGET_QUADS = 48_000;
    private static final int INDEX_BYTES_PER_QUAD = 6 * Integer.BYTES;
    private static final long GEOMETRY_PAGE_BYTES = 64L << 20;
    private static final long INDEX_PAGE_BYTES = 4L << 20;
    /** Initial batches per pass; the translucent pass can switch pages often and grows past it. */
    private static final int INITIAL_BATCHES = 64;
    /** Steps between neighboring sections per face code: -X, +X, -Y, +Y, -Z, +Z. */
    private static final int[] STEP_X = {-1, 1, 0, 0, 0, 0};
    private static final int[] STEP_Y = {0, 0, -1, 1, 0, 0};
    private static final int[] STEP_Z = {0, 0, 0, 0, -1, 1};

    /** Linear daylight colors (vanilla plains at noon, converted from sRGB). */

    private final VulkanRenderer renderer;
    private final VulkanTexture blocks;
    /** Connected-texture tiles beyond the first array (a placeholder layer when there are none). */
    private final VulkanTexture tiles;
    private final VulkanStaticBuffer quadIndices;
    private final VulkanPipeline opaque;
    private final VulkanPipeline cutout;
    private final VulkanPipeline translucent;
    private final SkyRenderer sky;
    /** Sky, fog and celestial state; defaults to a clear noon in the plains until a game updates it. */
    private final Sky skyState = new Sky();
    private final float[] skyBasis = new float[9];
    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    private SkyRenderer.Weather weatherSource;
    private float weatherStrength;
    private long weatherTicks;
    private float weatherPartial;
    private double feetX;
    private double feetY;
    private double feetZ;
    private final GeometryPool pool;
    private final GeometryPool indexPool;
    /**
     * Per-frame table the shaders read: 256 lightmap texels (0x00BBGGRR sRGB, index sky * 16 + block), then for
     * every block texture the array layer to sample this frame (animated textures point at their current frame).
     */
    private final VulkanStaticBuffer tableBuffer;
    private final int[] frameTable;
    /** The same lightmap as 0xRRGGBB, for CPU-side lighting (rain and snow, particles, models). */
    private final int[] lightmapRgb = new int[256];
    private final int tableBytes;
    private boolean lightmapDirty = true;
    private final boolean indirect;
    private final Animation[] animations;
    private final Map<Long, GpuSection> sections = new HashMap<>();
    private final ArrayDeque<PendingUpload> uploads = new ArrayDeque<>();
    private final ArrayDeque<ChunkMesh> waiting = new ArrayDeque<>();
    private final ByteBuffer scene = MemoryUtil.memCalloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] viewProjection = new float[16];
    private final float[] planes = new float[24];
    /** Per plane: its distance term plus the normal's reach across a section (see {@link #inFrustum}). */
    private final float[] planeReach = new float[6];
    /** What the last visibility walk saw: unchanged camera, frustum and grid let the next frame reuse it. */
    private final float[] walkedPlanes = new float[24];
    private double walkedX = Double.NaN;
    private double walkedY;
    private double walkedZ;
    private long walkedVersion = -1;
    /** Bumped whenever a slot of the grid changes (sections placed, removed or given new geometry). */
    private long gridVersion;
    private final TranslucentSorter sorter = new TranslucentSorter();

    // Ring grid of sections around the camera. The visibility walk reads only the flat per-slot arrays, which
    // stay contiguous in memory; it never dereferences a section object.
    // Per slot: the section's key ({@link #key}, EMPTY_SLOT when free), its visibility with GEOMETRY set when it
    // has something to draw, and the walk's mark (the stamp of the frame that reached it, times 64, plus the faces
    // it was entered through), so one neighbor costs two array reads.
    private GpuSection[] grid = new GpuSection[0];
    private long[] slotKey = new long[0];
    private long[] slotVisibility = new long[0];
    private int[] slotMark = new int[0];
    private int[] walk = new int[256];
    private int gridWidth = 1;
    /** gridWidth is a power of two, so ring coordinates wrap with a mask instead of a division. */
    private int gridMask;
    private int gridHeight;
    private int minSectionY;
    private int maxSectionY = -1;

    /** Sections with geometry the walk reached, nearest first; a section's index here is its draw slot. */
    private GpuSection[] visible = new GpuSection[256];
    private int visibleCount;
    private int reachedCount;
    private int stamp;
    private boolean indexGrowthNeeded;

    // Per-frame draw data written by prepare() and read by render().
    /** Per pass: the first command of each batch, as an index into {@link #commands}. */
    private final int[][] batchOffset = new int[3][INITIAL_BATCHES];
    /**
     * This frame's draw records (VkDrawIndexedIndirectCommand, five ints each), built on the CPU and copied to
     * the stream in one write. The stream is write-combined or device memory: never read it back.
     */
    private int[] commands = new int[5 * 1024];
    private int commandCount;
    private long commandStream;
    private final int[][] batchCount = new int[3][INITIAL_BATCHES];
    private final int[][] batchVertexPage = new int[3][INITIAL_BATCHES];
    private final int[][] batchIndexPage = new int[3][INITIAL_BATCHES];
    private final int[] batches = new int[3];
    private boolean prepared;
    private int viewportWidth;
    private int viewportHeight;
    private float[] skyForward = new float[3];
    private float skyFov;
    private int lastDraws;
    private long lastQuads;
    private int lastSorts;

    private float sunBrightness = 1f;
    private float torchFlicker;
    private float gamma;
    private float nightVision;

    /** Draw statistics of the last frame. */
    public record Stats(int loadedSections, int visibleSections, int drawnSections, int draws, long quads,
                        long geometryBytes, long indexBytes, int sortsLastFrame, int pendingUploads,
                        boolean indirect, double averagePrepareMicros) {
    }

    private record PendingUpload(ChunkMesh mesh, GeometryPool.Allocation allocation) {
    }

    /**
     * An animated block texture: meshes sample {@code layer}; every frame's art is kept in spare layers from
     * {@code firstFrameLayer}, and the current one is copied over {@code layer} when the animation advances.
     * {@code frames}/{@code ticks} are the .mcmeta sequence: which frame to show and for how many game ticks.
     */
    private static final class Animation {
        final int layer;
        final int firstFrameLayer;
        final int[] frames;
        final int[] ticks;
        final int period;
        int shown;

        Animation(int layer, int firstFrameLayer, int[] frames, int[] ticks) {
            this.layer = layer;
            this.firstFrameLayer = firstFrameLayer;
            this.frames = frames;
            this.ticks = ticks;
            int total = 0;
            for (int duration : ticks) {
                total += duration;
            }
            period = Math.max(1, total);
        }

        /** The same animation, not yet shown. */
        Animation copy() {
            return new Animation(layer, firstFrameLayer, frames, ticks);
        }
    }

    /** A decoded block texture: its square frames (one unless animated) and the .mcmeta sequence. */
    private record BlockTexture(List<RgbaImage> frames, int[] sequence, int[] ticks) {
    }

    private static final class GpuSection {
        final int x;
        final int y;
        final int z;
        final int[] rangeStart = new int[ChunkMesh.RANGES];
        final int[] rangeCount = new int[ChunkMesh.RANGES];
        GeometryPool.Allocation allocation;
        GeometryPool.Allocation indices;
        short[] translucentCells = new short[0];
        /** Until meshed, a section is assumed to see through; its neighbors stay reachable. */
        long visibility = ChunkMesh.ALL_CONNECTED;
        int sortedKey = -1;
        int drawSlot;

        GpuSection(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Block textures and the sky, decoded, laid out in their arrays and mipped by {@link #loadTextures}, which needs no
     * Vulkan and runs on any thread. Immutable: several renderers may be built from one.
     */
    public static final class Textures {
        private final TextureData blocks;
        private final TextureData tiles;
        private final Animation[] animations;
        private final int[] frameTable;
        private final SkyRenderer.Images sky;

        private Textures(TextureData blocks, TextureData tiles, Animation[] animations, int[] frameTable,
                         SkyRenderer.Images sky) {
            this.blocks = blocks;
            this.tiles = tiles;
            this.animations = animations;
            this.frameTable = frameTable;
            this.sky = sky;
        }
    }

    /** Reads, lays out and mips the block textures named by the palette, and the sky's; any thread. */
    public static Textures loadTextures(AssetSource assets, List<String> textureNames) {
        // Texture i is a layer of the first array (its first frame), spare animation frames following, until the
        // array is full; later textures (connected-texture tiles, registered after every model texture) go to the
        // second array, unanimated. The frame table maps each texture to array << 16 | layer. Layers of an array
        // share one size, its largest frame (water and lava flow are 32 px); smaller art is scaled up by whole
        // texels, which samples identically.
        // Decoding is most of the work and each texture is independent; the list keeps the palette's order.
        List<BlockTexture> loaded = textureNames.parallelStream().map(name -> loadBlockTexture(assets, name)).toList();
        int split = 0;
        int firstLayers = 0;
        while (split < loaded.size()) {
            int frames = loaded.get(split).frames().size();
            int needed = 1 + (frames > 1 ? frames : 0);
            if (firstLayers + needed > ChunkMesh.MAX_LAYERS) {
                break;
            }
            firstLayers += needed;
            split++;
        }
        if (loaded.size() - split > ChunkMesh.MAX_LAYERS) {
            throw new IllegalArgumentException("Block textures exceed two arrays of " + ChunkMesh.MAX_LAYERS + " layers.");
        }
        int layerSize = 16;
        int tileSize = 16;
        for (int index = 0; index < loaded.size(); index++) {
            int width = loaded.get(index).frames().getFirst().width();
            if (index < split) {
                layerSize = Math.max(layerSize, width);
            } else {
                tileSize = Math.max(tileSize, width);
            }
        }
        List<RgbaImage> layers = new ArrayList<>(split);
        for (int index = 0; index < split; index++) {
            BlockTexture texture = loaded.get(index);
            layers.add(upscale(texture.frames().get(texture.sequence()[0]), layerSize));
        }
        List<Animation> animated = new ArrayList<>();
        for (int index = 0; index < split; index++) {
            BlockTexture texture = loaded.get(index);
            if (texture.frames().size() > 1) {
                animated.add(new Animation(index, layers.size(), texture.sequence(), texture.ticks()));
                for (RgbaImage frame : texture.frames()) {
                    layers.add(upscale(frame, layerSize));
                }
            }
        }
        List<RgbaImage> tileLayers = new ArrayList<>(Math.max(1, loaded.size() - split));
        for (int index = split; index < loaded.size(); index++) {
            tileLayers.add(upscale(loaded.get(index).frames().getFirst(), tileSize));
        }
        if (tileLayers.isEmpty()) {
            tileLayers.add(upscale(loadBlockTexture(null, "missingno").frames().getFirst(), tileSize));
        }
        int[] frameTable = new int[256 + textureNames.size()];
        for (int texture = 0; texture < textureNames.size(); texture++) {
            frameTable[256 + texture] = texture < split ? texture : 1 << 16 | (texture - split);
        }
        return new Textures(
                TextureData.array(layers, new TextureOptions(ImageSampling.NEAREST, true, true,
                        Integer.numberOfTrailingZeros(layerSize) + 1)),
                TextureData.array(tileLayers, new TextureOptions(ImageSampling.NEAREST, true, true,
                        Integer.numberOfTrailingZeros(tileSize) + 1)),
                animated.toArray(new Animation[0]), frameTable, SkyRenderer.loadImages(assets));
    }

    /** As {@link #WorldRenderer(VulkanRenderer, Textures, RenderTargetFormat)}, preparing the textures here. */
    public WorldRenderer(VulkanRenderer renderer, AssetSource assets, List<String> textureNames,
                         RenderTargetFormat target) {
        this(renderer, loadTextures(assets, textureNames), target);
    }

    /**
     * Uploads prepared textures and creates the pipelines; the render thread's share of building a world view.
     *
     * @param target offscreen color format to render into (targets must be created with depth), or null for
     *               the main swapchain pass
     */
    public WorldRenderer(VulkanRenderer renderer, Textures textures, RenderTargetFormat target) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(textures, "textures");
        animations = new Animation[textures.animations.length];
        for (int index = 0; index < animations.length; index++) {
            animations[index] = textures.animations[index].copy();
        }
        frameTable = textures.frameTable.clone();
        tableBytes = (frameTable.length * Integer.BYTES + 255) & ~255;
        // -Dalkaline.world.directDraws=true exercises the fallback for devices without multi-draw-indirect.
        indirect = renderer.supportsMultiDrawIndirect() && !Boolean.getBoolean("alkaline.world.directDraws");
        List<AutoCloseable> created = new ArrayList<>();
        try {
            blocks = track(created, renderer.createTexture(textures.blocks));
            tiles = track(created, renderer.createTexture(textures.tiles));
            quadIndices = track(created, renderer.createStaticBuffer(quadIndexData()));
            opaque = track(created, renderer.createPipeline(terrainSpec("world_opaque.frag", target)
                    .depth(true, true).build()));
            cutout = track(created, renderer.createPipeline(terrainSpec("world_cutout.frag", target)
                    .depth(true, true).build()));
            translucent = track(created, renderer.createPipeline(terrainSpec("world_translucent.frag", target)
                    .depth(true, false).blend(PipelineSpec.Blend.ALPHA).build()));
            sky = track(created, new SkyRenderer(renderer, textures.sky, target));
            tableBuffer = track(created, renderer.createAddressableDeviceBuffer((long) tableBytes * TABLE_SLOTS));
            pool = track(created, new GeometryPool(renderer, ChunkMesh.BYTES_PER_QUAD, GEOMETRY_PAGE_BYTES));
            indexPool = track(created, new GeometryPool(renderer, INDEX_BYTES_PER_QUAD, INDEX_PAGE_BYTES));
        } catch (RuntimeException | Error failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            MemoryUtil.memFree(scene);
            throw failure;
        }
        skyState.update(6000L, 0f, 0f, 0f, 0.8f, 0f, 0f, 1f, 80.0, 8, Sky.Medium.AIR);
    }

    private static <T extends AutoCloseable> T track(List<AutoCloseable> created, T resource) {
        created.add(resource);
        return resource;
    }

    private static PipelineSpec.Builder terrainSpec(String fragment, RenderTargetFormat target) {
        PipelineSpec.Builder builder = PipelineSpec.builder("world.vert", fragment)
                .binding(0, ChunkMesh.INTS_PER_VERTEX * Integer.BYTES, false)
                .attribute(0, 0, VertexFormat.UINT4, 0)
                .cullBackFaces(true)
                .sampledImages(2)
                .pushConstants(PUSH_BYTES);
        if (target != null) {
            builder.offscreen(target, true);
        }
        return builder;
    }

    /**
     * A block texture's frames, by path under {@code assets/minecraft/textures/} (for example {@code
     * blocks/stone}). Vertical strips with a {@code .mcmeta} animation section are split into square frames and
     * keep its frame order and timing (default: every frame in order, one tick each); without one, only the first
     * square is used, as vanilla does. A checker stands in for missing art ({@code missingno}).
     */
    private static BlockTexture loadBlockTexture(AssetSource assets, String name) {
        // Connected-texture tiles are full asset paths; model textures are under textures/.
        String path = name.startsWith("assets/") ? name : "assets/minecraft/textures/" + name + ".png";
        if (assets != null && !name.equals("missingno")) {
            try (InputStream input = assets.open(path)) {
                RgbaImage image = input == null ? null : ImageDecoder.decode(input);
                int size = image == null ? 0 : image.width();
                if (image != null && size > 0 && image.height() >= size && image.height() % size == 0) {
                    int count = image.height() / size;
                    byte[] pixels = image.copyPixels();
                    int frameBytes = size * size * 4;
                    List<RgbaImage> frames = new ArrayList<>(count);
                    for (int frame = 0; frame < count; frame++) {
                        frames.add(new RgbaImage(size, size,
                                Arrays.copyOfRange(pixels, frame * frameBytes, (frame + 1) * frameBytes)));
                    }
                    int[][] timing = count > 1 ? animationTiming(assets, path + ".mcmeta", count) : null;
                    if (timing == null) {
                        return new BlockTexture(List.of(frames.getFirst()), new int[]{0}, new int[]{1});
                    }
                    return new BlockTexture(frames, timing[0], timing[1]);
                }
            } catch (IOException missing) {
                // Fall through to the placeholder.
            }
        }
        byte[] pixels = new byte[16 * 16 * 4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int offset = (y * 16 + x) * 4;
                boolean magenta = ((x >> 3) + (y >> 3) & 1) == 0;
                pixels[offset] = (byte) (magenta ? 0xF8 : 0);
                pixels[offset + 2] = (byte) (magenta ? 0xF8 : 0);
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return new BlockTexture(List.of(new RgbaImage(16, 16, pixels)), new int[]{0}, new int[]{1});
    }

    /** Nearest-neighbor enlargement by a whole factor (no-op at the target size). */
    private static RgbaImage upscale(RgbaImage image, int size) {
        if (image.width() == size) {
            return image;
        }
        int factor = size / image.width();
        byte[] source = image.copyPixels();
        byte[] target = new byte[size * size * 4];
        int row = size * 4;
        for (int y = 0; y < image.height(); y++) {
            // Widen one source row, then repeat it factor times.
            int first = y * factor * row;
            for (int x = 0; x < size; x++) {
                System.arraycopy(source, (y * image.width() + x / factor) * 4, target, first + x * 4, 4);
            }
            for (int copy = 1; copy < factor; copy++) {
                System.arraycopy(target, first, target, first + copy * row, row);
            }
        }
        return new RgbaImage(size, size, target);
    }

    /**
     * Reads an .mcmeta {@code animation} section: {@code frametime} (ticks, default 1) and an optional
     * {@code frames} list of indices or {@code {index, time}} objects. Returns {sequence, ticks}, or null when
     * the texture is not animated or the metadata is unusable.
     */
    static int[][] animationTiming(AssetSource assets, String metaPath, int frameCount) {
        Object root;
        try (InputStream input = assets.open(metaPath)) {
            if (input == null) {
                return null;
            }
            root = Json.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException absent) {
            return null;
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("animation") instanceof Map<?, ?> animation)) {
            return null;
        }
        int frameTime = animation.get("frametime") instanceof Number number ? Math.max(1, number.intValue()) : 1;
        List<int[]> entries = new ArrayList<>();
        if (animation.get("frames") instanceof List<?> frames) {
            for (Object entry : frames) {
                if (entry instanceof Number number) {
                    entries.add(new int[]{number.intValue(), frameTime});
                } else if (entry instanceof Map<?, ?> object && object.get("index") instanceof Number number) {
                    int time = object.get("time") instanceof Number t ? Math.max(1, t.intValue()) : frameTime;
                    entries.add(new int[]{number.intValue(), time});
                }
            }
        } else {
            for (int frame = 0; frame < frameCount; frame++) {
                entries.add(new int[]{frame, frameTime});
            }
        }
        entries.removeIf(entry -> entry[0] < 0 || entry[0] >= frameCount);
        if (entries.size() < 2) {
            return null;
        }
        int[] sequence = new int[entries.size()];
        int[] ticks = new int[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            sequence[index] = entries.get(index)[0];
            ticks[index] = entries.get(index)[1];
        }
        return new int[][]{sequence, ticks};
    }

    /**
     * Advances animated block textures to the given game tick. Meshes keep the texture's index; the frame table
     * maps it to the layer holding the current frame's art, so animation copies no image data and needs no
     * barriers.
     */
    public void animate(long tick) {
        for (Animation animation : animations) {
            long position = Math.floorMod(tick, (long) animation.period);
            int step = 0;
            while (position >= animation.ticks[step]) {
                position -= animation.ticks[step];
                step++;
            }
            if (step != animation.shown || frameTable[256 + animation.layer] == animation.layer) {
                frameTable[256 + animation.layer] = animation.firstFrameLayer + animation.frames[step];
                animation.shown = step;
            }
        }
    }

    private static ByteBuffer quadIndexData() {
        ByteBuffer data = ByteBuffer.allocateDirect(MAX_QUADS_PER_DRAW * 6 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int quad = 0; quad < MAX_QUADS_PER_DRAW; quad++) {
            int base = quad * 4;
            data.putInt(base).putInt(base + 1).putInt(base + 2).putInt(base + 2).putInt(base + 3).putInt(base);
        }
        return data.flip();
    }

    /** A free grid slot's key: x = -2^21 lies past the world border, so no section has it. */
    private static final long EMPTY_SLOT = Long.MIN_VALUE;
    /** Above the 36 bits of face connections in a slot's visibility: the section has geometry. */
    private static final long GEOMETRY = Long.MIN_VALUE;
    /** Walk stamps stay below this, so a stamp times 64 fits an int; the marks are cleared when it wraps. */
    private static final int STAMP_LIMIT = 1 << 25;

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) z & 0x3FFFFF) << 20 | ((long) y & 0xFFFFF);
    }

    // ---- lighting -----------------------------------------------------------------------------------------

    /**
     * Lightmap inputs (see {@link alkaline.renderer.world.Lightmap}): sun brightness 0.2 (night) .. 1 (day),
     * vanilla's torch flicker, the gamma setting 0 (moody) .. 1 (bright), and night vision 0 .. 1.
     */
    public void setLighting(float sunBrightness, float torchFlicker, float gamma, float nightVision) {
        lightmapDirty |= sunBrightness != this.sunBrightness || torchFlicker != this.torchFlicker
                || gamma != this.gamma || nightVision != this.nightVision;
        this.sunBrightness = sunBrightness;
        this.torchFlicker = torchFlicker;
        this.gamma = gamma;
        this.nightVision = nightVision;
    }

    // ---- section residency --------------------------------------------------------------------------------

    /**
     * Sets the vertical section range and the horizontal radius (in sections) the grid must hold around any
     * camera. Re-indexes resident sections when the shape changes; call outside a frame.
     */
    public void configure(int minSectionY, int maxSectionY, int radius) {
        int width = Integer.highestOneBit(2 * radius + 2) << 1;
        if (minSectionY == this.minSectionY && maxSectionY == this.maxSectionY && width == gridWidth) {
            return;
        }
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        gridWidth = width;
        gridMask = width - 1;
        gridHeight = maxSectionY - minSectionY + 1;
        int slots = width * width * gridHeight;
        grid = new GpuSection[slots];
        slotKey = new long[slots];
        slotVisibility = new long[slots];
        slotMark = new int[slots];
        walk = new int[slots];
        Arrays.fill(slotKey, EMPTY_SLOT);
        gridVersion++;
        for (GpuSection section : sections.values()) {
            occupy(section);
        }
    }

    private void occupy(GpuSection section) {
        int slot = slot(section.x, section.y, section.z);
        if (slot >= 0) {
            grid[slot] = section;
            gridVersion++;
            slotKey[slot] = key(section.x, section.y, section.z);
            slotVisibility[slot] = slotVisibility(section);
            slotMark[slot] = 0;
        }
    }

    /** The slot holding section (x, y, z), or -1. */
    private int occupied(int x, int y, int z) {
        int slot = slot(x, y, z);
        return slot >= 0 && slotKey[slot] == key(x, y, z) ? slot : -1;
    }

    /** The walk's view of a section: its face connections, and GEOMETRY when it draws something. */
    private static long slotVisibility(GpuSection section) {
        return section.visibility & ~GEOMETRY | (section.allocation != null ? GEOMETRY : 0L);
    }

    private int slot(int x, int y, int z) {
        if (y < minSectionY || y > maxSectionY) {
            return -1;
        }
        return ((x & gridMask) * gridWidth + (z & gridMask)) * gridHeight + y - minSectionY;
    }


    /**
     * Declares a section that will be meshed: until its mesh arrives it draws nothing but lets the visibility
     * walk pass through, so sections behind it are not hidden by a gap in loading.
     */
    public void request(int x, int y, int z) {
        sections.computeIfAbsent(key(x, y, z), ignored -> {
            GpuSection section = new GpuSection(x, y, z);
            occupy(section);
            return section;
        });
    }

    /** Queues a mesh to replace its section; geometry becomes visible after the upload is recorded. */
    public void submit(ChunkMesh mesh) {
        waiting.add(Objects.requireNonNull(mesh, "mesh"));
    }

    /** Drops a section and frees its geometry once no frame can read it. */
    public void remove(int x, int y, int z) {
        GpuSection section = sections.remove(key(x, y, z));
        if (section == null) {
            return;
        }
        int slot = slot(x, y, z);
        if (slot >= 0 && grid[slot] == section) {
            grid[slot] = null;
            slotKey[slot] = EMPTY_SLOT;
            gridVersion++;
        }
        if (section.allocation != null) {
            pool.free(section.allocation);
        }
        if (section.indices != null) {
            indexPool.free(section.indices);
        }
    }

    public boolean contains(int x, int y, int z) {
        GpuSection section = sections.get(key(x, y, z));
        return section != null && section.allocation != null;
    }

    public int loadedSections() {
        return sections.size();
    }

    /** Meshes queued or reserved but not yet copied to the GPU. */
    public int pendingUploads() {
        return waiting.size() + uploads.size();
    }

    /**
     * Reserves geometry for queued meshes within this frame's upload budget and grows the index pool when a
     * re-sort ran out of room; may create pages, so call outside a frame (before acquiring it).
     */
    public void prepareUploads() {
        pool.beginFrame();
        indexPool.beginFrame();
        if (indexGrowthNeeded) {
            indexPool.grow();
            indexGrowthNeeded = false;
        }
        long budget = UPLOAD_BUDGET_BYTES;
        for (PendingUpload upload : uploads) {
            budget -= (long) upload.mesh().quadCount() * ChunkMesh.BYTES_PER_QUAD;
        }
        while (!waiting.isEmpty()) {
            ChunkMesh mesh = waiting.peek();
            long bytes = (long) mesh.quadCount() * ChunkMesh.BYTES_PER_QUAD;
            if (bytes > budget && budget < UPLOAD_BUDGET_BYTES) {
                break;
            }
            waiting.poll();
            if (mesh.isEmpty()) {
                install(mesh, null);
                continue;
            }
            GeometryPool.Allocation allocation = pool.allocate(mesh.quadCount());
            if (allocation == null) {
                System.err.println("Section mesh exceeds a geometry page; skipped.");
                continue;
            }
            uploads.add(new PendingUpload(mesh, allocation));
            budget -= bytes;
        }
    }

    /** Copies reserved meshes through the stream region; part of {@link #prepare}. */
    private void recordUploads(VulkanFrame frame) {
        while (!uploads.isEmpty()) {
            PendingUpload upload = uploads.peek();
            int[] words = upload.mesh().words();
            long bytes = (long) words.length * Integer.BYTES;
            long offset = frame.allocate(bytes, 16);
            if (offset < 0) {
                return; // stream region exhausted this frame; finish next frame
            }
            frame.streamInts().put((int) (offset / Integer.BYTES), words);
            frame.copyFromStream(offset, pool.page(upload.allocation().page()),
                    (long) upload.allocation().firstQuad() * ChunkMesh.BYTES_PER_QUAD, bytes);
            uploads.poll();
            install(upload.mesh(), upload.allocation());
        }
    }

    private void install(ChunkMesh mesh, GeometryPool.Allocation allocation) {
        long key = key(mesh.sectionX(), mesh.sectionY(), mesh.sectionZ());
        GpuSection section = sections.get(key);
        if (section == null) {
            // Dropped while its upload was pending: release the geometry instead of resurrecting the section.
            if (allocation != null) {
                pool.free(allocation);
            }
            return;
        }
        if (section.allocation != null) {
            pool.free(section.allocation);
        }
        if (section.indices != null) {
            indexPool.free(section.indices);
            section.indices = null;
        }
        section.allocation = allocation;
        section.visibility = mesh.visibility();
        gridVersion++;
        int slot = slot(section.x, section.y, section.z);
        if (slot >= 0 && grid[slot] == section) {
            slotVisibility[slot] = slotVisibility(section);
        }
        section.translucentCells = mesh.translucentCells();
        section.sortedKey = -1;
        for (int range = 0; range < ChunkMesh.RANGES; range++) {
            section.rangeStart[range] = mesh.rangeStart(range);
            section.rangeCount[range] = mesh.rangeCount(range);
        }
    }

    // ---- per-frame preparation ----------------------------------------------------------------------------

    /**
     * Records uploads, finds the visible sections, re-sorts translucent geometry whose order changed and writes
     * this frame's draw records. Call after acquiring the frame and before any pass.
     *
     * @param forward camera forward direction (normalized); up is world +Y
     */
    public void prepare(VulkanFrame frame, int width, int height, double cameraX, double cameraY, double cameraZ,
                        float[] forward, float verticalFovRadians, float farPlane) {
        prepare(frame, width, height, cameraX, cameraY, cameraZ, forward, verticalFovRadians, farPlane, null);
    }

    /**
     * As {@link #prepare(VulkanFrame, int, int, double, double, double, float[], float, float)}, with a transform
     * applied in eye space after the camera's rotation (column-major 4x4, or null): vanilla's hurt tilt and view
     * bobbing. Culling, the sky and other renderers use the transformed camera.
     */
    public void prepare(VulkanFrame frame, int width, int height, double cameraX, double cameraY, double cameraZ,
                        float[] forward, float verticalFovRadians, float farPlane, float[] eyeTransform) {
        if (eyeTransform == null) {
            Mat4.identity(this.eyeTransform);
        } else {
            System.arraycopy(eyeTransform, 0, this.eyeTransform, 0, 16);
        }
        boolean uploading = !uploads.isEmpty();
        recordUploads(frame);
        long startNanos = System.nanoTime(); // steady-state cost: uploads are timed by their own budget
        writeFrameTable(frame);
        viewportWidth = width;
        viewportHeight = height;
        System.arraycopy(forward, 0, skyForward, 0, 3);
        skyFov = verticalFovRadians;
        buildCamera(forward, verticalFovRadians, width / (float) height, farPlane);
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        lastCameraZ = cameraZ;
        if (cameraX != walkedX || cameraY != walkedY || cameraZ != walkedZ || gridVersion != walkedVersion
                || !Arrays.equals(planes, walkedPlanes)) {
            traverse(cameraX, cameraY, cameraZ);
            walkedX = cameraX;
            walkedY = cameraY;
            walkedZ = cameraZ;
            walkedVersion = gridVersion;
            System.arraycopy(planes, 0, walkedPlanes, 0, planes.length);
        }
        sortTranslucent(frame, cameraX, cameraY, cameraZ);
        prepared = writeDrawData(frame, cameraX, cameraY, cameraZ);
        if (!uploading) {
            prepareNanos += System.nanoTime() - startNanos;
            prepareCount++;
        }
    }

    private long prepareNanos;
    private long prepareCount;
    private final float[] eyeTransform = Mat4.identity(new float[16]);
    /** The eye transform times the camera rotation: the full camera orientation the sky is traced with. */
    private final float[] eyeView = new float[16];

    /**
     * Copies this frame's table (vanilla's 16x16 lightmap, recomputed only when its inputs change, and the
     * texture-to-layer map) into the frame slot's part of a small device-local buffer.
     */
    private void writeFrameTable(VulkanFrame frame) {
        if (lightmapDirty) {
            for (int sky = 0; sky < 16; sky++) {
                for (int block = 0; block < 16; block++) {
                    int rgb = Lightmap.color(sky, block, sunBrightness, torchFlicker, gamma, nightVision);
                    frameTable[sky * 16 + block] = (rgb >> 16 & 0xFF) | (rgb & 0xFF00) | (rgb & 0xFF) << 16;
                    lightmapRgb[sky * 16 + block] = rgb;
                }
            }
            lightmapDirty = false;
        }
        long bytes = (long) frameTable.length * Integer.BYTES;
        long offset = frame.allocate(bytes, 16);
        if (offset < 0) {
            return;
        }
        frame.streamInts().put((int) (offset / Integer.BYTES), frameTable);
        long slot = (long) (frame.index() % TABLE_SLOTS) * tableBytes;
        frame.copyFromStream(offset, tableBuffer, slot, bytes);
        scene.putLong(104, tableBuffer.deviceAddress() + slot);
    }

    /** Whether a section whose lowest corner is at (minX, minY, minZ), camera-relative, touches the frustum. */
    private boolean inFrustum(float minX, float minY, float minZ) {
        for (int plane = 0; plane < 6; plane++) {
            if (planes[plane * 4] * minX + planes[plane * 4 + 1] * minY + planes[plane * 4 + 2] * minZ
                    + planeReach[plane] < 0f) {
                return false;
            }
        }
        return true;
    }


    /**
     * Breadth-first visibility walk from the camera's section (see the class comment). A section is entered
     * at most once per frame; every face it is entered through during its level is remembered, so a section
     * reachable through any open path is not lost to the order neighbors happen to be visited in. Sections with
     * geometry are appended to the visible list in walk order.
     */
    private void traverse(double cameraX, double cameraY, double cameraZ) {
        if (++stamp >= STAMP_LIMIT) {
            Arrays.fill(slotMark, 0);
            stamp = 1;
        }
        int mark = stamp << 6;
        visibleCount = 0;
        reachedCount = 0;
        if (visible.length < sections.size()) {
            visible = new GpuSection[Integer.highestOneBit(Math.max(1, sections.size())) << 1];
        }
        int cx = Math.floorDiv((int) Math.floor(cameraX), 16);
        int cy = Math.floorDiv((int) Math.floor(cameraY), 16);
        int cz = Math.floorDiv((int) Math.floor(cameraZ), 16);
        int start = occupied(cx, Math.max(minSectionY, Math.min(maxSectionY, cy)), cz);
        if (start < 0) {
            // Outside the loaded area: no walk is possible, so fall back to frustum culling alone.
            for (GpuSection section : sections.values()) {
                if (section.allocation != null && inFrustum((float) (section.x * 16.0 - cameraX),
                        (float) (section.y * 16.0 - cameraY), (float) (section.z * 16.0 - cameraZ))) {
                    visible[visibleCount++] = section;
                }
            }
            reachedCount = visibleCount;
            sortByManhattan(cx, cy, cz);
            return;
        }
        int startY = Math.max(minSectionY, Math.min(maxSectionY, cy));
        boolean inside = cy == startY;
        // Above or below the world, the walk enters the column's top or bottom section from outside.
        slotMark[start] = mark | (inside ? 0x3F : 1 << (cy > startY ? 3 : 2));
        int head = 0;
        int tail = 0;
        walk[tail++] = start;
        while (head < tail) {
            int slot = walk[head++];
            long visibility = slotVisibility[slot];
            if (visibility < 0L) {
                visible[visibleCount++] = grid[slot];
            }
            long key = slotKey[slot];
            int x = (int) (key >> 42);
            int y = (int) (key << 44 >> 44);
            int z = (int) (key << 22 >> 42);
            int dx = x - cx;
            int dy = y - cy;
            int dz = z - cz;
            // Only step away from the camera: the walk's depth is then the Manhattan distance.
            int outward = (dx <= 0 ? 1 : 0) | (dx >= 0 ? 2 : 0) | (dy <= 0 ? 4 : 0) | (dy >= 0 ? 8 : 0)
                    | (dz <= 0 ? 16 : 0) | (dz >= 0 ? 32 : 0);
            int exits = slot == start && inside ? 0x3F : exits(visibility, slotMark[slot] & 0x3F);
            int open = outward & exits;
            while (open != 0) {
                int out = Integer.numberOfTrailingZeros(open);
                open &= open - 1;
                int nx = x + STEP_X[out];
                int ny = y + STEP_Y[out];
                int nz = z + STEP_Z[out];
                int next = occupied(nx, ny, nz);
                if (next < 0) {
                    continue;
                }
                int entry = 1 << (out ^ 1);
                int reached = slotMark[next];
                if ((reached ^ mark) >>> 6 == 0) {
                    slotMark[next] = reached | entry;
                    continue;
                }
                if (inFrustum((float) (nx * 16.0 - cameraX), (float) (ny * 16.0 - cameraY),
                        (float) (nz * 16.0 - cameraZ))) {
                    slotMark[next] = mark | entry;
                    walk[tail++] = next;
                } else {
                    slotMark[next] = mark; // marked, never walked
                }
            }
        }
        reachedCount = tail;
    }

    /** Faces a section can be left through, given the faces it was entered through (6 bits each). */
    private static int exits(long visibility, int incoming) {
        int exits = 0;
        while (incoming != 0) {
            int in = Integer.numberOfTrailingZeros(incoming);
            incoming &= incoming - 1;
            exits |= (int) (visibility >>> (in * 6)) & 0x3F;
        }
        return exits;
    }

    /** Orders the visible list by Manhattan section distance, nearest first (only for the fallback path). */
    private void sortByManhattan(int cx, int cy, int cz) {
        long[] keys = new long[visibleCount];
        for (int index = 0; index < visibleCount; index++) {
            GpuSection section = visible[index];
            long distance = Math.abs(section.x - cx) + Math.abs(section.y - cy) + Math.abs(section.z - cz);
            keys[index] = distance << 32 | index;
        }
        Arrays.sort(keys);
        GpuSection[] ordered = new GpuSection[visibleCount];
        for (int index = 0; index < visibleCount; index++) {
            ordered[index] = visible[(int) keys[index]];
        }
        System.arraycopy(ordered, 0, visible, 0, visibleCount);
    }

    /** Re-sorts translucent index lists whose camera cell changed, nearest sections first, within a budget. */
    private void sortTranslucent(VulkanFrame frame, double cameraX, double cameraY, double cameraZ) {
        int budget = SORT_BUDGET_QUADS;
        int sorts = 0;
        for (int index = 0; index < visibleCount && budget > 0; index++) {
            GpuSection section = visible[index];
            int count = section.rangeCount[ChunkMesh.TRANSLUCENT_RANGE];
            if (count == 0 || section.allocation == null) {
                continue;
            }
            // Sections with a camera-independent order sort once (identity); the key below never matches a cell.
            int key = section.translucentCells.length == 0 ? Integer.MAX_VALUE
                    : TranslucentSorter.cameraKey(cameraX, cameraY, cameraZ, section.x, section.y, section.z);
            if (key == section.sortedKey) {
                continue;
            }
            GeometryPool.Allocation allocation = indexPool.allocateWithoutGrowth(count);
            if (allocation == null) {
                indexGrowthNeeded = true;
                break;
            }
            long bytes = (long) count * INDEX_BYTES_PER_QUAD;
            long offset = frame.allocate(bytes, 16);
            if (offset < 0) {
                indexPool.free(allocation);
                break;
            }
            sorter.sort(section.translucentCells, count, key, frame.stream(), (int) offset);
            frame.copyFromStream(offset, indexPool.page(allocation.page()),
                    (long) allocation.firstQuad() * INDEX_BYTES_PER_QUAD, bytes);
            // The previous list may still be read by frames in flight; the pool recycles it after them.
            if (section.indices != null) {
                indexPool.free(section.indices);
            }
            section.indices = allocation;
            section.sortedKey = key;
            budget -= count;
            sorts++;
        }
        lastSorts = sorts;
    }

    /**
     * Writes per-section origins and the three passes' draw records into the stream. Opaque and cutout records
     * are grouped by geometry page (their order only affects early depth rejection); translucent records keep
     * strict far-to-near order and start a new batch whenever the pages change.
     */
    private boolean writeDrawData(VulkanFrame frame, double cameraX, double cameraY, double cameraZ) {
        Arrays.fill(batches, 0);
        lastDraws = 0;
        lastQuads = 0;
        if (visibleCount == 0) {
            return true;
        }
        long origins = frame.allocate((long) visibleCount * 16, 16);
        // Upper bound: each section's directional runs plus splits of oversized runs.
        if (origins < 0) {
            return false;
        }
        ByteBuffer stream = frame.stream();
        for (int index = 0; index < visibleCount; index++) {
            GpuSection section = visible[index];
            section.drawSlot = index;
            int position = (int) origins + index * 16;
            stream.putFloat(position, (float) (section.x * 16.0 - cameraX))
                    .putFloat(position + 4, (float) (section.y * 16.0 - cameraY))
                    .putFloat(position + 8, (float) (section.z * 16.0 - cameraZ))
                    .putFloat(position + 12, 0f);
        }
        scene.putLong(96, frame.streamAddress(origins));
        // Upper bound: each section's directional runs plus splits of oversized runs, in two passes, plus one
        // translucent record.
        int capacity = visibleCount * 25 * 5;
        if (commands.length < capacity) {
            commands = new int[Integer.highestOneBit(capacity) << 1];
        }
        commandCount = 0;
        writeSolidPass(0, MaterialPass.OPAQUE, cameraX, cameraY, cameraZ);
        writeSolidPass(1, MaterialPass.CUTOUT, cameraX, cameraY, cameraZ);
        writeTranslucentPass();
        commandStream = 0;
        if (indirect && commandCount > 0) {
            commandStream = frame.allocate((long) commandCount * COMMAND_BYTES, 16);
            if (commandStream < 0) {
                return false;
            }
            frame.streamInts().put((int) (commandStream / Integer.BYTES), commands, 0, commandCount * 5);
        }
        return true;
    }

    private void writeSolidPass(int pass, MaterialPass material, double cameraX, double cameraY, double cameraZ) {
        int base = ChunkMesh.range(material, 0);
        for (int page = 0; page < pool.pageCount(); page++) {
            int batchStart = commandCount;
            for (int index = 0; index < visibleCount; index++) {
                GpuSection section = visible[index];
                if (section.allocation == null || section.allocation.page() != page) {
                    continue;
                }
                float minX = (float) (section.x * 16.0 - cameraX);
                float minY = (float) (section.y * 16.0 - cameraY);
                float minZ = (float) (section.z * 16.0 - cameraZ);
                // A face direction can be seen only from its outer side of the section's slab (with a block of
                // slack for geometry that overhangs the section, such as offset plants).
                int facing = (minX + 17f > 0f ? 1 : 0) | (minX - 1f < 0f ? 2 : 0)
                        | (minY + 17f > 0f ? 4 : 0) | (minY - 1f < 0f ? 8 : 0)
                        | (minZ + 17f > 0f ? 16 : 0) | (minZ - 1f < 0f ? 32 : 0) | 64;
                int slot = 0;
                while (slot < ChunkMesh.SLOTS_PER_PASS) {
                    if ((facing >> slot & 1) == 0 || section.rangeCount[base + slot] == 0) {
                        slot++;
                        continue;
                    }
                    int first = section.rangeStart[base + slot];
                    int count = 0;
                    while (slot < ChunkMesh.SLOTS_PER_PASS && (facing >> slot & 1) != 0) {
                        count += section.rangeCount[base + slot];
                        slot++;
                    }
                    int vertexOffset = (section.allocation.firstQuad() + first) * 4;
                    lastQuads += count;
                    while (count > 0) {
                        int quads = Math.min(count, MAX_QUADS_PER_DRAW);
                        putCommand(quads * 6, 0, vertexOffset, section.drawSlot);
                        count -= quads;
                        vertexOffset += quads * 4;
                    }
                }
            }
            addBatch(pass, batchStart, commandCount - batchStart, page, -1);
        }
    }

    private void writeTranslucentPass() {
        int batchStart = commandCount;
        int vertexPage = -1;
        int indexPage = -1;
        for (int index = visibleCount - 1; index >= 0; index--) {
            GpuSection section = visible[index];
            int count = section.rangeCount[ChunkMesh.TRANSLUCENT_RANGE];
            if (count == 0 || section.allocation == null || section.indices == null) {
                continue;
            }
            if (section.allocation.page() != vertexPage || section.indices.page() != indexPage) {
                addBatch(2, batchStart, commandCount - batchStart, vertexPage, indexPage);
                batchStart = commandCount;
                vertexPage = section.allocation.page();
                indexPage = section.indices.page();
            }
            int vertexOffset = (section.allocation.firstQuad() + section.rangeStart[ChunkMesh.TRANSLUCENT_RANGE]) * 4;
            putCommand(count * 6, section.indices.firstQuad() * 6, vertexOffset, section.drawSlot);
            lastQuads += count;
        }
        addBatch(2, batchStart, commandCount - batchStart, vertexPage, indexPage);
    }

    private void putCommand(int indexCount, int firstIndex, int vertexOffset, int firstInstance) {
        int position = commandCount++ * 5;
        commands[position] = indexCount;
        commands[position + 1] = 1;
        commands[position + 2] = firstIndex;
        commands[position + 3] = vertexOffset;
        commands[position + 4] = firstInstance;
    }

    private void addBatch(int pass, int offset, int count, int vertexPage, int indexPage) {
        if (count == 0) {
            return;
        }
        int batch = batches[pass];
        if (batch == batchOffset[pass].length) {
            // Back-to-front translucent order alternates between pages as the geometry grows past one page;
            // every switch is a batch, so the list grows (rarely, and only up to the high-water mark).
            int capacity = batch * 2;
            batchOffset[pass] = Arrays.copyOf(batchOffset[pass], capacity);
            batchCount[pass] = Arrays.copyOf(batchCount[pass], capacity);
            batchVertexPage[pass] = Arrays.copyOf(batchVertexPage[pass], capacity);
            batchIndexPage[pass] = Arrays.copyOf(batchIndexPage[pass], capacity);
        }
        batchOffset[pass][batch] = offset;
        batchCount[pass][batch] = count;
        batchVertexPage[pass][batch] = vertexPage;
        batchIndexPage[pass][batch] = indexPage;
        batches[pass] = batch + 1;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /** The sky, fog and celestial state the next {@link #render} draws; update it each frame. */
    public Sky sky() {
        return skyState;
    }

    /**
     * Rain or snow to draw after the terrain (vanilla's renderRainSnow); strength 0 draws none.
     *
     * @param ticks the renderer tick counter driving the fall
     * @param feetX the viewer's interpolated feet position
     */
    public void setWeather(SkyRenderer.Weather source, float strength, long ticks, float partialTicks, double feetX,
                           double feetY, double feetZ) {
        weatherSource = source;
        weatherStrength = strength;
        weatherTicks = ticks;
        weatherPartial = partialTicks;
        this.feetX = feetX;
        this.feetY = feetY;
        this.feetZ = feetZ;
    }

    /**
     * Draws sky, terrain and weather into the current pass (its target must have depth), from the data {@link
     * #prepare} wrote this frame, with the fog of {@link #sky()} (vanilla's: radial, linear from 3/4 of the
     * render distance to its end, or exponential in water and lava). The viewport is set here, flipped to y-up.
     *
     * @param fog whether terrain fades into the fog color
     */
    public void render(VulkanFrame frame, boolean fog) {
        renderSky(frame);
        renderSolid(frame, fog);
        renderTranslucent(frame);
        renderWeather(frame);
    }

    /**
     * Whether {@link #renderSky} may follow {@link #renderSolid}: the sky then draws at the far plane, only where
     * no opaque or cutout terrain is, with the same result as drawn first. Nothing blended may come between.
     */
    public boolean skyAfterSolid() {
        return sky.drawsAtFarPlane();
    }

    /** The sky behind everything: first in the pass, or right after {@link #renderSolid} (see skyAfterSolid). */
    public void renderSky(VulkanFrame frame) {
        drawSky(frame);
    }

    /**
     * Opaque and cutout terrain with the fog of {@link #sky()}; models, particles and the weather draw after
     * this and before {@link #renderTranslucent}, as vanilla orders its world pass.
     */
    public void renderSolid(VulkanFrame frame, boolean fog) {
        translucentReady = false;
        if (!prepared) {
            return;
        }
        // The world's y-up viewport; the sky leaves it set too, but the terrain may now come first.
        frame.setViewport(0, 0, viewportWidth, viewportHeight, true);
        for (int index = 0; index < 16; index++) {
            scene.putFloat(index * 4, viewProjection[index]);
        }
        Sky state = skyState;
        // Blindness fog is linear even under water, and drawn whatever the fog setting says.
        boolean exponential = state.medium != Sky.Medium.AIR && state.blindness < 0;
        fog |= state.blindness >= 0;
        scene.putFloat(64, srgbToLinear(state.fogColor[0])).putFloat(68, srgbToLinear(state.fogColor[1]))
                .putFloat(72, srgbToLinear(state.fogColor[2])).putFloat(76, state.fogStart);
        scene.putFloat(80, state.fogEnd).putFloat(84, fog || exponential ? 1f : 0f)
                .putFloat(88, exponential ? state.fogDensity : 0f).putFloat(92, 0f);
        scene.clear();
        int draws = 0;
        draws += drawPass(frame, opaque, 0);
        draws += drawPass(frame, cutout, 1);
        lastDraws = draws;
        translucentReady = true;
        frame.setViewport(0, 0, viewportWidth, viewportHeight, true);
    }

    /** Water and other blended terrain, back to front; after {@link #renderSolid} in the same pass. */
    public void renderTranslucent(VulkanFrame frame) {
        if (!translucentReady) {
            return;
        }
        lastDraws += drawPass(frame, translucent, 2);
    }

    /**
     * The cracks of blocks being dug ({@link DamageOverlay}); after {@link #renderSolid} in the same pass, once the
     * entities are drawn.
     *
     * @param count blocks to draw; {@code x}, {@code y}, {@code z} and {@code stage} (0..9) hold one each
     */
    public void renderDamage(VulkanFrame frame, DamageOverlay overlay, int count, int[] x, int[] y, int[] z,
                             int[] stage, DamageOverlay.Blocks states) {
        if (prepared && count > 0) {
            overlay.draw(frame, scene, blocks, tiles, quadIndices, lastCameraX, lastCameraY, lastCameraZ,
                    viewportWidth, viewportHeight, count, x, y, z, stage, states);
        }
    }

    /** Rain or snow set by {@link #setWeather}. */
    public void renderWeather(VulkanFrame frame) {
        if (prepared && weatherSource != null && weatherStrength > 0f) {
            sky.drawWeather(frame, weatherSource, weatherStrength, weatherTicks, weatherPartial, feetX, feetY, feetZ,
                    lastCameraX, lastCameraY, lastCameraZ, viewProjection, lightmapRgb);
        }
    }

    private boolean translucentReady;

    /** The fog of the last {@link #renderSolid} for other renderers in the scene: linear rgb, start, end, on, density. */
    public void fog(float[] out) {
        Sky state = skyState;
        boolean exponential = state.medium != Sky.Medium.AIR && state.blindness < 0;
        out[0] = srgbToLinear(state.fogColor[0]);
        out[1] = srgbToLinear(state.fogColor[1]);
        out[2] = srgbToLinear(state.fogColor[2]);
        out[3] = state.fogStart;
        out[4] = state.fogEnd;
        out[5] = scene.getFloat(84);
        out[6] = exponential ? state.fogDensity : 0f;
    }

    private static float srgbToLinear(float value) {
        return value <= 0.04045f ? value / 12.92f : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    private int drawPass(VulkanFrame frame, VulkanPipeline pipeline, int pass) {
        if (batches[pass] == 0) {
            return 0;
        }
        frame.bindPipeline(pipeline);
        frame.bindImage(0, blocks);
        frame.bindImage(1, tiles);
        frame.pushConstants(scene);
        if (pass != 2) {
            frame.bindIndices(quadIndices, 0);
        }
        int draws = 0;
        int[] offsets = batchOffset[pass];
        int[] counts = batchCount[pass];
        for (int batch = 0; batch < batches[pass]; batch++) {
            frame.bindVertices(0, pool.page(batchVertexPage[pass][batch]), 0);
            if (pass == 2) {
                frame.bindIndices(indexPool.page(batchIndexPage[pass][batch]), 0);
            }
            draws += counts[batch];
            if (indirect) {
                frame.drawIndexedIndirect(commandStream + (long) offsets[batch] * COMMAND_BYTES, counts[batch],
                        COMMAND_BYTES);
            } else {
                for (int command = offsets[batch]; command < offsets[batch] + counts[batch]; command++) {
                    int position = command * 5;
                    frame.drawIndexed(commands[position], 1, commands[position + 2], commands[position + 3],
                            commands[position + 4]);
                }
            }
        }
        return draws;
    }

    /** Statistics of the last frame; computed on request so the frame path does not allocate. */
    public Stats stats() {
        return new Stats(sections.size(), reachedCount, visibleCount, lastDraws, lastQuads, pool.usedBytes(),
                indexPool.usedBytes(), lastSorts, pendingUploads(), indirect,
                prepareCount == 0 ? 0 : prepareNanos / 1000.0 / prepareCount);
    }

    private void buildCamera(float[] f, float fov, float aspect, float far) {
        Mat4.perspective(fov, aspect, 0.05f, far, projection);
        // Right = forward x up, up' = right x forward; view rows are right, up', -forward.
        float rx = -f[2];
        float rz = f[0];
        float length = (float) Math.sqrt(rx * rx + rz * rz);
        rx /= length;
        rz /= length;
        float ux = -rz * f[1];
        float uy = rz * f[0] - rx * f[2];
        float uz = rx * f[1];
        Arrays.fill(view, 0f);
        view[0] = rx;
        view[4] = 0f;
        view[8] = rz;
        view[1] = ux;
        view[5] = uy;
        view[9] = uz;
        view[2] = -f[0];
        view[6] = -f[1];
        view[10] = -f[2];
        view[15] = 1f;
        Mat4.multiply(eyeTransform, view, eyeView);
        Mat4.multiply(projection, eyeView, viewProjection);
        // Frustum planes (a, b, c, d) from the combined matrix rows; Vulkan depth puts near at row 2 alone.
        for (int plane = 0; plane < 6; plane++) {
            for (int column = 0; column < 4; column++) {
                float row3 = viewProjection[column * 4 + 3];
                float value = switch (plane) {
                    case 0 -> row3 + viewProjection[column * 4];
                    case 1 -> row3 - viewProjection[column * 4];
                    case 2 -> row3 + viewProjection[column * 4 + 1];
                    case 3 -> row3 - viewProjection[column * 4 + 1];
                    case 4 -> viewProjection[column * 4 + 2];
                    default -> row3 - viewProjection[column * 4 + 2];
                };
                planes[plane * 4 + column] = value;
            }
            // The section corner farthest along the plane's normal is +16 on each axis it points along.
            float a = planes[plane * 4];
            float b = planes[plane * 4 + 1];
            float c = planes[plane * 4 + 2];
            planeReach[plane] = planes[plane * 4 + 3] + (a >= 0 ? a * 16f : 0f) + (b >= 0 ? b * 16f : 0f)
                    + (c >= 0 ? c * 16f : 0f);
        }
    }

    /** The block texture array, for particles that sample block textures. */
    public VulkanTexture blockTextures() {
        return blocks;
    }

    /** This frame's lightmap (vanilla's 16x16 table) as 0xRRGGBB, index sky * 16 + block. */
    public void lightmap(int[] out) {
        System.arraycopy(lightmapRgb, 0, out, 0, 256);
    }

    /** The camera's right and up vectors in world space (with the eye transform), for billboards. */
    public void cameraBasis(float[] right, float[] up) {
        right[0] = eyeView[0];
        right[1] = eyeView[4];
        right[2] = eyeView[8];
        up[0] = eyeView[1];
        up[1] = eyeView[5];
        up[2] = eyeView[9];
    }

    /** The camera-relative view-projection of the last {@link #prepare}, for other renderers in the scene. */
    public float[] viewProjection() {
        return viewProjection;
    }

    private void drawSky(VulkanFrame frame) {
        float aspect = viewportWidth / (float) Math.max(1, viewportHeight);
        float tanY = (float) Math.tan(skyFov / 2);
        float tanX = tanY * aspect;
        // Rows of the camera orientation: right, up and backward (eye space looks down -z).
        float[] m = eyeView;
        skyBasis[0] = m[0] * tanX;
        skyBasis[1] = m[4] * tanX;
        skyBasis[2] = m[8] * tanX;
        skyBasis[3] = m[1] * tanY;
        skyBasis[4] = m[5] * tanY;
        skyBasis[5] = m[9] * tanY;
        skyBasis[6] = -m[2];
        skyBasis[7] = -m[6];
        skyBasis[8] = -m[10];
        // The planes' full-screen triangle uses an unflipped viewport so its uv matches the screen.
        frame.setViewport(0, 0, viewportWidth, viewportHeight, false);
        sky.drawBackground(frame, skyState, skyBasis, viewProjection, lastCameraY, skyState.renderDistanceChunks,
                viewportWidth, viewportHeight);
        frame.setViewport(0, 0, viewportWidth, viewportHeight, true);
    }

    @Override
    public void close() {
        pool.close();
        indexPool.close();
        tableBuffer.close();
        sky.close();
        translucent.close();
        cutout.close();
        opaque.close();
        quadIndices.close();
        blocks.close();
        tiles.close();
        MemoryUtil.memFree(scene);
    }
}
