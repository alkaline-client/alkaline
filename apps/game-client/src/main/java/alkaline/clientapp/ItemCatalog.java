package alkaline.clientapp;

import alkaline.game.inventory.ItemColors;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Items;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.model.CustomItems;
import alkaline.renderer.model.ItemIcons;
import alkaline.renderer.model.ItemMeshes;
import alkaline.renderer.model.ItemModelNames;
import alkaline.renderer.world.CustomColors;
import alkaline.renderer.world.vanilla.BlockModels189;
import alkaline.renderer.world.vanilla.BlockStates189;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every item's model as the client draws it, shared by the world (hands, dropped items, projectiles) and the HUD
 * (inventory icons): which item model a stack uses (the bow's pulling frames, a cast fishing rod, a resource pack's
 * custom item texture), its mesh (built a few per frame on the render thread, from whatever assets are active), its
 * tints, and its icon. Render-thread state.
 */
public final class ItemCatalog {
    /** Icon pixels per 16-pixel slot: four per texel, so block faces stay crisp. */
    static final int ICON_SIZE = 64;
    private static final int BUILDS_PER_FRAME = 4;

    private AssetSource assets;
    private BlockModels189 blocks;
    private final Map<String, ItemMeshes.Mesh> meshes = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<String, RgbaImage> icons = new HashMap<>();
    private int grass = 0x7FB238;
    private int foliage = 0x48B518;
    /** The active packs' potion and spawn egg colors (OptiFine color.properties). */
    private ItemColors.Custom colors = ItemColors.Custom.NONE;
    /** Bumped whenever the assets change, so users drop what they uploaded. */
    private int generation;
    /** The active packs' custom item textures (OptiFine CIT). */
    private CustomItems custom = CustomItems.NONE;
    /** The CIT rule index each stack last matched (-1 for none); stacks are values, so equal stacks share it. */
    private final Map<ItemStack, Integer> matched = new HashMap<>();
    private final Map<ItemStack, Integer> armorMatched = new HashMap<>();
    /** Glint styles by stack (see {@link #glintStyle}), and each custom style's enchantment rules from 1 up. */
    private final Map<ItemStack, Integer> glintMatched = new HashMap<>();
    private final java.util.List<java.util.List<CustomItems.Rule>> glintStyles = new java.util.ArrayList<>();
    private static Map<String, Integer> ids;

    ItemCatalog(AssetSource assets) {
        assets(assets);
    }

    /** Switches to another asset set (resource packs changed): everything is rebuilt on demand. */
    void assets(AssetSource source) {
        assets = source;
        blocks = source == null ? null : new BlockModels189(source);
        meshes.clear();
        failed.clear();
        pending.clear();
        queued.clear();
        icons.clear();
        generation++;
        matched.clear();
        armorMatched.clear();
        glintMatched.clear();
        glintStyles.clear();
        custom = source == null ? CustomItems.NONE : CustomItems.load(source, ItemCatalog::id);
        if (!custom.rules().isEmpty()) {
            System.err.println("Custom item textures: " + custom.rules().size() + " rules.");
        }
        colors = source == null ? ItemColors.Custom.NONE : customColors(CustomColors.load(source));
        grass = colormap("colormap/grass", 0x7FB238);
        foliage = colormap("colormap/foliage", 0x48B518);
    }

    private static ItemColors.Custom customColors(CustomColors loaded) {
        if (loaded.isEmpty()) {
            return ItemColors.Custom.NONE;
        }
        return new ItemColors.Custom() {
            @Override
            public int potion(int effect) {
                return effect == 0 ? loaded.waterPotion() : loaded.potion(effect);
            }

            @Override
            public int egg(int entity, int layer) {
                return loaded.egg(entity, layer);
            }
        };
    }

    int generation() {
        return generation;
    }

    /** The item model a stack draws with ({@code bowPull} ticks drawn, or -1; {@code cast} for a rod with a hook out). */
    static String modelName(ItemStack stack, int bowPull, boolean cast) {
        if (stack == null) {
            return null;
        }
        if (stack.id() == 261 && bowPull >= 0) {
            // RenderItem.renderItemModelForEntity: the pulling frames at 1, 14 and 18 ticks.
            if (bowPull >= 18) {
                return "bow_pulling_2";
            }
            if (bowPull > 13) {
                return "bow_pulling_1";
            }
            if (bowPull > 0) {
                return "bow_pulling_0";
            }
        }
        if (stack.id() == 346 && cast) {
            return "fishing_rod_cast";
        }
        String registry = stack.id() < 256 ? Blocks189.name(stack.id()) : Items.name(stack.id());
        return ItemModelNames.model(registry, stack.damage());
    }

