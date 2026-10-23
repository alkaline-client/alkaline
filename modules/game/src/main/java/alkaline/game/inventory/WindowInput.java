package alkaline.game.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * GuiContainer's mouse and key handling for an open window (the player's own or one the server opened): turns
 * presses, moves and releases over slots into window clicks, with vanilla's drag splitting, double-click
 * collecting, shift-clicks, number keys, pick block and throwing. It runs on the game thread beside the
 * {@link Container}, so every decision sees the real cursor stack rather than the frame the screen last drew.
 *
 * <p>Slots are window slot ids; {@link Container#OUTSIDE} is a press outside the window, and -1 is inside the
 * window but not over a slot.
 */
public final class WindowInput {
    /** Where clicks go: applied to the inventory and sent to the server. */
    @FunctionalInterface
    public interface Clicks {
        void click(int slot, int button, int mode);
    }

    /** Mouse buttons as GuiContainer numbers them; the middle button is pick block when that is bound to it. */
    public static final int LEFT = 0, RIGHT = 1, MIDDLE = 2;
    private static final long DOUBLE_CLICK_MILLIS = 250L;

    private final Container inventory;
    private final Clicks clicks;
    private int hovered = -1;
    private boolean dragSplitting;
    private int dragButton;
    private int dragLimit;
    private final List<Integer> dragSlots = new ArrayList<>();
    private boolean ignoreMouseUp;
    private boolean doubleClick;
    private int lastClickSlot = Integer.MIN_VALUE;
    private long lastClickTime;
    private int lastClickButton = -1;
    private ItemStack shiftClicked;

    public WindowInput(Container inventory, Clicks clicks) {
        this.inventory = inventory;
        this.clicks = clicks;
    }

    /** The slot under the pointer (-1 for none), which number keys, throw and pick block act on. */
    public void hover(int slot) {
        hovered = slot;
    }

    public int hovered() {
        return hovered;
    }

    /** GuiContainer.mouseClicked. {@code pickBlock}: the button is bound to pick block (middle click by default). */
    public void press(int slot, int button, boolean shift, boolean pickBlock, long nowMillis) {
        doubleClick = slot == lastClickSlot && nowMillis - lastClickTime < DOUBLE_CLICK_MILLIS && lastClickButton == button;
        ignoreMouseUp = false;
        if ((button == LEFT || button == RIGHT || pickBlock) && slot != -1 && !dragSplitting) {
            if (inventory.cursor() == null) {
                if (pickBlock) {
                    clicks.click(slot, button, Container.CLONE);
                } else {
                    int mode = Container.PICKUP;
                    if (slot != Container.OUTSIDE && shift) {
                        shiftClicked = inventory.slot(slot);
                        mode = Container.QUICK_MOVE;
                    } else if (slot == Container.OUTSIDE) {
                        mode = Container.THROW;
                    }
                    clicks.click(slot, button, mode);
                }
                ignoreMouseUp = true;
            } else {
                dragSplitting = true;
                dragButton = button;
                dragSlots.clear();
                dragLimit = pickBlock ? 2 : button == RIGHT ? 1 : 0;
            }
        }
        lastClickSlot = slot;
        lastClickTime = nowMillis;
        lastClickButton = button;
    }

    /** GuiContainer.mouseClickMove: with a button held, the pointer entered {@code slot}. */
    public void drag(int slot) {
        ItemStack cursor = inventory.cursor();
        if (dragSplitting && slot >= 0 && cursor != null && cursor.count() > dragSlots.size()
                && !dragSlots.contains(slot) && canAdd(slot, cursor) && inventory.valid(slot, cursor)) {
            dragSlots.add(slot);
        }
    }

    /** GuiContainer.mouseReleased. */
    public void release(int slot, int button, boolean shift, boolean pickBlock) {
        if (doubleClick && slot >= 0 && button == LEFT && inventory.canCollect(slot)) {
            if (shift) {
                if (shiftClicked != null) {
                    for (int other = 0; other < inventory.size(); other++) {
                        ItemStack stack = inventory.slot(other);
                        if (stack != null && inventory.canTake(other) && inventory.group(other) == inventory.group(slot)
                                && canAdd(other, shiftClicked)) {
                            clicks.click(other, button, Container.QUICK_MOVE);
                        }
                    }
                }
            } else {
                clicks.click(slot, button, Container.COLLECT);
            }
            doubleClick = false;
            lastClickTime = 0L;
        } else {
            if (dragSplitting && dragButton != button) {
                dragSplitting = false;
                dragSlots.clear();
                ignoreMouseUp = true;
                return;
            }
            if (ignoreMouseUp) {
                ignoreMouseUp = false;
                return;
            }
            if (dragSplitting && !dragSlots.isEmpty()) {
                clicks.click(Container.OUTSIDE, dragLimit << 2, Container.DRAG);
                for (int target : dragSlots) {
                    clicks.click(target, 1 | dragLimit << 2, Container.DRAG);
                }
                clicks.click(Container.OUTSIDE, 2 | dragLimit << 2, Container.DRAG);
            } else if (inventory.cursor() != null) {
                if (pickBlock) {
                    clicks.click(slot, button, Container.CLONE);
                } else if (slot != -1) {
                    boolean quick = slot != Container.OUTSIDE && shift;
                    if (quick) {
                        shiftClicked = slot >= 0 ? inventory.slot(slot) : null;
                    }
                    clicks.click(slot, button, quick ? Container.QUICK_MOVE : Container.PICKUP);
                }
            }
        }
        if (inventory.cursor() == null) {
            lastClickTime = 0L;
        }
        dragSplitting = false;
        dragSlots.clear();
    }

    /** GuiContainer.checkHotbarKeys: a number key swaps the hovered slot with that hotbar slot. */
    public void hotbarKey(int hotbar) {
        if (inventory.cursor() == null && hovered >= 0) {
            clicks.click(hovered, hotbar, Container.SWAP);
        }
    }

    /** The drop key over a slot throws one item, or the stack with Ctrl. */
    public void throwKey(boolean wholeStack) {
        if (hovered >= 0 && inventory.slot(hovered) != null) {
            clicks.click(hovered, wholeStack ? 1 : 0, Container.THROW);
        }
    }

    /** The pick block key over a slot (creative: a full stack of it on the cursor). */
    public void pickKey() {
        if (hovered >= 0 && inventory.slot(hovered) != null) {
            clicks.click(hovered, 0, Container.CLONE);
        }
    }

    /** The window closed: forget presses in progress. */
    public void reset() {
        dragSplitting = false;
        dragSlots.clear();
        ignoreMouseUp = false;
        doubleClick = false;
        lastClickTime = 0L;
        hovered = -1;
    }

    /**
     * What a drag in progress would leave in each slot it covers (GuiContainer.updateDragSplitting), for the screen
     * to preview; null when no drag is under way. Index by window slot.
     */
    public ItemStack[] dragPreview() {
        ItemStack cursor = inventory.cursor();
        if (!dragSplitting || cursor == null || dragSlots.isEmpty()) {
            return null;
        }
        ItemStack[] preview = new ItemStack[inventory.size()];
        for (int slot : dragSlots) {
            ItemStack present = inventory.slot(slot);
            int base = present == null ? 0 : present.count();
            int amount = switch (dragLimit) {
                case 0 -> cursor.count() / dragSlots.size();
                case 1 -> 1;
                default -> cursor.maxStackSize();
            } + base;
            amount = Math.min(amount, Math.min(cursor.maxStackSize(), inventory.limit(slot, cursor)));
            preview[slot] = cursor.withCount(amount);
        }
        return preview;
    }

    /** How many items stay on the cursor after the drag in progress, or -1 without one. */
    public int dragRemainder() {
        ItemStack[] preview = dragPreview();
        ItemStack cursor = inventory.cursor();
        if (preview == null || cursor == null) {
            return -1;
        }
        int left = cursor.count();
        for (int slot : dragSlots) {
            ItemStack present = inventory.slot(slot);
            left -= preview[slot].count() - (present == null ? 0 : present.count());
        }
        return Math.max(0, left);
    }

    private boolean canAdd(int slot, ItemStack stack) {
        ItemStack present = inventory.slot(slot);
        return present == null || present.sameKind(stack) && present.count() <= stack.maxStackSize();
    }
}
