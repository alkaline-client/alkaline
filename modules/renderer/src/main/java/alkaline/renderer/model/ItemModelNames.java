package alkaline.renderer.model;

import java.util.HashMap;
import java.util.Map;

/**
 * The item model ({@code models/item/<name>.json}) vanilla 1.8.9 draws for an item id and damage:
 * RenderItem.registerItems' table, where a registry name and damage value name a model (oak and birch leaves,
 * wool colors, charcoal). Items without an entry use their registry name; an unknown
 * damage value of an item with variants falls back to its first variant.
 */
public final class ItemModelNames {
    /** Registry name, then damage=model pairs, one item per line (generated from RenderItem.registerItems). */
    private static final String VARIANTS = """
            anvil 0=anvil_intact 1=anvil_slightly_damaged 2=anvil_very_damaged
            carpet 0=white_carpet 1=orange_carpet 2=magenta_carpet 3=light_blue_carpet 4=yellow_carpet 5=lime_carpet 6=pink_carpet 7=gray_carpet 8=silver_carpet 9=cyan_carpet 10=purple_carpet 11=blue_carpet 12=brown_carpet 13=green_carpet 14=red_carpet 15=black_carpet
            coal 1=charcoal
            cobblestone_wall 1=mossy_cobblestone_wall
            cooked_fish 0=cooked_cod 1=cooked_salmon
            deadbush 0=dead_bush
            dirt 1=coarse_dirt 2=podzol
            double_plant 0=sunflower 1=syringa 2=double_grass 3=double_fern 4=double_rose 5=paeonia
            dye 0=dye_black 1=dye_red 2=dye_green 3=dye_brown 4=dye_blue 5=dye_purple 6=dye_cyan 7=dye_silver 8=dye_gray 9=dye_pink 10=dye_lime 11=dye_yellow 12=dye_light_blue 13=dye_magenta 14=dye_orange 15=dye_white
            fence 0=oak_fence
            fence_gate 0=oak_fence_gate
            fish 0=cod 1=salmon 2=clownfish 3=pufferfish
            golden_apple 1=golden_apple
            leaves 0=oak_leaves 1=spruce_leaves 2=birch_leaves 3=jungle_leaves
            leaves2 0=acacia_leaves 1=dark_oak_leaves
            log 0=oak_log 1=spruce_log 2=birch_log 3=jungle_log
            log2 0=acacia_log 1=dark_oak_log
            monster_egg 0=stone_monster_egg 1=cobblestone_monster_egg 2=stone_brick_monster_egg 3=mossy_brick_monster_egg 4=cracked_brick_monster_egg 5=chiseled_brick_monster_egg
            planks 0=oak_planks 1=spruce_planks 2=birch_planks 3=jungle_planks 4=acacia_planks 5=dark_oak_planks
            prismarine 1=prismarine_bricks 2=dark_prismarine
            quartz_block 1=chiseled_quartz_block 2=quartz_column
            red_flower 0=poppy 1=blue_orchid 2=allium 3=houstonia 4=red_tulip 5=orange_tulip 6=white_tulip 7=pink_tulip 8=oxeye_daisy
            red_sandstone 1=chiseled_red_sandstone 2=smooth_red_sandstone
            sand 1=red_sand
            sandstone 1=chiseled_sandstone 2=smooth_sandstone
            sapling 0=oak_sapling 1=spruce_sapling 2=birch_sapling 3=jungle_sapling 4=acacia_sapling 5=dark_oak_sapling
            sponge 1=sponge_wet
            stained_glass 0=white_stained_glass 1=orange_stained_glass 2=magenta_stained_glass 3=light_blue_stained_glass 4=yellow_stained_glass 5=lime_stained_glass 6=pink_stained_glass 7=gray_stained_glass 8=silver_stained_glass 9=cyan_stained_glass 10=purple_stained_glass 11=blue_stained_glass 12=brown_stained_glass 13=green_stained_glass 14=red_stained_glass 15=black_stained_glass
            stained_glass_pane 0=white_stained_glass_pane 1=orange_stained_glass_pane 2=magenta_stained_glass_pane 3=light_blue_stained_glass_pane 4=yellow_stained_glass_pane 5=lime_stained_glass_pane 6=pink_stained_glass_pane 7=gray_stained_glass_pane 8=silver_stained_glass_pane 9=cyan_stained_glass_pane 10=purple_stained_glass_pane 11=blue_stained_glass_pane 12=brown_stained_glass_pane 13=green_stained_glass_pane 14=red_stained_glass_pane 15=black_stained_glass_pane
            stained_hardened_clay 0=white_stained_hardened_clay 1=orange_stained_hardened_clay 2=magenta_stained_hardened_clay 3=light_blue_stained_hardened_clay 4=yellow_stained_hardened_clay 5=lime_stained_hardened_clay 6=pink_stained_hardened_clay 7=gray_stained_hardened_clay 8=silver_stained_hardened_clay 9=cyan_stained_hardened_clay 10=purple_stained_hardened_clay 11=blue_stained_hardened_clay 12=brown_stained_hardened_clay 13=green_stained_hardened_clay 14=red_stained_hardened_clay 15=black_stained_hardened_clay
            stone 1=granite 2=granite_smooth 3=diorite 4=diorite_smooth 5=andesite 6=andesite_smooth
            stone_slab 1=sandstone_slab 2=old_wood_slab 3=cobblestone_slab 4=brick_slab 5=stone_brick_slab 6=nether_brick_slab 7=quartz_slab
            stone_slab2 0=red_sandstone_slab
            stonebrick 1=mossy_stonebrick 2=cracked_stonebrick 3=chiseled_stonebrick
            tallgrass 0=dead_bush 1=tall_grass 2=fern
            wooden_door 0=oak_door
            wooden_slab 0=oak_slab 1=spruce_slab 2=birch_slab 3=jungle_slab 4=acacia_slab 5=dark_oak_slab
            wool 0=white_wool 1=orange_wool 2=magenta_wool 3=light_blue_wool 4=yellow_wool 5=lime_wool 6=pink_wool 7=gray_wool 8=silver_wool 9=cyan_wool 10=purple_wool 11=blue_wool 12=brown_wool 13=green_wool 14=red_wool 15=black_wool
            yellow_flower 0=dandelion
            """;
    private static final Map<String, Map<Integer, String>> TABLE = new HashMap<>();

    static {
        for (String line : VARIANTS.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length < 2) {
                continue;
            }
            Map<Integer, String> variants = new HashMap<>();
            for (int index = 1; index < parts.length; index++) {
                int split = parts[index].indexOf('=');
                variants.put(Integer.parseInt(parts[index].substring(0, split)), parts[index].substring(split + 1));
            }
            TABLE.put(parts[0], variants);
        }
    }

    private ItemModelNames() {
    }

    /**
     * @param registryName the item's registry name (for example {@code dye}), or a block's for block items
     * @return the model name, or null when vanilla draws the item with a built-in renderer (skulls, banners)
     */
    public static String model(String registryName, int damage) {
        if (registryName == null) {
            return null;
        }
        switch (registryName) {
            case "potion":
                return (damage & 16384) != 0 ? "bottle_splash" : "bottle_drinkable";
            case "skull", "banner":
                return null;
            default:
                break;
        }
        Map<Integer, String> variants = TABLE.get(registryName);
        if (variants == null) {
            return registryName;
        }
        String model = variants.get(damage);
        if (model == null) {
            model = variants.get(0);
        }
        return model != null ? model : registryName;
    }
}
