package alkaline.client.ui;

import java.util.Objects;

abstract class AbstractMenuPage implements ClientPage {
    private final PageId id;
    private final PageAction backAction;
    private PageLayout layout;
    private int selectedIndex = -1;

    AbstractMenuPage(PageId id, PageAction backAction) {
        this.id = Objects.requireNonNull(id, "id");
        this.backAction = Objects.requireNonNull(backAction, "backAction");
        resize(1_152, 720);
    }

    @Override
    public final PageId id() {
        return id;
    }

    @Override
    public void enter() {
        selectedIndex = firstEnabledIndex();
        refreshLayout();
    }

    @Override
    public void exit() {
    }

    @Override
    public final void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Page viewport dimensions must be positive.");
        }
        if (layout != null && layout.width() == width && layout.height() == height) {
            return;
        }
        layout = createLayout(width, height, selectedIndex);
        selectedIndex = validSelectedIndex();
        refreshLayout();
    }

    @Override
    public final PageLayout layout() {
        return layout;
    }

    @Override
    public final PageAction handle(PageInput input) {
        Objects.requireNonNull(input, "input");
        if (input instanceof PageInput.Resized resized) {
            resize(resized.width(), resized.height());
            return PageAction.NONE;
        }
        if (input instanceof PageInput.MouseMoved moved) {
            selectAt(moved.x(), moved.y());
            return PageAction.NONE;
        }
        if (input instanceof PageInput.MouseClicked clicked) {
            if (clicked.button() != MouseButton.PRIMARY) {
                return PageAction.NONE;
            }
            int clickedIndex = controlAt(clicked.x(), clicked.y());
            if (clickedIndex < 0 || !layout.controls().get(clickedIndex).enabled()) {
                return PageAction.NONE;
            }
            selectedIndex = clickedIndex;
            refreshLayout();
            return activateSelected();
        }
        if (input instanceof PageInput.KeyPressed pressed) {
            return handleKey(pressed.key());
        }
        return PageAction.NONE;
    }

    protected abstract PageLayout createLayout(int width, int height, int selectedIndex);

    private PageAction handleKey(UiKey key) {
        return switch (key) {
            case UP, LEFT -> {
                moveSelection(-1);
                yield PageAction.NONE;
            }
            case DOWN, RIGHT, TAB -> {
                moveSelection(1);
                yield PageAction.NONE;
            }
            case ENTER -> activateSelected();
            case ESCAPE -> backAction;
            case OTHER -> PageAction.NONE;
        };
    }

    private PageAction activateSelected() {
        if (selectedIndex < 0 || selectedIndex >= layout.controls().size()) {
            return PageAction.NONE;
        }
        PageControl control = layout.controls().get(selectedIndex);
        return control.enabled() ? control.action() : PageAction.NONE;
    }

    private void selectAt(int x, int y) {
        int index = controlAt(x, y);
        if (index >= 0 && layout.controls().get(index).enabled()) {
            selectedIndex = index;
            refreshLayout();
        }
    }

    private int controlAt(int x, int y) {
        for (int index = 0; index < layout.controls().size(); index++) {
            if (layout.controls().get(index).bounds().contains(x, y)) {
                return index;
            }
        }
        return -1;
    }

    private void moveSelection(int direction) {
        int count = layout.controls().size();
        if (count == 0) {
            selectedIndex = -1;
            return;
        }
        int candidate = selectedIndex < 0 ? (direction > 0 ? 0 : count - 1) : selectedIndex;
        for (int step = 0; step < count; step++) {
            candidate = Math.floorMod(candidate + direction, count);
            if (layout.controls().get(candidate).enabled()) {
                selectedIndex = candidate;
                refreshLayout();
                return;
            }
        }
    }

    private int firstEnabledIndex() {
        for (int index = 0; index < layout.controls().size(); index++) {
            if (layout.controls().get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    private int validSelectedIndex() {
        if (selectedIndex >= 0 && selectedIndex < layout.controls().size()
                && layout.controls().get(selectedIndex).enabled()) {
            return selectedIndex;
        }
        return firstEnabledIndex();
    }

    private void refreshLayout() {
        layout = createLayout(layout.width(), layout.height(), selectedIndex);
    }
}
