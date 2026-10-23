package alkaline.game.inventory;

import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;

/**
 * Item.getColorFromItemStack for 1.8.9, per render pass (sprite layer, or a block model's tint index): potion
 * liquids, dyed leather, spawn eggs, firework stars, and the grass and foliage colors block items take from the
 * colormaps' middle (temperature 0.5, rainfall 1.0). Returns 0xRRGGBB, or -1 for no tint.
 */
public final class ItemColors {
    /** Potion.liquidColor by effect id. */
    private static final int[] EFFECT_COLORS = new int[32];
    /** The effect a standard potion's damage (its low four bits) brews (PotionHelper's recipes). */
    private static final int[] POTION_EFFECT = {0, 10, 1, 12, 19, 6, 16, 0, 18, 5, 2, 8, 7, 13, 14, 0};
    /** EntityList.entityEggs: base and spot colors by entity id. */
    private static final java.util.Map<Integer, int[]> EGGS = new java.util.HashMap<>();
    /** Default leather (ItemArmor.getColor without a dye). */
    public static final int LEATHER = 0xA06540;

    static {
        int[][] colors = {{1, 8171462}, {2, 5926017}, {3, 14270531}, {4, 4866583}, {5, 9643043}, {6, 16262179},
                {7, 4393481}, {8, 7889559}, {9, 5578058}, {10, 13458603}, {11, 10044730}, {12, 14981690},
                {13, 3035801}, {14, 8356754}, {15, 2039587}, {16, 2039713}, {17, 5797459}, {18, 4738376},
                {19, 5149489}, {20, 3484199}, {21, 16284963}, {22, 2445989}, {23, 16262179}};
        for (int[] color : colors) {
            EFFECT_COLORS[color[0]] = color[1];
        }
        int[][] eggs = {{50, 894731, 0}, {51, 12698049, 4802889}, {52, 3419431, 11013646}, {54, 44975, 7969893},
                {55, 5349438, 8306542}, {56, 16382457, 12369084}, {57, 15373203, 5009705}, {58, 1447446, 0},
                {59, 803406, 11013646}, {60, 7237230, 3158064}, {61, 16167425, 16775294}, {62, 3407872, 16579584},
                {65, 4996656, 986895}, {66, 3407872, 5349438}, {67, 1315860, 6694966}, {68, 11586938, 7899042},
                {90, 15771042, 14377823}, {91, 15198183, 16758197}, {92, 4470310, 10592673},
                {93, 10592673, 16711680}, {94, 2243405, 7375001}, {95, 14144467, 13545366},
                {96, 10489616, 12040119}, {98, 15720061, 5653556}, {100, 12623485, 15656192},
                {101, 10051392, 7555121}, {120, 5651507, 12422002}};
        for (int[] egg : eggs) {
            EGGS.put(egg[0], new int[]{egg[1], egg[2]});
        }
    }

    /** Resource-pack color overrides (OptiFine color.properties); each returns 0xRRGGBB, or -1 for vanilla's. */
    public interface Custom {
        Custom NONE = new Custom() {
            @Override
            public int potion(int effect) {
                return -1;
            }

            @Override
            public int egg(int entity, int layer) {
                return -1;
            }
        };

        /** A potion effect's liquid color; effect 0 is the water bottle. */
        int potion(int effect);

        /** A spawn egg's shell (layer 0) or spot (layer 1) color. */
        int egg(int entity, int layer);
    }

    private ItemColors() {
    }

    /**
     * @param pass          the sprite layer of a generated item, or a block model's tint index
     * @param grass         ColorizerGrass at (0.5, 1.0), from the grass colormap
     * @param foliage       ColorizerFoliage at (0.5, 1.0), from the foliage colormap
     */
    public static int tint(ItemStack stack, int pass, int grass, int foliage) {
        return tint(stack, pass, grass, foliage, Custom.NONE);
    }

