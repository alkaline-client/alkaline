package alkaline.renderer.world;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.world.vanilla.BlockModels189;
import alkaline.renderer.world.vanilla.BlockStates189;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the mesher needs to draw Minecraft 1.8.9 block states ({@code id << 4 | metadata}), compiled once
 * from the game's blockstate and model JSON: for every state and neighbor-derived code an entry with its
 * weighted model alternatives and tint color, the baked quads of every model as flat arrays, and for models
 * that are plain textured cubes a per-face "appearance" (texture layer, orientation, tint, optional overlay such
 * as grass sides) so the mesher can merge their faces.
 *
 * <p>Texture layers index {@link #textures()} (paths under {@code assets/minecraft/textures/}, without
 * {@code .png}); {@code missingno} stands for unresolved textures.
 */
public final class BlockPalette {
    public static final int STATES = 1 << 16;
    /** 0xRRGGBB white: an untinted face. */
    public static final int NO_TINT = 0xFFFFFF;
    /** Plains biome colors until the game supplies biomes. */
    static final int GRASS_COLOR = 0x91BD59;
    static final int FOLIAGE_COLOR = 0x77AB2F;
    static final int PINE_COLOR = 0x619961;
    static final int BIRCH_COLOR = 0x80A755;
    static final int LILY_PAD_COLOR = 0x208030;

    private final List<String> textures;
    private final boolean[] textureOpaque;
    private final int missingLayer;
    // State -> first entry; entries are indexed base + extra. -1: nothing to draw with a model.
    final int[] stateBase;
    final int[] entryFirstChoice;
    final int[] entryChoiceCount;
    final int[] entryTint;
    final int[] choiceModel;
    final int[] choiceWeight;
    /** Six appearance indices per choice for cube models; -1 for other models. */
    final int[] choiceAppearance;
    final boolean[] modelAo;
    final boolean[] modelCube;
    /** Union of each model's quads: min x, y, z, max x, y, z (the block bounds side culling tests). */
    final float[] modelBounds;
    final int[] modelQuadStart;
    final int[] modelQuadCount;
    final float[] quadPositions;
    final float[] quadUvs;
    final int[] quadLayer;
    final boolean[] quadTinted;
    final byte[] quadCullFace;
    final byte[] quadFace;
    final short[] quadShade;
    final int[] appearanceLayer;
    final byte[] appearanceOrient;
    final int[] appearanceTint;
    final int[] overlayLayer;
    final byte[] overlayOrient;
    final int[] overlayTint;
    /** Per choice and face (as {@link #choiceAppearance}): the connected-texture rule, or -1. */
    final int[] choiceCtm;
    /** The active packs' connected-texture rules, and where each rule's tile layers start in {@link #ctmTiles}. */
    final ConnectedTextures ctm;
    final int[] ctmTileStart;
    /** Tile layers of every used rule, -1 for a tile that keeps the face's texture. */
    final int[] ctmTiles;
    /** Water's tint (a custom water colormap's plains color, or none). */
    final int waterTint;
    /** The active packs' custom colors, for the sky and items. */
    final CustomColors customColors;
    final int waterStill;
    final int waterFlow;
    final int lavaStill;
    final int lavaFlow;

