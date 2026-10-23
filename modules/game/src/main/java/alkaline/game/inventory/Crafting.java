package alkaline.game.inventory;

import alkaline.game.data.nbt.NBTTagCompound;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft 1.8.9's crafting recipes for the inventory's 2x2 grid and the crafting table's 3x3 grid. The server
 * never sends a crafting result slot (EntityPlayerMP skips SlotCrafting), so the client must find it itself, as
 * vanilla's ContainerPlayer and ContainerWorkbench do.
 *
 * <p>Covers CraftingManager's shaped and shapeless recipes (RecipesTools, RecipesWeapons, RecipesIngots,
 * RecipesFood, RecipesCrafting, RecipesArmor, RecipesDyes, the banner bases and the recipes CraftingManager adds
 * itself), tool repair (RecipeRepairItem) and leather dyeing (RecipesArmorDyes). Not covered: fireworks, book and
 * map cloning, map extending and banner patterns, whose results are built from tags; for those the grid shows no
 * result, and taking it anyway still works because the server's resync then sends it.
 *
 * <p>Ingredients follow CraftingManager's conversions: an item matches only damage 0, a block named alone matches
 * every variant in shaped recipes (32767) but only its first one in shapeless recipes (new ItemStack(Block)).
 */
public final class Crafting {
    /** Vanilla's wildcard damage (32767): any variant matches. */
    private static final int ANY = 32767;

    private record Ingredient(int id, int damage) {
        boolean matches(ItemStack stack) {
            return stack != null && stack.id() == id && (damage == ANY || damage == stack.damage());
        }
    }

    private record Shaped(int width, int height, Ingredient[] cells, int id, int count, int damage) {
    }

    private record Shapeless(Ingredient[] ingredients, int id, int count, int damage) {
    }

    private static final List<Shaped> SHAPED = new ArrayList<>();
    private static final List<Shapeless> SHAPELESS = new ArrayList<>();

    /** The block ids recipes name (1.8.9's block registry). */
    private static final Map<String, Integer> BLOCKS = new HashMap<>();
    private static final Map<String, Integer> ITEMS = new HashMap<>();

    /** Wood variants (BlockPlanks.EnumType): planks, slabs and logs (log2 holds acacia and dark oak as 0 and 1). */
    private static final int OAK = 0, SPRUCE = 1, BIRCH = 2, JUNGLE = 3, ACACIA = 4, DARK_OAK = 5;
    // Dye damage values (EnumDyeColor.getDyeDamage); wool and other colored blocks use 15 - dye.
    private static final int BLACK = 0, RED = 1, GREEN = 2, BROWN = 3, BLUE = 4, PURPLE = 5, CYAN = 6, SILVER = 7,
            GRAY = 8, PINK = 9, LIME = 10, YELLOW = 11, LIGHT_BLUE = 12, MAGENTA = 13, ORANGE = 14, WHITE = 15;
    private static final int DYE_ID = 351;

    /** EntitySheep's dye colors by dye damage, which leather dyeing averages. */
    private static final float[][] DYE_RGB = {
            {0.1f, 0.1f, 0.1f}, {0.6f, 0.2f, 0.2f}, {0.4f, 0.5f, 0.2f}, {0.4f, 0.3f, 0.2f}, {0.2f, 0.3f, 0.7f},
            {0.5f, 0.25f, 0.7f}, {0.3f, 0.5f, 0.6f}, {0.6f, 0.6f, 0.6f}, {0.3f, 0.3f, 0.3f}, {0.95f, 0.5f, 0.65f},
            {0.5f, 0.8f, 0.1f}, {0.9f, 0.9f, 0.2f}, {0.4f, 0.6f, 0.85f}, {0.7f, 0.3f, 0.85f}, {0.85f, 0.5f, 0.2f},
            {1f, 1f, 1f},
    };