    /** {@link #tint(ItemStack, int, int, int)} with the potion and spawn egg colors a resource pack overrides. */
    public static int tint(ItemStack stack, int pass, int grass, int foliage, Custom custom) {
        if (stack == null || pass < 0) {
            return -1;
        }
        int id = stack.id();
        int damage = stack.damage();
        return switch (id) {
            // Grass, tall grass, ferns, double grass and ferns (ItemColored): the grass color.
            case 2 -> grass;
            case 31 -> damage == 0 ? -1 : grass; // the dead shrub is not tinted
            case 175 -> damage == 2 || damage == 3 ? grass : -1;
            case 106 -> 4764952; // vines: ColorizerFoliage.getFoliageColorBasic
            // Leaves: spruce and birch have fixed colors (ColorizerFoliage.getFoliageColorPine / Birch).
            case 18 -> (damage & 3) == 1 ? 6396257 : (damage & 3) == 2 ? 8431445 : foliage;
            case 161 -> foliage;
            case 111 -> 7455580; // lily pad: ItemLilyPad.getColorFromItemStack (BlockLilyPad.getRenderColor)
            case 373 -> pass == 0 ? potion(stack, custom) : -1;
            case 298, 299, 300, 301 -> pass == 0 ? leather(stack) : -1;
            case 383 -> egg(damage, pass, custom);
            case 402 -> pass == 1 ? fireworkStar(stack) : -1;
            default -> -1;
        };
    }

    /**
     * ItemPotion.getColorFromItemStack: the effects' liquid colors averaged by level (water: 0x385DC6). OptiFine
     * overrides each Potion.getLiquidColor and the water color.
     */
    static int potion(ItemStack stack, Custom overrides) {
        NBTTagCompound tag = stack.tag();
        if (tag != null && tag.hasKey("CustomPotionEffects", 9)) {
            NBTTagList custom = tag.getTagList("CustomPotionEffects", 10);
            float r = 0;
            float g = 0;
            float b = 0;
            float total = 0;
            for (int index = 0; index < custom.tagCount(); index++) {
                NBTTagCompound effect = custom.getCompoundTagAt(index);
                int effectId = effect.getByte("Id") & 255;
                if (effectId >= EFFECT_COLORS.length || EFFECT_COLORS[effectId] == 0) {
                    continue;
                }
                int color = effectColor(effectId, overrides);
                int weight = (effect.getByte("Amplifier") & 255) + 1;
                r += (color >> 16 & 255) / 255f * weight;
                g += (color >> 8 & 255) / 255f * weight;
                b += (color & 255) / 255f * weight;
                total += weight;
            }
            if (total > 0) {
                return (int) (r / total * 255f) << 16 | (int) (g / total * 255f) << 8 | (int) (b / total * 255f);
            }
        }
        int effect = POTION_EFFECT[stack.damage() & 15];
        return effectColor(effect, overrides);
    }

    /** Potion.getLiquidColor (effect 0: PotionHelper's water color), or the pack's color for it. */
    private static int effectColor(int effect, Custom custom) {
        int color = custom.potion(effect);
        if (color >= 0) {
            return color;
        }
        return effect == 0 ? 0x385DC6 : EFFECT_COLORS[effect];
    }

    /** ItemArmor.getColor: display.color, or leather brown. */
    static int leather(ItemStack stack) {
        int color = stack.leatherColor();
        return color >= 0 ? color : LEATHER;
    }

    /** ItemMonsterPlacer: the base color, then the spots. */
    static int egg(int entity, int pass, Custom custom) {
        int color = custom.egg(entity, pass);
        if (color >= 0) {
            return color;
        }
        int[] colors = EGGS.get(entity);
        if (colors == null) {
            return 0xFFFFFF;
        }
        return pass == 0 ? colors[0] : colors[1];
    }

    /** ItemFireworkCharge.getColorFromItemStack: the explosion's colors averaged, or gray. */
    static int fireworkStar(ItemStack stack) {
        NBTTagCompound tag = stack.tag();
        if (tag == null || !tag.hasKey("Explosion", 10)) {
            return 9079434;
        }
        int[] colors = tag.getCompoundTag("Explosion").getIntArray("Colors");
        if (colors.length == 0) {
            return 9079434;
        }
        if (colors.length == 1) {
            return colors[0];
        }
        int r = 0;
        int g = 0;
        int b = 0;
        for (int color : colors) {
            r += color >> 16 & 255;
            g += color >> 8 & 255;
            b += color & 255;
        }
        return r / colors.length << 16 | g / colors.length << 8 | b / colors.length;
    }
}
