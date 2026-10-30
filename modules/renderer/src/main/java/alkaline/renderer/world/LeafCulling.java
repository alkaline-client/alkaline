package alkaline.renderer.world;

/**
 * Which faces between two leaf blocks the mesher drops. Vanilla's fancy leaves draw every leaf face that no opaque
 * cube covers, so a canopy is full of faces between leaves, seen only through the cutout holes of the leaves in
 * front of them.
 */
public enum LeafCulling {
    /** Vanilla: every face. */
    OFF,
    /**
     * A face toward a leaf block is dropped when the block beyond that leaf is not air either: to see it, the eye
     * looks through the holes of two leaf layers. The outer two layers of every canopy keep their faces.
     */
    DEEP,
    /** Every face toward a leaf block is dropped: canopies are shells, and their holes show what lies behind. */
    ALL
}
