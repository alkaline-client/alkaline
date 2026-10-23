# Architecture decision 0008: HUD cache, per-element blur, partial blur, and the player inventory

Status: accepted. Implemented 2026-10-17.

Measurements below: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS, client on
XWayland), 1280x760 window on a 170 Hz display, Low latency (mailbox) presentation. Scene: in game against Mojang's
1.8.9 server, night, rain, 21 zombies and 21 sheep in view, render distance 8, default HUD (FPS, scoreboard, health,
hotbar); 30 s runs with `--stats`. They are development-host evidence, not target-hardware qualification.

## HUD cache

The HUD was already a cache in the sense that matters: Compose rasterizes it on its own thread into a texture, and
the render thread composites the last finished texture every frame without waiting. What it lacked was a rate: the
UI thread rendered up to a fixed 144 times a second whenever the HUD was invalidated.

- **HUD frame rate** (`interface.hud_rate`, Interface) caps how often the HUD redraws: Refresh rate (default) follows
  the refresh rate of the window's display (SDL's current display mode, re-read when the window changes display or
  the mode changes), or a fixed 10-240 fps. Frames in between reuse the cached HUD.
- The cap applies while only the HUD is up. With a menu, the ClickGUI or the inventory open, the UI runs at least at
  the display's rate, so their motion stays smooth whatever the HUD is set to.
- The blur behind HUD elements is part of the HUD, so it follows the same rate (below).

## Blur per element

Every HUD module with a surface has a **Blur** option in its module settings (profile, so per profile): FPS,
coordinates, CPS, speed, keystrokes, ping, clock, server, potion effects, scoreboard and hotbar (on by default, as
before), and chat (off by default; each line frosts and fades with its text). Health and hunger has no surface and so
no option. HUD glass no longer follows the menu's **Frosted surfaces** setting, which now covers menus only.

Glass regions now carry whether they belong to the HUD and an opacity, so a fading element's backdrop fades with it.

## Partial, cached blur

The frosted backdrop is a four-down, two-up dual-filter (Kawase) blur of the scene. Before, whenever any glass was
on screen, all six passes covered their whole targets every frame, about 42% of the screen's pixels at 338 fps.

- **Only around regions.** Each pass now draws instanced rectangles instead of a full-screen triangle: the glass
  regions, padded by how far the passes after it sample (each pass's tap offset plus bilinear footprint, in its source
  texels, plus one texel for the target's own coverage), clamped to the screen, and merged where they overlap. The
  padding is computed per pass from the actual target sizes. Pixels outside the rectangles keep stale data no pass
  or glass ever reads.
- **Exactness.** The blur shaders take their coordinates from `gl_FragCoord` rather than the interpolated rectangle, so
  a pixel samples the same spots however the rectangles are cut. Checked on a static scene (panorama, rotation 0)
  against the same build blurring whole targets (`-Dalkaline.glass.fullBlur=true`): the title page's four small
  buttons and the settings panel both differ in **0 pixels**. (With interpolated coordinates, 159 pixels differed by
  one level.)
- **At the HUD rate.** The chain runs again only when the HUD cache period has passed, or when a region moved, was
  added or removed (opacity changes alone do not invalidate it); frames in between frost with the last result.

| | blur on every frame, whole targets | partial + HUD cache (Refresh rate) | blur off |
|---|---:|---:|---:|
| blur chain runs | every frame (~338/s) | 3,717 in 9,883 frames (~113/s) | — |
| target pixels per run | 100% | 52% | — |
| GPU frame, mean / p95 | 0.718 / 0.986 ms | 0.650 / 0.724 ms | 0.624 / 0.690 ms |

At the default HUD the blur's GPU cost drops from about 0.094 ms to 0.026 ms a frame (mean, against blur off), and its
p95 spike from about 0.3 ms to 0.03 ms. Refreshes run below 170/s because the cadence is measured in whole frames
at ~3 ms each. With the HUD rate at 30, the chain ran 840 times in 9,942 frames. Frame time is unchanged on this host,
which is presentation-bound (decision 0007).

## The player inventory

E (the inventory binding) opens the player's inventory over the game; E or Esc closes it.

- **Rules.** `Inventory.click` is a port of Container.slotClick with ContainerPlayer's slots: pick up, place, split,
  merge and swap; shift-click (armor onto its slot, hotbar and main inventory into each other, the result crafting
  repeatedly); number keys (swap with the hotbar); pick block (creative clone); throwing (drop key, clicks outside);
  drag splitting (left even, right one each, middle full stacks in creative); and double-click collecting. Armor slots
  take their piece (and pumpkins and heads on the head) one at a time.
- **Prediction.** As PlayerControllerMP does, a click is applied to the client's copy at once and sent with the stack
  it saw and an action number; the server confirms. A rejected click is acknowledged (Confirm Transaction) and the
  server's resend replaces the window. Closing sends Close Window and clears the cursor and the grid, which the server
  throws. Opening sends Client Status 2, as vanilla does (the server awards "Taking Inventory").
- **Input.** `WindowInput` is GuiContainer's mouse and key logic (press, move and release over slots, the 250 ms double
  click, drag splitting, ignore-mouse-up after a pickup). It runs on the game thread beside the inventory, so each
  decision sees the real cursor stack; the Compose screen only reports which slot is under the pointer.
- **Crafting.** The server never sends the player's crafting result (EntityPlayerMP skips SlotCrafting), so the client
  computes it, as vanilla's does: `Crafting` holds 1.8.9's recipes that fit 2x2 (shaped, matched mirrored and at any
  offset; shapeless up to four ingredients), tool repair and leather dyeing. Fireworks, book and map cloning and
  banner copying build their results from tags and are not covered: their grid shows no result, and taking it still
  works because the server's resend brings it.
- **Screen.** Vanilla's slot layout (so hands find the same places) in one frosted panel: armor with vanilla's empty
  slot outlines, the 2x2 grid and result, the main inventory and hotbar; the cursor stack follows the pointer, drag
  splitting previews its distribution, and hovering shows vanilla's tooltip (the translated name from en_US.lang with
  variants, rarity color, enchantments, potion names and lore). Names come from 1.8.9's unlocalized names, generated
  from the MCP sources' registries.
- **Other windows.** Chests and server menus are not drawn yet. When the server opens one, the client closes it at
  once and logs it, so the server's open window is the inventory again and its clicks keep working.

Checked against the server: crafting birch planks from two logs by shift-clicking the result left 8 planks and no logs
on the server (`/clear Player planks -1 0`), and drag-splitting 40 cobblestone left 40 on the server, as on screen.
266 tests pass, including the click rules, recipes, drag and double-click input, and the blur rectangles.

## Not done

Chests, furnaces and other windows; the player model beside the armor (the area is empty); creative mode's item
catalog (creative uses the survival layout, where pick block clones stacks); tag-built recipes; armor attribute lines
in tooltips.
