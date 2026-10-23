package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alkaline.renderer.asset.ZipAssetSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Compiles the real 1.8.9 models when the game jar is installed (skipped otherwise). */
class BlockPaletteJarTest {
    private static BlockPalette palette;

    @BeforeAll
    static void load() throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), ".minecraft", "versions", "1.8.9", "1.8.9.jar");
        assumeTrue(Files.isRegularFile(jar), "Minecraft 1.8.9 jar not installed");
        try (ZipAssetSource assets = new ZipAssetSource(jar)) {
            long start = System.nanoTime();
            palette = BlockPalette.load(assets);
            System.out.printf("Palette: %d models, %d states, %d textures in %.0f ms%n", palette.modelCount(),
                    palette.stateCount(), palette.textures().size(), (System.nanoTime() - start) / 1e6);
        }
    }

    private static int choice(int state) {
        int entry = palette.stateBase[state];
        assertTrue(entry >= 0, "state " + (state >> 4) + ":" + (state & 15) + " has no model");
        return palette.entryFirstChoice[entry];
    }

    private static int model(int state) {
        return palette.choiceModel[choice(state)];
    }

    @Test
    void cubesAreRecognized() {
        assertTrue(palette.modelCube[model(VanillaBlocks.STONE)]);
        assertTrue(palette.modelCube[model(VanillaBlocks.OAK_LEAVES)]);
        assertTrue(palette.modelCube[model(VanillaBlocks.OAK_LOG)]);
        assertTrue(palette.modelCube[model(VanillaBlocks.GRASS)]);
        assertFalse(palette.modelCube[model(VanillaBlocks.TALL_GRASS)]);
    }

    @Test
    void leavesAreCutoutTintedAndTransparent() {
        int appearance = palette.choiceAppearance[choice(VanillaBlocks.OAK_LEAVES) * 6 + 3];
        int layer = palette.appearanceLayer[appearance];
        assertEquals("blocks/leaves_oak", palette.textures().get(layer));
        assertFalse(palette.textureOpaque(layer));
        assertEquals(BlockPalette.FOLIAGE_COLOR, palette.appearanceTint[appearance]);
    }

    @Test
    void grassSidesCarryATintedOverlay() {
        int choice = choice(VanillaBlocks.GRASS);
        int top = palette.choiceAppearance[choice * 6 + 3];
        assertEquals("blocks/grass_top", palette.textures().get(palette.appearanceLayer[top]));
        assertEquals(BlockPalette.GRASS_COLOR, palette.appearanceTint[top]);
        int side = palette.choiceAppearance[choice * 6 + 4];
        assertEquals("blocks/grass_side", palette.textures().get(palette.appearanceLayer[side]));
        assertEquals(BlockPalette.NO_TINT, palette.appearanceTint[side]);
        assertEquals("blocks/grass_side_overlay", palette.textures().get(palette.overlayLayer[side]));
        assertEquals(BlockPalette.GRASS_COLOR, palette.overlayTint[side]);
    }

    @Test
    void tallGrassIsATintedFlatLitCross() {
        int model = model(VanillaBlocks.TALL_GRASS);
        assertFalse(palette.modelAo[model]);
        assertEquals(4, palette.modelQuadCount[model]);
        int quad = palette.modelQuadStart[model];
        assertTrue(palette.quadTinted[quad]);
        assertEquals("blocks/tallgrass", palette.textures().get(palette.quadLayer[quad]));
        assertEquals(BlockPalette.GRASS_COLOR, palette.entryTint[palette.stateBase[VanillaBlocks.TALL_GRASS]]);
    }

    @Test
    void neighborDependentBlocksResolve() {
        // Every stair shape, fence connection and door combination has a model.
        for (int extra = 0; extra < 5; extra++) {
            assertTrue(palette.entryChoiceCount[palette.stateBase[53 << 4] + extra] > 0, "stairs shape " + extra);
        }
        for (int extra = 0; extra < 16; extra++) {
            assertTrue(palette.entryChoiceCount[palette.stateBase[85 << 4] + extra] > 0, "fence " + extra);
        }
        for (int meta = 0; meta < 16; meta++) {
            int base = palette.stateBase[64 << 4 | meta];
            for (int extra = 0; extra < 17; extra++) {
                assertTrue(palette.entryChoiceCount[base + extra] > 0, "door " + meta + "/" + extra);
            }
        }
    }

    @Test
    void everyKnownModelStateResolves() {
        StringBuilder missing = new StringBuilder();
        for (int id = 0; id < alkaline.renderer.world.vanilla.Blocks189.COUNT; id++) {
            if (!alkaline.renderer.world.vanilla.Blocks189.known(id)
                    || alkaline.renderer.world.vanilla.Blocks189.render(id)
                    != alkaline.renderer.world.vanilla.Blocks189.Render.MODEL) {
                continue;
            }
            if (palette.stateBase[id << 4] < 0) {
                missing.append(alkaline.renderer.world.vanilla.Blocks189.name(id)).append(' ');
            }
        }
        assertEquals("", missing.toString().trim());
    }

    @Test
    void meshedLeavesSampleTheLeavesLayer() {
        char[] ids = new char[BlockSource.VOLUME];
        byte[] light = new byte[BlockSource.VOLUME];
        java.util.Arrays.fill(light, (byte) 0xF0);
        ids[BlockSource.index(5, 5, 5)] = VanillaBlocks.OAK_LEAVES;
        ids[BlockSource.index(5, 4, 5)] = VanillaBlocks.GRASS;
        ChunkMesh mesh = new ChunkMesher().mesh(0, 0, 0, ids, light, palette, true);
        java.util.Set<String> textures = new java.util.TreeSet<>();
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            int w2 = mesh.words()[quad * ChunkMesh.INTS_PER_QUAD + 2];
            textures.add(palette.textures().get(w2 >> 13 & 0x7FF) + "@" + (w2 >>> 24));
        }
        System.out.println("Meshed textures: " + textures);
        assertTrue(textures.stream().anyMatch(name -> name.startsWith("blocks/leaves_oak@")));
    }

    @Test
    void portalIsAVerticalPlane() {
        char[] ids = new char[BlockSource.VOLUME];
        byte[] light = new byte[BlockSource.VOLUME];
        java.util.Arrays.fill(light, (byte) 0xF0);
        ids[BlockSource.index(5, 5, 5)] = VanillaBlocks.state(90, 0);
        ChunkMesh mesh = new ChunkMesher().mesh(0, 0, 0, ids, light, palette, true);
        assertTrue(mesh.quadCount() > 0);
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            StringBuilder line = new StringBuilder("portal quad " + quad + ":");
            float minY = 99;
            float maxY = -99;
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = quad * ChunkMesh.INTS_PER_QUAD + vertex * ChunkMesh.INTS_PER_VERTEX;
                int w0 = mesh.words()[base];
                int w1 = mesh.words()[base + 1];
                float x = (w0 & 0x3FFF) / 512f - 8f;
                float y = (w0 >> 14 & 0x3FFF) / 512f - 8f;
                float z = (w1 & 0x3FFF) / 512f - 8f;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                line.append(String.format(" (%.3f %.3f %.3f)", x, y, z));
            }
            System.out.println(line);
            assertEquals(1.0f, maxY - minY, 1e-3f, "portal planes span the block's height");
        }
    }

    @Test
    void loneTripwireIsDrawn() {
        char[] ids = new char[BlockSource.VOLUME];
        byte[] light = new byte[BlockSource.VOLUME];
        java.util.Arrays.fill(light, (byte) 0xF0);
        ids[BlockSource.index(5, 5, 5)] = VanillaBlocks.state(132, 0);
        ids[BlockSource.index(5, 4, 5)] = VanillaBlocks.STONE;
        ChunkMesh mesh = new ChunkMesher().mesh(0, 0, 0, ids, light, palette, true);
        System.out.println("tripwire translucent quads: " + mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE)
                + " entry " + palette.stateBase[132 << 4]);
        assertTrue(mesh.rangeCount(ChunkMesh.TRANSLUCENT_RANGE) > 0);
    }
}
