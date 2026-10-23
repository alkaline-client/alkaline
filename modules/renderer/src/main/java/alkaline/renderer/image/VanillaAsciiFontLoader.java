package alkaline.renderer.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

/** Decodes the vanilla 1.8.9 ASCII font atlas from an asset supplied by the user. */
public final class VanillaAsciiFontLoader {
    public static final String ASSET_PATH = "assets/minecraft/textures/font/ascii.png";
    private static final int SIZE = 128;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private VanillaAsciiFontLoader() {
    }

    public static RgbaImage load(byte[] encodedPng) throws IOException {
        Objects.requireNonNull(encodedPng, "encodedPng");
        if (encodedPng.length < PNG_SIGNATURE.length || encodedPng.length > MAX_BYTES) {
            throw new IOException("Vanilla ASCII font input is empty or exceeds its PNG byte limit.");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (encodedPng[index] != PNG_SIGNATURE[index]) {
                throw new IOException("Vanilla ASCII font asset must be a PNG.");
            }
        }
        RgbaImage image = ImageDecoder.decode(new ByteArrayInputStream(encodedPng));
        if (image.width() != SIZE || image.height() != SIZE) {
            throw new IOException("Vanilla ASCII font atlas must decode to 128x128 pixels.");
        }
        return image;
    }
}
