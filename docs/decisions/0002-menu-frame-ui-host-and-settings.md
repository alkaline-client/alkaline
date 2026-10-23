# Architecture decision 0002: frame encoder, in-game Compose host, menu scene, and settings

Status: accepted for the title screen. Implemented 2026-10-16.

## Decision summary

1. **Frame encoder.** `VulkanRenderer.acquireFrame()` returns a `VulkanFrame` with no pass open. Callers record
   transfers and offscreen passes, then `beginMainPass()`. The main pass now carries a depth attachment
   (D32_SFLOAT preferred). Pipelines are described by immutable `PipelineSpec`s and are rebuilt by the renderer
   when the swapchain format changes. `beginFrame(r, g, b, a)` remains as acquire + main pass for existing callers.
2. **Per-frame streaming.** A persistently mapped, host-coherent stream buffer has one 8 MiB region per frame in
   flight. Instance data and part matrices are bump-allocated into it. Shaders read it through
   **buffer device addresses**, so per-frame data needs no descriptor sets.
3. **Entities.** Cuboid models are baked once into static vertex buffers carrying a part index. Each submitted
   entity writes one 3x4 matrix per part plus a 16-byte instance record. Entities sharing model and texture
   become one instanced draw.
4. **Particles.** A fixed-capacity structure-of-arrays simulation at 20 Hz with vanilla EntityFX motion.
   Rendering streams 32-byte instance records and expands quads in the vertex shader, with one draw per blend
   family. Particle kinds are `ParticleType` data, not subclasses.
5. **Panorama.** A port of the 1.8.9 title panorama: three passes, with no framebuffer copies.
6. **Compose in-game.** `ComposeLayerHost` runs an offscreen `ImageComposeScene` (Skia CPU raster) on a
   dedicated UI thread. It renders only on invalidation or input, and publishes BGRA frames. The render thread
   uploads the newest frame through a per-frame staging region and never waits for Compose.
7. **Frosted glass on the GPU.** Compose surfaces declare `Modifier.glass(radius)`. The renderer blurs the
   background scene with a 4-level dual-filter (Kawase) chain and draws rounded, antialiased glass under the UI
   layer. Compose cannot do this itself: `Modifier.blur` blurs a composable's own content, and the Vulkan scene
   is not part of Compose's canvas. Compose also reports `Modifier.sceneAnchor(name)` rectangles, which is how
   the 3D player preview follows the layout.
8. **Settings.** A dependency-free `modules/config` provides typed settings, versioned schemas with mandatory
   explicit migrations, lock-free snapshot reads (primitives in a `long[]`), and debounced atomic JSON saves on
   an I/O thread. Unknown keys are preserved, a file from a newer schema is never overwritten, and corrupt files
   are backed up before defaults are restored.

## Device features now required

Both features are core in Vulkan 1.3 and required by its feature set. They are queried during device
selection and enabled explicitly:

- `bufferDeviceAddress` (Vulkan 1.2 features): stream data read through `GL_EXT_buffer_reference`.
- `shaderDemoteToHelperInvocation` (Vulkan 1.3 features): glslc's Vulkan 1.3 target compiles `discard` to
  `OpDemoteToHelperInvocation`. The validation layer reported the missing feature until it was enabled.

## Panorama port notes

These were checked against the 1.8.9 MCP sources (the Kotlinized reference and an MCP-derived copy agree):

- Camera: 120 degree perspective; basis `Rx(180) * Rz(90)`; pitch `sin(t/400)*25+20` and yaw `-t*0.1` degrees,
  with `t` in ticks; faces `panorama_0..5` on a unit cube, nearest-filtered with repeat.
- 64 jittered renders blended with alpha `floor(255/(i+1))/255`. These are evaluated per fragment as 64
  analytic ray-cube intersections in one pass, without vanilla's 8-bit rounding between renders.
- One vanilla "rotate and blur" pass is a **transpose** plus a 3-tap box (out(x,y) = mean in(y-1..y+1, x)). Two
  passes therefore form a 3x3 box. Seven passes reduce to one separable 7x9 kernel. `PanoramaKernelTest` checks
  the closed form against literal repeated passes for 0..11 passes.
- Vanilla's raw cube image is itself transposed, and the default odd pass count makes the result upright. The
  port always outputs the upright image, so non-default pass counts never turn the panorama sideways. This was
  established empirically (zero-pass render) and documented in the shader.
- Gradients (`#80FFFFFF -> #00FFFFFF`, then `#00000000 -> #80000000`) and filtering run on sRGB-encoded values
  like GL, and are linearized only when the target re-encodes.

**Not proven:** pixel parity with an OpenGL capture. The 8-bit intermediate rounding differs by design, and no
same-frame vanilla capture was made.

## Measured on the development host (not target hardware)

GTX 1660 SUPER (outside the support ceiling), Linux, X11 SDL driver, 1280x760, title screen with player preview
and particles, validation layers off, `--stats` with a 2 s warm-up and about 3,400 frames:

| Background | Mean | p50 | p95 | p99 | Max |
| --- | --- | --- | --- | --- | --- |
| Panorama | 2.98 ms (336 fps) | 3.36 ms | 6.57 ms | 6.72 ms | 114 ms (single spike) |
| Blue Planet | 2.98 ms (336 fps) | 3.40 ms | 6.56 ms | 6.72 ms | 114 ms (single spike) |

Both backgrounds show the same bimodal 3.4/6.6 ms intervals. That indicates presentation pacing on this host
rather than GPU cost, but GPU timestamps were not collected, so per-pass GPU time is **unmeasured**. The single
~100 ms spike after warm-up was not investigated.

The render thread allocates about 8-10 KB per frame (ThreadMXBean). A JFR allocation profile attributes most
of it to LWJGL 3.4.3's FFM call path (`MemorySegment.ofAddress` per native call). Smaller sources are LWJGL
struct wrappers and capturing lambdas in pass setup. Driving this toward zero is open work.

## Boundaries

- Compose renders on the CPU. A full-window UI frame costs a 4 MB copy and upload, but only while the UI is
  changing. A Skia Vulkan backend sharing Alkaline's device would remove the copy; it is not attempted here.
- Singleplayer and Multiplayer pages state that the game adapter is not in this build.
- Entity and particle renderers are exercised by the menu preview only. World integration, lightmaps, and
  translucent entity sorting are not implemented.
- The legacy `ClientPageRouter` is no longer used by `apps:game-client`. It remains in `modules:client` with its
  tests.
