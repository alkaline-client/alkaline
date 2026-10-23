package alkaline.renderer.image;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Immutable, tightly packed 8-bit sRGB RGBA image data. */
public final class RgbaImage {
    private final int width;
    private final int height;
    private final byte[] pixels;

    public RgbaImage(int width, int height, byte[] pixels) {
        if (width <= 0 || height <= 0 || width > ImageDecoder.MAX_DIMENSION
                || height > ImageDecoder.MAX_DIMENSION || (long) width * height > ImageDecoder.MAX_PIXELS) {
            throw new IllegalArgumentException("Image dimensions are outside the supported range.");
        }
        Objects.requireNonNull(pixels, "pixels");
        int expectedLength = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (pixels.length != expectedLength) {
            throw new IllegalArgumentException("RGBA pixel data length does not match image dimensions.");
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int byteCount() {
        return pixels.length;
    }

    public ByteBuffer pixels() {
        return ByteBuffer.wrap(pixels).asReadOnlyBuffer();
    }

    public byte[] copyPixels() {
        return pixels.clone();
    }
}
