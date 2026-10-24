# Agent brief

Alkaline legacy is a Minecraft 1.8.9 PvP client built with Java 25, Gradle, Vulkan and Compose. The rules are in
AGENTS.md.

## References
- **Exact vanilla classes:** decompile them from
  `~/.gradle/caches/essential-loom/1.8.9/de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9/minecraft-mapped.jar`
  with Vineflower (`~/.gradle/caches/modules-2/files-2.1/org.vineflower/vineflower/1.12.0/*/vineflower-1.12.0.jar`).
  Unzip only the classes you need, then run `java -jar vineflower.jar -log=WARN <in> <out>`.
- **KotlinizedMCP** (`external/KotlinizedMCP-mcp`) is quicker to read, but it is modified. OptiFine's sources are in
  its `net/optifine` package.
- **Assets:** vanilla assets are in `~/.minecraft/versions/1.8.9/1.8.9.jar`, and the user's packs (Xe Lite) are in
  `~/.minecraft/resourcepacks`.
- `external/` and the packs are read-only. Never commit game assets, tokens, user settings or build output.

## Testing
- **Server:**
  - Copy `~/.gradle/caches/essential-loom/1.8.9/minecraft-server.jar` to a scratch dir and add `eula.txt`.
  - Write `server.properties` with `online-mode=false`, `use-native-transport=false`, `spawn-protection=0`,
    `level-type=FLAT` and `server-port=<your port>`.
  - Run `tail -f cmd | java -jar minecraft-server.jar nogui`, where `cmd` is a FIFO. Send commands with
    `echo ... > cmd`.
  - Teleport the player before you `setblock` near it, because the chunk must be loaded.
- **Client:**
  - Build it with `./gradlew :apps:game-client:installDist`.
  - Run `apps/game-client/build/install/game-client/bin/game-client --settings <scratch>/settings.json --join localhost:<port> --input-script "9500:key:T;9900:text:hi;10300:click:640,380" --screenshot out.png --after-ms 11000`.
  - Look at the screenshot.
- **Shell:** wrap loops and globs in `bash -c '...'`.

## Git
- Work on the branch `wave<N>/<id>` from `main`. Commit there, and don't merge or push.
- Outside your area, keep changes to small hooks, and don't reformat other code.
- Don't edit README.md or docs/decisions. Write `docs/handoff/<id>.md` instead: what changed, files touched outside
  your area, what you checked, and what's left.
