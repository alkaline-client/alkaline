# Architecture decision 0025: Version numbers and releases

Status: accepted. Implemented 2026-10-31.

## Context

The user asked for a version number system, suggesting `[year].[nth drop].[minor update]-[hotfix]`, and for a
build published as a GitHub release: either Alkaline's own layout or a client plus a version JSON that Minecraft's
own launcher can start.

## Decision: `<year>.<drop>.<update>[.<hotfix>]`

| Version      | Meaning                                       |
|--------------|-----------------------------------------------|
| `2026.1.0`   | The first drop of 2026                        |
| `2026.1.2`   | Its second update (small features and fixes)  |
| `2026.1.2.1` | A hotfix of that update                       |
| `2026.2.0`   | The next drop                                 |
| `2026.2.0-dev` | A build on the way to 2026.2.0              |

- **The year is the drop's year.** A hotfix of 2026.3 shipped in January is still `2026.3.x.y`; the first drop of
  the next year is `2027.1.0`.
- **A dot before the hotfix, not a hyphen.** In SemVer, and in Gradle, Maven, Cargo and most other tools, `-1` marks
  a pre-release, so `2026.1.2-1` would sort *before* `2026.1.2`. Four dotted numbers sort as intended everywhere and
  match Windows' four-part file versions. The fourth number is left out while it is zero. `-dev` keeps its
  pre-release meaning for builds between releases.
- **The full year, not `26`.** Minecraft itself is numbered `26.1`, `26.2` and so on; `Alkaline 26.1` beside
  `Minecraft 26.1` in a launcher would read as "for Minecraft 26.1". `2026.1.0` cannot be mistaken for a Minecraft
  version, and Alkaline's own multiversion future makes that matter more, not less.
- **The plugin API keeps its own version** ([decision 0022](0022-unrestricted-plugin-api-and-manifest.md)); a client
  release does not change it.

The version is set once, in the root `build.gradle.kts`. The game client's jar carries it as `Implementation-Version`,
and Settings → About shows it.

## Decision: both layouts, one zip per platform each

Each release has four files:

- `alkaline-<version>-<platform>.zip`: Gradle's `distZip` (one folder holding `bin/` and `lib/`), the layout
  Sulfur already downloads, unpacks into `~/.alkaline/legacy/client` and starts with Java 25
  (`sulfur-minecraft/src/alkaline.rs`). Sulfur finds it by that name under the tag `v<version>`.
- `alkaline-<version>-<platform>-minecraft-launcher.zip`: `versions/Alkaline-<version>/Alkaline-<version>.json` and
  `libraries/`, unzipped into `.minecraft`. The name does not end in `-<platform>.zip`, so Sulfur never takes it for
  the client.

Platforms are `linux-x64` and `windows-x64`. `-Palkaline.platform=` picks which natives (LWJGL, Skia, FFmpeg) a build
bundles, so one machine builds both.

The version JSON:

- is a standalone version, not `inheritsFrom: 1.8.9`, which would put LWJGL 2 and Java 8 under the client;
- asks for `java-runtime-epsilon`, Mojang's Java 25 runtime (25.0.1 on Linux and Windows today);
- names vanilla 1.8.9's client jar and asset index as its own, so the launcher downloads them from Mojang. The jar
  lands at `versions/Alkaline-<version>/Alkaline-<version>.jar`, and the JSON passes that path to the client as
  `-Dalkaline.minecraftJar=${library_directory}/../versions/${version_name}/${version_name}.jar`. As with the Paper
  server ([decision 0024](0024-local-paper-server-pvp-modules-and-shared-skeletons.md)), nothing of Mojang's is
  redistributed;
- lists every jar of the client's classpath, in the start script's order, with its SHA-1 and size and an empty URL:
  they are in the zip, and the repository is private, so a download URL would not work anyway.

Vanilla's 1.8.9 classes are then on the classpath as the version jar. Nothing loads them, and Alkaline's own
resources are looked up relative to its classes, so the jar's `assets/` do not shadow anything.

## Building a release

```bash
./gradlew :apps:game-client:distZip :apps:game-client:minecraftLauncherZip
./gradlew :apps:game-client:distZip :apps:game-client:minecraftLauncherZip -Palkaline.platform=windows-x64
```

The zips are in `apps/game-client/build/distributions/`. Tag the commit `v<version>`, publish the four zips as the
release, then move the version to the next `-dev`.

## Not verified

- The Windows zips are built on Linux and have not been run on Windows.
- The version JSON was checked by rebuilding its classpath as the launcher does and starting the client's main class
  from it; it has not been started from Minecraft's launcher itself.
- The launcher may skip libraries it considers duplicates. The classpath has two versions of a few Compose and
  AndroidX artifacts (as the start script's does); if the launcher drops one, the other remains.
