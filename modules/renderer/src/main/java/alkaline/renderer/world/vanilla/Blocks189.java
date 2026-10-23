package alkaline.renderer.world.vanilla;

import alkaline.renderer.world.MaterialPass;

/**
 * Minecraft 1.8.9's blocks as the renderer needs them, indexed by block id (the upper 12 bits of a block state;
 * the lower 4 are metadata). Values follow the 1.8.9 sources: each block class's {@code isOpaqueCube},
 * {@code isFullCube}, {@code getBlockLayer}, {@code getRenderType}, light level, offset type and color
 * multiplier, its material's flags, and the derived values vanilla computes at registration
 * ({@code translucent = !material.blocksLight()}, light opacity, {@code useNeighborBrightness}). Leaves use fancy
 * graphics (not opaque, cutout).
 */
public final class Blocks189 {
    public static final int COUNT = 256;

    /** How a block is drawn into sections. */
    public enum Render {
        /** Air, barriers, and blocks drawn by a tile-entity renderer (chests, signs, skulls, banners). */
        NONE,
        /** A JSON block model. */
        MODEL,
        /** Water or lava (vanilla's BlockFluidRenderer). */
        LIQUID
    }

    /** Vanilla's per-block color multiplier, applied to model faces with a tint index. */
    public enum Tint { NONE, GRASS, FOLIAGE, FOLIAGE_PINE, FOLIAGE_BIRCH, OLD_LEAVES, DOUBLE_PLANT, LILY_PAD, REDSTONE, STEM, WATER }

    /** Which {@code shouldSideBeRendered} a block uses for faces with a cull face. */
    public enum Sides {
        /** Block's own bounds, then "is the neighbor an opaque cube". */
        DEFAULT,
        /** Always drawn (fences, gates, hoppers, anvils, piston heads, dragon eggs). */
        ALWAYS,
        /** Ice and slime: hidden against the same block, otherwise default. */
        SAME_BLOCK,
        /** Glass and stained glass: drawn unless the neighbor has the identical state. */
        GLASS,
        /** Panes: hidden against the same block, otherwise default. */
        PANE,
        /** Walls: only the bottom face uses the default rule. */
        WALL,
        /** Carpets and snow layers: the top face is always drawn. */
        TOP_ALWAYS,
        FARMLAND,
        /** Repeaters and comparators: top and bottom faces never drawn. */
        DIODE,
        SLAB,
        PORTAL
    }

    /** Vanilla's materials, with the flags rendering reads. */
    public enum Material {
        AIR(false, false, false, false), GRASS, GROUND, WOOD, ROCK, IRON, ANVIL, WATER(false, true, false, false),
        LAVA(false, true, false, false), LEAVES(true, true, true, true), PLANTS(false, false, false, false),
        VINE(false, false, false, false), SPONGE, CLOTH, FIRE(false, false, false, false), SAND,
        CIRCUITS(false, false, false, false), CARPET(false, false, false, false), GLASS(true, true, true, true),
        REDSTONE_LIGHT, TNT(true, true, true, true), ICE(true, true, true, true), PACKED_ICE,
        SNOW(false, false, false, true), CRAFTED_SNOW, CACTUS(true, true, true, true), CLAY, GOURD, DRAGON_EGG,
        PORTAL(false, false, false, false), CAKE, WEB(false, true, true, false), PISTON, BARRIER;

        final boolean blocksMovement;
        final boolean blocksLight;
        final boolean solid;
        final boolean translucentMaterial;

        Material() {
            this(true, true, true, false);
        }

        Material(boolean blocksMovement, boolean blocksLight, boolean solid, boolean translucentMaterial) {
            this.blocksMovement = blocksMovement;
            this.blocksLight = blocksLight;
            this.solid = solid;
            this.translucentMaterial = translucentMaterial;
        }

        /** Vanilla's {@code Material.isOpaque}. */
        public boolean opaque() {
            return !translucentMaterial && blocksMovement;
        }

        public boolean solid() {
            return solid;
        }

        public boolean blocksMovement() {
            return blocksMovement;
        }
    }