    static {
        String[] blocks = {
                "1 stone", "3 dirt", "4 cobblestone", "5 planks", "12 sand", "13 gravel", "17 log", "20 glass",
                "22 lapis_block", "23 dispenser", "24 sandstone", "25 noteblock", "27 golden_rail", "28 detector_rail",
                "29 sticky_piston", "33 piston", "35 wool", "37 yellow_flower", "38 red_flower", "39 brown_mushroom",
                "40 red_mushroom", "41 gold_block", "42 iron_block", "44 stone_slab", "45 brick_block", "46 tnt",
                "47 bookshelf", "48 mossy_cobblestone", "49 obsidian", "50 torch", "53 oak_stairs", "54 chest",
                "57 diamond_block", "58 crafting_table", "61 furnace", "65 ladder", "66 rail", "67 stone_stairs",
                "69 lever", "70 stone_pressure_plate", "72 wooden_pressure_plate", "76 redstone_torch",
                "77 stone_button", "78 snow_layer", "80 snow", "82 clay", "84 jukebox", "85 fence", "86 pumpkin",
                "89 glowstone", "91 lit_pumpkin", "95 stained_glass", "96 trapdoor", "98 stonebrick", "101 iron_bars",
                "102 glass_pane", "103 melon_block", "106 vine", "107 fence_gate", "108 brick_stairs",
                "109 stone_brick_stairs", "112 nether_brick", "113 nether_brick_fence", "114 nether_brick_stairs",
                "116 enchanting_table", "123 redstone_lamp", "126 wooden_slab", "128 sandstone_stairs",
                "130 ender_chest", "131 tripwire_hook", "133 emerald_block", "134 spruce_stairs", "135 birch_stairs",
                "136 jungle_stairs", "138 beacon", "139 cobblestone_wall", "143 wooden_button", "145 anvil",
                "146 trapped_chest", "147 light_weighted_pressure_plate", "148 heavy_weighted_pressure_plate",
                "151 daylight_detector", "152 redstone_block", "154 hopper", "155 quartz_block", "156 quartz_stairs",
                "157 activator_rail", "158 dropper", "159 stained_hardened_clay", "160 stained_glass_pane",
                "162 log2", "163 acacia_stairs", "164 dark_oak_stairs", "165 slime", "167 iron_trapdoor",
                "168 prismarine", "169 sea_lantern", "170 hay_block", "171 carpet", "172 hardened_clay",
                "173 coal_block", "175 double_plant", "179 red_sandstone", "180 red_sandstone_stairs",
                "182 stone_slab2", "183 spruce_fence_gate", "184 birch_fence_gate", "185 jungle_fence_gate",
                "186 dark_oak_fence_gate", "187 acacia_fence_gate", "188 spruce_fence", "189 birch_fence",
                "190 jungle_fence", "191 dark_oak_fence", "192 acacia_fence",
        };
        for (String entry : blocks) {
            int space = entry.indexOf(' ');
            BLOCKS.put(entry.substring(space + 1), Integer.parseInt(entry.substring(0, space)));
        }
        for (int id = 256; id < 2268; id++) {
            String name = Items.name(id);
            if (name != null) {
                ITEMS.put(name, id);
            }
        }
        register();
        // CraftingManager sorts shaped recipes before shapeless ones, larger before smaller (a stable sort).
        SHAPED.sort((a, b) -> Integer.compare(b.width() * b.height(), a.width() * a.height()));
        SHAPELESS.sort((a, b) -> Integer.compare(b.ingredients().length, a.ingredients().length));
    }

    private Crafting() {
    }

