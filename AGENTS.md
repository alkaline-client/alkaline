# Alkaline development instructions

Alkaline as a whole is intended to be a multiversion Minecraft PvP client. **This `legacy/` repository targets Minecraft 1.8.9 only.** Do not add infrastructure for other game versions here. Superior performance, a coherent design language, and customization define the product.

## Think different — aggressive development is a requirement

**THINK DIFFERENT. Alkaline's development is deliberately aggressive.** Challenge inherited Minecraft assumptions, replace expensive paths, and pursue substantial architectural improvements. Do not preserve a legacy implementation merely because it is familiar. Prove improvements with measurements and working code; aggression is not a reason to hide regressions, guess about APIs, or skip validation.

## Priorities and platform policy

1. **High performance first; compatibility second.** Optimize frame-time consistency, input latency, allocation rate, and memory use, not just average FPS.
2. **Vulkan 1.3/1.4 + SDL3 are the primary target** for the game renderer and platform layer. The hardware floor is **Vulkan 1.1**, reached only through an optional, reduced **compatibility mode** that must never constrain the primary path ([decision 0004](docs/decisions/0004-vulkan-1-1-compatibility-floor.md)). Compatibility mode is **not implemented yet; do not build it until it is scheduled.** Until then the code rejects instance loaders and physical devices below Vulkan 1.3. Devices below 1.1 are always excluded.
3. **The supported GPU range is NVIDIA GTX 10 Series and older, inclusive.** Preserve this literal direction: it is a ceiling, not a GTX 10 Series minimum. Below the ceiling there is no specific hardware model floor: any GPU and driver implementing Vulkan 1.1 is in range (through compatibility mode once it exists; Vulkan 1.3 today). AMD/Intel model equivalences need explicit qualification before being advertised as supported. A newer development GPU does not count as target-hardware validation.
4. **Windows and Linux are first-class targets.** macOS is a possible later target; do not compromise current performance for speculative macOS compatibility.
5. **This repository is 1.8.9-only.** Other Minecraft versions belong elsewhere in Alkaline, not in generic version adapters, selectors, or modules inside `legacy/`. Isolate Minecraft integration from platform, renderer, UI, and configuration for testability, not for hypothetical version support.

## Design rule: simplicity

Alkaline's interface is simple by default. Every element must earn its place; when in doubt, leave it out.

- **Show only what the next decision needs.** The title screen is the Alkaline logo and the primary actions, nothing else. Version strings, legal notices, profiles, and previews live one level down (for example, in Settings).
- **Remove before adding.** A new screen or control replaces or absorbs something where it can. Decorative chrome, redundant labels, and second ways to do the same thing are defects.
- **One primary action per screen**, visually distinct; everything else is secondary or tertiary.
- **Group settings generically** (Video, Interface, Advanced, and later Controls, Audio): menu-only options sit beside the in-game settings they belong with, not in feature-named categories.
- **One type family and one component language:** Google Sans Flex with Material 3 Expressive components, shapes, and motion. Motion explains state changes (loading, reveal, navigation); it does not decorate.
- **Calm by default:** whitespace over borders, the background over panels, few colors. Prefer one frosted surface to several nested ones.

## Engineering rules

- Keep render and input hot paths allocation-conscious. Benchmark before asserting a performance win. Record GPU, driver, OS, resolution, scene, and frame-time percentiles with results.
- Require Vulkan 1.3 as the primary API baseline. Query and explicitly enable any device features the renderer uses; an API version alone is not a guarantee of optional feature support. Gate features beyond the baseline rather than silently raising the minimum. Keep each dependency on post-1.1 functionality explicit and local, so the future compatibility mode can substitute it.
- If an implementation detail, API contract, platform behavior, or other requirement is uncertain after checking project source, verify it against official documentation or a focused web search before proceeding. Prefer official specifications and primary sources; record the source when it affects a design decision.
- Read the real source before you write against an interface. Fuzzy self-dialogue is the signal to stop. Words like “probably” and “actually” about a signature mean that you guess. Open the library, the package, or a call site in the repository. One read prevents many compile-and-fix cycles.
- Validate supplied Vulkan captures against original OpenGL captures of the same deterministic 1.8.9 frame using `apps:screen-space-check`. Every decoded RGBA difference fails the check; do not add tolerances, alignment, or ignored regions that hide pipeline regressions. Keep capture resolution, origin, scene state, color encoding, and alpha conventions identical. The current checker accepts PNG files; automatic game capture is not implemented.
- Keep UI work, disk access, compilation, and blocking operations out of the game render loop.
- Use explicit ownership and lifetimes for native handles and GPU resources. Release them on partial initialization failures as well as normal shutdown.
- Keep persisted settings schema changes explicit. Avoid a global event bus, an all-purpose abstraction layer, and speculative plugin or multiversion APIs.
- Keep dependency versions pinned and use the checked-in build wrapper. Do not silently switch a stable dependency to a snapshot or prerelease.
- **Borrow as little code and structure from other projects as possible.** Design Alkaline's architecture and author its implementation independently. Use dependencies and official API contracts deliberately; use other clients to learn constraints, not as templates to transplant. The requested LWJGL source checkout is a read-only reference, not Alkaline's architecture.
- Treat `external/` and the parent workspace's `reference/` tree as read-only upstream reference material. Do not edit or copy third-party code without checking its license and provenance.
- Do not commit Minecraft binaries, assets, access tokens, user configuration, machine-specific paths, native binaries, or generated build output.
- Verify a change with the narrowest useful build, test, or runtime check. Report what actually ran; compilation alone does not prove graphics presentation, Windows compatibility, or Minecraft compatibility.
- This repository's foundation is not a working Minecraft client until the game adapter and Vulkan renderer are implemented and tested. Keep documentation and UI honest about that boundary.
