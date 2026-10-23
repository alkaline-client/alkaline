# Screen-space consistency check

This app compares two **separate PNG captures of the same deterministic Minecraft 1.8.9 frame**. It is an offline checker, not a capture producer. The OpenGL vanilla capture is the reference and the Alkaline Vulkan capture is the candidate.

## Exact comparison contract

The checker validates PNG chunk ordering and checksums, critical and supported standard ancillary chunks, complete zlib streams, and complete scanline data before pixel decoding. It disables ImageIO metadata decoding, ignores non-color pixel metadata, and keeps color/profile chunks for format validity without applying profiles. Inputs are limited to 128 MiB, 16,384 pixels per side, 16,777,216 pixels total, and a 256 MiB estimated peak working-memory budget; the estimate is deliberately conservative and is not a JVM heap guarantee. Indexed-color transparency is retained and compared as decoded alpha.

Keep the checker behavior exact: compare each decoded 8-bit RGBA pixel at the same `(x, y)` coordinate, with zero tolerance. A different RGB value or alpha value is a mismatch. Do not resize, align, flip, apply a tolerance, or mask a region. Inputs must have matching dimensions and the same top-left image origin; normalize OpenGL's bottom-up readback while capturing. Use the same color encoding and alpha convention for both PNGs, resolve multisampling before export, and keep animated or otherwise nondeterministic state fixed. The checker emits an opaque mask: `#404040` for equal pixels and `#FF20C0` for different pixels, including alpha differences.

Run the focused tests from `legacy/`:

```bash
./gradlew :apps:screen-space-check:test
```

Compare a capture pair with a new result path in an existing directory:

```bash
./gradlew :apps:screen-space-check:run --args="--opengl /path/vanilla-opengl.png --vulkan /path/alkaline-vulkan.png --output /path/result-mask.png"
```

Exit status `0` means exact equality, `1` means one or more different pixels (the mask is still written), and `2` means invalid input, arguments, or output. The command and PNG constraints are also documented in [`../../README.md`](../../README.md).

## Local synthetic consistency check

No authorized real OpenGL/Vulkan Minecraft capture pair is checked in, and this app does not produce captures. To exercise the installed checker without implying game-rendering parity, generate deterministic synthetic RGBA PNG inputs and run both an identical-pixel pair and a deliberately different pair:

```bash
# Run from legacy/
./gradlew :apps:screen-space-check:installDist
python3 apps/screen-space-check/verification/check_synthetic.py
```

The Python runner uses only the standard library. It writes synthetic inputs and result masks under a fresh `apps/screen-space-check/build/verification/synthetic-*/` directory, invokes the installed executable, checks the identical pair exits `0`, checks the changed pair exits `1`, then decodes and verifies every output-mask pixel exactly (including RGB values under zero alpha). It expects 802 differing pixels in a 320 x 180 synthetic pair. PNG compressed bytes are not a comparison target; pixel values and mask colors are. Use the directory printed by the runner to locate the evidence. Build outputs are ignored and must not be presented as real Minecraft captures.

This workflow verifies the checker contract only. It does not validate either renderer or establish visual parity. Real capture qualification still requires separate, authorized OpenGL and Vulkan PNG captures of the same deterministic Minecraft 1.8.9 frame and the documented capture manifest.

## Deterministic capture manifest

A future capture producer or test harness should record a manifest beside each **pair** of captures. This is a recommended evidence contract, not an existing producer or implemented manifest format. Keep the manifest machine-readable (for example, JSON) and identify both files independently. At minimum record:

| Field group | Fields to record |
| --- | --- |
| Identity | `schemaVersion`, unique `captureId`, `sceneId`, short `scenario`, capture `source` (`vanilla-opengl` or `alkaline-vulkan`) |
| Build and environment | `gameVersion` (must be `1.8.9`), client/renderer build or revision, OS, GPU, driver, display mode, framebuffer width and height |
| World and view | world/save or reproducible fixture identifier, dimension, seed when applicable, player/camera position and rotation, FOV, tick number, partial-tick/interpolation value, and any state required to restore the scene |
| Scene state | relevant block/entity/item identities and transforms, lighting and weather state, particle state or deterministic seed, and whether HUD/effects are enabled |
| Settings | all image-affecting game, renderer, and HUD settings, including GUI scale; record explicit values rather than relying on defaults |
| Capture contract | capture method/version, capture stage, sample phase, MSAA/resolve state, color encoding, alpha convention, image origin, and any readback normalization applied |
| Artifacts | relative PNG path, SHA-256, decoded dimensions, bit depth, and capture source for each image; record the checker version/command and result-mask path/hash after comparison |

