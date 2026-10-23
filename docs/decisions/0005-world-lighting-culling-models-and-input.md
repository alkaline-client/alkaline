# Architecture decision 0005: world lighting and culling, translucency, the model renderer, frame pacing and input

Status: accepted. Implemented 2026-10-17.

Measurements below: NVIDIA GTX 1660 SUPER (outside the support ceiling), driver 615.78.08, Linux (CachyOS,
Wayland session, client on XWayland), 1280x760, Overworld menu background, render distance 16, uncapped
presentation, 20 s runs with a 2 s warm-up (`game-client --stats`). They are development-host evidence, not
target-hardware qualification.

## Frame pacing

The frame loop polled the frame fence and slept 1 ms whenever the GPU was still busy, so frame intervals were
bimodal: p50 0.27 ms but p95 1.41 ms and p99 1.50 ms. `VulkanRenderer.awaitFrameSlot` now waits on the fence
with the driver's precise wait, before input is polled (the wait happens before input is sampled, not between
sampling and recording), and image acquisition waits with a bound instead of returning immediately.
Per-frame lambdas and struct wrappers in pass setup and barriers were removed, resource tracking no longer copies
an array per frame, and GPU timestamps report GPU frame time.

| Overworld, r16 | mean | p50 | p95 | p99 | allocation |
|---|---:|---:|---:|---:|---:|
| Before | 0.414 ms | 0.272 ms | 1.405 ms | 1.500 ms | 1,037 B/frame |
| Pacing fix only (old world renderer) | 0.300 ms | 0.272 ms | 0.508 ms | 0.690 ms | 916 B/frame |
| Now (lighting, sorting, occlusion culling) | 0.378 ms | 0.343 ms | 0.67–0.73 ms | 0.78–0.83 ms | 700–860 B/frame |

The world now draws vanilla smooth lighting, sorted translucency and more geometry, so the GPU frame rose
(0.25 to 0.35 ms) and frames are GPU-bound; CPU recording fell from 0.28 to 0.20 ms. The remaining allocation is
mostly LWJGL 3.4's FFM call path (a `MemorySegment` per native call that escape analysis does not always
remove). GPU-time spikes at p99 persist on this host; the UI upload (262 Compose frames per run, mostly during
the menu reveal) and water animation were ruled out as their main cause.

## World renderer

- **Vertex format.** 16 bytes per vertex (was 8): positions at 1/512 block with room for overhanging geometry,
  explicit texture coordinates, vanilla's baked vertex color (tint, directional shade, ambient occlusion, in 8
  bits with vanilla's float operations and truncation) and light sums. Geometry for the scene grew from 12.3 to
  37.7 MiB: twice from the vertex size, the rest because lit faces merge less.
- **Lighting.** `BlockSource.fill` now supplies light (sky and block levels) with a two-block halo. The mesher
  follows vanilla 1.8.9 smooth lighting: per-vertex averages of four cells with dark samples replaced by the
  center, 0.2 occlusion weights for normal cubes, and vanilla's corner substitution, which inspects the cells two
  layers out (hence the two-block halo). Triangles split along vanilla's quad diagonal. Leaves are normal cubes
  for occlusion but hide nothing, as in vanilla. Plants keep vanilla's per-position offset. The lightmap is
  computed once per frame on the CPU (`Lightmap`, vanilla's formula) and filtered in the fragment shader between
  the four nearest levels, as sampling vanilla's 16x16 texture does. Helper invocations extrapolate light levels,
  so they are clamped before indexing the table (an unclamped index hung the GPU).
- **Occlusion culling.** The mesher flood-fills each section and records which faces see each other. A
  breadth-first walk from the camera's section only steps away from the camera, through connected faces, into
  sections inside the frustum. It reads flat per-slot arrays of a power-of-two ring grid, never section objects.
  Its depth is exactly the Manhattan distance in sections, so the walk order is the translucent pass's order
  (reversed) and a good front-to-back order for opaque geometry, with no sort. Culling and draw setup take
  about 90 µs per frame for 1,146 reached sections (was 40% of the render thread in a per-frame scan of every
  loaded section).
- **Translucency.** If a quad in cell A hides part of a quad in cell B, A lies between the camera and B on every
  axis, so drawing cells by decreasing Manhattan distance from the camera's cell is exact for cube faces. It is a
  counting sort (at most 52 buckets) with stable order inside a cell. A section re-sorts only when the camera's
  cell, clamped to the section's span, changes, so sections outside the camera's slabs never re-sort. Sections
  whose translucent faces all face one way (open water) merge them and keep a static order. Index lists are
  double-buffered in their own pool, so a re-sort never waits for frames in flight. Inspired by, but not copied
  from, the problem Sodium's translucency sorting addresses (Sodium is PolyForm Shield licensed; no code was
  used).
