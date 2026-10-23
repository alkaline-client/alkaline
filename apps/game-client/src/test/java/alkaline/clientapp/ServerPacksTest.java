package alkaline.clientapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPacksTest {
    @Test
    void onlyWebAddressesAreFetched() {
        assertTrue(ServerPacks.downloadable("https://packs.example.net/pack.zip"));
        assertTrue(ServerPacks.downloadable("HTTP://example.net/p.zip"));
        assertFalse(ServerPacks.downloadable("file:///etc/passwd"));
        assertFalse(ServerPacks.downloadable("jar:file:/x.jar!/y"));
        assertFalse(ServerPacks.downloadable("level://world/resources.zip"));
        assertFalse(ServerPacks.downloadable("not a url"));
    }

    @Test
    void storedUnderTheHashOnlyWhenItIsOne() {
        String hash = "0123456789abcdef0123456789abcdef01234567";
        assertEquals(hash, ServerPacks.fileName(hash));
        assertEquals("legacy", ServerPacks.fileName("0123456789ABCDEF0123456789ABCDEF01234567"));
        assertEquals("legacy", ServerPacks.fileName(""));
        assertEquals("legacy", ServerPacks.fileName(null));
    }

    @Test
    void aStoredPackIsReusedOnlyWhileItsHashMatches(@TempDir Path folder) throws IOException {
        Path pack = folder.resolve("staging");
        Files.writeString(pack, "pack bytes");
        String hash = ServerPacks.sha1(pack);
        Files.move(pack, folder.resolve(hash));
        ServerPacks packs = new ServerPacks(folder);
        assertEquals(folder.resolve(hash), packs.cached(hash));
        Files.writeString(folder.resolve(hash), "changed");
        assertNull(packs.cached(hash));
        assertFalse(Files.exists(folder.resolve(hash)));
        assertNull(packs.cached("legacy"));
    }

    @Test
    void downloadsStopAtFiftyMegabytes(@TempDir Path folder) {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return length;
            }
        };
        IOException failure = assertThrows(IOException.class, () -> ServerPacks.copy(endless,
                folder.resolve("big"), -1, (received, total) -> { }, () -> false));
        assertTrue(failure.getMessage().contains(String.valueOf(ServerPacks.MAX_BYTES)));
    }

    @Test
    void copyingStopsWhenCancelled(@TempDir Path folder) {
        assertThrows(IOException.class, () -> ServerPacks.copy(new ByteArrayInputStream(new byte[10]),
                folder.resolve("x"), 10, (received, total) -> { }, () -> true));
    }

    @Test
    void onlyTheNewestTenPacksAreKept(@TempDir Path folder) throws IOException {
        for (int index = 0; index < 13; index++) {
            Path file = folder.resolve("pack" + index);
            Files.writeString(file, "x");
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L * (index + 1)));
        }
        new ServerPacks(folder).prune();
        try (var left = Files.list(folder)) {
            assertEquals(ServerPacks.KEPT, left.count());
        }
        assertFalse(Files.exists(folder.resolve("pack0")));
        assertTrue(Files.exists(folder.resolve("pack12")));
    }
}