    private static final String[] NAME = new String[COUNT];
    private static final Material[] MATERIAL = new Material[COUNT];
    private static final Render[] RENDER = new Render[COUNT];
    private static final boolean[] OPAQUE_CUBE = new boolean[COUNT];
    private static final boolean[] FULL_CUBE = new boolean[COUNT];
    private static final MaterialPass[] PASS = new MaterialPass[COUNT];
    private static final byte[] LIGHT = new byte[COUNT];
    private static final int[] OPACITY = new int[COUNT];
    private static final byte[] OFFSET = new byte[COUNT];
    private static final Tint[] TINT = new Tint[COUNT];
    private static final Sides[] SIDES = new Sides[COUNT];
    private static final boolean[] TRANSLUCENT = new boolean[COUNT];
    private static final boolean[] NEIGHBOR_BRIGHTNESS = new boolean[COUNT];
    private static final boolean[] FLAMMABLE = new boolean[COUNT];
    private static final boolean[] POWER_SOURCE = new boolean[COUNT];

    private Blocks189() {
    }

    public static boolean known(int id) {
        return id >= 0 && id < COUNT && NAME[id] != null;
    }

    /** Registry name ({@code minecraft:} omitted), or null for an unknown id. */
    public static String name(int id) {
        return NAME[id];
    }

    public static Material material(int id) {
        return MATERIAL[id];
    }

    public static Render render(int id) {
        return RENDER[id];
    }

    public static boolean opaqueCube(int id) {
        return OPAQUE_CUBE[id];
    }

    public static boolean fullCube(int id) {
        return FULL_CUBE[id];
    }

    /** Vanilla's {@code isBlockNormalCube}: darkens neighbors' corners (ambient occlusion 0.2). */
    public static boolean normalCube(int id) {
        return MATERIAL[id].blocksMovement && FULL_CUBE[id];
    }

    /** Vanilla's {@code isFullBlock} (the {@code fullBlock} field): opaque cubes and double slabs. */
    public static boolean fullBlock(int id) {
        return OPAQUE_CUBE[id];
    }

    /** The section pass of the block's layer: solid, cutout (mipped or not) or translucent. */
    public static MaterialPass pass(int id) {
        return PASS[id];
    }

    /** Vanilla light opacity: how much sky and block light the block absorbs (255 for opaque cubes). */
    public static int opacity(int id) {
        return OPACITY[id];
    }

    /** Light emitted, 0..15. */
    public static int light(int id) {
        return LIGHT[id];
    }

    /** Vanilla's per-position model offset: 0 none, 1 XZ, 2 XYZ. */
    public static int offset(int id) {
        return OFFSET[id];
    }

    public static Tint tint(int id) {
        return TINT[id];
    }

    public static Sides sides(int id) {
        return SIDES[id];
    }

    /** Vanilla's {@code Block.isTranslucent}: smooth lighting may sample corners past this block. */
    public static boolean translucent(int id) {
        return TRANSLUCENT[id];
    }

    /** Vanilla's {@code getUseNeighborBrightness}: brightness is the brightest of the cell's up/side neighbors. */
    public static boolean neighborBrightness(int id) {
        return NEIGHBOR_BRIGHTNESS[id];
    }

    /** Fire's encouragement is above zero: fire next to it takes its climbing shape. */
    public static boolean flammable(int id) {
        return FLAMMABLE[id];
    }

    /** {@code canProvidePower}: redstone wire connects to it. */
    public static boolean powerSource(int id) {
        return POWER_SOURCE[id];
    }

    public static boolean stairs(int id) {
        return id == 53 || id == 67 || id == 108 || id == 109 || id == 114 || id == 128 || id == 134 || id == 135
                || id == 136 || id == 156 || id == 163 || id == 164 || id == 180;
    }

    /** Half slabs (stone, wooden, red sandstone). */
    public static boolean halfSlab(int id) {
        return id == 44 || id == 126 || id == 182;
    }

    public static boolean fence(int id) {
        return id == 85 || id == 113 || (id >= 188 && id <= 192);
    }

    public static boolean fenceGate(int id) {
        return id == 107 || (id >= 183 && id <= 187);
    }

    public static boolean door(int id) {
        return id == 64 || id == 71 || (id >= 193 && id <= 197);
    }

    public static boolean pane(int id) {
        return id == 101 || id == 102 || id == 160;
    }

    public static boolean liquid(int id) {
        return id >= 8 && id <= 11;
    }

    // ---- the table -------------------------------------------------------------------------------------------

    private static final class Def {
        private final int id;

        Def(int id) {
            this.id = id;
        }

        Def cutout() {
            PASS[id] = MaterialPass.CUTOUT;
            return this;
        }

