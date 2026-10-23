# Vulkan world and entity renderer roadmap

**Status:** CPU mesher and indexed Vulkan smoke verified; game integration and visual parity remain open  
**Scope:** `legacy/`, Minecraft 1.8.9 only  
**Last updated:** 2026-10-11

## Executive assessment

The legacy renderer now has a persistent Vulkan 1.3 indexed-mesh frame smoke path, device-local staging uploads, a padded CPU section mesher, and strict PNG comparison documentation. It still has no typed chunk/block/entity snapshot boundary, Minecraft runtime adapter, vanilla asset loader, automatic capture producer, or comprehensive live-game evidence. The indexed mesh smoke proves only that this host can create the pipeline, upload a triangle, apply an object transform, and present frames; it is not game rendering.

The CPU mesher is intended for section rebuilds: capture a stable material neighborhood when a section changes, mesh it on a bounded worker, then cache/upload the result and draw that geometry repeatedly. It must not run per frame. **That pipeline is not implemented yet.** The current mesher is a correctness baseline, not a performance-first renderer: it scans all voxels, checks up to six neighbors for non-empty cells, emits four duplicated vertices for every visible face, uses five `int` fields per vertex, allocates all three pass-index arrays up front, and has no throughput/allocation benchmark. The next mesher step must measure representative workloads, reduce geometry/data volume (e.g. compatible greedy face merging and compact reusable output), and compare alternatives before making performance claims.

A complete, visually verified world and entity renderer remains dependent on a persistent presentation path, vanilla asset loading, game integration, deterministic captures, graphics validation on target hardware, and correctness/performance iteration.

## Effort and model-token estimates

These ranges are **planning assumptions**, not measured results. Tokens refer to cumulative model input plus output tokens across implementation and verification agents, not wall-clock time or API billing units. A capable coding model, pinned project build, bounded independent verifiers and no hidden full-client rewrite are assumed. Three initial read-only exploration agents reported **1,137,576 subagent tokens**; subsequent checker and review tasks reported another **683,729**, for **1,821,305 reported subagent tokens** as of the first implementation round. The three latest scoped architecture audits in the follow-up round reported another **757,052** (143,290 + 355,667 + 258,095), bringing the reported subagent subtotal to **2,578,357**. Main-agent token usage is unavailable, so actual total is higher but unknown. This is evidence that broad LLM reconnaissance has been too expensive relative to delivered code. From here, use narrow file scopes and verifier-only reviews; record token totals and recalibrate at each gate. Do not treat earlier remaining-work estimates as a reliable whole-campaign budget.

| Scope | Engineering effort (person-weeks) | LLM token envelope | Confidence |
|---|---:|---:|---|
| Vulkan frame/resource abstraction: device/queues, swapchain, resize, command submission, synchronization, basic pipeline/draw, teardown and tests | 4–8 | 0.25–0.65M | Low-medium |
| Integrated basic world renderer: renderer-facing world snapshots, section meshing, upload/batching, opaque/cutout/translucent treatment, texture/material path, culling and first profiling | 9–18 | 0.45–1.2M | Low |
| Entity renderer: model/pose input, transforms, batching, texture/material integration, deterministic tests/captures | 3–7 | 0.15–0.45M | Low |
| Capture, comprehensive visual verification, compatibility/performance qualification and iteration across the above | 4–10 | 0.25–0.8M | Low |
| **Combined usable foundation through basic integrated world/entity renderer** | **20–43** | **1.1–3.0M remaining** | **Low** |

The three exploration agents already used **1,137,576 subagent tokens** before implementation. That historical subtotal understates current spend; including later subagent reports gives **1,821,305 reported subagent tokens so far**, with main-agent usage unavailable. Do not calculate a precise whole-campaign estimate from unknown main-agent usage. The combined remaining estimate is not the sum of all upper/lower rows mechanically: stages share bounded context packs, helpers and test infrastructure. It excludes future model retries caused by unavailable dependencies, major design reversals, client acquisition/startup rework, and broad mod compatibility.

## Language and CPU/GPU placement

