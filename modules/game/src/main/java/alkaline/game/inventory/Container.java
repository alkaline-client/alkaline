package alkaline.game.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Vanilla's Container.slotClick, shared by the player's own window ({@link Inventory}) and the windows servers
 * open ({@link Window}): a click is applied to the client's copy the moment it is sent, as
 * PlayerControllerMP.windowClick does, so the server confirms the same result; on a disagreement it resends the
 * window. Subclasses supply the slots and the per-slot rules (Slot.isItemValid, getItemStackLimit, canTakeStack,
 * onPickupFromSlot) and their transferStackInSlot. The cursor stack and the player's storage live in the
 * {@link Inventory}, which every window shares.
 */
public abstract class Container {
    /** The slot id of a click outside the window: it throws the cursor stack. */
    public static final int OUTSIDE = -999;

    /** Click modes (C0EPacketClickWindow): pick up or place, shift-click, number key, pick block, drop, drag, collect. */
    public static final int PICKUP = 0, QUICK_MOVE = 1, SWAP = 2, CLONE = 3, THROW = 4, DRAG = 5, COLLECT = 6;

    private short transaction;
    // Container.slotClick's drag state.
    private int dragEvent;
    private int dragMode = -1;
    private final List<Integer> dragSlots = new ArrayList<>();

    /** The player's inventory: the cursor stack and the main inventory and hotbar every window shows. */
    protected abstract Inventory player();

    /** Number of slots in the window, the player's 36 included. */
    public abstract int size();

    public abstract ItemStack slot(int index);

    /** Slot.putStack. */
    protected abstract void put(int index, ItemStack stack);

    /** Whether the slot is one of the player's own (Slot.inventory == InventoryPlayer), as number keys check. */
    protected abstract boolean playerSlot(int index);

    /** Container.transferStackInSlot: where a shift-click sends the stack; returns what moved, or null. */
    protected abstract ItemStack transfer(int index);

    /** Slot.isItemValid. */
    public boolean valid(int index, ItemStack stack) {
        return true;
    }

    /** Slot.getItemStackLimit. */
    public int limit(int index, ItemStack stack) {
        return 64;
    }

    /** Slot.canTakeStack. */
    public boolean canTake(int index) {
        return true;
    }

    /** Slot.onPickupFromSlot: what taking from a result slot consumes. */
    protected void pickedUp(int index, ItemStack taken) {
    }

    /** Container.canMergeSlot: whether a double click may gather from this slot (result slots are left alone). */
    protected boolean canCollect(int index) {
        return true;
    }

    /** Slot.decrStackSize: removes up to {@code amount}; result slots override this to give their whole stack. */
    protected ItemStack take(int index, int amount) {
        ItemStack stack = slot(index);
        if (stack == null) {
            return null;
        }
        if (amount >= stack.count()) {
            put(index, null);
            return stack;
        }
        put(index, stack.withCount(stack.count() - amount));
        return stack.withCount(amount);
    }

    /** Which inventory a slot belongs to, for the shift double click that moves every matching stack beside it. */
    public abstract int group(int index);

    /** The stack on the cursor. */
    public ItemStack cursor() {
        return player().cursor();
    }

    /** Container.getNextTransactionID: the action number the next click carries. */
    public short nextTransaction() {
        return ++transaction;
    }

    /** The slots a drag in progress has covered, for the screen to highlight. */
    public List<Integer> dragSlots() {
        return List.copyOf(dragSlots);
    }

    protected void resetDrag() {
        dragEvent = 0;
        dragSlots.clear();
    }

    private void cursor(ItemStack stack) {
        player().setCursor(stack);
    }

    // ---- Container.slotClick ------------------------------------------------------------------------------

    /**
     * Applies a click as Container.slotClick does and returns the stack the click packet reports (the clicked
     * slot's stack before the click, or what a shift-click moved).
     *
     * @param slot     the slot id, or {@link #OUTSIDE}; drag start and end use {@link #OUTSIDE} too
     * @param button   the mouse button, the hotbar slot for {@link #SWAP}, or the drag stage and mode for {@link #DRAG}
     * @param mode     one of {@link #PICKUP} ... {@link #COLLECT}
     * @param creative whether the player is in creative mode (pick block clones, middle drag fills stacks)
     */
    public ItemStack click(int slot, int button, int mode, boolean creative) {
        ItemStack result = null;
        boolean inside = slot >= 0 && slot < size();
        if (mode == DRAG) {
            drag(slot, button, creative);
        } else if (dragEvent != 0) {
            resetDrag();
        } else if ((mode == PICKUP || mode == QUICK_MOVE) && (button == 0 || button == 1)) {
            if (slot == OUTSIDE) {
                ItemStack held = cursor();
                if (held != null) {
                    // Thrown on the server: all of it, or one item.
                    cursor(button == 0 || held.count() <= 1 ? null : held.withCount(held.count() - 1));
                }
            } else if (mode == QUICK_MOVE) {
                if (!inside || !canTake(slot)) {
                    return null;
                }
                // Container.slotClick retries while the slot still holds the same item (crafting repeatedly).
                ItemStack moved = transfer(slot);
                if (moved != null) {
                    result = moved;
                    while (moved != null && slot(slot) != null && slot(slot).id() == result.id()) {
                        moved = transfer(slot);
                    }
                }
            } else if (inside) {
                result = pickup(slot, button);
            }
        } else if (mode == SWAP && button >= 0 && button < 9 && inside) {
            swap(slot, button);
        } else if (mode == CLONE && creative && cursor() == null && inside) {
            ItemStack stack = slot(slot);
            if (stack != null) {
                cursor(stack.withCount(stack.maxStackSize()));
            }
        } else if (mode == THROW && cursor() == null && inside) {
            ItemStack stack = slot(slot);
            if (stack != null && canTake(slot)) {
                ItemStack thrown = take(slot, button == 0 ? 1 : stack.count());
                pickedUp(slot, thrown);
            }
        } else if (mode == COLLECT && inside) {
            collect(slot, button);
        }
        return result;
    }

