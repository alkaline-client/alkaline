package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.game.entity.Hanging;
import alkaline.game.entity.MinecartRails;
import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Items;
import alkaline.game.state.PlayerList;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.math.Mat4;
import alkaline.renderer.model.BakedModel;
import alkaline.renderer.model.CuboidModel;
import alkaline.renderer.model.EntityModels;
import alkaline.renderer.model.FirstPersonArm;
import alkaline.renderer.model.FirstPersonItem;
import alkaline.renderer.model.ItemDefinition;
import alkaline.renderer.model.ItemMeshes;
import alkaline.renderer.model.ObjectModels;
import alkaline.renderer.model.ProjectileModels;
import alkaline.renderer.model.ModelPose;
import alkaline.renderer.model.PoseMath;
import alkaline.renderer.texture.EmissiveTextures;
import alkaline.renderer.texture.RandomEntities;
import alkaline.renderer.texture.TextureAnimations;
import alkaline.renderer.vulkan.model.ModelRenderer;
import alkaline.renderer.vulkan.model.QuadRenderer;
import alkaline.renderer.vulkan.resource.RenderTargetFormat;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Draws the server's entities with the model renderer: players (with their skins), the common mobs, armor
 * layers, items held in third person, dropped items, and the local player's first-person hand or held item. Each
 * entity is posed as vanilla's RendererLivingEntity does: interpolated position and rotations, the death tilt,
 * the hurt overlay in the configured hit color, and the light where the entity stands.
 *
 * <p>Textures and item models are created outside frames: drawing a frame only queues what is missing, and
 * {@link #beforeFrame} loads a few queued assets per frame, so an entity appears a frame after it is first seen.
 */
final class EntityView implements AutoCloseable {
    /** Vanilla's hurt overlay policy; armor stays untinted as in 1.8.9 (1.7 tinted it). */
    record HitColor(int argb, boolean tintArmor) {
    }

    /** World lookups for lighting entities and laying their shadows. */
    interface Lighting {
        /** Light as {@code sky << 4 | block}. */
        int light(int x, int y, int z);

        /** The block state ({@code id << 4 | meta}), air outside the loaded world. */
        int state(int x, int y, int z);
    }

    private static final int LEATHER = 0xA06540;
    private static final String[] VILLAGERS = {"entity/villager/farmer", "entity/villager/librarian",
            "entity/villager/priest", "entity/villager/smith", "entity/villager/butcher", "entity/villager/villager"};
    /** Armor layer textures by material (leather, chainmail, iron, diamond, gold) and layer 1 or 2. */
    private static final String[][] ARMOR = new String[5][2];
    private static final String[] LEATHER_OVERLAY = {"models/armor/leather_layer_1_overlay",
            "models/armor/leather_layer_2_overlay"};

    static {
        String[] materials = {"leather", "chainmail", "iron", "diamond", "gold"};
        for (int material = 0; material < materials.length; material++) {
            for (int layer = 0; layer < 2; layer++) {
                ARMOR[material][layer] = "models/armor/" + materials[material] + "_layer_" + (layer + 1);
            }
        }
    }
    private static final int UPLOADS_PER_FRAME = 4;

    private final VulkanRenderer renderer;
    private final AssetSource assets;
    private final ModelRenderer models;
    private final Skins skins;
    private final Map<EntityModels.Kind, ModelRenderer.GpuModel> gpu = new EnumMap<>(EntityModels.Kind.class);
    private final Map<EntityModels.Kind, ModelPose> poses = new EnumMap<>(EntityModels.Kind.class);
    private final ModelPose armorOuter;
    private final ModelPose armorInner;
    private final ModelPose handPose;
    private final Map<String, VulkanTexture> textures = new HashMap<>();
    private final Set<String> failedTextures = new HashSet<>();
    private final ArrayDeque<String> pendingTextures = new ArrayDeque<>();
    /**
     * OptiFine's random entities and emissive textures: the packs' rules, the entity whose textures are being
     * picked (null outside a living entity), and each loaded texture's glowing {@code <name><suffix>.png}.
     */
    private final RandomEntities randomEntities;
    private final String emissiveSuffix;
    private final java.util.IdentityHashMap<VulkanTexture, VulkanTexture> emissive = new java.util.IdentityHashMap<>();
    private Entity randomEntity;
    private final RandomSubject subject = new RandomSubject();
    private int moonPhase;
    private long worldTime;
    private int weather;
    private java.util.function.IntBinaryOperator biomes = (x, z) -> 1;
    /**
     * OptiFine's texture animations over entity textures: per animated texture, every look composed so far (each
     * uploaded once, in {@link #beforeFrame}) and the one the current tick shows. Drawing only picks.
     */
    private final TextureAnimations animations;
    private final Map<String, Animated> animated = new HashMap<>();
    /** Distinct looks one animated texture keeps; past it, new looks wait and the last one stays on screen. */
    private static final int MAX_ANIMATION_STATES = 256;
    /** New looks composed and uploaded per frame (each upload waits for the GPU queue). */
    private static final int ANIMATION_UPLOADS_PER_FRAME = 2;
    private static final TextureOptions ANIMATION_OPTIONS = new TextureOptions(ImageSampling.NEAREST, false, false, 1);

    private static final class Animated {
        final TextureAnimations.Target target;
        final RgbaImage base;
        final VulkanTexture glow;
        final long[] states = new long[MAX_ANIMATION_STATES];
        final VulkanTexture[] looks = new VulkanTexture[MAX_ANIMATION_STATES];
        int count;
        VulkanTexture current;

        Animated(TextureAnimations.Target target, RgbaImage base, VulkanTexture glow) {
            this.target = target;
            this.base = base;
            this.glow = glow;
        }
    }

    /** This entity's light at full block light (EmissiveTextures.beginRenderEmissive: block 240, sky kept). */
    private int emissiveLight;
    private final Map<String, VulkanTexture> skinTextures = new HashMap<>();
    /** Item models drawn while not yet uploaded; uploaded in {@link #beforeFrame}. */
    private final Set<String> pendingItems = new java.util.LinkedHashSet<>();
    private final ItemCatalog catalog;
    private final Map<String, GpuItem> gpuItems = new HashMap<>();
    private int gpuGeneration;
    private final ModelRenderer.GpuModel arrowModel;
    private final ModelRenderer.GpuModel chestModel;
    private final ModelRenderer.GpuModel largeChestModel;
    private final ModelPose chestPose;
    private final ModelPose largeChestPose;
    private final boolean christmas;
    private Collection<ChestTracker.Chest> chests = java.util.List.of();
    private alkaline.game.player.CollisionShapes.States chestWorld;
    /** Players with a fishing hook out (their rod draws cast), refreshed each frame. */
    private final Set<Integer> hookOwners = new HashSet<>();
    private float viewYaw;
    private float viewPitch;
    private final EntityModels.Motion motion = new EntityModels.Motion();
    private MobAnimator anim;
    private final MobSelector.Choice choice = new MobSelector.Choice();
    private final float[] root = new float[16];
    private final float[] part = new float[16];
    private final float[] item = new float[16];
    private final float[] matrices = new float[CuboidModel.MAX_PARTS * PoseMath.FLOATS_PER_PART];
    private final float[] light0 = new float[3];
    private final float[] light1 = new float[3];
    private VulkanTexture glint;
    private boolean glintLoaded;
    private final GlintStyles glints;
    /**
     * Render.renderShadow's disc ({@code misc/shadow.png}), whether the Entity Shadows option is on, the dimension
     * whose light table fades them, the sky light the time and weather take away (World.skylightSubtracted), and
     * the viewer's feet (RenderManager.viewerPos), which the distance fade measures from.
     */
    private VulkanTexture shadow;
    private boolean shadowLoaded;
    private boolean shadows;
    private alkaline.renderer.world.Dimension dimension = alkaline.renderer.world.Dimension.OVERWORLD;
    private int skylightSubtracted;
    private double viewerX;
    private double viewerY;
    private double viewerZ;
    /** The camera and light lookups of the frame being drawn, for objects that read the world around them. */
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private Lighting lighting;
    private int[] lightmapColors;
    /** The entity's light as {@code sky << 4 | block}, for objects that adjust it (experience orbs). */
    private int packedLight;
    private final ModelRenderer.GpuModel[] orbModels = new ModelRenderer.GpuModel[11];
    /** A white texel for geometry drawn by vertex color alone (lightning). */
    private VulkanTexture white;
    /** Painting tiles by {@code art << 4 | u << 2 | v}; the ones asked for while drawing upload before the next frame. */
    private final Map<Integer, ModelRenderer.GpuModel> paintingTiles = new HashMap<>();
    private final Set<Integer> pendingTiles = new java.util.LinkedHashSet<>();
    /** The lightning bolts on the GPU by entity id: the seed their geometry came from, and whether this frame drew them. */
    private final Map<Integer, Bolt> bolts = new HashMap<>();
    private final Map<Integer, Long> pendingBolts = new HashMap<>();
    private final float[] stand = new float[16];
    private final Map<Integer, ItemStack> stateStacks = new HashMap<>();
    /** The cart tile {@link #displayTile} found: block id, metadata and the offset it sits down in the cart. */
    private int tileId;
    private int tileMeta;
    private int tileOffset;

    private static final class Bolt {
        long seed;
        ModelRenderer.GpuModel model;
        boolean seen;
    }
    /** Name tags, and the game they read; null viewer draws none. */
    private final QuadRenderer quads;
    private final NameTagRenderer labels;
    private final MapView mapView;
    private alkaline.game.world.MapStore mapStore;
    private NameTags.Viewer viewer;

    /** An item model on the GPU: its mesh (for the display transforms) and one model and texture per layer. */
    private record GpuItem(ItemMeshes.Mesh mesh, ModelRenderer.GpuModel[] models, VulkanTexture[] textures) {
        ItemDefinition definition() {
            return mesh.definition();
        }
    }

    private alkaline.renderer.world.CustomColors customColors = alkaline.renderer.world.CustomColors.NONE;

    EntityView(VulkanRenderer renderer, AssetSource assets, RenderTargetFormat target, Skins skins,
               ItemCatalog catalog) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.assets = assets;
        this.skins = Objects.requireNonNull(skins, "skins");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        randomEntities = RandomEntities.load(assets);
        emissiveSuffix = EmissiveTextures.suffix(assets);
        animations = TextureAnimations.load(assets);
        models = new ModelRenderer(renderer, target);
        quads = new QuadRenderer(renderer, target);
        labels = new NameTagRenderer(renderer, assets, quads);
        mapView = new MapView(renderer, assets);
        glints = new GlintStyles(renderer, catalog, models::prepare);
        arrowModel = models.upload(ProjectileModels.arrow());
        CuboidModel chest = alkaline.renderer.model.ChestModels.chest(false);
        CuboidModel largeChest = alkaline.renderer.model.ChestModels.chest(true);
        chestModel = models.upload(chest);
        largeChestModel = models.upload(largeChest);
        chestPose = new ModelPose(chest);
        largeChestPose = new ModelPose(largeChest);
        // TileEntityChestRenderer: Christmas chests from December 24 to 26.
        java.time.MonthDay today = java.time.MonthDay.now();
        christmas = today.getMonthValue() == 12 && today.getDayOfMonth() >= 24 && today.getDayOfMonth() <= 26;
        for (EntityModels.Kind kind : EntityModels.Kind.values()) {
            CuboidModel model = EntityModels.model(kind);
            gpu.put(kind, models.upload(model));
            poses.put(kind, new ModelPose(model));
        }
        for (int icon = 0; icon < orbModels.length; icon++) {
            orbModels[icon] = models.upload(ObjectModels.experienceOrb(icon));
        }
        white = renderer.createTexture(new RgbaImage(1, 1, new byte[]{-1, -1, -1, -1}),
                new TextureOptions(ImageSampling.NEAREST, false, false, 1));
        armorOuter = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_OUTER));
        armorInner = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_INNER));
        handPose = new ModelPose(EntityModels.model(EntityModels.Kind.PLAYER));
    }

    /** The packs' custom colors (OptiFine sheep.<dye> fleece, xporb and map.<name> colors). */
    void customColors(alkaline.renderer.world.CustomColors colors) {
        customColors = Objects.requireNonNull(colors, "colors");
        int[] map = new int[alkaline.renderer.world.CustomColors.MAP_COLOR_COUNT];
        boolean any = false;
        for (int index = 0; index < map.length; index++) {
            map[index] = colors.map(index);
            any |= map[index] >= 0;
        }
        mapView.colors(any ? map : null);
    }

    /**
     * The world state OptiFine's random entity rules read: moon phase, world time, weather ({@link
     * RandomEntities#CLEAR}, RAIN or THUNDER) and the biome of a column (for spawn biomes). Call once per frame.
     */
    void world(int moonPhase, long worldTime, int weather, java.util.function.IntBinaryOperator biomes) {
        this.moonPhase = moonPhase;
        this.worldTime = worldTime;
        this.weather = weather;
        this.biomes = Objects.requireNonNull(biomes, "biomes");
    }

    /** RandomEntity's view of the entity being drawn. */
    private final class RandomSubject implements RandomEntities.Subject {
        @Override
        public int randomId() {
            UUID uuid = randomEntity.uuid;
            if (uuid != null) {
                return (int) (uuid.getLeastSignificantBits() & 0x7FFFFFFFL);
            }
            // Vanilla gives a mob a random client-side UUID; a mix of the entity id is as random and stays put.
            long mixed = randomEntity.id * 0x9E3779B97F4A7C15L;
            mixed = (mixed ^ mixed >>> 32) * 0xD6E8FEB86659FD93L;
            return (int) ((mixed ^ mixed >>> 32) & 0x7FFFFFFFL);
        }

        @Override
        public int spawnBiome() {
            return biomes.applyAsInt(randomEntity.spawnX, randomEntity.spawnZ);
        }

        @Override
        public int spawnY() {
            return randomEntity.spawnY;
        }

        @Override
        public int health() {
            return randomEntity.living() ? (int) randomEntity.health() : 0;
        }

        @Override
        public int maxHealth() {
            return randomEntity.living() ? (int) randomEntity.maxHealth : 0;
        }

        @Override
        public String name() {
            String name = randomEntity.metadata.getString(2, "");
            return name.isEmpty() ? null : name;
        }

        @Override
        public int profession() {
            return randomEntity.category == Entity.Category.MOB && randomEntity.type == EntityTypes.VILLAGER
                    ? randomEntity.metadata.getInt(16, 0) : -1;
        }

        @Override
        public int collar() {
            if (randomEntity.category != Entity.Category.MOB || randomEntity.type != EntityTypes.WOLF) {
                return Integer.MIN_VALUE;
            }
            // EntityWolf: tamed is flag 4 of byte 16; the collar is EnumDyeColor.byDyeDamage(byte 20).
            return (randomEntity.metadata.getByte(16, 0) & 4) == 0 ? -1
                    : 15 - (randomEntity.metadata.getByte(20, 14) & 15);
        }

        @Override
        public int baby() {
            return randomEntity.living() ? EntityTypes.child(randomEntity) ? 1 : 0 : -1;
        }
    }

    // ---- assets -------------------------------------------------------------------------------------------

    /** Loads a few queued textures and item models; call outside frames. */
    void beforeFrame() {
        uploadSkins();
        labels.beforeFrame();
        if (mapStore != null) {
            mapView.beforeFrame(mapStore);
        }
        glints.beforeFrame();
        if (!glintLoaded) {
            glintLoaded = true;
            glint = load("assets/minecraft/textures/misc/enchanted_item_glint.png",
                    new TextureOptions(ImageSampling.LINEAR, false, true, 1));
        }
        if (!crystalBeamLoaded) {
            // Its v coordinate scrolls past the image, so it repeats, unlike the other entity textures.
            crystalBeamLoaded = true;
            crystalBeam = load("assets/minecraft/textures/entity/endercrystal/endercrystal_beam.png",
                    new TextureOptions(ImageSampling.NEAREST, false, true, 1));
        }
        if (!shadowLoaded) {
            shadowLoaded = true;
            // shadow.png.mcmeta clamps the texture: the quads cover whole blocks, past the disc's edge.
            shadow = load("assets/minecraft/textures/misc/shadow.png",
                    new TextureOptions(ImageSampling.NEAREST, false, false, 1));
        }
        for (int upload = 0; upload < UPLOADS_PER_FRAME && !pendingTextures.isEmpty(); upload++) {
            String path = pendingTextures.poll();
            // Custom armor textures name full asset paths; the rest are short paths under textures/.
            TextureOptions nearest = new TextureOptions(ImageSampling.NEAREST, false, false, 1);
            String asset = path.startsWith("assets/") ? path : "assets/minecraft/textures/" + path + ".png";
            TextureAnimations.Target animation = path.indexOf('+') >= 0 ? null : animations.target(asset);
            RgbaImage animationBase = animation == null ? null : image(asset);
            VulkanTexture texture = path.indexOf('+') >= 0 ? layered(path, nearest)
                    : animationBase != null ? renderer.createTexture(animationBase, nearest) : load(asset, nearest);
            if (texture == null) {
                failedTextures.add(path);
            } else {
                textures.put(path, texture);
                String glow = path.indexOf('+') >= 0 || assets == null ? null : EmissiveTextures.emissivePath(assets,
                        path.startsWith("assets/") ? path : "assets/minecraft/textures/" + path + ".png", emissiveSuffix);
                VulkanTexture glowing = glow == null ? null : load(glow, nearest);
                if (glowing != null) {
                    emissive.put(texture, glowing);
                }
                if (animationBase != null) {
                    animated.put(path, new Animated(animation, animationBase, glowing));
                }
            }
        }
        uploadObjects();
        animate(ANIMATION_OPTIONS);
        if (catalog.generation() != gpuGeneration) {
            // The assets changed (resource packs): drop every item uploaded from the old ones.
            closeItems();
            gpuGeneration = catalog.generation();
        }
        catalog.build();
        // Uploads happen here, outside the frame: items asked for while drawing the last one.
        if (!pendingItems.isEmpty()) {
            for (java.util.Iterator<String> names = pendingItems.iterator(); names.hasNext(); ) {
                String name = names.next();
                ItemMeshes.Mesh mesh = catalog.mesh(name);
                if (mesh != null) {
                    gpuItems.put(name, upload(mesh));
                    names.remove();
                } else if (catalog.failed(name)) {
                    names.remove();
                }
            }
        }
    }

    /** Uploads the painting tiles and lightning bolts drawing asked for, and drops the bolts that are gone. */
    private void uploadObjects() {
        for (Integer key : pendingTiles) {
            Hanging.Art art = Hanging.art(key >> 4);
            paintingTiles.put(key, models.upload(ObjectModels.paintingTile(art.u(), art.v(), art.width(), art.height(),
                    key >> 2 & 3, key & 3)));
        }
        pendingTiles.clear();
        for (java.util.Iterator<Map.Entry<Integer, Bolt>> each = bolts.entrySet().iterator(); each.hasNext(); ) {
            Bolt bolt = each.next().getValue();
            if (!bolt.seen) {
                models.release(bolt.model);
                each.remove();
            } else {
                bolt.seen = false;
            }
        }
        for (Map.Entry<Integer, Long> entry : pendingBolts.entrySet()) {
            Bolt bolt = bolts.computeIfAbsent(entry.getKey(), id -> new Bolt());
            if (bolt.model != null) {
                models.release(bolt.model);
            }
            bolt.seed = entry.getValue();
            bolt.model = models.uploadAdditive(ObjectModels.lightning(bolt.seed));
            bolt.seen = true;
        }
        pendingBolts.clear();
    }

    /** Advances the packs' texture animations one game tick; their new looks upload in the next beforeFrame. */
    void tick() {
        animations.tick();
    }

    /**
     * Picks each animated texture's look for the current tick, composing and uploading looks not seen before
     * (a few per frame). Outside frames only.
     */
    private void animate(TextureOptions options) {
        int uploads = 0;
        for (Animated texture : animated.values()) {
            long state = texture.target.state();
            VulkanTexture look = null;
            for (int index = 0; index < texture.count; index++) {
                if (texture.states[index] == state) {
                    look = texture.looks[index];
                    break;
                }
            }
            if (look == null && texture.count < MAX_ANIMATION_STATES && uploads < ANIMATION_UPLOADS_PER_FRAME) {
                look = renderer.createTexture(texture.target.compose(texture.base), options);
                texture.states[texture.count] = state;
                texture.looks[texture.count++] = look;
                if (texture.glow != null) {
                    emissive.put(look, texture.glow);
                }
                uploads++;
            }
            if (look != null) {
                texture.current = look;
            }
        }
    }

    private VulkanTexture load(String path, TextureOptions options) {
        RgbaImage image = image(path);
        return image == null ? null : renderer.createTexture(image, options);
    }

    /** A texture made of several {@code +}-joined paths drawn over one another (horse coat, marking, armor). */
    private VulkanTexture layered(String key, TextureOptions options) {
        String[] paths = key.split("\\+");
        RgbaImage[] images = new RgbaImage[paths.length];
        for (int index = 0; index < paths.length; index++) {
            images[index] = image("assets/minecraft/textures/" + paths[index] + ".png");
        }
        return images[0] == null ? null : renderer.createTexture(ImageLayers.composite(images), options);
    }

    private RgbaImage image(String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : ImageDecoder.decode(input);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * An entity texture by path under {@code textures/} (without {@code .png}), or null (queued) until loaded.
     * Keyed by the short path, which callers pass as constants, so a frame builds no strings.
     */
    private VulkanTexture texture(String path) {
        if (randomEntity != null && path != null) {
            path = randomEntities.texture(path, subject, moonPhase, worldTime, weather);
        }
        VulkanTexture texture = textures.get(path);
        if (texture != null && !animated.isEmpty()) {
            Animated animation = animated.get(path);
            if (animation != null && animation.current != null) {
                return animation.current;
            }
        }
        if (texture == null && !failedTextures.contains(path) && !pendingTextures.contains(path)) {
            pendingTextures.add(path);
        }
        return texture;
    }

    /** An item model on the GPU, or null (queued for the next {@link #beforeFrame}) until it is uploaded. */
    private GpuItem gpuItem(String name) {
        if (name == null) {
            return null;
        }
        GpuItem loaded = gpuItems.get(name);
        if (loaded == null && pendingItems.add(name)) {
            catalog.mesh(name);
        }
        return loaded;
    }

    private GpuItem upload(ItemMeshes.Mesh mesh) {
        int count = mesh.layers().size();
        ModelRenderer.GpuModel[] uploaded = new ModelRenderer.GpuModel[count];
        VulkanTexture[] textures = new VulkanTexture[count];
        Map<RgbaImage, VulkanTexture> shared = new java.util.IdentityHashMap<>();
        for (int index = 0; index < count; index++) {
            ItemMeshes.Layer layer = mesh.layers().get(index);
            uploaded[index] = models.upload(layer.model());
            textures[index] = shared.computeIfAbsent(layer.texture(), image -> renderer.createTexture(image,
                    new TextureOptions(ImageSampling.NEAREST, false, false, 1)));
        }
        return new GpuItem(mesh, uploaded, textures);
    }

    /** Draws an item model's layers, each tinted as the stack asks, at {@code matrix} (item model space). */
    private void submitItem(GpuItem item, ItemStack stack, float[] matrix, int light) {
        submitItem(item, stack, matrix, light, 0);
    }

    /** {@link #submitItem(GpuItem, ItemStack, float[], int)} with an overlay color blended over it (primed TNT's flash). */
    private void submitItem(GpuItem item, ItemStack stack, float[] matrix, int light, int overlay) {
        int[] tints = catalog.tints(stack, item.mesh());
        for (int index = 0; index < item.models().length; index++) {
            int slot = item.mesh().layers().get(index).tintIndex();
            int tint = slot >= 0 && slot < tints.length ? tints[slot] : 0xFFFFFFFF;
            models.submit(item.models()[index], item.textures()[index], matrix, tint, overlay, light,
                    glints.item(catalog.glintStyle(stack)));
        }
    }

    private void closeItems() {
        Set<VulkanTexture> closed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (GpuItem loaded : gpuItems.values()) {
            for (ModelRenderer.GpuModel model : loaded.models()) {
                models.release(model);
            }
            for (VulkanTexture texture : loaded.textures()) {
                if (closed.add(texture)) {
                    texture.close();
                }
            }
        }
        gpuItems.clear();
        pendingItems.clear();
    }

    /**
     * A player's skin texture, vanilla's default for the UUID until (or unless) theirs is loaded; sets
     * {@link #slim}. The textures property is decoded once per distinct value.
     */
    private VulkanTexture skin(UUID uuid, PlayerList.Player listed) {
        String property = listed == null ? null : listed.textures();
        Skins.Reference reference = null;
        if (property != null) {
            reference = references.get(property);
            if (reference == null && !references.containsKey(property)) {
                reference = Skins.reference(property);
                references.put(property, reference);
            }
        }
        if (reference != null) {
            VulkanTexture texture = skinTextures.get(reference.hash());
            if (texture != null) {
                slim = reference.slim();
                return texture;
            }
            Skins.Skin skin = skins.skin(reference);
            if (skin != null) {
                skinUploads.put(reference.hash(), skin);
            }
        }
        slim = Skins.defaultSlim(uuid);
        return texture(slim ? "entity/alex" : "entity/steve");
    }

    /** The model of the skin {@link #skin} returned last. */
    private boolean slim;
    private final Map<String, Skins.Reference> references = new HashMap<>();

    private final Map<String, Skins.Skin> skinUploads = new HashMap<>();

    /** Uploads skins downloaded since the last frame. */
    private void uploadSkins() {
        for (Map.Entry<String, Skins.Skin> entry : skinUploads.entrySet()) {
            skinTextures.put(entry.getKey(), renderer.createTexture(entry.getValue().image(),
                    new TextureOptions(ImageSampling.NEAREST, false, false, 1)));
        }
        skinUploads.clear();
    }

    // ---- world entities -----------------------------------------------------------------------------------

    /**
     * Draws every entity into the current pass (after opaque terrain).
     *
     * @param lightmap 256 lightmap texels (0xRRGGBB, sky * 16 + block)
     * @param fog      the terrain's fog (see WorldRenderer.fog)
     */
    void render(VulkanFrame frame, Collection<Entity> entities, PlayerList players, Lighting lighting,
                int[] lightmap, float[] fog, HitColor hit, float partialTick, double cameraX, double cameraY,
                double cameraZ, float[] viewProjection, float cameraYaw, float cameraPitch, Entity self) {
        viewYaw = cameraYaw;
        viewPitch = cameraPitch;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.lighting = lighting;
        lightmapColors = lightmap;
        hookOwners.clear();
        if (System.getProperty("alkaline.debug.objects") != null && System.nanoTime() / 1_000_000_000L % 2 == 0) {
            StringBuilder list = new StringBuilder("entities:");
            for (Entity entity : entities) {
                list.append(' ').append(entity.category).append(entity.type).append('@')
                        .append((int) entity.x).append(',').append((int) entity.y).append(',').append((int) entity.z);
            }
            System.err.println(list);
        }
        for (Entity entity : entities) {
            if (entity.category == Entity.Category.OBJECT && entity.type == EntityTypes.OBJECT_FISHING_HOOK) {
                hookOwners.add(entity.objectData);
            }
        }
        if (shadows && shadow != null) {
            renderShadows(frame, entities, self, partialTick, viewProjection);
        }
        models.setGlint(glint, System.currentTimeMillis());
        models.setFog(fog);
        models.setLighting(0.2f, 1.0f, -0.7f, -0.2f, 1.0f, 0.7f, 0.4f);
        models.begin(frame, viewProjection);
        selfId = self == null ? Integer.MIN_VALUE : self.id;
        for (Entity entity : entities) {
            renderEntity(entity, players, lighting, lightmap, hit, partialTick, cameraX, cameraY, cameraZ);
        }
        if (self != null) {
            renderEntity(self, players, lighting, lightmap, hit, partialTick, cameraX, cameraY, cameraZ);
        }
        if (chestWorld != null) {
            for (ChestTracker.Chest chest : chests) {
                renderChest(chest, lighting, lightmap, partialTick, cameraX, cameraY, cameraZ);
            }
        }
        models.flush();
        drawHealingBeams(frame, viewProjection);
        drawFramedMaps(frame, viewProjection);
        renderLabels(frame, entities, viewProjection, cameraYaw, cameraPitch, partialTick, cameraX, cameraY, cameraZ);
    }

    /**
     * The Entity Shadows option and what shadows read this frame: the dimension (its light table), the sky light
     * the time and weather take away (World.skylightSubtracted), and the viewer's feet. Call before {@link #render}.
     */
    void shadows(boolean enabled, alkaline.renderer.world.Dimension dimension, int skylightSubtracted,
                 double viewerX, double viewerY, double viewerZ) {
        shadows = enabled;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.skylightSubtracted = skylightSubtracted;
        this.viewerX = viewerX;
        this.viewerY = viewerY;
        this.viewerZ = viewerZ;
    }

    /** Whether the shadow draw of this frame has begun, and whether it blends in gamma space. */
    private boolean shadowsBegun;
    private boolean shadowsGamma;

    /**
     * Render.doRenderShadowAndFire's shadows, all in one draw before the models. Vanilla draws each right after its
     * entity; the picture is the same, since a shadow writes no depth and lies on the ground under the models.
     */
    private void renderShadows(VulkanFrame frame, Collection<Entity> entities, Entity self, float partialTick,
                               float[] viewProjection) {
        shadowsBegun = false;
        for (Entity entity : entities) {
            shadow(frame, viewProjection, entity, partialTick);
        }
        if (self != null) {
            shadow(frame, viewProjection, self, partialTick);
        }
        if (shadowsBegun) {
            quads.flush();
            if (shadowsGamma) {
                frame.endGammaSpace();
            }
        }
    }

    /**
     * Render.renderShadow: the shadow disc laid over the top faces of the full blocks under the entity, at each one
     * fading with the height above it and its light (skipped at level 3 or darker), and as a whole with the
     * squared distance from the viewer's feet (gone at 16 blocks).
     */
    private void shadow(VulkanFrame frame, float[] viewProjection, Entity entity, float partialTick) {
        float size = shadowSize(entity);
        if (size <= 0f || entity.invisible()) {
            return;
        }
        double dx = entity.x - viewerX;
        double dy = entity.y - viewerY;
        double dz = entity.z - viewerZ;
        // RenderEntityItem and RenderXPOrb set shadowOpaque to 0.75; every other renderer keeps 1.
        boolean faint = entity.category == Entity.Category.OBJECT
                && (entity.type == EntityTypes.OBJECT_ITEM || entity.type == EntityTypes.OBJECT_XP_ORB);
        float opacity = (float) ((1.0 - (dx * dx + dy * dy + dz * dz) / 256.0) * (faint ? 0.75f : 1f));
        if (opacity <= 0f) {
            return;
        }
        double x = entity.renderX(partialTick);
        double y = entity.renderY(partialTick);
        double z = entity.renderZ(partialTick);
        int minX = (int) Math.floor(x - size);
        int maxX = (int) Math.floor(x + size);
        int minZ = (int) Math.floor(z - size);
        int maxZ = (int) Math.floor(z + size);
        int maxY = (int) Math.floor(y);
        for (int blockY = (int) Math.floor(y - size); blockY <= maxY; blockY++) {
            float top = (float) (blockY + 0.015625 - cameraY);
            for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                for (int blockX = minX; blockX <= maxX; blockX++) {
                    if (!castsOn(lighting.state(blockX, blockY - 1, blockZ))) {
                        continue;
                    }
                    int level = shadowLight(lighting, skylightSubtracted, blockX, blockY, blockZ);
                    if (level <= 3) {
                        continue;
                    }
                    double alpha = (opacity - (y - blockY) / 2.0) * 0.5 * dimension.levelBrightness(level);
                    if (alpha < 0.0) {
                        continue;
                    }
                    if (!shadowsBegun) {
                        beginShadows(frame, viewProjection);
                    }
                    int argb = (int) ((float) Math.min(alpha, 1.0) * 255f) << 24 | 0xFFFFFF;
                    float x0 = (float) (blockX - cameraX);
                    float x1 = (float) (blockX + 1 - cameraX);
                    float z0 = (float) (blockZ - cameraZ);
                    float z1 = (float) (blockZ + 1 - cameraZ);
                    float u0 = (float) ((x - blockX) / 2.0 / size + 0.5);
                    float u1 = (float) ((x - blockX - 1) / 2.0 / size + 0.5);
                    float v0 = (float) ((z - blockZ) / 2.0 / size + 0.5);
                    float v1 = (float) ((z - blockZ - 1) / 2.0 / size + 0.5);
                    quads.quad(x0, top, z0, u0, v0, x0, top, z1, u0, v1, x1, top, z1, u1, v1, x1, top, z0, u1, v0,
                            argb);
                }
            }
        }
    }

    private void beginShadows(VulkanFrame frame, float[] viewProjection) {
        shadowsBegun = true;
        // Vanilla darkens the encoded framebuffer; blending linear values would leave the shadows paler.
        shadowsGamma = frame.beginGammaSpace();
        quads.begin(frame, viewProjection);
        quads.start(shadowsGamma ? QuadRenderer.Mode.GAMMA_ALPHA : QuadRenderer.Mode.ALPHA, shadow);
    }

    /** A shadow falls on a block with a render type that is a full cube (not air, barriers or slabs). */
    static boolean castsOn(int state) {
        int id = state >> 4;
        return id < Blocks189.COUNT && Blocks189.fullCube(id) && Blocks189.render(id) != Blocks189.Render.NONE;
    }

    /** World.getLightFromNeighbors: slabs, stairs and farmland take their brightest upper or side neighbor. */
    static int shadowLight(Lighting world, int subtracted, int x, int y, int z) {
        int id = world.state(x, y, z) >> 4;
        if (id < Blocks189.COUNT && Blocks189.neighborBrightness(id)) {
            int sides = Math.max(Math.max(lightLevel(world, subtracted, x + 1, y, z),
                    lightLevel(world, subtracted, x - 1, y, z)), Math.max(lightLevel(world, subtracted, x, y, z + 1),
                    lightLevel(world, subtracted, x, y, z - 1)));
            return Math.max(lightLevel(world, subtracted, x, y + 1, z), sides);
        }
        return lightLevel(world, subtracted, x, y, z);
    }

    /** Chunk.getLightSubtracted: the sky light less the time's darkening, or the block light if brighter. */
    private static int lightLevel(Lighting world, int subtracted, int x, int y, int z) {
        if (y < 0) {
            return 0;
        }
        int packed = world.light(x, Math.min(y, 255), z);
        return Math.max((packed >> 4) - subtracted, packed & 15);
    }

    /**
     * Each renderer's shadowSize, halved for children as Render.renderShadow does for any EntityLiving
     * (getRenderSizeModifier is 1 for all of them); 0 casts none.
     */
    static float shadowSize(Entity entity) {
        return switch (entity.category) {
            case PLAYER -> 0.5f;
            case OBJECT -> switch (entity.type) {
                case EntityTypes.OBJECT_ITEM, EntityTypes.OBJECT_XP_ORB -> 0.15f;
                case EntityTypes.OBJECT_BOAT, EntityTypes.OBJECT_MINECART, EntityTypes.OBJECT_TNT,
                     EntityTypes.OBJECT_FALLING_BLOCK, EntityTypes.OBJECT_ENDER_CRYSTAL -> 0.5f;
                default -> 0f; // arrows, projectiles, paintings, frames, armor stands, hooks, lightning
            };
            case MOB -> {
                float size = switch (entity.type) {
                    case EntityTypes.SPIDER, EntityTypes.WITHER -> 1f;
                    case EntityTypes.GIANT -> 3f; // RenderGiantZombie: 0.5 times its scale of 6
                    case EntityTypes.SLIME -> 0.25f * Math.max(1, entity.metadata.getByte(16, 1));
                    case EntityTypes.HORSE -> 0.75f;
                    case EntityTypes.CAVE_SPIDER, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.COW,
                         EntityTypes.MOOSHROOM, EntityTypes.SQUID -> 0.7f;
                    case EntityTypes.OCELOT -> 0.4f;
                    case EntityTypes.SILVERFISH, EntityTypes.ENDERMITE, EntityTypes.CHICKEN, EntityTypes.RABBIT -> 0.3f;
                    case EntityTypes.MAGMA_CUBE, EntityTypes.BAT -> 0.25f;
                    // RenderVillager.preRenderCallback: a quarter for a child, then halved again.
                    case EntityTypes.VILLAGER -> EntityTypes.child(entity) ? 0.25f : 0.5f;
                    default -> 0.5f;
                };
                yield EntityTypes.child(entity) ? size * 0.5f : size;
            }
        };
    }

    /** The maps the server sent, for filled maps in hand and in frames. */
    void maps(alkaline.game.world.MapStore store) {
        this.mapStore = store;
    }

    /** The game the name tags read (who is on whose team, what the crosshair is on). */
    void viewer(NameTags.Viewer viewer) {
        this.viewer = viewer;
    }

    /**
     * RendererLivingEntity.renderName for each entity that carries a tag: the tag above the head, the below-name
     * score under it for players, drawn after the models so their depth hides the full-brightness pass.
     */
    private void renderLabels(VulkanFrame frame, Collection<Entity> entities, float[] viewProjection, float yaw,
                              float pitch, float partialTick, double cameraX, double cameraY, double cameraZ) {
        if (viewer == null || !labels.ready()) {
            return;
        }
        boolean begun = false;
        for (Entity entity : entities) {
            if (entity.category == Entity.Category.OBJECT && entity.type != EntityTypes.OBJECT_ARMOR_STAND) {
                continue;
            }
            NameTags.Tag tag = NameTags.of(entity, viewer);
            if (tag == null) {
                continue;
            }
            if (!begun) {
                labels.begin(frame, viewProjection, yaw, pitch);
                begun = true;
            }
            float x = (float) (entity.renderX(partialTick) - cameraX);
            float y = (float) (entity.renderY(partialTick) - cameraY);
            float z = (float) (entity.renderZ(partialTick) - cameraZ);
            if (entity.category == Entity.Category.PLAYER && entity.sneaking()) {
                y -= 0.125f; // RenderPlayer lowers other sneaking players, and their tags with them
            }
            float top = y + NameTags.labelHeight(entity);
            if (tag.score() != null) {
                labels.label(tag.score(), x, top, z, false);
                top += NameTags.SCORE_LIFT;
            }
            labels.label(tag.text(), x, top, z, tag.sneaking());
        }
        if (begun) {
            labels.end();
        }
    }

    /** The chests to draw this frame and the world their neighbors are read from. */
    void chests(Collection<ChestTracker.Chest> chests, alkaline.game.player.CollisionShapes.States world) {
        this.chests = chests;
        this.chestWorld = world;
    }

    /**
     * TileEntityChestRenderer / TileEntityEnderChestRenderer: within 64 blocks; a double chest is drawn once, by
     * the half with no chest of its kind on its west or north, turned by the block's facing; the lid eases open.
     */
    private void renderChest(ChestTracker.Chest chest, Lighting lighting, int[] lightmap, float partialTick,
                             double cameraX, double cameraY, double cameraZ) {
        double dx = chest.x + 0.5 - cameraX;
        double dy = chest.y + 0.5 - cameraY;
        double dz = chest.z + 0.5 - cameraZ;
        if (dx * dx + dy * dy + dz * dz >= 4096.0) {
            return;
        }
        int block = chest.block;
        boolean ender = block == ChestTracker.ENDER_CHEST;
        boolean xPos = false;
        boolean zPos = false;
        if (!ender) {
            if (chestWorld.state(chest.x - 1, chest.y, chest.z) >> 4 == block
                    || chestWorld.state(chest.x, chest.y, chest.z - 1) >> 4 == block) {
                return;
            }
            xPos = chestWorld.state(chest.x + 1, chest.y, chest.z) >> 4 == block;
            zPos = chestWorld.state(chest.x, chest.y, chest.z + 1) >> 4 == block;
        }
        boolean large = xPos || zPos;
        String path = ender ? "entity/chest/ender"
                : christmas ? (large ? "entity/chest/christmas_double" : "entity/chest/christmas")
                : block == ChestTracker.TRAPPED_CHEST ? (large ? "entity/chest/trapped_double" : "entity/chest/trapped")
                : large ? "entity/chest/normal_double" : "entity/chest/normal";
        VulkanTexture texture = texture(path);
        if (texture == null) {
            return;
        }
        int meta = chestWorld.state(chest.x, chest.y, chest.z) & 15;
        int turn = switch (meta) {
            case 2 -> 180;
            case 4 -> 90;
            case 5 -> -90;
            default -> 0;
        };
        Mat4.identity(root);
        Mat4.translate(root, (float) (chest.x - cameraX), (float) (chest.y + 1 - cameraY), (float) (chest.z + 1 - cameraZ),
                root);
        Mat4.scale(root, 1f, -1f, -1f, root);
        Mat4.translate(root, 0.5f, 0.5f, 0.5f, root);
        if (meta == 2 && xPos) {
            Mat4.translate(root, 1f, 0f, 0f, root);
        }
        if (meta == 5 && zPos) {
            Mat4.translate(root, 0f, 0f, -1f, root);
        }
        Mat4.rotate(root, (float) Math.toRadians(turn), 0f, 1f, 0f, root);
        Mat4.translate(root, -0.5f, -0.5f, -0.5f, root);
        float open = 1f - chest.lid(partialTick);
        open = 1f - open * open * open;
        float angle = -(open * (float) Math.PI / 2f);
        ModelPose pose = large ? largeChestPose : chestPose;
        pose.reset();
        pose.rotation(alkaline.renderer.model.ChestModels.LID, angle, 0f, 0f);
        pose.rotation(alkaline.renderer.model.ChestModels.KNOB, angle, 0f, 0f);
        int packed = lighting.light(chest.x, chest.y, chest.z);
        int light = lightmap[(packed >> 4 & 15) * 16 + (packed & 15)];
        models.submit(large ? largeChestModel : chestModel, texture, pose, root, 0xFFFFFFFF, 0, light);
    }

    /** The local player's entity id while it is drawn (third person), for RenderPlayer's self exceptions. */
    private int selfId = Integer.MIN_VALUE;

    private void renderEntity(Entity entity, PlayerList players, Lighting lighting, int[] lightmap, HitColor hit,
                              float partialTick, double cameraX, double cameraY, double cameraZ) {
        {
            // An invisible armor stand still shows what it wears and holds.
            if (entity.invisible() && entity.category == Entity.Category.OBJECT
                    && entity.type != EntityTypes.OBJECT_ARMOR_STAND) {
                return;
            }
            double x = entity.renderX(partialTick) - cameraX;
            double y = entity.renderY(partialTick) - cameraY;
            double z = entity.renderZ(partialTick) - cameraZ;
            if (x * x + y * y + z * z > 128.0 * 128.0) {
                return;
            }
            float height = EntityTypes.height(entity);
            int packed = lighting.light((int) Math.floor(entity.renderX(partialTick)),
                    (int) Math.floor(entity.renderY(partialTick) + height * 0.85),
                    (int) Math.floor(entity.renderZ(partialTick)));
            int light = lightmap[(packed >> 4 & 15) * 16 + (packed & 15)];
            packedLight = packed;
            emissiveLight = lightmap[(packed >> 4 & 15) * 16 + 15];
            if (entity.category == Entity.Category.OBJECT) {
                renderObject(entity, (float) x, (float) y, (float) z, light, partialTick);
            } else {
                randomEntity = randomEntities.isEmpty() ? null : entity;
                try {
                    renderLiving(entity, players, (float) x, (float) y, (float) z, light, hit, partialTick);
                } finally {
                    randomEntity = null;
                }
            }
        }
    }

    private void renderLiving(Entity entity, PlayerList players, float x, float y, float z, int light, HitColor hit,
                              float partialTick) {
        EntityModels.Kind kind;
        VulkanTexture texture;
        float scale = 1f;
        boolean biped = false;
        boolean zombieArms = false;
        String extraTexture = null;
        int extraTint = 0xFFFFFFFF;
        EntityModels.Kind extraKind = null;
        motion.reset();
        float deathRotation = 90f;
        switch (entity.category == Entity.Category.PLAYER ? -1 : entity.type) {
            case -1 -> {
                texture = skin(entity.uuid, players.get(entity.uuid));
                kind = slim ? EntityModels.Kind.PLAYER_SLIM : EntityModels.Kind.PLAYER;
                scale = 0.9375f;
                biped = true;
                if (entity.sneaking() && entity.id != selfId) {
                    y -= 0.125f; // RenderPlayer lowers other sneaking players (not the local one)
                }
            }
            case EntityTypes.ZOMBIE, EntityTypes.GIANT -> {
                boolean villager = entity.metadata.getByte(13, 0) == 1;
                kind = villager ? EntityModels.Kind.ZOMBIE_VILLAGER : EntityModels.Kind.BIPED;
                texture = texture(villager ? "entity/zombie/zombie_villager" : "entity/zombie/zombie");
                biped = zombieArms = true;
                scale = entity.type == EntityTypes.GIANT ? 6f : 1f;
            }
            case EntityTypes.PIG_ZOMBIE -> {
                kind = EntityModels.Kind.BIPED_TALL;
                texture = texture("entity/zombie_pigman");
                biped = zombieArms = true;
            }
            case EntityTypes.SKELETON -> {
                kind = EntityModels.Kind.SKELETON;
                boolean wither = entity.metadata.getByte(13, 0) == 1;
                texture = texture(wither ? "entity/skeleton/wither_skeleton" : "entity/skeleton/skeleton");
                scale = wither ? 1.2f : 1f;
                biped = true;
            }
            case EntityTypes.CREEPER -> {
                kind = EntityModels.Kind.CREEPER;
                texture = texture("entity/creeper/creeper");
            }
            case EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER -> {
                kind = EntityModels.Kind.SPIDER;
                boolean cave = entity.type == EntityTypes.CAVE_SPIDER;
                texture = texture(cave ? "entity/spider/cave_spider" : "entity/spider/spider");
                scale = cave ? 0.7f : 1f;
            }
            case EntityTypes.ENDERMAN -> {
                kind = EntityModels.Kind.ENDERMAN;
                texture = texture("entity/enderman/enderman");
                motion.carrying = entity.metadata.getShort(16, 0) != 0;
                motion.screaming = entity.metadata.getByte(18, 0) > 0;
            }
            case EntityTypes.PIG -> {
                kind = EntityModels.Kind.PIG;
                texture = texture("entity/pig/pig");
            }
            case EntityTypes.COW, EntityTypes.MOOSHROOM -> {
                kind = EntityModels.Kind.COW;
                texture = texture(entity.type == EntityTypes.COW ? "entity/cow/cow" : "entity/cow/mooshroom");
                if (entity.type == EntityTypes.MOOSHROOM) {
                    extraKind = EntityModels.Kind.MOOSHROOM_MUSHROOMS;
                    extraTexture = "blocks/mushroom_red";
                }
            }
            case EntityTypes.SHEEP -> {
                kind = EntityModels.Kind.SHEEP;
                texture = texture("entity/sheep/sheep");
                int fleece = entity.metadata.getByte(16, 0);
                if ((fleece & 16) == 0) {
                    extraKind = EntityModels.Kind.SHEEP_WOOL;
                    extraTexture = "entity/sheep/sheep_fur";
                    int custom = customColors.sheep(fleece & 15);
                    extraTint = custom >= 0 ? 0xFF000000 | custom : fleeceColor(fleece & 15);
                }
            }
            case EntityTypes.CHICKEN -> {
                kind = EntityModels.Kind.CHICKEN;
                texture = texture("entity/chicken");
            }
            case EntityTypes.WOLF -> {
                kind = EntityModels.Kind.WOLF;
                int flags = entity.metadata.getByte(16, 0);
                motion.angry = (flags & 2) != 0;
                motion.tame = (flags & 4) != 0;
                motion.health = entity.metadata.getFloat(18, 20f);
                texture = texture(motion.tame ? "entity/wolf/wolf_tame"
                        : motion.angry ? "entity/wolf/wolf_angry" : "entity/wolf/wolf");
            }
            case EntityTypes.VILLAGER -> {
                kind = EntityModels.Kind.VILLAGER;
                int profession = entity.metadata.getInt(16, 0);
                texture = texture(VILLAGERS[profession >= 0 && profession < 5 ? profession : 5]);
                scale = EntityTypes.child(entity) ? 0.46875f : 0.9375f; // RenderVillager.preRenderCallback
            }
            case EntityTypes.IRON_GOLEM -> {
                kind = EntityModels.Kind.IRON_GOLEM;
                texture = texture("entity/iron_golem");
            }
            case EntityTypes.SLIME -> {
                kind = EntityModels.Kind.SLIME;
                texture = texture("entity/slime/slime");
                scale = Math.max(1, entity.metadata.getByte(16, 1));
                extraKind = EntityModels.Kind.SLIME_OUTER;
                extraTexture = "entity/slime/slime";
            }
            case EntityTypes.BLAZE -> {
                kind = EntityModels.Kind.BLAZE;
                texture = texture("entity/blaze");
            }
            case EntityTypes.SQUID -> {
                kind = EntityModels.Kind.SQUID;
                texture = texture("entity/squid");
            }
            case EntityTypes.GHAST -> {
                kind = EntityModels.Kind.GHAST;
                texture = texture(entity.metadata.getByte(16, 0) != 0 ? "entity/ghast/ghast_shooting"
                        : "entity/ghast/ghast");
                scale = 4.5f;
            }
            default -> {
                if (!MobSelector.select(entity, partialTick, x, y, z, motion, choice)) {
                    return; // Mobs without a model yet are not drawn.
                }
                kind = choice.kind;
                texture = texture(choice.texture);
                scale = choice.scale;
                y += choice.lift;
                biped = choice.biped;
                zombieArms = choice.zombieArms;
                deathRotation = choice.deathRotation;
            }
        }
        if (texture == null) {
            return;
        }
        float bodyYaw = lerpAngle(entity.previousBodyYaw, entity.bodyYaw, partialTick);
        float headYaw = lerpAngle(entity.previousHeadYaw, entity.headYaw, partialTick);
        motion.headYaw = Entity.wrap(headYaw - bodyYaw);
        motion.headPitch = entity.previousPitch + (entity.pitch - entity.previousPitch) * partialTick;
        motion.limbSwingAmount = Math.min(1f, entity.previousLimbSwingAmount
                + (entity.limbSwingAmount - entity.previousLimbSwingAmount) * partialTick);
        motion.limbSwing = entity.limbSwing - entity.limbSwingAmount * (1f - partialTick);
        motion.ageInTicks = entity.ticksExisted + partialTick;
        motion.swingProgress = entity.swing(partialTick);
        motion.sneaking = entity.sneaking();
        motion.onGround = entity.onGround;
        motion.heldItem = entity.equipment[0] != null ? 1 : 0;
        motion.riding = entity.vehicle >= 0;
        MobAnimator.State animState = anim == null ? null : anim.state(entity);
        if (animState != null) {
            anim.apply(entity, animState, partialTick, motion);
        }
        motion.entityId = entity.id;
        motion.ticksExisted = entity.ticksExisted;
        if (entity.category == Entity.Category.PLAYER && entity.usingItem() && entity.equipment[0] != null) {
            // RenderPlayer.setModelVisibilities: blocking raises the sword, drawing a bow aims both arms.
            int heldId = entity.equipment[0].id();
            if (heldId == 267 || heldId == 268 || heldId == 272 || heldId == 276 || heldId == 283) {
                motion.heldItem = 3;
            } else if (heldId == 261) {
                motion.aimedBow = true;
            }
        }
        if (entity.dead) {
            motion.limbSwingAmount = 0f;
        }
        ModelPose pose = poses.get(kind);
        EntityModels.animate(kind, motion, pose);
        if (zombieArms) {
            EntityModels.zombieArms(motion, pose);
        }
        float scaleY = scale;
        if (kind == EntityModels.Kind.SLIME || kind == EntityModels.Kind.MAGMA_CUBE) {
            // RenderSlime.preRenderCallback: squashed by the squish factor, size-scaled.
            float h = 1f / (motion.squish / (scale * 0.5f + 1f) + 1f);
            scaleY = scale / h;
            scale *= h;
        }
        livingRoot(x, y, z, bodyYaw, entity, partialTick, scale, scaleY, biped && motion.sneaking, deathRotation);
        if (kind == EntityModels.Kind.ENDER_DRAGON) {
            dragonRoot(x, y, z);
        }
        int overlay = entity.hurtTime > 0 || entity.deathTime > 0 ? hit.argb() : 0;
        // Villagers draw whole at half scale (set above), not as a separate head and body.
        boolean child = EntityTypes.child(entity) && kind != EntityModels.Kind.VILLAGER;
        // RendererLivingEntity.renderModel: an invisible body is skipped; its armor and held item still draw.
        boolean invisible = entity.invisible();
        if (!invisible && child && !submitChild(kind, texture, pose, light, overlay)) {
            return;
        }
        if (!child && !invisible) {
            models.submit(gpu.get(kind), texture, pose, root, 0xFFFFFFFF, overlay, light);
            submitEmissive(gpu.get(kind), texture, pose, 0xFFFFFFFF, overlay);
        }
        if (extraKind != null) {
            VulkanTexture extra = texture(extraTexture);
            ModelPose extraPose = poses.get(extraKind);
            if (extra != null && !child && !invisible) {
                extraPose.copyFrom(pose, extraKind == EntityModels.Kind.MOOSHROOM_MUSHROOMS ? 1
                        : Math.min(pose.model().partCount(), extraPose.model().partCount()));
                models.submit(gpu.get(extraKind), extra, extraPose, root, extraTint, overlay, light);
                submitEmissive(gpu.get(extraKind), extra, extraPose, extraTint, overlay);
            }
        }
        if (kind == EntityModels.Kind.GUARDIAN && animState != null && !invisible) {
            renderGuardianBeam(entity, animState, x, y, z, partialTick);
        }
        if (kind == EntityModels.Kind.ENDER_DRAGON && animState != null && animState.healingCrystal >= 0) {
            queueHealingBeam(entity, anim.entity(animState.healingCrystal), x, y, z, light, partialTick);
        }
        if (kind == EntityModels.Kind.WITCH && entity.equipment[0] != null) {
            renderWitchHeld(entity.equipment[0], pose, light);
        }
        if (kind == EntityModels.Kind.SNOW_GOLEM && !invisible) {
            renderPumpkinHead(pose, light);
        }
        if (biped && !child) {
            renderArmor(entity, pose, light, hit.tintArmor() ? overlay : 0, false);
            renderHeld(entity.equipment[0], pose, motion.sneaking, light,
                    entity.equipment[0] != null && entity.equipment[0].id() == 261 && entity.usingItem()
                            ? entity.usingTicks : -1, !hookOwners.isEmpty() && hookOwners.contains(entity.id));
        }
    }

    /**
     * RendererLivingEntity.renderModel's emissive pass: the same model again with the texture's glowing image at
     * full block light, when it has one. Drawn right after the base so its equal depth passes over it.
     */
    private void submitEmissive(ModelRenderer.GpuModel model, VulkanTexture texture, ModelPose pose, int tint,
                                int overlay) {
        VulkanTexture glowing = emissive.get(texture);
        if (glowing != null) {
            models.submit(model, glowing, pose, root, tint, overlay, emissiveLight);
        }
    }

    /** The per-mob animation state the renderer reads (set each frame by the game view). */
    void animation(MobAnimator animator) {
        anim = animator;
    }

    /** Items LayerHeldItemWitch holds like a tool: upright in the hand (ItemTool, ItemSword, rods, sticks). */
    private static boolean fullThreeD(int id) {
        return id >= 256 && id <= 258 || id >= 267 && id <= 279 || id >= 283 && id <= 286 || id >= 290 && id <= 294
                || id == 280 || id == 346 || id == 369 || id == 398;
    }

    /**
     * LayerHeldItemWitch: the held item at the tip of the nose, lifted with it, in the layer's per-kind pose (a bow,
     * a tool, or the default potion pose) and then the item's own third-person display.
     */
    private void renderWitchHeld(ItemStack stack, ModelPose pose, int light) {
        GpuItem held = gpuItem(catalog.model(stack, -1, false));
        if (held == null) {
            return;
        }
        PoseMath.resolve(pose, root, matrices);
        int base = 1 * PoseMath.FLOATS_PER_PART; // the nose is part 1
        Mat4.identity(part);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                part[column * 4 + row] = matrices[base + row * 4 + column];
            }
        }
        Mat4.translate(part, -0.0625f, 0.53125f, 0.21875f, part);
        int id = stack.id();
        if (id == 261) {
            Mat4.translate(part, 0f, 0.125f, -0.125f, part);
            Mat4.rotate(part, (float) Math.toRadians(-45), 0f, 1f, 0f, part);
            Mat4.scale(part, 0.625f, -0.625f, 0.625f, part);
            Mat4.rotate(part, (float) Math.toRadians(-100), 1f, 0f, 0f, part);
            Mat4.rotate(part, (float) Math.toRadians(-20), 0f, 1f, 0f, part);
        } else if (fullThreeD(id)) {
            if (id == 346 || id == 398) {
                Mat4.rotate(part, (float) Math.PI, 0f, 0f, 1f, part);
                Mat4.translate(part, 0f, -0.0625f, 0f, part);
            }
            Mat4.translate(part, 0f, 0.1875f, 0f, part); // RenderWitch.transformHeldFull3DItemLayer
            Mat4.translate(part, 0.0625f, -0.125f, 0f, part);
            Mat4.scale(part, 0.625f, -0.625f, 0.625f, part);
        } else {
            Mat4.translate(part, 0.1875f, 0.1875f, 0f, part);
            Mat4.scale(part, 0.875f, 0.875f, 0.875f, part);
            Mat4.rotate(part, (float) Math.toRadians(-20), 0f, 0f, 1f, part);
            Mat4.rotate(part, (float) Math.toRadians(-60), 1f, 0f, 0f, part);
            Mat4.rotate(part, (float) Math.toRadians(-30), 0f, 0f, 1f, part);
        }
        Mat4.rotate(part, (float) Math.toRadians(-15), 1f, 0f, 0f, part);
        Mat4.rotate(part, (float) Math.toRadians(40), 0f, 0f, 1f, part);
        Mat4.scale(part, 2f, 2f, 2f, part); // as for any held model
        display(part, held.definition().thirdPerson());
        Mat4.scale(part, 0.5f, 0.5f, 0.5f, part);
        Mat4.translate(part, -0.5f, -0.5f, -0.5f, part);
        submitItem(held, stack, part, light);
    }

    private final float[] beamBase = new float[16];
    private final float[] beamSegment = new float[16];
    /** The healing beams queued this frame (RenderDragon.drawRechargeRay), drawn once the models are flushed. */
    private static final int MAX_HEALING_BEAMS = 4;
    private static final int HEALING_BEAM_FLOATS = 19;
    private final float[] healingBeams = new float[MAX_HEALING_BEAMS * HEALING_BEAM_FLOATS];
    private final int[] healingBeamLight = new int[MAX_HEALING_BEAMS];
    private int healingBeamCount;
    private VulkanTexture crystalBeam;
    private boolean crystalBeamLoaded;

    /**
     * RenderDragon.drawRechargeRay's placement: from two blocks above the dragon toward its healing crystal's bob
     * (a block under the crystal's position), turned so the cone runs along local z. Queued here; the eight-sided cone
     * is written by {@link #drawHealingBeams} after the models, since its vertex colors need the quad path.
     */
    private void queueHealingBeam(Entity dragon, Entity crystal, float x, float y, float z, int light,
                                  float partialTick) {
        if (crystal == null || healingBeamCount == MAX_HEALING_BEAMS) {
            return;
        }
        float time = MobAnimator.crystalRotation(crystal) + partialTick;
        float bob = (float) Math.sin(time * 0.2f) / 2f + 0.5f;
        bob = (bob * bob + bob) * 0.2f;
        float dx = (float) (crystal.x - dragon.renderX(partialTick));
        float dy = (float) (bob + crystal.y - 1.0 - dragon.renderY(partialTick));
        float dz = (float) (crystal.z - dragon.renderZ(partialTick));
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float ticks = dragon.ticksExisted + partialTick;
        int base = healingBeamCount * HEALING_BEAM_FLOATS;
        Mat4.identity(beamBase);
        Mat4.translate(beamBase, x, y + 2f, z, beamBase);
        Mat4.rotate(beamBase, (float) -Math.atan2(dz, dx) - (float) (Math.PI / 2), 0f, 1f, 0f, beamBase);
        Mat4.rotate(beamBase, (float) -Math.atan2(flat, dy) - (float) (Math.PI / 2), 1f, 0f, 0f, beamBase);
        System.arraycopy(beamBase, 0, healingBeams, base, 16);
        healingBeams[base + 16] = length;
        healingBeams[base + 17] = -ticks * 0.01f;
        healingBeams[base + 18] = length / 32f - ticks * 0.01f;
        healingBeamLight[healingBeamCount++] = light;
    }

    /**
     * The queued healing beams: a triangle strip of eight sides, 0.15 blocks across at the dragon and 0.75 at the
     * crystal, black at the dragon fading to the dragon's light at the crystal, its texture scrolling toward the
     * dragon. Drawn unculled, without the standard lights.
     */
    private void drawHealingBeams(VulkanFrame frame, float[] viewProjection) {
        if (healingBeamCount == 0 || crystalBeam == null) {
            healingBeamCount = 0;
            return;
        }
        quads.begin(frame, viewProjection);
        quads.start(QuadRenderer.Mode.ALPHA, crystalBeam);
        for (int beam = 0; beam < healingBeamCount; beam++) {
            int base = beam * HEALING_BEAM_FLOATS;
            float length = healingBeams[base + 16];
            float far = healingBeams[base + 17];
            float near = healingBeams[base + 18];
            int lit = 0xFF000000 | healingBeamLight[beam];
            for (int side = 0; side < 8; side++) {
                float angle0 = side * (float) Math.PI * 2f / 8f;
                float angle1 = (side + 1) % 8 * (float) Math.PI * 2f / 8f;
                float s0 = (float) Math.sin(angle0) * 0.75f;
                float t0 = (float) Math.cos(angle0) * 0.75f;
                float s1 = (float) Math.sin(angle1) * 0.75f;
                float t1 = (float) Math.cos(angle1) * 0.75f;
                float u0 = side / 8f;
                float u1 = (side + 1) % 8 / 8f;
                quads.quad(beamX(base, s0 * 0.2f, t0 * 0.2f, 0f), beamY(base, s0 * 0.2f, t0 * 0.2f, 0f),
                        beamZ(base, s0 * 0.2f, t0 * 0.2f, 0f), u0, near, 0xFF000000,
                        beamX(base, s0, t0, length), beamY(base, s0, t0, length), beamZ(base, s0, t0, length), u0,
                        far, lit,
                        beamX(base, s1, t1, length), beamY(base, s1, t1, length), beamZ(base, s1, t1, length), u1,
                        far, lit,
                        beamX(base, s1 * 0.2f, t1 * 0.2f, 0f), beamY(base, s1 * 0.2f, t1 * 0.2f, 0f),
                        beamZ(base, s1 * 0.2f, t1 * 0.2f, 0f), u1, near, 0xFF000000);
            }
        }
        quads.flush();
        healingBeamCount = 0;
    }

    private float beamX(int base, float x, float y, float z) {
        return healingBeams[base] * x + healingBeams[base + 4] * y + healingBeams[base + 8] * z + healingBeams[base + 12];
    }

    private float beamY(int base, float x, float y, float z) {
        return healingBeams[base + 1] * x + healingBeams[base + 5] * y + healingBeams[base + 9] * z
                + healingBeams[base + 13];
    }

    private float beamZ(int base, float x, float y, float z) {
        return healingBeams[base + 2] * x + healingBeams[base + 6] * y + healingBeams[base + 10] * z
                + healingBeams[base + 14];
    }

    /**
     * RenderGuardian's beam from the guardian's eye to its target's middle: crossed planes turning about the beam,
     * tinted redder as the charge builds. It is drawn as a chain of 0.4-block stretches (one texture height each)
     * slid along the beam, which scrolls the texture as vanilla's v offset does.
     */
    private void renderGuardianBeam(Entity entity, MobAnimator.State state, float x, float y, float z,
                                    float partialTick) {
        Entity target = state.target == 0 ? null : anim.entity(state.target);
        VulkanTexture beam = target == null ? null : texture("entity/guardian_beam");
        if (beam == null) {
            return;
        }
        boolean elder = (entity.metadata.getInt(16, 0) & 4) != 0;
        double cameraX = entity.renderX(partialTick) - x;
        double cameraY = entity.renderY(partialTick) - y;
        double cameraZ = entity.renderZ(partialTick) - z;
        float eye = EntityTypes.height(entity) * 0.5f;
        float dx = (float) (target.renderX(partialTick) - cameraX) - x;
        float dy = (float) (target.renderY(partialTick) + EntityTypes.height(target) * 0.5f - cameraY) - (y + eye);
        float dz = (float) (target.renderZ(partialTick) - cameraZ) - z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-3f) {
            return;
        }
        float charge = MobAnimator.beamCharge(state, elder, partialTick);
        float squared = charge * charge;
        int tint = 0xFF000000 | 64 + (int) (squared * 240f) << 16 | 32 + (int) (squared * 192f) << 8
                | 128 - (int) (squared * 64f);
        float time = entity.ticksExisted + partialTick;
        float scroll = time * 0.5f % 1f;
        Mat4.identity(beamBase);
        Mat4.translate(beamBase, x, y + eye, z, beamBase);
        Mat4.rotate(beamBase, (float) (Math.PI / 2 - Math.atan2(dz / length, dx / length)), 0f, 1f, 0f, beamBase);
        Mat4.rotate(beamBase, (float) Math.acos(dy / length), 1f, 0f, 0f, beamBase);
        Mat4.rotate(beamBase, 0.075f * time, 0f, 1f, 0f, beamBase);
        ModelPose pose = poses.get(EntityModels.Kind.GUARDIAN_BEAM);
        for (int stretch = 0; stretch < 256; stretch++) {
            float start = (stretch - scroll) * 0.4f;
            if (start >= length) {
                break;
            }
            System.arraycopy(beamBase, 0, beamSegment, 0, 16);
            Mat4.translate(beamSegment, 0f, start, 0f, beamSegment);
            Mat4.scale(beamSegment, 0.2f, 0.2f, 0.2f, beamSegment);
            models.submit(gpu.get(EntityModels.Kind.GUARDIAN_BEAM), beam, pose, beamSegment, tint, 0, 0xFFFFFF);
        }
    }

    /**
     * RenderDragon.rotateCorpse replaces the yaw turn: heading from the history's yaw, a pitch from its height
     * change, and a block forward; the usual flip follows.
     */
    private void dragonRoot(float x, float y, float z) {
        float heading = motion.movement[7 * 2];
        float climb = motion.movement[5 * 2 + 1] - motion.movement[10 * 2 + 1];
        Mat4.identity(root);
        Mat4.translate(root, x, y, z, root);
        Mat4.rotate(root, (float) Math.toRadians(-heading), 0f, 1f, 0f, root);
        Mat4.rotate(root, (float) Math.toRadians(climb * 10f), 1f, 0f, 0f, root);
        Mat4.translate(root, 0f, 0f, 1f, root);
        Mat4.scale(root, -1f, -1f, 1f, root);
        Mat4.translate(root, 0f, -1.5078125f, 0f, root);
    }

    /**
     * RendererLivingEntity.doRender's transforms: to the feet, turned to the body yaw, tilted by death, flipped
     * from model space (y down) with the renderer's scale, and lifted by vanilla's 1.5078125.
     */
    private void livingRoot(float x, float y, float z, float bodyYaw, Entity entity, float partialTick, float scale,
                            float scaleY, boolean sneakingBiped, float deathRotation) {
        Mat4.identity(root);
        Mat4.translate(root, x, y, z, root);
        Mat4.rotate(root, (float) Math.toRadians(180.0 - bodyYaw), 0f, 1f, 0f, root);
        if (entity.deathTime > 0) {
            float tilt = (float) Math.sqrt(Math.max(0f, (entity.deathTime + partialTick - 1f) / 20f * 1.6f));
            Mat4.rotate(root, (float) Math.toRadians(Math.min(1f, tilt) * deathRotation), 0f, 0f, 1f, root);
        }
        Mat4.scale(root, -scale, -scaleY, scale, root);
        Mat4.translate(root, 0f, -1.5078125f, 0f, root);
        if (sneakingBiped) {
            Mat4.translate(root, 0f, 0.2f, 0f, root); // ModelBiped.render while sneaking
        }
    }

    private final float[] foalRoot = new float[16];

    /**
     * ModelHorse.render for a foal (size 0.5): the legs, the trunk and the head each draw under their own scale and
     * lift, the head lowering further while it grazes.
     */
    private void submitFoal(VulkanTexture texture, ModelPose pose, int light, int overlay) {
        float size = 0.5f;
        float grazing = motion.grazing;
        System.arraycopy(root, 0, foalRoot, 0, 16);
        int parts = pose.model().partCount();
        for (int group = 0; group < 3; group++) {
            int from = group == 0 ? 0 : group == 1 ? 12 : 18;
            int to = group == 0 ? 12 : group == 1 ? 18 : 25;
            System.arraycopy(foalRoot, 0, root, 0, 16);
            if (group == 0) {
                Mat4.scale(root, size, 0.5f + size * 0.5f, size, root);
                Mat4.translate(root, 0f, 0.95f * (1f - size), 0f, root);
            } else if (group == 1) {
                Mat4.scale(root, size, size, size, root);
                Mat4.translate(root, 0f, 1.35f * (1f - size), 0f, root);
            } else {
                float head = 0.5f + size * size * 0.5f;
                Mat4.scale(root, head, head, head, root);
                if (grazing <= 0f) {
                    Mat4.translate(root, 0f, 1.35f * (1f - size), 0f, root);
                } else {
                    Mat4.translate(root, 0f, 0.9f * (1f - size) * grazing + 1.35f * (1f - size) * (1f - grazing),
                            0.15f * (1f - size) * grazing, root);
                }
            }
            for (int part = 0; part < parts; part++) {
                foalVisible[part] = pose.visible(part);
                pose.visible(part, foalVisible[part] && part >= from && part < to);
            }
            models.submit(gpu.get(EntityModels.Kind.HORSE), texture, pose, root, 0xFFFFFFFF, overlay, light);
            for (int part = 0; part < parts; part++) {
                pose.visible(part, foalVisible[part]);
            }
        }
        System.arraycopy(foalRoot, 0, root, 0, 16);
    }

    private final boolean[] foalVisible = new boolean[CuboidModel.MAX_PARTS];

    /**
     * ModelBiped / ModelQuadruped child rendering: the head drawn larger relative to a half-size body. Returns
     * false for kinds without a child layout (then nothing is drawn rather than an adult-sized child).
     */
    private boolean submitChild(EntityModels.Kind kind, VulkanTexture texture, ModelPose pose, int light, int overlay) {
        return submitChild(kind, texture, pose, light, overlay, 0xFFFFFFFF, null, 0f);
    }

    /** {@link #submitChild(EntityModels.Kind, VulkanTexture, ModelPose, int, int)} with a tint and a glint (child armor). */
    private boolean submitChild(EntityModels.Kind kind, VulkanTexture texture, ModelPose pose, int light, int overlay,
                                int tint, alkaline.renderer.vulkan.model.GlintEffect glint, float glintTime) {
        if (kind == EntityModels.Kind.HORSE) {
            submitFoal(texture, pose, light, overlay);
            return true;
        }
        float headScale;
        float headLift;
        float headForward;
        int[] headParts;
        switch (kind) {
            case BIPED, BIPED_TALL, ARMOR_STAND, ARMOR_OUTER, ARMOR_INNER -> {
                headScale = 0.75f;
                headLift = 1f;
                headForward = 0f;
                headParts = new int[]{EntityModels.HEAD, EntityModels.HAT};
            }
            case PIG -> {
                headScale = 1f;
                headLift = 4f / 16f;
                headForward = 4f / 16f;
                headParts = new int[]{0};
            }
            case COW, SHEEP -> {
                headScale = 1f;
                headLift = 8f / 16f;
                headForward = (kind == EntityModels.Kind.COW ? 6f : 4f) / 16f;
                headParts = new int[]{0};
            }
            case CHICKEN, WOLF -> {
                headScale = 1f;
                headLift = 5f / 16f;
                headForward = 2f / 16f;
                headParts = new int[]{0};
            }
            case RABBIT -> { // ModelRabbit.render: head, ears and nose lowered, the rest halved
                headScale = 1f;
                headLift = 5f / 16f;
                headForward = 2f / 16f;
                headParts = new int[]{7, 8, 9, 11};
            }
            case OCELOT -> { // ModelOcelot.render
                headScale = 0.75f;
                headLift = 10f / 16f;
                headForward = 4f / 16f;
                headParts = new int[]{0};
            }
            default -> {
                return false;
            }
        }
        float[] saved = root.clone();
        boolean[] visible = new boolean[pose.model().partCount()];
        boolean[] head = new boolean[visible.length];
        for (int index = 0; index < visible.length; index++) {
            visible[index] = pose.visible(index);
        }
        for (int part : headParts) {
            head[part] = true;
        }
        // Head first (whatever of it is visible).
        for (int index = 0; index < visible.length; index++) {
            pose.visible(index, visible[index] && head[index]);
        }
        Mat4.scale(root, headScale, headScale, headScale, root);
        Mat4.translate(root, 0f, headLift, headForward, root);
        models.submit(gpu.get(kind), texture, pose, root, tint, overlay, light, glint, glintTime);
        // Then the half-size body.
        System.arraycopy(saved, 0, root, 0, 16);
        for (int index = 0; index < visible.length; index++) {
            pose.visible(index, visible[index] && !head[index]);
        }
        Mat4.scale(root, 0.5f, 0.5f, 0.5f, root);
        Mat4.translate(root, 0f, 1.5f, 0f, root);
        models.submit(gpu.get(kind), texture, pose, root, tint, overlay, light, glint, glintTime);
        System.arraycopy(saved, 0, root, 0, 16);
        for (int index = 0; index < visible.length; index++) {
            pose.visible(index, visible[index]);
        }
        return true;
    }

    /**
     * LayerBipedArmor: boots, leggings, chestplate and helmet on the inflated biped, posed as the wearer. An armor
     * stand has no biped to copy: with a null {@code wearer} the armor models take the stand's own rotations, and a
     * small stand ({@code child}) draws them in the child layout.
     */
    private void renderArmor(Entity entity, ModelPose wearer, int light, int overlay, boolean child) {
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack stack = entity.equipment[slot];
            if (stack == null || !Items.armor(stack.id())) {
                continue;
            }
            int type = Items.armorType(stack.id());
            boolean leggings = type == 2;
            ModelPose pose = leggings ? armorInner : armorOuter;
            EntityModels.Kind kind = leggings ? EntityModels.Kind.ARMOR_INNER : EntityModels.Kind.ARMOR_OUTER;
            pose.reset();
            if (wearer == null) {
                ObjectModels.poseArmorStand(pose, false, rotations(entity, 11, ObjectModels.DEFAULT_HEAD),
                        rotations(entity, 12, ObjectModels.DEFAULT_BODY),
                        rotations(entity, 13, ObjectModels.DEFAULT_LEFT_ARM),
                        rotations(entity, 14, ObjectModels.DEFAULT_RIGHT_ARM),
                        rotations(entity, 15, ObjectModels.DEFAULT_LEFT_LEG),
                        rotations(entity, 16, ObjectModels.DEFAULT_RIGHT_LEG), true, true, entity.yaw);
            } else {
                pose.copyFrom(wearer, EntityModels.BIPED_PARTS);
            }
            pose.visibleAll(false);
            switch (type) {
                case 0 -> {
                    pose.visible(EntityModels.HEAD, true);
                    pose.visible(EntityModels.HAT, true);
                }
                case 1 -> {
                    pose.visible(EntityModels.BODY, true);
                    pose.visible(EntityModels.RIGHT_ARM, true);
                    pose.visible(EntityModels.LEFT_ARM, true);
                }
                case 2 -> {
                    pose.visible(EntityModels.BODY, true);
                    pose.visible(EntityModels.RIGHT_LEG, true);
                    pose.visible(EntityModels.LEFT_LEG, true);
                }
                default -> {
                    pose.visible(EntityModels.RIGHT_LEG, true);
                    pose.visible(EntityModels.LEFT_LEG, true);
                }
            }
            int material = (stack.id() - 298) / 4;
            VulkanTexture texture = armorTexture(stack, ARMOR[material][leggings ? 1 : 0]);
            if (texture == null) {
                continue;
            }
            // LayerArmorBase: enchanted armor shimmers with the glint.
            alkaline.renderer.vulkan.model.GlintEffect shimmer = glints.armor(catalog.glintStyle(stack));
            float age = wearer == null ? entity.ticksExisted : motion.ageInTicks;
            if (material == 0) {
                int color = stack.leatherColor();
                int dye = 0xFF000000 | (color < 0 ? LEATHER : color);
                if (child) {
                    submitChild(kind, texture, pose, light, overlay, dye, shimmer, age);
                } else {
                    models.submit(gpu.get(kind), texture, pose, root, dye, overlay, light, shimmer, age);
                }
                VulkanTexture leatherOverlay = armorTexture(stack, LEATHER_OVERLAY[leggings ? 1 : 0]);
                if (leatherOverlay != null) {
                    if (child) {
                        submitChild(kind, leatherOverlay, pose, light, overlay);
                    } else {
                        models.submit(gpu.get(kind), leatherOverlay, pose, root, 0xFFFFFFFF, overlay, light);
                    }
                }
            } else if (child) {
                submitChild(kind, texture, pose, light, overlay, 0xFFFFFFFF, shimmer, age);
            } else {
                models.submit(gpu.get(kind), texture, pose, root, 0xFFFFFFFF, overlay, light, shimmer, age);
            }
        }
    }

    /** An armor texture, or a resource pack's custom armor texture for this piece (OptiFine CIT). */
    private VulkanTexture armorTexture(ItemStack stack, String path) {
        String custom = catalog.armorTexture(stack, path);
        VulkanTexture texture = custom == null ? null : texture(custom);
        return texture != null || (custom != null && !failedTextures.contains(custom)) ? texture : texture(path);
    }

    /**
     * LayerHeldItem: the item at the right hand, in its third-person display transform; ItemRenderer.renderItem
     * doubles block items. {@code bowPull} picks the bow's pulling frame (-1 when not drawing); {@code cast} draws a
     * fishing rod with its line out.
     */
    private void renderHeld(ItemStack stack, ModelPose pose, boolean sneaking, int light, int bowPull, boolean cast) {
        renderHeld(stack, pose, sneaking, light, bowPull, cast, false);
    }

    /** {@link #renderHeld(ItemStack, ModelPose, boolean, int, int, boolean)}; a small armor stand holds it in the child pose. */
    private void renderHeld(ItemStack stack, ModelPose pose, boolean sneaking, int light, int bowPull, boolean cast,
                            boolean child) {
        GpuItem held = gpuItem(catalog.model(stack, bowPull, cast));
        if (held == null) {
            return;
        }
        if (child) {
            // LayerHeldItem: the child's hand sits lower and tilted, at half size.
            System.arraycopy(root, 0, foalRoot, 0, 16);
            Mat4.translate(root, 0f, 0.625f, 0f, root);
            Mat4.rotate(root, (float) Math.toRadians(20f), 1f, 0f, 0f, root);
            Mat4.scale(root, 0.5f, 0.5f, 0.5f, root);
        }
        PoseMath.resolve(pose, root, matrices);
        if (child) {
            System.arraycopy(foalRoot, 0, root, 0, 16);
        }
        int base = EntityModels.RIGHT_ARM * PoseMath.FLOATS_PER_PART;
        // The arm's 3x4 row-major matrix as a column-major 4x4.
        Mat4.identity(part);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                part[column * 4 + row] = matrices[base + row * 4 + column];
            }
        }
        Mat4.translate(part, -0.0625f, 0.4375f, 0.0625f, part);
        if (sneaking) {
            Mat4.translate(part, 0f, 0.203125f, 0f, part);
        }
        // Every held model is doubled: ItemRenderer doubles 3D ones, RenderItem.preTransform the flat ones.
        Mat4.scale(part, 2f, 2f, 2f, part);
        display(part, held.definition().thirdPerson());
        Mat4.scale(part, 0.5f, 0.5f, 0.5f, part);
        Mat4.translate(part, -0.5f, -0.5f, -0.5f, part);
        submitItem(held, stack, part, light);
    }

    /** LayerSnowmanHead: a pumpkin block on the head, 0.625 scale, turned to face forward. */
    private void renderPumpkinHead(ModelPose pose, int light) {
        ItemStack pumpkin = new ItemStack(86, 1, 0, null);
        GpuItem block = gpuItem(catalog.model(pumpkin, -1, false));
        if (block == null) {
            return;
        }
        PoseMath.resolve(pose, root, matrices);
        int base = 2 * PoseMath.FLOATS_PER_PART; // the golem's head is part 2
        Mat4.identity(part);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                part[column * 4 + row] = matrices[base + row * 4 + column];
            }
        }
        Mat4.translate(part, 0f, -0.34375f, 0f, part);
        Mat4.rotate(part, (float) Math.PI, 0f, 1f, 0f, part);
        Mat4.scale(part, 0.625f, -0.625f, -0.625f, part);
        Mat4.translate(part, -0.5f, -0.5f, -0.5f, part);
        submitItem(block, pumpkin, part, light);
    }

    /** ItemCameraTransforms.applyTransform. */
    private static void display(float[] matrix, ItemDefinition.Display display) {
        Mat4.translate(matrix, display.translationX(), display.translationY(), display.translationZ(), matrix);
        Mat4.rotate(matrix, (float) Math.toRadians(display.rotationY()), 0f, 1f, 0f, matrix);
        Mat4.rotate(matrix, (float) Math.toRadians(display.rotationX()), 1f, 0f, 0f, matrix);
        Mat4.rotate(matrix, (float) Math.toRadians(display.rotationZ()), 0f, 0f, 1f, matrix);
        Mat4.scale(matrix, display.scaleX(), display.scaleY(), display.scaleZ(), matrix);
    }

    /**
     * Objects: dropped items (RenderEntityItem: bobbing and spinning, half size), thrown items as billboards facing
     * the camera (RenderSnowball: snowballs, eggs, ender pearls and eyes, potions, experience bottles, fire charges)
     * and arrows (RenderArrow), minecarts, boats, primed TNT, falling blocks, paintings, item frames, experience
     * orbs, armor stands, fireworks and lightning.
     */
    private void renderObject(Entity entity, float x, float y, float z, int light, float partialTick) {
        if (System.getProperty("alkaline.debug.objects") != null && entity.ticksExisted % 60 == 1) {
            System.err.println("object " + entity.type + " id " + entity.id + " rel " + x + "," + y + "," + z);
        }
        switch (entity.type) {
            case EntityTypes.OBJECT_ITEM -> {
                ItemStack stack = entity.metadata.getItem(10);
                GpuItem dropped = gpuItem(catalog.model(stack, -1, false));
                if (dropped == null) {
                    return;
                }
                float hover = (float) ((entity.id * 0x9E3779B9L >>> 40) / (double) (1 << 24) * Math.PI * 2.0);
                float age = entity.ticksExisted + partialTick;
                float bob = (float) Math.sin(age / 10f + hover) * 0.1f + 0.1f;
                Mat4.identity(item);
                // RenderEntityItem: centered 0.25 above the bob; RenderItem halves every model, and 3D ones are
                // halved again before it, so a dropped block is a quarter size and a sprite half.
                Mat4.translate(item, x, y + bob + 0.25f, z, item);
                Mat4.rotate(item, age / 20f + hover, 0f, 1f, 0f, item);
                float size = dropped.definition().block() ? 0.25f : 0.5f;
                Mat4.scale(item, size, size, size, item);
                Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
                submitItem(dropped, stack, item, light);
            }
            case EntityTypes.OBJECT_ARROW -> arrow(entity, x, y, z, light, partialTick);
            case EntityTypes.OBJECT_SNOWBALL -> billboard(new ItemStack(332, 1, 0, null), x, y, z, 0.5f, light);
            case EntityTypes.OBJECT_EGG -> billboard(new ItemStack(344, 1, 0, null), x, y, z, 0.5f, light);
            case EntityTypes.OBJECT_ENDER_PEARL -> billboard(new ItemStack(368, 1, 0, null), x, y, z, 0.5f, light);
            case EntityTypes.OBJECT_ENDER_EYE -> billboard(new ItemStack(381, 1, 0, null), x, y, z, 0.5f, light);
            case EntityTypes.OBJECT_EXP_BOTTLE -> billboard(new ItemStack(384, 1, 0, null), x, y, z, 0.5f, light);
            // The potion's damage value rides in the spawn data (RenderPotion).
            case EntityTypes.OBJECT_POTION -> billboard(new ItemStack(373, 1, entity.objectData, null), x, y, z, 0.5f,
                    light);
            case EntityTypes.OBJECT_FIREBALL -> billboard(new ItemStack(385, 1, 0, null), x, y, z, 2.0f, 0xFFFFFF);
            case EntityTypes.OBJECT_SMALL_FIREBALL -> billboard(new ItemStack(385, 1, 0, null), x, y, z, 0.5f,
                    0xFFFFFF);
            // RenderFireworkRocket is a RenderSnowball of the rocket item.
            case EntityTypes.OBJECT_FIREWORK -> billboard(new ItemStack(401, 1, 0, null), x, y, z, 0.5f, light);
            case EntityTypes.OBJECT_MINECART -> minecart(entity, x, y, z, light, partialTick);
            case EntityTypes.OBJECT_BOAT -> boat(entity, x, y, z, light, partialTick);
            case EntityTypes.OBJECT_TNT -> primedTnt(entity, x, y, z, light, partialTick);
            case EntityTypes.OBJECT_FALLING_BLOCK -> fallingBlock(entity, x, y, z, light);
            case EntityTypes.OBJECT_PAINTING -> painting(entity, x, y, z);
            case EntityTypes.OBJECT_ITEM_FRAME -> itemFrame(entity, light);
            case EntityTypes.OBJECT_XP_ORB -> experienceOrb(entity, x, y, z, partialTick);
            case EntityTypes.OBJECT_ARMOR_STAND -> armorStand(entity, x, y, z, light, partialTick);
            case EntityTypes.OBJECT_LIGHTNING -> lightning(entity, x, y, z);
            case EntityTypes.OBJECT_ENDER_CRYSTAL -> enderCrystal(entity, x, y, z, light, partialTick);
            default -> {
            }
        }
    }

    /** RenderSnowball: the item sprite {@code scale} blocks wide, turned to face the camera. */
    private void billboard(ItemStack stack, float x, float y, float z, float scale, int light) {
        GpuItem model = gpuItem(catalog.model(stack, -1, false));
        if (model == null) {
            return;
        }
        Mat4.identity(item);
        Mat4.translate(item, x, y, z, item);
        Mat4.scale(item, scale, scale, scale, item);
        Mat4.rotate(item, (float) Math.toRadians(-viewYaw), 0f, 1f, 0f, item);
        Mat4.rotate(item, (float) Math.toRadians(viewPitch), 1f, 0f, 0f, item);
        // RenderItem.preTransform doubles a flat model and renderItem halves it, so the sprite keeps `scale`.
        Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
        submitItem(model, stack, item, light);
    }

    // ---- minecarts, boats, TNT, falling blocks ------------------------------------------------------------

    /**
     * RenderMinecart: a hair of per-cart jitter against z-fighting; on a rail the cart takes the track's point and
     * turns along it (0.3 blocks ahead and behind); it rolls when hit; the load sits scaled to 0.75 and turned a
     * quarter, down by the cart's offset.
     */
    private void minecart(Entity entity, float x, float y, float z, int light, float partialTick) {
        VulkanTexture texture = texture("entity/minecart");
        if (texture == null) {
            return;
        }
        long seed = entity.id * 493286711L;
        seed = seed * seed * 4392167121L + seed * 98761L;
        float jitterX = (((float) (seed >> 16 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        float jitterY = (((float) (seed >> 20 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        float jitterZ = (((float) (seed >> 24 & 7L) + 0.5F) / 8.0F - 0.5F) * 0.004F;
        double worldX = entity.renderX(partialTick);
        double worldY = entity.renderY(partialTick);
        double worldZ = entity.renderZ(partialTick);
        float yaw = entity.previousYaw + (entity.yaw - entity.previousYaw) * partialTick;
        float pitch = entity.previousPitch + (entity.pitch - entity.previousPitch) * partialTick;
        double[] track = chestWorld == null ? null : MinecartRails.position(chestWorld, worldX, worldY, worldZ);
        if (track != null) {
            double[] ahead = MinecartRails.offset(chestWorld, worldX, worldY, worldZ, 0.3F);
            double[] behind = MinecartRails.offset(chestWorld, worldX, worldY, worldZ, -0.3F);
            ahead = ahead == null ? track : ahead;
            behind = behind == null ? track : behind;
            x += (float) (track[0] - worldX);
            y += (float) ((ahead[1] + behind[1]) / 2.0 - worldY);
            z += (float) (track[2] - worldZ);
            double dx = behind[0] - ahead[0];
            double dy = behind[1] - ahead[1];
            double dz = behind[2] - ahead[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length != 0.0) {
                dx /= length;
                dy /= length;
                dz /= length;
                yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI);
                pitch = (float) (Math.atan(dy) * 73.0);
            }
        }
        Mat4.identity(stand);
        Mat4.translate(stand, jitterX + x, jitterY + y + 0.375f, jitterZ + z, stand);
        Mat4.rotate(stand, (float) Math.toRadians(180.0F - yaw), 0f, 1f, 0f, stand);
        Mat4.rotate(stand, (float) Math.toRadians(-pitch), 0f, 0f, 1f, stand);
        float roll = entity.metadata.getInt(17, 0) - partialTick;
        float damage = Math.max(0f, entity.metadata.getFloat(19, 0f) - partialTick);
        if (roll > 0f) {
            Mat4.rotate(stand, (float) Math.toRadians((float) Math.sin(roll) * roll * damage / 10.0F
                    * entity.metadata.getInt(18, 1)), 1f, 0f, 0f, stand);
        }
        if (displayTile(entity)) {
            GpuItem load = gpuItem(ItemCatalog.stateModel(tileId, tileMeta));
            if (load != null) {
                System.arraycopy(stand, 0, item, 0, 16);
                Mat4.scale(item, 0.75f, 0.75f, 0.75f, item);
                Mat4.translate(item, -0.5f, (tileOffset - 8) / 16.0f, 0.5f, item);
                Mat4.rotate(item, (float) (Math.PI / 2), 0f, 1f, 0f, item);
                submitItem(load, stateStack(tileId, tileMeta), item, light);
            }
        }
        Mat4.scale(stand, -1f, -1f, 1f, stand);
        ModelPose pose = poses.get(EntityModels.Kind.MINECART);
        pose.reset();
        models.submit(gpu.get(EntityModels.Kind.MINECART), texture, pose, stand, 0xFFFFFFFF, 0, light);
    }

    /**
     * EntityMinecart.getDisplayTile: the block the server put in the cart, or its type's own (a chest, a furnace,
     * TNT, a spawner, a hopper, a command block), sets {@link #tileId}, {@link #tileMeta} and {@link #tileOffset}.
     * False for an empty cart.
     */
    private boolean displayTile(Entity entity) {
        if (entity.metadata.getByte(22, 0) == 1) {
            int state = entity.metadata.getInt(20, 0);
            tileId = state & 4095;
            tileMeta = state >> 12 & 15;
            tileOffset = entity.metadata.getInt(21, 6);
            return tileId != 0;
        }
        tileMeta = 0;
        tileOffset = 6;
        switch (entity.objectData) {
            case 1 -> {
                tileId = 54;
                tileMeta = 2; // facing north
                tileOffset = 8;
            }
            case 2 -> {
                tileId = (entity.metadata.getByte(16, 0) & 1) != 0 ? 62 : 61;
                tileMeta = 2;
            }
            case 3 -> tileId = 46;
            case 4 -> tileId = 52;
            case 5 -> {
                tileId = 154;
                tileOffset = 1;
            }
            case 6 -> tileId = 137;
            default -> tileId = 0;
        }
        return tileId != 0;
    }

    /** The stack a block state tints by (grass, leaves), one per state. */
    private ItemStack stateStack(int id, int meta) {
        return stateStacks.computeIfAbsent(id << 4 | meta, key -> new ItemStack(id, 1, meta, null));
    }

    /** RenderBoat: lifted a quarter block, turned to the yaw, and rocking after a hit. */
    private void boat(Entity entity, float x, float y, float z, int light, float partialTick) {
        VulkanTexture texture = texture("entity/boat");
        if (texture == null) {
            return;
        }
        float yaw = entity.previousYaw + (entity.yaw - entity.previousYaw) * partialTick;
        Mat4.identity(stand);
        Mat4.translate(stand, x, y + 0.25f, z, stand);
        Mat4.rotate(stand, (float) Math.toRadians(180.0F - yaw), 0f, 1f, 0f, stand);
        float sinceHit = entity.metadata.getInt(17, 0) - partialTick;
        float damage = Math.max(0f, entity.metadata.getFloat(19, 0f) - partialTick);
        if (sinceHit > 0f) {
            Mat4.rotate(stand, (float) Math.toRadians((float) Math.sin(sinceHit) * sinceHit * damage / 10.0F
                    * entity.metadata.getInt(18, 1)), 1f, 0f, 0f, stand);
        }
        Mat4.scale(stand, -1f, -1f, 1f, stand);
        ModelPose pose = poses.get(EntityModels.Kind.BOAT);
        pose.reset();
        models.submit(gpu.get(EntityModels.Kind.BOAT), texture, pose, stand, 0xFFFFFFFF, 0, light);
    }

    /**
     * RenderTNTPrimed: the block, swelling over its last half second, and flashing white every 5 ticks (vanilla
     * blends a white copy over it; here the flash is the overlay color, which lightens the same texels).
     */
    private void primedTnt(Entity entity, float x, float y, float z, int light, float partialTick) {
        GpuItem block = gpuItem(ItemCatalog.stateModel(46, 0));
        if (block == null) {
            return;
        }
        int fuse = 80 - entity.ticksExisted; // the client counts down from the 80 EntityTNTPrimed starts at
        float remaining = fuse - partialTick + 1.0F;
        Mat4.identity(item);
        Mat4.translate(item, x, y + 0.5f, z, item);
        if (remaining < 10.0F) {
            float swell = Math.max(0f, Math.min(1f, 1.0F - remaining / 10.0F));
            swell *= swell;
            swell *= swell;
            float scale = 1.0F + swell * 0.3F;
            Mat4.scale(item, scale, scale, scale, item);
        }
        Mat4.translate(item, -0.5f, -0.5f, 0.5f, item);
        Mat4.rotate(item, (float) (Math.PI / 2), 0f, 1f, 0f, item);
        int overlay = 0;
        if (fuse / 5 % 2 == 0) {
            float flash = Math.max(0f, Math.min(1f, (1.0F - remaining / 100.0F) * 0.8F));
            overlay = Math.round(flash * 255f) << 24 | 0xFFFFFF;
        }
        submitItem(block, stateStack(46, 0), item, light, overlay);
    }

    /**
     * RenderFallingBlock: the block's own model centered on the entity, unless the world already has that block
     * where the entity is (it has landed).
     */
    private void fallingBlock(Entity entity, float x, float y, float z, int light) {
        int id = entity.objectData & 4095;
        int meta = entity.objectData >> 12 & 15;
        if (id == 0) {
            return;
        }
        if (chestWorld != null && chestWorld.state((int) Math.floor(entity.x), (int) Math.floor(entity.y),
                (int) Math.floor(entity.z)) == (id << 4 | meta)) {
            return;
        }
        GpuItem block = gpuItem(ItemCatalog.stateModel(id, meta));
        if (block == null) {
            return;
        }
        Mat4.identity(item);
        Mat4.translate(item, x - 0.5f, y, z - 0.5f, item);
        submitItem(block, stateStack(id, meta), item, light);
    }

    // ---- paintings, frames, orbs, armor stands, lightning -------------------------------------------------

    /**
     * RenderPainting: one tile per 16x16 pixels, each lit from the block in front of it (the wall's row and column it
     * covers), on the entity's wall turned to its facing.
     */
    private void painting(Entity entity, float x, float y, float z) {
        Hanging.Art art = Hanging.art(entity.objectData);
        if (System.getProperty("alkaline.debug.paintings") != null) {
            System.err.println("painting " + entity.id + " at " + entity.x + "," + entity.y + "," + entity.z + " yaw "
                    + entity.yaw + " art " + art + " rel " + x + "," + y + "," + z + " tex "
                    + textures.get("painting/paintings_kristoffer_zetterstrand") + " tiles " + paintingTiles.size());
        }
        VulkanTexture texture = texture("painting/paintings_kristoffer_zetterstrand");
        if (texture == null) {
            return;
        }
        int facing = Math.round(entity.yaw / 90f) & 3;
        Mat4.identity(stand);
        Mat4.translate(stand, x, y, z, stand);
        Mat4.rotate(stand, (float) Math.toRadians(180.0F - entity.yaw), 0f, 1f, 0f, stand);
        float left = -art.width() / 2.0F;
        float bottom = -art.height() / 2.0F;
        for (int u = 0; u < art.width() / 16; u++) {
            for (int v = 0; v < art.height() / 16; v++) {
                int key = Hanging.artIndex(art.title()) << 4 | u << 2 | v;
                ModelRenderer.GpuModel tile = paintingTiles.get(key);
                if (tile == null) {
                    pendingTiles.add(key);
                    continue;
                }
                float centerX = left + u * 16 + 8;
                float centerY = bottom + v * 16 + 8;
                // RenderPainting.setLightmap: the block this tile hangs in front of.
                int lightX = (int) Math.floor(entity.x);
                int lightY = (int) Math.floor(entity.y + centerY / 16.0F);
                int lightZ = (int) Math.floor(entity.z);
                switch (facing) {
                    case 2 -> lightX = (int) Math.floor(entity.x + centerX / 16.0F);
                    case 1 -> lightZ = (int) Math.floor(entity.z - centerX / 16.0F);
                    case 0 -> lightX = (int) Math.floor(entity.x - centerX / 16.0F);
                    default -> lightZ = (int) Math.floor(entity.z + centerX / 16.0F);
                }
                int packed = lighting.light(lightX, lightY, lightZ);
                System.arraycopy(stand, 0, item, 0, 16);
                Mat4.translate(item, centerX / 16.0F, centerY / 16.0F, 0f, item);
                models.submit(tile, texture, item, 0xFFFFFFFF, 0,
                        lightmapColors[(packed >> 4 & 15) * 16 + (packed & 15)]);
            }
        }
    }

    /**
     * RenderItemFrame: the frame model (the map variant when it holds a map), then the item 7 pixels out, turned by
     * the frame's rotation in eighths; sprites are turned to face out. A map is queued for {@link #framedMap}.
     */
    private void itemFrame(Entity entity, int light) {
        int facing = entity.objectData & 3;
        int[][] front = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int hangX = (int) Math.floor(entity.x + front[facing][0] * 0.46875);
        int hangY = (int) Math.floor(entity.y);
        int hangZ = (int) Math.floor(entity.z + front[facing][1] * 0.46875);
        ItemStack stack = entity.metadata.getItem(8);
        boolean map = stack != null && stack.id() == 358;
        GpuItem frame = gpuItem(ItemCatalog.locationModel("item_frame", map ? "map" : "normal"));
        if (frame == null) {
            return;
        }
        Mat4.identity(stand);
        Mat4.translate(stand, (float) (hangX + 0.5 - cameraX), (float) (hangY + 0.5 - cameraY),
                (float) (hangZ + 0.5 - cameraZ), stand);
        Mat4.rotate(stand, (float) Math.toRadians(180.0F - entity.yaw), 0f, 1f, 0f, stand);
        System.arraycopy(stand, 0, item, 0, 16);
        Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
        submitItem(frame, null, item, light);
        if (map) {
            framedMap(stand, stack.damage(), entity.metadata.getByte(9, 0), light);
        }
        if (stack == null || map) {
            return;
        }
        GpuItem shown = gpuItem(catalog.model(stack, -1, false));
        if (shown == null) {
            return;
        }
        System.arraycopy(stand, 0, item, 0, 16);
        Mat4.translate(item, 0f, 0f, 0.4375f, item);
        Mat4.rotate(item, (float) Math.toRadians((entity.metadata.getByte(9, 0) & 7) * 360.0F / 8.0F), 0f, 0f, 1f,
                item);
        Mat4.scale(item, 0.5f, 0.5f, 0.5f, item);
        if (!shown.definition().block()) {
            Mat4.rotate(item, (float) Math.PI, 0f, 1f, 0f, item);
        } else {
            // RenderItem halves a model; a flat one is doubled first (preTransform), a block is not.
            Mat4.scale(item, 0.5f, 0.5f, 0.5f, item);
        }
        Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
        submitItem(shown, stack, item, light);
    }

    /** EntityXPOrb.getTextureByXP: the icon grows with the orb's value. */
    private static int orbIcon(int value) {
        int[] steps = {3, 7, 17, 37, 73, 149, 307, 617, 1237, 2477};
        int icon = 0;
        while (icon < steps.length && value >= steps[icon]) {
            icon++;
        }
        return icon;
    }

    /**
     * RenderXPOrb: a quad facing the camera, 0.3 blocks, tinted through green and yellow as it ages and half
     * transparent; its light is raised by half the block-light range.
     */
    private void experienceOrb(Entity entity, float x, float y, float z, float partialTick) {
        VulkanTexture texture = texture("entity/experience_orb");
        if (texture == null) {
            return;
        }
        int sky = packedLight >> 4 & 15;
        int block = packedLight & 15;
        // EntityXPOrb.getBrightnessForRender adds 120 of the block light's 240.
        int light = average(lightmapColors[sky * 16 + Math.min(15, block + 7)],
                lightmapColors[sky * 16 + Math.min(15, block + 8)]);
        // OptiFine's xporb.time rescales the timer and its xporb.png palette replaces the pulse.
        float age = customColors.xpOrbTimer((entity.ticksExisted + partialTick) / 2.0F);
        int custom = customColors.xpOrb(age);
        int red = (int) (((float) Math.sin(age) + 1.0F) * 0.5F * 255.0F);
        int green = 255;
        int blue = (int) (((float) Math.sin(age + (float) (Math.PI * 4.0 / 3.0)) + 1.0F) * 0.1F * 255.0F);
        if (custom >= 0) {
            red = custom >> 16 & 255;
            green = custom >> 8 & 255;
            blue = custom & 255;
        }
        Mat4.identity(item);
        Mat4.translate(item, x, y, z, item);
        Mat4.rotate(item, (float) Math.toRadians(180.0F - viewYaw), 0f, 1f, 0f, item);
        Mat4.rotate(item, (float) Math.toRadians(-viewPitch), 1f, 0f, 0f, item);
        Mat4.scale(item, 0.3f, 0.3f, 0.3f, item);
        models.submit(orbModels[orbIcon(entity.objectData)], texture, item, 128 << 24 | red << 16 | green << 8 | blue, 0,
                light);
    }

    /** The average of two 0xRRGGBB colors. */
    private static int average(int a, int b) {
        return ((a ^ b) & 0xFEFEFE) >> 1 | a & b;
    }

    /** The rotation triple at metadata {@code index}, or the stand's default. */
    private static float[] rotations(Entity entity, int index, float[] fallback) {
        float[] found = entity.metadata.getRotations(index);
        return found == null ? fallback : found;
    }

    /**
     * ArmorStandRenderer: the stand's model posed from its rotation metadata (small stands use the child layout),
     * its armor posed the same way, and what it holds. An invisible stand draws only the armor and the item.
     */
    private void armorStand(Entity entity, float x, float y, float z, int light, float partialTick) {
        VulkanTexture texture = texture("entity/armorstand/wood");
        if (texture == null) {
            return;
        }
        int flags = entity.metadata.getByte(10, 0);
        boolean small = (flags & 1) != 0;
        boolean arms = (flags & 4) != 0;
        ModelPose pose = poses.get(EntityModels.Kind.ARMOR_STAND);
        ObjectModels.poseArmorStand(pose, true, rotations(entity, 11, ObjectModels.DEFAULT_HEAD),
                rotations(entity, 12, ObjectModels.DEFAULT_BODY), rotations(entity, 13, ObjectModels.DEFAULT_LEFT_ARM),
                rotations(entity, 14, ObjectModels.DEFAULT_RIGHT_ARM),
                rotations(entity, 15, ObjectModels.DEFAULT_LEFT_LEG),
                rotations(entity, 16, ObjectModels.DEFAULT_RIGHT_LEG), arms, (flags & 8) == 0, entity.yaw);
        livingRoot(x, y, z, lerpAngle(entity.previousYaw, entity.yaw, partialTick), entity, partialTick, 1f, 1f,
                false, 90f);
        if (!entity.invisible()) {
            if (small) {
                submitChild(EntityModels.Kind.ARMOR_STAND, texture, pose, light, 0, 0xFFFFFFFF, null, 0f);
            } else {
                models.submit(gpu.get(EntityModels.Kind.ARMOR_STAND), texture, pose, root, 0xFFFFFFFF, 0, light);
            }
        }
        renderArmor(entity, null, light, 0, small);
        if (entity.equipment[0] != null) {
            // LayerHeldItem draws at the right arm whether or not the stand shows arms.
            pose.visible(EntityModels.RIGHT_ARM, true);
            renderHeld(entity.equipment[0], pose, false, light, -1, false, small);
        }
    }

    private static final float CRYSTAL_TIP = (float) Math.toRadians(60.0);
    private static final float CRYSTAL_DIAGONAL = (float) Math.sqrt(0.5);

    /**
     * RenderEnderCrystal and ModelEnderCrystal, without the flip into model space (y stays up): at twice size and
     * half a block down, the base plate; then, spun by the inner rotation and lifted by its bob, the glass cube
     * tipped 60 degrees about the x = z diagonal, a second glass cube at 0.875 tipped and spun again, and the inner
     * cube at 0.875 of that, tipped and spun again.
     */
    private void enderCrystal(Entity entity, float x, float y, float z, int light, float partialTick) {
        VulkanTexture texture = texture("entity/endercrystal/endercrystal");
        if (texture == null) {
            return;
        }
        float time = MobAnimator.crystalRotation(entity) + partialTick;
        float bob = (float) Math.sin(time * 0.2f) / 2f + 0.5f;
        bob = bob * bob + bob;
        float spin = (float) Math.toRadians(time * 3f);
        Mat4.identity(stand);
        Mat4.translate(stand, x, y, z, stand);
        Mat4.scale(stand, 2f, 2f, 2f, stand);
        Mat4.translate(stand, 0f, -0.5f, 0f, stand);
        crystalPart(ObjectModels.CRYSTAL_BASE, texture, light);
        Mat4.rotate(stand, spin, 0f, 1f, 0f, stand);
        Mat4.translate(stand, 0f, 0.8f + bob * 0.2f, 0f, stand);
        Mat4.rotate(stand, CRYSTAL_TIP, CRYSTAL_DIAGONAL, 0f, CRYSTAL_DIAGONAL, stand);
        crystalPart(ObjectModels.CRYSTAL_GLASS, texture, light);
        for (int inner = 0; inner < 2; inner++) {
            Mat4.scale(stand, 0.875f, 0.875f, 0.875f, stand);
            Mat4.rotate(stand, CRYSTAL_TIP, CRYSTAL_DIAGONAL, 0f, CRYSTAL_DIAGONAL, stand);
            Mat4.rotate(stand, spin, 0f, 1f, 0f, stand);
            crystalPart(inner == 0 ? ObjectModels.CRYSTAL_GLASS : ObjectModels.CRYSTAL_CUBE, texture, light);
        }
    }

    /** One part of the crystal at the current {@link #stand} matrix. */
    private void crystalPart(int part, VulkanTexture texture, int light) {
        ModelPose pose = poses.get(EntityModels.Kind.ENDER_CRYSTAL);
        pose.visibleAll(false);
        pose.visible(part, true);
        models.submit(gpu.get(EntityModels.Kind.ENDER_CRYSTAL), texture, pose, stand, 0xFFFFFFFF, 0, light);
    }

    /**
     * RenderLightningBolt: the jagged bolt from its foot up, blended into the scene (additive at 0.3 alpha),
     * unlit. Its geometry comes from the bolt's seed and is rebuilt when a new strike changes it.
     */
    private void lightning(Entity entity, float x, float y, float z) {
        Bolt bolt = bolts.get(entity.id);
        if (bolt == null || bolt.seed != entity.boltVertex) {
            pendingBolts.put(entity.id, entity.boltVertex);
        }
        if (bolt == null) {
            return;
        }
        bolt.seen = true;
        Mat4.identity(item);
        Mat4.translate(item, x, y, z, item);
        models.setUnlit(true);
        models.submit(bolt.model, white, item, 77 << 24 | 115 << 16 | 115 << 8 | 128, 0, 0xFFFFFF);
        models.setUnlit(false);
    }

    /** RenderArrow: turned to the arrow's yaw and pitch, with the shake after it hits. */
    private void arrow(Entity entity, float x, float y, float z, int light, float partialTick) {
        VulkanTexture texture = texture("entity/arrow");
        if (texture == null) {
            return;
        }
        float yaw = entity.previousYaw + (entity.yaw - entity.previousYaw) * partialTick;
        float pitch = entity.previousPitch + (entity.pitch - entity.previousPitch) * partialTick;
        Mat4.identity(item);
        Mat4.translate(item, x, y, z, item);
        Mat4.rotate(item, (float) Math.toRadians(yaw - 90f), 0f, 1f, 0f, item);
        Mat4.rotate(item, (float) Math.toRadians(pitch), 0f, 0f, 1f, item);
        float shake = entity.arrowShake - partialTick;
        if (shake > 0f) {
            Mat4.rotate(item, (float) Math.toRadians(-Math.sin(shake * 3f) * shake), 0f, 0f, 1f, item);
        }
        models.submit(arrowModel, texture, item, 0xFFFFFFFF, 0, light);
    }

    // ---- first person -------------------------------------------------------------------------------------

    /**
     * The local player's hand (or held sprite item) in eye space, drawn with the hand projection into a pass whose
     * depth was cleared.
     *
     * @param bob         eye-space transform applied before the hand (hurt tilt and view bobbing), or null
     * @param projection  the hand projection (vanilla's base field of view)
     */
    void renderHand(VulkanFrame frame, UUID self, PlayerList players, ItemStack held, float equipProgress,
                    float swingProgress, float pitch, float yaw, float armPitchLag, float armYawLag, int light,
                    float[] bob, float[] projection, Hand use) {
        models.clearFog();
        FirstPersonItem.lights(pitch, yaw, light0, light1);
        models.setLighting(light0[0], light0[1], light0[2], light1[0], light1[1], light1[2], 0.4f);
        models.setGlint(glint, System.currentTimeMillis());
        models.begin(frame, projection);
        if (held != null && held.id() == MapView.FILLED_MAP && mapStore != null) {
            renderMapHand(frame, self, players, held, equipProgress, swingProgress, pitch, armPitchLag, armYawLag,
                    light, bob, projection);
            return;
        }
        GpuItem item = gpuItem(catalog.model(held, use.bowPull(), use.cast()));
        if (item != null) {
            FirstPersonItem.transform(item.definition().firstPerson(), equipProgress, swingProgress, armPitchLag,
                    armYawLag, use.action(), use.using(), use.useLeft(), use.maxUse(), use.style(), root);
            prepend(bob, root);
            submitItem(item, held, root, light);
        } else if (held == null) {
            VulkanTexture skin = skin(self, players.get(self));
            if (skin != null) {
                EntityModels.Kind kind = slim ? EntityModels.Kind.PLAYER_SLIM : EntityModels.Kind.PLAYER;
                ModelPose pose = poses.get(kind);
                FirstPersonArm.pose(pose);
                FirstPersonArm.transform(equipProgress, swingProgress, armPitchLag, armYawLag, root);
                prepend(bob, root);
                models.submit(gpu.get(kind), skin, pose, root, 0xFFFFFFFF, 0, light);
            }
        }
        models.flush();
    }

    private final float[] mapBase = new float[16];
    private final float[] mapArm = new float[16];
    private final float[] mapSheet = new float[16];

    /**
     * ItemRenderer.renderItemMap: both arms holding the map out, tilted toward the view as the head lowers, and the map
     * on its background sheet. The arms are skin models; the sheet and map are quads shaded by the item lights.
     */
    private void renderMapHand(VulkanFrame frame, UUID self, PlayerList players, ItemStack held, float equipProgress,
                               float swingProgress, float pitch, float armPitchLag, float armYawLag, int light,
                               float[] bob, float[] projection) {
        float root = (float) Math.sqrt(swingProgress);
        float angle = 1.0F - pitch / 45.0F + 0.1F;
        angle = Math.max(0f, Math.min(1f, angle));
        angle = -(float) Math.cos(angle * Math.PI) * 0.5F + 0.5F;
        Mat4.identity(mapBase);
        Mat4.rotate(mapBase, (float) Math.toRadians(armPitchLag * 0.1f), 1f, 0f, 0f, mapBase);
        Mat4.rotate(mapBase, (float) Math.toRadians(armYawLag * 0.1f), 0f, 1f, 0f, mapBase);
        Mat4.translate(mapBase, -0.4f * (float) Math.sin(root * Math.PI),
                0.2f * (float) Math.sin(root * Math.PI * 2.0), -0.2f * (float) Math.sin(swingProgress * Math.PI),
                mapBase);
        Mat4.translate(mapBase, 0f, 0.04f, -0.72f, mapBase);
        Mat4.translate(mapBase, 0f, equipProgress * -1.2f, 0f, mapBase);
        Mat4.translate(mapBase, 0f, angle * -0.5f, 0f, mapBase);
        Mat4.rotate(mapBase, (float) Math.toRadians(90), 0f, 1f, 0f, mapBase);
        Mat4.rotate(mapBase, (float) Math.toRadians(angle * -85f), 0f, 0f, 1f, mapBase);
        VulkanTexture skin = skin(self, players.get(self));
        if (skin != null) {
            EntityModels.Kind kind = slim ? EntityModels.Kind.PLAYER_SLIM : EntityModels.Kind.PLAYER;
            ModelPose pose = poses.get(kind);
            // renderRightArm, then renderLeftArm: each turned and moved against the map's base.
            System.arraycopy(mapBase, 0, mapArm, 0, 16);
            Mat4.rotate(mapArm, (float) Math.toRadians(54), 0f, 1f, 0f, mapArm);
            Mat4.rotate(mapArm, (float) Math.toRadians(64), 1f, 0f, 0f, mapArm);
            Mat4.rotate(mapArm, (float) Math.toRadians(-62), 0f, 0f, 1f, mapArm);
            Mat4.translate(mapArm, 0.25f, -0.85f, 0.75f, mapArm);
            FirstPersonArm.pose(pose);
            prepend(bob, mapArm);
            models.submit(gpu.get(kind), skin, pose, mapArm, 0xFFFFFFFF, 0, light);
            System.arraycopy(mapBase, 0, mapArm, 0, 16);
            Mat4.rotate(mapArm, (float) Math.toRadians(92), 0f, 1f, 0f, mapArm);
            Mat4.rotate(mapArm, (float) Math.toRadians(45), 1f, 0f, 0f, mapArm);
            Mat4.rotate(mapArm, (float) Math.toRadians(41), 0f, 0f, 1f, mapArm);
            Mat4.translate(mapArm, -0.3f, -1.1f, 0.45f, mapArm);
            FirstPersonArm.poseLeft(pose);
            prepend(bob, mapArm);
            models.submit(gpu.get(kind), skin, pose, mapArm, 0xFFFFFFFF, 0, light);
        }
        models.flush();
        // The sheet: swung and tilted further, then a unit of 128 map pixels laid out as one block.
        float swingTurn = (float) Math.sin(swingProgress * swingProgress * Math.PI);
        float swingRoot = (float) Math.sin(root * Math.PI);
        System.arraycopy(mapBase, 0, mapSheet, 0, 16);
        Mat4.rotate(mapSheet, (float) Math.toRadians(swingTurn * -20f), 0f, 1f, 0f, mapSheet);
        Mat4.rotate(mapSheet, (float) Math.toRadians(swingRoot * -20f), 0f, 0f, 1f, mapSheet);
        Mat4.rotate(mapSheet, (float) Math.toRadians(swingRoot * -80f), 1f, 0f, 0f, mapSheet);
        Mat4.scale(mapSheet, 0.38f, 0.38f, 0.38f, mapSheet);
        Mat4.rotate(mapSheet, (float) Math.toRadians(90), 0f, 1f, 0f, mapSheet);
        Mat4.rotate(mapSheet, (float) Math.toRadians(180), 0f, 0f, 1f, mapSheet);
        Mat4.translate(mapSheet, -1f, -1f, 0f, mapSheet);
        Mat4.scale(mapSheet, 0.015625f, 0.015625f, 0.015625f, mapSheet);
        prepend(bob, mapSheet);
        // glNormal3f(0, 0, -1) under standard item lighting: two lights and the ambient, as ModelRenderer's.
        float nx = -mapSheet[8];
        float ny = -mapSheet[9];
        float nz = -mapSheet[10];
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length;
        ny /= length;
        nz /= length;
        float diffuse = 0.4f + 0.6f * (Math.max(nx * light0[0] + ny * light0[1] + nz * light0[2], 0f)
                + Math.max(nx * light1[0] + ny * light1[1] + nz * light1[2], 0f));
        float shade = Math.min(diffuse, 1f);
        quads.begin(frame, projection);
        mapView.drawHeld(quads, mapStore, held.damage(), mapSheet, shaded(light, shade));
        quads.flush();
    }

    /** A lightmap color (0xRRGGBB) scaled by a shade, as an opaque ARGB tint. */
    private static int shaded(int light, float shade) {
        int red = Math.min(255, Math.round((light >> 16 & 255) * shade));
        int green = Math.min(255, Math.round((light >> 8 & 255) * shade));
        int blue = Math.min(255, Math.round((light & 255) * shade));
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /** Maps in item frames waiting for the end of the entity pass: matrix (map pixels to view), id and tint. */
    private float[][] framedMatrices = new float[8][16];
    private int[] framedIds = new int[8];
    private int[] framedColors = new int[8];
    private int framed;

    /**
     * RenderItemFrame's map: the bare map (and the holder's own marker) one block square on the frame's face.
     * {@code stand} is the frame's matrix at its hanging block's center, turned to face out; the rotation is the
     * frame's raw rotation byte (maps turn in quarter turns). The map is queued: it draws after the models, which
     * write the depth its quad is tested against, in {@link #drawFramedMaps}.
     */
    void framedMap(float[] stand, int mapId, int rotation, int light) {
        if (mapStore == null) {
            return;
        }
        if (framed == framedIds.length) {
            framedMatrices = java.util.Arrays.copyOf(framedMatrices, framed * 2);
            for (int index = framed; index < framedMatrices.length; index++) {
                framedMatrices[index] = new float[16];
            }
            framedIds = java.util.Arrays.copyOf(framedIds, framed * 2);
            framedColors = java.util.Arrays.copyOf(framedColors, framed * 2);
        }
        float[] matrix = framedMatrices[framed];
        System.arraycopy(stand, 0, matrix, 0, 16);
        Mat4.translate(matrix, 0f, 0f, 0.4375f, matrix);
        Mat4.rotate(matrix, (float) Math.toRadians(rotation % 4 * 2 * 360.0F / 8.0F), 0f, 0f, 1f, matrix);
        Mat4.rotate(matrix, (float) Math.PI, 0f, 0f, 1f, matrix);
        Mat4.scale(matrix, 0.0078125f, 0.0078125f, 0.0078125f, matrix);
        Mat4.translate(matrix, -64f, -64f, 0f, matrix);
        Mat4.translate(matrix, 0f, 0f, -1f, matrix);
        framedIds[framed] = mapId;
        framedColors[framed] = 0xFF000000 | light; // lighting is off in frames: only the world's light shades them
        framed++;
    }

    private void drawFramedMaps(VulkanFrame frame, float[] viewProjection) {
        if (framed > 0) {
            quads.begin(frame, viewProjection);
            for (int index = 0; index < framed; index++) {
                mapView.drawFramed(quads, mapStore, framedIds[index], framedMatrices[index], framedColors[index]);
            }
            quads.flush();
            framed = 0;
        }
    }

    /**
     * The first-person item use as ItemRenderer reads it: the action, whether the item is in use, ticks of use left
     * (interpolated), the item's maximum use, the bow's drawn ticks (-1 when not drawing) and a cast rod.
     */
    record Hand(FirstPersonItem.Use action, boolean using, float useLeft, int maxUse, int bowPull, boolean cast,
                FirstPersonItem.Style style) {
        static final Hand IDLE = new Hand(FirstPersonItem.Use.NONE, false, 0f, 0, -1, false,
                FirstPersonItem.Style.VANILLA);
    }

    private void prepend(float[] transform, float[] matrix) {
        if (transform != null) {
            Mat4.multiply(transform, matrix, part);
            System.arraycopy(part, 0, matrix, 0, 16);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** RendererLivingEntity.interpolateRotation. */
    private static float lerpAngle(float previous, float current, float partialTick) {
        return previous + Entity.wrap(current - previous) * partialTick;
    }

    /** EntitySheep.fleeceColorTable as ARGB. */
    private static int fleeceColor(int color) {
        return FLEECE[color & 15];
    }

    /** EntitySheep's fleece colors by dye, as 0xFFRRGGBB. */
    private static final int[] FLEECE = fleeceColors();

    private static int[] fleeceColors() {
        float[][] table = {{1f, 1f, 1f}, {0.85f, 0.5f, 0.2f}, {0.7f, 0.3f, 0.85f}, {0.4f, 0.6f, 0.85f},
                {0.9f, 0.9f, 0.2f}, {0.5f, 0.8f, 0.1f}, {0.95f, 0.5f, 0.65f}, {0.3f, 0.3f, 0.3f}, {0.6f, 0.6f, 0.6f},
                {0.3f, 0.5f, 0.6f}, {0.5f, 0.25f, 0.7f}, {0.2f, 0.3f, 0.7f}, {0.4f, 0.3f, 0.2f}, {0.4f, 0.5f, 0.2f},
                {0.6f, 0.2f, 0.2f}, {0.1f, 0.1f, 0.1f}};
        int[] colors = new int[table.length];
        for (int index = 0; index < table.length; index++) {
            float[] rgb = table[index];
            colors[index] = 0xFF000000 | Math.round(rgb[0] * 255f) << 16 | Math.round(rgb[1] * 255f) << 8
                    | Math.round(rgb[2] * 255f);
        }
        return colors;
    }

    int dropped() {
        return models.droppedInstances();
    }

    @Override
    public void close() {
        closeItems();
        if (white != null) {
            white.close();
        }
        labels.close();
        mapView.close();
        quads.close();
        models.close();
        for (VulkanTexture texture : textures.values()) {
            texture.close();
        }
        for (VulkanTexture texture : new java.util.HashSet<>(emissive.values())) {
            texture.close();
        }
        for (Animated texture : animated.values()) {
            for (int index = 0; index < texture.count; index++) {
                texture.looks[index].close();
            }
        }
        for (VulkanTexture texture : skinTextures.values()) {
            texture.close();
        }
        glints.close();
        if (glint != null) {
            glint.close();
        }
        if (crystalBeam != null) {
            crystalBeam.close();
        }
        if (shadow != null) {
            shadow.close();
        }
    }
}