Keep CPU meshing off the render loop: on section invalidation, a stable snapshot is meshed on a bounded worker, cached, and uploaded; unchanged sections are drawn repeatedly without rebuilding. The current scalar mesher has no production caller or benchmark and emits four duplicated vertices for every visible voxel face, so it is a correctness baseline—not a performance-first implementation. Its next gate is representative measurement and output compaction/greedy merging, not assumption that CPU is inherently better.

Java remains the current renderer implementation language and Kotlin is viable within the same JVM/runtime and Gradle module. For either, use primitive packed data, reusable per-worker buffers, bounded queues, and measurements of p50/p95 build time, output bytes, allocations, and upload cost. Rust is also a viable candidate, but only for a coarse, batch-oriented native boundary that beats optimized JVM code end to end. Compare release builds including input/output conversion, FFI calls, memory lifetime costs and Windows/Linux artifact build/distribution. Proposed adoption gate: a repeatable >=20% reduction in end-to-end p95 mesh+pack time on representative workloads and target-class hardware, with no correctness/maintenance regression. Language choice alone does not reduce GPU work, draw calls, synchronization, or frame time.

- **Original Alkaline implementation (recommended):** estimates above. The work is explicit and verifiable against our own contracts and API documentation.
- **Reuse high-level design ideas from other renderers:** may reduce exploration and design time modestly, but the 1.8.9 data/runtime boundary, Vulkan backend and required tests remain. Budget roughly **0–15% less** than the original plan only after a concrete independently understood idea is identified and verified. This is not a promise of savings.
- **Copy decompiled Minecraft 26.3 renderer code:** not an approved implementation route. It is a different version and backend, the project requires independent design, and licensing/provenance plus semantic mismatch add risk. Without inspecting or copying it, any quantitative savings are unsubstantiated; use no claimed discount (planning range remains **1.1–3.0M tokens**) and require legal/provenance approval before any future reconsideration. This plan deliberately contains no original-renderer implementation details.

These are separate scopes. A functioning generic Vulkan frame path does not imply Minecraft compatibility, and a CPU mesher test does not prove GPU frame-time or pixel parity.

## Token-efficient execution protocol

The aim is not to minimize tokens at the expense of correctness. Spend a bounded amount to make each change understandable, then use deterministic verifiers to catch mistakes cheaply before asking a model to reason over a larger failure.

### Context pack per task

Pass only:
1. The relevant public interface(s), directly called implementation(s), tests, build file, and project instructions.
2. The exact accepted behavior and measurable success criteria.
3. A compact dependency/ownership note and the permitted knowledge boundary.
4. The latest test/build output, not an unfiltered session transcript.

Do not pass full repository dumps or decompiled renderer material. Keep design context and verification context separate. An implementation contributor must not receive or inspect any implementation details of Minecraft's original world renderer. The dedicated consistency-check owner is the only exception to that separation, and its scope is limited to the check/capture contract—not renderer internals.

### Small-batch loop

For each milestone:
1. State assumptions, API contracts, and an explicit stop condition before coding.
2. Implement one coherent batch (ideally one narrow interface/helper and its tests).
3. Run formatting/static checks if configured, then the narrowest unit test/build that proves the batch.
4. Have an independent read-only verifier inspect the diff and test evidence using the same allowed sources, with no renderer implementation leakage.
5. Fix concrete verifier findings, rerun the failed check, then record measured tokens and elapsed time for recalibration.

Do not ask a verifier to restate the design or re-review untouched files. Use verifier work to challenge failure modes, lifecycle edges, deterministic output and hot-path allocation. If a check fails, provide the verifier the failing output and the smallest relevant source slice. Retry only after identifying a specific correction.

### Token budget controls

- Treat the table as a planning envelope, not an authorization to spend to the ceiling.
- At each milestone, capture actual input/output token use from available run metadata. If telemetry is unavailable, mark actual usage **unknown** rather than infer it.
- Pause at a boundary when a stage exceeds its midpoint estimate by 50%; write the cause and revise the next estimate before continuing. Do not pause mid-fix for a deterministic test failure.
- Reserve approximately 20–30% of the token envelope for verifier passes and targeted repairs; do not funnel the whole budget into first-pass generation.
- Prefer source-level tests, Gradle checks, Vulkan validation layers when available, and image diffs over model-only self-assessment.
- Never “save tokens” by weakening zero-tolerance image comparison, skipping lifetime/error-path tests, or treating compilation as runtime proof.
- Defer optimization claims until repeatable benchmarks exist. No claim of Sodium-level performance without same-machine, same-scene, same-settings measurements and recorded frame-time percentiles/allocation data.

