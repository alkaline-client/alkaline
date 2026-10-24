# Architecture decision 0015: Renderer performance pass and the first Vulkan 1.4 path

Status: accepted. Implemented 2026-10-24, after the wave 2 merge (decision 0014).

The goal was more frames without changing the picture. Each change below was measured before it was kept.
Changes that alter the output were checked against a run of the old order or the old build.

## Measuring

- `--stats` takes `-Dalkaline.stats.warmupMs`, so a scripted scene is measured without loading and joining.
- With `--stats` the renderer writes extra timestamps at marks in the frame (`VulkanRenderer.gpuMark`) and reports
  the mean GPU time of each section: setup, sky, clouds, terrain, entities, damage and particles, weather and
  translucent, border and hand, blit, blur, backdrop, ui. The marks cost nothing when stats are off.
- The report also gives the time spent creating static textures, split at 1 MiB.
- CPU hot spots came from JFR with `-XX:+DebugNonSafepoints` and 1 ms sampling. Without that flag JFR put the time
  of inlined code on the wrong lines; it blamed the font lookup for time spent writing vertices.

## Changes

CPU, render thread:

- **Writes into mapped memory.** Name tag quads and model instances were written through `ByteBuffer.put*`, with a
  bounds check and a memory-session check on every call. That was about a quarter of the frame in a scene with
  name tags. `QuadRenderer` now writes vertices by address and copies each batch once (`VulkanFrame.streamPointer`).
  `ModelRenderer` writes matrices and instances the same way.
- **Font.** `VanillaFontLayout.atlasIndex` was a `String.indexOf` per glyph. It is now a char table, and a test pins
  the table to `indexOf` for all 65536 chars.
- **Poses.** `PoseMath.resolve` reads parents from a flat array (`CuboidModel.parent`) and tracks hidden parts in a
  bitmask. It skips sine and cosine for zero angles, which most parts have.
- **Items.** Item stacks are immutable records whose `hashCode` and `equals` walk the NBT tag. `ItemCatalog` now
  keeps the model, tints, glint style and armor rule per stack instance (an identity map), and no longer allocates
  a tint array per draw.
- **Chunk columns.** `ClientWorld.column` had one cache entry. Entities spread over chunks missed it on every
  lookup, then boxed a key and allocated an entry. It is now a 64-entry direct-mapped cache of immutable entries.
- **Visibility walk.** The walk read six parallel arrays per neighbor. Slots now hold a packed key, the
  visibility with a geometry bit, and a stamp-and-faces mark, so a neighbor costs two reads. Frustum planes carry
  a precomputed section reach. When the camera, frustum and section grid are unchanged, the walk's result is
  reused.
- Small allocations were removed: the sheep color table per sheep, and boxing on lookups in empty sets.

GPU:

- **No world-to-scene copy.** `MenuBackground.needsDepth` lets the compositor give its scene target a depth
  attachment. `GameView` then draws into the scene directly and skips its own target and the full-screen blit.
  The old path stays as a fallback.
- **UI composite by rows.** `ComposeLayerHost` already compares rows on the UI thread. It now also reports the row
  bands that hold any pixel. The compositor blends the Compose layer only over those bands, each under a scissor.
  The HUD leaves most of the screen clear.
- **Hidden sky work skipped.** An OptiFine `blend=replace` layer is unblended and alpha-tested at 0.1. When its
  texture's lowest alpha passes that test at the layer's brightness, it overwrites every sky pixel. Then the upper
  plane, the sunrise fan and every earlier layer are not drawn. The custom-sky cube is now welded: corners come
  from exact quarter turns and one shared transform, so shared edges are bit-identical and the cube has no
  cracks.
- **Sky after the terrain.** With the device's `depthClamp` feature, the sky draws at the far plane: z goes past w
  and the pipeline clamps depth to exactly 1.0, depth-tested and with no depth writes. Drawn first it looks as
  before. Drawn after opaque and cutout terrain, it shades only the pixels the terrain leaves uncovered. `GameView`
  draws it after the terrain unless fancy or fast clouds lie between the sky and the terrain (clouds on and the eye
  below them), because the clouds' depth would then change what shows. `WorldRenderer.renderSolid` now sets its own
  y-up viewport, because it no longer always follows the sky.

