package alkaline.renderer.vulkan.resource;

import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * The pixels of an immutable texture, ready to upload: every layer and its CPU mip chain, tightly packed level by
 * level (level 0 of every layer, then level 1, and so on). Building one makes no Vulkan calls, so any thread can
 * do it; the render thread then only creates the image and copies these bytes
 * ({@link VulkanRenderer#createTexture(TextureData)}). Instances are immutable and may be uploaded more than once.
 */
public final class TextureData {
    /** Arrays with at least this many layers build their mip chains on the common pool, a layer per task. */
    private static final int PARALLEL_LAYERS = 8;
    private final int width;
    private final int height;
    private final int layers;
    private final int mipLevels;
    private final boolean array;
    private final TextureOptions options;
    /** Direct, so both the staging copy and host image copy read it in place; only duplicates leave. */
    private final ByteBuffer pixels;

    private TextureData(int width, int height, int layers, int mipLevels, boolean array, TextureOptions options,
                        ByteBuffer pixels) {
        this.width = width;
        this.height = height;
        this.layers = layers;
        this.mipLevels = mipLevels;
        this.array = array;
        this.options = options;
        this.pixels = pixels;
    }

    /** One 2D image. */
    public static TextureData of(RgbaImage image, TextureOptions options) {
        Objects.requireNonNull(image, "image");
        return build(List.of(image), false, options);
    }

    /** A same-size 2D array (sampler2DArray) whose layers follow list order. */
    public static TextureData array(List<RgbaImage> layers, TextureOptions options) {
        return build(List.copyOf(layers), true, options);
    }

    private static TextureData build(List<RgbaImage> sources, boolean array, TextureOptions options) {
        Objects.requireNonNull(options, "options");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("A texture needs one image, or one or more same-size array layers.");
        }
        RgbaImage first = sources.getFirst();
        for (RgbaImage layer : sources) {
            if (layer.width() != first.width() || layer.height() != first.height()) {
                throw new IllegalArgumentException("Texture array layers must share one size.");
            }
        }
        int width = first.width();
        int height = first.height();
        int mipLevels = Math.min(options.mipLevels(), 32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        boolean srgb = options.srgb();
        IntStream layerIndices = IntStream.range(0, sources.size());
        if (sources.size() >= PARALLEL_LAYERS && mipLevels > 1) {
            layerIndices = layerIndices.parallel();
        }
        // chains[layer][level]
        byte[][][] chains = new byte[sources.size()][][];
        layerIndices.forEach(layer -> {
            byte[][] chain = new byte[mipLevels][];
            chain[0] = sources.get(layer).copyPixels();
            for (int level = 1; level < mipLevels; level++) {
                chain[level] = MipChain.downsample(chain[level - 1], Math.max(1, width >> (level - 1)),
                        Math.max(1, height >> (level - 1)), srgb);
            }
            chains[layer] = chain;
        });
        long total = 0;
        for (int level = 0; level < mipLevels; level++) {
            total += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4 * sources.size();
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(Math.toIntExact(total));
        for (int level = 0; level < mipLevels; level++) {
            for (byte[][] chain : chains) {
                pixels.put(chain[level]);
            }
        }
        pixels.flip();
        return new TextureData(width, height, sources.size(), mipLevels, array, options, pixels);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int layers() {
        return layers;
    }

    public int mipLevels() {
        return mipLevels;
    }

    /** Whether the texture is sampled as an array, even with one layer. */
    public boolean array() {
        return array;
    }

    public TextureOptions options() {
        return options;
    }

    /** Bytes of level 0 over all layers. */
    public long levelZeroBytes() {
        return (long) width * height * 4 * layers;
    }

    /** All levels, positioned at 0; a fresh view each call, so concurrent uploads do not share a position. */
    ByteBuffer pixels() {
        return pixels.duplicate();
    }
}
