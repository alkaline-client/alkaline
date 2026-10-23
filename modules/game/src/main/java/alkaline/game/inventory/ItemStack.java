package alkaline.game.inventory;

import alkaline.game.data.nbt.NBTTagCompound;

/**
 * An item as the protocol sends it: the 1.8.9 numeric id (blocks below 256, items from 256), stack size,
 * damage or variant, and the optional tag (enchantments, display name, leather color).
 */
public record ItemStack(int id, int count, int damage, NBTTagCompound tag) {
    /** The same item with another stack size. */
    public ItemStack withCount(int newCount) {
        return newCount == count ? this : new ItemStack(id, newCount, damage, tag);
    }

    public int maxStackSize() {
        return Items.maxStackSize(id);
    }

    /** ItemStack.isStackable: more than one fits, and it is not a worn tool. */
    public boolean stackable() {
        return maxStackSize() > 1 && (!Items.damageable(id) || damage <= 0);
    }

    /** Same item, variant and tag (ItemStack.isItemEqual plus areItemStackTagsEqual): such stacks merge. */
    public boolean sameKind(ItemStack other) {
        return other != null && other.id == id && other.damage == damage && java.util.Objects.equals(tag, other.tag);
    }

    /** Whether the stack shows vanilla's enchantment glint: enchanted, or an enchanted book. */
    public boolean glint() {
        if (id == 403) {
            return true;
        }
        return tag != null && tag.hasKey("ench", 9) && tag.getTagList("ench", 10).tagCount() > 0;
    }

    /** The dye color of leather armor ({@code display.color}), or -1 for the default leather brown. */
    public int leatherColor() {
        if (tag == null || !tag.hasKey("display", 10)) {
            return -1;
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        return display.hasKey("color", 3) ? display.getInteger("color") & 0xFFFFFF : -1;
    }

    /** EnchantmentHelper.getEnchantmentLevel: the level of enchantment {@code id} on this stack, 0 without it. */
    public int enchantment(int id) {
        if (tag == null) {
            return 0;
        }
        alkaline.game.data.nbt.NBTTagList list = tag.getTagList("ench", 10);
        for (int index = 0; index < list.tagCount(); index++) {
            NBTTagCompound entry = list.getCompoundTagAt(index);
            if (entry.getShort("id") == id) {
                return entry.getShort("lvl");
            }
        }
        return 0;
    }

    /** The custom name ({@code display.Name}), or null. */
    public String customName() {
        if (tag == null || !tag.hasKey("display", 10)) {
            return null;
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        return display.hasKey("Name", 8) ? display.getString("Name") : null;
    }
}
