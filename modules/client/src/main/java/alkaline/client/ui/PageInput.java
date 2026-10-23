package alkaline.client.ui;

import java.util.Objects;

/** Input delivered to a page by the platform adapter. */
public sealed interface PageInput permits PageInput.KeyPressed, PageInput.MouseMoved,
        PageInput.MouseClicked, PageInput.Resized, PageInput.TextEntered {
    record KeyPressed(UiKey key, boolean repeat) implements PageInput {
        public KeyPressed {
            Objects.requireNonNull(key, "key");
        }
    }

    record MouseMoved(int x, int y) implements PageInput {
    }

    record MouseClicked(int x, int y, MouseButton button) implements PageInput {
        public MouseClicked {
            Objects.requireNonNull(button, "button");
        }
    }

    record Resized(int width, int height) implements PageInput {
        public Resized {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Page viewport dimensions must be positive.");
            }
        }
    }

    record TextEntered(String text) implements PageInput {
        public TextEntered {
            Objects.requireNonNull(text, "text");
        }
    }
}