## Phased delivery and gates

### Phase 0 — Establish visual acceptance contract (first)

**Owner:** dedicated screen-space-check subagent. No renderer code until this gate has been set up.  
**Deliver:** documented capture manifest and broad scene matrix; harness/test changes only when they close a concrete gap. Keep the current exact decoded-RGBA comparison contract. Use generated fixtures only to test the comparator and report format. Real visual acceptance requires authorized local vanilla 1.8.9 assets and deterministic, matching game states; never commit game assets. Sodium/Radium is an optional additional comparison only if its 1.8.9 install and deterministic capture route are verified from documented/API-level facts; it is not the expected image oracle.  
**Gate:** tests pass; output clearly distinguishes synthetic harness evidence from actual vanilla captures; unimplemented capture production stays explicitly open.

### Phase 1 — Vulkan helper groundwork

Add narrow pure functions and tests for swapchain selection inputs: surface-format preference, present-mode selection, extent clamping (including fixed-extent surfaces), image-count bounds, and queue-family selection if a missing edge is demonstrated. Keep result types small and immutable. Query/enable optional device features explicitly when the actual renderer uses them.  
**Gate:** focused tests cover preferred and fallback cases, invalid/empty inputs, and min/max boundaries; `renderer` compiles under the pinned toolchain.

### Phase 2 — Alkaline-owned world input and CPU section mesher

The CPU mesher is a **section rebuild task**, not per-frame rendering: when a section changes, a bounded worker receives an immutable/stable padded snapshot, generates a compact mesh, and hands the result to an upload queue. The mesh is cached and drawn across frames until invalidated. Do not mesh on the render thread or rebuild unchanged sections.

First benchmark the correctness baseline with deterministic empty, solid, surface-heavy, sparse, mixed-material and translucent sections at representative sizes. Record p50/p95 time, voxels scanned, faces/quads emitted, output bytes, allocations, and capacity growth; prepare inputs outside the timed region. Then compare an optimized compatible-face greedy path and compact reusable packed output against the baseline. Merge only faces with compatible pass/material and matching attributes; preserve halo semantics, winding, deterministic output, and future seams for UV/light constraints. Use per-worker scratch/output ownership and a bounded result queue when integrated. Keep alternatives only if benchmark data justifies them. Do not claim the CPU path is fastest; compare end-to-end mesh+pack cost, including any Kotlin/Java/Rust ABI or data-copy overhead before choosing another language.

**Gate:** focused tests cover visible/hidden faces, six adjacent section boundaries, face winding/indices, material/pass merge boundaries, deterministic output and output capacity/reuse. Run repeatable baseline-vs-optimized benchmarks and report the actual host/CPU/JVM and workload. The first language remains Java/Kotlin on the current JVM unless a native Rust batch path demonstrates a repeatable, end-to-end performance win large enough to offset build/distribution and ownership costs on Windows and Linux. The `game` module remains independent of Vulkan.

### Phase 3 — Vulkan frame and upload path

Create explicit device/queue ownership, swapchain lifecycle and resize handling, command buffers, synchronization, image views, render passes/dynamic rendering choice, pipeline ownership, staging/device-local upload, and a nonblank deterministic triangle/mesh frame. Handle partial initialization and shutdown. Keep blocking setup/reload outside the render loop.  
**Gate:** Vulkan validation reports no errors in a smoke run; resize/recreate and shutdown tests or repeatable manual checks; target machine/GPU/driver details recorded. A development GPU outside the documented support ceiling is not target qualification.

### Phase 4 — World submission and assets

Connect section mesh output to reusable device-local buffers, visibility/culling and batching. Add texture/material and color-space handling using vanilla assets loaded from the legal runtime installation path. Implement opaque and cutout passes first; add transparency sorting/blending as its own tested stage. Avoid per-frame allocation and record upload and GPU timing.  
**Gate:** deterministic scene captures, no Vulkan validation errors, strict PNG check against same-state vanilla OpenGL for each supported scene, and repeatable performance measurements. Any mismatch is investigated, not masked.

