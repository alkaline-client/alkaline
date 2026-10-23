package alkaline.client.ui;

/** Immutable top-left-origin rectangle used for page hit testing and rendering. */
public record PixelRect(int x, int y, int width, int height) {
    public PixelRect {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Page rectangles must have positive dimensions.");
        }
    }

    public boolean contains(int pointX, int pointY) {
        return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
    }
}
