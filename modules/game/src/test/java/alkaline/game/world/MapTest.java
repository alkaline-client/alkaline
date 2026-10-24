package alkaline.game.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import alkaline.game.net.ServerSession;
import org.junit.jupiter.api.Test;

class MapTest {
    @Test
    void baseColorsScaleByTheFourBrightnessLevels() {
        // Grass (index 1, 0x7FB238): levels 180, 220, 255, 135 of 255.
        assertEquals(0xFF000000 | (0x7F * 180 / 255) << 16 | (0xB2 * 180 / 255) << 8 | 0x38 * 180 / 255,
                MapColors.argb((byte) (1 << 2), 0));
        assertEquals(0xFF000000 | (0x7F * 220 / 255) << 16 | (0xB2 * 220 / 255) << 8 | 0x38 * 220 / 255,
                MapColors.argb((byte) (1 << 2 | 1), 0));
        assertEquals(0xFF7FB238, MapColors.argb((byte) (1 << 2 | 2), 0));
        assertEquals(0xFF000000 | (0x7F * 135 / 255) << 16 | (0xB2 * 135 / 255) << 8 | 0x38 * 135 / 255,
                MapColors.argb((byte) (1 << 2 | 3), 0));
        // Snow, full brightness, is opaque white; water (12) uses 0x4040FF.
        assertEquals(0xFFFFFFFF, MapColors.argb((byte) (8 << 2 | 2), 0));
        assertEquals(0xFF4040FF, MapColors.argb((byte) (12 << 2 | 2), 0));
    }

    @Test
    void theClearColorIsAFaintCheckerboard() {
        assertEquals(16 << 24, MapColors.argb((byte) 0, 0));
        assertEquals(24 << 24, MapColors.argb((byte) 3, 1));
        assertEquals(24 << 24, MapColors.argb((byte) 0, 128)); // the next row starts on the other square
        assertEquals(16 << 24, MapColors.argb((byte) 0, 129));
        // Indices past the table read as clear too.
        assertEquals(16 << 24, MapColors.argb((byte) (50 << 2), 0));
    }

    @Test
    void patchesLandAtTheirOffsetAndDecorationsAreReplaced() {
        MapStore store = new MapStore();
        assertNull(store.get(7));
        byte[] patch = {1, 2, 3, 4, 5, 6};
        store.apply(new ServerSession.MapData(7, 3, new int[]{1 << 24}, 3, 2, 10, 20, patch));
        MapStore.Data data = store.get(7);
        assertEquals(1, data.version());
        assertEquals(1, data.colors()[10 + 20 * 128]);
        assertEquals(3, data.colors()[12 + 20 * 128]);
        assertEquals(4, data.colors()[10 + 21 * 128]);
        assertEquals(6, data.colors()[12 + 21 * 128]);
        assertEquals(0, data.colors()[9 + 20 * 128]);
        assertArrayEquals(new int[]{1 << 24}, data.icons());
        store.apply(new ServerSession.MapData(7, 3, new int[0], 0, 0, 0, 0, null));
        assertArrayEquals(new int[0], data.icons());
        assertEquals(1, data.colors()[10 + 20 * 128]);
        assertEquals(2, data.version());
    }
}
