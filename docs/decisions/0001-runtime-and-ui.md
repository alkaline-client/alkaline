# Architecture decision 0001: JVM, direct Vulkan, SDL3, and Compose

Status: accepted for the foundation. Investigated 2026-10-10 America/New_York.

## Decision

Use Java 25 for engine-facing code, LWJGL 3.4.3 for Vulkan and SDL3 bindings, and Kotlin with Compose Multiplatform for the in-game GUI/HUD in the Vulkan renderer module. SDL3 owns the game window, events, and input; Vulkan owns GPU resources and rendering. Do not use SDL_GPU as the renderer. Keep UI structure/data separate from the renderer implementation, with layouts loaded from data so mods can customize supported components without rewriting Compose code. **The `legacy/` repository implements 1.8.9 only; no multiversion abstractions belong here.**

This is an independently designed Alkaline architecture. Other clients are evidence of constraints, not sources of code or structural templates. The requested LWJGL source clone is a pristine reference checkout; Alkaline consumes published artifacts rather than building an upstream fork.

## Why this stack

| Consideration | JVM + LWJGL 3 | Rust + Vulkan/SDL3 |
| --- | --- | --- |
| Initial Minecraft 1.8.9 target | Existing game behavior is Java; adaptation can remain in the same language/runtime | Either retain a JVM and own a native boundary, or reimplement substantial client behavior |
| Vulkan/SDL3 control | Direct native API access with explicit memory/resource ownership | Also direct control through bindings; ownership tooling is stronger |
| Performance | Requires deliberate allocation, batching, data layout, and GC measurement | Predictable memory management can help, but does not alone reduce GPU work or frame time |
| Material 3 Expressive | Official Compose design-system implementation, with a pinned prerelease for public Expressive APIs | No verified turnkey official Material 3 Expressive implementation for the investigated Rust UI toolkits |
| Maintenance | One primary JVM toolchain and fewer game/native boundary contracts | Additional build/distribution and interoperation work if Java remains |

The choice is about integration cost and direct API access, not a claim that Java is universally faster than Rust. No cross-language benchmark has been performed. Add Rust only when a measured, substantial, batch-oriented workload earns its boundary cost; do not create a native bridge preemptively.

## The Minecraft migration is real work

Minecraft 1.8.9 assumes LWJGL 2, OpenGL, legacy window/input APIs, and its historical Java environment. Replacing a dependency does not convert that renderer to Vulkan. The initial client module contains only fixed 1.8.9 identity metadata. It does not load Minecraft or provide Forge/mod compatibility. This repository has no version-adapter layer and does not accommodate other Minecraft versions.

Future work must establish a reproducible, legally distributable game acquisition and mapping process, validate execution on Java 25, adapt startup/window/input ownership, and replace rendering at explicit boundaries. Fixed-function state, tessellation, textures, font rendering, chunk uploads, resource reloads, and screenshots all require deliberate handling. Old transformers/mods that assume Java 8, LWJGL 2, or OpenGL are not promised compatibility. Do not ship Mojang binaries, assets, or credentials in this repository.

## Vulkan baseline and the unusual GPU ceiling

The user specifies **GTX 10 Series and older, inclusive**. This is a support ceiling, not a minimum. Following the requested scope change, eligible GPU/driver combinations must also implement **Vulkan 1.3 or newer**. Devices limited to Vulkan 1.0–1.2 are excluded. Qualification needs a concrete GPU/driver/OS matrix; NVIDIA, AMD, and Intel chronology is not an interchangeable capability test.

The prior Vulkan 1.0 foundation baseline is superseded by **Vulkan 1.3 minimum**. Reject loaders below 1.3, request 1.3 at instance creation, and reject physical devices below 1.3 independently of surface capabilities. Continue checking graphics/present queues, swapchain extension, formats, and presentation modes. This scope change does not implement a logical device or frame renderer. Query and explicitly enable features when using them, even if their APIs are core in 1.3. Higher requirements such as mesh shaders or ray tracing remain optional and capability-gated.

