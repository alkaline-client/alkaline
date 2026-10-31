# Architecture decision 0022: An unrestricted plugin API and a defined manifest

Status: accepted. Implemented 2026-10-31. Supersedes the capability model of
[decision 0021](0021-page-morph-world-hand-off-notifications-and-plugins.md#plugins).

## Context

Decision 0021 drafted a narrow plugin API, with:

- Eight capabilities a manifest asked for and the player granted one by one.
- A manifest of seven loosely checked fields.

The user asked for the plugin system's scope to be expanded greatly and for the manifest to be properly defined. The
user then ruled on restrictions: plugins must not be limited. Gating got in the way of developing plugins, and
plugins the player develops or installs locally are always unrestricted. Alkaline's planned online plugin market
(once monetized) will review what it lists and ban cheats. That review is the policy layer, not runtime checks in
the client.

## Decision

**No capabilities.** Every part of the API is open to every installed plugin. That includes:

- Input control: `attack`, `use`, `select`, `look`, steering movement.
- Cancelling clicks and keys.
- Changing incoming chat.
- Raw play packets.
- Client-side block changes.

The grant checkboxes, `Capability` and `CapabilityException` are gone. The Plugins section says plainly that plugins
run with the player's access to the computer and can do anything Alkaline can.

**The manifest is a defined, versioned format** (`"schema": 1`). It is documented in
[`docs/plugins/manifest.md`](../plugins/manifest.md) with a JSON Schema beside it:

- Identity: `id` (permanent, also the data folder), `name`, a SemVer `version`, `entrypoint`, the `api` built
  against, and `minecraft: "1.8.9"`.
- About: `description`, `authors`, an SPDX `license`, `links` and an `icon`.
- Relations: `dependencies` and `optionalDependencies` with npm-style ranges, and plugin-message `channels`.
- Declarations: `settings` and `hud` elements.

The parser is strict. An unknown field is an error, and only `x-` fields are free. It reports every problem with its
path, on the plugin's card. A manifest declares what Alkaline needs before any plugin code runs. That is why
settings and HUD elements live in the manifest and not in code: the player can configure a plugin that is off, and
its HUD elements have saved placements before it draws them.

**Settings are Alkaline settings.** Each plugin's declared settings become a `ConfigSchema` and a `ConfigStore` in
`plugins/data/<id>/settings.json`. Its card shows them with the same controls as Alkaline's own settings, including
key capture. The config module gained one kind for this: `Setting.OptionSetting`, a choice among strings named at run
time, because an enum's constants are not known for a plugin. Text settings became editable.

**HUD elements join the HUD editor.** Each declared element is a `HudContribution`: a switch and a `HudPlacement` in
the plugin's store. `GameHud` places these elements and Alkaline's modules with the same code. They drag, snap,
scale, hide and reset like modules, and the editor's toolbar works on either kind.

**Dependencies are real:**

- A plugin's class loader looks in the client first, then its own jar, then its dependencies'. A plugin can
  therefore call a library plugin's classes directly. `context.dependency<T>(id)` returns the library's running
  instance.
- Enabling a plugin enables what it needs, and its dependencies start first.
- Disabling a plugin disables what needs it.
- Remembered plugins start in dependency order.
- Missing or mismatched versions and cycles are reported on the card.

**The API, by area** (`:modules:plugin-api`, `Plugin.kt` and `Game.kt`):

- Context: `info`, `dataFolder`, `log` (also shown on the card), `settings`, a coroutine `scope` cancelled on
  disable, and `runOnUiThread`.
- Interface: notifications, HUD elements, an `overlay` over the game view, a `page` on the card, and `openScreen`,
  which shows a screen in Alkaline's panel with the mouse free (`Overlay.PLUGIN`).
- Game lifecycle: join, leave, tick and `schedule`.
- Game state, as immutable snapshots:
  - The player, with motion, health, food, effects, experience, mode and dimension.
  - The inventory.
  - The world: blocks with registry names, light, biome, time, weather, and client-side `setBlock`.
  - Entities, with type names, players' names, health and equipment.
  - The crosshair target.
  - The server: player list, sidebar, titles and brand.
  - `project`, which maps a world point to a point on screen.
- Chat: see, hide or replace incoming messages, send, show local lines, and commands. A command is handled in the
  client and never reaches the server; its reply is prefixed with the plugin's name.
- Input: see or cancel clicks and keys, the plugin's own key bindings, movement steering, and attack, use, select
  and look.
- Sound: positional and non-positional.
- Network: plugin channels (REGISTERed at join for manifest `channels`) and raw outgoing packets.

There is still no event bus (AGENTS.md). Each kind of hook is a typed list of entries owned by plugins, and stopping
a plugin removes exactly its entries, cancels its scope and closes its screen.

**Threads:**

- Game listeners run on the render thread between frames.
- Game state is read there. A read from another thread throws a clear `IllegalStateException`, not a data race.
- Actions work from any thread and are queued for the render thread.
- `project` reads the last frame's camera from any thread. The game view publishes its camera only while a plugin
  draws an overlay: one 16-float copy per frame then, nothing otherwise.

**Reload** in the Plugins section rescans the folder, so a rebuilt jar runs without restarting the client.

## Examples

`apps/example-plugin` now builds two jars from two source sets:

- **Click Guard 1.1.0:** declared settings (a switch, a gap slider, a reset binding), a HUD element in the editor,
  and `/clickguard`.
- **Waypoints 1.0.0:**
  - Bindings to mark a place and to open a list screen.
  - `/waypoint`.
  - An overlay that labels each waypoint where it shows: `project`, read in the layout phase every frame, so labels
    move without recomposing.
  - A nearest-waypoint HUD element with distance and direction.
  - A players-nearby element that reads entities.
  - World reads: the block a waypoint is on.
  - Sound.
  - Per-server files in its data folder.

## Verification

Run against a local Mojang 1.8.9 server, fresh world, 1280x760:

- **Keybinding and notification:** pressing the binding marked a waypoint, and the notification gave the block from
  `world()` ("on grass").
- **Commands:** `/waypoint add Tower`, `/clickguard gap 30` and `/clickguard` replied in chat. The server log shows
  none of them.
- **Settings:** the gap setting changed by command applied at once. Presses 15 ms apart: 3 counted, 1 dropped.
- **Projection:** with the server teleporting the player 12 blocks south, facing north, the label sat at the screen
  center. Turned 40° (yaw 140), it sat at x ≈ 1095 px. The 70° vertical FOV at this aspect predicts 1097 px, and the
  HUD arrow read ↗.
- **Persistence:** waypoints reloaded from the data folder on the next join.
- **Plugins section:** icons, versions, authors and the API version; settings of every type (binding, choice,
  color, integer slider, switch); HUD rows; commands.
- **HUD editor:** Move opened it with the plugin element selected, and turning a hidden element on showed it.
- **Turning off and Reload:** turning Click Guard off removed its HUD element at once and saved that in
  `plugins.json`. Reload rescanned with the state kept.

Unit tests cover:

- SemVer parsing and precedence (the specification's ordering example).
- Range semantics: `^`, `~`, comparators, `||` and pre-releases.
- The manifest: every field read, and problems reported with their paths.
- The host:
  - Dependency order on enable, on disable and on start.
  - Unmet versions.
  - Bad jars.
  - Chat hide and replace.
  - Commands.
  - Undeclared HUD elements and settings of the wrong type rejected.

## Limits

- A plugin composable that throws still breaks the interface layer (Compose cannot catch composition errors).
  Listeners, coroutines and `enable` are guarded. *No longer true: [decision 0023](0023-plugin-hardening-and-music-player.md)
  stops the plugin and rebuilds the interface.*
- Incoming packets are not exposed raw, only as the typed state above and plugin channels. Decoding happens on the
  network thread, and a raw hook there would hold up the connection.
- Plugin commands have no tab completion. The server answers completions.
- Overlays follow the camera of the last drawn frame. The interface composes on its own thread, so a label can trail
  a fast turn by a frame.
- World-space rendering (boxes and lines drawn by the GPU) is not offered: the renderer has no line pipeline yet.
- The market, signing and review are not built (not scheduled).
