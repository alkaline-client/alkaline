package alkaline.renderer.model;

import alkaline.renderer.image.RgbaImage;

/**
 * Player skins as the player model samples them (ImageBufferDownload.parseUserSkin): 64x32 legacy skins gain
 * the 1.8 layout's left arm and leg (mirrored copies of the right ones), any size is scaled to a 64x64 multiple,
 * and the base layer is forced opaque while overlay areas that are fully opaque are cleared (old skins filled them
 * with solid color).
 */
public final class SkinImages {
    private SkinImages() {
    }

    public static RgbaImage normalize(RgbaImage source) {
        int width = 64;
        int height = 64;
        int k = 1;
        while (width < source.width() || height < source.height()) {
            width *= 2;
            height *= 2;
            k *= 2;
        }
        int[] pixels = new int[width * height];
        byte[] input = source.copyPixels();
        for (int y = 0; y < Math.min(height, source.height()); y++) {
            for (int x = 0; x < Math.min(width, source.width()); x++) {
                int offset = (y * source.width() + x) * 4;
                pixels[y * width + x] = (input[offset + 3] & 0xFF) << 24 | (input[offset] & 0xFF) << 16
                        | (input[offset + 1] & 0xFF) << 8 | input[offset + 2] & 0xFF;
            }
        }
        if (source.height() == 32 * k) {
            int[][] copies = {
                    {24, 48, 20, 52, 4, 16, 8, 20}, {28, 48, 24, 52, 8, 16, 12, 20},
                    {20, 52, 16, 64, 8, 20, 12, 32}, {24, 52, 20, 64, 4, 20, 8, 32},
                    {28, 52, 24, 64, 0, 20, 4, 32}, {32, 52, 28, 64, 12, 20, 16, 32},
                    {40, 48, 36, 52, 44, 16, 48, 20}, {44, 48, 40, 52, 48, 16, 52, 20},
                    {36, 52, 32, 64, 48, 20, 52, 32}, {40, 52, 36, 64, 44, 20, 48, 32},
                    {44, 52, 40, 64, 40, 20, 44, 32}, {48, 52, 44, 64, 52, 20, 56, 32},
            };
            for (int[] copy : copies) {
                draw(pixels, width, copy[0] * k, copy[1] * k, copy[2] * k, copy[3] * k, copy[4] * k, copy[5] * k,
                        copy[6] * k, copy[7] * k);
            }
        }
        opaque(pixels, width, 0, 0, 32 * k, 16 * k);
        transparent(pixels, width, 32 * k, 0, 64 * k, 32 * k);
        opaque(pixels, width, 0, 16 * k, 64 * k, 32 * k);
        transparent(pixels, width, 0, 32 * k, 16 * k, 48 * k);
        transparent(pixels, width, 16 * k, 32 * k, 40 * k, 48 * k);
        transparent(pixels, width, 40 * k, 32 * k, 56 * k, 48 * k);
        transparent(pixels, width, 0, 48 * k, 16 * k, 64 * k);
        opaque(pixels, width, 16 * k, 48 * k, 48 * k, 64 * k);
        transparent(pixels, width, 48 * k, 48 * k, 64 * k, 64 * k);
        byte[] output = new byte[width * height * 4];
        for (int index = 0; index < pixels.length; index++) {
            int argb = pixels[index];
            output[index * 4] = (byte) (argb >> 16);
            output[index * 4 + 1] = (byte) (argb >> 8);
            output[index * 4 + 2] = (byte) argb;
            output[index * 4 + 3] = (byte) (argb >>> 24);
        }
        return new RgbaImage(width, height, output);
    }

    /** Graphics.drawImage(image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2): a reversed destination mirrors. */
    private static void draw(int[] pixels, int width, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2,
                             int sy2) {
        int columns = Math.abs(dx2 - dx1);
        int rows = Math.abs(dy2 - dy1);
        int[] copy = new int[columns * rows];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                copy[row * columns + column] = pixels[(sy1 + row) * width + sx1 + column];
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int x = dx1 < dx2 ? dx1 + column : dx1 - 1 - column;
                int y = dy1 < dy2 ? dy1 + row : dy1 - 1 - row;
                int argb = copy[row * columns + column];
                if (argb >>> 24 != 0) {
                    pixels[y * width + x] = argb;
                }
            }
        }
    }

    private static void opaque(int[] pixels, int width, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                pixels[y * width + x] |= 0xFF000000;
            }
        }
    }

    /** Clears an overlay area only when none of it is already transparent. */
    private static void transparent(int[] pixels, int width, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if ((pixels[y * width + x] >>> 24) < 128) {
                    return;
                }
            }
        }
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                pixels[y * width + x] &= 0x00FFFFFF;
            }
        }
    }
}
