package alkaline.game.inventory;

/**
 * Minecraft 1.8.9's item registry by numeric id (Item.registerItems: 256-431 and the records at 2256-2267) and
 * the few item properties the client draws with. Block items (ids below 256) are named by their block.
 */
public final class Items {
    private static final String[] NAMES = new String[2268];

    static {
        name(256, "iron_shovel");
        name(257, "iron_pickaxe");
        name(258, "iron_axe");
        name(259, "flint_and_steel");
        name(260, "apple");
        name(261, "bow");
        name(262, "arrow");
        name(263, "coal");
        name(264, "diamond");
        name(265, "iron_ingot");
        name(266, "gold_ingot");
        name(267, "iron_sword");
        name(268, "wooden_sword");
        name(269, "wooden_shovel");
        name(270, "wooden_pickaxe");
        name(271, "wooden_axe");
        name(272, "stone_sword");
        name(273, "stone_shovel");
        name(274, "stone_pickaxe");
        name(275, "stone_axe");
        name(276, "diamond_sword");
        name(277, "diamond_shovel");
        name(278, "diamond_pickaxe");
        name(279, "diamond_axe");
        name(280, "stick");
        name(281, "bowl");
        name(282, "mushroom_stew");
        name(283, "golden_sword");
        name(284, "golden_shovel");
        name(285, "golden_pickaxe");
        name(286, "golden_axe");
        name(287, "string");
        name(288, "feather");
        name(289, "gunpowder");
        name(290, "wooden_hoe");
        name(291, "stone_hoe");
        name(292, "iron_hoe");
        name(293, "diamond_hoe");
        name(294, "golden_hoe");
        name(295, "wheat_seeds");
        name(296, "wheat");
        name(297, "bread");
        name(298, "leather_helmet");
        name(299, "leather_chestplate");
        name(300, "leather_leggings");
        name(301, "leather_boots");
        name(302, "chainmail_helmet");
        name(303, "chainmail_chestplate");
        name(304, "chainmail_leggings");
        name(305, "chainmail_boots");
        name(306, "iron_helmet");
        name(307, "iron_chestplate");
        name(308, "iron_leggings");
        name(309, "iron_boots");
        name(310, "diamond_helmet");
        name(311, "diamond_chestplate");
        name(312, "diamond_leggings");
        name(313, "diamond_boots");
        name(314, "golden_helmet");
        name(315, "golden_chestplate");
        name(316, "golden_leggings");
        name(317, "golden_boots");
        name(318, "flint");
        name(319, "porkchop");
        name(320, "cooked_porkchop");
        name(321, "painting");
        name(322, "golden_apple");
        name(323, "sign");
        name(324, "wooden_door");
        name(325, "bucket");
        name(326, "water_bucket");
        name(327, "lava_bucket");
        name(328, "minecart");
        name(329, "saddle");
        name(330, "iron_door");
        name(331, "redstone");
        name(332, "snowball");
        name(333, "boat");
        name(334, "leather");
        name(335, "milk_bucket");
        name(336, "brick");
        name(337, "clay_ball");
        name(338, "reeds");
        name(339, "paper");
        name(340, "book");
        name(341, "slime_ball");
        name(342, "chest_minecart");
        name(343, "furnace_minecart");
        name(344, "egg");
        name(345, "compass");
        name(346, "fishing_rod");
        name(347, "clock");
        name(348, "glowstone_dust");
        name(349, "fish");
        name(350, "cooked_fish");
        name(351, "dye");
        name(352, "bone");
        name(353, "sugar");
        name(354, "cake");
        name(355, "bed");
        name(356, "repeater");
        name(357, "cookie");
        name(358, "filled_map");
        name(359, "shears");
        name(360, "melon");
        name(361, "pumpkin_seeds");
        name(362, "melon_seeds");
        name(363, "beef");
        name(364, "cooked_beef");
        name(365, "chicken");
        name(366, "cooked_chicken");
        name(367, "rotten_flesh");
        name(368, "ender_pearl");
        name(369, "blaze_rod");
        name(370, "ghast_tear");
        name(371, "gold_nugget");
        name(372, "nether_wart");
        name(373, "potion");
        name(374, "glass_bottle");
        name(375, "spider_eye");
        name(376, "fermented_spider_eye");
        name(377, "blaze_powder");
        name(378, "magma_cream");
        name(379, "brewing_stand");
        name(380, "cauldron");
        name(381, "ender_eye");
        name(382, "speckled_melon");
        name(383, "spawn_egg");
        name(384, "experience_bottle");
        name(385, "fire_charge");
        name(386, "writable_book");
        name(387, "written_book");
        name(388, "emerald");
        name(389, "item_frame");
        name(390, "flower_pot");
        name(391, "carrot");
        name(392, "potato");
        name(393, "baked_potato");
        name(394, "poisonous_potato");
        name(395, "map");
        name(396, "golden_carrot");
        name(397, "skull");
        name(398, "carrot_on_a_stick");
        name(399, "nether_star");
        name(400, "pumpkin_pie");
        name(401, "fireworks");
        name(402, "firework_charge");
        name(403, "enchanted_book");
        name(404, "comparator");
        name(405, "netherbrick");
        name(406, "quartz");
        name(407, "tnt_minecart");
        name(408, "hopper_minecart");
        name(409, "prismarine_shard");
        name(410, "prismarine_crystals");
        name(411, "rabbit");
        name(412, "cooked_rabbit");
        name(413, "rabbit_stew");
        name(414, "rabbit_foot");
        name(415, "rabbit_hide");
        name(416, "armor_stand");
        name(417, "iron_horse_armor");
        name(418, "golden_horse_armor");
        name(419, "diamond_horse_armor");
        name(420, "lead");
        name(421, "name_tag");
        name(422, "command_block_minecart");
        name(423, "mutton");
        name(424, "cooked_mutton");
        name(425, "banner");
        name(427, "spruce_door");
        name(428, "birch_door");
        name(429, "jungle_door");
        name(430, "acacia_door");
        name(431, "dark_oak_door");
        name(2256, "record_13");
        name(2257, "record_cat");
        name(2258, "record_blocks");
        name(2259, "record_chirp");
        name(2260, "record_far");
        name(2261, "record_mall");
        name(2262, "record_mellohi");
        name(2263, "record_stal");
        name(2264, "record_strad");
        name(2265, "record_ward");
        name(2266, "record_11");
        name(2267, "record_wait");
    }

