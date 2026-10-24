package alkaline.clientapp;

import alkaline.renderer.image.RgbaImage;
import java.nio.ByteBuffer;

/** Source-over compositing of equally sized images (vanilla's LayeredTexture, used for horse coats). */
final class ImageLayers {
    private ImageLayers() {
    }

    /** The first image with each later one drawn over it; layers of another size are skipped. */
    static RgbaImage composite(RgbaImage[] layers) {
        RgbaImage base = layers[0];
        byte[] out = base.copyPixels();
        for (int layer = 1; layer < layers.length; layer++) {
            RgbaImage over = layers[layer];
            if (over == null || over.width() != base.width() || over.height() != base.height()) {
                continue;
            }
            ByteBuffer pixels = over.pixels();
            for (int index = 0; index < out.length; index += 4) {
                int alpha = pixels.get(index + 3) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int below = out[index + 3] & 0xFF;
                int result = alpha + below * (255 - alpha) / 255;
                for (int channel = 0; channel < 3; channel++) {
                    int source = pixels.get(index + channel) & 0xFF;
                    int destination = out[index + channel] & 0xFF;
                    int mixed = result == 0 ? 0
                            : (source * alpha * 255 + destination * below * (255 - alpha)) / (result * 255);
                    out[index + channel] = (byte) Math.min(255, mixed);
                }
                out[index + 3] = (byte) result;
            }
        }
        return new RgbaImage(base.width(), base.height(), out);
    }
}
