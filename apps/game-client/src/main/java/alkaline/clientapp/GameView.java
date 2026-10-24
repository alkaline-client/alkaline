package alkaline.clientapp;

import alkaline.client.settings.ClientSettings;
import alkaline.config.ConfigSnapshot;
import alkaline.game.entity.Entity;
import alkaline.game.entity.EntityTypes;
import alkaline.game.inventory.ItemStack;
import alkaline.game.net.ServerSession;
import alkaline.game.player.LocalPlayer;
import alkaline.game.world.Biomes189;
import alkaline.game.world.ClientWorld;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.image.ImageDecoder;
import alkaline.renderer.image.ImageSampling;
import alkaline.renderer.math.Mat4;
import alkaline.renderer.model.FirstPersonItem;
import alkaline.renderer.particle.ParticleEffects;
import alkaline.renderer.particle.ParticleSystem;
import alkaline.renderer.particle.VanillaParticles;
import alkaline.renderer.vulkan.background.MenuBackground;
import alkaline.renderer.vulkan.background.OverworldBackground;
import alkaline.renderer.vulkan.particle.ParticleRenderer;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.TextureOptions;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.resource.VulkanTexture;
import alkaline.renderer.vulkan.world.CloudRenderer;
import alkaline.renderer.vulkan.world.SectionStreamer;
import alkaline.renderer.vulkan.world.SkyRenderer;
import alkaline.renderer.vulkan.world.WorldRenderer;
import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.Lightmap;
import alkaline.renderer.world.Sky;
import alkaline.renderer.world.vanilla.Blocks189;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * The world of a multiplayer session, seen from the local player's eyes: the server's chunks meshed by the
 * world renderer into the scene target the menu compositor presents, under vanilla's sky (time of day, biome
 * sky color, stars, sun, moon, clouds, rain and snow from the server's weather), with the server's entities,
 * vanilla's particles and the first-person hand. Sections re-mesh as chunks arrive and blocks change; a dimension
 * change rebuilds the streamed area.
 *
 * <p>The pass follows vanilla's renderWorldPass order: sky, clouds below the eye's height, solid terrain,
 * entities, particles, rain and snow, translucent terrain, clouds above, then the hand after a depth clear.
 */
final class GameView implements MenuBackground {
    static final String ID = "game";
    /** Chunk.getPrecipitationHeight's stop: a material that blocks movement, or a liquid. */
    private static final IntPredicate STOPS_PRECIPITATION = id ->
            Blocks189.material(id).blocksMovement() || Blocks189.liquid(id);
    private static final int PARTICLE_CAPACITY = 16_384;

    private final VulkanRenderer renderer;
    private final AssetSource assets;
    private final BlockPalette palette;
    private final ServerSession session;
    private final PlaySession play;
    private final LocalPlayer player;
    private final Supplier<ConfigSnapshot> config;
    private final VulkanPipeline blit;
    private final float[] forward = new float[3];
    private final EntityView entities;
    private final CloudRenderer clouds;
    private final ParticleSystem particles = new ParticleSystem(PARTICLE_CAPACITY, 1_8_9L);
    private final ParticleRenderer particleRenderer;
    private final ParticleEffects effects;
    private final VulkanTexture particleSheet;
    private final VulkanTexture explosionSheet;
    private final CameraEffects camera = new CameraEffects();
    /** Chests, which vanilla draws as tile entities. */
    private final ChestTracker chestTracker = new ChestTracker();
    private final int[] lightmap = new int[256];
    private final float[] fog = new float[7];
    private final float[] right = new float[3];
    private final float[] up = new float[3];
    private final double[] cameraPosition = new double[3];
    private final float[] handProjection = new float[16];
    /** Entity light from whichever world is current (created once: a frame allocates no lambda). */
    private final EntityView.Lighting entityLight;
    private ConfigSnapshot optionsFor;
    private OverworldBackground.Settings options;
    /** The hurt overlay, rebuilt when the Hit color setting changes. */
    private EntityView.HitColor hitColor;
    private WorldRenderer world;
    private SectionStreamer streamer;
    private ClientWorld streamed;
    private Weather weather;
    private RainSource rainSource;
    private VulkanRenderTarget target;
    private long worldTime;
    private long ticks;
    private float rain;
    private float thunder;
    // ItemRenderer.updateEquippedItem.
    private float equipped;
    private float previousEquipped;
    private ItemStack shown;
    private int shownSlot = -1;
    private float zoomFactor = 1f;
    private boolean smoothZoom = true;
    private FirstPersonItem.Style handStyle = FirstPersonItem.Style.VANILLA;
    /** GameSettings.thirdPersonView: 0 first person, 1 behind, 2 in front. */
    private int perspective;
    /** The local player as other players see it, drawn in third person. */
    private Entity selfModel;