    private BlockPalette(Compiler compiler) {
        textures = List.copyOf(compiler.textures);
        textureOpaque = Arrays.copyOf(compiler.textureOpaque, textures.size());
        missingLayer = compiler.layer("missingno");
        stateBase = compiler.stateBase;
        entryFirstChoice = compiler.entryFirstChoice.toArray();
        entryChoiceCount = compiler.entryChoiceCount.toArray();
        entryTint = compiler.entryTint.toArray();
        choiceModel = compiler.choiceModel.toArray();
        choiceWeight = compiler.choiceWeight.toArray();
        choiceAppearance = compiler.choiceAppearance.toArray();
        modelAo = compiler.modelAo.toBooleans();
        modelCube = compiler.modelCube.toBooleans();
        modelBounds = compiler.modelBounds.toFloats();
        modelQuadStart = compiler.modelQuadStart.toArray();
        modelQuadCount = compiler.modelQuadCount.toArray();
        quadPositions = compiler.quadPositions.toFloats();
        quadUvs = compiler.quadUvs.toFloats();
        quadLayer = compiler.quadLayer.toArray();
        quadTinted = compiler.quadTinted.toBooleans();
        quadCullFace = compiler.quadCullFace.toBytes();
        quadFace = compiler.quadFace.toBytes();
        quadShade = compiler.quadShade.toShorts();
        appearanceLayer = compiler.appearanceLayer.toArray();
        appearanceOrient = compiler.appearanceOrient.toBytes();
        appearanceTint = compiler.appearanceTint.toArray();
        overlayLayer = compiler.overlayLayer.toArray();
        overlayOrient = compiler.overlayOrient.toBytes();
        overlayTint = compiler.overlayTint.toArray();
        choiceCtm = compiler.choiceCtm.toArray();
        ctm = compiler.ctm;
        ctmTileStart = compiler.ctmTileStart;
        ctmTiles = compiler.ctmTiles.toArray();
        waterTint = compiler.waterTint;
        customColors = compiler.colors;
        waterStill = compiler.layer("blocks/water_still");
        waterFlow = compiler.layer("blocks/water_flow");
        lavaStill = compiler.layer("blocks/lava_still");
        lavaFlow = compiler.layer("blocks/lava_flow");
    }

    /** Compiles every 1.8.9 block state from the game assets. */
    public static BlockPalette load(AssetSource assets) {
        return new Compiler(Objects.requireNonNull(assets, "assets")).compile();
    }

    /** The active packs' custom colors (sky, fog, potions, eggs). */
    public CustomColors customColors() {
        return customColors;
    }

    /** Texture paths by layer, for the renderer's texture array. */
    public List<String> textures() {
        return textures;
    }

    /** Whether a layer's first frame has no transparent texels (cutout faces may then draw in the opaque pass). */
    public boolean textureOpaque(int layer) {
        return textureOpaque[layer];
    }

    int missingLayer() {
        return missingLayer;
    }

    /** Whether the state is drawn from a block model (false for air, liquids and tile-entity blocks). */
    public boolean hasModel(int state) {
        return stateBase[state] >= 0;
    }

    /**
     * The texture layer vanilla's block particles (breaking, block dust) use for a state: the model's north
     * face for cubes, else its first quad, the still liquid texture for liquids; -1 for nothing to draw.
     */
    public int particleLayer(int state) {
        if (state < 0 || state >= STATES) {
            return -1;
        }
        int id = state >> 4;
        if (Blocks189.liquid(id)) {
            return id == 10 || id == 11 ? lavaStill : waterStill;
        }
        int base = stateBase[state];
        if (base < 0 || entryChoiceCount[base] == 0) {
            return -1;
        }
        int choice = entryFirstChoice[base];
        int appearance = choiceAppearance[choice * 6 + 4];
        if (appearance >= 0) {
            return appearanceLayer[appearance];
        }
        int model = choiceModel[choice];
        return modelQuadCount[model] > 0 ? quadLayer[modelQuadStart[model]] : -1;
    }

    /** EntityDiggingFX's color multiplier for a state (0xRRGGBB): the block tint, none for grass blocks. */
    public int particleTint(int state) {
        if (state < 0 || state >= STATES || state >> 4 == 2) {
            return NO_TINT;
        }
        int base = stateBase[state];
        return base < 0 ? NO_TINT : entryTint[base];
    }

    /** Number of baked models (for diagnostics). */
    public int modelCount() {
        return modelAo.length;
    }

    /** States with a model whose blockstate variant failed to resolve fall back to this many cubes (diagnostics). */
    public int stateCount() {
        int count = 0;
        for (int base : stateBase) {
            if (base >= 0) {
                count++;
            }
        }
        return count;
    }

