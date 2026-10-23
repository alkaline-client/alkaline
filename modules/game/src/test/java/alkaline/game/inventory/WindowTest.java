package alkaline.game.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowTest {
    private static ItemStack stack(int id, int count) {
        return new ItemStack(id, count, 0, null);
    }

    private static ItemStack stack(int id, int count, int damage) {
        return new ItemStack(id, count, damage, null);
    }

    @Test
    void windowTypesOpenTheirVanillaScreens() {
        Inventory player = new Inventory();
        assertEquals(Window.Type.CHEST, Window.open(1, "minecraft:chest", "{}", 54, player, false).type());
        assertEquals(54 + 36, Window.open(1, "minecraft:chest", "{}", 54, player, false).size());
        assertEquals(Window.Type.CHEST, Window.open(1, "minecraft:container", "{}", 27, player, false).type());
        assertEquals(Window.Type.DISPENSER, Window.open(1, "minecraft:dropper", "{}", 9, player, false).type());
        assertEquals(10 + 36, Window.open(1, "minecraft:crafting_table", "{}", 0, player, false).size());
        assertEquals(2 + 36, Window.open(1, "EntityHorse", "{}", 2, player, false).size());
        assertEquals(17 + 36, Window.open(1, "EntityHorse", "{}", 17, player, false).size());
        assertNull(Window.open(1, "minecraft:command_block", "{}", 0, player, false));
    }

    @Test
    void thePlayersSlotsAreTheInventorysStorage() {
        Inventory player = new Inventory();
        player.set(Inventory.FIRST_HOTBAR, stack(1, 5));
        Window chest = Window.open(1, "minecraft:chest", "{}", 27, player, false);
        assertEquals(stack(1, 5), chest.slot(27 + 27)); // the first hotbar slot below the chest
        chest.set(27, stack(4, 3));
        assertEquals(stack(4, 3), player.slot(Inventory.FIRST_MAIN));
    }

    @Test
    void shiftClickMovesBetweenTheChestAndThePlayer() {
        Inventory player = new Inventory();
        Window chest = Window.open(1, "minecraft:chest", "{}", 27, player, false);
        chest.set(0, stack(1, 64));
        chest.click(0, 0, Container.QUICK_MOVE, false);
        assertNull(chest.slot(0));
        assertEquals(stack(1, 64), player.slot(44)); // the last hotbar slot first (reverse merge)
        chest.click(27 + 35, 0, Container.QUICK_MOVE, false);
        assertEquals(stack(1, 64), chest.slot(0));
    }

    @Test
    void furnaceShiftClickSortsInputAndFuel() {
        Inventory player = new Inventory();
        Window furnace = Window.open(2, "minecraft:furnace", "{}", 3, player, false);
        furnace.set(3, stack(15, 10)); // iron ore
        furnace.set(4, stack(263, 8)); // coal
        furnace.set(5, stack(1, 1)); // stone: neither, goes to the hotbar
        furnace.click(3, 0, Container.QUICK_MOVE, false);
        furnace.click(4, 0, Container.QUICK_MOVE, false);
        furnace.click(5, 0, Container.QUICK_MOVE, false);
        assertEquals(stack(15, 10), furnace.slot(0));
        assertEquals(stack(263, 8), furnace.slot(1));
        assertEquals(stack(1, 1), furnace.slot(30));
        // Nothing goes into the output, and a bucket is fuel-slot material one at a time.
        player.setCursor(stack(1, 1));
        furnace.click(2, 0, Container.PICKUP, false);
        assertNull(furnace.slot(2));
        assertFalse(furnace.valid(1, stack(1, 1)));
        assertEquals(1, furnace.limit(1, stack(325, 1)));
    }

    @Test
    void theCraftingTableCraftsThreeByThree() {
        Inventory player = new Inventory();
        Window table = Window.open(3, "minecraft:crafting_table", "{}", 0, player, false);
        for (int cell : new int[]{1, 2, 3, 5, 8}) {
            table.set(cell, stack(5, 2)); // planks: a pickaxe head and handle need sticks...
        }
        assertNull(table.slot(0));
        table.set(5, stack(280, 2));
        table.set(8, stack(280, 2));
        assertEquals(270, table.slot(0).id()); // wooden pickaxe
        table.click(0, 0, Container.QUICK_MOVE, false);
        // Two pickaxes: the grid had two of everything.
        assertEquals(2, countOf(player, 270));
        assertNull(table.slot(1));
        assertNull(table.slot(0));
    }

    @Test
    void cakeLeavesTheBucketsBehind() {
        ItemStack milk = stack(335, 1);
        ItemStack[] grid = {milk, milk, milk, stack(353, 1), stack(344, 1), stack(353, 1), stack(296, 1),
                stack(296, 1), stack(296, 1)};
        assertEquals(354, Crafting.result(grid).id());
        ItemStack[] left = Crafting.remaining(grid);
        assertEquals(stack(325, 1), left[0]);
        assertNull(left[3]);
    }

    @Test
    void threeByThreeRecipesNeedTheirExactLayout() {
        ItemStack cobble = stack(4, 1);
        assertEquals(61, Crafting.result(new ItemStack[]{cobble, cobble, cobble, cobble, null, cobble, cobble,
                cobble, cobble}).id()); // furnace
        assertNull(Crafting.result(new ItemStack[]{cobble, cobble, cobble, cobble, cobble, cobble, cobble, cobble,
                cobble}));
        // A 2x2 recipe works in any corner of the 3x3 grid.
        ItemStack planks = stack(5, 1, 3);
        assertEquals(58, Crafting.result(new ItemStack[]{null, null, null, null, planks, planks, null, planks,
                planks}).id());
        // A sword upright in the middle column; the stick has to be damage 0.
        ItemStack iron = stack(265, 1);
        assertEquals(267, Crafting.result(new ItemStack[]{null, iron, null, null, iron, null, null, stack(280, 1),
                null}).id());
        assertNull(Crafting.result(new ItemStack[]{null, iron, null, null, iron, null, null, stack(280, 1, 1),
                null}));
    }

    @Test
    void villagerTradesComputeAndPay() {
        Inventory player = new Inventory();
        Window villager = Window.open(4, "minecraft:villager", "{}", 3, player, false);
        villager.trades(List.of(new Trade(stack(388, 2), null, stack(297, 3), false, 0, 7)));
        villager.set(0, stack(388, 5)); // emeralds buy bread
        assertEquals(stack(297, 3), villager.slot(2));
        villager.click(2, 0, Container.PICKUP, false);
        assertEquals(stack(297, 3), player.cursor());
        assertEquals(stack(388, 3), villager.slot(0));
        assertEquals(stack(297, 3), villager.slot(2)); // still enough for another
    }

    @Test
    void enchantingNeedsLapisLevelsAndAnOffer() {
        Inventory player = new Inventory();
        Window table = Window.open(5, "minecraft:enchanting_table", "{}", 0, player, false);
        table.set(0, stack(276, 1)); // diamond sword
        table.property(0, 5);
        table.property(1, 12);
        table.player(10, false);
        assertFalse(table.canEnchant(0)); // no lapis
        table.set(1, stack(351, 2, 4));
        assertTrue(table.canEnchant(0));
        assertFalse(table.canEnchant(1)); // twelve levels needed
        assertFalse(table.valid(1, stack(351, 1, 0))); // ink is not lapis
        table.set(0, null);
        assertEquals(0, table.property(0));
        assertEquals(-1, table.property(4));
    }

    @Test
    void theAnvilResultNeedsLevels() {
        Inventory player = new Inventory();
        Window anvil = Window.open(6, "minecraft:anvil", "{}", 0, player, false);
        anvil.set(0, stack(276, 1, 1000)); // a worn diamond sword
        anvil.set(1, stack(264, 3));       // and three diamonds: each repairs a quarter
        anvil.set(2, stack(276, 1, 0));    // the result, as the server sends it
        anvil.player(0, false);
        assertEquals(3, anvil.repairCost());
        anvil.click(2, 0, Container.PICKUP, false);
        assertNull(player.cursor());
        anvil.player(3, false);
        anvil.click(2, 0, Container.PICKUP, false);
        assertNotNull(player.cursor());
        assertNull(anvil.slot(0));
        assertNull(anvil.slot(1)); // all three used

        // Renaming costs a level; merging two enchanted swords adds their enchantments' weights.
        anvil.set(0, stack(276, 1));
        anvil.rename("Blade");
        assertEquals(1, anvil.repairCost());
    }

    @Test
    void brewingStandsHoldOnePotionPerBottleSlot() {
        Inventory player = new Inventory();
        Window stand = Window.open(7, "minecraft:brewing_stand", "{}", 4, player, false);
        player.set(9, stack(372, 4)); // nether wart
        stand.click(4, 0, Container.QUICK_MOVE, false);
        assertEquals(stack(372, 4), stand.slot(3));
        assertEquals(1, stand.limit(0, stack(373, 1)));
        assertFalse(stand.valid(0, stack(372, 1)));
    }

    @Test
    void horsesTakeSaddlesAndArmorOnlyWhereTheyFit() {
        Inventory player = new Inventory();
        Window donkey = Window.open(8, "EntityHorse", "{}", 17, player, false);
        assertTrue(donkey.horseChest());
        assertFalse(donkey.valid(1, stack(419, 1))); // donkeys wear no armor
        assertTrue(donkey.valid(0, stack(329, 1)));
        Window horse = Window.open(9, "EntityHorse", "{}", 2, player, true);
        assertTrue(horse.valid(1, stack(419, 1)));
    }

    private static int countOf(Inventory inventory, int id) {
        int total = 0;
        for (int slot = 0; slot < Inventory.SLOTS; slot++) {
            ItemStack stack = inventory.slot(slot);
            if (stack != null && stack.id() == id) {
                total += stack.count();
            }
        }
        return total;
    }
}
