package alkaline.game.player;

import alkaline.game.inventory.ItemStack;

/**
 * How fast the local player digs a block in 1.8.9, from the client's side: Block.getBlockHardness, whether the
 * block's material needs a tool (Material.isToolNotRequired), a held tool's speed and harvest rules
 * (Item.getStrVsBlock and canHarvestBlock of ItemTool, ItemPickaxe, ItemAxe, ItemSpade, ItemSword and
 * ItemShears), and EntityPlayer.getToolDigEfficiency's modifiers. {@link #progress} is Block.
 * getPlayerRelativeBlockHardness: the share of the block dug each tick.
 */
public final class Digging {
    /** Efficiency's enchantment id. */
    private static final int EFFICIENCY = 32;

    /** Block.setHardness by block id (0-197); -1 is unbreakable. */
    private static final float[] HARDNESS = new float[198];
    /** Blocks of the rock, iron and anvil materials: pickaxes dig these fast and harvest them. */
    private static final boolean[] ROCK_OR_IRON = new boolean[198];
    /** Blocks whose material requires a tool to harvest (rock, iron, anvil, snow, crafted snow, web, barrier). */
    private static final boolean[] TOOL_REQUIRED = new boolean[198];
    /** Blocks of the wood, plants and vine materials: axes dig these fast. */
    private static final boolean[] AXE_MATERIAL = new boolean[198];
    /** Blocks of the plants, vine, coral, leaves and gourd materials: swords dig these at 1.5. */
    private static final boolean[] SWORD_MATERIAL = new boolean[198];
    private static final boolean[] PICKAXE_SET = new boolean[198];
    private static final boolean[] AXE_SET = new boolean[198];
    private static final boolean[] SPADE_SET = new boolean[198];

    static {
        float[] hardness = {
                0f, 1.5f, 0.6f, 0.5f, 2f, 2f, 0f, -1f, 100f, 100f, 100f, 100f, 0.5f, 0.6f, 3f, 3f, 3f, 2f, 0.2f,
                0.6f, 0.3f, 3f, 3f, 3.5f, 0.8f, 0.8f, 0.2f, 0.7f, 0.7f, 0.5f, 4f, 0f, 0f, 0.5f, 0.5f, 0.8f, -1f, 0f,
                0f, 0f, 0f, 3f, 5f, 2f, 2f, 2f, 0f, 1.5f, 2f, 50f, 0f, 0f, 5f, 2f, 2.5f, 0f, 3f, 5f, 2.5f, 0f, 0.6f,
                3.5f, 3.5f, 1f, 3f, 0.4f, 0.7f, 2f, 1f, 0.5f, 0.5f, 5f, 0.5f, 3f, 3f, 0f, 0f, 0.5f, 0.1f, 0.5f, 0.2f,
                0.4f, 0.6f, 0f, 2f, 2f, 1f, 0.4f, 0.5f, 0.3f, -1f, 1f, 0.5f, 0f, 0f, 0.3f, 3f, 0.75f, 1.5f, 0.2f,
                0.2f, 5f, 0.3f, 1f, 0f, 0f, 0.2f, 2f, 2f, 1.5f, 0.6f, 0f, 2f, 2f, 2f, 0f, 5f, 0.5f, 2f, -1f, -1f, 3f,
                3f, 0.3f, 0.3f, 2f, 2f, 0.2f, 0.8f, 3f, 22.5f, 0f, 0f, 5f, 2f, 2f, 2f, -1f, 3f, 2f, 0f, 0f, 0f, 0.5f,
                1f, 5f, 2.5f, 0.5f, 0.5f, 0f, 0f, 0.2f, 5f, 3f, 3f, 0.8f, 0.8f, 0.7f, 3.5f, 1.25f, 0.3f, 0.2f, 2f,
                2f, 2f, 0f, -1f, 5f, 1.5f, 0.3f, 0.5f, 0.1f, 1.25f, 5f, 0.5f, 0f, 1f, 1f, 0.2f, 0.8f, 0.8f, 2f, 2f,
                2f, 2f, 2f, 2f, 2f, 2f, 2f, 2f, 2f, 2f, 3f, 3f, 3f, 3f, 3f};
        System.arraycopy(hardness, 0, HARDNESS, 0, HARDNESS.length);
        mark(ROCK_OR_IRON, 1, 4, 7, 14, 15, 16, 21, 22, 23, 24, 41, 42, 43, 44, 45, 48, 49, 52, 56, 57, 61, 62, 67, 70,
                71, 73, 74, 87, 98, 101, 108, 109, 112, 113, 114, 116, 117, 118, 120, 121, 128, 129, 130, 133, 137, 139,
                145, 147, 148, 152, 153, 154, 155, 156, 158, 159, 167, 168, 172, 173, 179, 180, 181, 182);
        System.arraycopy(ROCK_OR_IRON, 0, TOOL_REQUIRED, 0, TOOL_REQUIRED.length);
        mark(TOOL_REQUIRED, 30, 78, 80, 166);
        // Wood: planks, logs, note block, bookshelf, chest, workbench, signs, doors, pressure plate, jukebox, fences
        // and gates, trapdoor, huge mushrooms, slabs, stairs, daylight detectors, banners. Plants and vines: saplings,
        // flowers, mushrooms, crops, stems, lily pad, sugar cane, nether wart, cocoa, grass, dead bush, vine, tall
        // plants.
        mark(AXE_MATERIAL, 5, 17, 25, 47, 53, 54, 58, 63, 64, 68, 72, 84, 85, 96, 99, 100, 107, 125, 126, 134, 135,
                136, 146, 151, 162, 163, 164, 176, 177, 178, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194,
                195, 196, 197, 6, 31, 32, 37, 38, 39, 40, 59, 83, 104, 105, 106, 111, 115, 127, 141, 142, 175);
        mark(SWORD_MATERIAL, 6, 18, 31, 32, 37, 38, 39, 40, 59, 83, 86, 91, 103, 104, 105, 106, 111, 115, 127, 141, 142,
                161, 175);
        mark(PICKAXE_SET, 1, 4, 14, 15, 16, 21, 22, 24, 27, 28, 41, 42, 43, 44, 48, 56, 57, 66, 73, 74, 79, 87, 157,
                174, 179);
        mark(AXE_SET, 5, 17, 47, 54, 65, 86, 91, 103, 162);
        mark(SPADE_SET, 2, 3, 12, 13, 60, 78, 80, 82, 88, 110);
    }

