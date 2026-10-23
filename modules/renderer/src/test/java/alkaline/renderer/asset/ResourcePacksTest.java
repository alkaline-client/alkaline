package alkaline.renderer.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePacksTest {
    @TempDir
    Path folder;

    private void zip(String name, String... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(folder.resolve(name)))) {
            for (int index = 0; index < entries.length; index += 2) {
                out.putNextEntry(new ZipEntry(entries[index]));
                out.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    @Test
    void findsZipAndFolderPacksWithTheirMetadata() throws IOException {
        zip("§aRed PvP.zip", "pack.mcmeta", "{\"pack\":{\"pack_format\":1,\"description\":\"§cFast §rand red\"}}");
        zip("broken.zip", "nested/pack.mcmeta", "{}");
        Path directory = Files.createDirectories(folder.resolve("Plain"));
        Files.writeString(directory.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":2,\"description\":\"New\"}}");
        List<ResourcePacks.Pack> packs = ResourcePacks.scan(List.of(new ResourcePacks.Folder("test", folder)));
        assertEquals(2, packs.size());
        assertEquals("Plain", packs.get(0).name());
        assertFalse(packs.get(0).compatible());
        assertEquals("Red PvP", packs.get(1).name());
        assertEquals("Fast and red", packs.get(1).description());
        assertEquals("test/§aRed PvP.zip", packs.get(1).id());
        assertTrue(packs.get(1).compatible());
    }

    @Test
    void firstPackWinsAndListingMergesEveryLayer() throws IOException {
        zip("top.zip", "pack.mcmeta", "{\"pack\":{\"pack_format\":1}}", "assets/minecraft/a.txt", "top",
                "assets/minecraft/optifine/cit/x.properties", "items=1");
        zip("under.zip", "pack.mcmeta", "{\"pack\":{\"pack_format\":1}}", "assets/minecraft/a.txt", "under",
                "assets/minecraft/b.txt", "under");
        List<ResourcePacks.Pack> packs = ResourcePacks.scan(List.of(new ResourcePacks.Folder("t", folder)));
        ResourcePacks.Pack top = packs.stream().filter(p -> p.name().equals("top")).findFirst().orElseThrow();
        ResourcePacks.Pack under = packs.stream().filter(p -> p.name().equals("under")).findFirst().orElseThrow();
        boolean[] vanillaClosed = {false};
        AssetSource vanilla = new AssetSource() {
            @Override
            public InputStream open(String path) {
                return path.equals("assets/minecraft/c.txt") ? new ByteArrayInputStream("vanilla".getBytes()) : null;
            }

            @Override
            public String describe() {
                return "vanilla";
            }

            @Override
            public void close() {
                vanillaClosed[0] = true;
            }
        };
        try (AssetSource layered = ResourcePacks.layer(List.of(top, under), vanilla)) {
            assertEquals("top", read(layered, "assets/minecraft/a.txt"));
            assertEquals("under", read(layered, "assets/minecraft/b.txt"));
            assertEquals("vanilla", read(layered, "assets/minecraft/c.txt"));
            assertNull(layered.open("assets/minecraft/d.txt"));
            assertEquals(List.of("assets/minecraft/optifine/cit/x.properties"),
                    layered.list("assets/minecraft/optifine/"));
        }
        assertFalse(vanillaClosed[0]);
    }

    private static String read(AssetSource source, String path) throws IOException {
        try (InputStream input = source.open(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
