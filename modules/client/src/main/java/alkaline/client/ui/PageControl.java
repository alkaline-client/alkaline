package alkaline.client.ui;

import java.util.Objects;

/** An action slot in a page layout; content and skin are renderer concerns. */
public record PageControl(String id, PixelRect bounds, boolean enabled, PageAction action) {
    public PageControl {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Page control ids must not be blank.");
        }
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(action, "action");
    }
}
