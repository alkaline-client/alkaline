# Architecture decision 0006: block models and liquids, partial multiplayer, sky and weather, options, and the Linux click fix

Status: accepted. Implemented 2026-10-17.

Measurements below: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS,
client on XWayland), 1280x760, Overworld menu background, uncapped presentation, 40 s runs with a 2 s warm-up
(`game-client --stats`). They are development-host evidence, not target-hardware qualification.

## Linux click rate: what Minecraft uses, plus a one-click quirk

Decision 0005 bypassed libinput by reading `/dev/input` directly. That needs the `input` group, and it reads
input from every application, so it was dropped (`EvdevMouseButtons` is deleted). Alkaline cannot replace the
compositor's libinput, and patched libinput builds are system packages, not something a client should ship.
Alkaline now takes clicks from the window system through SDL, as Minecraft does, and offers the fix players already
use: libinput's `ModelBouncingKeys` quirk turns off button debouncing for mice (the same quirk libinput ships in
`/usr/share/libinput` for specific devices).

- `LibinputClickFix` reports whether the fix is installed, and installs or removes it through `pkexec` and a
  bundled script (`alkaline-click-fix.sh`). The script writes one marked section into
  `/etc/libinput/local-overrides.quirks`. Removing it restores the file byte for byte. Host libinput: 1.32.
- `diagnostics --click-test` counts SDL clicks and reports the fix status. `diagnostics --click-fix
  install|remove|status` runs the fix. Alkaline's dedicated launcher (planned) is the intended home for the button.
  The game itself never changes system files.
- The quirk applies after the compositor or X server restarts (log out and back in). It affects every mouse
  in the session, which is what players expect from it.

Sources: libinput's quirks documentation and shipped quirk files; community advice for 1.8.9 PvP on Linux (the
Hypixel forum "libinput-debounce-patcher" thread, Arch and Garuda forum posts). Native Wayland support is
planned; the client still runs through XWayland and that is not changed here.

## Enchantment glint

The glint looked wrong because vanilla blends it in gamma space (`GL_SRC_COLOR, GL_ONE` on an 8-bit sRGB-encoded
framebuffer), while Alkaline's sRGB attachments blend in linear space. Frames can now suspend linear rendering
(`frame.beginGammaSpace()`/`endGammaSpace()`): color targets and the swapchain get a UNORM alias view
(`VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`, `VkImageFormatListCreateInfo`, and `VK_KHR_swapchain_mutable_format` where
present), the pass resumes on the alias with load operations, and depth is stored across the switch. The glint
draws vanilla's two layers (scroll and rotation, sprite span 16/512, scaled by the lightmap). Without the
extension a linear fallback approximates the result. The sky planes and celestial bodies use the same gamma path.

## Block models and liquids

`BlockPalette.load` compiles the jar's blockstates and models (about 270 ms: 1,969 models, 2,908 states, 362
textures). The parts:

- **States.** 1.8.9 state ids (`id << 4 | meta`) are the world's block ids, so the server's chunk data needs no
  translation. `Blocks189` holds per-block data (material, render type, opacity, light, offset, tint, culling
  rule, translucency). `BlockStates189` maps states to blockstate variants. `NeighborRules` derives the states
  that vanilla computes from neighbors in `getActualState`: fences, panes, walls, gates, stair shapes, redstone,
  tripwire, doors, double plants, stems, vines, fire, repeater lock and snowy grass.
- **Baking.** `BlockModels189` reproduces FaceBakery (element rotation with rescale, model rotations, uvlock,
  face uv rotation, facing from vertices, shading) and weighted variant picking with vanilla's position hash.
- **Meshing.** The cube fast path keeps greedy merging, with an appearance key for each face (texture layer,
  orientation, tint and overlay, such as the grass side overlay). Every other model emits its baked quads with
  vanilla's flat or smooth lighting (`AmbientOcclusionFace` with bounds flags, neighbor weights and vertex
  translations; `useNeighborBrightness`; the slab rule; emission) and vanilla's side-culling rules per block
  kind. Leaves have no cull faces in vanilla's models: they mesh as cubes and drop only faces against opaque
  cubes.
- **Liquids.** `BlockFluidRenderer`: corner heights, flow direction using vanilla's sin/cos table and fast
  atan2 (`VanillaMath`), two-sided sides, undersides. A section whose translucent quads all lie on one axis keeps a
  static order (positive faces ascending, then negative faces descending), which covers open water.
- **Textures** load by path and upscale to the array's layer size. The mip count follows the layer size.

`diagnostics --block-gallery [view]` renders ten test views (stairs, fences, panes, plants, liquids, redstone,
and a tripwire close-up). Validation reports no errors.

**Draw batches.** At render distance 32 the back-to-front translucent pass switches between geometry and index
pages more than the fixed 64 batches allowed. The remaining translucent sections were dropped, so lakes went
missing at some camera angles. The batch lists now grow. At r32 with the same scene, p99 fell from 3.94 to
1.41 ms, partly because the per-frame warning print is gone.

| Overworld | sections drawn | quads | geometry | mean | p50 | p95 | p99 | allocation |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| r16, run 1 | 336 | 177 k | 49.3 MiB | 0.791 ms | 0.641 ms | 1.855 ms | 3.024 ms | 816 B/frame |
| r16, run 2 | 336 | 177 k | 49.3 MiB | 0.464 ms | 0.409 ms | 0.849 ms | 1.155 ms | 833 B/frame |
| r32 (dropping sections) | 1,226 | 683 k | 181.9 MiB | 1.400 ms | 1.311 ms | 2.404 ms | 3.937 ms | 2,073 B/frame |
| r32 (growable batches) | 1,225 | 684 k | 181.9 MiB | 0.803 ms | 0.753 ms | 1.169 ms | 1.408 ms | 941 B/frame |

