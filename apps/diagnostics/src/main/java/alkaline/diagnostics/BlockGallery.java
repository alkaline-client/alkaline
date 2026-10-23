package alkaline.diagnostics;

import alkaline.platform.sdl.SdlWindow;
import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.asset.MinecraftInstallation;
import alkaline.renderer.asset.ZipAssetSource;
import alkaline.renderer.image.RgbaImage;
import alkaline.renderer.vulkan.resource.PresentMode;
import alkaline.renderer.vulkan.resource.VulkanFrame;
import alkaline.renderer.vulkan.resource.VulkanRenderer;
import alkaline.renderer.vulkan.world.SectionStreamer;
import alkaline.renderer.vulkan.world.WorldRenderer;
import alkaline.renderer.world.BlockPalette;
import alkaline.renderer.world.BlockSource;
import alkaline.renderer.world.vanilla.Blocks189;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

/**
 * Every 1.8.9 block with a block model, on a stone floor, through the real world renderer: a grid of each
 * block's metadata 0 (eight per row, three blocks apart, in id order) and, south of it, arrangements that
 * exercise neighbor-dependent shapes (fences and gates, walls, panes, stairs corners, slabs, redstone, doors,
 * double plants, rails, flowing water and lava). Views frame parts of it for screenshots.
 */
final class BlockGallery {
    private static final int FLOOR = 63;
    private static final int COLUMNS = 8;
    private static final int SPACING = 3;

    /** A camera: eye position, yaw (degrees, 0 looks toward -z), pitch (degrees, negative looks down). */
    private record View(double x, double y, double z, float yaw, float pitch) {
    }

    private BlockGallery() {
    }

    static int run(int viewIndex, Path screenshot) {
        try {
            return show(viewIndex, screenshot);
        } catch (IOException failure) {
            System.err.println("Block gallery unavailable: " + failure.getMessage());
            return 1;
        }
    }

