# Architecture decision 0007: HUD, modules and profiles, entities, effects, and a renderer efficiency review

Status: accepted. Implemented 2026-10-17.

Measurements below: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS,
client on XWayland), 1280x760, Low latency (mailbox) presentation. They are development-host evidence, not
target-hardware qualification. Runtime checks ran against Mojang's 1.8.9 server (offline mode, superflat) on the
same machine; under Java 25 that server needs `use-native-transport=false`, because its Netty cannot use epoll there.

## Profiles and modules

Client settings (`settings.json`) stay what they were: video, interface, controls. Everything a player arranges
per game mode now lives in **profiles**: `~/.alkaline/legacy/profiles/<name>.json`, one of them active
(`profiles.json`).

- `ProfileStore` (config) owns one `ConfigStore` that moves between files when the profile changes
  (`ConfigStore.switchTo`), so readers and listeners never re-subscribe. Create (from defaults or as a copy),
  rename, delete (not the last) and activate are disk operations for the UI thread, never the render loop.
- `Modules` (client) declares every module and the profile schema (version 1): `<id>.enabled`, the module's
  options, and for HUD modules a placement: anchor (one of nine screen points), x and y in dp from it, scale.
  Plugin settings will join the same files when plugins exist; the ClickGUI already has their section.
- A bug found while testing: opening the store flushed the not-yet-loaded store, writing defaults over the active
  profile. `switchTo` now writes only when a change is pending; `ProfileStoreTest` covers reopening.

## The HUD

