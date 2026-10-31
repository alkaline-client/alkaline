# Writing an Alkaline plugin

A plugin is a jar in `~/.alkaline/legacy/plugins/`:

- Its compiled classes.
- An [`alkaline-plugin.json`](manifest.md) manifest at the jar's root.
- Any resources it needs, such as its icon.

It compiles against `:modules:plugin-api` only (`compileOnly`). At run time the client provides that API, Kotlin,
Compose and Material 3, so none of them go into the jar. [`apps/example-plugin`](../../apps/example-plugin) builds two
complete examples: Click Guard (input, a HUD element, a command) and Waypoints (an overlay that follows the world, a
screen, the world and entities, settings of every kind). To build them:

```bash
./gradlew :apps:example-plugin:assemble
```

The jars are written to `apps/example-plugin/build/plugins/`.

## Trust

Plugins the player installs are unrestricted. They run in the client's process with the player's own access to the
computer, and every part of the API is open to them: there are no permissions to request or grant. The Plugins
section says so. Alkaline's planned plugin market will review the plugins it lists, and it will not list cheats.

## Lifecycle

The client creates the `entrypoint` class with its no-argument constructor and calls `enable(context)` on the UI
thread. Everything registered through the context lasts until the plugin is turned off; Alkaline removes it then.
At that point it also cancels `context.scope` and calls `disable()`. The plugin never needs to undo a registration
itself, and `Registration.remove()` ends one early.

When a plugin throws, from a listener, a coroutine in its scope, `enable`, or a composable or effect of its interface,
Alkaline:

- Quarantines that run of the plugin at once (its other hooks stop firing), then stops it and any plugin that depends
  on it.
- Notifies the player.
- Shows the error on the plugin's card, with where it happened ("in onTick", "in its interface").

A composable that throws is found by its class loader in the stack trace. Alkaline stops the plugin and rebuilds the
interface without it; state kept outside the composition survives, and `remember`ed state does not.

The plugin stays on in `plugins.json`, so it starts again with the client or with Reload. Reload in the Plugins
section rescans the folder: replace a jar and reload to try a new build without restarting. Alkaline runs each jar
from a copy in `plugins/.loaded/`, so the original can be replaced while the client runs, on Windows too.

Each plugin gets a new context when it starts. Once that run ends, the context is inactive (`context.active` is
false): registering throws, and actions do nothing. Long work on your own threads should check `active`.

## Being a good citizen

None of this limits what a plugin can do; it keeps one plugin's mistakes from becoming the player's problem.

- **Time:** every hook call is timed. The plugin's card shows how long it spent in hooks over the last second and
  its slowest call. A call over 50 ms is logged, since the game waits for it.
- **Stalls:** a call that holds a thread for 2 seconds is reported to the player as not responding, with the stuck
  thread's stack in the log. Java cannot stop it, so do not block in listeners: use `scope` and `game.schedule`.
- **Logs:** at most 40 lines a second reach the console and the card; the rest are counted.
- **Notifications:** a repeat within 4 seconds is merged into the one showing, and only the first few of a burst
  show; the rest go to the log.
- **Arguments:** bad ones throw `IllegalArgumentException` at the call. That means chat the server would kick for,
  non-finite angles, positions or volumes, and packet ids that are not serverbound play packets.

## Compatibility

Within one `api` version, Alkaline only adds to the API. The build checks the API's binary interface against
`modules/plugin-api/api/plugin-api.api`, so a change that would break built plugins cannot ship by accident. To stay
compatible as the API grows:

- Snapshot classes (`PlayerState`, `EntityState`...) and event objects may gain properties. Read them by name, and do
  not construct snapshots or call their `copy` or `componentN` functions.
- Enums and sealed types may gain values: give a `when` over them an `else` branch.
- Only the `alkaline.plugin` package is the API. The client's other classes are reachable, since plugins share its
  class path, but they change without notice.

## What the context offers

| Area | API |
|---|---|
| About itself | `info` (the manifest), `active`, `dataFolder`, `log` (shown on the card too), `settings` (declared in the manifest) |
| Work off the game | `scope` (coroutines, cancelled on disable), `runOnUiThread` |
| Interface | `notify`; `hud(id)` for declared HUD elements; `overlay` over the game view; `page` on the plugin's card; `openScreen` and `closeScreen` for a screen in Alkaline's panel |
| Other plugins | `dependency<T>(id)` for a running dependency's instance; dependencies' classes are visible |
| Game lifecycle | `onJoin`, `onLeave`, `onTick`, `schedule(ticks)` |
| Game state | `player`, `inventory`, `world` (blocks, light, biome, time, weather), `entities`, `entity(id)`, `target`, `server` (player list, scoreboard, titles, brand), `project` (world point to screen) |
| Chat | `onChat` (see, hide or replace incoming messages), `sendChat`, `showChat` (local lines), `command` (handle `/name` locally) |
| Input | `onClick` and `onKey` (see or cancel), `onPress` for a key setting, `onMovement` (steer), `attack`, `use`, `select`, `look` |
| Sound | `playSound`, with or without a position |
| Network | `onMessage` and `sendMessage` on plugin channels, `sendPacket` for raw play packets |
| Music | `music`: the current `track`, `playing`, `positionMillis`, `volume`; `play` and `enqueue` (a path, a link or a search), `pause`, `resume`, `toggle`, `next`, `previous`, `seek`; `onTrack` |

## Threads

- **The UI thread:** `enable`, composables and `runOnUiThread`.
- **The render thread:** game listeners, between frames. Return quickly: a slow listener is a dropped frame.
- **Game state is read on the render thread.** `player()`, `world()` and the other reads throw elsewhere. From a
  coroutine, use `game.schedule { }`.
- **Actions work from any thread.** Off the render thread they are queued for it.
- **Getting game data into the interface:** copy what the composable needs into Compose state (`mutableStateOf`) in
  a tick listener. Compose state may be written from the render thread.
- **Placing a label on a world point:** `project` is safe from any thread. Read it in the layout phase every frame,
  as Waypoints does, so the label moves without recomposing.