    /**
     * The model key a stack draws with: {@link #modelName}, or that model with a resource pack's custom item
     * texture ({@code cit:<rule>:<model>}).
     */
    String model(ItemStack stack, int bowPull, boolean cast) {
        String base = modelName(stack, bowPull, cast);
        if (base == null || custom.isEmpty()) {
            return base;
        }
        CustomItems.Rule rule = rule(stack);
        return rule == null ? base : "cit:" + rule.index() + ":" + base;
    }

    /**
     * A worn armor piece's texture as a resource pack's custom armor rule replaces it (a full asset path), or null
     * to keep {@code original} (for example {@code models/armor/diamond_layer_1}).
     */
    String armorTexture(ItemStack stack, String original) {
        if (stack == null || custom.isEmpty()) {
            return null;
        }
        Integer index = armorMatched.get(stack);
        if (index == null) {
            if (armorMatched.size() > 256) {
                armorMatched.clear();
            }
            CustomItems.Rule rule = custom.armor(new Query(stack));
            index = rule == null ? -1 : rule.index();
            armorMatched.put(stack, index);
        }
        return index < 0 ? null : custom.rule(index).texture(original, false);
    }

    private CustomItems.Rule rule(ItemStack stack) {
        Integer index = matched.get(stack);
        if (index == null) {
            if (matched.size() > 4096) {
                matched.clear();
            }
            CustomItems.Rule rule = custom.item(new Query(stack));
            index = rule == null ? -1 : rule.index();
            matched.put(stack, index);
        }
        return index < 0 ? null : custom.rule(index);
    }

    /**
     * The enchantment glint a stack draws: -1 for none, 0 for vanilla's, or n > 0 for a resource pack's
     * enchantment rules ({@link #glintRules}). Styles stay numbered until the assets change ({@link #generation}).
     */
    int glintStyle(ItemStack stack) {
        if (stack == null || !stack.glint()) {
            return -1;
        }
        if (!custom.hasEnchantmentRules()) {
            return custom.useGlint() ? 0 : -1;
        }
        Integer style = glintMatched.get(stack);
        if (style == null) {
            if (glintMatched.size() > 4096) {
                glintMatched.clear();
            }
            java.util.List<CustomItems.Rule> rules = custom.enchantments(new Query(stack));
            int index = glintStyles.indexOf(rules);
            if (index < 0 && !rules.isEmpty()) {
                glintStyles.add(rules);
                index = glintStyles.size() - 1;
            }
            style = rules.isEmpty() ? (custom.useGlint() ? 0 : -1) : index + 1;
            glintMatched.put(stack, style);
        }
        return style;
    }

    /** The enchantment rules of a custom glint style (n > 0), drawn in order. */
    java.util.List<CustomItems.Rule> glintRules(int style) {
        return glintStyles.get(style - 1);
    }

    /** The active assets (resource packs included), or null. */
    AssetSource assets() {
        return assets;
    }

    /** Item or block id by registry name, with or without {@code minecraft:}; -1 when unknown. */
    static int id(String name) {
        if (ids == null) {
            Map<String, Integer> table = new HashMap<>();
            for (int id = 0; id < 256; id++) {
                String block = Blocks189.name(id);
                if (block != null) {
                    table.putIfAbsent(block, id);
                }
            }
            for (int id = 256; id < 2268; id++) {
                String item = Items.name(id);
                if (item != null) {
                    table.putIfAbsent(item, id);
                }
            }
            ids = table;
        }
        String key = name.startsWith("minecraft:") ? name.substring(10) : name;
        return ids.getOrDefault(key, -1);
    }

    /** A stack as custom item rules read it. */
    private record Query(ItemStack stack) implements CustomItems.Stack {
        @Override
        public int id() {
            return stack.id();
        }

        @Override
        public int damage() {
            return stack.damage();
        }

        @Override
        public int count() {
            return stack.count();
        }

        @Override
        public int maxDamage() {
            return Items.maxDamage(stack.id());
        }

        @Override
        public int enchantment(int id) {
            int level = stack.enchantment(id);
            return level > 0 ? level : stored(id);
        }

