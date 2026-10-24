# Architecture decision 0016: Death screen

Status: accepted. Implemented 2026-10-24.

Until now the client sent "perform respawn" as soon as its health reached zero, because it had no death screen.
The screen follows 1.8.9's `GuiGameOver`, decompiled from the real client, in Alkaline's components.

## Behavior

- **Opening.** The screen opens when an Update Health packet brings health to zero or below, as
  `Minecraft.runTick` and `displayGuiScreen(null)` do while the player is dead. If chat, a window, the server pack
  prompt or the pause card is open, that stays, and the death screen follows when it closes.
- **Look.** A full-screen wash from `0x60500000` to `0xA0803030` (`drawGradientRect`), "You died!" and
  "Score: N" with the number in yellow. The score is the player's metadata 18. The HUD shows underneath, as vanilla
  draws it under screens.
- **Buttons.** Respawn is the primary action. Both buttons wait 20 ticks (1 s) before they work, as
  `enableButtonsTimer` does. Leaving asks "Are you sure you want to quit?" first, with the same delay
  (`GuiYesNo.setButtonDelay(20)`).
- **Escape** does nothing (`keyTyped` is empty). The ClickGUI, chat and inventory keys are ignored too.
- **Respawn** sends Client Status 0 (`EntityPlayerSP.respawnPlayer`) and returns to the game.
- **Hardcore** (Join Game game mode bit 8) shows "Game over!", "You cannot respawn in hardcore mode!" and only
  Leave server.

The rules are in `MenuController` (`died`, `respawn`, `alive`, `Overlay.DEATH`), the screen in `DeathScreen.kt`,
and the packet side in `PlaySession` (`onDeath`, `onAlive`, `respawn`).

## Deviations

- Vanilla's "Title screen" button is "Disconnect", as on the pause card, because leaving returns to the server
  list.
- The screen closes by itself when health comes back without a click, for example when a server respawns the player.
  Vanilla keeps it open until a button is pressed.
- 1.8.9 shows no cause of death on this screen; the death message stays in chat, as in vanilla.

## Verification

- `DeathScreenRulesTest` covers opening, Escape and the blocked keys, a single respawn request, screens open at
  death, the pause card, health coming back, leaving, and dying outside a game.
- In game, Xe Lite, port 25643, a fresh world per run, `kill Player`:
  - the screen and score showed;
  - Respawn after 3 s brought back full health and the crosshair;
  - Disconnect showed the confirmation, and confirming left the server (logged) and opened the server list.
- Hardcore was not run in game.