    private Digging() {
    }

    private static void mark(boolean[] set, int... ids) {
        for (int id : ids) {
            set[id] = true;
        }
    }

    private static boolean in(boolean[] set, int id) {
        return id >= 0 && id < set.length && set[id];
    }

    /** Block.getBlockHardness: 0 for unknown ids, negative for blocks that cannot be dug (bedrock, portals). */
    public static float hardness(int blockId) {
        return blockId >= 0 && blockId < HARDNESS.length ? HARDNESS[blockId] : 0f;
    }

    /** Whether a block's material needs the right tool to drop anything (stone, ore, iron blocks, snow, web). */
    private static boolean toolRequired(int blockId) {
        return in(TOOL_REQUIRED, blockId);
    }

    private enum Kind { NONE, SWORD, PICKAXE, AXE, SPADE, SHEARS }

    /** The kind of a held item, for the speed and harvest rules. */
    private static Kind kind(int item) {
        return switch (item) {
            case 267, 268, 272, 276, 283 -> Kind.SWORD;
            case 257, 270, 274, 278, 285 -> Kind.PICKAXE;
            case 258, 271, 275, 279, 286 -> Kind.AXE;
            case 256, 269, 273, 277, 284 -> Kind.SPADE;
            case 359 -> Kind.SHEARS;
            default -> Kind.NONE;
        };
    }

    /** Item.ToolMaterial's harvest level: wood and gold 0, stone 1, iron 2, diamond 3. */
    private static int tier(int item) {
        return switch (item) {
            case 272, 273, 274, 275 -> 1;
            case 256, 257, 258, 267 -> 2;
            case 276, 277, 278, 279 -> 3;
            default -> 0;
        };
    }

