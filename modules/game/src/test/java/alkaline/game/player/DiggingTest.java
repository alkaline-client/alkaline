package alkaline.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alkaline.game.data.nbt.NBTTagCompound;
import alkaline.game.data.nbt.NBTTagList;
import alkaline.game.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Dig times the 1.8.9 client computes (the figures match the tables players know: stone 1.15 s with wood...). */
class DiggingTest {
    private static final int STONE = 1;
    private static final int DIRT = 3;
    private static final int OBSIDIAN = 49;
    private static final int IRON_ORE = 15;
    private static final int WOODEN_PICKAXE = 270;
    private static final int STONE_PICKAXE = 274;
    private static final int DIAMOND_PICKAXE = 278;

    private static ItemStack tool(int id) {
        return new ItemStack(id, 1, 0, null);
    }

    /** Ticks to dig a block from the first hit, as PlayerControllerMP counts them (progress added per tick). */
    private static int ticks(ItemStack held, int block) {
        float perTick = Digging.progress(Digging.breakSpeed(held, block, -1, -1, false, true), block,
                Digging.canHarvest(held, block));
        int ticks = 0;
        float damage = 0f;
        while (damage < 1f && ticks < 100_000) {
            damage += perTick;
            ticks++;
        }
        return ticks;
    }

    @Test
    void stoneByHandTakesSevenAndAHalfSeconds() {
        // 150 ticks in exact arithmetic; vanilla's float sum of 1/1.5/100 needs one more.
        assertEquals(151, ticks(null, STONE));
        assertFalse(Digging.canHarvest(null, STONE));
    }

    @Test
    void picksSpeedUpStoneByTier() {
        assertEquals(23, ticks(tool(WOODEN_PICKAXE), STONE));  // 1.15 s
        assertEquals(12, ticks(tool(STONE_PICKAXE), STONE));   // 0.6 s
        assertEquals(6, ticks(tool(DIAMOND_PICKAXE), STONE));  // 0.3 s
    }

    @Test
    void obsidianNeedsADiamondPickaxe() {
        assertEquals(188, ticks(tool(DIAMOND_PICKAXE), OBSIDIAN)); // 9.4 s
        assertFalse(Digging.canHarvest(tool(274), OBSIDIAN));
        assertTrue(Digging.canHarvest(tool(DIAMOND_PICKAXE), OBSIDIAN));
    }

    @Test
    void ironOreHarvestsFromStoneUp() {
        assertFalse(Digging.canHarvest(tool(WOODEN_PICKAXE), IRON_ORE));
        assertTrue(Digging.canHarvest(tool(STONE_PICKAXE), IRON_ORE));
        // Without the right tool the block still breaks, at a hundred instead of thirty ticks of speed per point.
        float wood = Digging.breakSpeed(tool(WOODEN_PICKAXE), IRON_ORE, -1, -1, false, true);
        assertEquals(2f / 3f / 100f, Digging.progress(wood, IRON_ORE, false), 1e-7);
        float stone = Digging.breakSpeed(tool(STONE_PICKAXE), IRON_ORE, -1, -1, false, true);
        assertEquals(4f / 3f / 30f, Digging.progress(stone, IRON_ORE, true), 1e-7);
    }

    @Test
    void dirtWithAndWithoutAShovel() {
        assertEquals(15, ticks(null, DIRT));         // 0.75 s
        assertEquals(3, ticks(tool(256), DIRT));     // iron shovel: 0.15 s
        assertEquals(1f, Digging.speed(tool(WOODEN_PICKAXE), DIRT));
    }

    @Test
    void efficiencyAddsItsSquarePlusOne() {
        NBTTagList list = new NBTTagList();
        NBTTagCompound efficiency = new NBTTagCompound();
        efficiency.setShort("id", (short) 32);
        efficiency.setShort("lvl", (short) 5);
        list.appendTag(efficiency);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("ench", list);
        ItemStack enchanted = new ItemStack(DIAMOND_PICKAXE, 1, 0, tag);
        assertEquals(8f + 26f, Digging.breakSpeed(enchanted, STONE, -1, -1, false, true));
        assertEquals(2, ticks(enchanted, STONE));
        // Efficiency does nothing when the tool does not speed the block up.
        assertEquals(1f, Digging.breakSpeed(enchanted, DIRT, -1, -1, false, true));
    }

    @Test
    void potionsWaterAndAirChangeTheSpeed() {
        ItemStack pickaxe = tool(DIAMOND_PICKAXE);
        assertEquals(8f * 1.4f, Digging.breakSpeed(pickaxe, STONE, 1, -1, false, true), 1e-4);
        assertEquals(8f * 0.3f, Digging.breakSpeed(pickaxe, STONE, -1, 0, false, true), 1e-4);
        assertEquals(8f * 0.09f, Digging.breakSpeed(pickaxe, STONE, -1, 1, false, true), 1e-4);
        assertEquals(8f / 5f, Digging.breakSpeed(pickaxe, STONE, -1, -1, true, true), 1e-4);
        assertEquals(8f / 25f, Digging.breakSpeed(pickaxe, STONE, -1, -1, true, false), 1e-4);
    }

    @Test
    void unbreakableBlocksNeverProgress() {
        assertEquals(0f, Digging.progress(8f, 7, true));   // bedrock
        assertEquals(0f, Digging.progress(8f, 166, true)); // barrier
        assertEquals(100f, Digging.hardness(8));            // water
    }

    @Test
    void swordsShearsAndAxes() {
        assertEquals(15f, Digging.speed(tool(267), 30));   // a sword on cobweb
        assertEquals(1.5f, Digging.speed(tool(267), 18));  // leaves
        assertEquals(15f, Digging.speed(tool(359), 18));   // shears on leaves
        assertEquals(5f, Digging.speed(tool(359), 35));    // wool
        assertEquals(6f, Digging.speed(tool(258), 17));    // iron axe on a log
        assertTrue(Digging.canHarvest(tool(359), 30));
        assertTrue(Digging.canHarvest(null, 17));          // wood needs no tool
    }

    @Test
    void hardnessMatchesTheVanillaTable() {
        assertEquals(1.5f, Digging.hardness(1));
        assertEquals(0.6f, Digging.hardness(2));
        assertEquals(50f, Digging.hardness(49));
        assertEquals(22.5f, Digging.hardness(130));
        assertEquals(1.5f, Digging.hardness(109));  // stone brick stairs follow stone bricks
        assertEquals(2f, Digging.hardness(139));    // cobblestone wall
        assertEquals(0f, Digging.hardness(31));     // tall grass
        assertEquals(3f, Digging.hardness(197));    // dark oak door
        assertEquals(-1f, Digging.hardness(137));   // command block
        assertEquals(0f, Digging.hardness(4000));   // unknown
    }
}