### Phase 5 — Entity rendering and complete scene matrix

Add a renderer-neutral entity pose/geometry/material input contract and a batched draw path, then add held items, animation interpolation, particles/weather and GUI/UI composition at their established boundaries. Coordinate resource reload and capture timing.  
**Gate:** strict same-frame captures cover all applicable scene groups below; tests and profiling cover the chosen performance goals. A world-only result is not an entity-renderer completion.

## Comprehensive scene/capture matrix

For each applicable configuration, store a manifest alongside captures. Record game/client build and mappings, installed mods, asset/resource-pack identity, world/scene seed or fixture, player/camera pose and FOV, tick and interpolation values, entity poses, window and viewport size, GUI scale, lighting/time/weather, graphics settings, MSAA/resolve, shader options, color-space/sRGB, alpha convention, image origin, GPU, driver, OS and capture method. Keep scene state deterministic and disable or seed animation/randomness where possible. Normalize top-left origin and resolve multisampling during capture—not inside the comparator.

| Group | Representative cases |
|---|---|
| Calibration | Flat known colors, textured quads, overlapping opaque surfaces |
| Terrain | Vanilla textured flat/varied terrain, close and distant geometry, chunk/section boundaries |
| Depth and clipping | Occlusion, intersecting geometry, silhouettes, near-plane and viewport edges |
| Cutout | Leaves, fences/plants or other alpha-tested vanilla textures |
| Transparency-heavy | Glass, water, multiple overlapping translucent layers, intersecting transparent/opaque surfaces |
| Lighting | Day/night, directional/shaded faces, local light sources and transitions |
| Entities | Static and animated poses, player view, multiple entities and held items |
| Effects | Particles, clouds, weather and overlapping effects where deterministic capture is possible |
| GUI/HUD | Crosshair, text, inventory/menu, supported GUI scales and overlays |
| Lifecycle/settings | Resize/aspect changes, resource reload, render-setting variants, screenshot/readback repeatability |

Run the exact comparator per pair. Keep difference masks and counts. No tolerance, registration, exclusion masks, or post-hoc alignment is allowed. If exact parity is not a valid acceptance condition for a deliberately different rendering choice, document that specific choice and use a separately approved metric without silently changing this strict check.

## Current-round stop line

The current implementation adds general runtime/CPU preparation primitives and a separate developer HUD preview. It does **not** claim Minecraft simulation or game integration, vanilla asset binding, live captures, entity model/animation integration, target hardware qualification, visual parity, or Sodium-equivalent performance. Preserve this stop line; code that compiles is not a presentation or compatibility result.

## Current repository evidence and references

- `legacy/README.md`: current module layout, Vulkan probe/buffer status, screen-space PNG contract, and milestone statements.
- `legacy/AGENTS.md`: performance, Vulkan feature, capture strictness, ownership, originality, asset/license and verification policies.
- `legacy/modules/client`: fixed 1.8.9/protocol-47 descriptor plus a dedicated 20 Hz fixed-rate snapshot tick-loop primitive; it does not implement Minecraft world/player simulation.
- `legacy/modules/renderer`: CPU world section and entity batch compilers, plus bounded async world/entity CPU preparation service; GPU upload, cache integration, pass sorting and frame submission remain open.
- `legacy/apps/hud-preview`: developer-only Compose Desktop window that previews the actual Material 3 Expressive HUD/layout and can save the desktop window as PNG. It is not the Vulkan Compose host or a pixel-parity capture route.

These are current repository observations, not proof that future gates have passed. Update this document with dated milestone results, exact test commands/output summaries, machine details, known gaps and available token telemetry as work progresses.

## Progress log

### 2026-10-11 — Async runtime groundwork and HUD preview

