# The plugin manifest: `alkaline-plugin.json`

Every plugin jar has an `alkaline-plugin.json` at its root. It says who the plugin is, and it declares what
Alkaline must know before running any of the plugin's code: its settings, its HUD elements and the plugins it
needs. Alkaline reads the manifest when it scans the plugin folder. The Plugins section of the ClickGUI shows the
manifest's details, and lists every problem in it with the field that has the problem.

[`manifest.schema.json`](manifest.schema.json) is the JSON Schema (2020-12) for this format. To get completion
and checks in an editor, point the manifest's `$schema` at it.

```json
{
  "schema": 1,
  "id": "waypoints",
  "name": "Waypoints",
  "version": "1.0.0",
  "entrypoint": "example.waypoints.Waypoints",
  "api": 1,
  "minecraft": "1.8.9",
  "description": "Marks places in the world and points to the nearest.",
  "authors": ["Ana", { "name": "Bo", "contact": "bo@example.com" }],
  "license": "MIT",
  "links": {
    "homepage": "https://example.com/waypoints",
    "source": "https://example.com/waypoints/src",
    "issues": "https://example.com/waypoints/issues"
  },
  "icon": "waypoints/icon.png",
  "dependencies": { "map-core": "^2.1.0" },
  "optionalDependencies": { "click-guard": ">=1.0.0" },
  "channels": ["waypoints:sync"],
  "settings": [
    { "id": "add_key", "type": "key", "title": "Mark this place", "default": "key:B" },
    { "id": "range", "type": "integer", "title": "Show within", "min": 16, "max": 10000, "default": 1000 }
  ],
  "hud": [
    { "id": "nearest", "title": "Nearest waypoint", "anchor": "top", "x": 0, "y": 6 }
  ]
}
```

Alkaline reads the manifest strictly. A field it does not know is an error, so a misspelt field is caught and does
not silently do nothing. Fields of your own (for example, for your build tooling) start with `x-` and are ignored.

## Identity

