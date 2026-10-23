package alkaline.renderer.vulkan;

/** Chooses an existing queue family for graphics and for this specific SDL surface. */
public record QueueFamilies(int graphics, int present) {
    public static QueueFamilies choose(boolean[] graphics, boolean[] present) {
        if (graphics.length != present.length) {
            throw new IllegalArgumentException("Queue family observations must have equal length.");
        }
        int graphicsIndex = -1;
        int presentIndex = -1;
        for (int index = 0; index < graphics.length; index++) {
            if (graphics[index] && present[index]) {
                return new QueueFamilies(index, index);
            }
            if (graphics[index] && graphicsIndex < 0) {
                graphicsIndex = index;
            }
            if (present[index] && presentIndex < 0) {
                presentIndex = index;
            }
        }
        return new QueueFamilies(graphicsIndex, presentIndex);
    }
}
