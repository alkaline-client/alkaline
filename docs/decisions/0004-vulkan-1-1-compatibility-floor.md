# Architecture decision 0004: Vulkan 1.1 hardware floor through a future compatibility mode

Status: accepted as policy on 2026-10-17. **Not implemented.** The client still requires Vulkan 1.3.

## Decision

1. **Primary target: Vulkan 1.3/1.4.** The renderer is designed, optimized and validated for Vulkan 1.3 and
   newer. New features keep targeting this baseline: dynamic rendering, buffer device addresses, demote to
   helper invocation, and the other 1.2/1.3 core features the renderer already enables.
2. **Hardware floor: Vulkan 1.1.** The lowest supported hardware is any GPU and driver that implement Vulkan
   1.1. There is no specific hardware model floor. This replaces the earlier rule that excluded devices limited
   to Vulkan 1.0–1.2.
3. **Vulkan 1.1 support is an optional "compatibility mode" only.** It is a separate, reduced path, not the
   primary renderer, and it must not slow down or constrain the 1.3 path. Devices below 1.1 stay excluded.
4. **The GPU ceiling is unchanged.** "NVIDIA GTX 10 Series and older, inclusive" remains the upper bound of
   the supported range; the new rule only moves the lower bound.

## Consequences for current work

- Nothing changes in code yet. `VulkanApiSupport` and `VulkanDeviceContext` still reject loaders and devices
  below 1.3, and no 1.1 code path exists. Do not add one until compatibility mode is scheduled.
- When a renderer feature needs something beyond Vulkan 1.1 (dynamic rendering, buffer device addresses,
  timeline semaphores, synchronization2, draw-indirect count, descriptor indexing), keep the dependency explicit
  and local, so a later compatibility mode can substitute a render pass, a descriptor-bound buffer, or a
  direct-draw loop without redesigning the feature.
- Compatibility mode will need its own qualification matrix (GPU, driver, OS) and its own frame-time results;
  results from the 1.3 path do not carry over.
