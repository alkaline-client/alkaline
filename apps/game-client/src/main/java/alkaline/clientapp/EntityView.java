package alkaline.clientapp;

import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
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
import alkaline.renderer.model.ProjectileModels;
import alkaline.renderer.model.ModelPose;
import alkaline.renderer.model.PoseMath;
import alkaline.renderer.vulkan.model.ModelRenderer;
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

    /** World lookups for lighting entities. */
    interface Lighting {
        /** Light as {@code sky << 4 | block}. */
        int light(int x, int y, int z);
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

    /** An item model on the GPU: its mesh (for the display transforms) and one model and texture per layer. */
    private record GpuItem(ItemMeshes.Mesh mesh, ModelRenderer.GpuModel[] models, VulkanTexture[] textures) {
        ItemDefinition definition() {
            return mesh.definition();
        }
    }

    EntityView(VulkanRenderer renderer, AssetSource assets, RenderTargetFormat target, Skins skins,
               ItemCatalog catalog) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.assets = assets;
        this.skins = Objects.requireNonNull(skins, "skins");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        models = new ModelRenderer(renderer, target);
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
        armorOuter = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_OUTER));
        armorInner = new ModelPose(EntityModels.model(EntityModels.Kind.ARMOR_INNER));
        handPose = new ModelPose(EntityModels.model(EntityModels.Kind.PLAYER));
    }

    // ---- assets -------------------------------------------------------------------------------------------

    /** Loads a few queued textures and item models; call outside frames. */
    void beforeFrame() {
        uploadSkins();
        glints.beforeFrame();
        if (!glintLoaded) {
            glintLoaded = true;
            glint = load("assets/minecraft/textures/misc/enchanted_item_glint.png",
                    new TextureOptions(ImageSampling.LINEAR, false, true, 1));
        }
        for (int upload = 0; upload < UPLOADS_PER_FRAME && !pendingTextures.isEmpty(); upload++) {
            String path = pendingTextures.poll();
            // Custom armor textures name full asset paths; the rest are short paths under textures/.
            TextureOptions nearest = new TextureOptions(ImageSampling.NEAREST, false, false, 1);
            VulkanTexture texture = path.indexOf('+') >= 0 ? layered(path, nearest)
                    : load(path.startsWith("assets/") ? path : "assets/minecraft/textures/" + path + ".png", nearest);
            if (texture == null) {
                failedTextures.add(path);
            } else {
                textures.put(path, texture);
            }
        }
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
        VulkanTexture texture = textures.get(path);
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
        int[] tints = catalog.tints(stack, item.mesh());
        for (int index = 0; index < item.models().length; index++) {
            int slot = item.mesh().layers().get(index).tintIndex();
            int tint = slot >= 0 && slot < tints.length ? tints[slot] : 0xFFFFFFFF;
            models.submit(item.models()[index], item.textures()[index], matrix, tint, 0, light,
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
        hookOwners.clear();
        for (Entity entity : entities) {
            if (entity.category == Entity.Category.OBJECT && entity.type == EntityTypes.OBJECT_FISHING_HOOK) {
                hookOwners.add(entity.objectData);
            }
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
            if (entity.invisible() && entity.category == Entity.Category.OBJECT) {
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
            if (entity.category == Entity.Category.OBJECT) {
                renderObject(entity, (float) x, (float) y, (float) z, light, partialTick);
            } else {
                renderLiving(entity, players, (float) x, (float) y, (float) z, light, hit, partialTick);
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
                    extraTint = fleeceColor(fleece & 15);
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
        }
        if (extraKind != null) {
            VulkanTexture extra = texture(extraTexture);
            ModelPose extraPose = poses.get(extraKind);
            if (extra != null && !child && !invisible) {
                extraPose.copyFrom(pose, extraKind == EntityModels.Kind.MOOSHROOM_MUSHROOMS ? 1
                        : Math.min(pose.model().partCount(), extraPose.model().partCount()));
                models.submit(gpu.get(extraKind), extra, extraPose, root, extraTint, overlay, light);
            }
        }
        if (kind == EntityModels.Kind.GUARDIAN && animState != null && !invisible) {
            renderGuardianBeam(entity, animState, x, y, z, partialTick);
        }
        if (kind == EntityModels.Kind.WITCH && entity.equipment[0] != null) {
            renderWitchHeld(entity.equipment[0], pose, light);
        }
        if (kind == EntityModels.Kind.SNOW_GOLEM && !invisible) {
            renderPumpkinHead(pose, light);
        }
        if (biped && !child) {
            renderArmor(entity, pose, light, hit.tintArmor() ? overlay : 0);
            renderHeld(entity.equipment[0], pose, motion.sneaking, light,
                    entity.equipment[0] != null && entity.equipment[0].id() == 261 && entity.usingItem()
                            ? entity.usingTicks : -1, hookOwners.contains(entity.id));
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
        if (held.definition().block()) {
            Mat4.scale(part, 2f, 2f, 2f, part);
        }
        display(part, held.definition().thirdPerson());
        Mat4.scale(part, 0.5f, 0.5f, 0.5f, part);
        Mat4.translate(part, -0.5f, -0.5f, -0.5f, part);
        submitItem(held, stack, part, light);
    }

    private final float[] beamBase = new float[16];
    private final float[] beamSegment = new float[16];

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
        if (kind == EntityModels.Kind.HORSE) {
            submitFoal(texture, pose, light, overlay);
            return true;
        }
        float headScale;
        float headLift;
        float headForward;
        int[] headParts;
        switch (kind) {
            case BIPED, BIPED_TALL -> {
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
        for (int index = 0; index < visible.length; index++) {
            visible[index] = pose.visible(index);
        }
        // Head first.
        pose.visibleAll(false);
        for (int head : headParts) {
            pose.visible(head, true);
        }
        Mat4.scale(root, headScale, headScale, headScale, root);
        Mat4.translate(root, 0f, headLift, headForward, root);
        models.submit(gpu.get(kind), texture, pose, root, 0xFFFFFFFF, overlay, light);
        // Then the half-size body.
        System.arraycopy(saved, 0, root, 0, 16);
        for (int index = 0; index < visible.length; index++) {
            pose.visible(index, visible[index]);
        }
        for (int head : headParts) {
            pose.visible(head, false);
        }
        Mat4.scale(root, 0.5f, 0.5f, 0.5f, root);
        Mat4.translate(root, 0f, 1.5f, 0f, root);
        models.submit(gpu.get(kind), texture, pose, root, 0xFFFFFFFF, overlay, light);
        System.arraycopy(saved, 0, root, 0, 16);
        for (int index = 0; index < visible.length; index++) {
            pose.visible(index, visible[index]);
        }
        return true;
    }

    /** LayerBipedArmor: boots, leggings, chestplate and helmet on the inflated biped, posed as the wearer. */
    private void renderArmor(Entity entity, ModelPose wearer, int light, int overlay) {
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
            pose.copyFrom(wearer, EntityModels.BIPED_PARTS);
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
            if (material == 0) {
                int color = stack.leatherColor();
                models.submit(gpu.get(kind), texture, pose, root, 0xFF000000 | (color < 0 ? LEATHER : color), overlay,
                        light, shimmer, motion.ageInTicks);
                VulkanTexture leatherOverlay = armorTexture(stack, LEATHER_OVERLAY[leggings ? 1 : 0]);
                if (leatherOverlay != null) {
                    models.submit(gpu.get(kind), leatherOverlay, pose, root, 0xFFFFFFFF, overlay, light);
                }
            } else {
                models.submit(gpu.get(kind), texture, pose, root, 0xFFFFFFFF, overlay, light, shimmer,
                        motion.ageInTicks);
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
        GpuItem held = gpuItem(catalog.model(stack, bowPull, cast));
        if (held == null) {
            return;
        }
        PoseMath.resolve(pose, root, matrices);
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
        if (held.definition().block()) {
            Mat4.scale(part, 2f, 2f, 2f, part);
        }
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
     * and arrows (RenderArrow). Other objects are not drawn yet.
     */
    private void renderObject(Entity entity, float x, float y, float z, int light, float partialTick) {
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
                Mat4.translate(item, x, y + bob + 0.125f, z, item);
                Mat4.rotate(item, age / 20f + hover, 0f, 1f, 0f, item);
                Mat4.scale(item, 0.5f, 0.5f, 0.5f, item);
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
            default -> {
            }
        }
    }

    /** RenderSnowball: the item model scaled by {@code scale} and half again, turned to face the camera. */
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
        Mat4.scale(item, 0.5f, 0.5f, 0.5f, item);
        Mat4.translate(item, -0.5f, -0.5f, -0.5f, item);
        submitItem(model, stack, item, light);
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
        GpuItem item = gpuItem(catalog.model(held, use.bowPull(), use.cast()));
        if (item != null) {
            FirstPersonItem.transform(item.definition().firstPerson(), item.definition().block(), equipProgress,
                    swingProgress, armPitchLag, armYawLag, use.action(), use.using(), use.useLeft(), use.maxUse(),
                    use.style(), root);
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
        float[][] table = {{1f, 1f, 1f}, {0.85f, 0.5f, 0.2f}, {0.7f, 0.3f, 0.85f}, {0.4f, 0.6f, 0.85f},
                {0.9f, 0.9f, 0.2f}, {0.5f, 0.8f, 0.1f}, {0.95f, 0.5f, 0.65f}, {0.3f, 0.3f, 0.3f}, {0.6f, 0.6f, 0.6f},
                {0.3f, 0.5f, 0.6f}, {0.5f, 0.25f, 0.7f}, {0.2f, 0.3f, 0.7f}, {0.4f, 0.3f, 0.2f}, {0.4f, 0.5f, 0.2f},
                {0.6f, 0.2f, 0.2f}, {0.1f, 0.1f, 0.1f}};
        float[] rgb = table[color & 15];
        return 0xFF000000 | Math.round(rgb[0] * 255f) << 16 | Math.round(rgb[1] * 255f) << 8 | Math.round(rgb[2] * 255f);
    }

    int dropped() {
        return models.droppedInstances();
    }

    @Override
    public void close() {
        closeItems();
        models.close();
        for (VulkanTexture texture : textures.values()) {
            texture.close();
        }
        for (VulkanTexture texture : skinTextures.values()) {
            texture.close();
        }
        glints.close();
        if (glint != null) {
            glint.close();
        }
    }
}
