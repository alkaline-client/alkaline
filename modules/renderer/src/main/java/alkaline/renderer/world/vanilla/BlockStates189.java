package alkaline.renderer.world.vanilla;

import java.util.Arrays;

/**
 * Maps a 1.8.9 block state (id and metadata) plus its neighbor-dependent properties to the blockstate file and
 * variant key vanilla's model loader uses ({@code BlockModelShapes} state mappers): properties sorted by name,
 * {@code name=value} pairs joined by commas, {@code normal} when none remain.
 *
 * <p>Properties vanilla derives from neighbors ({@code getActualState}) arrive as a small integer, {@code
 * extra}, whose meaning is per block (see {@link #extraCount} and {@link NeighborRules}). Every (metadata, extra)
 * combination is enumerable, so the palette bakes them all up front.
 */
public final class BlockStates189 {
    /** A blockstate file under {@code assets/minecraft/blockstates/} and the variant within it. */
    public record Location(String file, String variant) {
    }

    /** EnumFacing order: down, up, north, south, west, east (getFront). */
    static final String[] FACING = {"down", "up", "north", "south", "west", "east"};
    /** EnumFacing.getHorizontal order: south, west, north, east. */
    static final String[] HORIZONTAL = {"south", "west", "north", "east"};
    private static final String[] WOOD = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] COLOR = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] STONE = {"stone", "granite", "smooth_granite", "diorite", "smooth_diorite",
            "andesite", "smooth_andesite"};
    private static final String[] STONE_SLAB = {"stone", "sandstone", "wood_old", "cobblestone", "brick",
            "stone_brick", "nether_brick", "quartz"};
    private static final String[] RAIL = {"north_south", "east_west", "ascending_east", "ascending_west",
            "ascending_north", "ascending_south", "south_east", "south_west", "north_west", "north_east"};
    private static final String[] RED_FLOWER = {"poppy", "blue_orchid", "allium", "houstonia", "red_tulip",
            "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"};
    private static final String[] DOUBLE_PLANT = {"sunflower", "syringa", "double_grass", "double_fern",
            "double_rose", "paeonia"};
    private static final String[] LEVER = {"down_x", "east", "west", "south", "north", "up_z", "up_x", "down_z"};
    private static final String[] MUSHROOM = new String[16];
    private static final String[] STAIRS_SHAPE = {"straight", "inner_left", "inner_right", "outer_left",
            "outer_right"};
    private static final String[] ATTACH = {"none", "side", "up"};

    static {
        String[] names = {"all_inside", "north_west", "north", "north_east", "west", "center", "east",
                "south_west", "south", "south_east", "stem"};
        Arrays.fill(MUSHROOM, "all_inside");
        System.arraycopy(names, 0, MUSHROOM, 0, names.length);
        MUSHROOM[14] = "all_outside";
        MUSHROOM[15] = "all_stem";
    }

    private BlockStates189() {
    }

    /** How many neighbor-derived codes a block distinguishes (1: none). */
    public static int extraCount(int id) {
        return switch (id) {
            case 2, 3, 110 -> 2;                       // snowy
            case 51 -> 2 * 2 * 3 * 16 + 1;             // fire: alt, flip, upper, sides; plus the plain state
            case 55 -> 81;                             // redstone: four sides, none/side/up each
            case 93, 94 -> 2;                          // repeater: locked
            case 104, 105 -> 5;                        // stem: up or toward the fruit
            case 106 -> 2;                             // vine: up
            case 131 -> 2;                             // tripwire hook: suspended
            case 132 -> 16;                            // tripwire: four connections
            case 139 -> 32;                            // wall: up and four connections
            case 175 -> 6;                             // double plant upper half: the lower half's variant
            default -> {
                if (Blocks189.fence(id) || Blocks189.pane(id)) {
                    yield 16;
                }
                if (Blocks189.fenceGate(id)) {
                    yield 2;                           // in wall
                }
                if (Blocks189.stairs(id)) {
                    yield 5;                           // shape
                }
                if (Blocks189.door(id)) {
                    yield 17;                          // the other half's metadata, or 16 when it is missing
                }
                yield 1;
            }
        };
    }

    /**
     * The model location vanilla resolves for a state; null for blocks without a block model (air, liquids,
     * tile-entity blocks).
     */
    public static Location location(int id, int meta, int extra) {
        if (!Blocks189.known(id) || Blocks189.render(id) != Blocks189.Render.MODEL) {
            return null;
        }
        String name = Blocks189.name(id);
        return switch (id) {
            case 1 -> normal(pick(STONE, meta));
            case 2 -> new Location(name, "snowy=" + (extra == 1));
            case 3 -> meta == 2 ? new Location("podzol", "snowy=" + (extra == 1))
                    : normal(meta == 1 ? "coarse_dirt" : "dirt");
            case 5 -> normal(pick(WOOD, meta) + "_planks");
            case 6 -> new Location(pick(WOOD, meta & 7) + "_sapling", "stage=" + ((meta & 8) >> 3));
            case 12 -> normal(meta == 1 ? "red_sand" : "sand");
            case 17 -> new Location(WOOD[meta & 3] + "_log", "axis=" + logAxis(meta));
            case 18 -> normal(WOOD[meta & 3] + "_leaves");
            case 19 -> new Location(name, "wet=" + ((meta & 1) == 1));
            case 23, 158 -> new Location(name, "facing=" + front(meta & 7));
            case 24 -> normal(pick(new String[]{"sandstone", "chiseled_sandstone", "smooth_sandstone"}, meta));
            case 26 -> new Location(name, "facing=" + HORIZONTAL[meta & 3] + ",part=" + ((meta & 8) > 0 ? "head" : "foot"));
            case 27, 28, 157 -> new Location(name, "powered=" + ((meta & 8) > 0) + ",shape=" + pick(RAIL, Math.min(meta & 7, 5)));
            case 29, 33 -> new Location(name, "extended=" + ((meta & 8) > 0) + ",facing=" + piston(meta));
            case 31 -> normal(pick(new String[]{"dead_bush", "tall_grass", "fern"}, meta));
            case 32 -> normal("dead_bush");
            case 34 -> new Location(name, "facing=" + piston(meta) + ",short=false,type=" + ((meta & 8) > 0 ? "sticky" : "normal"));
            case 35 -> normal(COLOR[meta] + "_wool");
            case 37 -> normal("dandelion");
            case 38 -> normal(pick(RED_FLOWER, meta));
            case 43 -> new Location(STONE_SLAB[meta & 7] + "_double_slab", (meta & 8) != 0 ? "all" : "normal");
            case 44 -> new Location(STONE_SLAB[meta & 7] + "_slab", "half=" + half(meta));
            case 50, 75, 76 -> new Location(name, "facing=" + torch(meta));
            case 51 -> fire(extra);
            case 53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180 -> new Location(name,
                    "facing=" + FACING[5 - (meta & 3)] + ",half=" + ((meta & 4) > 0 ? "top" : "bottom")
                            + ",shape=" + STAIRS_SHAPE[extra]);
            case 55 -> new Location(name, "east=" + ATTACH[extra / 27 % 3] + ",north=" + ATTACH[extra / 9 % 3]
                    + ",south=" + ATTACH[extra / 3 % 3] + ",west=" + ATTACH[extra % 3]);
            case 59, 141, 142 -> new Location(name, "age=" + (meta & 7));
            case 60 -> new Location(name, "moisture=" + (meta & 7));
            case 61, 62, 65 -> new Location(name, "facing=" + frontHorizontal(meta));
            case 64, 71, 193, 194, 195, 196, 197 -> door(name, meta, extra);
            case 66 -> new Location(name, "shape=" + pick(RAIL, meta));
            case 69 -> new Location(name, "facing=" + LEVER[meta & 7] + ",powered=" + ((meta & 8) > 0));
            case 70, 72 -> new Location(name, "powered=" + (meta == 1));
            case 77, 143 -> new Location(name, "facing=" + button(meta) + ",powered=" + ((meta & 8) > 0));
            case 78 -> new Location(name, "layers=" + ((meta & 7) + 1));
            case 85, 113, 188, 189, 190, 191, 192, 101, 102 -> new Location(name, sides(extra));
            case 86, 91 -> new Location(name, "facing=" + HORIZONTAL[meta & 3]);
            case 90 -> new Location(name, "axis=" + ((meta & 3) == 2 ? "z" : "x"));
            case 92 -> new Location(name, "bites=" + Math.min(meta, 6));
            case 93, 94 -> new Location(name, "delay=" + (1 + (meta >> 2)) + ",facing=" + HORIZONTAL[meta & 3]
                    + ",locked=" + (extra == 1));
            case 95 -> normal(COLOR[meta] + "_stained_glass");
            case 96, 167 -> new Location(name, "facing=" + trapdoor(meta) + ",half=" + ((meta & 8) == 0 ? "bottom" : "top")
                    + ",open=" + ((meta & 4) != 0));
            case 97 -> normal(pick(new String[]{"stone", "cobblestone", "stone_brick", "mossy_brick", "cracked_brick",
                    "chiseled_brick"}, meta) + "_monster_egg");
            case 98 -> normal(pick(new String[]{"stonebrick", "mossy_stonebrick", "cracked_stonebrick",
                    "chiseled_stonebrick"}, meta));
            case 99, 100 -> new Location(name, "variant=" + MUSHROOM[meta]);
            case 104, 105 -> extra == 0 ? new Location(name, "age=" + (meta & 7) + ",facing=up")
                    : new Location(name, "facing=" + FACING[extra + 1]);
            case 106 -> new Location(name, "east=" + ((meta & 8) > 0) + ",north=" + ((meta & 4) > 0) + ",south="
                    + ((meta & 1) > 0) + ",up=" + (extra == 1) + ",west=" + ((meta & 2) > 0));
            case 107, 183, 184, 185, 186, 187 -> new Location(name, "facing=" + HORIZONTAL[meta & 3] + ",in_wall="
                    + (extra == 1) + ",open=" + ((meta & 4) != 0));
            case 110 -> new Location(name, "snowy=" + (extra == 1));
            case 115 -> new Location(name, "age=" + (meta & 3));
            case 117 -> new Location(name, "has_bottle_0=" + ((meta & 1) > 0) + ",has_bottle_1=" + ((meta & 2) > 0)
                    + ",has_bottle_2=" + ((meta & 4) > 0));
            case 118 -> new Location(name, "level=" + (meta & 3));
            case 120 -> new Location(name, "eye=" + ((meta & 4) != 0) + ",facing=" + HORIZONTAL[meta & 3]);
            case 125 -> normal(pick(WOOD, meta & 7) + "_double_slab");
            case 126 -> new Location(pick(WOOD, meta & 7) + "_slab", "half=" + half(meta));
            case 127 -> new Location(name, "age=" + Math.min((meta & 15) >> 2, 2) + ",facing=" + HORIZONTAL[meta & 3]);
            case 131 -> new Location(name, "attached=" + ((meta & 4) > 0) + ",facing=" + HORIZONTAL[meta & 3]
                    + ",powered=" + ((meta & 8) > 0) + ",suspended=" + (extra == 1));
            case 132 -> new Location(name, "attached=" + ((meta & 4) > 0) + ",east=" + ((extra & 8) != 0)
                    + ",north=" + ((extra & 1) != 0) + ",south=" + ((extra & 4) != 0) + ",suspended="
                    + ((meta & 2) > 0) + ",west=" + ((extra & 2) != 0));
            case 139 -> new Location((meta == 1 ? "mossy_cobblestone" : "cobblestone") + "_wall",
                    "east=" + ((extra & 8) != 0) + ",north=" + ((extra & 1) != 0) + ",south=" + ((extra & 4) != 0)
                            + ",up=" + ((extra & 16) != 0) + ",west=" + ((extra & 2) != 0));
            case 140 -> new Location(name, "contents=empty");
            case 145 -> new Location(name, "damage=" + Math.min((meta & 15) >> 2, 2) + ",facing=" + HORIZONTAL[meta & 3]);
            case 147, 148, 151, 178 -> new Location(name, "power=" + meta);
            case 149, 150 -> new Location(name, "facing=" + HORIZONTAL[meta & 3] + ",mode="
                    + ((meta & 4) > 0 ? "subtract" : "compare") + ",powered=" + ((meta & 8) > 0));
            case 154 -> new Location(name, "facing=" + front(meta & 7));
            case 155 -> switch (meta) {
                case 1 -> normal("chiseled_quartz_block");
                case 2 -> new Location("quartz_column", "axis=y");
                case 3 -> new Location("quartz_column", "axis=x");
                case 4 -> new Location("quartz_column", "axis=z");
                default -> normal("quartz_block");
            };
            case 159 -> normal(COLOR[meta] + "_stained_hardened_clay");
            case 160 -> new Location(COLOR[meta] + "_stained_glass_pane", sides(extra));
            case 161 -> normal(newWood(meta) + "_leaves");
            case 162 -> new Location(newWood(meta) + "_log", "axis=" + logAxis(meta));
            case 168 -> normal(pick(new String[]{"prismarine", "prismarine_bricks", "dark_prismarine"}, meta));
            case 170 -> new Location(name, "axis=" + ((meta & 12) == 4 ? "x" : (meta & 12) == 8 ? "z" : "y"));
            case 171 -> normal(COLOR[meta] + "_carpet");
            case 175 -> (meta & 8) > 0 ? new Location(DOUBLE_PLANT[extra], "half=upper")
                    : new Location(pick(DOUBLE_PLANT, meta & 7), "half=lower");
            case 179 -> normal(pick(new String[]{"red_sandstone", "chiseled_red_sandstone", "smooth_red_sandstone"},
                    meta));
            case 181 -> new Location("red_sandstone_double_slab", (meta & 8) != 0 ? "all" : "normal");
            case 182 -> new Location("red_sandstone_slab", "half=" + half(meta));
            default -> normal(name);
        };
    }

    private static Location normal(String file) {
        return new Location(file, "normal");
    }

    /** Enum lookup by metadata; out-of-range values fall back to the first constant, as byMetadata does. */
    private static String pick(String[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : values[0];
    }

    private static String front(int index) {
        return FACING[Math.abs(index % 6)];
    }

    /** getFront, with vertical results replaced by north (furnaces, ladders, chests). */
    private static String frontHorizontal(int meta) {
        String facing = front(meta);
        return facing.equals("up") || facing.equals("down") ? "north" : facing;
    }

    private static String piston(int meta) {
        int index = meta & 7;
        return index > 5 ? "down" : FACING[index];
    }

    /** BlockPlanks.byMetadata((meta & 3) + 4): acacia, dark oak, then out of range (oak). */
    private static String newWood(int meta) {
        return switch (meta & 3) {
            case 0 -> "acacia";
            case 1 -> "dark_oak";
            default -> "oak";
        };
    }

    private static String half(int meta) {
        return (meta & 8) == 0 ? "bottom" : "top";
    }

    private static String logAxis(int meta) {
        return switch (meta & 12) {
            case 0 -> "y";
            case 4 -> "x";
            case 8 -> "z";
            default -> "none";
        };
    }

    private static String torch(int meta) {
        return switch (meta) {
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            case 4 -> "north";
            default -> "up";
        };
    }

    private static String button(int meta) {
        return switch (meta & 7) {
            case 0 -> "down";
            case 1 -> "east";
            case 2 -> "west";
            case 3 -> "south";
            case 4 -> "north";
            default -> "up";
        };
    }

    private static String trapdoor(int meta) {
        return switch (meta & 3) {
            case 0 -> "north";
            case 1 -> "south";
            case 2 -> "west";
            default -> "east";
        };
    }

    /** Fences and panes: connection bits north 1, west 2, south 4, east 8. */
    private static String sides(int extra) {
        return "east=" + ((extra & 8) != 0) + ",north=" + ((extra & 1) != 0) + ",south=" + ((extra & 4) != 0)
                + ",west=" + ((extra & 2) != 0);
    }

    /**
     * Fire: code 0 is the plain state (supported from below); otherwise {@code 1 + (alt | flip << 1 | upper << 2
     * | sides << 4)} with upper 0..2 and side bits north 1, east 2, south 4, west 8 (as packed in bits 4-7).
     */
    private static Location fire(int extra) {
        boolean alt = false;
        boolean flip = false;
        int upper = 0;
        int sides = 0;
        if (extra > 0) {
            int code = extra - 1;
            alt = (code & 1) != 0;
            flip = (code & 2) != 0;
            upper = code >> 2 & 3;
            sides = code >> 4;
        }
        return new Location("fire", "alt=" + alt + ",east=" + ((sides & 2) != 0) + ",flip=" + flip + ",north="
                + ((sides & 1) != 0) + ",south=" + ((sides & 4) != 0) + ",upper=" + upper + ",west=" + ((sides & 8) != 0));
    }

    /**
     * Doors: {@code extra} is the other half's metadata, or 16 when the other half is missing. The lower half
     * stores facing and open, the upper half the hinge; a missing half leaves vanilla's defaults (facing north,
     * closed, hinge left).
     */
    private static Location door(String name, int meta, int extra) {
        boolean upper = (meta & 8) > 0;
        boolean missing = extra >= 16;
        String facing = "north";
        boolean open = false;
        String hinge = "left";
        if (!upper || !missing) {
            int lower = upper ? extra : meta;
            facing = rotateYCcw(HORIZONTAL[lower & 3]);
            open = (lower & 4) > 0;
        }
        if (upper || !missing) {
            int top = upper ? meta : extra;
            hinge = (top & 1) > 0 ? "right" : "left";
        }
        return new Location(name, "facing=" + facing + ",half=" + (upper ? "upper" : "lower") + ",hinge=" + hinge
                + ",open=" + open);
    }

    private static String rotateYCcw(String facing) {
        return switch (facing) {
            case "north" -> "west";
            case "west" -> "south";
            case "south" -> "east";
            default -> "north";
        };
    }
}
