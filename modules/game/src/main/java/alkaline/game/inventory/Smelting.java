package alkaline.game.inventory;

/**
 * What the furnace and the brewing stand accept, for shift-clicks and slot checks: FurnaceRecipes' inputs,
 * TileEntityFurnace.getItemBurnTime's fuels, and Item.isPotionIngredient's brewing ingredients (1.8.9).
 */
public final class Smelting {
    private Smelting() {
    }

    /** FurnaceRecipes.getSmeltingResult(stack) != null. */
    public static boolean smeltable(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return switch (stack.id()) {
            // Ores, sand, cobblestone, clay, cactus, logs, netherrack, and raw meat, potatoes and clay balls.
            case 14, 15, 56, 12, 4, 82, 81, 17, 162, 129, 87, 16, 73, 74, 21, 153,
                 319, 363, 365, 411, 423, 337, 392 -> true;
            case 98 -> stack.damage() == 0;   // stone bricks crack
            case 19 -> stack.damage() == 1;   // wet sponge dries
            case 349 -> stack.damage() <= 1;  // cod and salmon cook
            default -> false;
        };
    }

    /** TileEntityFurnace.isItemFuel: burn time above zero. */
    public static boolean fuel(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return switch (stack.id()) {
            // Wooden blocks (Material.wood), the wooden slab and the coal block.
            case 5, 17, 162, 25, 47, 53, 54, 58, 72, 84, 85, 96, 99, 100, 107, 126, 134, 135, 136, 146, 151, 163,
                 164, 173, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192 -> true;
            // Saplings, wooden tools, sticks, coal, the lava bucket and blaze rods.
            case 6, 268, 269, 270, 271, 290, 280, 263, 327, 369 -> true;
            default -> false;
        };
    }

    /** Item.isPotionIngredient (and the pufferfish, ItemFishFood's only one). */
    public static boolean brewingIngredient(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return switch (stack.id()) {
            case 289, 331, 348, 353, 370, 372, 375, 376, 377, 378, 382, 396, 414 -> true;
            case 349 -> stack.damage() == 3;
            default -> false;
        };
    }
}
