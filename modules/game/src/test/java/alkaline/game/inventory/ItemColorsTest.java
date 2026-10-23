package alkaline.game.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ItemColorsTest {
    private static final ItemColors.Custom PACK = new ItemColors.Custom() {
        @Override
        public int potion(int effect) {
            return effect == 0 ? 0x00FF00 : effect == 10 ? 0x112233 : -1;
        }

        @Override
        public int egg(int entity, int layer) {
            return entity == 50 ? (layer == 0 ? 0xFF0000 : 0x0000FF) : -1;
        }
    };

    @Test
    void vanillaColorsWithoutAPack() {
        assertEquals(0x385DC6, ItemColors.tint(new ItemStack(373, 1, 0, null), 0, 0, 0));
        assertEquals(0xCD5CAB, ItemColors.tint(new ItemStack(373, 1, 8193, null), 0, 0, 0));
        assertEquals(894731, ItemColors.tint(new ItemStack(383, 1, 50, null), 0, 0, 0));
    }

    @Test
    void potionColorsFollowThePack() {
        assertEquals(0x00FF00, ItemColors.tint(new ItemStack(373, 1, 0, null), 0, 0, 0, PACK));
        assertEquals(0x112233, ItemColors.tint(new ItemStack(373, 1, 8193, null), 0, 0, 0, PACK));
        // An effect the pack leaves out keeps vanilla's color (speed).
        assertEquals(8171462, ItemColors.tint(new ItemStack(373, 1, 8194, null), 0, 0, 0, PACK));
        assertEquals(-1, ItemColors.tint(new ItemStack(373, 1, 8193, null), 1, 0, 0, PACK));
    }

    @Test
    void spawnEggColorsFollowThePack() {
        assertEquals(0xFF0000, ItemColors.tint(new ItemStack(383, 1, 50, null), 0, 0, 0, PACK));
        assertEquals(0x0000FF, ItemColors.tint(new ItemStack(383, 1, 50, null), 1, 0, 0, PACK));
        assertEquals(15771042, ItemColors.tint(new ItemStack(383, 1, 90, null), 0, 0, 0, PACK));
    }

    @Test
    void lilyPadItemUsesTheRenderColorNotTheBlocksFoliageMultiplier() {
        // ItemLilyPad.getColorFromItemStack = BlockLilyPad.getRenderColor = 7455580; colorMultiplier is 2129968.
        assertEquals(0x71C35C, ItemColors.tint(new ItemStack(111, 1, 0, null), 0, 0, 0));
        assertEquals(0x71C35C, ItemColors.tint(new ItemStack(111, 1, 0, null), 0, 0, 0, PACK));
    }
}
