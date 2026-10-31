# Architecture decision 0021: The page morph, the world hand-off, notifications, and plugins

Status: accepted. Implemented 2026-10-31.

Measured on a GTX 1660 SUPER (NVIDIA 615.78.08, Linux 7.3, Wayland, mailbox, V-Sync off, Java 25) in a 1280x760
window, with the Fluid background, joining a local Mojang 1.8.9 server (fresh world per run, view distance 8).
Numbers vary between worlds and runs; the tables give typical runs, not averages.

## Title actions grow into their pages

Singleplayer, Multiplayer and Settings open with Material's container transform: the action's button grows into
the page's panel, the panel's content fades in once it lands, and Back shrinks the panel into the button again.
Pages otherwise only cross-fade (the slide is gone: the morph is the navigation motion).

Compose's shared-element transition (`SharedTransitionLayout` + `sharedBounds`) was tried first and dropped. The
interface is rasterized by Skia on the CPU, and that transition runs a lookahead pass over both pages and redraws
both elements in an overlay every frame: UI frames climbed from 11 to 30 ms as the panel grew (1280x760; larger
windows scale with their pixels). Removing the overlay clip or the pressed-shape animation changed nothing; turning
the shared bounds off removed the climb entirely.

`MorphState` draws the flight itself: one rounded rectangle and its GPU glass, whose bounds, color and corners follow
the Material motion scheme's spatial spring. Its bounds are applied in the layout phase, so nothing recomposes per
frame. The real button and panel report their bounds as they lay out and give way while the container flies. Page
fades multiply each draw's alpha (`CompositingStrategy.ModulateAlpha`) instead of drawing the page into a
window-sized offscreen layer.

| UI frame times over three page changes | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| shared-element transition | 2.6 | 9.9 | 18.8 |
| `MorphState` | 2.4 | 8.1 | 12.4 |

Still open: the first time a page opens, composing it takes about 150 ms (cold JIT and class loading), and every
page change has one 20 ms frame while the new page composes. That predates the morph; a JDK AOT cache (JEP 483/515)
built by the launcher is the likely fix.

Frosted surfaces now fade with their content: `LocalGlassFade` scales a glass region's opacity, so a fading page
or the account chip no longer leaves an empty frosted shape behind until it is removed.

## Joining fades from the menu into a ready world

Before, the world replaced the menu background the moment login finished: an empty sky, chunks popping in, and the
joining card vanishing in one frame. Now:

- The menu background stays on screen under the joining card while the world streams in. From login the world
  already draws every frame, unseen, under the menu (`MenuCompositor.hold`), so its first visible frames are not its
  first frames (warm code, created resources).
- `GameView.ready`: the player has a position, and the chunk under them and its eight neighbours have arrived and are
  meshed from their latest blocks and on the GPU (`SectionStreamer.settled`, `WorldRenderer.settled`). After six
  seconds the world shows anyway.
- Then `MenuCompositor.release` cross-fades the two scenes over 600 ms in one extra full-screen pass (`fade.frag`).
  The blur and backdrop sample the mix, so glass agrees with what is under it. The card fades out and the HUD fades
  in over the same 600 ms.

Fixing the stutter while chunks arrive took three render-thread changes, found by timing the loop's phases:

- **Re-mesh coalescing.** Every block or light change queued a full re-mesh job for its section, even with one
  already waiting: tens of thousands of jobs were pending a few seconds after login. A section now has at most one
  queued re-mesh, which reads the latest blocks when it starts.
- **Deduplicated changes.** `ClientWorld`'s changed-section queue handed the same sections out many times (a column
  load marks 27 sections for each of its 16). It is now a set; a key is removed before it is handed out, so a change
  made meanwhile is never lost. Drains of up to 29 ms no longer show among the frames over 12 ms.
- **Chest scans** read a section's state array directly (`ClientWorld.sectionStates`) instead of copying blocks and
  light cell by cell: from 5 to 24 ms per frame to under 4 ms on the frames still over 12 ms.

