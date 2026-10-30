# Architecture decision 0011: full play-protocol coverage, container windows, item use, and the launcher

Status: accepted. Implemented 2026-10-18. Builds on decisions 0006 and 0008.

Checked against Mojang's 1.8.9 server (offline mode, superflat, Java 25 with `use-native-transport=false`) with
`--input-script` driving the client and `--screenshot` captures; the reference for every rule below is the local
KotlinizedMCP 1.8.9 source (`external/KotlinizedMCP-mcp`).

## Every play packet decoded

`ServerSession` now decodes all protocol 47 clientbound play packets. Connection- and world-level ones (keep-alive,
join, respawn, chunks, block changes, explosions, disconnect, compression) stay in the reader thread; everything
else goes through a pure `ServerSession.decode(id, buffer)`, so each layout has a unit test (`PlayPacketsTest`)
without a socket. New events: spawn position, use bed, paintings, experience orbs, lightning, collect item, attach,
entity properties, block action, break animation, sounds, window property, sign text and the sign editor, maps,
tile entity data, statistics, tab completion, plugin messages (MC|TrList and MC|Brand decoded), difficulty, combat,
camera, world border, player list header/footer, resource packs and entity NBT. Join Game now reads its final
`reducedDebugInfo` field. Counts are checked before allocation as before.

Every serverbound play packet has a sender: interact (INTERACT_AT then INTERACT, as PlayerControllerMP), digging,
block placement and use item, steer vehicle, creative slot, enchant item, update sign, tab complete, plugin
messages (MC|TrSel, MC|ItemName, MC|Beacon), spectate and resource pack status.

What the game uses today: Open Window and the window packets (below); entity properties set the local player's
`generic.movementSpeed` (Speed and Slowness now change walking speed; the sprint modifier stays client-owned, as
vanilla's `setSprinting` keeps it); attach makes the player ride (position follows the vehicle, Steer Vehicle and
look are sent instead of movement); collect item, paintings, orbs and lightning are tracked entities (not drawn yet);
the player list shows the header and footer; resource packs are answered **declined**, since Alkaline cannot load
them yet. Sounds, maps, statistics, the world border, break animations and block actions are decoded but have no
consumer until audio, maps and the renderer need them.

## One Container.slotClick for every window

`Container` ports Container.slotClick once (pick up, shift-click with retry, number keys, clone, throw, the
three-stage drag, double-click collect) over abstract slots and the per-slot hooks vanilla's `Slot` subclasses
override (`valid`, `limit`, `canTake`, `pickedUp`, `canCollect`, `take`). `Inventory` (window 0, unchanged API) and
`Window` (everything a server opens) extend it. A window's own slots come first; the player's 27 + 9 slots are the
`Inventory`'s storage, shared, so a chest and the inventory never disagree about the hotbar, and the cursor stack is
the player's. `WindowInput` (GuiContainer's mouse handling) works on any `Container`.

`Window.open` maps the GUI id as `EntityPlayerSP.displayGUIChest`/`displayGui` do (chest and unknown slotted ids show
as chests; unknown slot-less ids get a Close Window) and carries each vanilla container's transferStackInSlot and
slot rules: chest, dispenser/dropper, hopper, furnace (smeltables to the input, fuels to the fuel slot via
`Smelting`), brewing stand (one potion per bottle slot, ingredients), crafting table, enchanting table (one item,
lapis only), anvil, beacon (one payment item), villager, horse (saddle, armor only for plain horses, the chest only
for chested donkeys and mules).

Results the server never sends are computed as the vanilla client computes them:

- **Crafting table results.** EntityPlayerMP skips SlotCrafting, so `Crafting` now holds vanilla's full recipe book
  for 2x2 and 3x3 grids (named by registry name, resolved and checked at class load), with CraftingManager's
  ingredient quirks (items match damage 0 only; blocks match any variant in shaped recipes but only variant 0 in
  shapeless ones) and the container items left behind (milk, water and lava buckets). Fireworks, book and map
  cloning, map extending and banner patterns are still not computed; the server's resync corrects them.
- **Villager results** from MC|TrList (MerchantRecipeList.canRecipeBeUsed, either slot order) and paying for a
  trade (SlotMerchantResult.doTrade).
- **The anvil cost.** The server sends `maximumCost` only when the window opens; ContainerRepair.updateRepairOutput
  runs on the client too, so `Anvil` ports it: material repair a quarter at a time, combining items and enchanted
  books with each enchantment's weight, max level and compatibility, rename cost, previous work, and the 39/40
  "Too Expensive!" rule. Without it the result could never be taken.

