package alkaline.renderer.vulkan.background;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.vulkan.resource.PipelineSpec;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanPipeline;
import alkaline.renderer.vulkan.resource.VulkanRenderTarget;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.world.SectionStreamer;
import alkaline.renderer.vulkan.world.WorldRenderer;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.Sky;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * "Overworld": a slow aerial orbit over procedurally generated terrain with vanilla block textures, drawn by
 * the {@link WorldRenderer}. It doubles as a live exercise of the world path: sections stream in on worker
 * threads, upload without stalls, and the render-distance, smooth-lighting and fog settings apply immediately.
 */
public final class OverworldBackground implements MenuBackground {
    public static final String ID = "overworld";
    private static final float FOV = (float) Math.toRadians(70.0);
    private static final double ORBIT_RADIUS = 36.0;
    private static final double ORBIT_SECONDS = 300.0;

    /**
     * Live world options, read every frame and shared with the game view.
     *
     * @param fogStart share of the render distance where fog begins
     * @param sky      upper sky, sun and moon, and stars toggles
     */
    public record Settings(int renderDistanceChunks, boolean ambientOcclusion, boolean fog, float fogStart,
                           boolean sky, boolean sunMoon, boolean stars) {
        /** Copies the sky and fog options into the sky state the next frame draws. */
        public void applyTo(Sky state) {
            state.fogStartShare = fogStart;
            state.upperSky = sky;
            state.sunMoon = sunMoon;
            state.stars = stars;
        }
    }

    private final VulkanRenderer renderer;
    private final WorldRenderer world;
    private final SectionStreamer streamer;
    private final VulkanPipeline blit;
    private final Supplier<Settings> settings;
    private final double eyeY;
    private final float[] forward = new float[3];
    private final double[] eye = new double[3];
    private VulkanRenderTarget target;
    private long ticks;
    private boolean ready;

    /**
     * @param palette compiled block models (see {@link BlockPalette#load}), shared with any game world
     * @param eyeY    camera height in blocks; the generator knows its terrain, the background does not
     */
    public OverworldBackground(VulkanRenderer renderer, AssetSource assets, BlockPalette palette, BlockSource source,
                               double eyeY, Supplier<Settings> settings) throws IOException {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.eyeY = eyeY;
        world = new WorldRenderer(renderer, assets, palette.textures(), SCENE_FORMAT);
        VulkanPipeline createdBlit = null;
        try {
            createdBlit = renderer.createPipeline(PipelineSpec.builder("fullscreen.vert", "blit.frag")
                    .offscreen(SCENE_FORMAT).sampledImage(true).build());
            streamer = new SectionStreamer(source, palette, world);
        } catch (RuntimeException | Error failure) {
            if (createdBlit != null) {
                createdBlit.close();
            }
            world.close();
            throw failure;
        }
        blit = createdBlit;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public void beforeFrame(int width, int height) {
        if (width > 0 && height > 0 && (target == null || target.width() != width || target.height() != height)) {
            if (target != null) {
                target.close();
            }
            target = renderer.createRenderTarget(width, height, SCENE_FORMAT, true, false, true);
        }
        Settings current = settings.get();
        eye(ticks / 20.0);
        streamer.update(Math.floorDiv((int) Math.floor(eye[0]), 16), Math.floorDiv((int) Math.floor(eye[2]), 16),
                current.renderDistanceChunks(), current.ambientOcclusion());
        world.prepareUploads();
        if (!ready && streamer.pending() == 0 && world.loadedSections() > 0) {
            ready = true;
        }
    }

    /** Orbit position at a time in seconds: a wide circle around the origin, gently rising and falling. */
    private void eye(double seconds) {
        double angle = seconds / ORBIT_SECONDS * Math.PI * 2;
        eye[0] = Math.cos(angle) * ORBIT_RADIUS;
        eye[1] = eyeY + Math.sin(angle * 3) * 2.0;
        eye[2] = Math.sin(angle) * ORBIT_RADIUS;
    }

    @Override
    public void render(VulkanFrame frame, VulkanRenderTarget scene, float partialTick) {
        world.animate(ticks);
        if (target == null) {
            frame.beginRendering(scene, true, 0.52f, 0.70f, 1f, 1f);
            frame.endRendering();
            return;
        }
        double seconds = (ticks + partialTick) / 20.0;
        double angle = seconds / ORBIT_SECONDS * Math.PI * 2;
        eye(seconds);
        // Look along the orbit, turned 35 degrees inward and tilted down toward the terrain.
        double heading = angle + Math.PI / 2 + Math.toRadians(35);
        double pitch = Math.toRadians(-17);
        forward[0] = (float) (Math.cos(heading) * Math.cos(pitch));
        forward[1] = (float) Math.sin(pitch);
        forward[2] = (float) (Math.sin(heading) * Math.cos(pitch));
        Settings current = settings.get();
        float reach = current.renderDistanceChunks() * 16f;
        current.applyTo(world.sky());
        world.sky().update(6000L, 0f, 0f, 0f, 0.8f, forward[0], forward[1], forward[2], eye[1],
                current.renderDistanceChunks(), Sky.Medium.AIR);
        world.prepare(frame, target.width(), target.height(), eye[0], eye[1], eye[2], forward, FOV, reach + 96f);
        frame.beginRendering(target, true, 0f, 0f, 0f, 1f);
        world.render(frame, current.fog());
        frame.endRendering();

        frame.beginRendering(scene, false, 0f, 0f, 0f, 1f);
        frame.bindPipeline(blit);
        frame.bindImage(target);
        frame.draw(3, 1, 0, 0);
        frame.endRendering();
    }

    /** Terrain statistics for diagnostics. */
    public String describe() {
        WorldRenderer.Stats stats = world.stats();
        return String.format(java.util.Locale.ROOT,
                "world: %d sections loaded, %d reached by the visibility walk, %d drawn, %d draws (%s), %d quads, "
                        + "%.1f MiB geometry, %.2f MiB sorted indices (%d re-sorts last frame), %.0f us/section mesh, %.1f us/frame culling and draw setup",
                stats.loadedSections(), stats.visibleSections(), stats.drawnSections(), stats.draws(),
                stats.indirect() ? "indirect" : "direct", stats.quads(), stats.geometryBytes() / 1048576.0,
                stats.indexBytes() / 1048576.0, stats.sortsLastFrame(), streamer.averageMeshMicros(),
                stats.averagePrepareMicros());
    }

    @Override
    public void close() {
        streamer.close();
        if (target != null) {
            target.close();
        }
        blit.close();
        world.close();
    }
}