## Vulkan 1.4: host image copy

The instance asks for API 1.4. Features beyond 1.3 stay optional and are checked per device.
`VulkanDeviceContext.supportsHostImageCopy` requires:

- a 1.4 device with `hostImageCopy`;
- host transfer support for RGBA8 UNORM and sRGB;
- `SHADER_READ_ONLY_OPTIMAL` among the copy destination layouts;
- `optimalDeviceAccess`, so the images are not slower to sample.

On such a device, static textures up to 1 MiB are written from the CPU. The image moves to its shader layout and
each mip level is copied, with no staging buffer, no submit and no `vkQueueWaitIdle`, which used to drain the
frames in flight. These are the textures made during play: skins, items, maps, font pages and animation frames.

- **Why the 1 MiB limit:** on the GTX 1660 SUPER without resizable BAR, `identicalMemoryTypeRequirements` is false
  and host-transfer images may need the 246 MiB host-visible device heap. Copying every texture this way, the 25 MB
  sky images included, ran out of that heap and the join failed.
- **Fallback:** larger textures keep the staging copy. An allocation that still fails retries without host
  transfer.
- **Validation:** a full session under the Khronos validation layer reported nothing.

Not used yet: push descriptors, `dynamicRenderingLocalRead` and maintenance 5 and 6, which this device also has.
They would save little in the measured frames.

## Results

- **Setup:** GTX 1660 SUPER, NVIDIA 615.78.08, Linux 7.3 (CachyOS), Wayland, mailbox, V-Sync off, Java 25.
- **Scene:** Xe Lite, 12 chunks, a flat world at 18:30, 63 named NoAI mobs, 7 armor stands in enchanted diamond
  armor, water, stained glass and panes.
- **Method:** 23 s measured after 22 s, the old build (main at 2f19d17 with only the warm-up property added)
  against the new one.

| Window | fps old → new | p99 frame ms | GPU frame ms | Render thread bytes/frame |
|---|---|---|---|---|
| 1280×760 | 1942 → 2476 | 0.792 → 0.637 | 0.313 → 0.258 | 11854 → 2500 |
| 1920×1080 | 1525 → 1943 | 1.092 → 0.917 | 0.541 → 0.420 | 13712 → 3160 |
| 3840×2160 | 495 → 670 | 2.732 → 2.139 | 1.865 → 1.400 | 27051 → 13723 |
| 1920×1080, clouds off | 1571 → 2285 | 1.097 → 0.860 | 0.523 → 0.340 | 13459 → 2955 |

- **Static camera:** the camera does not move, so the reused visibility walk flatters the CPU numbers. The other
  CPU changes hold while moving.
- **GPU sections at 1080p:** the sky fell from 0.182 to 0.136 ms (0.058 with clouds off). The world-to-scene blit
  (0.047 ms) is gone, and the UI composite fell from 0.038 to 0.006 ms.
- **Textures up to 1 MiB,** two runs each: 0.64 ms mean with host copy, 1.7 to 1.9 ms with staging. The worst
  single upload was 3.9 to 5.5 ms against 3.3 ms.
- **Pixels:**
  - drawing the sky after the terrain (clouds off, 1080p) left the terrain unchanged pixel for pixel;
  - the sky differed no more between the two orders than between two runs of one order, because Xe Lite's layers
    turn with the partial tick;
  - the Overworld menu background matches the old build.

## Not done

- **Load-time textures:** the block texture array takes about 1.2 s on the render thread (CPU mip chain and
  staging), and the large sky images about 200 ms each. Moving that work off the render thread would shorten pack
  reloads.
- **Sky with clouds below the eye:** it still draws first. Drawing it after the terrain would change where fancy
  clouds hide terrain.
- **Remaining GPU costs:** the scene-to-swapchain backdrop (0.21 ms at 4K) and full-screen custom sky layers
  remain the largest. Merging layers in one shader would change 8-bit blending between layers.
- Results are from one GPU above the supported ceiling. GTX 10 series and older were not measured.
