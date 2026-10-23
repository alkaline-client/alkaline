# Reference stack and port map

`external/KotlinizedMCP-mcp` is a local, ignored, read-only source reference. Do not make Alkaline changes in that tree or include its bundled assets and native binaries in commits. Port code into the modules below with the narrowest useful dependency boundary.

## Alkaline stacks

| Stack | Alkaline location | Owns |
| --- | --- | --- |
| Client behavior and deterministic game logic | `modules/game` | Rules and data that can be tested without SDL, Vulkan, OpenGL, a Minecraft launcher, or server runtime |
| Client/version identity and page routing | `modules/client` | The Alkaline 1.8.9/protocol-47 descriptor, renderer-neutral page interfaces, and the 1.8.9-style page structure used by the visible shell |
| Window and input platform | `modules/platform` | SDL3 window, event, input, and native lifetime handling |
| Rendering and in-game UI | `modules/renderer` | Vulkan resource/rendering APIs and the data-driven Compose Material 3 Expressive HUD |
| Executables/tools | `apps/*` | Diagnostics, the visible `apps/game-client` shell, and offline utilities; not game/runtime architecture modules |

Keep renderer calls out of game logic. Game logic provides state or frame data; the renderer consumes it. Compose belongs to the in-game renderer stack, not to a separate desktop application. Its HUD data model can configure supported UI components without executing mod-provided code.

## Reference source areas

| Reference area | Classification | Port policy |
| --- | --- | --- |
| `src/main/java/net/minecraft/util` | Shared client-side geometry/input/math candidates | Port isolated algorithms and value types; omit Minecraft entity/client dependencies or replace them at explicit seams |
| `src/main/java/net/minecraft/nbt` | Client-relevant persistence/data format | Keep binary behavior and test exact round-trips; keep auth/session-specific helpers separate |
| `src/main/java/net/minecraft/world/gen` and `world/biome` | Client world data and deterministic generation | Keep algorithms independent of rendering and server lifecycle; validate seeded output against the reference |
| `src/main/java/net/minecraft/client/renderer` | Renderer stack | Treat as behavioral reference only; do not copy OpenGL/LWJGL 2 assumptions into the Vulkan module |
| `src/main/java/net/optifine/render` and `net/optifine/shaders` | Renderer-specific reference | Do not adopt as Alkaline architecture; port only a needed behavior behind the Vulkan renderer's own API |
| `src/main/java/net/minecraft/server` and server-only world lifecycle | Server-side code | Out of scope for this 1.8.9 client port unless a client-facing data contract needs a small shared piece |

## Ported client-oriented foundations

The current `modules/game` ports cover block/vector geometry and collision, NBT data, deterministic terrain-noise generators, camera mouse smoothing, and frame-time sample storage. These are reusable foundations, not a complete Minecraft client. Keep future ports client-oriented and add deterministic tests before connecting them to the running client.
