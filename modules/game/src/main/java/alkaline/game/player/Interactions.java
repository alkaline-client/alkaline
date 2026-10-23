package alkaline.game.player;

import alkaline.game.inventory.ItemStack;

/**
 * What a right click does on the client side of 1.8.9, so the client sends what vanilla sends: which blocks
 * Block.onBlockActivated answers true for on the client (the click is used up, the held item is not used), which
 * held items act on the clicked block (Item.onItemUse places or uses them), and which items are then used over
 * time (Item.getMaxItemUseDuration).
 */
public final class Interactions {
    private static final boolean[] ACTIVATABLE = new boolean[4096];

    static {
        int[] ids = {23, 25, 26, 54, 58, 61, 62, 63, 64, 68, 69, 71, 77, 85, 92, 93, 94, 96, 107, 113, 116, 117, 118,
                122, 130, 137, 138, 143, 145, 146, 149, 150, 151, 154, 158, 167, 178, 183, 184, 185, 186, 187, 188, 189,
                190, 191, 192, 193, 194, 195, 196, 197};
        for (int id : ids) {
            ACTIVATABLE[id] = true;
        }
    }

    private Interactions() {
    }

    /**
     * Blocks whose onBlockActivated returns true on the client: containers, doors, trapdoors and gates, levers and
     * buttons, redstone repeaters and comparators, beds, cakes, cauldrons, signs, fences (leads), note blocks...
     */
    public static boolean activatable(int blockId) {
        return blockId >= 0 && blockId < ACTIVATABLE.length && ACTIVATABLE[blockId];
    }

    /** Held items whose onItemUse places or acts on the clicked block: blocks, and items that place one. */
    public static boolean placeable(int itemId) {
        if (itemId < 256) {
            return true;
        }
        return switch (itemId) {
            // Sign, doors, bed, redstone, repeater, comparator, cake, sugar canes, cauldron, brewing stand, flower
            // pot, skull, string, item frame, painting, armor stand, banner, firework, spawn egg.
            case 323, 324, 330, 427, 428, 429, 430, 431, 355, 331, 356, 404, 354, 338, 380, 379, 390, 397, 287,
                 389, 321, 416, 425, 401, 383 -> true;
            default -> false;
        };
    }

    /**
     * Ticks the held item is used for once used (0 for items used at once or not at all): food and drinks 32
     * (food only when hungry or always edible, never in creative), bows while arrows last and swords until let go.
     */
    public static int useDuration(ItemStack held, int food, boolean creative, boolean hasArrows) {
        if (held == null) {
            return 0;
        }
        int id = held.id();
        return switch (id) {
            case 260, 282, 297, 319, 320, 349, 350, 357, 360, 363, 364, 365, 366, 367, 375, 391, 392, 393, 394, 396,
                 400, 411, 412, 413, 423, 424 -> !creative && food < 20 ? 32 : 0;
            case 322 -> creative ? 0 : 32; // golden apples are always edible
            case 373 -> (held.damage() & 16384) == 0 ? 32 : 0; // splash potions are thrown
            case 335 -> 32; // milk
            case 261 -> creative || hasArrows ? 72000 : 0;
            case 267, 268, 272, 276, 283 -> 72000; // swords block
            default -> 0;
        };
    }
}