The HUD is Compose in the existing in-game host, drawn from one immutable `HudFrame` the client publishes on the
UI thread once per tick, and only when it changed. Each HUD module is an element at its profile placement:
FPS, coordinates (with facing), CPS, speed, keystrokes, ping, clock, server, potion effects, the scoreboard
sidebar (with the port of KotlinizedMCP's "Score Point Display" as an option), chat (receive only), health and
hunger (vanilla's `icons.png` hearts, armor, hunger, air and experience) and the hotbar (item sprites, counts,
durability, glint). Titles, the action bar and the player list (Tab) are vanilla's fixed overlays.

This replaces the JSON component tree (`UiLayout`, `AlkalineHud`, `ui/hud/default.json`): it never reached the
game, and a tree of generic nodes cannot express placement per element. Layout is still data (the profile), and
`apps/hud-preview` now previews the real HUD over a profile file.

Chrome follows the design rules: readouts are frosted pills, the hotbar a frosted bar with the selected slot in
the primary container; vanilla sprites appear only where the shape carries gameplay meaning (hearts, hunger,
armor, air, effect icons). Text keeps Minecraft's § colors, set in Google Sans Flex.

## ClickGUI and HUD editor

Right Shift (`controls.miscellaneous.click_gui`) or Modules on the pause card opens one frosted panel: a sidebar
with Modules, Plugins and Music, and the HUD editor at its foot. Modules lists HUD and gameplay modules under the
profile picker (switch, new from current, rename, delete); each row is the module's switch, with its options and
"Move in HUD editor" folded under it. Plugins and Music are honest empty states: neither plugins nor audio exist.

The HUD editor shows every enabled element over the game. Elements drag with snapping to the screen margins and
center and to other elements' edges and centers (dashed guides), scale with the wheel, and on drop take the anchor
of the screen third their center lies in, so they keep their distance from that edge when the window or GUI scale
changes. A toolbar sizes, resets or hides the selected element.

Typing names needed keyboard input in Compose, which the host never had (the multiplayer address bar could not be
typed into either). SDL's text input (layouts and dead keys resolved by the window system) and editing keys now
reach Compose as AWT key events; text input runs only while the interface has the mouse.

## Entities

`modules/game` tracks entities from the protocol (spawns, relative moves, teleports, head look, velocity,
metadata, equipment, status, animation) and simulates the client's copy as vanilla does: living entities reach
each server position over three ticks, objects over one; body yaw follows movement within 75 degrees of the head;
limb swing, the six-tick arm swing, the 10-tick hurt flash and the death tilt. The local player takes knockback
from Entity Velocity and explosion pushes, and attacks the entity under the crosshair (3 blocks, 6 in creative,
blocked by blocks in between), with vanilla's swing-before-attack order and the sprint reset after knockback.
Inventory window 0, the player list, the scoreboard with teams, potion effects, experience, titles and chat are
tracked too.

The renderer draws players (skins from the profile's textures property, downloaded once into
`~/.alkaline/legacy/skins/` and normalized as vanilla's ImageBufferDownload does; Steve or Alex by UUID until
then), zombies, pigmen, giants, skeletons and wither skeletons, creepers, spiders and cave spiders, endermen, pigs,
cows, mooshrooms, sheep with their fleece color, chickens, wolves, villagers by profession, iron golems, slimes,
blazes, squids and ghasts, plus baby mobs, armor layers (leather dyed with its overlay), items held in third
person and dropped items. Models, pivots and animation constants are vanilla's
(`EntityModels`); models now take the terrain's fog. The first-person hand is the player model's right arm
(`FirstPersonArm`, ItemRenderer.renderPlayerArm) or the held sprite item, drawn after a depth clear with the
70-degree hand projection, lowered and raised when the held item changes.

Not drawn yet: name tags, the remaining mobs (witches, bats, horses, rabbits, ocelots, silverfish, endermites,
guardians, the dragon, the wither), objects other than dropped items (arrows, minecarts, boats...), block items
in hand and on the ground, and armor glint.

## Hit color

Hurt and dying entities take an overlay in the configured `world.hit_color`, vanilla's red at 30% by default. The
renderer receives a `HitColor` policy rather than a bare color, so later options plug in without touching the
draw path; the first one prepared is `tintArmor`: vanilla 1.8.9 leaves armor untinted (LayerArmorBase does not
combine textures), unlike 1.7, so it is off and not exposed yet.

## Camera, clouds and particles

- **View bobbing** Off, Minimal or Full. Full is vanilla's setupViewBobbing on the camera and the hand; Minimal
  bobs only the hand and keeps the camera level. The world renderer takes the eye-space transform
  (`WorldRenderer.prepare(..., eyeTransform)`) and traces the sky with the full camera orientation.
- **Hurt shake** (KotlinizedMCP's "Hurt Shake"): hurtCameraEffect's roll after damage, on by default as in vanilla.
- Vanilla's sprint and flight FOV change and the underwater FOV; the **Zoom** module narrows the view (4x by
  default, eased) and slows the mouse by its magnification.
- **Clouds** Off, Fast or Fancy, with OptiFine's cloud height. The whole 256x256 cloud map is built once into a
  static mesh of exact cloud cells (tops, bottoms, and sides only where a cloud meets clear sky) in 16x16 chunks;
  a frame draws the chunks within fog distance, wrapped around the camera, from push constants. Fancy uses
  vanilla's depth pass so a cloud's translucent faces never blend over each other; both looks blend in gamma space
  and fog as vanilla's do. Superflat worlds use vanilla's horizon (0, not 63) and void fog factor.
- **Particles** All, Decreased or Minimal, with vanilla's 16-block range and its rule that Decreased drops a third.
  Every EnumParticleTypes id with a look of its own maps to a `ParticleType` (now built with a builder: sheets,
  launch modes, collision, ground death), including block debris sampled from the block texture array, the
  explosion sheet, Effect packet events (block breaking, smoke, potion splashes, ender eyes, spawner flames, bone
  meal), critical hit emitters and rain splashes. Particles are lit from the world's light and collide with solid
  blocks below them. Item fragments of non-block items fall back to plain colors, since item sprites are not in a
  particle sheet.

## Options

Settings schema version 4 (migration drops `interface.fps_counter`, now the FPS module).

| Entry | Source |
|---|---|
| Clouds (Off/Fast/Fancy), Particles (All/Decreased/Minimal), View bobbing, Fullscreen | vanilla (View bobbing adds Minimal) |
| Cloud height | OptiFine |
| Hit color | Alkaline (vanilla's color by default) |
| Auto sprint, Jump delay, Hurt shake | KotlinizedMCP's Extra Settings (Jump delay keeps vanilla's 10-tick delay by default) |
| Score numbers (Scoreboard module) | KotlinizedMCP's Score Point Display |
| Modules key (Right Shift) | Alkaline |

KotlinizedMCP's "Mouse Delay" is not ported: it changes which look direction picks targets between ticks, and
Alkaline applies mouse motion to the look as events arrive, so there is no delayed look to correct.

## Efficiency review: world and model renderers

Scene: in game, night (stars), rain, 21 zombies and 21 sheep in view, render distance 8, standing still; 40 s
runs with `--stats`, and 15-20 s JFR recordings for where render-thread time goes.

| | before | after |
|---|---:|---:|
| render-thread allocation | 66.5 KB/frame | 14.8 KB/frame |
| render-thread Java samples (JFR) | 9.7/s | 4.6/s |
| Compose rows uploaded per HUD change | 760 (full frame) | ~29 |
| frame time p50 / GPU p50 | 2.6 ms / 0.64 ms | 2.7-2.8 ms / 0.68 ms |

Frame time is unchanged because this host is presentation-bound: the render thread spends most of each frame in
`vkAcquireNextImageKHR` (115 of 126 native samples) at about 338 fps under XWayland. The freed CPU time shows in
the Java samples, not in fps here.

Findings and changes:

1. **World column lookups.** Rain and snow asked the client world about 441 columns several times every frame.
   Each lookup boxed a `Long` key, and the key (`x << 32 | z`) hashed to `x ^ z`, which collides along every
   diagonal, so the `ConcurrentHashMap` buckets around the player had turned into trees. The key is now multiplied
   by an odd constant (a bijection, so keys stay unique) to spread the hash; a versioned one-entry cache answers
   repeated lookups in the same column without boxing; and the weather's column answers are kept per tick.
2. **Rain lighting** recomputed vanilla's lightmap formula four times per column per frame; it now reads the
   frame's 256-entry lightmap table, which the world renderer already computes.
3. **Stars** were rotated on the CPU and streamed every night frame (1,500 quads, about 216 KB). They are a static
   buffer rotated by a push-constant matrix.
4. **Compose uploads.** The HUD changes up to 20 times a second, and every change copied the whole UI frame
   (about 3.9 MB here) into staging on the render thread. The UI thread now marks the rows that changed since the
   last frame the render thread took (frames it skipped carry their rows forward), and only those bands are
   copied and uploaded; most changes are a few small bands.
5. **Entity path allocations** removed: texture paths are constants instead of per-entity string concatenation,
   skin properties are decoded once per value instead of per player per frame, and hitbox sizes no longer
   allocate arrays.
6. **Remaining:** JFR attributes most of the remaining allocation to LWJGL's native-call bridging
   (`MemorySegment.ofAddress` in `vkCmdSetScissor`, once per render pass). It predates this work; avoiding it needs
   a change in how commands are recorded, not in the world or model renderer.

No change was needed in the visibility walk, draw-record building or model instancing: at 42 entities the model
renderer does not appear among render-thread samples, and the walk is about a third of the remaining Java time at
render distance 8.

## Not done

Chat input, name tags, the remaining mobs and objects, block items in hand, inventory screens, block interaction,
vanilla's 10-tick swing cooldown after missing, speed-potion FOV, sounds (the Music section stays empty), and
plugins (the Plugins section stays empty).
