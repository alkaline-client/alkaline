package alkaline.game.inventory;

import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ContainerRepair.updateRepairOutput's cost, which the vanilla client computes itself (the server sends the anvil's
 * cost only when the window opens): repairing with material or a second item, merging enchantments, renaming, and
 * the previous work (RepairCost). The result item comes from the server; the client needs the cost to show it and
 * to know whether the result can be taken.
 */
public final class Anvil {
    /** The cost in levels (0: no result) and how many of the right-hand material a repair uses. */
    public record Cost(int levels, int material) {
        static final Cost NONE = new Cost(0, 0);
    }

    // Enchantment by id: max level, weight, and what it applies to (EnumEnchantmentType).
    private static final int ALL = 0, ARMOR = 1, FEET = 2, HEAD = 3, TORSO = 4, WEAPON = 5, DIGGER = 6, BREAKABLE = 7,
            BOW = 8, FISHING_ROD = 9;
    private static final int[][] ENCHANTMENTS = new int[256][];

    static {
        define(0, 4, 10, ARMOR);
        define(1, 4, 5, ARMOR);
        define(2, 4, 5, FEET);
        define(3, 4, 2, ARMOR);
        define(4, 4, 5, ARMOR);
        define(5, 3, 2, HEAD);
        define(6, 1, 2, HEAD);
        define(7, 3, 1, TORSO);
        define(8, 3, 2, FEET);
        define(16, 5, 10, WEAPON);
        define(17, 5, 5, WEAPON);
        define(18, 5, 5, WEAPON);
        define(19, 2, 5, WEAPON);
        define(20, 2, 2, WEAPON);
        define(21, 3, 2, WEAPON);
        define(32, 5, 10, DIGGER);
        define(33, 1, 1, DIGGER);
        define(34, 3, 5, BREAKABLE);
        define(35, 3, 2, DIGGER);
        define(48, 5, 10, BOW);
        define(49, 2, 2, BOW);
        define(50, 1, 2, BOW);
        define(51, 1, 1, BOW);
        define(61, 3, 2, FISHING_ROD);
        define(62, 3, 2, FISHING_ROD);
    }

    private Anvil() {
    }

    private static void define(int id, int maxLevel, int weight, int type) {
        ENCHANTMENTS[id] = new int[]{maxLevel, weight, type};
    }

    /**
     * @param name the rename text as the anvil has it ("" or null for none; an unchanged name arrives as "")
     */
    public static Cost cost(ItemStack left, ItemStack right, String name, boolean creative) {
        if (left == null) {
            return Cost.NONE;
        }
        int work = repairCost(left) + (right == null ? 0 : repairCost(right));
        int levels = 0;
        int material = 0;
        Map<Integer, Integer> enchantments = enchantments(left);
        int damage = left.damage();
        int maxDamage = Items.maxDamage(left.id());
        if (right != null) {
            boolean book = right.id() == 403 && !enchantments(right).isEmpty();
            if (maxDamage > 0 && repairItem(left.id()) == right.id()) {
                int step = Math.min(damage, maxDamage / 4);
                if (step <= 0) {
                    return Cost.NONE;
                }
                int used;
                for (used = 0; step > 0 && used < right.count(); used++) {
                    damage -= step;
                    levels++;
                    step = Math.min(damage, maxDamage / 4);
                }
                material = used;
            } else {
                if (!book && (left.id() != right.id() || maxDamage <= 0)) {
                    return Cost.NONE;
                }
                if (maxDamage > 0 && !book) {
                    int repaired = maxDamage - left.damage() + maxDamage - right.damage() + maxDamage * 12 / 100;
                    int newDamage = Math.max(0, maxDamage - repaired);
                    if (newDamage < left.damage()) {
                        levels += 2;
                    }
                }
                for (Map.Entry<Integer, Integer> added : enchantments(right).entrySet()) {
                    int id = added.getKey();
                    int[] enchantment = id >= 0 && id < 256 ? ENCHANTMENTS[id] : null;
                    if (enchantment == null) {
                        continue;
                    }
                    int present = enchantments.getOrDefault(id, 0);
                    int level = added.getValue();
                    level = present == level ? level + 1 : Math.max(level, present);
                    boolean applies = creative || left.id() == 403 || applies(enchantment[2], left.id());
                    for (int other : enchantments.keySet()) {
                        if (other != id && !together(id, other)) {
                            applies = false;
                            levels++;
                        }
                    }
                    if (applies) {
                        level = Math.min(level, enchantment[0]);
                        enchantments.put(id, level);
                        int factor = switch (enchantment[1]) {
                            case 1 -> 8;
                            case 2 -> 4;
                            case 5 -> 2;
                            case 10 -> 1;
                            default -> 0;
                        };
                        if (book) {
                            factor = Math.max(1, factor / 2);
                        }
                        levels += factor * level;
                    }
                }
            }
        }
        int renaming = 0;
        if (name == null || name.isBlank()) {
            if (left.customName() != null) {
                renaming = 1;
            }
        } else if (!name.equals(left.customName())) {
            renaming = 1;
        }
        levels += renaming;
        int total = work + levels;
        if (levels <= 0) {
            return new Cost(0, material);
        }
        if (renaming == levels && renaming > 0 && total >= 40) {
            total = 39;
        }
        return new Cost(total, material);
    }

