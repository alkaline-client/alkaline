package alkaline.renderer.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alkaline.renderer.asset.AssetSource;
import alkaline.renderer.asset.ZipAssetSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Connected textures meshed with the real 1.8.9 models (when the game jar is installed) under small test packs: pane
 * walls, iron bars, overlays, ctm_compact, height and biome conditions, and emissive textures.
 */
class ConnectedTexturesJarTest {
    private static final String CTM = "assets/minecraft/mcpatcher/ctm/";
    private static final int GLASS_PANE = 102;
    private static final int IRON_BARS = 101;
    private static final int STONE = 1 << 4;
    private static final int DIRT = 3 << 4;
    private static Path jar;

    @BeforeAll
    static void findJar() {
        jar = Path.of(System.getProperty("user.home"), ".minecraft", "versions", "1.8.9", "1.8.9.jar");
        assumeTrue(Files.isRegularFile(jar), "Minecraft 1.8.9 jar not installed");
    }

    /** An in-memory resource pack. */
    private static final class Pack implements AssetSource {
        final Map<String, byte[]> files = new TreeMap<>();

        Pack text(String path, String text) {
            files.put(path, text.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        /** Tiles {@code first..last} beside a properties file, each one distinct opaque color. */
        Pack tiles(String directory, int first, int last) {
            for (int tile = first; tile <= last; tile++) {
                png(directory + tile + ".png", 0xFF000000 | tile * 0x050301 + 0x203040);
            }
            return this;
        }

        Pack png(String path, int argb) {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setRGB(x, y, argb);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            files.put(path, out.toByteArray());
            return this;
        }

        @Override
        public InputStream open(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public List<String> list(String prefix) {
            return files.keySet().stream().filter(path -> path.startsWith(prefix)).toList();
        }

        @Override
        public String describe() {
            return "test pack";
        }
    }

    private static BlockPalette palette(Pack pack) throws IOException {
        try (ZipAssetSource vanilla = new ZipAssetSource(jar)) {
            return BlockPalette.load(pack == null ? vanilla : AssetSource.layered(pack, vanilla));
        }
    }

    /** One decoded quad: its first vertex's direction field, center (section-local blocks), layer and block light. */
    private record Quad(int face, float x, float y, float z, int layer, int blockLight, int range) {
    }

    private static List<Quad> quads(ChunkMesh mesh) {
        List<Quad> quads = new ArrayList<>();
        int[] words = mesh.words();
        for (int range = 0; range < ChunkMesh.RANGES; range++) {
            for (int quad = mesh.rangeStart(range); quad < mesh.rangeStart(range) + mesh.rangeCount(range); quad++) {
                float x = 0;
                float y = 0;
                float z = 0;
                int base = quad * ChunkMesh.INTS_PER_QUAD;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int w0 = words[base + vertex * ChunkMesh.INTS_PER_VERTEX];
                    int w1 = words[base + vertex * ChunkMesh.INTS_PER_VERTEX + 1];
                    x += (w0 & 0x3FFF) / 512f - 8f;
                    y += (w0 >> 14 & 0x3FFF) / 512f - 8f;
                    z += (w1 & 0x3FFF) / 512f - 8f;
                }
                int w0 = words[base];
                int w1 = words[base + 1];
                int w2 = words[base + 2];
                int layer = (w1 >>> 27) << 11 | w2 >> 13 & 0x7FF;
                quads.add(new Quad(w0 >>> 28, x / 4, y / 4, z / 4, layer, w2 >>> 24, range));
            }
        }
        return quads;
    }

    private static ChunkMesh mesh(BlockPalette palette, char[] ids, byte[] biomes) {
        byte[] light = new byte[BlockSource.VOLUME];
        Arrays.fill(light, (byte) 0xF0);
        return new ChunkMesher().mesh(0, 0, 0, ids, light, biomes, palette, true);
    }

    private static String texture(BlockPalette palette, Quad quad) {
        return palette.textures().get(quad.layer());
    }

    private static boolean inCell(Quad quad, int x, int y, int z) {
        return quad.x() >= x && quad.x() <= x + 1 && quad.y() >= y && quad.y() <= y + 1 && quad.z() >= z
                && quad.z() <= z + 1;
    }

    // ---- panes ------------------------------------------------------------------------------------------------

    /** A 3x3 wall of glass panes running north-south at x = 5. */
    private static char[] paneWall() {
        char[] ids = new char[BlockSource.VOLUME];
        for (int y = 4; y <= 6; y++) {
            for (int z = 4; z <= 6; z++) {
                ids[BlockSource.index(5, y, z)] = (char) (GLASS_PANE << 4);
            }
        }
        return ids;
    }

    private static Pack glassPack() {
        // Xe Lite's layout: ctm/glass/glass.properties with method and tiles only, matching blocks/glass by name.
        return new Pack().text(CTM + "glass/glass.properties", "method=ctm\ntiles=0-46\n").tiles(CTM + "glass/", 0, 46);
    }

    @Test
    void paneWallJoinsIntoOneConnectedSheet() throws IOException {
        BlockPalette palette = palette(glassPack());
        List<Quad> quads = quads(mesh(palette, paneWall(), null));
        // The middle pane's broad west face: all eight neighbors join, tile 26.
        Quad middle = quads.stream().filter(q -> inCell(q, 5, 5, 5) && q.face() == 0 && Math.abs(q.x() - 5 - 7 / 16f) < 1e-3)
                .findFirst().orElseThrow();
        assertEquals(CTM + "glass/26.png", texture(palette, middle));
        // A corner pane (bottom, north end): neighbors up, south and up-south only.
        Quad corner = quads.stream().filter(q -> inCell(q, 5, 4, 4) && q.face() == 0 && q.x() < 5.5f).findFirst().orElseThrow();
        assertTrue(texture(palette, corner).startsWith(CTM + "glass/"), texture(palette, corner));
    }

    @Test
    void paneWallHidesTheSeamsBetweenPanes() throws IOException {
        List<Quad> vanilla = quads(mesh(palette(null), paneWall(), null));
        List<Quad> connected = quads(mesh(palette(glassPack()), paneWall(), null));
        // Up faces (code 3) inside the wall: vanilla draws them between stacked panes, connected textures do not.
        long vanillaInner = vanilla.stream().filter(q -> q.face() == 3 && Math.abs(q.y() - 5) < 1e-3).count();
        long connectedInner = connected.stream().filter(q -> q.face() == 3 && Math.abs(q.y() - 5) < 1e-3).count();
        assertTrue(vanillaInner > 0);
        assertEquals(0, connectedInner);
        // The wall's top keeps its edge.
        assertTrue(connected.stream().anyMatch(q -> q.face() == 3 && Math.abs(q.y() - 7) < 1e-3));
        // Edge quads between neighbors in the wall (north and south faces at z = 5 and 6) go too.
        assertTrue(vanilla.stream().anyMatch(q -> (q.face() == 4 || q.face() == 5) && Math.abs(q.z() - 5) < 1e-3));
        assertEquals(0, connected.stream().filter(q -> (q.face() == 4 || q.face() == 5) && Math.abs(q.z() - 5) < 1e-3).count());
    }

    @Test
    void ironBarsTakeRandomTiles() throws IOException {
        Pack pack = new Pack().text(CTM + "bars/iron_bars.properties", "method=random\ntiles=0-3\n").tiles(CTM + "bars/", 0, 3);
        BlockPalette palette = palette(pack);
        char[] ids = new char[BlockSource.VOLUME];
        for (int z = 0; z < 16; z++) {
            ids[BlockSource.index(5, 5, z)] = (char) (IRON_BARS << 4);
        }
        List<Quad> quads = quads(mesh(palette, ids, null));
        long tiled = quads.stream().filter(q -> texture(palette, q).startsWith(CTM + "bars/")).count();
        long distinct = quads.stream().map(q -> texture(palette, q)).filter(t -> t.startsWith(CTM + "bars/")).distinct().count();
        assertTrue(tiled > 0);
        assertTrue(distinct > 1, "random tiles vary along the row");
    }

    // ---- cube faces: overlay, compact, conditions, emissive ---------------------------------------------------

    @Test
    void overlayDrawsTheBorderOfANeighboringBlock() throws IOException {
        Pack pack = new Pack().text(CTM + "overlay/stone.properties",
                "method=overlay\nmatchBlocks=stone\nconnectBlocks=dirt\ntiles=0-16\n").tiles(CTM + "overlay/", 0, 16);
        BlockPalette palette = palette(pack);
        char[] ids = new char[BlockSource.VOLUME];
        ids[BlockSource.index(5, 5, 5)] = STONE;
        ids[BlockSource.index(4, 5, 5)] = DIRT;
        List<Quad> quads = quads(mesh(palette, ids, null));
        // On the stone's top face the dirt is to the left (west): tile 9; on its north face it is to the right: tile 7.
        List<String> top = quads.stream().filter(q -> inCell(q, 5, 5, 5) && q.face() == 3).map(q -> texture(palette, q)).toList();
        assertTrue(top.contains("blocks/stone"));
        assertTrue(top.contains(CTM + "overlay/9.png"), top.toString());
        List<String> north = quads.stream().filter(q -> inCell(q, 5, 5, 5) && q.face() == 4).map(q -> texture(palette, q)).toList();
        assertTrue(north.contains(CTM + "overlay/7.png"), north.toString());
        // Without a dirt neighbor there is no overlay.
        ids[BlockSource.index(4, 5, 5)] = 0;
        assertTrue(quads(mesh(palette, ids, null)).stream().noneMatch(q -> texture(palette, q).startsWith(CTM + "overlay/")));
    }

    @Test
    void compactSplitsAFaceIntoQuarterTiles() throws IOException {
        Pack pack = new Pack().text(CTM + "compact/stone.properties", "method=ctm_compact\nmatchBlocks=stone\n")
                .tiles(CTM + "compact/", 0, 4);
        BlockPalette palette = palette(pack);
        char[] ids = new char[BlockSource.VOLUME];
        ids[BlockSource.index(5, 5, 5)] = STONE;
        ids[BlockSource.index(6, 5, 5)] = STONE;
        List<Quad> top = quads(mesh(palette, ids, null)).stream().filter(q -> inCell(q, 5, 5, 5) && q.face() == 3
                && q.x() < 6).toList();
        // CTM index 1 (a neighbor to the east): west half tile 0, east half tile 3, four quarters in all.
        assertEquals(4, top.size());
        for (Quad quad : top) {
            String expected = CTM + "compact/" + (quad.x() < 5.5f ? 0 : 3) + ".png";
            assertEquals(expected, texture(palette, quad));
        }
    }

    @Test
    void heightAndBiomeConditionsChooseTheBlocksTheyCover() throws IOException {
        Pack pack = new Pack()
                .text(CTM + "a/low.properties", "method=fixed\nmatchBlocks=stone\nheights=0-4\ntiles=low\n")
                .text(CTM + "b/desert.properties", "method=fixed\nmatchBlocks=stone\nbiomes=desert\ntiles=sand\n")
                .png(CTM + "a/low.png", 0xFF102030).png(CTM + "b/sand.png", 0xFFE0D090);
        BlockPalette palette = palette(pack);
        assertTrue(palette.needsBiomes());
        char[] ids = new char[BlockSource.VOLUME];
        ids[BlockSource.index(5, 3, 5)] = STONE;
        ids[BlockSource.index(5, 10, 5)] = STONE;
        byte[] plains = new byte[BlockSource.COLUMNS];
        Arrays.fill(plains, (byte) 1);
        List<Quad> quads = quads(mesh(palette, ids, plains));
        assertTrue(quads.stream().filter(q -> inCell(q, 5, 3, 5)).allMatch(q -> texture(palette, q).equals(CTM + "a/low.png")));
        assertTrue(quads.stream().filter(q -> inCell(q, 5, 10, 5)).allMatch(q -> texture(palette, q).equals("blocks/stone")));
        byte[] desert = new byte[BlockSource.COLUMNS];
        Arrays.fill(desert, (byte) 2);
        List<Quad> hot = quads(mesh(palette, ids, desert));
        // The height rule comes first by path; above it the biome rule applies.
        assertTrue(hot.stream().filter(q -> inCell(q, 5, 3, 5)).allMatch(q -> texture(palette, q).equals(CTM + "a/low.png")));
        assertTrue(hot.stream().filter(q -> inCell(q, 5, 10, 5)).allMatch(q -> texture(palette, q).equals(CTM + "b/sand.png")));
    }

    @Test
    void emissiveTexturesDrawAgainAtFullBlockLight() throws IOException {
        Pack pack = new Pack().text("assets/minecraft/optifine/emissive.properties", "suffix.emissive=_e\n")
                .png("assets/minecraft/textures/blocks/stone_e.png", 0x80FFFFFF);
        BlockPalette palette = palette(pack);
        char[] ids = new char[BlockSource.VOLUME];
        ids[BlockSource.index(5, 5, 5)] = STONE;
        byte[] light = new byte[BlockSource.VOLUME];
        // Dark: no sky, no block light.
        List<Quad> quads = quads(new ChunkMesher().mesh(0, 0, 0, ids, light, null, palette, true));
        List<Quad> glowing = quads.stream().filter(q -> texture(palette, q).equals("blocks/stone_e")).toList();
        assertEquals(6, glowing.size());
        assertTrue(glowing.stream().allMatch(q -> q.blockLight() == 240));
        assertTrue(glowing.stream().allMatch(q -> q.range() == ChunkMesh.range(MaterialPass.CUTOUT, q.face())));
        assertEquals(6, quads.stream().filter(q -> texture(palette, q).equals("blocks/stone")).count());
    }
}