Use the same scene and image-affecting settings for both sources. A manifest makes a run auditable; it does not make a nondeterministic scene deterministic by itself. Freeze or explicitly reproduce time, interpolation, entity animation, particles, weather, and other changing state before accepting a pair. Keep captures, manifests, and result masks as separate evidence artifacts; never overwrite prior results.

## Practical scene matrix

Build a deterministic, repeatable pair for every row and record the corresponding settings and state in its manifest. Each row is a coverage target, not a claim that the scene or capture route currently exists.

| ID | Coverage | Practical case and controls |
| --- | --- | --- |
| `opaque-depth` | Opaque surfaces and depth | A fixed view with near/far opaque geometry, overlap, and clear depth ordering. Hold camera and tick fixed. |
| `clipping` | Clipping and viewport edges | Geometry crossing the view edges and near/far clipping boundaries; include partially visible objects and repeat at a second aspect ratio. |
| `vanilla-textures` | Vanilla textures | Representative vanilla terrain and item textures, atlas/UV edges, and texture animation only when its phase can be fixed. Use the same 1.8.9 assets on both sides. |
| `cutout` | Cutout/alpha-tested surfaces | Leaves, plants, and other cutout surfaces against both bright and dark backgrounds; keep texture and alpha-test settings fixed. |
| `transparency-heavy` | Transparency-heavy composition | Overlapping translucent surfaces and effects (for example, water/glass and layered translucent geometry); fix view order, camera, and all relevant settings. |
| `lighting` | Lighting | Fixed geometry under representative ambient/block-light conditions and a repeatable time-of-day; record light levels and lighting-related settings. |
| `entities` | Entities | Stationary and animated entity examples with recorded type, pose, equipment, and animation/tick state. Include overlap with world geometry. |
| `held-items` | First-person held items | Empty hand and representative held-item classes, with recorded item, hand, use/swing state, and partial tick. Capture UI state consistently. |
| `particles-weather` | Particles and weather | A repeatable particle effect and weather cases (clear/rain/snow where applicable); fix random seed, spawn state, time, and particle settings, or exclude uncontrolled motion. |
| `hud-scales` | HUD scales | HUD visible at every supported GUI-scale mode, including auto/default if present; cover more than one framebuffer resolution and record exact scale and HUD state. |
| `resize` | Resize | Capture before and after a window/framebuffer resize, including a changed aspect ratio, then resize back and repeat the original capture to check reproducibility. Record framebuffer dimensions, not only window dimensions. |
| `reload` | Reload | Repeat the same baseline before and after a world/resource reload and renderer restart when available. Record the reload action and restored scene state. |
| `settings` | Settings | Change one image-affecting setting at a time, capture its enabled/disabled or low/high states, then restore the baseline and verify it repeats. Record every value in the manifest. |

A completed matrix should identify uncovered rows as blocked or unavailable rather than treating them as passes. For each runnable row, preserve both original PNGs and the result mask, including failed comparisons, with the manifest and checker result.

## Evidence levels and status

Keep these evidence types distinct:

1. **Checker tests** use generated/synthetic PNG fixtures to verify comparison behavior (including exact RGBA, alpha/hidden-RGB differences, masks, and invalid inputs). A passing test proves only the checker behavior exercised by that test; it is not a game-rendering result.
2. **Vanilla capture comparisons** require a reproducible, real Minecraft 1.8.9 OpenGL reference PNG and a matching Alkaline Vulkan PNG, plus the manifest and checker output for the same scene. Only such a paired run is evidence about renderer consistency.

No automatic game capture hooks or actual vanilla game rendering are implemented here. Do not describe checker fixtures or generated test masks as live game captures. Sodium may be considered only as an optional route after a documented, reproducible way to produce comparable Minecraft 1.8.9 PNG captures exists; it is not an oracle or a substitute for the vanilla OpenGL reference.
