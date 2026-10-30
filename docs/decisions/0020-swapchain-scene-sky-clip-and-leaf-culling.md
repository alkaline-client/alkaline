# Architecture decision 0020: The scene in the swapchain image, a clipped sky pass, and leaf culling

Status: accepted. Implemented 2026-10-30.

Measured on a GTX 1660 SUPER (NVIDIA 615.78.08, Linux 7.3, Wayland, mailbox, V-Sync off, Java 25) at 1920x1080,
render distance 12, on the hilltop scene of decision 0017 (seed 1, (96, 75, -96), noon, fancy clouds above the
eye). Each run measures about 23 s after a 22 s warm-up. "Turning" runs add `-Dalkaline.stats.turnDegreesPerSecond=90`
(new, stats only), which turns the player steadily: a still camera lets the visibility walk be reused and flatters
the CPU, while mouse look changes the frustum every frame.

## The scene drawn straight into the swapchain image

The game view drew the world into the compositor's scene image, and the main pass copied it to the swapchain image
(the backdrop, 0.055 ms). Only the frosted-glass blur needs the scene as an image, and the blur refreshes at the HUD
rate, about one frame in thirty at these frame rates.

- `VulkanFrame.beginSwapchainScene` opens a pass on the swapchain image itself, with the shared depth attachment.
  Pipelines built for the scene format draw in it. `beginMainPass` then keeps its color and clears only depth.
- `MenuBackground.rendersToSwapchain` / `renderToSwapchain`: the compositor draws such a background there on every
  frame that does not refresh the blur. Blur frames keep the scene image and the copy.
- The swapchain now prefers `R8G8B8A8_SRGB`, the scene format, when the surface offers it; otherwise
  `B8G8R8A8_SRGB` and the copy, as before (`VulkanRenderer.swapchainScene`).
- `-Dalkaline.scene.copy=true` keeps the copy on every frame, for comparisons.

| | fps | GPU frame ms | backdrop ms |
|---|---|---|---|
| still, before | 2619 | 0.314 | 0.055 |
| still, after | 2940 | 0.265 | 0.008 |
| turning, before | 1975 | 0.415 | 0.055 |
| turning, after | 2153 | 0.376 | 0.010 |

Pixels, one saved world copied into each run: the two paths match exactly except blended water, where 86% of the
differing pixels are one level apart and none more than six (dark water). Two runs of one path differ only in the
drifting clouds. Both images are RGBA8 sRGB; sRGB blending precision is up to the implementation, and the driver most
likely blends into swapchain images slightly differently.

## The lower sky plane clipped to below the horizon

Switching sky parts off one at a time (sky drawn first, 0.073 ms): the lower plane cost 0.030 ms, the upper plane
and the clear 0.042 ms, the sun, the sunrise, the stars and the gamma-space switch about 0.002 ms. The lower plane was
a full-screen triangle whose fragments mostly discard.

From above sea level that plane can only cover directions below the horizon, and the horizon is a straight line on
the screen (the view direction's y is linear in screen coordinates). `sky_planes_common.glsl` clips the screen to that
half-plane in the vertex shader (Sutherland-Hodgman against one line, sent as a fan of three triangles) with a small
margin, so the fragment shader still makes every decision. The sky fell from 0.073 to 0.065 ms looking 8 degrees
down; looking up it saves almost all of the lower pass.

## Leaf culling (Video > Leaf culling)

Vanilla's fancy leaves draw every leaf face no opaque cube covers, so a canopy is full of faces between leaves that
show only through the cutout holes in front of them. The setting leaves some out (`LeafCulling`, in the mesher):

- **Off** (default): vanilla.
- **Deep:** a face toward a leaf is dropped when the block beyond that leaf is not air either. It shows only through
  the holes of two leaf layers. The outer two layers of every canopy keep their faces.
- **All:** every face toward a leaf is dropped. Canopies become shells whose holes show what lies behind.

Changing it re-meshes the loaded sections. The behavior was studied in More Culling (GPL-3.0, cloned read-only into
the workspace's `reference/MoreCulling`): its Fast mode is All, and its Depth mode checks along the face normal. No
code was taken; the rule here runs in Alkaline's mesher on its own padded block array.

| | GPU frame ms (still) | terrain ms (still) | GPU frame ms (turning) | terrain ms (turning) | fps (turning) |
|---|---|---|---|---|---|
| Off | 0.256 | 0.089 | 0.360 | 0.174 | 2160 |
| Deep | 0.248 | 0.083 | 0.322 | 0.148 | 2370 |
| All | 0.241 | 0.077 | 0.293 | 0.125 | 2574 |

The same view in the three modes: Deep differs from Off on 15k pixels by more than 16 levels, inside canopies;
All on 36k, where the holes now show the sky and the ground. `ChunkMesherTest.leafCullingDropsFacesBetweenLeaves`
pins the rule.

## Tried and dropped

- **Always drawing the sky after the terrain.** With clouds above the eye, vanilla's clouds write depth before the
  terrain, and terrain behind a cloud is then never drawn. Drawing the terrain first, the pixels a cloud hides would
  need their depth reset to the far plane, and only those. A depth test cannot write a value other than the one it
  compares, so this needs a stencil buffer; the depth format is D32 without one.
- **Two-phase occlusion culling.** Rays cast from the eye through the saved world showed that 28 to 67% of the
  camera-facing terrain faces in view (45% over eight headings) lie in sections no ray reaches. A first phase drew
  the sections seen recently. Instanced boxes around the other sections' geometry were then depth-tested with early
  fragment tests, and a surviving fragment switched its section's draw records on. 98 of 109 tested sections came
  back hidden, but the terrain took 0.128 ms instead of 0.092: the pass break and barriers between the phases cost
  more than the hidden geometry. Removed.
- **The vertex shader's sRGB decode.** Turning it off left the still-camera terrain at 0.093 ms both ways (two runs
  each), so the terrain is not vertex-ALU bound.
- **Six swapchain images** instead of four: no change (2994 against 2998 fps). The frame is 0.33 ms with 0.26 ms of
  GPU work. The rest is the slot wait and present, outside the command buffer.

## Not done

- The block-crack overlay meshes its block with every face, so with Deep leaves a crack can show on an inner face
  the terrain no longer draws (seen only through the holes).
- The menu's Overworld background always draws through its own target and copy.
- GTX 10 series and older were not measured.
