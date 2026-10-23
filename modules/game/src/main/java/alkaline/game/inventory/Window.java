package alkaline.game.inventory;

import java.util.Arrays;
import java.util.List;

/**
 * A window the server opened (Open Window): a chest, furnace, crafting table, villager... Its own slots come first,
 * then the player's main inventory (27) and hotbar (9), which are the {@link Inventory}'s storage, shared. Clicks
 * follow {@link Container}'s rules with the slot rules and transferStackInSlot of the matching vanilla container
 * (ContainerChest, ContainerFurnace, ContainerWorkbench...). Game-thread state.
 *
 * <p>Results the server never sends are computed here as the vanilla client computes them: the crafting table's
 * (SlotCrafting is skipped by EntityPlayerMP) and the villager's (from MC|TrList's trades). The anvil's result and
 * every window property (furnace progress, enchantment costs, beacon levels...) come from the server.
 */
public final class Window extends Container {
    /** The vanilla screen a window type opens (EntityPlayerSP.displayGUIChest and displayGui). */
    public enum Type {
        CHEST, DISPENSER, HOPPER, FURNACE, BREWING_STAND, CRAFTING_TABLE, ENCHANTING_TABLE, ANVIL, BEACON,
        VILLAGER, HORSE
    }

    private static final int PLAYER_SLOTS = 36;

    private final int id;
    private final Type type;
    private final String titleJson;
    private final Inventory player;
    private final ItemStack[] own;
    private final int[] properties = new int[8];
    private final boolean horseArmor;
    private int level;
    private boolean creative;
    /** The anvil's rename text (MC|ItemName), and what its inputs cost (computed, as the vanilla client does). */
    private String repairName = "";
    private Anvil.Cost repair = new Anvil.Cost(0, 0);
    private List<Trade> trades = List.of();
    private int tradeIndex;
    private Trade currentTrade;

    private Window(int id, Type type, String titleJson, int ownSlots, Inventory player, boolean horseArmor) {
        this.id = id;
        this.type = type;
        this.titleJson = titleJson;
        this.player = player;
        this.own = new ItemStack[ownSlots];
        this.horseArmor = horseArmor;
        if (type == Type.ENCHANTING_TABLE) {
            Arrays.fill(properties, 4, 7, -1);
        }
    }

    /**
     * The window for an Open Window packet, or null for a type vanilla opens no screen for.
     *
     * @param guiType    the GUI id (minecraft:chest ... or EntityHorse)
     * @param slots      the size of the window's own inventory as the packet gives it
     * @param horseArmor for horse windows, whether the horse can wear armor (a plain horse)
     */
    public static Window open(int id, String guiType, String titleJson, int slots, Inventory player,
                              boolean horseArmor) {
        Type type = switch (guiType) {
            case "minecraft:chest", "minecraft:container" -> Type.CHEST;
            case "minecraft:dispenser", "minecraft:dropper" -> Type.DISPENSER;
            case "minecraft:hopper" -> Type.HOPPER;
            case "minecraft:furnace" -> Type.FURNACE;
            case "minecraft:brewing_stand" -> Type.BREWING_STAND;
            case "minecraft:crafting_table" -> Type.CRAFTING_TABLE;
            case "minecraft:enchanting_table" -> Type.ENCHANTING_TABLE;
            case "minecraft:anvil" -> Type.ANVIL;
            case "minecraft:beacon" -> Type.BEACON;
            case "minecraft:villager" -> Type.VILLAGER;
            case "EntityHorse" -> Type.HORSE;
            // NetHandlerPlayClient.handleOpenWindow: other types with slots show as a chest, without none.
            default -> slots > 0 ? Type.CHEST : null;
        };
        if (type == null) {
            return null;
        }
        int ownSlots = switch (type) {
            case CHEST -> slots / 9 * 9;
            case DISPENSER -> 9;
            case HOPPER -> slots;
            case FURNACE, ANVIL, VILLAGER -> 3;
            case BREWING_STAND -> 4;
            case CRAFTING_TABLE -> 10;
            case ENCHANTING_TABLE -> 2;
            case BEACON -> 1;
            case HORSE -> slots > 2 ? 17 : 2;
        };
        return new Window(id, type, titleJson, ownSlots, player, horseArmor);
    }

