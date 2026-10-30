# Architecture decision 0017: The Nether and the End, client relighting, fog in gamma space, held item size

Status: accepted. Implemented 2026-10-30.

Each change was checked against vanilla 1.8.9 itself: the real client (Java 8, LWJGL 2) and Alkaline joined fresh
worlds on the local 1.8.9 server, ran the same scripted commands, and their screenshots of the same viewpoint were
compared by region.

## Held items

`RenderItem.preTransform` doubles every flat item model held by an entity, and `ItemRenderer.renderItem` doubles 3D
(block) models instead. So every held model is doubled once. `FirstPersonItem` doubled only block models, so swords,
tools and food in first person were half their vanilla size. It now doubles every model, and the `block3d`
parameter is gone. Third person already did this.

Dropped items were checked as well: a sprite is half a block and a block a quarter, as in vanilla. They differed
only in their spin phase.

## End chunks: void columns in a Map Chunk Bulk

The End lost whole sections near its island (`ArrayIndexOutOfBoundsException` in `Blocks189.neighborBrightness`,
block ids above 255; see decision 0015, Not done). A TCP proxy recorded the server's bytes. The server sent clean
data, and the client misread it.

- **Cause:** a bulk entry with an empty section mask is a void column, with no sections and only its 256 biome
  bytes. `ClientWorld.applyChunk` treated "ground-up, mask 0" as an unload and returned before reading those bytes.
  Every later column in the same packet then read its block states 256 bytes early, from the previous column's light
  and biomes.
- **Why only the End:** Overworld and Nether columns always hold bedrock. It varied from run to run with how the
  server batched columns.
- **Fix:** `applyChunk` now always reads the column and loads an empty one, as `handleMapChunkBulk` does. The unload
  moved to the single Chunk Data packet, where vanilla's `handleChunkData` does it.
- `ClientWorldTest` pins the alignment. Four End runs that failed every time before the fix now have none.

## Dimensions

The renderer had no notion of dimension: the Nether and the End were drawn with the Overworld's sky, fog and
lightmap, and the End came out nearly black. `Dimension` (renderer `world`) carries what each WorldProvider
changes, and `PlaySession` takes it from Join Game and Respawn.

- **Celestial angle:** noon in the End, midnight in the Nether. Sun brightness, sky color and the sunrise (none in
  the End) follow from it.
- **Fog color:** the Nether's is 0.2, 0.03, 0.03, and the End's is 0xA080A0 at 0.15. Both blend toward the sky color
  by render distance and are dimmed by the eye's light, as in the Overworld.
- **Fog distance (doesXZShowFog):** linear from 5% of the render distance to half of it, at most 96 blocks.
- **Sky:** clouds, the sky planes, the sun, the moon and the stars are drawn over a surface world only. The Nether
  clears to its fog color. The End draws `renderSkyEnd`'s box over it: six faces of the plane y = -100, the texture
  tiled 16 times, colored 40/255.
- **Lightmap:** the Nether's light table has a floor of 0.1. The End uses its own formula, 0.22 / 0.28 / 0.25 plus
  three quarters of the block light.
- The resource packs' sky and fog colormaps and the custom sky (`world0`) apply in the Overworld only.
- Leaving the End: the server sends Change Game State 4 (the credits) and keeps the player in the End until the
  client asks to respawn. There are no credits here, so the client asks at once. Before this, an End exit portal
  left the player stuck. The Overworld draws correctly after the return.

Measured against vanilla at the same viewpoints: walls, fog and terrain at the screen center agree within a few
levels of 255. The edges stay brighter, because vanilla's darkness vignette (`GuiIngame.renderVignette`) is not
implemented.

## Client relighting

The server sends light only with whole chunks. Vanilla's client relights every block change itself
(`World.checkLightFor`); Alkaline did not. A lava fall that starts flowing after its chunk arrives, and in the
Nether most of them do, stayed unlit and nearly black. A placed block cast no shade.

- `Relighter` (game `world`) follows `checkLightFor`: a breadth-first removal, then a refill from the remaining
  sources, within 17 blocks, with `getRawLight`'s opacity rules.
- Sky light also follows the column's height (the top block with any opacity), as `Chunk.relightBlock` moves it.
- Light is written into copies of the touched sections, which are published together, so meshing threads never see
  a half-relit section.
- `ClientWorld` gets block opacity and emission through `LightRules`. The game client passes the renderer's
  `Blocks189` table, so the game module does not depend on the renderer.
- A block set into a missing section of a dimension without sky no longer creates the section with full sky light.

## Fog in gamma space

Vanilla's fixed-function fog blends the 8-bit gamma-encoded color. The world shaders blended linear values. A bright
surface behind dark fog, such as lava in the Nether or a lit wall at night, came out two to three times too bright:
a lava fall measured 57 against vanilla's 29, and now measures 34. `fogMix` (world_common.glsl) and `model.frag`
blend in gamma space. Fragments without fog skip the conversion. Clouds already drew in gamma space.

## Terrain lightmap as a filtered texture

The terrain shaders read the lightmap from the frame table: four global-memory loads, a bilinear filter in the shader,
then the sRGB decode. Replacing the lookup with a constant (an experiment, not kept) cut the terrain pass by a third,
so the lookup was the pass's largest cost.

The lightmap is now a 16x16 BGRA8 texture (`VulkanDynamicTexture`). It is uploaded only when it changes and sampled
with linear filtering and clamping at (level + 0.5) / 16, which is how vanilla addresses its own lightmap texture,
with the same fixed-point filter weights. The sRGB decode stays after the filter: an sRGB view would filter in linear
space and change the picture.

Measured on a GTX 1660 SUPER (NVIDIA 615.78.08, Linux 7.3, Wayland, mailbox, V-Sync off) at 1920x1080, render
distance 12. The scene was a default world with seed 1, on a hilltop at (96, 75, -96) looking out over land and sea;
22 s warm-up, then about 23 s measured, one run per build:

| | fps | p99 frame ms | GPU frame ms | terrain ms | water ms |
|---|---|---|---|---|---|
| before | 2318 | 0.785 | 0.362 | 0.117 | 0.037 |
| after | 2621 | 0.736 | 0.315 | 0.091 | 0.029 |

Pixels: the terrain region of the two captures differs less than two captures of the old build do (animated water
and leaves). Only 431 of 580500 pixels differ by more than 2 levels.

Panama, or Nalim through JVMCI, would remove the JNI transition from Vulkan calls. That is not worth doing yet. JFR
puts the render thread's native time in fence waits, submit and present, not in call overhead. Terrain goes out as
a few multi-draw-indirect calls. The frame is GPU-bound: 0.14 ms of CPU against 0.32 ms of GPU.

Largest remaining GPU costs at 1080p: the sky planes (0.073 ms, drawn first whenever clouds lie above the eye) and
the scene-to-swapchain copy (0.055 ms, bandwidth-bound). Only a compositor that renders into the swapchain and copies
just the glass regions would remove the copy.

## Not done

- End crystals (object type 51) are not drawn.
- Entity shadows (`Render.renderShadow`) do not exist for any entity.
- The darkness vignette is not drawn (see above).
- OptiFine's End and Nether colors and the `world1` custom sky are not read.
- Water was not compared against vanilla.