    /** Mode 0: pick up, place, merge or swap with the cursor. */
    private ItemStack pickup(int slot, int button) {
        ItemStack inSlot = slot(slot);
        ItemStack held = cursor();
        ItemStack result = inSlot;
        if (inSlot == null) {
            if (held != null && valid(slot, held)) {
                int amount = Math.min(button == 0 ? held.count() : 1, limit(slot, held));
                if (held.count() >= amount) {
                    put(slot, held.withCount(amount));
                    cursor(held.count() - amount == 0 ? null : held.withCount(held.count() - amount));
                }
            }
        } else if (canTake(slot)) {
            if (held == null) {
                int amount = button == 0 ? inSlot.count() : (inSlot.count() + 1) / 2;
                ItemStack taken = take(slot, amount);
                cursor(taken);
                pickedUp(slot, taken);
            } else if (valid(slot, held)) {
                if (inSlot.sameKind(held)) {
                    int amount = button == 0 ? held.count() : 1;
                    amount = Math.min(amount, limit(slot, held) - inSlot.count());
                    amount = Math.min(amount, held.maxStackSize() - inSlot.count());
                    // A negative amount (an overfull slot) moves items back to the cursor, as vanilla's does.
                    cursor(held.count() - amount == 0 ? null : held.withCount(held.count() - amount));
                    put(slot, inSlot.withCount(inSlot.count() + amount));
                } else if (held.count() <= limit(slot, held)) {
                    put(slot, held);
                    cursor(inSlot);
                }
            } else if (inSlot.id() == held.id() && held.maxStackSize() > 1 && inSlot.damage() == held.damage()
                    && Objects.equals(inSlot.tag(), held.tag())) {
                // A result slot: take the whole result onto a matching cursor stack if it fits.
                int amount = inSlot.count();
                if (amount > 0 && amount + held.count() <= held.maxStackSize()) {
                    cursor(held.withCount(held.count() + amount));
                    ItemStack taken = take(slot, amount);
                    pickedUp(slot, taken);
                }
            }
        }
        return result;
    }

    /** Mode 2: swap with a hotbar slot (number keys). */
    private void swap(int slot, int hotbar) {
        if (!canTake(slot)) {
            return;
        }
        Inventory inventory = player();
        ItemStack inHotbar = inventory.hotbar(hotbar);
        ItemStack inSlot = slot(slot);
        boolean own = playerSlot(slot);
        boolean fits = inHotbar == null || own && valid(slot, inHotbar);
        int empty = -1;
        if (!fits) {
            empty = inventory.firstEmpty();
            fits = empty > -1;
        }
        if (inSlot != null && fits) {
            inventory.setHotbar(hotbar, inSlot);
            if ((!own || !valid(slot, inHotbar)) && inHotbar != null) {
                if (empty > -1) {
                    inventory.add(inHotbar);
                    put(slot, null);
                    pickedUp(slot, inSlot);
                }
            } else {
                put(slot, inHotbar);
                pickedUp(slot, inSlot);
            }
        } else if (inSlot == null && inHotbar != null && valid(slot, inHotbar)) {
            inventory.setHotbar(hotbar, null);
            put(slot, inHotbar);
        }
    }