    // ---- tints ---------------------------------------------------------------------------------------------

    /** BlockRedstoneWire.colorMultiplier(power). */
    static int redstone(int power) {
        float f = power / 15.0F;
        float red = power == 0 ? 0.3F : f * 0.6F + 0.4F;
        float green = Math.max(0.0F, f * f * 0.7F - 0.5F);
        float blue = Math.max(0.0F, f * f * 0.6F - 0.7F);
        return clamp((int) (red * 255.0F)) << 16 | clamp((int) (green * 255.0F)) << 8 | clamp((int) (blue * 255.0F));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ---- compilation ---------------------------------------------------------------------------------------

    /**
     * Texture count the renderer's two block texture arrays hold: model textures and their animation frames in the
     * first, connected-texture tiles spilling into the second.
     */
    public static final int MAX_TEXTURES = 2 * ChunkMesh.MAX_LAYERS - 512;

    private static final class Compiler {
        private final AssetSource assets;
        private final BlockModels189 models;
        private final List<String> textures = new ArrayList<>();
        private boolean[] textureOpaque = new boolean[64];
        private final Map<String, Integer> layers = new HashMap<>();
        private final Map<BlockModels189.Model, Integer> modelIndex = new IdentityHashMap<>();
        private final Map<String, Integer> appearanceIndex = new HashMap<>();
        final int[] stateBase = new int[STATES];
        final Ints entryFirstChoice = new Ints();
        final Ints entryChoiceCount = new Ints();
        final Ints entryTint = new Ints();
        final Ints choiceModel = new Ints();
        final Ints choiceWeight = new Ints();
        final Ints choiceAppearance = new Ints();
        final Ints modelAo = new Ints();
        final Ints modelCube = new Ints();
        final Floats modelBounds = new Floats();
        final Ints modelQuadStart = new Ints();
        final Ints modelQuadCount = new Ints();
        final Floats quadPositions = new Floats();
        final Floats quadUvs = new Floats();
        final Ints quadLayer = new Ints();
        final Ints quadTinted = new Ints();
        final Ints quadCullFace = new Ints();
        final Ints quadFace = new Ints();
        final Ints quadShade = new Ints();
        final Ints appearanceLayer = new Ints();
        final Ints appearanceOrient = new Ints();
        final Ints appearanceTint = new Ints();
        final Ints overlayLayer = new Ints();
        final Ints overlayOrient = new Ints();
        final Ints overlayTint = new Ints();
        final Ints choiceCtm = new Ints();
        final ConnectedTextures ctm;
        final int[] ctmTileStart;
        final Ints ctmTiles = new Ints();
        /** Cube description of each model: per face base quad, overlay quad (-1) and orientations. */
        private final List<int[]> cubeFaces = new ArrayList<>();

        /** Plains' colors (the world has no per-biome colors yet), from the active colormaps. */
        private final CustomColors colors;
        private final int grass;
        private final int foliage;
        private final int pine;
        private final int birch;
        private final int lilyPad;
        final int waterTint;

        Compiler(AssetSource assets) {
            this.assets = assets;
            colors = CustomColors.load(assets);
            grass = vanillaColormap(assets, "assets/minecraft/textures/colormap/grass.png", GRASS_COLOR);
            foliage = vanillaColormap(assets, "assets/minecraft/textures/colormap/foliage.png", FOLIAGE_COLOR);
            pine = colors.pine() != null ? colors.pine().color(1, 0.8F, 0.4F, 64) : PINE_COLOR;
            birch = colors.birch() != null ? colors.birch().color(1, 0.8F, 0.4F, 64) : BIRCH_COLOR;
            lilyPad = colors.lilyPad() >= 0 ? colors.lilyPad() : LILY_PAD_COLOR;
            waterTint = colors.water() != null ? colors.water().color(1, 0.8F, 0.4F, 64) : NO_TINT;
            models = new BlockModels189(assets);
            ctm = ConnectedTextures.load(assets);
            ctmTileStart = new int[ctm.rules().size()];
            Arrays.fill(ctmTileStart, -1);
            layer("missingno");
            for (String liquid : new String[]{"blocks/water_still", "blocks/water_flow", "blocks/lava_still",
                    "blocks/lava_flow"}) {
                layer(liquid);
            }
        }

        BlockPalette compile() {
            Arrays.fill(stateBase, -1);
            for (int id = 0; id < Blocks189.COUNT; id++) {
                if (!Blocks189.known(id) || Blocks189.render(id) != Blocks189.Render.MODEL) {
                    continue;
                }
                int extras = BlockStates189.extraCount(id);
                for (int meta = 0; meta < 16; meta++) {
                    int base = entryFirstChoice.size();
                    boolean any = false;
                    for (int extra = 0; extra < extras; extra++) {
                        BlockStates189.Location location = BlockStates189.location(id, meta, extra);
                        List<BlockModels189.Choice> choices = location == null ? List.of() : models.variants(location);
                        if (choices.isEmpty()) {
                            // Vanilla shows its missing model; an unknown state draws nothing here.
                            choices = List.of();
                        }
                        any |= !choices.isEmpty();
                        int tint = tintColor(id, meta, extra);
                        entryFirstChoice.add(choiceModel.size());
                        entryChoiceCount.add(choices.size());
                        entryTint.add(tint);
                        for (BlockModels189.Choice choice : choices) {
                            int model = model(choice.model());
                            choiceModel.add(model);
                            choiceWeight.add(choice.weight());
                            int[] faces = cubeFaces.get(model);
                            for (int face = 0; face < 6; face++) {
                                int appearance = faces == null ? -1 : appearance(model, face, faces, tint);
                                choiceAppearance.add(appearance);
                                choiceCtm.add(appearance < 0 ? -1 : ctmRule(id, meta, appearance, face));
                            }
                        }
                    }
                    if (any) {
                        stateBase[id << 4 | meta] = base;
                    } else {
                        // Roll back the unused entries of a state with no resolvable model.
                        entryFirstChoice.truncate(base);
                        entryChoiceCount.truncate(base);
                        entryTint.truncate(base);
                    }
                }
            }
            registerConnectedTiles();
            return new BlockPalette(this);
        }

        /**
         * Block.colorMultiplier in plains: a custom block colormap first (OptiFine), else the vanilla rule, with grass
         * and foliage from the active colormap images.
         */
        int tintColor(int id, int meta, int extra) {
            if (Blocks189.tint(id) != Blocks189.Tint.NONE && Blocks189.tint(id) != Blocks189.Tint.WATER) {
                int custom = colors.block(id, meta, 1, 0.8F, 0.4F, 64);
                if (custom >= 0) {
                    return custom;
                }
            }
            return switch (Blocks189.tint(id)) {
                case NONE, WATER -> NO_TINT;
                case GRASS -> grass;
                case FOLIAGE -> foliage;
                case FOLIAGE_PINE -> pine;
                case FOLIAGE_BIRCH -> birch;
                case OLD_LEAVES -> (meta & 3) == 1 ? pine : (meta & 3) == 2 ? birch : foliage;
                case DOUBLE_PLANT -> {
                    int variant = (meta & 8) > 0 ? extra : meta & 7;
                    yield variant == 2 || variant == 3 ? grass : NO_TINT;
                }
                case LILY_PAD -> lilyPad;
                case REDSTONE -> redstone(meta);
                case STEM -> (meta & 7) * 32 << 16 | (255 - (meta & 7) * 8) << 8 | (meta & 7) * 4;
            };
        }

        /** ColorizerGrass / ColorizerFoliage at plains' temperature 0.8 and rainfall 0.4. */
        private static int vanillaColormap(AssetSource assets, String path, int fallback) {
            try (InputStream input = assets.open(path)) {
                if (input == null) {
                    return fallback;
                }
                RgbaImage image = ImageDecoder.decode(input);
                if (image.width() != 256 || image.height() != 256) {
                    return fallback;
                }
                double temperature = 0.8F;
                double rainfall = 0.4F * temperature;
                int x = (int) ((1.0D - temperature) * 255.0D);
                int y = (int) ((1.0D - rainfall) * 255.0D);
                byte[] pixels = image.copyPixels();
                int index = (y * 256 + x) * 4;
                return (pixels[index] & 255) << 16 | (pixels[index + 1] & 255) << 8 | pixels[index + 2] & 255;
            } catch (IOException | RuntimeException unreadable) {
                return fallback;
            }
        }

        /** The first connected-texture rule matching a cube face, or -1. Tiles are registered after every model. */
        private int ctmRule(int id, int meta, int appearance, int face) {
            String texture = textures.get(appearanceLayer.get(appearance));
            List<ConnectedTextures.Rule> rules = ctm.rules();
            for (int index = 0; index < rules.size(); index++) {
                if (rules.get(index).matches(id, meta, texture, face)) {
                    return index;
                }
            }
            return -1;
        }

        /**
         * Registers the tiles of every rule in use as layers after all model textures (so model layers keep low
         * indices), as far as the renderer's two texture arrays hold them; faces of rules left out keep their
         * texture.
         */
        private void registerConnectedTiles() {
            int[] rules = choiceCtm.toArray();
            boolean[] used = new boolean[ctm.rules().size()];
            for (int rule : rules) {
                if (rule >= 0) {
                    used[rule] = true;
                }
            }
            for (int index = 0; index < used.length; index++) {
                if (!used[index]) {
                    continue;
                }
                ConnectedTextures.Rule rule = ctm.rules().get(index);
                int fresh = 0;
                for (String tile : rule.tiles()) {
                    fresh += layers.containsKey(tile) || tile.equals(ConnectedTextures.DEFAULT_TILE) ? 0 : 1;
                }
                if (textures.size() + fresh > MAX_TEXTURES) {
                    System.err.println("Connected texture " + rule.path() + " skipped: too many block textures.");
                    continue;
                }
                ctmTileStart[index] = ctmTiles.size();
                for (String tile : rule.tiles()) {
                    ctmTiles.add(tile.equals(ConnectedTextures.DEFAULT_TILE) ? -1 : layer(tile));
                }
            }
            for (int index = 0; index < rules.length; index++) {
                if (rules[index] >= 0 && ctmTileStart[rules[index]] < 0) {
                    choiceCtm.set(index, -1);
                }
            }
        }

        int layer(String texture) {
            Integer existing = layers.get(texture);
            if (existing != null) {
                return existing;
            }
            int index = textures.size();
            textures.add(texture);
            layers.put(texture, index);
            if (index >= textureOpaque.length) {
                textureOpaque = Arrays.copyOf(textureOpaque, textureOpaque.length * 2);
            }
            textureOpaque[index] = opaque(texture);
            return index;
        }

        /** Whether the texture's first square frame is fully opaque. */
        private boolean opaque(String texture) {
            if (texture.equals("missingno")) {
                return true;
            }
            // Connected-texture tiles are full asset paths; model textures are under textures/.
            String path = texture.startsWith("assets/") ? texture : "assets/minecraft/textures/" + texture + ".png";
            try (InputStream input = assets.open(path)) {
                if (input == null) {
                    return true;
                }
                RgbaImage image = ImageDecoder.decode(input);
                byte[] pixels = image.copyPixels();
                int size = Math.min(image.width(), image.height());
                for (int row = 0; row < size; row++) {
                    for (int column = 0; column < image.width(); column++) {
                        if ((pixels[(row * image.width() + column) * 4 + 3] & 0xFF) != 255) {
                            return false;
                        }
                    }
                }
                return true;
            } catch (IOException | RuntimeException unreadable) {
                return true;
            }
        }

        private int model(BlockModels189.Model model) {
            Integer existing = modelIndex.get(model);
            if (existing != null) {
                return existing;
            }
            int index = modelAo.size();
            modelIndex.put(model, index);
            modelAo.add(model.ambientOcclusion() ? 1 : 0);
            modelQuadStart.add(quadLayer.size());
            modelQuadCount.add(model.quads().size());
            float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                    -Float.MAX_VALUE};
            for (BlockModels189.Quad quad : model.quads()) {
                for (int corner = 0; corner < 4; corner++) {
                    for (int axis = 0; axis < 3; axis++) {
                        float value = quad.positions()[corner * 3 + axis];
                        bounds[axis] = Math.min(bounds[axis], value);
                        bounds[axis + 3] = Math.max(bounds[axis + 3], value);
                    }
                }
                quadPositions.addAll(quad.positions());
                quadUvs.addAll(quad.uvs());
                quadLayer.add(layer(quad.texture()));
                quadTinted.add(quad.tintIndex() >= 0 ? 1 : 0);
                quadCullFace.add(quad.cullFace());
                quadFace.add(quad.face());
                quadShade.add(quad.shade());
            }
            if (model.quads().isEmpty()) {
                bounds = new float[]{0, 0, 0, 1, 1, 1};
            }
            modelBounds.addAll(bounds);
            int[] faces = cube(model);
            cubeFaces.add(faces);
            modelCube.add(faces != null ? 1 : 0);
            return index;
        }

        /**
         * Recognizes a plain textured cube: per face one full-face quad (culled by its own face or not at all) with the standard
         * shade and a full-sprite texture mapping, plus at most one identical overlay. Returns per face {base quad,
         * base orientation, overlay quad or -1, overlay orientation}, or null.
         */
        private static int[] cube(BlockModels189.Model model) {
            int[] faces = new int[24];
            Arrays.fill(faces, -1);
            List<BlockModels189.Quad> quads = model.quads();
            for (int index = 0; index < quads.size(); index++) {
                BlockModels189.Quad quad = quads.get(index);
                int face = quad.face();
                // Faces without a cull face (vanilla's leaves) still qualify: the mesher skips a full face only
                // where an opaque cube covers it, which no camera outside that cube can see.
                if (quad.partRotated() || (quad.cullFace() != face && quad.cullFace() >= 0)
                        || quad.shade() != ChunkMesher.SHADE[face]) {
                    return null;
                }
                int orient = orientation(quad, face);
                if (orient < 0) {
                    return null;
                }
                if (faces[face * 4] < 0) {
                    faces[face * 4] = index;
                    faces[face * 4 + 1] = orient;
                } else if (faces[face * 4 + 2] < 0) {
                    faces[face * 4 + 2] = index;
                    faces[face * 4 + 3] = orient;
                } else {
                    return null;
                }
            }
            for (int face = 0; face < 6; face++) {
                if (faces[face * 4] < 0) {
                    return null;
                }
            }
            return faces;
        }

        /** The texture orientation (see {@link ChunkMesher#orient}) of a full-face quad, or -1. */
        private static int orientation(BlockModels189.Quad quad, int face) {
            float[] p = quad.positions();
            float[] uv = quad.uvs();
            int[] natural = new int[8];
            int[] baked = new int[8];
            for (int corner = 0; corner < 4; corner++) {
                float x = p[corner * 3];
                float y = p[corner * 3 + 1];
                float z = p[corner * 3 + 2];
                if (!unit(x) || !unit(y) || !unit(z) || !unit(uv[corner * 2]) || !unit(uv[corner * 2 + 1])) {
                    return -1;
                }
                int[] block = {Math.round(x), Math.round(y), Math.round(z)};
                // The face must lie on its own boundary plane.
                int axis = face >> 1;
                if (block[axis] != (face & 1)) {
                    return -1;
                }
                natural[corner * 2] = ChunkMesher.naturalU(face, block[0], block[1], block[2]);
                natural[corner * 2 + 1] = ChunkMesher.naturalV(face, block[0], block[1], block[2]);
                baked[corner * 2] = Math.round(uv[corner * 2]);
                baked[corner * 2 + 1] = Math.round(uv[corner * 2 + 1]);
            }
            // All four corners of the face must be distinct.
            int mask = 0;
            for (int corner = 0; corner < 4; corner++) {
                mask |= 1 << (natural[corner * 2] | natural[corner * 2 + 1] << 1);
            }
            if (mask != 15) {
                return -1;
            }
            for (int orient = 0; orient < 8; orient++) {
                boolean match = true;
                for (int corner = 0; corner < 4 && match; corner++) {
                    int u = natural[corner * 2];
                    int v = natural[corner * 2 + 1];
                    match = ChunkMesher.orient(orient, u, v, 1, true) == baked[corner * 2]
                            && ChunkMesher.orient(orient, u, v, 1, false) == baked[corner * 2 + 1];
                }
                if (match) {
                    return orient;
                }
            }
            return -1;
        }

        private static boolean unit(float value) {
            return Math.abs(value) < 1.0E-4F || Math.abs(value - 1.0F) < 1.0E-4F;
        }

        private int appearance(int model, int face, int[] faces, int tint) {
            int start = modelQuadStart.get(model);
            int base = start + faces[face * 4];
            int overlay = faces[face * 4 + 2];
            int baseLayer = quadLayer.get(base);
            int baseTint = quadTinted.get(base) != 0 ? tint : NO_TINT;
            int layerOverlay = overlay < 0 ? -1 : quadLayer.get(start + overlay);
            int tintOverlay = overlay < 0 ? NO_TINT : quadTinted.get(start + overlay) != 0 ? tint : NO_TINT;
            String key = baseLayer + "/" + faces[face * 4 + 1] + "/" + baseTint + "/" + layerOverlay + "/"
                    + faces[face * 4 + 3] + "/" + tintOverlay;
            Integer existing = appearanceIndex.get(key);
            if (existing != null) {
                return existing;
            }
            int index = appearanceLayer.size();
            if (index >= ChunkMesher.MAX_APPEARANCES) {
                throw new IllegalStateException("Too many distinct cube faces for the mesher's merge key.");
            }
            appearanceIndex.put(key, index);
            appearanceLayer.add(baseLayer);
            appearanceOrient.add(faces[face * 4 + 1]);
            appearanceTint.add(baseTint);
            overlayLayer.add(layerOverlay);
            overlayOrient.add(Math.max(0, faces[face * 4 + 3]));
            overlayTint.add(tintOverlay);
            return index;
        }
    }

    /** Growable primitive lists for compilation. */
    private static final class Ints {
        private int[] values = new int[256];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }

        void truncate(int newSize) {
            size = newSize;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }

        void set(int index, int value) {
            values[index] = value;
        }

        boolean[] toBooleans() {
            boolean[] result = new boolean[size];
            for (int index = 0; index < size; index++) {
                result[index] = values[index] != 0;
            }
            return result;
        }

        byte[] toBytes() {
            byte[] result = new byte[size];
            for (int index = 0; index < size; index++) {
                result[index] = (byte) values[index];
            }
            return result;
        }

        short[] toShorts() {
            short[] result = new short[size];
            for (int index = 0; index < size; index++) {
                result[index] = (short) values[index];
            }
            return result;
        }
    }

    private static final class Floats {
        private float[] values = new float[1024];
        private int size;

        void addAll(float[] more) {
            if (size + more.length > values.length) {
                values = Arrays.copyOf(values, Math.max(values.length * 2, size + more.length));
            }
            System.arraycopy(more, 0, values, size, more.length);
            size += more.length;
        }

        float[] toFloats() {
            return Arrays.copyOf(values, size);
        }
    }
}
