package alkaline.client.ui;

import java.util.List;
import java.util.Objects;

/** Immutable structural description of the active page for any renderer or accessibility layer. */
public record PageLayout(PageId page, int width, int height, List<PageRegion> regions,
                         List<PageControl> controls, int selectedIndex) {
    public PageLayout {
        Objects.requireNonNull(page, "page");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Page layout dimensions must be positive.");
        }
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (selectedIndex < -1 || selectedIndex >= controls.size()) {
            throw new IllegalArgumentException("Selected control index is outside the page controls.");
        }
    }
}