    private static void register() {
        // RecipesTools and RecipesWeapons: wood (any planks), stone, iron, diamond, gold.
        Ingredient[] materials = {block("planks"), block("cobblestone"), item("iron_ingot"), item("diamond"),
                item("gold_ingot")};
        String[] prefixes = {"wooden", "stone", "iron", "diamond", "golden"};
        for (int material = 0; material < materials.length; material++) {
            Ingredient x = materials[material];
            String prefix = prefixes[material];
            shaped(prefix + "_pickaxe", 1, 0, "XXX| # | # ", '#', item("stick"), 'X', x);
            shaped(prefix + "_shovel", 1, 0, "X|#|#", '#', item("stick"), 'X', x);
            shaped(prefix + "_axe", 1, 0, "XX|X#| #", '#', item("stick"), 'X', x);
            shaped(prefix + "_hoe", 1, 0, "XX| #| #", '#', item("stick"), 'X', x);
            shaped(prefix + "_sword", 1, 0, "X|X|#", '#', item("stick"), 'X', x);
        }
        shaped("shears", 1, 0, " #|# ", '#', item("iron_ingot"));
        shaped("bow", 1, 0, " #X|# X| #X", 'X', item("string"), '#', item("stick"));
        shaped("arrow", 4, 0, "X|#|Y", 'Y', item("feather"), 'X', item("flint"), '#', item("stick"));

        // RecipesIngots.
        String[][] storage = {{"gold_block", "gold_ingot"}, {"iron_block", "iron_ingot"},
                {"diamond_block", "diamond"}, {"emerald_block", "emerald"}, {"redstone_block", "redstone"},
                {"coal_block", "coal"}, {"hay_block", "wheat"}, {"slime", "slime_ball"}};
        for (String[] pair : storage) {
            shaped(pair[0], 1, 0, "###|###|###", '#', item(pair[1]));
            shaped(pair[1], 9, 0, "#", '#', block(pair[0]));
        }
        shaped("lapis_block", 1, 0, "###|###|###", '#', item("dye", BLUE));
        shaped("dye", 9, BLUE, "#", '#', block("lapis_block"));
        shaped("gold_ingot", 1, 0, "###|###|###", '#', item("gold_nugget"));
        shaped("gold_nugget", 9, 0, "#", '#', item("gold_ingot"));

        // RecipesFood.
        shapeless("mushroom_stew", 1, 0, block("brown_mushroom", 0), block("red_mushroom", 0), item("bowl"));
        shaped("cookie", 8, 0, "#X#", 'X', item("dye", BROWN), '#', item("wheat"));
        shaped("rabbit_stew", 1, 0, " R |CPM| B ", 'R', item("cooked_rabbit"), 'C', item("carrot"), 'P',
                item("baked_potato"), 'M', block("brown_mushroom"), 'B', item("bowl"));
        shaped("rabbit_stew", 1, 0, " R |CPD| B ", 'R', item("cooked_rabbit"), 'C', item("carrot"), 'P',
                item("baked_potato"), 'D', block("red_mushroom"), 'B', item("bowl"));
        shaped("melon_block", 1, 0, "MMM|MMM|MMM", 'M', item("melon"));
        shaped("melon_seeds", 1, 0, "M", 'M', item("melon"));
        shaped("pumpkin_seeds", 4, 0, "M", 'M', block("pumpkin"));
        shapeless("pumpkin_pie", 1, 0, block("pumpkin", 0), item("sugar"), item("egg"));
        shapeless("fermented_spider_eye", 1, 0, item("spider_eye"), block("brown_mushroom", 0), item("sugar"));
        shapeless("blaze_powder", 2, 0, item("blaze_rod"));
        shapeless("magma_cream", 1, 0, item("blaze_powder"), item("slime_ball"));

        // RecipesCrafting.
        shaped("chest", 1, 0, "###|# #|###", '#', block("planks"));
        shaped("trapped_chest", 1, 0, "#-", '#', block("chest"), '-', block("tripwire_hook"));
        shaped("ender_chest", 1, 0, "###|#E#|###", '#', block("obsidian"), 'E', item("ender_eye"));
        shaped("furnace", 1, 0, "###|# #|###", '#', block("cobblestone"));
        shaped("crafting_table", 1, 0, "##|##", '#', block("planks"));
        shaped("sandstone", 1, 0, "##|##", '#', block("sand", 0));
        shaped("red_sandstone", 1, 0, "##|##", '#', block("sand", 1));
        shaped("sandstone", 4, 2, "##|##", '#', block("sandstone", 0));
        shaped("red_sandstone", 4, 2, "##|##", '#', block("red_sandstone", 0));
        shaped("sandstone", 1, 1, "#|#", '#', block("stone_slab", 1));
        shaped("red_sandstone", 1, 1, "#|#", '#', block("stone_slab2", 0));
        shaped("quartz_block", 1, 1, "#|#", '#', block("stone_slab", 7));
        shaped("quartz_block", 2, 2, "#|#", '#', block("quartz_block", 0));
        shaped("stonebrick", 4, 0, "##|##", '#', block("stone", 0));
        shaped("stonebrick", 1, 3, "#|#", '#', block("stone_slab", 5));
        shapeless("stonebrick", 1, 1, block("stonebrick", 0), block("vine", 0));
        shapeless("mossy_cobblestone", 1, 0, block("cobblestone", 0), block("vine", 0));
        shaped("iron_bars", 16, 0, "###|###", '#', item("iron_ingot"));
        shaped("glass_pane", 16, 0, "###|###", '#', block("glass"));
        shaped("redstone_lamp", 1, 0, " R |RGR| R ", 'R', item("redstone"), 'G', block("glowstone"));
        shaped("beacon", 1, 0, "GGG|GSG|OOO", 'G', block("glass"), 'S', item("nether_star"), 'O',
                block("obsidian"));
        shaped("nether_brick", 1, 0, "NN|NN", 'N', item("netherbrick"));
        shaped("stone", 2, 3, "CQ|QC", 'C', block("cobblestone"), 'Q', item("quartz"));
        shapeless("stone", 1, 1, block("stone", 3), item("quartz"));
        shapeless("stone", 2, 5, block("stone", 3), block("cobblestone", 0));
        shaped("dirt", 4, 1, "DG|GD", 'D', block("dirt", 0), 'G', block("gravel"));
        shaped("stone", 4, 4, "SS|SS", 'S', block("stone", 3));
        shaped("stone", 4, 2, "SS|SS", 'S', block("stone", 1));
        shaped("stone", 4, 6, "SS|SS", 'S', block("stone", 5));
        shaped("prismarine", 1, 0, "SS|SS", 'S', item("prismarine_shard"));
        shaped("prismarine", 1, 1, "SSS|SSS|SSS", 'S', item("prismarine_shard"));
        shaped("prismarine", 1, 2, "SSS|SIS|SSS", 'S', item("prismarine_shard"), 'I', item("dye", BLACK));
        shaped("sea_lantern", 1, 0, "SCS|CCC|SCS", 'S', item("prismarine_shard"), 'C', item("prismarine_crystals"));

        // RecipesArmor: leather, iron, diamond, gold (chainmail has no recipe).
        String[] armorMaterials = {"leather", "iron_ingot", "diamond", "gold_ingot"};
        String[] armorPrefixes = {"leather", "iron", "diamond", "golden"};
        for (int material = 0; material < armorMaterials.length; material++) {
            Ingredient x = item(armorMaterials[material]);
            String prefix = armorPrefixes[material];
            shaped(prefix + "_helmet", 1, 0, "XXX|X X", 'X', x);
            shaped(prefix + "_chestplate", 1, 0, "X X|XXX|XXX", 'X', x);
            shaped(prefix + "_leggings", 1, 0, "XXX|X X|X X", 'X', x);
            shaped(prefix + "_boots", 1, 0, "X X|X X", 'X', x);
        }

        // RecipesDyes.
        for (int color = 0; color < 16; color++) {
            shapeless("wool", 1, color, item("dye", 15 - color), block("wool", 0));
            shaped("stained_hardened_clay", 8, 15 - color, "###|#X#|###", '#', block("hardened_clay", 0), 'X',
                    item("dye", color));
            shaped("stained_glass", 8, 15 - color, "###|#X#|###", '#', block("glass", 0), 'X', item("dye", color));
            shaped("stained_glass_pane", 16, color, "###|###", '#', block("stained_glass", color));
        }
        shapeless("dye", 1, YELLOW, block("yellow_flower", 0));
        shapeless("dye", 1, RED, block("red_flower", 0));
        shapeless("dye", 3, WHITE, item("bone"));
        shapeless("dye", 2, PINK, item("dye", RED), item("dye", WHITE));
        shapeless("dye", 2, ORANGE, item("dye", RED), item("dye", YELLOW));
        shapeless("dye", 2, LIME, item("dye", GREEN), item("dye", WHITE));
        shapeless("dye", 2, GRAY, item("dye", BLACK), item("dye", WHITE));
        shapeless("dye", 2, SILVER, item("dye", GRAY), item("dye", WHITE));
        shapeless("dye", 3, SILVER, item("dye", BLACK), item("dye", WHITE), item("dye", WHITE));
        shapeless("dye", 2, LIGHT_BLUE, item("dye", BLUE), item("dye", WHITE));
        shapeless("dye", 2, CYAN, item("dye", BLUE), item("dye", GREEN));
        shapeless("dye", 2, PURPLE, item("dye", BLUE), item("dye", RED));
        shapeless("dye", 2, MAGENTA, item("dye", PURPLE), item("dye", PINK));
        shapeless("dye", 3, MAGENTA, item("dye", BLUE), item("dye", RED), item("dye", PINK));
        shapeless("dye", 4, MAGENTA, item("dye", BLUE), item("dye", RED), item("dye", RED), item("dye", WHITE));
        shapeless("dye", 1, LIGHT_BLUE, block("red_flower", 1));
        shapeless("dye", 1, MAGENTA, block("red_flower", 2));
        shapeless("dye", 1, SILVER, block("red_flower", 3));
        shapeless("dye", 1, RED, block("red_flower", 4));
        shapeless("dye", 1, ORANGE, block("red_flower", 5));
        shapeless("dye", 1, SILVER, block("red_flower", 6));
        shapeless("dye", 1, PINK, block("red_flower", 7));
        shapeless("dye", 1, SILVER, block("red_flower", 8));
        shapeless("dye", 2, YELLOW, block("double_plant", 0));
        shapeless("dye", 2, MAGENTA, block("double_plant", 1));
        shapeless("dye", 2, RED, block("double_plant", 4));
        shapeless("dye", 2, PINK, block("double_plant", 5));
        for (int color = 0; color < 16; color++) {
            shaped("carpet", 3, color, "##", '#', block("wool", color));
        }

        // RecipesBanners: the plain banners (pattern recipes are built from tags).
        for (int color = 0; color < 16; color++) {
            shaped("banner", 1, 15 - color, "###|###| S ", '#', block("wool", color), 'S', item("stick"));
        }

        // CraftingManager's own.
        shaped("paper", 3, 0, "###", '#', item("reeds"));
        shapeless("book", 1, 0, item("paper"), item("paper"), item("paper"), item("leather"));
        shapeless("writable_book", 1, 0, item("book"), item("dye", BLACK), item("feather"));
        String[] woods = {"", "spruce_", "birch_", "jungle_", "acacia_", "dark_oak_"};
        int[] woodPlanks = {OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK};
        for (int wood = 0; wood < woods.length; wood++) {
            Ingredient planks = block("planks", woodPlanks[wood]);
            shaped(woods[wood] + "fence", 3, 0, "W#W|W#W", '#', item("stick"), 'W', planks);
        }
        shaped("cobblestone_wall", 6, 0, "###|###", '#', block("cobblestone"));
        shaped("cobblestone_wall", 6, 1, "###|###", '#', block("mossy_cobblestone"));
        shaped("nether_brick_fence", 6, 0, "###|###", '#', block("nether_brick"));
        for (int wood = 0; wood < woods.length; wood++) {
            Ingredient planks = block("planks", woodPlanks[wood]);
            shaped(woods[wood] + "fence_gate", 1, 0, "#W#|#W#", '#', item("stick"), 'W', planks);
        }
        shaped("jukebox", 1, 0, "###|#X#|###", '#', block("planks"), 'X', item("diamond"));
        shaped("lead", 2, 0, "~~ |~O |  ~", '~', item("string"), 'O', item("slime_ball"));
        shaped("noteblock", 1, 0, "###|#X#|###", '#', block("planks"), 'X', item("redstone"));
        shaped("bookshelf", 1, 0, "###|XXX|###", '#', block("planks"), 'X', item("book"));
        shaped("snow", 1, 0, "##|##", '#', item("snowball"));
        shaped("snow_layer", 6, 0, "###", '#', block("snow"));
        shaped("clay", 1, 0, "##|##", '#', item("clay_ball"));
        shaped("brick_block", 1, 0, "##|##", '#', item("brick"));
        shaped("glowstone", 1, 0, "##|##", '#', item("glowstone_dust"));
        shaped("quartz_block", 1, 0, "##|##", '#', item("quartz"));
        shaped("wool", 1, 0, "##|##", '#', item("string"));
        shaped("tnt", 1, 0, "X#X|#X#|X#X", 'X', item("gunpowder"), '#', block("sand"));
        shaped("stone_slab", 6, 3, "###", '#', block("cobblestone"));
        shaped("stone_slab", 6, 0, "###", '#', block("stone", 0));
        shaped("stone_slab", 6, 1, "###", '#', block("sandstone"));
        shaped("stone_slab", 6, 4, "###", '#', block("brick_block"));
        shaped("stone_slab", 6, 5, "###", '#', block("stonebrick"));
        shaped("stone_slab", 6, 6, "###", '#', block("nether_brick"));
        shaped("stone_slab", 6, 7, "###", '#', block("quartz_block"));
        shaped("stone_slab2", 6, 0, "###", '#', block("red_sandstone"));
        for (int wood : woodPlanks) {
            shaped("wooden_slab", 6, wood, "###", '#', block("planks", wood));
        }
        shaped("ladder", 3, 0, "# #|###|# #", '#', item("stick"));
        String[] doors = {"wooden_door", "spruce_door", "birch_door", "jungle_door", "acacia_door", "dark_oak_door"};
        int[] doorPlanks = {OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK};
        for (int door = 0; door < doors.length; door++) {
            shaped(doors[door], 3, 0, "##|##|##", '#', block("planks", doorPlanks[door]));
        }
        shaped("trapdoor", 2, 0, "###|###", '#', block("planks"));
        shaped("iron_door", 3, 0, "##|##|##", '#', item("iron_ingot"));
        shaped("iron_trapdoor", 1, 0, "##|##", '#', item("iron_ingot"));
        shaped("sign", 3, 0, "###|###| X ", '#', block("planks"), 'X', item("stick"));
        shaped("cake", 1, 0, "AAA|BEB|CCC", 'A', item("milk_bucket"), 'B', item("sugar"), 'C', item("wheat"),
                'E', item("egg"));
        shaped("sugar", 1, 0, "#", '#', item("reeds"));
        for (int wood = 0; wood < 4; wood++) {
            shaped("planks", 4, wood, "#", '#', block("log", wood));
        }
        shaped("planks", 4, ACACIA, "#", '#', block("log2", 0));
        shaped("planks", 4, DARK_OAK, "#", '#', block("log2", 1));
        shaped("stick", 4, 0, "#|#", '#', block("planks"));
        shaped("torch", 4, 0, "X|#", 'X', item("coal"), '#', item("stick"));
        shaped("torch", 4, 0, "X|#", 'X', item("coal", 1), '#', item("stick"));
        shaped("bowl", 4, 0, "# #| # ", '#', block("planks"));
        shaped("glass_bottle", 3, 0, "# #| # ", '#', block("glass"));
        shaped("rail", 16, 0, "X X|X#X|X X", 'X', item("iron_ingot"), '#', item("stick"));
        shaped("golden_rail", 6, 0, "X X|X#X|XRX", 'X', item("gold_ingot"), 'R', item("redstone"), '#',
                item("stick"));
        shaped("activator_rail", 6, 0, "XSX|X#X|XSX", 'X', item("iron_ingot"), '#', block("redstone_torch"), 'S',
                item("stick"));
        shaped("detector_rail", 6, 0, "X X|X#X|XRX", 'X', item("iron_ingot"), 'R', item("redstone"), '#',
                block("stone_pressure_plate"));
        shaped("minecart", 1, 0, "# #|###", '#', item("iron_ingot"));
        shaped("cauldron", 1, 0, "# #|# #|###", '#', item("iron_ingot"));
        shaped("brewing_stand", 1, 0, " B |###", '#', block("cobblestone"), 'B', item("blaze_rod"));
        shaped("lit_pumpkin", 1, 0, "A|B", 'A', block("pumpkin"), 'B', block("torch"));
        shaped("chest_minecart", 1, 0, "A|B", 'A', block("chest"), 'B', item("minecart"));
        shaped("furnace_minecart", 1, 0, "A|B", 'A', block("furnace"), 'B', item("minecart"));
        shaped("tnt_minecart", 1, 0, "A|B", 'A', block("tnt"), 'B', item("minecart"));
        shaped("hopper_minecart", 1, 0, "A|B", 'A', block("hopper"), 'B', item("minecart"));
        shaped("boat", 1, 0, "# #|###", '#', block("planks"));
        shaped("bucket", 1, 0, "# #| # ", '#', item("iron_ingot"));
        shaped("flower_pot", 1, 0, "# #| # ", '#', item("brick"));
        shapeless("flint_and_steel", 1, 0, item("iron_ingot"), item("flint"));
        shaped("bread", 1, 0, "###", '#', item("wheat"));
        String[] stairs = {"oak_stairs", "spruce_stairs", "birch_stairs", "jungle_stairs", "acacia_stairs",
                "dark_oak_stairs"};
        for (int wood = 0; wood < stairs.length; wood++) {
            shaped(stairs[wood], 4, 0, "#  |## |###", '#', block("planks", woodPlanks[wood]));
        }
        shaped("fishing_rod", 1, 0, "  #| #X|# X", '#', item("stick"), 'X', item("string"));
        shaped("carrot_on_a_stick", 1, 0, "# | X", '#', item("fishing_rod"), 'X', item("carrot"));
        shaped("stone_stairs", 4, 0, "#  |## |###", '#', block("cobblestone"));
        shaped("brick_stairs", 4, 0, "#  |## |###", '#', block("brick_block"));
        shaped("stone_brick_stairs", 4, 0, "#  |## |###", '#', block("stonebrick"));
        shaped("nether_brick_stairs", 4, 0, "#  |## |###", '#', block("nether_brick"));
        shaped("sandstone_stairs", 4, 0, "#  |## |###", '#', block("sandstone"));
        shaped("red_sandstone_stairs", 4, 0, "#  |## |###", '#', block("red_sandstone"));
        shaped("quartz_stairs", 4, 0, "#  |## |###", '#', block("quartz_block"));
        shaped("painting", 1, 0, "###|#X#|###", '#', item("stick"), 'X', block("wool"));
        shaped("item_frame", 1, 0, "###|#X#|###", '#', item("stick"), 'X', item("leather"));
        shaped("golden_apple", 1, 0, "###|#X#|###", '#', item("gold_ingot"), 'X', item("apple"));
        shaped("golden_apple", 1, 1, "###|#X#|###", '#', block("gold_block"), 'X', item("apple"));
        shaped("golden_carrot", 1, 0, "###|#X#|###", '#', item("gold_nugget"), 'X', item("carrot"));
        shaped("speckled_melon", 1, 0, "###|#X#|###", '#', item("gold_nugget"), 'X', item("melon"));
        shaped("lever", 1, 0, "X|#", '#', block("cobblestone"), 'X', item("stick"));
        shaped("tripwire_hook", 2, 0, "I|S|#", '#', block("planks"), 'S', item("stick"), 'I', item("iron_ingot"));
        shaped("redstone_torch", 1, 0, "X|#", '#', item("stick"), 'X', item("redstone"));
        shaped("repeater", 1, 0, "#X#|III", '#', block("redstone_torch"), 'X', item("redstone"), 'I',
                block("stone", 0));
        shaped("comparator", 1, 0, " # |#X#|III", '#', block("redstone_torch"), 'X', item("quartz"), 'I',
                block("stone", 0));
        shaped("clock", 1, 0, " # |#X#| # ", '#', item("gold_ingot"), 'X', item("redstone"));
        shaped("compass", 1, 0, " # |#X#| # ", '#', item("iron_ingot"), 'X', item("redstone"));
        shaped("map", 1, 0, "###|#X#|###", '#', item("paper"), 'X', item("compass"));
        shaped("stone_button", 1, 0, "#", '#', block("stone", 0));
        shaped("wooden_button", 1, 0, "#", '#', block("planks"));
        shaped("stone_pressure_plate", 1, 0, "##", '#', block("stone", 0));
        shaped("wooden_pressure_plate", 1, 0, "##", '#', block("planks"));
        shaped("heavy_weighted_pressure_plate", 1, 0, "##", '#', item("iron_ingot"));
        shaped("light_weighted_pressure_plate", 1, 0, "##", '#', item("gold_ingot"));
        shaped("dispenser", 1, 0, "###|#X#|#R#", '#', block("cobblestone"), 'X', item("bow"), 'R', item("redstone"));
        shaped("dropper", 1, 0, "###|# #|#R#", '#', block("cobblestone"), 'R', item("redstone"));
        shaped("piston", 1, 0, "TTT|#X#|#R#", '#', block("cobblestone"), 'X', item("iron_ingot"), 'R',
                item("redstone"), 'T', block("planks"));
        shaped("sticky_piston", 1, 0, "S|P", 'S', item("slime_ball"), 'P', block("piston"));
        shaped("bed", 1, 0, "###|XXX", '#', block("wool"), 'X', block("planks"));
        shaped("enchanting_table", 1, 0, " B |D#D|###", '#', block("obsidian"), 'B', item("book"), 'D',
                item("diamond"));
        shaped("anvil", 1, 0, "III| i |iii", 'I', block("iron_block"), 'i', item("iron_ingot"));
        shaped("leather", 1, 0, "##|##", '#', item("rabbit_hide"));
        shapeless("ender_eye", 1, 0, item("ender_pearl"), item("blaze_powder"));
        shapeless("fire_charge", 3, 0, item("gunpowder"), item("blaze_powder"), item("coal"));
        shapeless("fire_charge", 3, 0, item("gunpowder"), item("blaze_powder"), item("coal", 1));
        shaped("daylight_detector", 1, 0, "GGG|QQQ|WWW", 'G', block("glass"), 'Q', item("quartz"), 'W',
                block("wooden_slab"));
        shaped("hopper", 1, 0, "I I|ICI| I ", 'I', item("iron_ingot"), 'C', block("chest"));
        shaped("armor_stand", 1, 0, "///| / |/_/", '/', item("stick"), '_', block("stone_slab", 0));
    }