- Added `GameLoop<S>` in `modules/client`: a dedicated 20 Hz tick thread publishes immutable-by-contract snapshots through an `AtomicReference`, skips runaway catch-up after a bounded backlog, supports cooperative stop/await, and exposes handler failure. It is scheduling infrastructure only; Minecraft gameplay state remains unimplemented.
- Added `WorldSectionMeshingService` with fixed-size CPU workers, a bounded queue, explicit failed futures on saturation, and async paths for both world sections and entity inputs. The actual scene compilation stays CPU-only; callers still own caches, GPU resource creation/upload and Vulkan submission on the renderer thread.
- Added `apps/hud-preview`, a developer-only Compose Desktop host that loads the default or a supplied JSON layout and displays `AlkalineHud` with sample values. The window contains only the preview; F2 opens a save dialog and captures the desktop window to PNG on a background thread. This is not an IDE `@Preview`, Vulkan rendering, or parity capture.
- Verified with `./gradlew :modules:client:test :modules:renderer:test`: **BUILD SUCCESSFUL**. The client tests cover published ticks, stop/restart, null state and handler failure; renderer tests cover two-worker execution, queue rejection, entity batch scheduling and compiler failure.
- Verified preview-related follow-up with `./gradlew :apps:hud-preview:test :apps:hud-preview:compileKotlin :modules:client:test :modules:renderer:test` and `./gradlew check`: **BUILD SUCCESSFUL**. `ScreenshotTest.writesBufferedPreviewCaptureAsPng()` passed. The final preview application was launched after the verified changes; this session's Computer Use bridge was unavailable, so no claim of pixel-level visual review or interactive F2 confirmation.
- Remaining renderer gates are unchanged: no game adapter/gameplay state, no world depth pipeline, no texture-backed GPU scene cache/draw integration, no live capture/parity, and no target-hardware qualification. No performance win is asserted from adding parallel infrastructure alone.

### 2026-10-11 — Verified CPU meshing and Vulkan foundation

- Model-token report subtotal remains **2,578,357 subagent tokens**; this does not include main-agent usage, which is unavailable. The Vulkan repair task later reported another **6,019,547 subagent tokens**, bringing reported subagent use to at least **8,597,904**. The project should use shorter, bounded tasks and avoid broad discovery runs. This unusually high token cost is recorded as a process defect, not as a normal estimate.
- Added an isolated JMH baseline-vs-greedy mesher harness for deterministic 16³ empty, solid, plane, sparse, mixed-material and translucent fixtures. Longer run: `:modules:renderer:jmh -PjmhArgs='alkaline.renderer.world.SectionMesherBenchmark -wi 3 -i 5 -w 500ms -r 700ms -f 2 -p size=16 -p shape=EMPTY,SOLID,PLANE,SPARSE,MIXED,TRANSLUCENT'`; OpenJDK 25.0.4.1, Intel Core i5-10400F, one benchmark thread, two forks, ten measurement samples per method/shape. Throughput in ops/s (baseline → greedy): empty **562,675 → 30,113**; solid **9,468 → 17,106**; plane **36,374 → 22,129**; sparse **21,481 → 8,993**; mixed **1,618 → 1,154**; translucent **1,522 → 11,170**. Output for solid fell from **1,536 quads / 159,744 bytes** to **6 / 528**; translucent fell from **24,576 / 2,555,904 bytes** to **96 / 8,448**; mixed fell from **14,212 / 1,478,048 bytes** to **10,356 / 911,328**. The empty, plane, sparse and mixed cases are slower under greedy; the current implementation is **not** a universal performance win. Keep algorithm selection open; consider a baseline/hybrid path rather than using greedy everywhere. These are throughput measurements on one development CPU, not target-GPU or end-to-end mesh+upload performance claims. **Do not ship unconditional greedy routing as the performance-first choice** until a hybrid or workload-aware policy is benchmarked.
- Extracted `VulkanUploader` (staging/one-time transfer), `VulkanGraphicsPipeline` (shader/layout/format-specific pipeline), `VulkanSwapchain` (swapchain images/views/present semaphores/format/extent), and `VulkanDeviceContext` (instance/surface/device/queues/command pool/frame sync). `VulkanRenderer` coordinates frame sequencing and resource registries. The split keeps resource/lifetime roles explicit instead of concentrating all native setup in one class.
- The diagnostics smoke presents an indexed object-space mesh with device-local vertex/index buffers for three frames and performs a second translated/scaled draw through the same transform path. Verified on Linux/X11, SDL 3.4.14, NVIDIA GTX 1660 SUPER / driver 615.78.08: output included `Presented 3 indexed-mesh Vulkan frames.` and `BUILD SUCCESSFUL`. This GPU is outside the documented support ceiling. No validation-layer run, resize exercise, capture, or target hardware qualification was performed.
- The screen-space-check owner added the scene/capture manifest and broad scene matrix under `legacy/apps/screen-space-check/README.md`; all 12 checker tests passed. There are still no live Minecraft captures, vanilla asset loading, game adapter, world/entity data integration, texture binding, or model/animation renderer.
- The exact-RGBA PNG comparator remains unchanged. Synthetic checker fixtures are not visual evidence. Sodium-family benchmarking remains optional until a reproducible 1.8.9 test route is documented.
- Remaining correctness gates include Vulkan validation layers, swapchain resize/recreation exercise, present semaphore lifetime validation, shutdown/partial-init testing, entity input/pose integration, vanilla assets, deterministic same-frame OpenGL captures, target-class hardware, and a GPU-side/world-rendering performance profile. No Sodium-level performance claim is established.