    /** ItemStack.getRepairCost: the previous work on an item. */
    private static int repairCost(ItemStack stack) {
        NBTTagCompound tag = stack.tag();
        return tag != null && tag.hasKey("RepairCost", 3) ? tag.getInteger("RepairCost") : 0;
    }

    /** EnchantmentHelper.getEnchantments: id to level, from ench (or an enchanted book's StoredEnchantments). */
    static Map<Integer, Integer> enchantments(ItemStack stack) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        NBTTagCompound tag = stack.tag();
        if (tag == null) {
            return result;
        }
        NBTTagList list = tag.getTagList(stack.id() == 403 ? "StoredEnchantments" : "ench", 10);
        for (int index = 0; index < list.tagCount(); index++) {
            NBTTagCompound entry = list.getCompoundTagAt(index);
            result.put((int) entry.getShort("id"), (int) entry.getShort("lvl"));
        }
        return result;
    }

    /** ToolMaterial.getRepairItem and ArmorMaterial's repair item: what repairs an item a quarter at a time. */
    private static int repairItem(int id) {
        return switch (id) {
            // Tools, swords and armor only (ItemTool, ItemSword, ItemArmor); hoes take no material.
            case 268, 269, 270, 271 -> 5;                                       // wood: planks
            case 272, 273, 274, 275 -> 4;                                       // stone: cobblestone
            case 256, 257, 258, 267, 302, 303, 304, 305, 306, 307, 308, 309 -> 265; // iron (and chain)
            case 276, 277, 278, 279, 310, 311, 312, 313 -> 264;                 // diamond
            case 283, 284, 285, 286, 314, 315, 316, 317 -> 266;                 // gold
            case 298, 299, 300, 301 -> 334;                                     // leather
            default -> -1;
        };
    }

    /** EnumEnchantmentType.canEnchantItem, with the overrides of sharpness (axes), efficiency (shears)... */
    private static boolean applies(int type, int id) {
        boolean sword = id == 267 || id == 268 || id == 272 || id == 276 || id == 283;
        boolean axe = id == 258 || id == 271 || id == 275 || id == 279 || id == 286;
        boolean tool = axe || id == 256 || id == 257 || id == 269 || id == 270 || id == 273 || id == 274
                || id == 277 || id == 278 || id == 284 || id == 285;
        return switch (type) {
            case ALL -> true;
            case ARMOR -> Items.armor(id);
            case FEET -> Items.armorType(id) == 3;
            case HEAD -> Items.armorType(id) == 0;
            case TORSO -> Items.armor(id);
            case WEAPON -> sword || axe;
            case DIGGER -> tool || id == 359;
            case BREAKABLE -> Items.damageable(id);
            case BOW -> id == 261;
            case FISHING_ROD -> id == 346;
            default -> false;
        };
    }

    /**
     * {@code added}.canApplyTogether({@code present}): one of each kind of protection (feather falling goes with
     * any), one damage enchantment, and silk touch not with fortune (fortune and looting not with silk touch).
     */
    private static boolean together(int added, int present) {
        if (added == present) {
            return false;
        }
        if (added <= 4 && present <= 4) {
            return added == 2 || present == 2;
        }
        if (added >= 16 && added <= 18 && present >= 16 && present <= 18) {
            return false;
        }
        if (added == 33) {
            return present != 35;
        }
        return !((added == 35 || added == 21) && present == 33);
    }
}
