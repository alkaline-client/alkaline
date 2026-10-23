package alkaline.game.inventory;

/**
 * One villager trade (MerchantRecipe) as MC|TrList sends it: what it costs (the second item optional), what it
 * gives, and whether it is used up for now.
 */
public record Trade(ItemStack first, ItemStack second, ItemStack result, boolean disabled, int uses, int maxUses) {
    /** MerchantRecipeList.func_181078_a: the same item and damage, and the offered tag holds the wanted one. */
    static boolean pays(ItemStack offered, ItemStack wanted) {
        if (offered == null || wanted == null || offered.id() != wanted.id() || offered.damage() != wanted.damage()) {
            return false;
        }
        return wanted.tag() == null || offered.tag() != null && offered.tag().equals(wanted.tag());
    }
}