    /** ArmorMaterial's damage reduction per armor type (helmet, chestplate, leggings, boots). */
    private static final int[][] ARMOR_POINTS = {
            {1, 3, 2, 1}, // leather
            {2, 5, 4, 1}, // chainmail
            {2, 6, 5, 2}, // iron
            {3, 8, 6, 3}, // diamond
            {2, 5, 3, 1}, // gold
    };
    private static final String[] ARMOR_MATERIALS = {"leather", "chainmail", "iron", "diamond", "gold"};

    private Items() {
    }

    private static void name(int id, String name) {
        NAMES[id] = name;
    }

    /** The registry name of an item id (256 and up), or null for blocks and unknown ids. */
    public static String name(int id) {
        return id >= 0 && id < NAMES.length ? NAMES[id] : null;
    }

    /** Whether the id is an armor piece (298-317). */
    public static boolean armor(int id) {
        return id >= 298 && id <= 317;
    }

    /** The armor slot of an armor item: 0 helmet, 1 chestplate, 2 leggings, 3 boots; -1 otherwise. */
    public static int armorType(int id) {
        return armor(id) ? (id - 298) % 4 : -1;
    }

    /** The armor texture name (textures/models/armor/{name}_layer_N.png), or null. */
    public static String armorMaterial(int id) {
        return armor(id) ? ARMOR_MATERIALS[(id - 298) / 4] : null;
    }

    /** Armor points (half shirts in the HUD) an armor item gives; 0 for anything else. */
    public static int armorPoints(int id) {
        return armor(id) ? ARMOR_POINTS[(id - 298) / 4][(id - 298) % 4] : 0;
    }

    /** Item.getItemStackLimit by id: 1 for tools, armor and other single items, 16 for a few, 64 otherwise. */
    public static int maxStackSize(int id) {
        if (armor(id) || maxDamage(id) > 0 || (id >= 2256 && id <= 2267)) {
            return 1;
        }
        return switch (id) {
            // Buckets (filled), saddle, minecarts, boat, milk, stews, potion, cake, bed, book and quill, enchanted
            // book, horse armor.
            case 282, 326, 327, 328, 329, 333, 335, 342, 343, 354, 355, 373, 386, 403, 407, 408, 413, 417, 418, 419,
                 422 -> 1;
            // Sign, empty bucket, snowball, egg, ender pearl, written book, armor stand, banner.
            case 323, 325, 332, 344, 368, 387, 416, 425 -> 16;
            default -> 64;
        };
    }

    /** Item.isDamageable: whether the item wears (its damage value is durability, not a variant). */
    public static boolean damageable(int id) {
        return maxDamage(id) > 0;
    }

    /** Maximum damage of tools, weapons and armor (durability), or 0 for items that do not wear. */
    public static int maxDamage(int id) {
        return switch (id) {
            case 256, 257, 258, 267, 292 -> 250;       // iron tools
            case 268, 269, 270, 271, 290 -> 59;        // wood
            case 272, 273, 274, 275, 291 -> 131;       // stone
            case 276, 277, 278, 279, 293 -> 1561;      // diamond
            case 283, 284, 285, 286, 294 -> 32;        // gold
            case 259 -> 64;                            // flint and steel
            case 261 -> 384;                           // bow
            case 346 -> 64;                            // fishing rod
            case 359 -> 238;                           // shears
            case 398 -> 25;                            // carrot on a stick
            default -> armor(id) ? armorDurability(id) : 0;
        };
    }

    /** ItemArmor: the material's durability factor times the type's base (11, 16, 15, 13). */
    private static int armorDurability(int id) {
        int[] factor = {5, 15, 15, 33, 7};
        int[] base = {11, 16, 15, 13};
        return factor[(id - 298) / 4] * base[(id - 298) % 4];
    }
}
