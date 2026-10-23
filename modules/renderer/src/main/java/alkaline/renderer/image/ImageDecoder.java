package alkaline.renderer.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounded decoder for PNG images. */
public final class ImageDecoder {
    public static final long MAX_ENCODED_BYTES = 32L * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    /** Up to a full 4096 x 4096 image: resource packs ship skyboxes of 3072 x 2048 and larger. */
    public static final long MAX_PIXELS = (long) MAX_DIMENSION * MAX_DIMENSION;

    private ImageDecoder() {
    }

    public static RgbaImage decode(InputStream input) throws IOException {
        if (input == null) {
            throw new NullPointerException("input");
        }
        byte[] encoded = readBounded(input);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (imageInput == null) {
                throw new IOException("No image reader accepted the input.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            ImageReader reader = null;
            while (readers.hasNext()) {
                ImageReader candidate = readers.next();
                String format = candidate.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (format.equals("png") || format.equals("jpeg") || format.equals("jpg")
                        || format.equals("bmp") || format.equals("gif")) {
                    reader = candidate;
                    break;
                }
                candidate.dispose();
            }
            if (reader == null) {
                throw new IOException("Only PNG images are supported.");
            }
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new IOException("Image decoder returned inconsistent dimensions.");
                }
                byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
                int offset = 0;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int argb = decoded.getRGB(x, y);
                        rgba[offset++] = (byte) (argb >>> 16);
                        rgba[offset++] = (byte) (argb >>> 8);
                        rgba[offset++] = (byte) argb;
                        rgba[offset++] = (byte) (argb >>> 24);
                    }
                }
                return new RgbaImage(width, height, rgba);
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; ; ) {
            read = input.read(buffer);
            if (read == -1) {
                break;
            }
            if (read == 0) {
                int value = input.read();
                if (value == -1) {
                    break;
                }
                if (++total > MAX_ENCODED_BYTES) {
                    throw new IOException("Encoded image exceeds the supported byte limit.");
                }
                bytes.write(value);
                continue;
            }
            total += read;
            if (total > MAX_ENCODED_BYTES) {
                throw new IOException("Encoded image exceeds the supported byte limit.");
            }
            bytes.write(buffer, 0, read);
        }
        if (total == 0) {
            throw new IOException("Image input is empty.");
        }
        return bytes.toByteArray();
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IOException("Image dimensions exceed the supported limits.");
        }
    }
}