    GameView(VulkanRenderer renderer, AssetSource assets, BlockPalette palette, PlaySession play, Skins skins,
             ItemCatalog catalog, Supplier<ConfigSnapshot> config) throws IOException {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.assets = assets;
        this.palette = Objects.requireNonNull(palette, "palette");
        this.play = Objects.requireNonNull(play, "play");
        this.session = play.session();
        this.player = play.player();
        this.config = Objects.requireNonNull(config, "config");
        blit = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "blit.frag")
                .offscreen(SCENE_FORMAT).sampledImage(true).build());
        try {
            world = new WorldRenderer(renderer, assets, palette.textures(), SCENE_FORMAT);
        } catch (IOException | RuntimeException failure) {
            blit.close();
            throw failure;
        }
        entities = new EntityView(renderer, assets, SCENE_FORMAT, skins, catalog);
        CloudRenderer createdClouds = null;
        try {
            createdClouds = new CloudRenderer(renderer, assets, SCENE_FORMAT);
        } catch (IOException missing) {
            System.err.println("Clouds unavailable: " + missing.getMessage());
        }
        clouds = createdClouds;
        particleRenderer = new ParticleRenderer(renderer, SCENE_FORMAT);
        particleSheet = sheet(VanillaParticles.SHEET);
        explosionSheet = sheet(VanillaParticles.EXPLOSION_SHEET);
        entityLight = (x, y, z) -> session.world().light(x, y, z);
        effects = new ParticleEffects(particles, new ParticleEffects.Blocks() {
            @Override
            public int particleLayer(int state) {
                return palette.particleLayer(state);
            }

            @Override
            public int particleTint(int state) {
                return palette.particleTint(state);
            }
        });
        effects.water(this::waterParticleTint);
    }

    /** CustomColors.updateWaterFX for a water particle spawned at a point: the pack's water color there, or -1. */
    private int waterParticleTint(double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int biome = session.world().biome(blockX, blockZ);
        return palette.customColors().waterParticle(biome, Biomes189.temperature(biome, blockX, (int) y, blockZ),
                Biomes189.rainfall(biome), (int) Math.floor(y));
    }

    private VulkanTexture sheet(String path) {
        if (assets == null) {
            return null;
        }
        try (InputStream input = assets.open(path)) {
            return input == null ? null : renderer.createTexture(ImageDecoder.decode(input),
                    new TextureOptions(ImageSampling.NEAREST, false, false, 1));
        } catch (IOException | RuntimeException failure) {
            System.err.println("Particle sheet " + path + " unavailable: " + failure.getMessage());
            return null;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    /** A Block Action packet (a chest's lid opening or closing). */
    void blockAction(int x, int y, int z, int action, int value, int block) {
        chestTracker.blockAction(x, y, z, action, value, block);
    }

    /** The server's time of day, for sun brightness. */
    void time(long timeOfDay) {
        worldTime = dayTime(timeOfDay);
    }

    /**
     * WorldClient.setWorldTime: a server with doDaylightCycle off sends the time negated (S03PacketTimeUpdate), and
     * the client stores its absolute value.
     */
    static long dayTime(long timeOfDay) {
        return timeOfDay < 0L ? -timeOfDay : timeOfDay;
    }

    /** The server's rain and thunder strength (0..1), as Change Game State packets set them. */
    void weather(float rain, float thunder) {
        this.rain = rain;
        this.thunder = thunder;
    }

    ParticleEffects particles() {
        return effects;
    }

    /** A critical hit's sparks around an entity. */
    void critical(Entity target, boolean magic) {
        effects.critical(target.x, target.y, target.z, EntityTypes.width(target), EntityTypes.height(target), magic);
    }

    /** The Zoom module this tick: the magnification while its key is held (1 otherwise), and whether it eases. */
    void zoom(float factor, boolean smooth) {
        zoomFactor = factor;
        smoothZoom = smooth;
    }

    /** The Animations module's first-person style this tick ({@link FirstPersonItem.Style#VANILLA} when off). */
    void handStyle(FirstPersonItem.Style style) {
        handStyle = Objects.requireNonNull(style, "style");
    }

    /**
     * Carries the state a view built from new assets (resource packs changed) must keep: time and weather (sent
     * only when they change), the camera and hand.
     */
    void inherit(GameView previous) {
        worldTime = previous.worldTime;
        rain = previous.rain;
        thunder = previous.thunder;
        ticks = previous.ticks;
        perspective = previous.perspective;
        zoomFactor = previous.zoomFactor;
        smoothZoom = previous.smoothZoom;
        handStyle = previous.handStyle;
        shown = previous.shown;
        shownSlot = previous.shownSlot;
        equipped = previous.equipped;
        previousEquipped = previous.previousEquipped;
    }

    /** The perspective key: first person, then behind, then in front (GameSettings.thirdPersonView). */
    void cyclePerspective() {
        perspective = (perspective + 1) % 3;
    }

    /**
     * Copies the local player onto its third-person model after the player's tick (so the model and the camera
     * move together): position, look, swing, hurt, held item and armor, and the flags it sets itself.
     */
    void followSelf() {
        int id = play.entities().selfId();
        if (selfModel == null || selfModel.id != id) {
            selfModel = new Entity(id, Entity.Category.PLAYER, 0, play.self(), player.x, player.y, player.z,
                    player.yaw, player.pitch);
        }
        Entity model = selfModel;
        model.metadata.merge(play.entities().self());
        int flags = model.metadata.getByte(0, 0) & ~(0x02 | 0x08 | 0x10);
        model.metadata.setByte(0, flags | (player.sneaking ? 0x02 : 0) | (player.sprinting ? 0x08 : 0)
                | (player.usingItem ? 0x10 : 0));
        model.follow(player.x, player.y, player.z, player.yaw, player.pitch);
        model.tick();
        model.headYaw = player.yaw;
        model.previousSwingProgress = player.previousSwingProgress;
        model.swingProgress = player.swingProgress;
        model.hurtTime = player.hurtTime;
        model.dead = player.health <= 0f;
        model.vehicle = play.riding() ? 0 : -1;
        model.equipment[0] = play.held();
        for (int piece = 0; piece < 4; piece++) {
            model.equipment[1 + piece] = play.inventory().armor(piece);
        }
    }

    float magnification() {
        return camera.magnification();
    }

    @Override
    public void tick() {
        ticks++;
        ConfigSnapshot values = config.get();
        // EntityRenderer.updateRenderer: the fog color follows the light where the player stands.
        ClientWorld current = session.world();
        int x = (int) Math.floor(player.x);
        int y = (int) Math.floor(player.y);
        int z = (int) Math.floor(player.z);
        int light = current.light(x, y, z);
        int skyLevel = Math.max(0, (light >> 4) - Sky.skylightSubtracted(worldTime, rain, thunder));
        int level = Math.max(skyLevel, light & 15);
        world.sky().tick(Lightmap.levelBrightness(level), values.get(ClientSettings.WORLD_RENDER_DISTANCE));
        PlaySession.Effect nausea = play.effects().get(9);
        camera.tick(player, zoomFactor, smoothZoom, nausea == null ? -1 : nausea.ticksLeft());
        swirls();
        effects.level(switch (values.get(ClientSettings.WORLD_PARTICLES)) {
            case ALL -> ParticleEffects.Level.ALL;
            case DECREASED -> ParticleEffects.Level.DECREASED;
            case MINIMAL -> ParticleEffects.Level.MINIMAL;
        });
        effects.camera(player.x, player.y + LocalPlayer.EYE_HEIGHT, player.z);
        if (weather != null) {
            weather.tick();
            if (values.get(ClientSettings.WORLD_RAIN)) {
                effects.rain(rainSource, rain, ticks, player.x, player.y, player.z);
            }
        }
        effects.tick();
        particles.tick();
        chestTracker.tick();
        // ItemRenderer.updateEquippedItem: the hand lowers when the held item changes and rises with the new one.
        previousEquipped = equipped;
        ItemStack held = play.held();
        int slot = play.inventory().selected();
        boolean changed = slot != shownSlot || !same(shown, held);
        float target = changed ? 0f : 1f;
        equipped += Math.max(-0.4f, Math.min(0.4f, target - equipped));
        if (equipped < 0.1f) {
            shown = held;
            shownSlot = slot;
        }
    }

    /**
     * EntityLivingBase.updatePotionEffects on the client: entities with effects (metadata 7 holds the mixed color,
     * 8 whether they come from a beacon) give off swirls, rarely when invisible and a fifth as often from beacons.
     */
    private void swirls() {
        for (Entity entity : play.entities().all()) {
            if (entity.living() && !entity.dead) {
                swirl(entity.metadata, entity.invisible(), entity.x, entity.y, entity.z, EntityTypes.width(entity),
                        EntityTypes.height(entity));
            } else if (entity.category == Entity.Category.OBJECT) {
                projectile(entity);
            }
        }
        alkaline.game.entity.Metadata self = play.entities().self();
        swirl(self, (self.getByte(0, 0) & 0x20) != 0, player.x, player.y, player.z, (float) LocalPlayer.WIDTH,
                (float) LocalPlayer.HEIGHT);
    }

    private void swirl(alkaline.game.entity.Metadata metadata, boolean invisible, double x, double y, double z,
                       float width, float height) {
        int color = metadata.getInt(7, 0);
        if (color <= 0) {
            return;
        }
        boolean ambient = metadata.getByte(8, 0) > 0;
        boolean show = invisible ? swirlRandom.nextInt(15) == 0 : swirlRandom.nextBoolean();
        if (ambient) {
            show &= swirlRandom.nextInt(5) == 0;
        }
        if (show) {
            effects.spawn(ambient ? 16 : 15, false, x + (swirlRandom.nextDouble() - 0.5) * width,
                    y + swirlRandom.nextDouble() * height, z + (swirlRandom.nextDouble() - 0.5) * width,
                    (color >> 16 & 255) / 255.0, (color >> 8 & 255) / 255.0, (color & 255) / 255.0);
        }
    }

    private final java.util.Random swirlRandom = new java.util.Random();

    /**
     * Projectile particles the client spawns itself: the impact of a snowball, egg or ender pearl (their onImpact,
     * the tick the client sees it hit a block) and a critical arrow's trail (EntityArrow.onUpdate in flight).
     */
    private void projectile(Entity entity) {
        if (entity.impactTicks == 1) {
            switch (entity.type) {
                case EntityTypes.OBJECT_SNOWBALL -> {
                    for (int index = 0; index < 8; index++) {
                        effects.spawn(31, false, entity.x, entity.y, entity.z, 0, 0, 0);
                    }
                }
                case EntityTypes.OBJECT_EGG -> {
                    for (int index = 0; index < 8; index++) {
                        effects.spawn(36, false, entity.x, entity.y, entity.z, (swirlRandom.nextFloat() - 0.5) * 0.08,
                                (swirlRandom.nextFloat() - 0.5) * 0.08, (swirlRandom.nextFloat() - 0.5) * 0.08, 344);
                    }
                }
                case EntityTypes.OBJECT_ENDER_PEARL -> {
                    for (int index = 0; index < 32; index++) {
                        effects.spawn(24, false, entity.x, entity.y + swirlRandom.nextDouble() * 2.0, entity.z,
                                swirlRandom.nextGaussian(), 0, swirlRandom.nextGaussian());
                    }
                }
                default -> {
                }
            }
        }
        if (entity.type == EntityTypes.OBJECT_ARROW && !entity.stuck && (entity.metadata.getByte(16, 0) & 1) != 0) {
            for (int index = 0; index < 4; index++) {
                effects.spawn(9, false, entity.x + entity.motionX * index / 4.0, entity.y + entity.motionY * index / 4.0,
                        entity.z + entity.motionZ * index / 4.0, -entity.motionX, -entity.motionY + 0.2,
                        -entity.motionZ);
            }
        }
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a == null ? b == null : b != null && a.id() == b.id() && a.damage() == b.damage();
    }

    @Override
    public void beforeFrame(int width, int height) {
        if (width > 0 && height > 0 && (target == null || target.width() != width || target.height() != height)) {
            if (target != null) {
                target.close();
            }
            target = renderer.createRenderTarget(width, height, SCENE_FORMAT, true, false, true);
        }
        ClientWorld current = session.world();
        if (current != streamed) {
            // A new dimension: drop everything streamed from the old world.
            if (streamer != null) {
                streamer.close();
                world.close();
                try {
                    world = new WorldRenderer(renderer, assets, palette.textures(), SCENE_FORMAT);
                } catch (IOException failure) {
                    throw new IllegalStateException("Block textures unavailable: " + failure.getMessage(), failure);
                }
            }
            streamer = new SectionStreamer(new Source(current), palette, world);
            weather = new Weather(current);
            rainSource = new RainSource(current);
            particles.clear();
            particles.world(new ParticleWorld(current));
            chestTracker.clear();
            streamed = current;
        }
        OverworldBackground.Settings options = options(config.get());
        streamer.update(Math.floorDiv((int) Math.floor(player.x), 16), Math.floorDiv((int) Math.floor(player.z), 16),
                options.renderDistanceChunks(), options.ambientOcclusion());
        current.drainChanged(key -> {
            streamer.invalidate(ClientWorld.sectionKeyX(key), ClientWorld.sectionKeyY(key), ClientWorld.sectionKeyZ(key));
            chestTracker.changed(key);
        });
        chestTracker.update(current);
        entities.chests(chestTracker.chests(), current::state);
        world.prepareUploads();
        entities.beforeFrame();
    }

    @Override
    public void render(VulkanFrame frame, VulkanRenderTarget scene, float partialTick) {
        world.animate(ticks);
        if (target == null) {
            frame.beginRendering(scene, true, 0.52f, 0.70f, 1f, 1f);
            frame.endRendering();
            return;
        }
        ConfigSnapshot values = config.get();
        double yaw = Math.toRadians(player.yaw);
        double pitch = Math.toRadians(player.pitch);
        // Minecraft's yaw 0 looks toward +z; pitch is positive looking down.
        forward[0] = (float) (-Math.sin(yaw) * Math.cos(pitch));
        forward[1] = (float) -Math.sin(pitch);
        forward[2] = (float) (Math.cos(yaw) * Math.cos(pitch));
        OverworldBackground.Settings options = options(values);
        int distance = options.renderDistanceChunks();
        float reach = distance * 16f;
        double eyeX = player.eyeX(partialTick);
        double eyeY = player.eyeY(partialTick);
        double eyeZ = player.eyeZ(partialTick);
        double feetX = eyeX;
        double feetY = player.previousY + (player.y - player.previousY) * partialTick;
        double feetZ = eyeZ;
        ClientWorld current = session.world();
        float cameraYaw = player.yaw;
        float cameraPitch = player.pitch;
        if (perspective > 0) {
            // EntityRenderer.orientCamera: four blocks behind (or in front of) the eyes, pulled in by walls.
            double back = thirdPersonDistance(current, eyeX, eyeY, eyeZ) * (perspective == 2 ? -1.0 : 1.0);
            eyeX -= forward[0] * back;
            eyeY -= forward[1] * back;
            eyeZ -= forward[2] * back;
            if (perspective == 2) {
                forward[0] = -forward[0];
                forward[1] = -forward[1];
                forward[2] = -forward[2];
                cameraYaw += 180f;
                cameraPitch = -cameraPitch;
            }
        }
        int blockX = (int) Math.floor(feetX);
        int blockY = (int) Math.floor(feetY);
        int blockZ = (int) Math.floor(feetZ);
        int biome = current.biome(blockX, blockZ);
        float temperature = Biomes189.temperature(biome, blockX, blockY, blockZ);
        Sky sky = world.sky();
        options.applyTo(sky);
        // OptiFine looks custom sky, fog and underwater colormaps up by the entity's block biome (World.getSkyColor).
        sky.customColors = palette.customColors();
        sky.biome = biome;
        sky.feetY = feetY;
        sky.rainfall = Biomes189.rainfall(biome);
        sky.flat = play.flat();
        Sky.Medium medium = medium(current, eyeX, eyeY, eyeZ);
        sky.update(worldTime, partialTick, rain, thunder, temperature, forward[0], forward[1], forward[2], eyeY,
                distance, medium);
        // EntityRenderer.getNightVisionBrightness: full, flickering over the last 10 seconds.
        PlaySession.Effect nightVision = play.effects().get(16);
        float vision = nightVision == null ? 0f : nightVision.ticksLeft() > 200 ? 1f
                : 0.7f + (float) Math.sin((nightVision.ticksLeft() - partialTick) * Math.PI * 0.2f) * 0.3f;
        PlaySession.Effect blindness = play.effects().get(15);
        sky.blindness = blindness == null ? -1 : blindness.ticksLeft();
        sky.nightVision = vision;
        world.setLighting(sky.sunBrightness, 0f, values.get(ClientSettings.WORLD_BRIGHTNESS), vision);
        world.setWeather(weather, values.get(ClientSettings.WORLD_RAIN) ? rain : 0f, ticks, partialTick, feetX, feetY, feetZ);
        float[] eyeTransform = camera.update(player, values.get(ClientSettings.WORLD_VIEW_BOBBING),
                values.get(ClientSettings.WORLD_HURT_SHAKE), partialTick);
        float fov = camera.fov(values.get(ClientSettings.WORLD_FOV), partialTick);
        if (medium != Sky.Medium.AIR) {
            fov = fov * 60f / 70f; // EntityRenderer.getFOVModifier under water and in lava
        }
        world.prepare(frame, target.width(), target.height(), eyeX, eyeY, eyeZ, forward,
                (float) Math.toRadians(fov), reach + 96f, eyeTransform);
        world.lightmap(lightmap);
        int hitArgb = values.get(ClientSettings.WORLD_HIT_COLOR);
        if (hitColor == null || hitColor.argb() != hitArgb) {
            hitColor = new EntityView.HitColor(hitArgb, false);
        }
        CloudRenderer.Mode cloudMode = clouds == null ? CloudRenderer.Mode.OFF : switch (values.get(ClientSettings.WORLD_CLOUDS)) {
            case OFF -> CloudRenderer.Mode.OFF;
            case FAST -> CloudRenderer.Mode.FAST;
            case FANCY -> CloudRenderer.Mode.FANCY;
        };
        float cloudHeight = values.get(ClientSettings.WORLD_CLOUD_HEIGHT);
        boolean belowClouds = eyeY < 128.0 + cloudHeight * 128.0;
        double cloudTicks = ticks + partialTick;

        frame.beginRendering(target, true, 0f, 0f, 0f, 1f);
        world.renderSky(frame);
        if (belowClouds) {
            drawClouds(frame, cloudMode, sky, cloudTicks, eyeX, eyeY, eyeZ, cloudHeight);
        }
        world.renderSolid(frame, options.fog());
        world.fog(fog);
        play.animation().self(selfModel);
        entities.animation(play.animation());
        entities.render(frame, play.entities().all(), play.players(), entityLight, lightmap,
                fog, hitColor, partialTick, eyeX, eyeY, eyeZ, world.viewProjection(), cameraYaw, cameraPitch,
                perspective > 0 ? selfModel : null);
        if (particleSheet != null) {
            world.cameraBasis(right, up);
            cameraPosition[0] = eyeX;
            cameraPosition[1] = eyeY;
            cameraPosition[2] = eyeZ;
            particleRenderer.draw(frame, particles, particleSheet, explosionSheet, world.blockTextures(), lightmap,
                    partialTick, world.viewProjection(), cameraPosition, right, up);
        }
        world.renderWeather(frame);
        world.renderTranslucent(frame);
        if (!belowClouds) {
            drawClouds(frame, cloudMode, sky, cloudTicks, eyeX, eyeY, eyeZ, cloudHeight);
        }
        if (perspective == 0) {
            drawHand(frame, partialTick, current, eyeX, eyeY, eyeZ, medium != Sky.Medium.AIR, reach);
        }
        frame.endRendering();
        frame.beginRendering(scene, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(blit);
        frame.bindImage(target);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
    }

    /** The world options for a settings snapshot, rebuilt only when the snapshot changes. */
    private OverworldBackground.Settings options(ConfigSnapshot values) {
        if (values != optionsFor) {
            optionsFor = values;
            options = ClientApp.worldSettings(values);
        }
        return options;
    }

    private void drawClouds(VulkanFrame frame, CloudRenderer.Mode mode, Sky sky, double cloudTicks, double eyeX,
                            double eyeY, double eyeZ, float height) {
        if (mode != CloudRenderer.Mode.OFF) {
            clouds.draw(frame, mode, sky, cloudTicks, eyeX, eyeY, eyeZ, height, world.viewProjection());
        }
    }

    /**
     * orientCamera's wall check: eight rays from just around the eyes to the camera, each shortening the distance
     * to where it hits. Vanilla adds the z jitter to x as well, so the rays are not symmetric; kept.
     */
    private double thirdPersonDistance(ClientWorld current, double eyeX, double eyeY, double eyeZ) {
        double distance = 4.0;
        float lookPitch = perspective == 2 ? player.pitch + 180f : player.pitch;
        double yaw = Math.toRadians(player.yaw);
        double pitch = Math.toRadians(lookPitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch) * distance;
        double dz = Math.cos(yaw) * Math.cos(pitch) * distance;
        double dy = -Math.sin(pitch) * distance;
        for (int corner = 0; corner < 8; corner++) {
            double ox = ((corner & 1) * 2 - 1) * 0.1;
            double oy = ((corner >> 1 & 1) * 2 - 1) * 0.1;
            double oz = ((corner >> 2 & 1) * 2 - 1) * 0.1;
            double[] hit = alkaline.game.entity.ObjectPhysics.hits(current::state, eyeX + ox, eyeY + oy, eyeZ + oz,
                    eyeX - dx + ox + oz, eyeY - dy + oy, eyeZ - dz + oz);
            if (hit != null) {
                double hx = hit[0] - eyeX;
                double hy = hit[1] - eyeY;
                double hz = hit[2] - eyeZ;
                distance = Math.min(distance, Math.sqrt(hx * hx + hy * hy + hz * hz));
            }
        }
        return distance;
    }

    /** EntityRenderer.renderHand: depth cleared, vanilla's base 70-degree hand projection, the hand's own bob. */
    private void drawHand(VulkanFrame frame, float partialTick, ClientWorld current, double eyeX, double eyeY,
                          double eyeZ, boolean submerged, float reach) {
        if (play.gameMode() == 3) {
            return; // spectators have no hand
        }
        frame.clearDepth();
        float handFov = submerged ? 60f : 70f;
        Mat4.perspective((float) Math.toRadians(handFov), target.width() / (float) target.height(), 0.05f,
                reach * 2f, handProjection);
        int packed = current.light((int) Math.floor(eyeX), (int) Math.floor(eyeY), (int) Math.floor(eyeZ));
        int light = lightmap[(packed >> 4 & 15) * 16 + (packed & 15)];
        float armPitch = player.previousArmPitch + (player.armPitch - player.previousArmPitch) * partialTick;
        float armYaw = player.previousArmYaw + (player.armYaw - player.previousArmYaw) * partialTick;
        float equipProgress = 1f - (previousEquipped + (equipped - previousEquipped) * partialTick);
        entities.renderHand(frame, play.self(), play.players(), shown, equipProgress, player.swing(partialTick),
                player.pitch, player.yaw, player.pitch - armPitch, player.yaw - armYaw, light, camera.hand(),
                handProjection, hand(partialTick));
    }

    /**
     * How the first-person item is in use: ItemRenderer reads the shown item's use action while the player's use
     * count runs (counting down from the item's maximum), the bow its pulling frame from the ticks drawn.
     */
    private EntityView.Hand hand(float partialTick) {
        boolean using = player.usingItem && shown != null && shown.id() == player.itemInUse;
        boolean cast = shown != null && shown.id() == 346 && hookOut();
        if (!using) {
            return cast || handStyle != FirstPersonItem.Style.VANILLA
                    ? new EntityView.Hand(FirstPersonItem.Use.NONE, false, 0f, 0, -1, cast, handStyle)
                    : EntityView.Hand.IDLE;
        }
        FirstPersonItem.Use action = switch (shown.id()) {
            case 261 -> FirstPersonItem.Use.BOW;
            case 267, 268, 272, 276, 283 -> FirstPersonItem.Use.BLOCK;
            case 373, 335 -> FirstPersonItem.Use.DRINK;
            default -> FirstPersonItem.Use.EAT;
        };
        int maxUse = action == FirstPersonItem.Use.BOW || action == FirstPersonItem.Use.BLOCK ? 72000 : 32;
        float useLeft = maxUse - player.itemUseTicks - partialTick + 1f;
        return new EntityView.Hand(action, true, useLeft, maxUse,
                action == FirstPersonItem.Use.BOW ? player.itemUseTicks : -1, cast, handStyle);
    }

    /** Whether the local player's fishing hook is out (its spawn data names its thrower). */
    private boolean hookOut() {
        int self = play.entities().selfId();
        for (Entity entity : play.entities().all()) {
            if (entity.category == Entity.Category.OBJECT && entity.type == EntityTypes.OBJECT_FISHING_HOOK
                    && entity.objectData == self) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (streamer != null) {
            streamer.close();
        }
        if (target != null) {
            target.close();
        }
        entities.close();
        if (clouds != null) {
            clouds.close();
        }
        particleRenderer.close();
        if (particleSheet != null) {
            particleSheet.close();
        }
        if (explosionSheet != null) {
            explosionSheet.close();
        }
        blit.close();
        world.close();
    }

    /**
     * ActiveRenderInfo.getBlockAtEntityViewpoint: water or lava when the eye is below the liquid surface of its
     * block (a level-{@code n} liquid fills {@code 1 - (n + 1) / 9 + 1 / 9} of it), else air.
     */
    private static Sky.Medium medium(ClientWorld world, double eyeX, double eyeY, double eyeZ) {
        int y = (int) Math.floor(eyeY);
        int state = world.state((int) Math.floor(eyeX), y, (int) Math.floor(eyeZ));
        int id = state >> 4;
        if (!Blocks189.liquid(id)) {
            return Sky.Medium.AIR;
        }
        int meta = state & 15;
        float filled = (meta >= 8 ? 0 : meta + 1) / 9.0F - 0.11111111F;
        if (eyeY >= y + 1 - filled) {
            return Sky.Medium.AIR;
        }
        return id == 10 || id == 11 ? Sky.Medium.LAVA : Sky.Medium.WATER;
    }

    /**
     * The client world as the weather renderer reads it: precipitation heights, biome climate and light. Rain and
     * snow ask about the same 21x21 columns every frame, and the answers change at most once a tick, so each
     * column's answers are kept for the tick (a frame then reads arrays instead of the world's column map).
     */
    private static final class Weather implements SkyRenderer.Weather {
        private static final int SIZE = 32;
        private final ClientWorld world;
        private final int[] stamp = new int[SIZE * SIZE];
        private final int[] columnX = new int[SIZE * SIZE];
        private final int[] columnZ = new int[SIZE * SIZE];
        private final int[] height = new int[SIZE * SIZE];
        private final int[] biome = new int[SIZE * SIZE];
        private final int[] lightY = new int[SIZE * SIZE];
        private final int[] light = new int[SIZE * SIZE];
        private int generation = 1;

        Weather(ClientWorld world) {
            this.world = world;
        }

        /** Starts a new tick: every cached answer is stale. */
        void tick() {
            generation++;
        }

        private int slot(int x, int z) {
            int slot = (x & (SIZE - 1)) | (z & (SIZE - 1)) * SIZE;
            if (stamp[slot] != generation || columnX[slot] != x || columnZ[slot] != z) {
                stamp[slot] = generation;
                columnX[slot] = x;
                columnZ[slot] = z;
                height[slot] = world.precipitationHeight(x, z, STOPS_PRECIPITATION);
                biome[slot] = world.biome(x, z);
                lightY[slot] = Integer.MIN_VALUE;
            }
            return slot;
        }

        @Override
        public int precipitationHeight(int x, int z) {
            return height[slot(x, z)];
        }

        @Override
        public boolean precipitates(int x, int z) {
            return Biomes189.precipitates(biome[slot(x, z)]);
        }

        @Override
        public float temperature(int x, int y, int z) {
            return Biomes189.temperature(biome[slot(x, z)], x, y, z);
        }

        @Override
        public int light(int x, int y, int z) {
            int slot = slot(x, z);
            if (lightY[slot] != y) {
                lightY[slot] = y;
                light[slot] = world.light(x, y, z);
            }
            return light[slot];
        }
    }

    /** Rain splashes: where rain lands and what it lands on. */
    private record RainSource(ClientWorld world) implements ParticleEffects.Rain {
        @Override
        public int precipitationHeight(int x, int z) {
            return world.precipitationHeight(x, z, STOPS_PRECIPITATION);
        }

        @Override
        public boolean rains(int x, int y, int z) {
            return Biomes189.precipitates(world.biome(x, z)) && Biomes189.temperature(world.biome(x, z), x, y, z) >= 0.15F;
        }

        @Override
        public int state(int x, int y, int z) {
            return world.state(x, y, z);
        }
    }

    /** Particle collision and light from the client world. */
    private record ParticleWorld(ClientWorld world) implements ParticleSystem.World {
        @Override
        public boolean solid(int x, int y, int z) {
            int id = world.state(x, y, z) >> 4;
            return id != 0 && Blocks189.material(id).blocksMovement();
        }

        @Override
        public boolean water(int x, int y, int z) {
            int id = world.state(x, y, z) >> 4;
            return id == 8 || id == 9;
        }

        @Override
        public int light(int x, int y, int z) {
            return world.light(x, y, z);
        }
    }

    /** The client world as the meshers' block source: sections 0..15 with the two-block halo. */
    private record Source(ClientWorld world) implements BlockSource {
        @Override
        public int minSectionY() {
            return 0;
        }

        @Override
        public int maxSectionY() {
            return ClientWorld.SECTIONS - 1;
        }

        @Override
        public void fill(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light) {
            world.copy(sectionX * 16 - HALO, sectionY * 16 - HALO, sectionZ * 16 - HALO, PADDED, PADDED, PADDED, ids,
                    light);
        }

        @Override
        public void fillBiomes(int sectionX, int sectionZ, byte[] biomes) {
            for (int z = -HALO; z < 16 + HALO; z++) {
                for (int x = -HALO; x < 16 + HALO; x++) {
                    biomes[BlockSource.biomeIndex(x, z)] = (byte) world.biome(sectionX * 16 + x, sectionZ * 16 + z);
                }
            }
        }
    }
}
