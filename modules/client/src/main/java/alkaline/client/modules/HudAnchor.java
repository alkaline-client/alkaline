package alkaline.client.modules;

/**
 * The point of the screen a HUD element is placed from. Offsets grow inward from edges and corners, so an
 * element anchored bottom right keeps its distance from that corner when the window or GUI scale changes.
 */
public enum HudAnchor {
    TOP_LEFT(0, 0), TOP(1, 0), TOP_RIGHT(2, 0),
    LEFT(0, 1), CENTER(1, 1), RIGHT(2, 1),
    BOTTOM_LEFT(0, 2), BOTTOM(1, 2), BOTTOM_RIGHT(2, 2);

    private final int column;
    private final int row;

    HudAnchor(int column, int row) {
        this.column = column;
        this.row = row;
    }

    /** 0 left, 1 center, 2 right. */
    public int column() {
        return column;
    }

    /** 0 top, 1 middle, 2 bottom. */
    public int row() {
        return row;
    }

    /** Fraction of the screen width the anchor sits at, and of the element's own width it aligns. */
    public float horizontal() {
        return column * 0.5f;
    }

    public float vertical() {
        return row * 0.5f;
    }

    /** The anchor of the screen third (by column and row) a point falls in. */
    public static HudAnchor of(int column, int row) {
        return values()[row * 3 + column];
    }
}