| Field | Required | Meaning |
|---|---|---|
| `schema` | yes | The manifest format. This document describes format `1`. |
| `id` | yes | The plugin's permanent id: 2 to 64 characters of `a-z`, `0-9`, `-` and `_`, starting with a letter. It names the plugin's data folder (`plugins/data/<id>/`), its saved state and its dependencies. Do not change it between versions. Two jars with one id cannot both load. |
| `name` | yes | The name players see, up to 48 characters. |
| `version` | yes | A [Semantic Versioning 2.0.0](https://semver.org) version: `MAJOR.MINOR.PATCH`, with an optional `-prerelease` and `+build`. Other plugins' dependency ranges match against it. |
| `entrypoint` | yes | The fully qualified name of the class that implements `alkaline.plugin.AlkalinePlugin`. The class must be in the jar and must have a public no-argument constructor. |
| `api` | yes | The plugin API version the plugin was built against (`PLUGIN_API_VERSION` in `:modules:plugin-api`). Alkaline loads `api` from `PLUGIN_API_MINIMUM` up to its own version. Additions to the API keep the number. A removal or an incompatible change increases it. |
| `minecraft` | yes | `"1.8.9"`. A legacy Alkaline plugin is written against the 1.8.9 game. Other editions of Alkaline do not load it. |

## About

| Field | Meaning |
|---|---|
| `description` | One or two sentences, up to 280 characters, shown under the name. |
| `authors` | A list. Each entry is a name, or `{ "name", "contact" }`, where `contact` is any address up to 256 characters. |
| `license` | An [SPDX license expression](https://spdx.org/licenses/), such as `MIT` or `Apache-2.0 OR MIT`. |
| `links` | `homepage`, `source` and `issues`: `https://`, `http://` or `mailto:` addresses. Each one becomes a button on the plugin's card. |
| `icon` | The path of a PNG inside the jar (relative, no `..`), up to 256 KiB. It is shown at 40 dp, so 64 or 128 pixels square is enough. |

## Dependencies

`dependencies` and `optionalDependencies` map other plugins' ids to version ranges:

- Turning a plugin on also turns on its required dependencies, and they start first.
- Turning a dependency off also turns off the plugins that need it.
- A plugin whose required dependencies are missing, or installed at a version outside the range, cannot be
  turned on. Its card says why.
- When an optional dependency is installed and on, it starts first. When it is missing, the plugin starts anyway.
- A plugin's class loader sees its dependencies' classes, so it can call them directly.
  `context.dependency<T>(id)` returns the running instance of a dependency.
- Plugins that depend on each other cannot load.

Ranges use the npm syntax:

| Range | Matches |
|---|---|
| `1.2.3` or `=1.2.3` | exactly 1.2.3 |
| `>1.2.3`, `>=1.2.3`, `<2.0.0`, `<=1.9.9` | as written |
| `>=1.2.0 <2.0.0` | both (comparators joined by spaces must all hold) |
| `1.0.0 \|\| >=3.0.0` | either |
| `^1.2.3` | `>=1.2.3 <2.0.0` (`^0.2.3` is `<0.3.0`, `^0.0.3` is `<0.0.4`) |
| `~1.2.3` | `>=1.2.3 <1.3.0` |
| `*` | any release |

`^` and `~` accept partial versions (`^1`, `~1.2`). A pre-release such as `2.0.0-beta.1` matches only a range that
names a pre-release of 2.0.0. Without that, `>=1.0.0` would select an unfinished major version.

## Channels

`channels` lists plugin-message channels, each 1 to 20 printable characters and not starting with `MC|`. When the
player joins a server, Alkaline announces the channels of the plugins that are on with a `REGISTER` message, so
server plugins (Bukkit, BungeeCord, and so on) know they may send on them. A plugin can send and receive on any
channel through `game.sendMessage` and `game.onMessage`. This list only controls what Alkaline announces.

## Settings

`settings` declares the plugin's settings. Alkaline stores them in `plugins/data/<id>/settings.json` and shows them
on the plugin's card with Alkaline's own controls, even while the plugin is off. The plugin reads them with
`context.settings.<type>(id)`. A setting read in a composable recomposes it when the setting changes.

Every setting has:

- `id`: lower_snake_case, up to 32 characters.
- `type`.
- `title`: up to 48 characters.
- `description` (optional): up to 200 characters.
- `default` (optional): the value until the player changes it.

| `type` | Value | Further fields | `default` if left out |
|---|---|---|---|
| `boolean` | a switch | | `false` |
| `integer` | a slider of whole numbers | `min`, `max` (required) | `min` |
| `decimal` | a slider | `min`, `max` (required) | `min` |
| `color` | `"#AARRGGBB"`, read as an ARGB int | | `"#FFFFFFFF"` |
| `text` | one line | `maxLength`, 1 to 4096 (default 256) | `""` |
| `choice` | one of `options`, 1 to 16 names of up to 32 characters | `options` (required) | the first option |
| `key` | `none`, `key:<name>` (the platform's key name: `key:R`, `key:Left Shift`), or `mouse:<left\|right\|middle\|x1\|x2>` | | `none` |

A `key` setting is rebound like Alkaline's own Controls. `game.onPress(setting)` runs when its input is pressed in
game. The file is versioned like Alkaline's own settings. A value that is no longer valid (out of a new range, or a
removed option) reverts to its default and is noted in the plugin's log. To change a setting's meaning, give it a
new id.

## HUD elements

`hud` declares the plugin's HUD elements. Each element is an entry in the HUD editor: the player drags, scales,
hides and resets it like Alkaline's own modules, and its card has a switch and a Move button. The plugin draws the
element with `context.hud(id) { ... }`. An element that the plugin has not drawn yet is not shown.

| Field | Meaning |
|---|---|
| `id` | lower_snake_case, up to 32 characters. |
| `title`, `description` | As shown in the editor and on the card. |
| `anchor` | Where the element starts: `top_left` (default), `top`, `top_right`, `left`, `center`, `right`, `bottom_left`, `bottom` or `bottom_right`. |
| `x`, `y` | Offset from the anchor in dp, positive right and down (default 0). |
| `scale` | 0.5 to 2 (default 1). |
| `enabled` | Whether the element shows before the player chooses (default `true`). |

## Problems

All problems are reported together, each with a path (`settings[2].default: must be one of the options.`), in the
plugin's card and in the client's log. A plugin with any problem does not load.