    private static int id(String name) {
        Integer id = BLOCKS.containsKey(name) ? BLOCKS.get(name) : ITEMS.get(name);
        if (id == null) {
            throw new IllegalStateException("Unknown recipe item " + name);
        }
        return id;
    }

    /** An item ingredient: new ItemStack(Item) is damage 0, and only damage 0 matches. */
    private static Ingredient item(String name) {
        return new Ingredient(id(name), 0);
    }

    private static Ingredient item(String name, int damage) {
        return new Ingredient(id(name), damage);
    }

    /** A block named without a variant: any variant matches (vanilla's 32767). */
    private static Ingredient block(String name) {
        return new Ingredient(BLOCKS.get(name), ANY);
    }

    private static Ingredient block(String name, int meta) {
        return new Ingredient(BLOCKS.get(name), meta);
    }

    /** Rows of a pattern are separated by '|'; ' ' is an empty cell, other characters name the ingredients after. */
    private static void shaped(String result, int count, int damage, String pattern, Object... key) {
        String[] rows = pattern.split("\\|");
        int width = rows[0].length();
        Ingredient[] cells = new Ingredient[width * rows.length];
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < width; column++) {
                char symbol = rows[row].charAt(column);
                for (int index = 0; index < key.length; index += 2) {
                    if ((char) key[index] == symbol) {
                        cells[row * width + column] = (Ingredient) key[index + 1];
                    }
                }
            }
        }
        SHAPED.add(new Shaped(width, rows.length, cells, id(result), count, damage));
    }

    private static void shapeless(String result, int count, int damage, Ingredient... ingredients) {
        SHAPELESS.add(new Shapeless(ingredients, id(result), count, damage));
    }

    /**
     * The result of a square grid (2x2 or 3x3, row by row from the top left), or null, as
     * CraftingManager.findMatchingRecipe.
     */
    public static ItemStack result(ItemStack[] grid) {
        int size = side(grid);
        for (Shaped recipe : SHAPED) {
            if (matches(recipe, grid, size)) {
                return new ItemStack(recipe.id(), recipe.count(), recipe.damage(), null);
            }
        }
        ItemStack special = repair(grid);
        if (special == null) {
            special = dyedLeather(grid);
        }
        if (special != null) {
            return special;
        }
        for (Shapeless recipe : SHAPELESS) {
            if (matches(recipe, grid)) {
                return new ItemStack(recipe.id(), recipe.count(), recipe.damage(), null);
            }
        }
        return null;
    }

    /**
     * What taking a result leaves in each grid cell besides the used-up items (IRecipe.getRemainingItems): water,
     * lava and milk buckets give back an empty bucket. Null entries leave nothing.
     */
    public static ItemStack[] remaining(ItemStack[] grid) {
        ItemStack[] left = new ItemStack[grid.length];
        if (result(grid) == null) {
            return left;
        }
        for (int index = 0; index < grid.length; index++) {
            ItemStack stack = grid[index];
            if (stack != null && (stack.id() == 326 || stack.id() == 327 || stack.id() == 335)) {
                left[index] = new ItemStack(325, 1, 0, null);
            }
        }
        return left;
    }

    private static int side(ItemStack[] grid) {
        return grid.length == 9 ? 3 : 2;
    }

    /** ShapedRecipes.matches: the pattern, or its mirror image, at some offset, with every other cell empty. */
    private static boolean matches(Shaped recipe, ItemStack[] grid, int size) {
        for (int offsetX = 0; offsetX <= size - recipe.width(); offsetX++) {
            for (int offsetY = 0; offsetY <= size - recipe.height(); offsetY++) {
                if (matches(recipe, grid, size, offsetX, offsetY, true)
                        || matches(recipe, grid, size, offsetX, offsetY, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(Shaped recipe, ItemStack[] grid, int size, int offsetX, int offsetY,
                                   boolean mirrored) {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int column = x - offsetX;
                int row = y - offsetY;
                Ingredient wanted = null;
                if (column >= 0 && row >= 0 && column < recipe.width() && row < recipe.height()) {
                    wanted = recipe.cells()[(mirrored ? recipe.width() - column - 1 : column) + row * recipe.width()];
                }
                ItemStack present = grid[x + y * size];
                if (wanted == null ? present != null : !wanted.matches(present)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** ShapelessRecipes.matches: each item consumes one ingredient, and none is left over. */
    private static boolean matches(Shapeless recipe, ItemStack[] grid) {
        boolean[] used = new boolean[recipe.ingredients().length];
        int remaining = used.length;
        for (ItemStack stack : grid) {
            if (stack == null) {
                continue;
            }
            boolean found = false;
            for (int index = 0; index < used.length; index++) {
                if (!used[index] && recipe.ingredients()[index].matches(stack)) {
                    used[index] = true;
                    remaining--;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return remaining == 0;
    }

    /** RecipeRepairItem: two single, worn items of one kind combine with a 5% bonus. */
    private static ItemStack repair(ItemStack[] grid) {
        ItemStack first = null;
        ItemStack second = null;
        for (ItemStack stack : grid) {
            if (stack == null) {
                continue;
            }
            if (first == null) {
                first = stack;
            } else if (second == null) {
                second = stack;
            } else {
                return null;
            }
        }
        if (second == null || first.id() != second.id() || first.count() != 1 || second.count() != 1
                || !Items.damageable(first.id())) {
            return null;
        }
        int max = Items.maxDamage(first.id());
        int left = (max - first.damage()) + (max - second.damage()) + max * 5 / 100;
        return new ItemStack(first.id(), 1, Math.max(0, max - left), null);
    }

    /** RecipesArmorDyes: one leather piece and dyes average into the piece's new color. */
    private static ItemStack dyedLeather(ItemStack[] grid) {
        ItemStack armor = null;
        int[] sum = new int[3];
        int brightness = 0;
        int colors = 0;
        for (ItemStack stack : grid) {
            if (stack == null) {
                continue;
            }
            if (Items.armor(stack.id())) {
                if (!"leather".equals(Items.armorMaterial(stack.id())) || armor != null) {
                    return null;
                }
                armor = stack;
                int color = stack.leatherColor();
                if (color >= 0) {
                    float red = (color >> 16 & 255) / 255f;
                    float green = (color >> 8 & 255) / 255f;
                    float blue = (color & 255) / 255f;
                    brightness = (int) (brightness + Math.max(red, Math.max(green, blue)) * 255f);
                    sum[0] = (int) (sum[0] + red * 255f);
                    sum[1] = (int) (sum[1] + green * 255f);
                    sum[2] = (int) (sum[2] + blue * 255f);
                    colors++;
                }
            } else if (stack.id() == DYE_ID) {
                float[] rgb = DYE_RGB[Math.floorMod(stack.damage(), 16)];
                int red = (int) (rgb[0] * 255f);
                int green = (int) (rgb[1] * 255f);
                int blue = (int) (rgb[2] * 255f);
                brightness += Math.max(red, Math.max(green, blue));
                sum[0] += red;
                sum[1] += green;
                sum[2] += blue;
                colors++;
            } else {
                return null;
            }
        }
        if (armor == null || colors == 0 || colors == (armor.leatherColor() >= 0 ? 1 : 0)) {
            return null;
        }
        int red = sum[0] / colors;
        int green = sum[1] / colors;
        int blue = sum[2] / colors;
        float average = brightness / (float) colors;
        float peak = Math.max(red, Math.max(green, blue));
        red = (int) (red * average / peak);
        green = (int) (green * average / peak);
        blue = (int) (blue * average / peak);
        NBTTagCompound tag = armor.tag() == null ? new NBTTagCompound() : (NBTTagCompound) armor.tag().copy();
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!tag.hasKey("display", 10)) {
            tag.setTag("display", display);
        }
        display.setInteger("color", (red << 16) + (green << 8) + blue);
        return new ItemStack(armor.id(), 1, armor.damage(), tag);
    }
}