    private static int show(int viewIndex, Path screenshot) throws IOException {
        Path jar = MinecraftInstallation.locateClientJar()
                .orElseThrow(() -> new IOException("No Minecraft 1.8.9 jar; set ALKALINE_MINECRAFT_JAR."));
        Gallery world = new Gallery();
        View[] views = views(world);
        View view = views[Math.floorMod(viewIndex, views.length)];
        try (AssetSource assets = new ZipAssetSource(jar);
             SdlWindow window = SdlWindow.openVisible("Alkaline block gallery, view " + viewIndex, 1280, 720);
             VulkanRenderer renderer = new VulkanRenderer(window, PresentMode.FIFO, false)) {
            BlockPalette palette = BlockPalette.load(assets);
            System.out.printf("%d views; view %d. %d block models, %d states.%n", views.length, viewIndex,
                    palette.modelCount(), palette.stateCount());
            try (WorldRenderer renderer3d = new WorldRenderer(renderer, assets, palette.textures(), null);
                 SectionStreamer streamer = new SectionStreamer(world, palette, renderer3d)) {
                float[] forward = forward(view);
                AtomicBoolean done = new AtomicBoolean();
                boolean captureRequested = false;
                long started = System.nanoTime();
                long ticks = 0;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    SDL_Event event = SDL_Event.calloc(stack);
                    while (!done.get()) {
                        while (SDLEvents.SDL_PollEvent(event)) {
                            if (event.type() == SDLEvents.SDL_EVENT_QUIT
                                    || event.type() == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                                done.set(true);
                            }
                        }
                        streamer.update(Math.floorDiv((int) view.x(), 16), Math.floorDiv((int) view.z(), 16), 4, true);
                        renderer3d.prepareUploads();
                        VulkanFrame frame = renderer.acquireFrame();
                        if (frame == null) {
                            continue;
                        }
                        ticks = (System.nanoTime() - started) / 50_000_000L;
                        renderer3d.animate(ticks);
                        renderer3d.prepare(frame, frame.width(), frame.height(), view.x(), view.y(), view.z(), forward,
                                (float) Math.toRadians(70), 160f);
                        frame.beginMainPass(0.47f, 0.65f, 1.0f, 1f);
                        renderer3d.render(frame, false);
                        boolean settled = streamer.pending() == 0 && renderer3d.pendingUploads() == 0
                                && System.nanoTime() - started > 1_500_000_000L;
                        if (screenshot != null && settled && !captureRequested) {
                            captureRequested = renderer.captureNextFrame(image -> {
                                try {
                                    writePng(image, screenshot);
                                    System.out.println("Saved " + screenshot);
                                } catch (IOException failure) {
                                    System.err.println("Screenshot failed: " + failure.getMessage());
                                }
                                done.set(true);
                            });
                        }
                        renderer.endFrame();
                    }
                }
                System.out.println(renderer3d.stats());
            }
        }
        return 0;
    }

    private static View[] views(Gallery world) {
        int rows = (world.gridCount + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * SPACING;
        java.util.List<View> views = new java.util.ArrayList<>();
        // Overview from the south, looking north and down over the whole grid.
        views.add(new View(width / 2.0, FLOOR + 28, rows * SPACING + 14, 0f, -48f));
        // Close-ups: four rows at a time, from the south.
        for (int row = 0; row < rows; row += 4) {
            views.add(new View(width / 2.0, FLOOR + 7.5, row * SPACING + 4 * SPACING + 4, 0f, -32f));
        }
        // The arrangements south of the grid, two views.
        double south = rows * SPACING + 6;
        views.add(new View(12, FLOOR + 9, south + 22, 0f, -30f));
        views.add(new View(36, FLOOR + 9, south + 22, 0f, -30f));
        // Detail: the lone tripwire (and its hook), whose faint string fades out with distance.
        views.add(new View(15.5, FLOOR + 2.4, 47.8, -10f, -45f));
        return views.toArray(new View[0]);
    }

    private static float[] forward(View view) {
        double yaw = Math.toRadians(view.yaw());
        double pitch = Math.toRadians(view.pitch());
        return new float[]{(float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (-Math.cos(yaw) * Math.cos(pitch))};
    }

    /** The gallery as a block source: sparse placed states over a stone floor, sky light from above. */
    private static final class Gallery implements BlockSource {
        private final Map<Long, Character> placed = new HashMap<>();
        private final int gridCount;

        Gallery() {
            int slot = 0;
            for (int id = 1; id < Blocks189.COUNT; id++) {
                if (!Blocks189.known(id) || Blocks189.render(id) == Blocks189.Render.NONE) {
                    continue;
                }
                int x = (slot % COLUMNS) * SPACING + 1;
                int z = (slot / COLUMNS) * SPACING + 1;
                place(x, FLOOR + 1, z, id, 0);
                if (Blocks189.door(id) || id == 175) {
                    place(x, FLOOR + 2, z, id, 8);
                }
                if (id == 26) {
                    place(x, FLOOR + 1, z - 1, id, 8);  // bed head north of the foot (facing south: meta 0)
                }
                slot++;
            }
            gridCount = slot;
            int south = ((gridCount + COLUMNS - 1) / COLUMNS) * SPACING + 6;
            arrangements(south);
        }

        private void arrangements(int z) {
            // Fence line with a gate and a post, nether fence crossing, cobblestone wall with a corner.
            for (int x = 1; x <= 6; x++) {
                place(x, FLOOR + 1, z, x == 4 ? 107 : 85, 0);
            }
            place(3, FLOOR + 1, z + 1, 85, 0);
            for (int x = 8; x <= 12; x++) {
                place(x, FLOOR + 1, z, 139, 0);
            }
            place(12, FLOOR + 1, z + 1, 139, 0);
            place(12, FLOOR + 1, z + 2, 139, 0);
            place(10, FLOOR + 2, z, 139, 0);
            // Glass pane cross and iron bars.
            for (int d = -2; d <= 2; d++) {
                place(16 + d, FLOOR + 1, z + 2, 102, 0);
                place(16, FLOOR + 1, z + 2 + d, 102, 0);
                place(21 + d, FLOOR + 1, z, 101, 0);
            }
            place(21, FLOOR + 1, z + 1, 101, 0);
            // Stairs: a straight run, an outer and an inner corner, and upside-down stairs.
            for (int x = 25; x <= 28; x++) {
                place(x, FLOOR + 1, z, 53, 3);   // facing north (meta 3 -> getFront(2))
            }
            place(29, FLOOR + 1, z, 53, 0);     // east, turns the run into a corner
            place(29, FLOOR + 1, z + 1, 53, 0);
            place(25, FLOOR + 1, z + 3, 67, 2); // facing south
            place(26, FLOOR + 1, z + 3, 67, 2);
            place(27, FLOOR + 1, z + 3, 67, 1); // west: inner/outer corner with the run
            place(27, FLOOR + 1, z + 4, 67, 1);
            place(30, FLOOR + 2, z + 3, 108, 4 | 3);
            // Slabs: bottom, top, a double slab, alternating.
            place(32, FLOOR + 1, z, 44, 0);
            place(33, FLOOR + 1, z, 44, 8);
            place(34, FLOOR + 1, z, 43, 0);
            place(35, FLOOR + 1, z, 126, 1);
            place(36, FLOOR + 1, z, 126, 9);
            place(32, FLOOR + 1, z + 1, 44, 0);
            place(33, FLOOR + 1, z + 1, 44, 0);
            // Redstone: a cross of wire with a torch, a repeater and a lamp; wire climbing a block.
            for (int d = -2; d <= 2; d++) {
                place(40 + d, FLOOR + 1, z + 2, 55, d == 0 ? 15 : 8);
                place(40, FLOOR + 1, z + 2 + d, 55, 4);
            }
            place(43, FLOOR + 1, z + 2, 76, 5);
            place(40, FLOOR + 1, z + 5, 93, 0);
            place(44, FLOOR + 1, z + 4, 1, 0);
            place(44, FLOOR + 2, z + 4, 55, 10);
            place(43, FLOOR + 1, z + 4, 55, 10);
            // Doors: a pair, one open.
            place(1, FLOOR + 1, z + 6, 64, 1);
            place(1, FLOOR + 2, z + 6, 64, 8);
            place(2, FLOOR + 1, z + 6, 64, 1 | 4);
            place(2, FLOOR + 2, z + 6, 64, 9);
            // Double plants.
            for (int variant = 0; variant < 6; variant++) {
                place(5 + variant, FLOOR + 1, z + 6, 175, variant);
                place(5 + variant, FLOOR + 2, z + 6, 175, 8);
            }
            // Rails: straight, curve, slope onto a block.
            place(13, FLOOR + 1, z + 6, 66, 0);
            place(13, FLOOR + 1, z + 7, 66, 8);
            place(14, FLOOR + 1, z + 7, 66, 1);
            place(15, FLOOR + 1, z + 7, 66, 2);
            place(16, FLOOR + 1, z + 7, 1, 0);
            // A pool: still water sources, with flowing water running out over the floor.
            for (int x = 19; x <= 23; x++) {
                for (int dz = 6; dz <= 9; dz++) {
                    place(x, FLOOR, z + dz, 9, 0);
                }
            }
            for (int level = 1; level <= 7; level++) {
                place(23 + level, FLOOR + 1, z + 7, 8, level);
            }
            place(19, FLOOR + 1, z + 7, 8, 8); // falling water above the pool
            // Lava pool and a flow.
            for (int x = 33; x <= 35; x++) {
                place(x, FLOOR, z + 7, 11, 0);
            }
            place(36, FLOOR + 1, z + 7, 10, 2);
            place(37, FLOOR + 1, z + 7, 10, 4);
            // Torch, ladder and vine on a wall; fire on netherrack and against planks; snow and carpet.
            for (int x = 40; x <= 45; x++) {
                place(x, FLOOR + 1, z + 9, 4, 0);
                place(x, FLOOR + 2, z + 9, 4, 0);
            }
            place(40, FLOOR + 1, z + 8, 50, 4);
            place(41, FLOOR + 1, z + 8, 65, 2);
            place(41, FLOOR + 2, z + 8, 65, 2);
            place(42, FLOOR + 2, z + 8, 106, 4);
            place(43, FLOOR + 1, z + 8, 69, 4);
            place(44, FLOOR + 1, z + 8, 77, 4);
            place(46, FLOOR + 1, z + 6, 87, 0);
            place(46, FLOOR + 2, z + 6, 51, 0);
            place(47, FLOOR + 1, z + 6, 5, 0);
            place(48, FLOOR + 1, z + 6, 51, 0);
            for (int layers = 0; layers < 8; layers++) {
                place(30 + layers, FLOOR + 1, z + 11, 78, layers);
                place(30 + layers, FLOOR + 1, z + 12, 171, layers * 2);
            }
        }

        private void place(int x, int y, int z, int id, int meta) {
            placed.put(key(x, y, z), (char) (id << 4 | meta));
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) z & 0xFFFFF) << 20 | ((long) y & 0xFFFFF);
        }

        private char state(int x, int y, int z) {
            if (y <= FLOOR) {
                Character placedState = placed.get(key(x, y, z));
                if (placedState != null) {
                    return placedState;
                }
                return y < FLOOR - 3 ? (char) 0 : (char) (1 << 4);
            }
            Character placedState = placed.get(key(x, y, z));
            return placedState == null ? 0 : placedState;
        }

        @Override
        public int minSectionY() {
            return 3;
        }

        @Override
        public int maxSectionY() {
            return 5;
        }

        @Override
        public void fill(int sectionX, int sectionY, int sectionZ, char[] ids, byte[] light) {
            int originX = sectionX * 16;
            int originY = sectionY * 16;
            int originZ = sectionZ * 16;
            int top = 16 * 6 + HALO;
            for (int z = -HALO; z < 16 + HALO; z++) {
                for (int x = -HALO; x < 16 + HALO; x++) {
                    int sky = 15;
                    for (int y = top; y >= originY - HALO; y--) {
                        char state = state(originX + x, y, originZ + z);
                        int id = state >> 4;
                        sky = Math.max(0, sky - Math.min(15, Blocks189.opacity(id)));
                        int localY = y - originY;
                        if (localY <= 15 + HALO) {
                            int index = BlockSource.index(x, localY, z);
                            ids[index] = state;
                            light[index] = (byte) (Blocks189.opaqueCube(id) ? 0 : sky << 4);
                        }
                    }
                }
            }
        }
    }

    private static void writePng(RgbaImage image, Path destination) throws IOException {
        BufferedImage buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        byte[] pixels = image.copyPixels();
        for (int index = 0; index < image.width() * image.height(); index++) {
            int offset = index * 4;
            buffered.setRGB(index % image.width(), index / image.width(), (pixels[offset + 3] & 0xFF) << 24
                    | (pixels[offset] & 0xFF) << 16 | (pixels[offset + 1] & 0xFF) << 8 | (pixels[offset + 2] & 0xFF));
        }
        if (!ImageIO.write(buffered, "png", destination.toFile())) {
            throw new IOException("No PNG writer available.");
        }
    }
}