- **Draw submission.** Draw records are built on the CPU and copied to the stream in one write (reading the
  write-combined stream back cost 2.5 ms per frame); each pass is one `vkCmdDrawIndexedIndirect` per geometry
  page, with section origins read through the instance index. `multiDrawIndirect` and
  `drawIndirectFirstInstance` are queried and enabled explicitly; without them, or with
  `-Dalkaline.world.directDraws=true`, the same records replay as direct draws (+0.04 ms CPU here).
- **Animated textures** no longer copy image layers or change the texture array's layout; a per-frame table
  maps each texture to the layer holding its current frame.

Not done: block models beyond cubes and plants (slabs, stairs, fences, liquids with sloped surfaces), biome
colors, emissive blocks in the menu terrain, and connection to a game world.

## Model renderer

`EntityRenderer` became `ModelRenderer` (`EntityModel` became `CuboidModel`): one instanced pipeline for posed
cuboid models and rigid baked models, sharing `BakedModel` geometry. Items render with vanilla's blending (alpha
over 0.1, blended) and an enchantment glint pass (vanilla's two scrolling layers, `src * src + dst`, drawn in
one pass by emitting the root of the summed increment).

**Held-item gaps.** Vanilla builds generated items from two alpha-tested 16x16 quads plus edge quads that span
whole rows, with texture coordinates pulled 0.1% inward; front-face pixel boundaries come from texture sampling
while edges come from geometry, and they never meet exactly. `ItemModels.extrude` builds a closed surface on the
pixel grid instead: per opaque pixel a front and back quad, per silhouette edge a side quad, all from the same
integer grid, each sampling its texel center. A test proves every edge is shared bit-for-bit by exactly two
quads. In first-person renders (`diagnostics --held-item diamond_sword --zoom 3`, ten swing positions) the vanilla
geometry showed 24 background pixels enclosed by the item; the extrusion showed none besides one at the tip of
a real concave notch. Cost: 248 quads for a diamond sword (vanilla: 50), built once.

`FirstPersonItem` reproduces vanilla's first-person placement (equip, swing, arm lag, the model's display
transform) and item lights; `ItemDefinition` reads item models and display transforms from the jar through
their parent chain.

## Input

- **Controls.** A Controls settings category holds vanilla 1.8.9's default bindings as a new setting kind
  (`Setting.KeySetting`: `key:<physical key name>`, `mouse:<button>` or `none`), grouped as vanilla does, plus
  mouse sensitivity and invert. Bindings follow physical key positions (SDL scancodes). Selecting a binding
  listens for the next key or mouse button; Esc cancels, Backspace unbinds; shared inputs show in the error
  color. Gameplay does not consume them yet.
- **Linux click rate.** libinput (used by every X server and Wayland compositor) debounces buttons: a release and
  the next press within 25 ms of the previous event are filtered (`DEBOUNCE_TIMEOUT_BOUNCE`), unless the device
  has the `ModelBouncingKeys` quirk. Fast butterfly and drag clicking produces exactly that, so those clicks
  never reach SDL; Alkaline is affected like any application. Alkaline's own path counts every delivered press.
  `EvdevMouseButtons` reads buttons from `/dev/input` directly, bypassing libinput, where the player has access
  (the `input` group; not granted on the development host). `diagnostics --click-test` compares SDL and kernel
  click counts and prints a per-device quirk that disables debouncing system-wide as the alternative. The game
  input layer should take attack and use presses from this reader when it is available.

Sources: libinput `src/libinput-plugin-button-debounce.c` (MIT) and the libinput button debouncing
documentation; vanilla behavior from the 1.8.9 MCP reference.