    /** Mode 6: a double click gathers matching stacks onto the cursor, partial stacks first. */
    private void collect(int slot, int button) {
        ItemStack held = cursor();
        if (held == null || slot(slot) != null && canTake(slot)) {
            return;
        }
        int size = size();
        int start = button == 0 ? 0 : size - 1;
        int step = button == 0 ? 1 : -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = start; index >= 0 && index < size && held.count() < held.maxStackSize(); index += step) {
                ItemStack stack = slot(index);
                if (stack != null && canAdd(index, held) && canTake(index) && canCollect(index)
                        && (pass != 0 || stack.count() != stack.maxStackSize())) {
                    int amount = Math.min(held.maxStackSize() - held.count(), stack.count());
                    ItemStack taken = take(index, amount);
                    held = held.withCount(held.count() + amount);
                    pickedUp(index, taken);
                }
            }
        }
        cursor(held);
    }

    /** Mode 5: the three-stage drag that spreads the cursor stack over the slots it passed. */
    private void drag(int slot, int button, boolean creative) {
        int previous = dragEvent;
        dragEvent = button & 3;
        ItemStack held = cursor();
        if ((previous != 1 || dragEvent != 2) && previous != dragEvent) {
            resetDrag();
        } else if (held == null) {
            resetDrag();
        } else if (dragEvent == 0) {
            dragMode = button >> 2 & 3;
            if (dragMode == 0 || dragMode == 1 || dragMode == 2 && creative) {
                dragEvent = 1;
                dragSlots.clear();
            } else {
                resetDrag();
            }
        } else if (dragEvent == 1) {
            if (slot >= 0 && slot < size() && canAdd(slot, held) && valid(slot, held)
                    && held.count() > dragSlots.size() && !dragSlots.contains(slot)) {
                dragSlots.add(slot);
            }
        } else if (dragEvent == 2) {
            if (!dragSlots.isEmpty()) {
                ItemStack spread = held;
                int left = held.count();
                for (int target : dragSlots) {
                    if (canAdd(target, held) && valid(target, held) && held.count() >= dragSlots.size()) {
                        int present = slot(target) != null ? slot(target).count() : 0;
                        int amount = switch (dragMode) {
                            case 0 -> (int) Math.floor(spread.count() / (float) dragSlots.size());
                            case 1 -> 1;
                            default -> spread.maxStackSize();
                        } + present;
                        amount = Math.min(amount, spread.maxStackSize());
                        amount = Math.min(amount, limit(target, spread));
                        left -= amount - present;
                        put(target, spread.withCount(amount));
                    }
                }
                cursor(left <= 0 ? null : spread.withCount(left));
            }
            resetDrag();
        } else {
            resetDrag();
        }
    }

    /**
     * Container.mergeItemStack into slots [start, end): first onto matching stacks, then into the first empty
     * slot. {@code count[0]} is the moving stack's size and is reduced by what was placed.
     */
    protected boolean merge(ItemStack stack, int[] count, int start, int end, boolean reverse) {
        boolean merged = false;
        int index = reverse ? end - 1 : start;
        if (stack.stackable()) {
            while (count[0] > 0 && (reverse ? index >= start : index < end)) {
                ItemStack present = slot(index);
                if (present != null && present.id() == stack.id() && present.damage() == stack.damage()
                        && Objects.equals(present.tag(), stack.tag())) {
                    int total = present.count() + count[0];
                    if (total <= stack.maxStackSize()) {
                        count[0] = 0;
                        put(index, present.withCount(total));
                        merged = true;
                    } else if (present.count() < stack.maxStackSize()) {
                        count[0] -= stack.maxStackSize() - present.count();
                        put(index, present.withCount(stack.maxStackSize()));
                        merged = true;
                    }
                }
                index += reverse ? -1 : 1;
            }
        }
        if (count[0] > 0) {
            index = reverse ? end - 1 : start;
            while (reverse ? index >= start : index < end) {
                if (slot(index) == null) {
                    put(index, stack.withCount(count[0]));
                    count[0] = 0;
                    merged = true;
                    break;
                }
                index += reverse ? -1 : 1;
            }
        }
        return merged;
    }

    /**
     * The shape every transferStackInSlot shares: {@code rule} merges the slot's stack somewhere (false when it
     * found no room), and what did not move stays in the slot. Returns the stack as it was, or null when nothing
     * moved. Most containers ({@code pickup}) also treat an unchanged size as no move and then call
     * onPickupFromSlot, which is how shift-clicking a crafting result uses up the grid; chests, hoppers and horses
     * do neither.
     */
    protected ItemStack move(int index, MergeRule rule, boolean pickup) {
        ItemStack stack = slot(index);
        if (stack == null) {
            return null;
        }
        int[] moving = {stack.count()};
        if (!rule.merge(stack, moving)) {
            return null;
        }
        put(index, moving[0] == 0 ? null : stack.withCount(moving[0]));
        if (pickup) {
            if (moving[0] == stack.count()) {
                return null;
            }
            pickedUp(index, stack.withCount(moving[0]));
        }
        return stack;
    }

    /** One transferStackInSlot branch: merge {@code stack} ({@code count[0]} items) somewhere. */
    @FunctionalInterface
    protected interface MergeRule {
        boolean merge(ItemStack stack, int[] count);
    }

    /** Container.canAddItemToSlot with the stack size mattering: empty, or the same kind with room to spare. */
    protected boolean canAdd(int index, ItemStack stack) {
        ItemStack present = slot(index);
        return present == null || present.sameKind(stack) && present.count() <= stack.maxStackSize();
    }
}