The two r16 runs differ only in timing; at r16 the batches never ran out, so the change does not affect them. The
host's run-to-run variance is that large, and its spikes are CPU-side (run 1: CPU record p99 2.98 ms, GPU frame
p99 0.42 ms). Run 2 (CPU p50 0.23 ms, GPU p50 0.39 ms) is GPU-bound, and lands close to decision 0005 although
the scene now has models, liquids and the sky. A reproducible benchmark harness is still missing.

## Multiplayer (partial)

A protocol 47 client in `modules/game`:

- **Connection.** `Login` handles handshake, login, encryption (AES/CFB8, Mojang session join for online
  mode), and compression. `ServerSession` reads play packets on its own thread and queues events. `ClientWorld`
  stores chunks as copy-on-write sections, with block changes, biomes and cached precipitation heights.
- **Player.** `LocalPlayer` is vanilla's movement and collision: walk and sprint, friction, gravity, jumping,
  step-up, sneak edges, flight, water and lava. Tests check the walk speed (0.2159 blocks per tick) and the jump
  peak (1.252).
- **Game loop.** `PlaySession` applies the events and moves the player from the Controls bindings. It reports
  movement as `onUpdateWalkingPlayer` does.
- **Rendering.** `GameView` streams the world's sections into the world renderer.

Multiplayer's Join connects; the pause card (Esc) resumes or disconnects. Settings and the client brand go out
after Join Game, not before, which vanilla servers otherwise kick for. Tested against Mojang's 1.8.9 server in
offline mode: login, chunk streaming, walking and sprinting without "moved wrongly" warnings, day/night and weather.

Not implemented: entities and other players, chat UI (chat prints to stdout), HUD, inventory, block breaking and
placing, client-side relighting, the death screen (death respawns at once), sounds, biome colors, online-mode
sign-in on the Join path, and singleplayer.

## Sky, stars, sun, moon and weather

`Sky` computes vanilla's values from the server time, weather and the biome temperature at the player:

- **Colors.** Sky color via `getSkyColorByTemp`, darkened by rain and thunder. Fog color with the sunrise tint
  toward the sun, blended to the sky color by render distance, dimmed by the light where the player stands
  (smoothed per tick) and by the void below y = 32.
- **Celestial.** The sunrise fan, star brightness, sun brightness for the lightmap, and the moon phase.
- **Fog.** `SkyRenderer` draws the sky planes and void box, the sunrise fan, the sun (30) and moon (20) at
  distance 100, and the 1,500 stars from vanilla's seed. Terrain fog is radial and linear from 3/4 of the
  render distance to its end, and exponential under water (density 0.1) and in lava (2.0).
- **Rain and snow.** Drawn per `renderRainSnow`: precipitation heights, biome temperature (rain at 0.15 and
  above, else snow) and the lightmap. Rain and thunder strength come from the server's Change Game State
  reasons 1, 2, 7 and 8.

`Biomes189` holds the climate per biome id (mutated variants included) and vanilla's temperature noise above
y = 64. Verified in game: stars and the moon at midnight with its phase, the sunset fan, and rain with a
darkened sky. Not done: clouds, lightning, rain splashes and sounds, the End and Nether skies.

## Options

Option entries that Alkaline can already honor now follow Minecraft 1.8.9, or OptiFine for the sky and fog
parts. Each takes the reference's range, steps, default and value labels. No feature was added for them.

| Entry | Source | Range and labels |
|---|---|---|
| Max framerate | vanilla | 10–260 in steps of 10, 260 = "Unlimited" (default) |
| Render distance | vanilla (64-bit, 1 GB heap) | 2–32 chunks |
| FOV | vanilla | 30–110, "Normal" at 70, "Quake Pro" at 110 (in game) |
| Brightness | vanilla | "Moody" to "Bright", "+N%" between (the lightmap's gamma; in game) |
| Mouse sensitivity | vanilla | "\*yawn\*" at 0, "HYPERSPEED!!!" at 200%, percent between |
| Fog, Fog start | OptiFine | on/off (fast fog is not implemented); 0.2–0.8, default 0.75 (vanilla's value) |
| Sky, Sun & moon, Stars | OptiFine | Sky off clears to the fog color, as in OptiFine |
| Rain & snow | OptiFine (Fancy/Off) | Off hides precipitation only; the sky still darkens |

Smooth lighting stays on/off because vanilla 1.8.9's Minimum and Maximum render the same. Existing Alkaline
entries (frame sync, GUI scale, FPS counter, invert mouse) keep their behavior. The settings schema is now
version 3. Its migration maps the old "0 = unlimited" frame limit to 260 and clamps other values to 10–260
(`ClientSettingsMigrationTest`).

Not ported because Alkaline lacks the feature: view bobbing, clouds, particles, graphics fancy/fast, mipmap
levels, anisotropic filtering, antialiasing, animated-texture toggles, chat options, fullscreen, and the
remaining OptiFine entries.

## Build

`processResources` depends on `packageVulkanShaders`, so editing GLSL regenerates the SPIR-V on any build, with
up-to-date checks on inputs and outputs. Without `glslc` the build warns and keeps the checked-in binaries.
