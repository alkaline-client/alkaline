# Agent brief

Every implementation session reads this file before it starts. Sessions do not share memory, so this file and
your task prompt are all you know about earlier work. `AGENTS.md` (loaded automatically) holds the project rules;
this file adds what a session needs to work with others without conflicts.

<project>
Alkaline legacy: a Minecraft **1.8.9-only** PvP client. It uses Java 25, Gradle (`./gradlew`), a Vulkan renderer
through LWJGL, SDL3, and Compose for the in-game UI.

- `apps/game-client`: the game. The hot files are `ClientApp.java`, `PlaySession.java`, `GameView.java` and
  `EntityView.java`.
- `modules/game`: protocol, world, entities, inventory.
- `modules/renderer`: CPU models, meshing, assets, and the Vulkan code under `vulkan/`.
- `modules/ui`: Compose menus and screens (Material 3 Expressive).
- `docs/decisions/`: one numbered decision record per feature wave.
</project>

<reference_sources>
Use these to get exact vanilla behavior. Learn the constants and rules from them. Do not transplant their
structure (AGENTS.md: borrow as little code as possible).

- **Exact vanilla classes:** decompile them from the MCP-named jar
  `~/.gradle/caches/essential-loom/1.8.9/de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9/minecraft-mapped.jar`
  with Vineflower (`~/.gradle/caches/modules-2/files-2.1/org.vineflower/vineflower/1.12.0/*/vineflower-1.12.0.jar`).
  `unzip` only the classes you need into a scratch dir, then run `java -jar vineflower.jar -log=WARN <in> <out>`.
- **KotlinizedMCP** (`external/KotlinizedMCP-mcp/src/main/{java,kotlin}`) is quicker to read, but it is
  **modified**: EntityPlayerSP's sprint and jump rules differ from vanilla, for example. Check gameplay rules
  against the decompiled jar. Its `net/optifine/` package holds OptiFine's sources, which are the reference for
  CTM, custom sky, custom colors, random entities and the like.
- **Assets:** the vanilla jar with assets is `~/.minecraft/versions/1.8.9/1.8.9.jar`. The user's packs are in
  `~/.minecraft/resourcepacks`; Xe Lite uses `assets/minecraft/mcpatcher/` (cit, ctm, sky, color.properties).
- `external/` and these packs are **read-only**. Never commit Minecraft jars, assets, pack files, tokens, user
  settings or build output.
</reference_sources>

<runtime_check>
Compilation does not prove that rendering or gameplay works. For anything visible, run the game against a local
server.

- **Server:**
  1. Copy `~/.gradle/caches/essential-loom/1.8.9/minecraft-server.jar` into a scratch dir and write `eula.txt`.
  2. Write `server.properties` with `online-mode=false`, `use-native-transport=false` (needed on Java 25),
     `spawn-protection=0`, `level-type=FLAT`, and the **port given in your task**.
  3. Start it in the background with `tail -f cmd | java -jar minecraft-server.jar nogui`, where `cmd` is a FIFO.
     Send console commands (`op`, `give`, `summon`, `time set`, `tp`) with `echo ... > cmd`.
  4. Stop it with `echo stop > cmd` when you finish.
- **Gotchas:** never set `spawn-animals=false`, because the server then deletes every summoned animal. Teleport the
  player to y=4 on the flat world; higher can kill it.
- **Client:**
  1. Build it with `./gradlew :apps:game-client:installDist`.
  2. Run `apps/game-client/build/install/game-client/bin/game-client --settings <scratch>/settings.json --join localhost:<port> --input-script <file> --screenshot <png> --after-ms <ms>`.
     Input script commands are click, rclick, move, down, up, rdown, rup, key, keydown, keyup and resize. `--stats`
     prints frame stats.
  3. Look at the screenshot yourself. Use a scratch `--settings` so the user's `~/.alkaline/legacy` stays
     untouched.
- **Shell:** the Bash tool does not word-split or glob the way bash does, so wrap loops and globs in `bash -c '...'`.
</runtime_check>

<working_rules>
- Never speculate about code you have not opened. Read the interface and a call site before you write against it.
  If a signature or vanilla rule is uncertain, open the source or decompile the class.
- Before building, check whether the feature already exists in part, using a quick grep. Extend what is there. If
  the task is already done, say so in your handoff note and stop.
- Keep working until everything the task asks is done. Stop to ask only when you cannot go on without the user,
  or before a risky step.
- When the task is done and checked, stop and report. Do not add features, refactors or docs the task did not ask
  for. List ideas under "Follow-ups" in the handoff note instead.
- **Settle a doubt before you defer it.** Before you write "could not verify", "not done", or a suspicion about
  existing code, spend the few tool calls that settle it:
  - read the pack's properties file or the vanilla class;
  - or change the scene until it isolates the change: another time of day, another setting, the pack off, or a
    tiny test pack in your scratch dir.
  A doubt you could have settled in a minute is not a follow-up. "Could not separate" or "could not see" is not a
  result.
- **Fix small bugs you find on your task's path.** Fix it when all of these hold:
  - the fix is about 15 lines or fewer;
  - it is in a file you own, or fits as a hook;
  - the decompiled jar confirms the correct vanilla value or rule.
  Test it and list it under "What changed". Larger fixes and fixes outside your area go under "Follow-ups", with the
  evidence: file:line and the vanilla value.
- Hot paths (render loop, input, meshing) must not allocate per frame. Disk, decoding and other blocking work stays
  off the render thread. GPU uploads happen in `beforeFrame`, never while a frame is being drawn.
- Match the surrounding code: its naming, comment density and idioms. Javadoc states the vanilla class or method
  that a rule comes from.
- **Verify:**
  - When you change code that can be built or tested, run a real check that exercises the change before you report
    it done. Use the narrowest Gradle test or build, plus the runtime check above for anything visible.
  - A check that failed to start does not count.
  - If no real check can run, say which one you did not run and why. Do not report the change as done.
- Remove the temporary scripts and files you created, except those in your scratch dir.
</working_rules>

<conflict_protocol>
Several sessions work at once, each on its own branch. Conflicts are prevented first and then resolved by one
integrator per wave.

1. **Branch:** if your session is not already on its own branch or worktree, create `wave<N>/<task-id>` from `main`.
   Commit only there, in small commits. Never merge, rebase onto others, push, or touch another task's branch.
2. **Ownership:** your task lists the files and directories you **own**. You may change them freely.
3. **Hooks:** outside what you own, you may add small hooks: a call, a field, a switch case, or a settings key.
   Keep them at about 20 lines per file or fewer.
   - Append new cases and entries at the end of a list or switch instead of inserting them in the middle.
   - Do not reformat, reorder, rename or move existing code in files you do not own.
   - Prefer putting new logic in a **new file** that a one-line hook calls.
4. **Shared docs:** never edit `README.md` or `docs/decisions/*`; the wave integrator writes those. Write your
   handoff note to `docs/handoff/<task-id>.md` (a new file, so it cannot conflict). Use this structure:
   - **What changed:** the behavior, in a few lines.
   - **Files touched outside ownership:** each file, and what hook was added there.
   - **Verification:** the exact commands you ran and their results, plus the screenshots you checked.
   - **Not done / known gaps:** for each gap, what you tried and why it could not be finished.
   - **Follow-ups.**
5. **Commits:** messages are `<task-id>: <summary>`. End each one with
   `Co-Authored-By: Claude <model name> <noreply@anthropic.com>`, using your own model name.
6. **Final message:** the handoff note's first three sections, briefly.
</conflict_protocol>