        /** Enchanted books keep theirs in StoredEnchantments. */
        private int stored(int id) {
            alkaline.game.data.nbt.NBTTagCompound tag = stack.tag();
            if (tag == null) {
                return 0;
            }
            alkaline.game.data.nbt.NBTTagList list = tag.getTagList("StoredEnchantments", 10);
            for (int index = 0; index < list.tagCount(); index++) {
                if (list.getCompoundTagAt(index).getShort("id") == id) {
                    return list.getCompoundTagAt(index).getShort("lvl");
                }
            }
            return 0;
        }

        @Override
        public int[] enchantments() {
            alkaline.game.data.nbt.NBTTagCompound tag = stack.tag();
            if (tag == null) {
                return new int[0];
            }
            alkaline.game.data.nbt.NBTTagList list = tag.getTagList(tag.hasKey("ench", 9) ? "ench"
                    : "StoredEnchantments", 10);
            int[] found = new int[list.tagCount()];
            for (int index = 0; index < found.length; index++) {
                found[index] = list.getCompoundTagAt(index).getShort("id");
            }
            return found;
        }

        @Override
        public boolean nbt(String[] path, java.util.function.Predicate<String> test) {
            return stack.tag() != null && walk(stack.tag(), path, 0, test);
        }

        private static boolean walk(alkaline.game.data.nbt.NBTBase tag, String[] path, int depth,
                                    java.util.function.Predicate<String> test) {
            if (depth == path.length) {
                return test.test(text(tag));
            }
            String key = path[depth];
            if (tag instanceof alkaline.game.data.nbt.NBTTagCompound compound) {
                return compound.hasKey(key) && walk(compound.getTag(key), path, depth + 1, test);
            }
            if (tag instanceof alkaline.game.data.nbt.NBTTagList list) {
                if (key.equals("*")) {
                    for (int index = 0; index < list.tagCount(); index++) {
                        if (walk(list.get(index), path, depth + 1, test)) {
                            return true;
                        }
                    }
                    return false;
                }
                try {
                    int index = Integer.parseInt(key);
                    return index >= 0 && index < list.tagCount() && walk(list.get(index), path, depth + 1, test);
                } catch (NumberFormatException notIndex) {
                    return false;
                }
            }
            return false;
        }

        private static String text(alkaline.game.data.nbt.NBTBase tag) {
            if (tag instanceof alkaline.game.data.nbt.NBTTagString string) {
                return string.getString();
            }
            if (tag instanceof alkaline.game.data.nbt.NBTTagFloat || tag instanceof alkaline.game.data.nbt.NBTTagDouble) {
                return String.valueOf(((alkaline.game.data.nbt.NBTBase.NBTPrimitive) tag).getDouble());
            }
            if (tag instanceof alkaline.game.data.nbt.NBTBase.NBTPrimitive number) {
                return String.valueOf(number.getLong());
            }
            return tag.toString();
        }
    }

    /** The mesh of an item model, or null while it is queued (or when it cannot be built). */
    ItemMeshes.Mesh mesh(String name) {
        if (name == null || assets == null) {
            return null;
        }
        ItemMeshes.Mesh mesh = meshes.get(name);
        if (mesh == null && !failed.contains(name) && queued.add(name)) {
            pending.add(name);
        }
        return mesh;
    }

    /** Whether an item model could not be built (missing model or textures). */
    boolean failed(String name) {
        return failed.contains(name);
    }

    /** Builds a few queued meshes; call once per frame. Returns whether any finished. */
    boolean build() {
        boolean built = false;
        for (int count = 0; count < BUILDS_PER_FRAME && !pending.isEmpty(); count++) {
            String name = pending.poll();
            queued.remove(name);
            try {
                if (name.startsWith("state:") || name.startsWith("loc:")) {
                    meshes.put(name, stateMesh(name));
                } else if (name.startsWith("cit:")) {
                    int split = name.indexOf(':', 4);
                    CustomItems.Rule rule = custom.rule(Integer.parseInt(name.substring(4, split)));
                    meshes.put(name, ItemMeshes.build(assets, blocks, name.substring(split + 1), rule));
                } else {
                    meshes.put(name, ItemMeshes.build(assets, blocks, name));
                }
                built = true;
            } catch (IOException | RuntimeException failure) {
                failed.add(name);
                System.err.println("Item model " + name + " unavailable: " + failure.getMessage());
            }
        }
        return built;
    }

