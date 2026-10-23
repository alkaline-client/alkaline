package alkaline.clientapp;

import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Items;
import java.util.ArrayList;
import java.util.List;

/**
 * Item names and tooltips as vanilla shows them on hover: the translated name (variants included), its rarity
 * color, enchantments, potion effects and lore, from the game's en_US.lang. Unlocalized names come from 1.8.9's
 * Item.registerItems and Block.registerBlocks; the variant suffixes from the ItemBlock subclasses that name them.
 */
final class ItemNames {
    private static final String[] ITEMS = new String[2268];
    private static final String[] BLOCKS = new String[256];
    private static final String[] COLORS = {"white", "orange", "magenta", "lightBlue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "big_oak"};
    /** Potion damage's low four bits, as brewing sets them, to Potion names. */
    private static final String[] POTIONS = {null, "regeneration", "moveSpeed", "fireResistance", "poison", "heal",
            "nightVision", null, "weakness", "damageBoost", "moveSlowdown", "jump", "harm", "waterBreathing",
            "invisibility", null};
    /** Potion ids (1.8.9) to their names, for CustomPotionEffects. */
    private static final String[] EFFECTS = {null, "moveSpeed", "moveSlowdown", "digSpeed", "digSlowDown",
            "damageBoost", "heal", "harm", "jump", "confusion", "regeneration", "resistance", "fireResistance",
            "waterBreathing", "invisibility", "blindness", "nightVision", "hunger", "weakness", "poison", "wither",
            "healthBoost", "absorption", "saturation"};

    static {
        ITEMS[256] = "shovelIron";
        ITEMS[257] = "pickaxeIron";
        ITEMS[258] = "hatchetIron";
        ITEMS[259] = "flintAndSteel";
        ITEMS[260] = "apple";
        ITEMS[261] = "bow";
        ITEMS[262] = "arrow";
        ITEMS[263] = "coal";
        ITEMS[264] = "diamond";
        ITEMS[265] = "ingotIron";
        ITEMS[266] = "ingotGold";
        ITEMS[267] = "swordIron";
        ITEMS[268] = "swordWood";
        ITEMS[269] = "shovelWood";
        ITEMS[270] = "pickaxeWood";
        ITEMS[271] = "hatchetWood";
        ITEMS[272] = "swordStone";
        ITEMS[273] = "shovelStone";
        ITEMS[274] = "pickaxeStone";
        ITEMS[275] = "hatchetStone";
        ITEMS[276] = "swordDiamond";
        ITEMS[277] = "shovelDiamond";
        ITEMS[278] = "pickaxeDiamond";
        ITEMS[279] = "hatchetDiamond";
        ITEMS[280] = "stick";
        ITEMS[281] = "bowl";
        ITEMS[282] = "mushroomStew";
        ITEMS[283] = "swordGold";
        ITEMS[284] = "shovelGold";
        ITEMS[285] = "pickaxeGold";
        ITEMS[286] = "hatchetGold";
        ITEMS[287] = "string";
        ITEMS[288] = "feather";
        ITEMS[289] = "sulphur";
        ITEMS[290] = "hoeWood";
        ITEMS[291] = "hoeStone";
        ITEMS[292] = "hoeIron";
        ITEMS[293] = "hoeDiamond";
        ITEMS[294] = "hoeGold";
        ITEMS[295] = "seeds";
        ITEMS[296] = "wheat";
        ITEMS[297] = "bread";
        ITEMS[298] = "helmetCloth";
        ITEMS[299] = "chestplateCloth";
        ITEMS[300] = "leggingsCloth";
        ITEMS[301] = "bootsCloth";
        ITEMS[302] = "helmetChain";
        ITEMS[303] = "chestplateChain";
        ITEMS[304] = "leggingsChain";
        ITEMS[305] = "bootsChain";
        ITEMS[306] = "helmetIron";
        ITEMS[307] = "chestplateIron";
        ITEMS[308] = "leggingsIron";
        ITEMS[309] = "bootsIron";
        ITEMS[310] = "helmetDiamond";
        ITEMS[311] = "chestplateDiamond";
        ITEMS[312] = "leggingsDiamond";
        ITEMS[313] = "bootsDiamond";
        ITEMS[314] = "helmetGold";
        ITEMS[315] = "chestplateGold";
        ITEMS[316] = "leggingsGold";
        ITEMS[317] = "bootsGold";
        ITEMS[318] = "flint";
        ITEMS[319] = "porkchopRaw";
        ITEMS[320] = "porkchopCooked";
        ITEMS[321] = "painting";
        ITEMS[322] = "appleGold";
        ITEMS[323] = "sign";
        ITEMS[324] = "doorOak";
        ITEMS[325] = "bucket";
        ITEMS[326] = "bucketWater";
        ITEMS[327] = "bucketLava";
        ITEMS[328] = "minecart";
        ITEMS[329] = "saddle";
        ITEMS[330] = "doorIron";
        ITEMS[331] = "redstone";
        ITEMS[332] = "snowball";
        ITEMS[333] = "boat";
        ITEMS[334] = "leather";
        ITEMS[335] = "milk";
        ITEMS[336] = "brick";
        ITEMS[337] = "clay";
        ITEMS[338] = "reeds";
        ITEMS[339] = "paper";
        ITEMS[340] = "book";
        ITEMS[341] = "slimeball";
        ITEMS[342] = "minecartChest";
        ITEMS[343] = "minecartFurnace";
        ITEMS[344] = "egg";
        ITEMS[345] = "compass";
        ITEMS[346] = "fishingRod";
        ITEMS[347] = "clock";
        ITEMS[348] = "yellowDust";
        ITEMS[349] = "fish";
        ITEMS[350] = "fish";
        ITEMS[351] = "dyePowder";
        ITEMS[352] = "bone";
        ITEMS[353] = "sugar";
        ITEMS[354] = "cake";
        ITEMS[355] = "bed";
        ITEMS[356] = "diode";
        ITEMS[357] = "cookie";
        ITEMS[358] = "map";
        ITEMS[359] = "shears";
        ITEMS[360] = "melon";
        ITEMS[361] = "seeds_pumpkin";
        ITEMS[362] = "seeds_melon";
        ITEMS[363] = "beefRaw";
        ITEMS[364] = "beefCooked";
        ITEMS[365] = "chickenRaw";
        ITEMS[366] = "chickenCooked";
        ITEMS[367] = "rottenFlesh";
        ITEMS[368] = "enderPearl";
        ITEMS[369] = "blazeRod";
        ITEMS[370] = "ghastTear";
        ITEMS[371] = "goldNugget";
        ITEMS[372] = "netherStalkSeeds";
        ITEMS[373] = "potion";
        ITEMS[374] = "glassBottle";
        ITEMS[375] = "spiderEye";
        ITEMS[376] = "fermentedSpiderEye";
        ITEMS[377] = "blazePowder";
        ITEMS[378] = "magmaCream";
        ITEMS[379] = "brewingStand";
        ITEMS[380] = "cauldron";
        ITEMS[381] = "eyeOfEnder";
        ITEMS[382] = "speckledMelon";
        ITEMS[383] = "monsterPlacer";
        ITEMS[384] = "expBottle";
        ITEMS[385] = "fireball";
        ITEMS[386] = "writingBook";
        ITEMS[387] = "writtenBook";
        ITEMS[388] = "emerald";
        ITEMS[389] = "frame";
        ITEMS[390] = "flowerPot";
        ITEMS[391] = "carrots";
        ITEMS[392] = "potato";
        ITEMS[393] = "potatoBaked";
        ITEMS[394] = "potatoPoisonous";
        ITEMS[395] = "emptyMap";
        ITEMS[396] = "carrotGolden";
        ITEMS[397] = "skull";
        ITEMS[398] = "carrotOnAStick";
        ITEMS[399] = "netherStar";
        ITEMS[400] = "pumpkinPie";
        ITEMS[401] = "fireworks";
        ITEMS[402] = "fireworksCharge";
        ITEMS[403] = "enchantedBook";
        ITEMS[404] = "comparator";
        ITEMS[405] = "netherbrick";
        ITEMS[406] = "netherquartz";
        ITEMS[407] = "minecartTnt";
        ITEMS[408] = "minecartHopper";
        ITEMS[409] = "prismarineShard";
        ITEMS[410] = "prismarineCrystals";
        ITEMS[411] = "rabbitRaw";
        ITEMS[412] = "rabbitCooked";
        ITEMS[413] = "rabbitStew";
        ITEMS[414] = "rabbitFoot";
        ITEMS[415] = "rabbitHide";
        ITEMS[416] = "armorStand";
        ITEMS[417] = "horsearmormetal";
        ITEMS[418] = "horsearmorgold";
        ITEMS[419] = "horsearmordiamond";
        ITEMS[420] = "leash";
        ITEMS[421] = "nameTag";
        ITEMS[422] = "minecartCommandBlock";
        ITEMS[423] = "muttonRaw";
        ITEMS[424] = "muttonCooked";
        ITEMS[425] = "banner";
        ITEMS[427] = "doorSpruce";
        ITEMS[428] = "doorBirch";
        ITEMS[429] = "doorJungle";
        ITEMS[430] = "doorAcacia";
        ITEMS[431] = "doorDarkOak";
        ITEMS[2256] = "record";
        ITEMS[2257] = "record";
        ITEMS[2258] = "record";
        ITEMS[2259] = "record";
        ITEMS[2260] = "record";
        ITEMS[2261] = "record";
        ITEMS[2262] = "record";
        ITEMS[2263] = "record";
        ITEMS[2264] = "record";
        ITEMS[2265] = "record";
        ITEMS[2266] = "record";
        ITEMS[2267] = "record";
        BLOCKS[0] = "air";
        BLOCKS[1] = "stone";
        BLOCKS[2] = "grass";
        BLOCKS[3] = "dirt";
        BLOCKS[4] = "stonebrick";
        BLOCKS[5] = "wood";
        BLOCKS[6] = "sapling";
        BLOCKS[7] = "bedrock";
        BLOCKS[8] = "water";
        BLOCKS[9] = "water";
        BLOCKS[10] = "lava";
        BLOCKS[11] = "lava";
        BLOCKS[12] = "sand";
        BLOCKS[13] = "gravel";
        BLOCKS[14] = "oreGold";
        BLOCKS[15] = "oreIron";
        BLOCKS[16] = "oreCoal";
        BLOCKS[17] = "log";
        BLOCKS[18] = "leaves";
        BLOCKS[19] = "sponge";
        BLOCKS[20] = "glass";
        BLOCKS[21] = "oreLapis";
        BLOCKS[22] = "blockLapis";
        BLOCKS[23] = "dispenser";
        BLOCKS[24] = "sandStone";
        BLOCKS[25] = "musicBlock";
        BLOCKS[26] = "bed";
        BLOCKS[27] = "goldenRail";
        BLOCKS[28] = "detectorRail";
        BLOCKS[29] = "pistonStickyBase";
        BLOCKS[30] = "web";
        BLOCKS[31] = "tallgrass";
        BLOCKS[32] = "deadbush";
        BLOCKS[33] = "pistonBase";
        BLOCKS[34] = "pistonBase";
        BLOCKS[35] = "cloth";
        BLOCKS[37] = "flower1";
        BLOCKS[38] = "flower2";
        BLOCKS[39] = "mushroom";
        BLOCKS[40] = "mushroom";
        BLOCKS[41] = "blockGold";
        BLOCKS[42] = "blockIron";
        BLOCKS[43] = "stoneSlab";
        BLOCKS[44] = "stoneSlab";
        BLOCKS[45] = "brick";
        BLOCKS[46] = "tnt";
        BLOCKS[47] = "bookshelf";
        BLOCKS[48] = "stoneMoss";
        BLOCKS[49] = "obsidian";
        BLOCKS[50] = "torch";
        BLOCKS[51] = "fire";
        BLOCKS[52] = "mobSpawner";
        BLOCKS[53] = "stairsWood";
        BLOCKS[54] = "chest";
        BLOCKS[55] = "redstoneDust";
        BLOCKS[56] = "oreDiamond";
        BLOCKS[57] = "blockDiamond";
        BLOCKS[58] = "workbench";
        BLOCKS[59] = "crops";
        BLOCKS[60] = "farmland";
        BLOCKS[61] = "furnace";
        BLOCKS[62] = "furnace";
        BLOCKS[63] = "sign";
        BLOCKS[64] = "doorOak";
        BLOCKS[65] = "ladder";
        BLOCKS[66] = "rail";
        BLOCKS[67] = "stairsStone";
        BLOCKS[68] = "sign";
        BLOCKS[69] = "lever";
        BLOCKS[70] = "pressurePlateStone";
        BLOCKS[71] = "doorIron";
        BLOCKS[72] = "pressurePlateWood";
        BLOCKS[73] = "oreRedstone";
        BLOCKS[74] = "oreRedstone";
        BLOCKS[75] = "notGate";
        BLOCKS[76] = "notGate";
        BLOCKS[77] = "button";
        BLOCKS[78] = "snow";
        BLOCKS[79] = "ice";
        BLOCKS[80] = "snow";
        BLOCKS[81] = "cactus";
        BLOCKS[82] = "clay";
        BLOCKS[83] = "reeds";
        BLOCKS[84] = "jukebox";
        BLOCKS[85] = "fence";
        BLOCKS[86] = "pumpkin";
        BLOCKS[87] = "hellrock";
        BLOCKS[88] = "hellsand";
        BLOCKS[89] = "lightgem";
        BLOCKS[90] = "portal";
        BLOCKS[91] = "litpumpkin";
        BLOCKS[92] = "cake";
        BLOCKS[93] = "diode";
        BLOCKS[94] = "diode";
        BLOCKS[95] = "stainedGlass";
        BLOCKS[96] = "trapdoor";
        BLOCKS[97] = "monsterStoneEgg";
        BLOCKS[98] = "stonebricksmooth";
        BLOCKS[99] = "mushroom";
        BLOCKS[100] = "mushroom";
        BLOCKS[101] = "fenceIron";
        BLOCKS[102] = "thinGlass";
        BLOCKS[103] = "melon";
        BLOCKS[104] = "pumpkinStem";
        BLOCKS[105] = "pumpkinStem";
        BLOCKS[106] = "vine";
        BLOCKS[107] = "fenceGate";
        BLOCKS[108] = "stairsBrick";
        BLOCKS[109] = "stairsStoneBrickSmooth";
        BLOCKS[110] = "mycel";
        BLOCKS[111] = "waterlily";
        BLOCKS[112] = "netherBrick";
        BLOCKS[113] = "netherFence";
        BLOCKS[114] = "stairsNetherBrick";
        BLOCKS[115] = "netherStalk";
        BLOCKS[116] = "enchantmentTable";
        BLOCKS[117] = "brewingStand";
        BLOCKS[118] = "cauldron";
        BLOCKS[120] = "endPortalFrame";
        BLOCKS[121] = "whiteStone";
        BLOCKS[122] = "dragonEgg";
        BLOCKS[123] = "redstoneLight";
        BLOCKS[124] = "redstoneLight";
        BLOCKS[125] = "woodSlab";
        BLOCKS[126] = "woodSlab";
        BLOCKS[127] = "cocoa";
        BLOCKS[128] = "stairsSandStone";
        BLOCKS[129] = "oreEmerald";
        BLOCKS[130] = "enderChest";
        BLOCKS[131] = "tripWireSource";
        BLOCKS[132] = "tripWire";
        BLOCKS[133] = "blockEmerald";
        BLOCKS[134] = "stairsWoodSpruce";
        BLOCKS[135] = "stairsWoodBirch";
        BLOCKS[136] = "stairsWoodJungle";
        BLOCKS[137] = "commandBlock";
        BLOCKS[138] = "beacon";
        BLOCKS[139] = "cobbleWall";
        BLOCKS[140] = "flowerPot";
        BLOCKS[141] = "carrots";
        BLOCKS[142] = "potatoes";
        BLOCKS[143] = "button";
        BLOCKS[144] = "skull";
        BLOCKS[145] = "anvil";
        BLOCKS[146] = "chestTrap";
        BLOCKS[147] = "weightedPlate_light";
        BLOCKS[148] = "weightedPlate_heavy";
        BLOCKS[149] = "comparator";
        BLOCKS[150] = "comparator";
        BLOCKS[151] = "daylightDetector";
        BLOCKS[152] = "blockRedstone";
        BLOCKS[153] = "netherquartz";
        BLOCKS[154] = "hopper";
        BLOCKS[155] = "quartzBlock";
        BLOCKS[156] = "stairsQuartz";
        BLOCKS[157] = "activatorRail";
        BLOCKS[158] = "dropper";
        BLOCKS[159] = "clayHardenedStained";
        BLOCKS[160] = "thinStainedGlass";
        BLOCKS[161] = "leaves";
        BLOCKS[162] = "log";
        BLOCKS[163] = "stairsWoodAcacia";
        BLOCKS[164] = "stairsWoodDarkOak";
        BLOCKS[165] = "slime";
        BLOCKS[166] = "barrier";
        BLOCKS[167] = "ironTrapdoor";
        BLOCKS[168] = "prismarine";
        BLOCKS[169] = "seaLantern";
        BLOCKS[170] = "hayBlock";
        BLOCKS[171] = "woolCarpet";
        BLOCKS[172] = "clayHardened";
        BLOCKS[173] = "blockCoal";
        BLOCKS[174] = "icePacked";
        BLOCKS[175] = "doublePlant";
        BLOCKS[176] = "banner";
        BLOCKS[177] = "banner";
        BLOCKS[178] = "daylightDetector";
        BLOCKS[179] = "redSandStone";
        BLOCKS[180] = "stairsRedSandStone";
        BLOCKS[181] = "stoneSlab2";
        BLOCKS[182] = "stoneSlab2";
        BLOCKS[183] = "spruceFenceGate";
        BLOCKS[184] = "birchFenceGate";
        BLOCKS[185] = "jungleFenceGate";
        BLOCKS[186] = "darkOakFenceGate";
        BLOCKS[187] = "acaciaFenceGate";
        BLOCKS[188] = "spruceFence";
        BLOCKS[189] = "birchFence";
        BLOCKS[190] = "jungleFence";
        BLOCKS[191] = "darkOakFence";
        BLOCKS[192] = "acaciaFence";
        BLOCKS[193] = "doorSpruce";
        BLOCKS[194] = "doorBirch";
        BLOCKS[195] = "doorJungle";
        BLOCKS[196] = "doorAcacia";
        BLOCKS[197] = "doorDarkOak";
    }

    private final ChatText text;

    ItemNames(ChatText text) {
        this.text = text;
    }

    /** The display name, as Item.getItemStackDisplayName (a custom name wins). */
    String name(ItemStack stack) {
        String custom = stack.customName();
        if (custom != null) {
            return custom;
        }
        int id = stack.id();
        int damage = stack.damage();
        if (id == 373) {
            return potionName(stack);
        }
        String key = id < 256 ? blockKey(id, damage) : itemKey(id, damage);
        String name = key == null ? null : text.translation(key + ".name");
        if (name == null && key != null && key.lastIndexOf('.') > 5) {
            // A variant vanilla has no string for falls back to its base name.
            name = text.translation(key.substring(0, key.lastIndexOf('.')) + ".name");
        }
        if (id >= 2256 && id <= 2267) {
            return text.translation("item.record.name") != null ? text.translation("item.record.name") : "Music Disc";
        }
        return name != null ? name : (id < 256 ? "Block " : "Item ") + id;
    }

    /** Tooltip lines with § formatting: the name in its rarity color, then effects, enchantments and lore. */
    List<String> tooltip(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        String name = name(stack);
        lines.add(rarity(stack) + (stack.customName() != null ? "§o" : "") + name);
        if (stack.id() >= 2256 && stack.id() <= 2267) {
            String disc = Items.name(stack.id());
            String desc = disc == null ? null : text.translation("item.record." + disc.substring(7) + ".desc");
            if (desc != null) {
                lines.add("§7" + desc);
            }
        }
        NBTTagCompound tag = stack.tag();
        if (tag != null) {
            NBTTagList enchantments = stack.id() == 403 ? tag.getTagList("StoredEnchantments", 10) : tag.getTagList("ench", 10);
            for (int index = 0; index < enchantments.tagCount(); index++) {
                NBTTagCompound entry = enchantments.getCompoundTagAt(index);
                String enchantment = enchantmentName(entry.getShort("id"));
                if (enchantment != null) {
                    lines.add("§7" + enchantment + " " + level(entry.getShort("lvl")));
                }
            }
            if (tag.hasKey("display", 10) && tag.getCompoundTag("display").hasKey("Lore", 9)) {
                NBTTagList lore = tag.getCompoundTag("display").getTagList("Lore", 8);
                for (int index = 0; index < lore.tagCount(); index++) {
                    lines.add("§5§o" + lore.getStringTagAt(index));
                }
            }
        }
        return lines;
    }

    /** EnumRarity: enchanted items are aqua, golden apples and some rare items gold-ish, enchanted gold apples purple. */
    private static String rarity(ItemStack stack) {
        boolean enchanted = stack.tag() != null && stack.tag().hasKey("ench", 9) && stack.tag().getTagList("ench", 10).tagCount() > 0;
        int id = stack.id();
        if (id == 322) {
            return stack.damage() > 0 ? "§d" : "§b";
        }
        if (id == 399 || id == 138) {
            return "§b"; // nether star, beacon: rare
        }
        if (id == 122 || id == 397 || id == 384) {
            return enchanted ? "§b" : "§e"; // dragon egg, skulls, bottle o' enchanting: uncommon
        }
        if (id == 403 || id == 373 && (stack.damage() & 0x3F) != 0 && enchanted) {
            return "§e";
        }
        return enchanted ? "§b" : "§f";
    }

    /** An enchantment's name and level as the enchanting table's hint shows it, for example "Sharpness II". */
    String enchantment(int id, int level) {
        String name = enchantmentName(id);
        return name == null ? null : name + " " + level(level);
    }

    private String level(int level) {
        String roman = text.translation("enchantment.level." + level);
        return roman != null ? roman : Integer.toString(level);
    }

    private String enchantmentName(int id) {
        String key = switch (id) {
            case 0 -> "protect.all";
            case 1 -> "protect.fire";
            case 2 -> "protect.fall";
            case 3 -> "protect.explosion";
            case 4 -> "protect.projectile";
            case 5 -> "oxygen";
            case 6 -> "waterWorker";
            case 7 -> "thorns";
            case 8 -> "waterWalker";
            case 16 -> "damage.all";
            case 17 -> "damage.undead";
            case 18 -> "damage.arthropods";
            case 19 -> "knockback";
            case 20 -> "fire";
            case 21 -> "lootBonus";
            case 32 -> "digging";
            case 33 -> "untouching";
            case 34 -> "durability";
            case 35 -> "lootBonusDigger";
            case 48 -> "arrowDamage";
            case 49 -> "arrowKnockback";
            case 50 -> "arrowFire";
            case 51 -> "arrowInfinite";
            case 61 -> "lootBonusFishing";
            case 62 -> "fishingSpeed";
            default -> null;
        };
        return key == null ? null : text.translation("enchantment." + key);
    }

    /** ItemPotion.getItemStackDisplayName: "Splash " and the first effect's "Potion of …", or a plain potion. */
    private String potionName(ItemStack stack) {
        int damage = stack.damage();
        if (damage == 0) {
            return orElse(text.translation("item.emptyPotion.name"), "Water Bottle");
        }
        String prefix = (damage & 16384) != 0 ? orElse(text.translation("potion.prefix.grenade"), "Splash").trim() + " " : "";
        String effect = null;
        NBTTagCompound tag = stack.tag();
        if (tag != null && tag.hasKey("CustomPotionEffects", 9)) {
            NBTTagList custom = tag.getTagList("CustomPotionEffects", 10);
            if (custom.tagCount() > 0) {
                int id = custom.getCompoundTagAt(0).getByte("Id");
                effect = id > 0 && id < EFFECTS.length ? EFFECTS[id] : null;
            }
        }
        if (effect == null) {
            effect = POTIONS[damage & 15];
        }
        String postfix = effect == null ? null : text.translation("potion." + effect + ".postfix");
        return prefix + (postfix != null ? postfix.trim() : orElse(text.translation("item.potion.name"), "Potion"));
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String itemKey(int id, int damage) {
        String base = id < ITEMS.length ? ITEMS[id] : null;
        if (base == null) {
            return null;
        }
        return switch (id) {
            case 263 -> damage == 1 ? "item.charcoal" : "item.coal";
            case 351 -> "item.dyePowder." + COLORS[15 - Math.floorMod(damage, 16)];
            case 349 -> "item.fish." + new String[]{"cod", "salmon", "clownfish", "pufferfish"}[Math.floorMod(damage, 4)] + ".raw";
            case 350 -> "item.fish." + (damage == 1 ? "salmon" : "cod") + ".cooked";
            case 397 -> "item.skull." + new String[]{"skeleton", "wither", "zombie", "char", "creeper"}[Math.min(4, Math.max(0, damage))];
            default -> "item." + base;
        };
    }

    private static String blockKey(int id, int damage) {
        String base = BLOCKS[id];
        if (base == null) {
            return null;
        }
        String variant = switch (id) {
            case 1 -> pick(new String[]{"stone", "granite", "graniteSmooth", "diorite", "dioriteSmooth", "andesite", "andesiteSmooth"}, damage);
            case 3 -> pick(new String[]{"default", "coarse", "podzol"}, damage);
            case 5, 6, 126 -> pick(WOODS, damage & 7);
            case 12 -> pick(new String[]{"default", "red"}, damage);
            case 17, 18 -> pick(WOODS, damage & 3);
            case 161, 162 -> pick(WOODS, 4 + (damage & 1));
            case 19 -> pick(new String[]{"dry", "wet"}, damage);
            case 24, 179 -> pick(new String[]{"default", "chiseled", "smooth"}, damage);
            case 31 -> pick(new String[]{"shrub", "grass", "fern"}, damage);
            case 35, 95, 159, 160, 171 -> pick(COLORS, damage & 15);
            case 37 -> "dandelion";
            case 38 -> pick(new String[]{"poppy", "blueOrchid", "allium", "houstonia", "tulipRed", "tulipOrange", "tulipWhite", "tulipPink", "oxeyeDaisy"}, damage);
            case 175 -> pick(new String[]{"sunflower", "syringa", "grass", "fern", "rose", "paeonia"}, damage & 7);
            case 44 -> pick(new String[]{"stone", "sand", "wood", "cobble", "brick", "smoothStoneBrick", "netherBrick", "quartz"}, damage & 7);
            case 182 -> "red_sandstone";
            case 97 -> pick(new String[]{"stone", "cobble", "brick", "mossybrick", "crackedbrick", "chiseledbrick"}, damage);
            case 98 -> pick(new String[]{"default", "mossy", "cracked", "chiseled"}, damage);
            case 139 -> pick(new String[]{"normal", "mossy"}, damage);
            case 145 -> pick(new String[]{"intact", "slightlyDamaged", "veryDamaged"}, damage >> 2);
            case 155 -> pick(new String[]{"default", "chiseled", "lines", "lines", "lines"}, damage);
            case 168 -> pick(new String[]{"rough", "bricks", "dark"}, damage);
            default -> null;
        };
        // Leaves and logs of the second set keep their own base names (leaves2 is named "leaves", log2 "log").
        return "tile." + base + (variant == null ? "" : "." + variant);
    }

    private static String pick(String[] names, int index) {
        return index >= 0 && index < names.length ? names[index] : names[0];
    }
}
