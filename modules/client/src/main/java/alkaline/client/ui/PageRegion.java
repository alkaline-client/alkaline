package alkaline.client.ui;

import java.util.Objects;

/** A non-interactive structural region that a renderer can skin independently. */
public record PageRegion(String id, PixelRect bounds, RegionKind kind) {
    public PageRegion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Page region ids must not be blank.");
        }
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(kind, "kind");
    }

    public enum RegionKind {
        PANEL,
        HEADER,
        LIST,
        FOOTER
    }
}
