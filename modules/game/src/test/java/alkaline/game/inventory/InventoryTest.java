package alkaline.game.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryTest {
    private static ItemStack stack(int id, int count) {
        return new ItemStack(id, count, 0, null);
    }

    private static ItemStack stack(int id, int count, int damage) {
        return new ItemStack(id, count, damage, null);
    }

    @Test
    void leftClickTakesTheStackAndRightClickHalfOfIt() {
        Inventory inventory = new Inventory();
        inventory.set(9, stack(4, 33));
        assertEquals(stack(4, 33), inventory.click(9, 0, Inventory.PICKUP, false));
        assertEquals(33, inventory.cursor().count());
        assertNull(inventory.slot(9));

        inventory.click(9, 0, Inventory.PICKUP, false);
        inventory.click(9, 1, Inventory.PICKUP, false);
        assertEquals(17, inventory.cursor().count());
        assertEquals(16, inventory.slot(9).count());
    }

    @Test
    void rightClickPlacesOneAndPlacingMergesUpToTheStackLimit() {
        Inventory inventory = new Inventory();
        inventory.setCursor(stack(4, 40));
        inventory.click(10, 1, Inventory.PICKUP, false);
        assertEquals(1, inventory.slot(10).count());
        assertEquals(39, inventory.cursor().count());
        inventory.set(11, stack(4, 60));
        inventory.click(11, 0, Inventory.PICKUP, false);
        assertEquals(64, inventory.slot(11).count());
        assertEquals(35, inventory.cursor().count());
    }

    @Test
    void differentItemsSwapWithTheCursor() {
        Inventory inventory = new Inventory();
        inventory.set(12, stack(1, 5));
        inventory.setCursor(stack(4, 7));
        inventory.click(12, 0, Inventory.PICKUP, false);
        assertEquals(stack(4, 7), inventory.slot(12));
        assertEquals(stack(1, 5), inventory.cursor());
    }

    @Test
    void armorSlotsTakeOnlyTheirPieceOneAtATime() {
        Inventory inventory = new Inventory();
        inventory.setCursor(stack(311, 1)); // diamond chestplate
        inventory.click(Inventory.HELMET, 0, Inventory.PICKUP, false);
        assertNull(inventory.slot(Inventory.HELMET));
        inventory.click(Inventory.HELMET + 1, 0, Inventory.PICKUP, false);
        assertEquals(311, inventory.slot(Inventory.HELMET + 1).id());
        assertNull(inventory.cursor());
    }

    @Test
    void shiftClickMovesArmorOnAndHotbarItemsIntoTheMainInventory() {
        Inventory inventory = new Inventory();
        inventory.set(20, stack(310, 1)); // diamond helmet
        inventory.click(20, 0, Inventory.QUICK_MOVE, false);
        assertEquals(310, inventory.slot(Inventory.HELMET).id());

        inventory.set(36, stack(4, 64));
        inventory.set(9, stack(4, 60));
        inventory.click(36, 0, Inventory.QUICK_MOVE, false);
        assertEquals(64, inventory.slot(9).count());
        assertEquals(60, inventory.slot(10).count());
        assertNull(inventory.slot(36));
    }

    @Test
    void numberKeysSwapWithTheHotbar() {
        Inventory inventory = new Inventory();
        inventory.set(15, stack(264, 3));
        inventory.set(38, stack(4, 9));
        inventory.click(15, 2, Inventory.SWAP, false);
        assertEquals(stack(264, 3), inventory.slot(38));
        assertEquals(stack(4, 9), inventory.slot(15));
    }

    @Test
    void theGridCraftsAndTakingTheResultUsesItUp() {
        Inventory inventory = new Inventory();
        inventory.set(1, stack(17, 2, 2)); // birch logs
        assertEquals(new ItemStack(5, 4, 2, null), inventory.slot(Inventory.RESULT));
        inventory.click(Inventory.RESULT, 0, Inventory.PICKUP, false);
        assertEquals(new ItemStack(5, 4, 2, null), inventory.cursor());
        assertEquals(1, inventory.slot(1).count());
        // A matching cursor stack takes another result.
        inventory.click(Inventory.RESULT, 0, Inventory.PICKUP, false);
        assertEquals(8, inventory.cursor().count());
        assertNull(inventory.slot(1));
        assertNull(inventory.slot(Inventory.RESULT));
    }

    @Test
    void shiftClickingTheResultCraftsUntilTheGridRunsOut() {
        Inventory inventory = new Inventory();
        for (int slot = 1; slot <= 4; slot++) {
            inventory.set(slot, stack(5, 3)); // planks
        }
        assertEquals(stack(58, 1), inventory.slot(Inventory.RESULT));
        inventory.click(Inventory.RESULT, 0, Inventory.QUICK_MOVE, false);
        assertEquals(stack(58, 3), inventory.slot(44)); // into the hotbar, last slot first
        assertNull(inventory.slot(1));
        assertNull(inventory.slot(Inventory.RESULT));
    }

    @Test
    void recipesMatchMirroredAndAtAnyOffset() {
        ItemStack iron = stack(265, 1);
        assertEquals(359, Crafting.result(new ItemStack[]{null, iron, iron, null}).id()); // shears
        assertEquals(359, Crafting.result(new ItemStack[]{iron, null, null, iron}).id()); // mirrored
        assertEquals(new ItemStack(280, 4, 0, null),
                Crafting.result(new ItemStack[]{null, stack(5, 1, 3), null, stack(5, 1, 1)})); // any planks
        assertEquals(72, Crafting.result(new ItemStack[]{null, null, stack(5, 1), stack(5, 1)}).id()); // plate
        assertNull(Crafting.result(new ItemStack[]{stack(5, 1), null, null, stack(5, 1)}));
        assertEquals(stack(259, 1), Crafting.result(new ItemStack[]{null, stack(318, 1), stack(265, 1), null}));
    }

    @Test
    void wornToolsRepairWithAFivePercentBonus() {
        ItemStack worn = new ItemStack(278, 1, 1500, null);
        ItemStack result = Crafting.result(new ItemStack[]{worn, null, null, new ItemStack(278, 1, 1000, null)});
        assertEquals(1561 - ((1561 - 1500) + (1561 - 1000) + 1561 * 5 / 100), result.damage());
    }

    @Test
    void leatherTakesTheAverageDyeColor() {
        ItemStack dyed = Crafting.result(new ItemStack[]{stack(299, 1), stack(351, 1, 1), null, null});
        assertEquals(0x993333, dyed.leatherColor()); // red dye alone: 0.6, 0.2, 0.2
    }

    @Test
    void dragSplittingSpreadsTheCursorEvenly() {
        Inventory inventory = new Inventory();
        List<int[]> sent = new ArrayList<>();
        WindowInput input = new WindowInput(inventory, (slot, button, mode) -> {
            sent.add(new int[]{slot, button, mode});
            inventory.click(slot, button, mode, false);
        });
        inventory.setCursor(stack(4, 10));
        input.press(9, 0, false, false, 1000L);
        input.drag(9);
        input.drag(10);
        input.drag(11);
        input.release(11, 0, false, false);
        assertEquals(3, inventory.slot(9).count());
        assertEquals(3, inventory.slot(10).count());
        assertEquals(3, inventory.slot(11).count());
        assertEquals(1, inventory.cursor().count());
        assertEquals(5, sent.size()); // start, three slots, end
        assertEquals(Inventory.DRAG, sent.get(0)[2]);
    }

    @Test
    void doubleClickCollectsMatchingStacks() {
        Inventory inventory = new Inventory();
        WindowInput input = new WindowInput(inventory, (slot, button, mode) -> inventory.click(slot, button, mode, false));
        inventory.set(9, stack(4, 10));
        inventory.set(20, stack(4, 5));
        inventory.set(30, stack(1, 5));
        input.press(9, 0, false, false, 1000L);
        input.release(9, 0, false, false);
        input.press(9, 0, false, false, 1100L);
        input.release(9, 0, false, false);
        assertEquals(15, inventory.cursor().count());
        assertNull(inventory.slot(20));
        assertEquals(5, inventory.slot(30).count());
    }

    @Test
    void closingThrowsTheCursorAndTheGrid() {
        Inventory inventory = new Inventory();
        inventory.set(1, stack(17, 1));
        inventory.setCursor(stack(4, 3));
        inventory.closed();
        assertNull(inventory.cursor());
        assertNull(inventory.slot(1));
        assertNull(inventory.slot(Inventory.RESULT));
    }
}
