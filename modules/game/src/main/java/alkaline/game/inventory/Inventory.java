package alkaline.game.inventory;

/**
 * The player's inventory window (window 0): 0 crafting result, 1-4 crafting grid, 5-8 armor (helmet to boots),
 * 9-35 main inventory, 36-44 hotbar, plus the stack held on the cursor. Game-thread state.
 *
 * <p>It is also the player's storage that every other {@link Window} shows below its own slots: the main inventory
 * and hotbar live here once, and the cursor stack is shared. Clicks follow {@link Container}'s rules with
 * ContainerPlayer's slots.
 */
public final class Inventory extends Container {
    public static final int SLOTS = 45;
    public static final int RESULT = 0;
    public static final int FIRST_GRID = 1;
    public static final int HELMET = 5;
    public static final int FIRST_MAIN = 9;
    public static final int FIRST_HOTBAR = 36;

    private final ItemStack[] slots = new ItemStack[SLOTS];
    private ItemStack cursor;
    private int selected;

    @Override
    protected Inventory player() {
        return this;
    }

    @Override
    public int size() {
        return SLOTS;
    }

    @Override
    public ItemStack slot(int index) {
        return index >= 0 && index < SLOTS ? slots[index] : null;
    }

    /** Sets a slot as the server sent it; a crafting grid change recomputes the result, as InventoryCrafting does. */
    public void set(int index, ItemStack item) {
        if (index >= 0 && index < SLOTS) {
            put(index, item);
        }
    }

    public void setAll(ItemStack[] items) {
        for (int index = 0; index < SLOTS; index++) {
            slots[index] = index < items.length ? items[index] : null;
        }
        craft();
    }

    public void clear() {
        java.util.Arrays.fill(slots, null);
        cursor = null;
        resetDrag();
    }

    /** The stack held on the cursor while a window is open. */
    @Override
    public ItemStack cursor() {
        return cursor;
    }

    public void setCursor(ItemStack item) {
        cursor = item;
    }

    /** The selected hotbar slot, 0..8. */
    public int selected() {
        return selected;
    }

    public void select(int slot) {
        selected = Math.floorMod(slot, 9);
    }

    public ItemStack hotbar(int slot) {
        return slots[FIRST_HOTBAR + slot];
    }

    void setHotbar(int slot, ItemStack stack) {
        slots[FIRST_HOTBAR + slot] = stack;
    }

    public ItemStack held() {
        return hotbar(selected);
    }

    /** Armor piece by equipment order: 0 boots, 1 leggings, 2 chestplate, 3 helmet. */
    public ItemStack armor(int piece) {
        return slots[HELMET + 3 - piece];
    }

    /** InventoryPlayer.getTotalArmorValue: the half-armor icons the HUD shows, 0..20. */
    public int armorPoints() {
        int total = 0;
        for (int piece = 0; piece < 4; piece++) {
            ItemStack item = armor(piece);
            if (item != null) {
                total += Items.armorPoints(item.id());
            }
        }
        return total;
    }

    /** The main inventory and hotbar as another window shows them: 0-26 main, 27-35 hotbar. */
    ItemStack storage(int index) {
        return slots[FIRST_MAIN + index];
    }

    void setStorage(int index, ItemStack stack) {
        slots[FIRST_MAIN + index] = stack != null && stack.count() <= 0 ? null : stack;
    }

    /**
     * The window closed (ContainerPlayer.onContainerClosed): the cursor stack and the crafting grid are thrown
     * on the server, so they leave the client's copy too.
     */
    public void closed() {
        cursor = null;
        for (int index = FIRST_GRID; index < FIRST_GRID + 4; index++) {
            slots[index] = null;
        }
        slots[RESULT] = null;
        resetDrag();
    }

    // ---- InventoryPlayer ----------------------------------------------------------------------------------

    /** InventoryPlayer.getFirstEmptyStack as a window slot: hotbar first, then the main inventory; -1 if full. */
    int firstEmpty() {
        for (int main = 0; main < 36; main++) {
            int index = main < 9 ? FIRST_HOTBAR + main : main;
            if (slots[index] == null) {
                return index;
            }
        }
        return -1;
    }

    /** InventoryPlayer.addItemStackToInventory: onto partial stacks, then into empty slots, hotbar first. */
    void add(ItemStack stack) {
        int left = stack.count();
        if (stack.stackable()) {
            for (int main = 0; main < 36 && left > 0; main++) {
                int index = main < 9 ? FIRST_HOTBAR + main : main;
                ItemStack present = slots[index];
                if (present != null && present.sameKind(stack) && present.count() < present.maxStackSize()) {
                    int moved = Math.min(left, present.maxStackSize() - present.count());
                    slots[index] = present.withCount(present.count() + moved);
                    left -= moved;
                }
            }
        }
        if (left > 0) {
            int empty = firstEmpty();
            if (empty >= 0) {
                slots[empty] = stack.withCount(left);
            }
        }
    }