    /** The model name of block state ({@code id}, {@code meta}) drawn as the world draws it, not as an item. */
    static String stateModel(int id, int meta) {
        return "state:" + id + ':' + meta;
    }

    /** The model name of one variant of a blockstate file ({@code item_frame}, {@code normal}). */
    static String locationModel(String file, String variant) {
        return "loc:" + file + '#' + variant;
    }

    /** Builds a {@link #stateModel} or {@link #locationModel}. */
    private ItemMeshes.Mesh stateMesh(String name) throws IOException {
        if (name.startsWith("loc:")) {
            int split = name.indexOf('#');
            return ItemMeshes.state(assets, blocks, new BlockStates189.Location(name.substring(4, split),
                    name.substring(split + 1)), null, name);
        }
        String[] parts = name.split(":");
        int id = Integer.parseInt(parts[1]);
        int meta = Integer.parseInt(parts[2]);
        // Chests (and ender and trapped ones) are drawn with the chest model, not a block model.
        String chest = switch (id) {
            case 54 -> "entity/chest/normal";
            case 146 -> "entity/chest/trapped";
            case 130 -> "entity/chest/ender";
            default -> null;
        };
        BlockStates189.Location location = BlockStates189.location(id, meta, 0);
        if (location == null && chest == null) {
            throw new IOException("Block " + id + ":" + meta + " has no model.");
        }
        return ItemMeshes.state(assets, blocks, location, chest, name);
    }

    /** The tint of each of a mesh's tint slots for a stack (0xFFRRGGBB, or 0xFFFFFFFF untinted). */
    int[] tints(ItemStack stack, ItemMeshes.Mesh mesh) {
        int slots = 0;
        for (ItemMeshes.Layer layer : mesh.layers()) {
            slots = Math.max(slots, layer.tintIndex() + 1);
        }
        if (mesh.block()) {
            for (alkaline.renderer.world.vanilla.BlockModels189.Quad quad : mesh.quads()) {
                slots = Math.max(slots, quad.tintIndex() + 1);
            }
        }
        int[] tints = new int[Math.max(1, slots)];
        for (int slot = 0; slot < tints.length; slot++) {
            int color = ItemColors.tint(stack, slot, grass, foliage, colors);
            tints[slot] = color < 0 ? 0xFFFFFFFF : 0xFF000000 | color;
        }
        return tints;
    }

    /**
     * The inventory icon of a stack under a key the HUD can look it up by, or null while the mesh is queued. The
     * key changes with the model and its tints.
     */
    String icon(ItemStack stack, Map<String, RgbaImage> out) {
        String name = model(stack, -1, false);
        ItemMeshes.Mesh mesh = mesh(name);
        if (mesh == null) {
            return null;
        }
        int[] tints = tints(stack, mesh);
        StringBuilder key = new StringBuilder("gen:").append(generation).append(':').append(name);
        for (int tint : tints) {
            if (tint != 0xFFFFFFFF) {
                key.append(':').append(Integer.toHexString(tint));
            }
        }
        String id = key.toString();
        RgbaImage icon = icons.get(id);
        if (icon == null) {
            icon = ItemIcons.icon(mesh, tints, ICON_SIZE);
            icons.put(id, icon);
            out.put(id, icon);
        }
        return id;
    }

    /** An icon {@link #icon} rendered, by its key, or null. */
    RgbaImage iconImage(String key) {
        return icons.get(key);
    }

    /** ColorizerGrass / ColorizerFoliage at temperature 0.5 and rainfall 1.0: the colormaps' pixel (127, 127). */
    private int colormap(String path, int fallback) {
        if (assets == null) {
            return fallback;
        }
        try (InputStream input = assets.open("assets/minecraft/textures/" + path + ".png")) {
            if (input == null) {
                return fallback;
            }
            RgbaImage image = ImageDecoder.decode(input);
            int x = (int) ((1.0 - 0.5) * 255.0);
            int y = (int) ((1.0 - 1.0 * 0.5) * 255.0);
            if (x >= image.width() || y >= image.height()) {
                return fallback;
            }
            byte[] pixels = image.copyPixels();
            int index = (y * image.width() + x) * 4;
            return (pixels[index] & 255) << 16 | (pixels[index + 1] & 255) << 8 | pixels[index + 2] & 255;
        } catch (IOException | RuntimeException failure) {
            return fallback;
        }
    }
}