    /** Item.ToolMaterial's efficiency on its proper blocks: wood 2, stone 4, iron 6, diamond 8, gold 12. */
    private static float toolSpeed(int item) {
        return switch (item) {
            case 272, 273, 274, 275 -> 4f;
            case 256, 257, 258, 267 -> 6f;
            case 276, 277, 278, 279 -> 8f;
            case 283, 284, 285, 286 -> 12f;
            default -> 2f;
        };
    }

    /** Item.getStrVsBlock: the held item's speed on a block (1 bare-handed or with an unrelated item). */
    public static float speed(ItemStack held, int blockId) {
        if (held == null) {
            return 1f;
        }
        int item = held.id();
        return switch (kind(item)) {
            case PICKAXE -> in(ROCK_OR_IRON, blockId) || in(PICKAXE_SET, blockId) ? toolSpeed(item) : 1f;
            case AXE -> in(AXE_MATERIAL, blockId) || in(AXE_SET, blockId) ? toolSpeed(item) : 1f;
            case SPADE -> in(SPADE_SET, blockId) ? toolSpeed(item) : 1f;
            case SWORD -> blockId == 30 ? 15f : in(SWORD_MATERIAL, blockId) ? 1.5f : 1f;
            case SHEARS -> blockId == 30 || blockId == 18 || blockId == 161 ? 15f : blockId == 35 ? 5f : 1f;
            case NONE -> 1f;
        };
    }

    /**
     * Whether the held item harvests a block (Item.canHarvestBlock); blocks whose material needs no tool always
     * drop. Pickaxes harvest rock and iron by tier (obsidian needs diamond; diamond, emerald, gold and redstone
     * need iron; iron and lapis need stone).
     */
    public static boolean canHarvest(ItemStack held, int blockId) {
        if (!toolRequired(blockId)) {
            return true;
        }
        if (held == null) {
            return false;
        }
        int item = held.id();
        return switch (kind(item)) {
            case PICKAXE -> {
                int tier = tier(item);
                yield switch (blockId) {
                    case 49 -> tier == 3;
                    case 56, 57, 129, 133, 14, 41, 73, 74 -> tier >= 2;
                    case 15, 42, 21, 22 -> tier >= 1;
                    default -> in(ROCK_OR_IRON, blockId);
                };
            }
            case SPADE -> blockId == 78 || blockId == 80;
            case SWORD -> blockId == 30;
            case SHEARS -> blockId == 30 || blockId == 55 || blockId == 132;
            default -> false;
        };
    }

    /**
     * EntityPlayer.getBreakSpeed: the held item's speed, plus the Efficiency bonus when it helps, scaled by Haste
     * and Mining Fatigue, a fifth under water without Aqua Affinity and a fifth off the ground.
     *
     * @param eyesInWater whether the eyes are under water and the helmet lacks Aqua Affinity
     * @param haste Haste's amplifier, or -1
     * @param miningFatigue Mining Fatigue's amplifier, or -1
     */
    public static float breakSpeed(ItemStack held, int blockId, int haste, int miningFatigue, boolean eyesInWater,
                                   boolean onGround) {
        float speed = speed(held, blockId);
        if (speed > 1f && held != null) {
            int efficiency = held.enchantment(EFFICIENCY);
            if (efficiency > 0) {
                speed += efficiency * efficiency + 1;
            }
        }
        if (haste >= 0) {
            speed *= 1f + (haste + 1) * 0.2f;
        }
        if (miningFatigue >= 0) {
            speed *= switch (miningFatigue) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
        }
        if (eyesInWater) {
            speed /= 5f;
        }
        if (!onGround) {
            speed /= 5f;
        }
        return speed;
    }

    /**
     * Block.getPlayerRelativeBlockHardness: the fraction of the block one tick of digging removes. 0 for blocks
     * that cannot be dug; 30 ticks of speed per hardness point with the right tool, 100 without.
     *
     * @param breakSpeed {@link #breakSpeed}
     */
    public static float progress(float breakSpeed, int blockId, boolean harvests) {
        float hardness = hardness(blockId);
        if (hardness < 0f) {
            return 0f;
        }
        return breakSpeed / hardness / (harvests ? 30f : 100f);
    }
}
