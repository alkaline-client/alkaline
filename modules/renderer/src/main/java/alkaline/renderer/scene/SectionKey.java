package alkaline.renderer.scene;

/** Stable value key for integer section-grid coordinates. */
public record SectionKey(int x, int y, int z) implements Comparable<SectionKey> {
    @Override
    public int compareTo(SectionKey other) {
        int order = Integer.compare(x, other.x);
        if (order == 0) order = Integer.compare(y, other.y);
        if (order == 0) order = Integer.compare(z, other.z);
        return order;
    }
}