    public int id() {
        return id;
    }

    public Type type() {
        return type;
    }

    /** The window title as a chat component (JSON). */
    public String titleJson() {
        return titleJson;
    }

    /** Slots of the window's own inventory, before the player's 36. */
    public int ownSlots() {
        return own.length;
    }

    /** A chest's rows. */
    public int rows() {
        return own.length / 9;
    }

    /** Whether a horse window has the donkey or mule chest (15 more slots). */
    public boolean horseChest() {
        return type == Type.HORSE && own.length > 2;
    }

    /** Whether a horse window's armor slot is usable. */
    public boolean horseArmor() {
        return horseArmor;
    }

    /** The player's experience level and creative mode, which enchanting and the anvil check. */
    public void player(int experienceLevel, boolean creativeMode) {
        level = experienceLevel;
        creative = creativeMode;
        if (type == Type.ANVIL) {
            repair = Anvil.cost(own[0], own[1], repairName, creative);
        }
    }

    @Override
    protected Inventory player() {
        return player;
    }

    @Override
    public int size() {
        return own.length + PLAYER_SLOTS;
    }

    @Override
    public ItemStack slot(int index) {
        if (index < 0 || index >= size()) {
            return null;
        }
        return index < own.length ? own[index] : player.storage(index - own.length);
    }

    /** Sets a slot as the server sent it (Set Slot), recomputing what depends on it. */
    public void set(int index, ItemStack item) {
        if (index >= 0 && index < size()) {
            put(index, item);
        }
    }

    /** Window Items: every slot, the player's included. */
    public void setAll(ItemStack[] items) {
        for (int index = 0; index < size(); index++) {
            ItemStack item = index < items.length ? items[index] : null;
            if (index < own.length) {
                own[index] = item;
            } else {
                player.setStorage(index - own.length, item);
            }
        }
        changed(-1);
    }

    // ---- window properties --------------------------------------------------------------------------------

    /**
     * Window Property, as each container's updateProgressBar stores it: furnace 0 burn time left, 1 the fuel's
     * burn time, 2 cook time, 3 total cook time; brewing stand 0 brew time; enchanting table 0-2 level costs,
     * 3 the name seed, 4-6 enchantment hints (id | level << 8, -1 for none); anvil 0 the cost; beacon 0 levels,
     * 1 primary effect, 2 secondary effect.
     */
    public void property(int index, int value) {
        if (index >= 0 && index < properties.length) {
            properties[index] = value;
        }
    }

    public int property(int index) {
        return index >= 0 && index < properties.length ? properties[index] : 0;
    }

    // ---- enchanting table ---------------------------------------------------------------------------------

    /** Lapis lazuli in the enchanting table (ContainerEnchantment.getLapisAmount). */
    public int lapis() {
        ItemStack stack = own.length > 1 ? own[1] : null;
        return stack == null ? 0 : stack.count();
    }

    /** ContainerEnchantment.enchantItem's client check: whether option 0..2 can be chosen now. */
    public boolean canEnchant(int option) {
        if (type != Type.ENCHANTING_TABLE || option < 0 || option > 2) {
            return false;
        }
        int cost = option + 1;
        if (lapis() < cost && !creative) {
            return false;
        }
        int levels = properties[option];
        return levels > 0 && own[0] != null && (level >= cost && level >= levels || creative);
    }

    // ---- anvil ----------------------------------------------------------------------------------------------

    /** The anvil's cost in levels (ContainerRepair.maximumCost), 0 without a result. */
    public int repairCost() {
        return type == Type.ANVIL ? repair.levels() : 0;
    }

    /** ContainerRepair.updateItemName: the rename text ("" keeps or clears no custom name). */
    public void rename(String name) {
        repairName = name == null ? "" : name;
        changed(0);
    }

    // ---- villager -----------------------------------------------------------------------------------------

    public List<Trade> trades() {
        return trades;
    }

    /** MC|TrList: the villager's trades. */
    public void trades(List<Trade> list) {
        trades = List.copyOf(list);
        merchantResult();
    }

    public int tradeIndex() {
        return tradeIndex;
    }

    /** The trade the player turned to (GuiMerchant's arrows, sent as MC|TrSel). */
    public void selectTrade(int index) {
        tradeIndex = index;
        merchantResult();
    }