        Def translucentLayer() {
            PASS[id] = MaterialPass.TRANSLUCENT;
            return this;
        }

        /** Not an opaque cube, but a full cube (glass-like, leaves, spawners, barriers). */
        Def notOpaque() {
            OPAQUE_CUBE[id] = false;
            return this;
        }

        /** Neither opaque nor a full cube: most shaped blocks. */
        Def shaped() {
            OPAQUE_CUBE[id] = false;
            FULL_CUBE[id] = false;
            return this;
        }

        Def light(int level) {
            LIGHT[id] = (byte) level;
            return this;
        }

        Def opacity(int value) {
            OPACITY[id] = value;
            return this;
        }

        Def offset(int type) {
            OFFSET[id] = (byte) type;
            return this;
        }

        Def tint(Tint value) {
            TINT[id] = value;
            return this;
        }

        Def sides(Sides value) {
            SIDES[id] = value;
            return this;
        }

        Def render(Render value) {
            RENDER[id] = value;
            return this;
        }

        Def flammable() {
            FLAMMABLE[id] = true;
            return this;
        }

        Def power() {
            POWER_SOURCE[id] = true;
            return this;
        }
    }

    /** {@code setLightLevel(f)} stores {@code (int) (15 * f)}. */
    private static int level(float value) {
        return (int) (15.0F * value);
    }

    private static Def def(int id, String name, Material material) {
        NAME[id] = name;
        MATERIAL[id] = material;
        RENDER[id] = Render.MODEL;
        OPAQUE_CUBE[id] = true;
        FULL_CUBE[id] = true;
        PASS[id] = MaterialPass.OPAQUE;
        TINT[id] = Tint.NONE;
        SIDES[id] = Sides.DEFAULT;
        OPACITY[id] = -1;
        return new Def(id);
    }

    /** Plants (vanilla's BlockBush): not opaque, not full, cutout. */
    private static Def bush(int id, String name, Material material) {
        return def(id, name, material).shaped().cutout();
    }

    private static void stairs(int id, String name, Material material) {
        def(id, name, material).shaped().opacity(255);
    }

    private static void slabs(int doubleId, String doubleName, int halfId, String halfName, Material material) {
        def(doubleId, doubleName, material).opacity(255);
        def(halfId, halfName, material).shaped().opacity(255).sides(Sides.SLAB);
    }