## Status update 2026-10-16

- The frame path now has a deferred-pass encoder (`VulkanFrame`), a main-pass depth attachment, offscreen color
  targets, per-frame streamed data read through buffer device addresses, and spec-built pipelines. Smoke runs
  and the title screen report no Vulkan validation errors on the development host.
- **Entity renderer:** cuboid models are baked once. Per entity, the CPU resolves the part hierarchy into
  streamed 3x4 matrices, and entities sharing model and texture are one instanced draw. It uses vanilla box UVs,
  ModelBiped animation and two-light shading. It is exercised by the title-screen player preview, not by world
  entities; translucent entities, lightmaps and held items are open.
- **Particles:** a structure-of-arrays 20 Hz simulation with vanilla EntityFX motion and data-defined particle
  types, rendered as instanced billboards from streamed records. It is exercised by the title screen; world
  lighting and collision hooks exist only as interfaces.
- **Not done:** world sections on the GPU, same-frame OpenGL captures, GPU timestamps, and target-hardware
  qualification. See decision 0002 for measured frame pacing and render-thread allocation on the development
  host.

## Status update 2026-10-16 (world renderer, second pass)

- **Review of the CPU path.** Fixed: textured world meshes stretched one atlas tile across greedy-merged faces
  (merging is now disabled for that atlas path); faces between *different* translucent materials were dropped
  (now only identical materials hide a shared face, matching vanilla's same-block rule); `WorldMeshCache`
  compared every section's full voxel array on each lookup (identity check first). Still true of that path:
  `visibleMeshes` compiles on the caller's thread, so it must not be used from the render loop. The new path
  below supersedes it for rendering.
- **New GPU world path** (`renderer.world.ChunkMesher`, `renderer.vulkan.world`):
  - Greedy merging in every section, not only uniform ones: texture coordinates come from block positions and a
    mipmapped texture array with repeat addressing, so merged faces tile. Merging requires equal layer, tint,
    pass and uniform corner occlusion, so it never changes the image.
  - Per-vertex ambient occlusion from the one-block halo, with the quad diagonal chosen per occlusion pattern.
  - 8-byte vertices (section-local position, face, occlusion, layer, RGB565 tint); one shared static index
    buffer; quads grouped into 18 (pass, face) ranges.
  - Geometry lives in 64 MiB device-local pages with first-fit, coalescing frees deferred past in-flight frames.
    Uploads are copied from the frame's stream region inside the frame, with no blocking staging submit.
  - Per frame: frustum culling, then only the face directions that can face the camera (adjacent directions
    merged into one draw); opaque and cutout near-to-far, translucent far-to-near; camera-relative origins.
  - Sections are generated and meshed on worker threads nearest-first (`SectionStreamer`).
- **Measured** on the development host (GTX 1660 SUPER, outside the support ceiling; Linux/X11; 1280x760;
  Overworld background, render distance 16, uncapped present): 5,166 sections loaded, 288 visible, 836 draws,
  45k quads, 7.6 MiB geometry; frame mean 0.38 ms, p95 1.38 ms; render-thread allocation about 1.3 KB/frame
  (the same as the other backgrounds); meshing including terrain generation about 370 us per section on
  workers. A Vulkan validation run reported no errors. This is not target-hardware or parity evidence.
- **Not done:** block models other than full cubes, game light levels, biome colors, animated textures,
  per-quad translucent sorting inside a section, GPU timestamps, and connection to a game world.

## Status update 2026-10-16 (world renderer, third pass)

- **Plant models.** `BlockPalette.Builder.cross` declares vanilla's `block/cross` model: two diagonal planes,
  each emitted as two opposite-winding quads so both sides survive back-face culling, alpha-tested, unshaded and
  without occlusion, never hiding neighbors. Diagonal quads use face codes 6 and 7 (texture u follows x or z)
  and a seventh per-pass range, `ChunkMesh.ANY_DIRECTION`, that is drawn from every camera side; mesh ranges
  are now 21. Tall grass (grass-tinted), dandelions and poppies are in `VanillaBlocks` and on the Overworld
  background's grassland.
- **Animated textures.** Block textures whose strip has a `.mcmeta` animation section are split into frames
  stored in spare texture-array layers; `WorldRenderer.animate(frame, tick)` follows the metadata's frame order
  and per-frame ticks and, only when the frame changes, copies that frame's art (all mip levels) over the layer
  meshes sample (`VulkanFrame.copyTextureLayer`). Meshes and the vertex format are unchanged. Water animates
  today; strips without metadata stay still, as in vanilla.
- A validation-layer run of the Overworld background reported no errors.
- **Still not done:** other non-cube models (slabs, stairs, fences, liquids' sloped surfaces), game light
  levels, biome colors, interpolated animations, translucent sorting inside a section, GPU timestamps, and
  connection to a game world.

## Status update 2026-10-17 (lighting, culling, translucency, models, pacing)

See [decision 0005](decisions/0005-world-lighting-culling-models-and-input.md) for details and measurements.

- **Frame pacing:** precise fence waits before input polling replaced a 1 ms sleep; Overworld r16 p95/p99 went
  from 1.41/1.50 ms to 0.51/0.69 ms with the old world path, and stays at 0.67–0.73/0.78–0.83 ms with this
  round's heavier world. GPU timestamps and CPU/GPU/wait percentiles are in `--stats`.
- **World:** 16-byte vertices; vanilla smooth lighting and lightmap; flood-fill occlusion graph with a
  breadth-first visibility walk on a flat ring grid; exact Manhattan-order translucency sorting with re-sorts
  only on cell changes and static order for one-direction sections; indirect multi-draw per pass with a
  direct-draw fallback; animation through a per-frame layer table. Validation layers report no errors.
- **Models:** `ModelRenderer` draws posed cuboids and rigid items; gap-free item extrusion; enchantment glint;
  vanilla first-person placement (`diagnostics --held-item`).
- **Still not done:** non-cube block models and liquids, biome colors, game-world connection, target-hardware
  qualification and same-frame OpenGL captures.

## Status update 2026-10-17 (block models, multiplayer, sky and weather)

See [decision 0006](decisions/0006-block-models-multiplayer-sky-and-input-fix.md) for details and measurements.

- **Blocks:** vanilla blockstates and models compiled from the jar (FaceBakery, weighted variants, neighbor
  states, per-block side culling), vanilla flat and smooth lighting for models, liquids per BlockFluidRenderer,
  static order for one-axis translucent sections, `diagnostics --block-gallery`.
- **Glint:** blended in gamma space through UNORM alias views, as vanilla blends it.
- **Sky:** vanilla sky and fog colors, sunrise fan, stars, sun, moon phases, radial and underwater/lava fog,
  rain and snow from server weather.
- **World draw batches** grow instead of dropping translucent sections (water vanished at r32 at some angles).
- **Multiplayer (partial):** protocol 47 login, chunk streaming, vanilla movement; no entities yet.
- **Still not done:** biome colors, clouds, entities in the world, target-hardware qualification and same-frame
  OpenGL captures.