    /** InventoryMerchant.resetRecipeAndSlots: the result of the trade the inputs pay for. */
    private void merchantResult() {
        if (type != Type.VILLAGER) {
            return;
        }
        currentTrade = null;
        ItemStack first = own[0];
        ItemStack second = own[1];
        if (first == null) {
            first = second;
            second = null;
        }
        ItemStack result = null;
        if (first != null) {
            Trade trade = usable(first, second);
            if (trade == null && second != null) {
                trade = usable(second, first);
            }
            if (trade != null && !trade.disabled()) {
                currentTrade = trade;
                result = trade.result();
            }
        }
        own[2] = result;
    }

    /** MerchantRecipeList.canRecipeBeUsed: the selected trade (or, for index 0, the first) the inputs pay for. */
    private Trade usable(ItemStack first, ItemStack second) {
        if (tradeIndex > 0 && tradeIndex < trades.size()) {
            Trade trade = trades.get(tradeIndex);
            boolean hasSecond = trade.second() != null;
            if (!Trade.pays(first, trade.first())
                    || (second != null || hasSecond) && (!hasSecond || !Trade.pays(second, trade.second()))
                    || first.count() < trade.first().count()
                    || hasSecond && second.count() < trade.second().count()) {
                return null;
            }
            return trade;
        }
        for (Trade trade : trades) {
            boolean hasSecond = trade.second() != null;
            if (Trade.pays(first, trade.first()) && first.count() >= trade.first().count()
                    && (!hasSecond && second == null || hasSecond && Trade.pays(second, trade.second())
                    && second.count() >= trade.second().count())) {
                return trade;
            }
        }
        return null;
    }

    // ---- slots --------------------------------------------------------------------------------------------

    @Override
    protected void put(int index, ItemStack stack) {
        ItemStack value = stack != null && stack.count() <= 0 ? null : stack;
        if (index < own.length) {
            own[index] = value;
            changed(index);
        } else {
            player.setStorage(index - own.length, value);
        }
    }