## Screens

`ContainerScreen` replaces the inventory screen and draws every kind in the existing frosted-panel style with
vanilla's slot coordinates (so hands find the same places): titles from the server's chat component, furnace fuel
and cook progress, brewing progress, the enchanting table's three offers (level cost, the "Sharpness II . . . ?"
hint, enabled only when `canEnchant`), the anvil's name field and cost, the beacon's tiers of powers with confirm,
the villager's trade on show with previous/next, and the horse's saddle and armor slots. The sign editor is its own
overlay; its lines are sent as JSON strings on Done or Escape, as GuiEditSign does. A server-opened window replaces
whatever screen is open, as vanilla's displayGuiScreen does, without a Close Window for the old one.

Text fields in game set `MenuController.typing`, and the client then passes every key to the field: E types an E
in the anvil instead of closing it.

**Fixed on the way:** Compose 1.12 no longer accepts a raw AWT key event (`KeyEvent(awtEvent)` throws a
ClassCastException on the UI thread at the first Backspace, Enter or arrow key in any text field, the multiplayer
address bar included). `ComposeLayerHost` now builds Compose's own key event from the AWT key code and modifiers.

## Using items and blocks

The use key now does what Minecraft.rightClickMouse does: interact with the entity under the crosshair (sneaking on a
tamed horse opens its inventory, villagers trade), otherwise send Player Block Placement for the targeted block
(`BlockTarget`: an Amanatides-Woo walk through the cells the ray crosses, collision boxes for solid blocks and the
whole cell for the rest) and, unless the block was activated (`Interactions.activatable`: containers, doors, levers,
fences...) or a block item placed, use the held item (Player Block Placement at -1). A press acts at once; holding
repeats every four ticks. Eating, drinking, drawing a bow and sword blocking last as vanilla's item use durations
say, move the player at a fifth of the speed and end sprinting (EntityPlayerSP.onLivingUpdate), and end on release
(Player Digging RELEASE_USE_ITEM), on switching slots, or with the server's entity status 9. Placement is not
predicted locally: the server's Block Change and Set Slot follow within a tick. Block breaking is not built.

## Runtime checks (all against the 1.8.9 server)

- Chest opened by right-clicking it; shift-click, pick-up and swap with the hotbar, close (the cursor stack is
  thrown) and reopen: the server's contents matched the client's every time; no rejected transactions.
- Furnace progress and fuel follow the window properties, and smelting results arrive. (A furnace placed by
  `setblock` without `CookTimeTotal` never cooks on the server either; vanilla shows the same empty arrow.)
- Crafting table: a wooden pickaxe computed client-side from right-click-placed planks and sticks, accepted.
- Enchanting table: offers shown, Sharpness I applied, one level and one lapis spent.
- Anvil: rename by typing (Backspace), cost 1 shown, result taken, one level spent.
- Villager: trades shown, bread bought, the second trade paged to.
- Sign editor opened by placing a sign; Done sends the lines.
- Brewing stand, hopper, dropper, beacon and a chested donkey (sneak + right-click) open with their layouts.
- Holding the use key 1.6 s eats a golden apple (the server's count drops by one).
- `./gradlew check`: 293 tests, no failures. Not checked: Windows, real (non-synthetic) mouse and keyboard input,
  beacon power confirmation on a built pyramid, brewing a potion to completion, and riding with steering.

## The launcher

Superseded 2026-10-30: the prototype in `../launcher` was deleted; the Sulfur launcher replaces it.

The launcher lives in `../launcher` (its own Gradle build and repository): Kotlin + Compose Desktop, Material 3
Expressive and Google Sans Flex on the same pinned versions as this repository, so the two read as one product and
the client's Java 25 runtime is already there. Its one screen is the mark, the name, Play and a status line. Play
finds the client (an explicit `--client`/`ALKALINE_CLIENT_HOME`, this checkout's `installDist` output, or
`~/.alkaline/legacy/client`), builds it with this repository's wrapper when only the source is there, and starts
`bin/game-client` as its own process with the launcher's Java as `JAVA_HOME`, logging to
`~/.alkaline/launcher/logs/`. Arguments after `--` go to the client. Checked: `./gradlew run --args="--play -- --join
localhost:25611"` started this client, which joined the local server. The Linux click fix is planned to move there.