    static {
        def(0, "air", Material.AIR).render(Render.NONE).notOpaque();
        def(1, "stone", Material.ROCK);
        def(2, "grass", Material.GRASS).cutout().tint(Tint.GRASS);
        def(3, "dirt", Material.GROUND);
        def(4, "cobblestone", Material.ROCK);
        def(5, "planks", Material.WOOD).flammable();
        bush(6, "sapling", Material.PLANTS);
        def(7, "bedrock", Material.ROCK);
        def(8, "flowing_water", Material.WATER).render(Render.LIQUID).shaped().translucentLayer().opacity(3)
                .tint(Tint.WATER);
        def(9, "water", Material.WATER).render(Render.LIQUID).shaped().translucentLayer().opacity(3).tint(Tint.WATER);
        def(10, "flowing_lava", Material.LAVA).render(Render.LIQUID).shaped().light(15);
        def(11, "lava", Material.LAVA).render(Render.LIQUID).shaped().light(15);
        def(12, "sand", Material.SAND);
        def(13, "gravel", Material.SAND);
        def(14, "gold_ore", Material.ROCK);
        def(15, "iron_ore", Material.ROCK);
        def(16, "coal_ore", Material.ROCK);
        def(17, "log", Material.WOOD).flammable();
        def(18, "leaves", Material.LEAVES).notOpaque().cutout().opacity(1).tint(Tint.OLD_LEAVES).flammable();
        def(19, "sponge", Material.SPONGE);
        def(20, "glass", Material.GLASS).shaped().cutout().sides(Sides.GLASS);
        def(21, "lapis_ore", Material.ROCK);
        def(22, "lapis_block", Material.IRON);
        def(23, "dispenser", Material.ROCK);
        def(24, "sandstone", Material.ROCK);
        def(25, "noteblock", Material.WOOD);
        def(26, "bed", Material.CLOTH).shaped().cutout();
        def(27, "golden_rail", Material.CIRCUITS).shaped().cutout();
        def(28, "detector_rail", Material.CIRCUITS).shaped().cutout().power();
        def(29, "sticky_piston", Material.PISTON).shaped();
        def(30, "web", Material.WEB).shaped().cutout().opacity(1);
        bush(31, "tallgrass", Material.VINE).offset(2).tint(Tint.GRASS).flammable();
        bush(32, "deadbush", Material.VINE).flammable();
        def(33, "piston", Material.PISTON).shaped();
        def(34, "piston_head", Material.PISTON).shaped().sides(Sides.ALWAYS);
        def(35, "wool", Material.CLOTH).flammable();
        def(36, "piston_extension", Material.PISTON).render(Render.NONE).shaped();
        bush(37, "yellow_flower", Material.PLANTS).offset(1).flammable();
        bush(38, "red_flower", Material.PLANTS).offset(1).flammable();
        bush(39, "brown_mushroom", Material.PLANTS).light(level(0.125F));
        bush(40, "red_mushroom", Material.PLANTS);
        def(41, "gold_block", Material.IRON);
        def(42, "iron_block", Material.IRON);
        slabs(43, "double_stone_slab", 44, "stone_slab", Material.ROCK);
        def(45, "brick_block", Material.ROCK);
        def(46, "tnt", Material.TNT).flammable();
        def(47, "bookshelf", Material.WOOD).flammable();
        def(48, "mossy_cobblestone", Material.ROCK);
        def(49, "obsidian", Material.ROCK);
        def(50, "torch", Material.CIRCUITS).shaped().cutout().light(level(0.9375F));
        def(51, "fire", Material.FIRE).shaped().cutout().light(15);
        def(52, "mob_spawner", Material.ROCK).notOpaque().cutout();
        stairs(53, "oak_stairs", Material.WOOD);
        FLAMMABLE[53] = true;
        def(54, "chest", Material.WOOD).render(Render.NONE).shaped();
        def(55, "redstone_wire", Material.CIRCUITS).shaped().cutout().tint(Tint.REDSTONE);
        def(56, "diamond_ore", Material.ROCK);
        def(57, "diamond_block", Material.IRON);
        def(58, "crafting_table", Material.WOOD);
        bush(59, "wheat", Material.PLANTS);
        def(60, "farmland", Material.GROUND).shaped().opacity(255).sides(Sides.FARMLAND);
        def(61, "furnace", Material.ROCK);
        def(62, "lit_furnace", Material.ROCK).light(level(0.875F));
        def(63, "standing_sign", Material.WOOD).render(Render.NONE).shaped();
        def(64, "wooden_door", Material.WOOD).shaped().cutout();
        def(65, "ladder", Material.CIRCUITS).shaped().cutout();
        def(66, "rail", Material.CIRCUITS).shaped().cutout();
        stairs(67, "stone_stairs", Material.ROCK);
        def(68, "wall_sign", Material.WOOD).render(Render.NONE).shaped();
        def(69, "lever", Material.CIRCUITS).shaped().power();
        def(70, "stone_pressure_plate", Material.ROCK).shaped().power();
        def(71, "iron_door", Material.IRON).shaped().cutout();
        def(72, "wooden_pressure_plate", Material.WOOD).shaped().power();
        def(73, "redstone_ore", Material.ROCK);
        def(74, "lit_redstone_ore", Material.ROCK).light(level(0.625F));
        def(75, "unlit_redstone_torch", Material.CIRCUITS).shaped().cutout().power();
        def(76, "redstone_torch", Material.CIRCUITS).shaped().cutout().light(level(0.5F)).power();
        def(77, "stone_button", Material.CIRCUITS).shaped().power();
        def(78, "snow_layer", Material.SNOW).shaped().opacity(0).sides(Sides.TOP_ALWAYS);
        def(79, "ice", Material.ICE).notOpaque().translucentLayer().opacity(3).sides(Sides.SAME_BLOCK);
        def(80, "snow", Material.CRAFTED_SNOW);
        def(81, "cactus", Material.CACTUS).shaped().cutout();
        def(82, "clay", Material.CLAY);
        def(83, "reeds", Material.PLANTS).shaped().cutout().tint(Tint.GRASS);
        def(84, "jukebox", Material.WOOD);
        def(85, "fence", Material.WOOD).shaped().sides(Sides.ALWAYS).flammable();
        def(86, "pumpkin", Material.GOURD);
        def(87, "netherrack", Material.ROCK);
        def(88, "soul_sand", Material.SAND);
        def(89, "glowstone", Material.GLASS).light(15);
        def(90, "portal", Material.PORTAL).shaped().translucentLayer().light(level(0.75F)).sides(Sides.PORTAL);
        def(91, "lit_pumpkin", Material.GOURD).light(15);
        def(92, "cake", Material.CAKE).shaped().cutout();
        def(93, "unpowered_repeater", Material.CIRCUITS).shaped().cutout().sides(Sides.DIODE);
        def(94, "powered_repeater", Material.CIRCUITS).shaped().cutout().sides(Sides.DIODE);
        def(95, "stained_glass", Material.GLASS).shaped().translucentLayer().sides(Sides.GLASS);
        def(96, "trapdoor", Material.WOOD).shaped().cutout();
        def(97, "monster_egg", Material.CLAY);
        def(98, "stonebrick", Material.ROCK);
        def(99, "brown_mushroom_block", Material.WOOD);
        def(100, "red_mushroom_block", Material.WOOD);
        def(101, "iron_bars", Material.IRON).shaped().cutout().sides(Sides.PANE);
        def(102, "glass_pane", Material.GLASS).shaped().cutout().sides(Sides.PANE);
        def(103, "melon_block", Material.GOURD);
        bush(104, "pumpkin_stem", Material.PLANTS).tint(Tint.STEM);
        bush(105, "melon_stem", Material.PLANTS).tint(Tint.STEM);
        def(106, "vine", Material.VINE).shaped().cutout().tint(Tint.FOLIAGE).flammable();
        def(107, "fence_gate", Material.WOOD).shaped().sides(Sides.ALWAYS).flammable();
        stairs(108, "brick_stairs", Material.ROCK);
        stairs(109, "stone_brick_stairs", Material.ROCK);
        def(110, "mycelium", Material.GRASS);
        bush(111, "waterlily", Material.PLANTS).tint(Tint.LILY_PAD);
        def(112, "nether_brick", Material.ROCK);
        def(113, "nether_brick_fence", Material.ROCK).shaped().sides(Sides.ALWAYS);
        stairs(114, "nether_brick_stairs", Material.ROCK);
        bush(115, "nether_wart", Material.PLANTS);
        def(116, "enchanting_table", Material.ROCK).shaped().opacity(0);
        def(117, "brewing_stand", Material.IRON).shaped().cutout().light(level(0.125F));
        def(118, "cauldron", Material.IRON).shaped();
        def(119, "end_portal", Material.PORTAL).render(Render.NONE).shaped().light(15);
        def(120, "end_portal_frame", Material.ROCK).notOpaque().light(level(0.125F));
        def(121, "end_stone", Material.ROCK);
        def(122, "dragon_egg", Material.DRAGON_EGG).shaped().light(level(0.125F)).sides(Sides.ALWAYS);
        def(123, "redstone_lamp", Material.REDSTONE_LIGHT);
        def(124, "lit_redstone_lamp", Material.REDSTONE_LIGHT).light(15);
        slabs(125, "double_wooden_slab", 126, "wooden_slab", Material.WOOD);
        FLAMMABLE[125] = true;
        FLAMMABLE[126] = true;
        def(127, "cocoa", Material.PLANTS).shaped().cutout();
        stairs(128, "sandstone_stairs", Material.ROCK);
        def(129, "emerald_ore", Material.ROCK);
        def(130, "ender_chest", Material.ROCK).render(Render.NONE).shaped().light(level(0.5F));
        def(131, "tripwire_hook", Material.CIRCUITS).shaped().cutout().power();
        def(132, "tripwire", Material.CIRCUITS).shaped().translucentLayer();
        def(133, "emerald_block", Material.IRON);
        stairs(134, "spruce_stairs", Material.WOOD);
        stairs(135, "birch_stairs", Material.WOOD);
        stairs(136, "jungle_stairs", Material.WOOD);
        FLAMMABLE[134] = true;
        FLAMMABLE[135] = true;
        FLAMMABLE[136] = true;
        def(137, "command_block", Material.IRON);
        def(138, "beacon", Material.GLASS).shaped().cutout().light(15);
        def(139, "cobblestone_wall", Material.ROCK).shaped().sides(Sides.WALL);
        def(140, "flower_pot", Material.CIRCUITS).shaped().cutout();
        bush(141, "carrots", Material.PLANTS);
        bush(142, "potatoes", Material.PLANTS);
        def(143, "wooden_button", Material.CIRCUITS).shaped().power();
        def(144, "skull", Material.CIRCUITS).render(Render.NONE).shaped();
        def(145, "anvil", Material.ANVIL).shaped().opacity(0).sides(Sides.ALWAYS);
        def(146, "trapped_chest", Material.WOOD).render(Render.NONE).shaped().power();
        def(147, "light_weighted_pressure_plate", Material.IRON).shaped().power();
        def(148, "heavy_weighted_pressure_plate", Material.IRON).shaped().power();
        def(149, "unpowered_comparator", Material.CIRCUITS).shaped().cutout().sides(Sides.DIODE).power();
        def(150, "powered_comparator", Material.CIRCUITS).shaped().cutout().light(level(0.625F))
                .sides(Sides.DIODE).power();
        def(151, "daylight_detector", Material.WOOD).shaped().power();
        def(152, "redstone_block", Material.IRON).power();
        def(153, "quartz_ore", Material.ROCK);
        def(154, "hopper", Material.IRON).shaped().cutout().sides(Sides.ALWAYS);
        def(155, "quartz_block", Material.ROCK);
        stairs(156, "quartz_stairs", Material.ROCK);
        def(157, "activator_rail", Material.CIRCUITS).shaped().cutout();
        def(158, "dropper", Material.ROCK);
        def(159, "stained_hardened_clay", Material.ROCK);
        def(160, "stained_glass_pane", Material.GLASS).shaped().translucentLayer().sides(Sides.PANE);
        def(161, "leaves2", Material.LEAVES).notOpaque().cutout().opacity(1).tint(Tint.FOLIAGE).flammable();
        def(162, "log2", Material.WOOD).flammable();
        stairs(163, "acacia_stairs", Material.WOOD);
        stairs(164, "dark_oak_stairs", Material.WOOD);
        def(165, "slime", Material.CLAY).notOpaque().translucentLayer().sides(Sides.SAME_BLOCK);
        def(166, "barrier", Material.BARRIER).render(Render.NONE).notOpaque();
        TRANSLUCENT[166] = true;
        def(167, "iron_trapdoor", Material.IRON).shaped().cutout();
        def(168, "prismarine", Material.ROCK);
        def(169, "sea_lantern", Material.GLASS).light(15);
        def(170, "hay_block", Material.GRASS).flammable();
        def(171, "carpet", Material.CARPET).shaped().opacity(0).sides(Sides.TOP_ALWAYS).flammable();
        def(172, "hardened_clay", Material.ROCK);
        def(173, "coal_block", Material.ROCK).flammable();
        def(174, "packed_ice", Material.PACKED_ICE);
        bush(175, "double_plant", Material.VINE).offset(1).tint(Tint.DOUBLE_PLANT).flammable();
        def(176, "standing_banner", Material.WOOD).render(Render.NONE).shaped();
        def(177, "wall_banner", Material.WOOD).render(Render.NONE).shaped();
        def(178, "daylight_detector_inverted", Material.WOOD).shaped().power();
        def(179, "red_sandstone", Material.ROCK);
        stairs(180, "red_sandstone_stairs", Material.ROCK);
        slabs(181, "double_stone_slab2", 182, "stone_slab2", Material.ROCK);
        String[] woods = {"spruce", "birch", "jungle", "dark_oak", "acacia"};
        for (int index = 0; index < 5; index++) {
            def(183 + index, woods[index] + "_fence_gate", Material.WOOD).shaped().sides(Sides.ALWAYS).flammable();
            def(188 + index, woods[index] + "_fence", Material.WOOD).shaped().sides(Sides.ALWAYS).flammable();
        }
        String[] doors = {"spruce", "birch", "jungle", "acacia", "dark_oak"};
        for (int index = 0; index < 5; index++) {
            def(193 + index, doors[index] + "_door", Material.WOOD).shaped().cutout();
        }
        for (int id = 0; id < COUNT; id++) {
            if (NAME[id] == null) {
                continue;
            }
            Material material = MATERIAL[id];
            if (id != 166) {
                TRANSLUCENT[id] = !material.blocksLight;
            }
            if (OPACITY[id] < 0) {
                OPACITY[id] = OPAQUE_CUBE[id] ? 255 : 0;
            }
            NEIGHBOR_BRIGHTNESS[id] = material != Material.AIR
                    && (stairs(id) || halfSlab(id) || id == 43 || id == 125 || id == 181 || id == 60
                    || TRANSLUCENT[id] || OPACITY[id] == 0);
        }
    }
}