    /** onCraftMatrixChanged: the crafting result, the villager's result, the enchantment table's cleared costs. */
    private void changed(int index) {
        switch (type) {
            case CRAFTING_TABLE -> {
                if (index != 0) {
                    own[0] = Crafting.result(Arrays.copyOfRange(own, 1, 10));
                }
            }
            case VILLAGER -> {
                // Window Items sets the result last, so the server's result stands there.
                if (index >= 0 && index != 2) {
                    merchantResult();
                }
            }
            case ANVIL -> repair = Anvil.cost(own[0], own[1], repairName, creative);
            case ENCHANTING_TABLE -> {
                if (own[0] == null) {
                    // ContainerEnchantment.onCraftMatrixChanged on the client: no item, no offers.
                    Arrays.fill(properties, 0, 3, 0);
                    Arrays.fill(properties, 4, 7, -1);
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected boolean playerSlot(int index) {
        return index >= own.length;
    }

    @Override
    public boolean valid(int index, ItemStack stack) {
        if (index >= own.length) {
            return true;
        }
        if (stack == null) {
            return false;
        }
        return switch (type) {
            case FURNACE -> index == 0 || index == 1 && (Smelting.fuel(stack) || stack.id() == BUCKET);
            case BREWING_STAND -> index < 3 ? potion(stack) : Smelting.brewingIngredient(stack);
            case CRAFTING_TABLE, ANVIL -> index != (type == Type.ANVIL ? 2 : 0);
            case VILLAGER -> index != 2;
            case ENCHANTING_TABLE -> index == 0 || stack.id() == DYE && stack.damage() == 4;
            case BEACON -> stack.id() == 388 || stack.id() == 264 || stack.id() == 266 || stack.id() == 265;
            case HORSE -> switch (index) {
                case 0 -> stack.id() == SADDLE && own[0] == null;
                case 1 -> horseArmor && stack.id() >= 417 && stack.id() <= 419;
                default -> true;
            };
            default -> true;
        };
    }

    @Override
    public int limit(int index, ItemStack stack) {
        if (index >= own.length) {
            return 64;
        }
        return switch (type) {
            case FURNACE -> index == 1 && stack != null && stack.id() == BUCKET ? 1 : 64;
            case BREWING_STAND -> index < 3 ? 1 : 64;
            case ENCHANTING_TABLE -> index == 0 ? 1 : 64;
            case BEACON -> 1;
            default -> 64;
        };
    }

    @Override
    public boolean canTake(int index) {
        if (type == Type.ANVIL && index == 2) {
            int cost = repair.levels();
            return (creative || level >= cost) && cost > 0 && own[2] != null;
        }
        return true;
    }

    /** Result slots (InventoryCraftResult, InventoryMerchant's third) give their whole stack. */
    @Override
    protected ItemStack take(int index, int amount) {
        if (resultSlot(index) && own[index] != null) {
            ItemStack stack = own[index];
            own[index] = null;
            return stack;
        }
        return super.take(index, amount);
    }

    private boolean resultSlot(int index) {
        return type == Type.CRAFTING_TABLE && index == 0 || (type == Type.ANVIL || type == Type.VILLAGER) && index == 2;
    }

    @Override
    protected void pickedUp(int index, ItemStack taken) {
        if (taken == null) {
            return;
        }
        if (type == Type.CRAFTING_TABLE && index == 0) {
            // SlotCrafting.onPickupFromSlot: one of everything in the grid, and container items left behind.
            ItemStack[] grid = Arrays.copyOfRange(own, 1, 10);
            ItemStack[] left = Crafting.remaining(grid);
            for (int cell = 0; cell < 9; cell++) {
                ItemStack stack = grid[cell];
                if (stack != null) {
                    own[1 + cell] = stack.count() <= 1 ? null : stack.withCount(stack.count() - 1);
                }
                if (left[cell] != null) {
                    if (own[1 + cell] == null) {
                        own[1 + cell] = left[cell];
                    } else {
                        player.add(left[cell]);
                    }
                }
            }
            changed(1);
        } else if (type == Type.ANVIL && index == 2) {
            // ContainerRepair's output slot: the left item is used up, the right one by the repair's material.
            int material = repair.material();
            own[0] = null;
            own[1] = material > 0 && own[1] != null && own[1].count() > material
                    ? own[1].withCount(own[1].count() - material) : null;
            changed(0);
        } else if (type == Type.VILLAGER && index == 2 && currentTrade != null) {
            // SlotMerchantResult.onPickupFromSlot: the trade takes its price from the inputs, either way round.
            ItemStack[] paid = pay(currentTrade, own[0], own[1]);
            if (paid == null) {
                ItemStack[] swapped = pay(currentTrade, own[1], own[0]);
                paid = swapped == null ? null : new ItemStack[]{swapped[1], swapped[0]};
            }
            if (paid != null) {
                own[0] = paid[0];
                own[1] = paid[1];
                merchantResult();
            }
        }
    }

    /** SlotMerchantResult.doTrade: what is left of the inputs after paying, or null when they do not pay. */
    private static ItemStack[] pay(Trade trade, ItemStack first, ItemStack second) {
        if (first == null || first.id() != trade.first().id()) {
            return null;
        }
        ItemStack wanted = trade.second();
        if (wanted != null && second != null && wanted.id() == second.id()) {
            return new ItemStack[]{less(first, trade.first().count()), less(second, wanted.count())};
        }
        if (wanted == null && second == null) {
            return new ItemStack[]{less(first, trade.first().count()), null};
        }
        return null;
    }

    private static ItemStack less(ItemStack stack, int amount) {
        return stack.count() - amount <= 0 ? null : stack.withCount(stack.count() - amount);
    }

    @Override
    protected boolean canCollect(int index) {
        // ContainerWorkbench.canMergeSlot: never gather from the crafting result.
        return !(type == Type.CRAFTING_TABLE && index == 0);
    }

    @Override
    public int group(int index) {
        if (index >= own.length) {
            return 2;
        }
        return switch (type) {
            case CRAFTING_TABLE -> index == 0 ? 0 : 1;
            case ANVIL -> index == 2 ? 0 : 1;
            default -> 1;
        };
    }

    // ---- transferStackInSlot ------------------------------------------------------------------------------

    @Override
    protected ItemStack transfer(int index) {
        ItemStack stack = slot(index);
        if (stack == null) {
            return null;
        }
        int n = own.length;
        int hotbar = n + 27;
        int end = n + PLAYER_SLOTS;
        return switch (type) {
            case CHEST, HOPPER -> move(index, index < n ? range(n, end, true) : range(0, n, false), false);
            case DISPENSER -> move(index, index < n ? range(n, end, true) : range(0, n, false), true);
            case FURNACE -> {
                MergeRule rule;
                if (index == 2) {
                    rule = range(3, 39, true);
                } else if (index != 0 && index != 1) {
                    if (Smelting.smeltable(stack)) {
                        rule = range(0, 1, false);
                    } else if (Smelting.fuel(stack)) {
                        rule = range(1, 2, false);
                    } else {
                        rule = shuffle(index, n, hotbar, end);
                    }
                } else {
                    rule = range(3, 39, false);
                }
                yield move(index, rule, true);
            }
            case BREWING_STAND -> {
                MergeRule rule;
                if (index > 3) {
                    if (own[3] == null && Smelting.brewingIngredient(stack)) {
                        rule = range(3, 4, false);
                    } else if (potion(stack)) {
                        rule = range(0, 3, false);
                    } else {
                        rule = shuffle(index, n, hotbar, end);
                    }
                } else {
                    rule = range(4, 40, true);
                }
                yield move(index, rule, true);
            }
            case CRAFTING_TABLE -> {
                MergeRule rule = index == 0 ? range(10, 46, true) : index < 10 ? range(10, 46, false)
                        : shuffle(index, n, hotbar, end);
                yield move(index, rule, true);
            }
            case ENCHANTING_TABLE -> {
                MergeRule rule;
                if (index == 0 || index == 1) {
                    rule = range(2, 38, true);
                } else if (stack.id() == DYE && stack.damage() == 4) {
                    rule = range(1, 2, true);
                } else {
                    // A single item goes onto the table (its tag only when it is alone), never a stack.
                    rule = (moving, count) -> {
                        if (own[0] != null || !valid(0, moving)) {
                            return false;
                        }
                        if (moving.tag() != null && count[0] == 1) {
                            put(0, moving);
                        } else {
                            put(0, new ItemStack(moving.id(), 1, moving.damage(), null));
                        }
                        count[0]--;
                        return true;
                    };
                }
                yield move(index, rule, true);
            }
            case ANVIL -> {
                MergeRule rule = index == 2 ? range(3, 39, true)
                        : index != 0 && index != 1 ? range(0, 2, false) : range(3, 39, false);
                yield move(index, rule, true);
            }
            case BEACON -> {
                MergeRule rule;
                if (index == 0) {
                    rule = range(1, 37, true);
                } else if (own[0] == null && valid(0, stack) && stack.count() == 1) {
                    rule = range(0, 1, false);
                } else {
                    rule = shuffle(index, n, hotbar, end);
                }
                yield move(index, rule, true);
            }
            case VILLAGER -> {
                MergeRule rule = index == 2 ? range(3, 39, true)
                        : index != 0 && index != 1 ? shuffle(index, n, hotbar, end) : range(3, 39, false);
                yield move(index, rule, true);
            }
            case HORSE -> {
                MergeRule rule;
                if (index < n) {
                    rule = range(n, end, true);
                } else if (valid(1, stack) && own[1] == null) {
                    rule = range(1, 2, false);
                } else if (valid(0, stack)) {
                    rule = range(0, 1, false);
                } else if (n > 2) {
                    rule = range(2, n, false);
                } else {
                    yield null;
                }
                yield move(index, rule, false);
            }
        };
    }

    private MergeRule range(int start, int end, boolean reverse) {
        return (moving, count) -> merge(moving, count, start, end, reverse);
    }

    /** The usual fallback: the main inventory to the hotbar and back. */
    private MergeRule shuffle(int index, int n, int hotbar, int end) {
        if (index >= n && index < hotbar) {
            return range(hotbar, end, false);
        }
        if (index >= hotbar && index < end) {
            return range(n, hotbar, false);
        }
        return range(n, end, false);
    }

    /** ContainerBrewingStand.Potion.canHoldPotion: potions and glass bottles. */
    private static boolean potion(ItemStack stack) {
        return stack != null && (stack.id() == 373 || stack.id() == 374);
    }

    private static final int BUCKET = 325, DYE = 351, SADDLE = 329;
}
