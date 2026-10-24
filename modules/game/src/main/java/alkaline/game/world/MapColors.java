package alkaline.game.world;

/**
 * The colors of a map's pixels (MapColor and MapItemRenderer.Instance.updateMapTexture): a pixel byte is a base
 * color index in its top six bits and one of four brightness levels in the low two.
 */
public final class MapColors {
    /** MapColor.colorValue by index; entries past the table are unused and read as transparent. */
    private static final int[] BASE = {0, 8368696, 16247203, 13092807, 16711680, 10526975, 10987431, 31744, 16777215,
            10791096, 9923917, 7368816, 4210943, 9402184, 16776437, 14188339, 11685080, 6724056, 15066419, 8375321,
            15892389, 5000268, 10066329, 5013401, 8339378, 3361970, 6704179, 6717235, 10040115, 1644825, 16445005,
            6085589, 4882687, 55610, 8476209, 7340544};

    private MapColors() {
    }

    /**
     * The ARGB texel of the pixel at {@code index} (row * 128 + column) holding {@code pixel}: base color 0 is a
     * faint dark checker (alpha 16 or 24) that lets the map's background show through, the rest are opaque and scaled
     * by the level (180, 220, 255 or 135 of 255).
     */
    public static int argb(byte pixel, int index) {
        int value = pixel & 255;
        int base = value / 4;
        if (base == 0 || base >= BASE.length) {
            return ((index + index / 128 & 1) * 8 + 16) << 24;
        }
        int level = switch (value & 3) {
            case 0 -> 180;
            case 1 -> 220;
            case 2 -> 255;
            default -> 135;
        };
        int color = BASE[base];
        int red = (color >> 16 & 255) * level / 255;
        int green = (color >> 8 & 255) * level / 255;
        int blue = (color & 255) * level / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