    // ---- ContainerPlayer ----------------------------------------------------------------------------------

    @Override
    protected void put(int slot, ItemStack stack) {
        slots[slot] = stack != null && stack.count() <= 0 ? null : stack;
        if (grid(slot)) {
            craft();
        }
    }

    /** Every slot but the crafting grid and result belongs to the player's inventory (armor included). */
    @Override
    protected boolean playerSlot(int index) {
        return index >= HELMET;
    }

    /** ContainerPlayer.transferStackInSlot: where a shift-click sends a stack. Returns what moved, or null. */
    @Override
    protected ItemStack transfer(int index) {
        ItemStack stack = slots[index];
        if (stack == null) {
            return null;
        }
        int armorType = Items.armorType(stack.id());
        MergeRule rule;
        if (index == RESULT) {
            rule = (moving, count) -> merge(moving, count, FIRST_MAIN, SLOTS, true);
        } else if (index < FIRST_MAIN) {
            rule = (moving, count) -> merge(moving, count, FIRST_MAIN, SLOTS, false);
        } else if (armorType >= 0 && slots[HELMET + armorType] == null) {
            rule = (moving, count) -> merge(moving, count, HELMET + armorType, HELMET + armorType + 1, false);
        } else if (index < FIRST_HOTBAR) {
            rule = (moving, count) -> merge(moving, count, FIRST_HOTBAR, SLOTS, false);
        } else {
            rule = (moving, count) -> merge(moving, count, FIRST_MAIN, FIRST_HOTBAR, false);
        }
        return move(index, rule, true);
    }

    /** Slot.decrStackSize: the result slot always gives its whole stack. */
    @Override
    protected ItemStack take(int slot, int amount) {
        ItemStack stack = slots[slot];
        if (slot == RESULT && stack != null) {
            slots[RESULT] = null;
            return stack;
        }
        return super.take(slot, amount);
    }

    /** SlotCrafting.onPickupFromSlot: taking a result uses up one item from every grid slot. */
    @Override
    protected void pickedUp(int slot, ItemStack taken) {
        if (slot != RESULT || taken == null) {
            return;
        }
        ItemStack[] grid = {slots[1], slots[2], slots[3], slots[4]};
        ItemStack[] left = Crafting.remaining(grid);
        for (int index = 0; index < 4; index++) {
            ItemStack stack = grid[index];
            if (stack != null) {
                slots[FIRST_GRID + index] = stack.count() <= 1 ? null : stack.withCount(stack.count() - 1);
            }
            if (left[index] != null) {
                if (slots[FIRST_GRID + index] == null) {
                    slots[FIRST_GRID + index] = left[index];
                } else {
                    add(left[index]);
                }
            }
        }
        craft();
    }

    private void craft() {
        slots[RESULT] = Crafting.result(new ItemStack[]{slots[1], slots[2], slots[3], slots[4]});
    }

    private static boolean grid(int slot) {
        return slot >= FIRST_GRID && slot < FIRST_GRID + 4;
    }

    /** Slot.isItemValid: nothing goes into the result; armor slots take their piece (and pumpkins, heads on top). */
    @Override
    public boolean valid(int slot, ItemStack stack) {
        if (slot == RESULT) {
            return false;
        }
        if (slot >= HELMET && slot < FIRST_MAIN) {
            if (stack == null) {
                return false;
            }
            int type = slot - HELMET;
            if (Items.armor(stack.id())) {
                return Items.armorType(stack.id()) == type;
            }
            return (stack.id() == 86 || stack.id() == 397) && type == 0;
        }
        return true;
    }

    /** Slot.getItemStackLimit: armor slots hold one item. */
    @Override
    public int limit(int slot, ItemStack stack) {
        return slot >= HELMET && slot < FIRST_MAIN ? 1 : 64;
    }

    /** ContainerPlayer.canMergeSlot: a double click never gathers from the crafting result. */
    @Override
    protected boolean canCollect(int index) {
        return index != RESULT;
    }

    /** IInventories of the player's window: the result, the grid, and the player's own slots. */
    @Override
    public int group(int slot) {
        return slot == RESULT ? 0 : slot < HELMET ? 1 : 2;
    }
}