Capability suitability and product qualification are separate: the diagnostic may run on newer development GPUs and must never report them as supported target hardware. The available GTX 1660 SUPER is outside the stated ceiling. Windows/Linux are first-class; macOS portability extensions and MoltenVK remain a later decision.

## Kotlin and data-driven in-game UI

Kotlin is available alongside Java on the Java 25 toolchain. The renderer module owns a Compose Material 3 Expressive HUD component and its JSON layout model. Layout data controls component kind, hierarchy, text/value binding, theme color token or explicit hex color, typography, visibility condition, sizing, spacing, anchoring, and action identifiers. Game code supplies display values and handles action identifiers; layout data does not execute code. This makes common layout and visual changes data-only while keeping behavior and new component implementations in reviewed code.

The in-game Compose UI belongs in the renderer module with a data-driven component tree. This does not establish a Compose-to-Vulkan backend or prove that the HUD is already rendered in a running game; mounting it into a Vulkan frame host remains a separate implementation step with latency, synchronization, and maintenance costs to measure.

## Material 3 Expressive boundary

Pin Kotlin 2.4.21, Compose Multiplatform 1.12.1, and `org.jetbrains.compose.material3:material3:1.13.0-alpha02`. Material 3 remains a deliberate prerelease exception because the Expressive API is required. It belongs to `modules:renderer`, where the game HUD is defined; this does not imply that the current Vulkan backend can host Compose.

## Pinned build

Use Gradle 9.7.0 with its distribution checksum and a Java 25 toolchain. Kotlin 2.4.21's documented supported Gradle range ends at 9.7.0. LWJGL 3.4.3 remains pinned by request; other pinned dependencies are at their latest verified versions, except Material 3 which intentionally uses the newest verified alpha release. The OS still supplies the Vulkan loader/driver.

## Evidence

- Minecraft 1.8.9 protocol 47 is corroborated by [MinecraftServer.java in the local reference copy](/home/bhop4real/Projects/Alkaline/reference/repo/KotlinizedMCP-mcp/src/main/java/net/minecraft/server/MinecraftServer.java#L435) and its [client login packet](/home/bhop4real/Projects/Alkaline/reference/repo/KotlinizedMCP-mcp/src/main/java/net/minecraft/client/multiplayer/GuiConnecting.java#L67). The version module uses it only as descriptor metadata; it is not protocol/client compatibility implementation.
- [LWJGL latest stable release 3.4.3](https://github.com/LWJGL/lwjgl3/releases/tag/3.4.3), published 2026-08-23; tag commit `30fac9b95f99cda97312232be25ba55297bf9951`. Includes SDL 3.4.14 and Vulkan 1.4.360 bindings; binding coverage is not the minimum API required by Alkaline.
- [LWJGL SDL Vulkan bindings at the pinned tag](https://github.com/LWJGL/lwjgl3/blob/3.4.3/modules/lwjgl/sdl/src/generated/java/org/lwjgl/sdl/SDLVulkan.java).
- [LWJGL source build documentation](https://github.com/LWJGL/lwjgl3/blob/3.4.3/doc/README.md). Upstream builds with Ant; its root Gradle files do not define Alkaline's build.
- [SDL3 Vulkan surface contract](https://wiki.libsdl.org/SDL3/SDL_Vulkan_CreateSurface).
- [Compose Multiplatform 1.12.1 release](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1).
- [Material 3 Expressive source artifact 1.13.0-alpha02](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/1.13.0-alpha02/material3-1.13.0-alpha02-sources.jar).
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html).
- [Gradle JVM compatibility](https://docs.gradle.org/current/userguide/compatibility.html).

Release/API availability is verified from upstream. On the Linux development machine, the diagnostic created an SDL 3.4.14 Vulkan surface and observed graphics/present queues, swapchain extension, two formats, and three present modes on a GTX 1660 SUPER. This does **not** demonstrate actual swapchain creation, presentation, Minecraft adaptation, or support on an in-scope GPU. Windows and target-era GPUs remain untested. Compose's use in this source tree is verified, but rendering through Vulkan and integration into a game loop remain open.