`VulkanRenderer.batchUploads` records every staging upload a view makes into one submission with one wait, instead of
draining the queue once per model (the game view builds dozens). It saved about 30 ms of the 230 ms constructor;
the rest is CPU work (model baking, image decoding).

| Join, frames over 12 ms after login (excluding screenshots) | before | after |
|---|---|---|
| at login (view construction + first frame) | 365 ms | 270 to 380 ms |
| while chunks arrive, under the card | 20 to 40 ms, many | none in most runs |
| during the reveal | 34, 30, 19 ms | one 13 ms frame |

Still open: the stall at login, when the game view is built on the render thread under the joining card. Building
the session-independent renderers once (or their CPU parts on the connection thread) would remove it.

Diagnostics: `-Dalkaline.stats.hitchMillis=N` logs every frame longer than N ms with its time, and the input script's
`T:shot:/path.png` saves the frame at T without ending the run.

## Notifications

A HUD module, Notifications (bottom right by default, Blur and Duration options), shows short messages as frosted
cards that slide in from their anchored side and fold away; up to four at once, newest at the anchored edge. In the
menus the same cards appear in the bottom-right corner with the menu's glass. Today they report resource packs
applied (or failing), the server's pack applied (or failing), and plugin messages.

## Plugins

> Superseded in part by [decision 0022](0022-unrestricted-plugin-api-and-manifest.md): capabilities and grants are gone
> (local plugins are unrestricted), the manifest is a defined format, and the API covers far more. This section
> records the first draft.

The user asked for a plugin system that can change both the Compose interface and the game, with access to game
data (clicking, for example) granted on the plugin's request. AGENTS.md warns against *speculative* plugin APIs; this
one is requested, kept narrow, and has no general event bus.

- `:modules:plugin-api` is all a plugin compiles against: `AlkalinePlugin`, `PluginContext`, `Game`, and the
  `Capability` it may ask for: notifications, HUD elements, a page, reading the player, reading chat, sending chat,
  seeing clicks, cancelling clicks. Every call checks its capability and throws `CapabilityException` without it.
- A plugin is a jar in `<settings folder>/plugins` with `alkaline-plugin.json` (id, name, version, api, main,
  description, capabilities). `PluginHost` (in `:modules:ui`) loads each with its own class loader whose parent is the
  client's, so plugins share the client's Kotlin, Compose and Material 3. It remembers which plugins are enabled and
  what the player granted in `plugins.json`.
- The ClickGUI's Plugins section lists every jar with what it asks to do. The player ticks what to grant and turns
  the plugin on; an enabled plugin's page folds out under it. Changing a grant restarts the plugin, so nothing it
  held stays reachable.
- Hooks are typed lists owned by plugins (tick, chat, click; HUD elements and pages as Compose state). Disabling a
  plugin removes exactly its own entries. Game hooks run on the render thread between frames; a listener that throws
  stops its plugin and posts a notification. A cancelled click is never acted on, and the game does not see the
  button held.

Trust: plugins run in the client's process with the player's own access to the computer. Capabilities decide what
Alkaline hands a plugin, not what its code could do. The JVM no longer has a security manager, so a real sandbox
would need a separate process. The Plugins section says so.

Limits of this draft: plugin HUD elements sit in a corner rather than in the HUD editor; a plugin's composable that
throws breaks the interface layer (Compose cannot catch composition errors); plugins are found at startup only; and
the API reaches the local player, chat, ticks and clicks, not the world, entities or packets.

`apps/example-plugin` builds `click-guard.jar`: it counts attack clicks on the HUD, drops a press closer than a set
gap to the previous one (with the cancel capability), has a page with the gap and a reset, and posts a notification
when it starts. Verified in game on a local server: four presses with one 20 ms after another showed
"3 clicks · 1 dropped", and the grant persisted across a restart.
