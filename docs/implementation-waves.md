# Implementation waves

This plan splits the remaining legacy work into waves of self-contained prompts, one prompt per session. Sessions
share no memory across models or effort levels. Each prompt therefore carries its own context, and
[agent-brief.md](agent-brief.md) carries what every session needs: the reference sources, the runtime check and the
conflict protocol.

Prompt style follows Anthropic's guidance for Claude 5.5 models:

- State the goal and **why** it matters.
- Use XML sections.
- Say "read before you write".
- Require a real check that exercises the change before the work is called done.
- Name the stopping points: "keep working until done" and "no unrequested additions".
- Set effort with the model selector, not in the prompt.

## How to run a wave

1. **Start sessions.** Open one session per task in the wave, with the model and effort listed. Paste the prompt
   block as the first message.
   - Open every session in `~/Projects/Alkaline/legacy`, not the parent folder. Then the relative paths in the prompts
     resolve, and `AGENTS.md` loads automatically.
   - Tasks in the same wave own disjoint files, so they may run at the same time.
   - From wave 1 on, start each one **in its own worktree**, so each gets its own branch.
2. **Use distinct ports.** Each task has its own server port, so parallel runtime checks do not collide.
3. **Integrate.** When every task in the wave has reported, run the wave's **integrator** prompt in a session on
   `main`. It merges the branches in the listed order and resolves conflicts. It then builds and tests, runs one
   runtime check across the wave, writes the decision record and pushes `main`.
4. **Check the handoff notes.** A task whose handoff note says "not done" moves to the next wave with that note
   pasted into its prompt.

### Model choice

| Model and effort | Use for |
|---|---|
| Opus 5.5 High | New subsystems, Vulkan or shader work, exact-vanilla ports with hard math, and the performance pass |
| Opus 5.5 Medium | Hard but well-specified work, Compose UI work, and the integrators (conflict resolution needs judgment) |
| Sonnet 5.5 Medium | Well-specified features in one area, and mechanical ports with a clear reference |
| Sonnet 5.5 High | Non-UI features across several files or modules |
| Haiku 5.5 High | Tests, docs and small isolated changes |

**Compose UI work goes to Opus 5.5**: code that writes Compose screens, HUD elements, overlays or prompts. Sonnet's
Compose work needed rework, and design quality there matters more than the cost. Everything else, including
in-world rendering such as name tags, stays on the tier listed.
| Haiku 5.5 Extra (xhigh) | Small vanilla-rule fixes that need careful reading |

If a cheaper session stalls or reports a check it could not run, rerun the task one tier up with its handoff note
attached. Do not keep retrying on the same tier.

**Sonnet tends to defer.** It does the asked steps well, but parks anything uncertain under "Not done" or
"Follow-ups" instead of spending a few calls to settle it. 0.1 is an example: it never checked the sky colormap, and
it suspected a sky timing bug that a one-line read of the pack disproved. The brief now forbids unsettled deferrals.
For Sonnet tasks, also read the "Not done" section before accepting the work. Send a short follow-up in the same
session if an item there names no attempt; continuing the session is cheaper than a new one.

### Hot files

`ClientApp.java`, `PlaySession.java`, `GameView.java` and `EntityView.java` collect hooks from almost every
feature. No task owns them except where noted. Tasks add only small hooks there (see the brief), and the
integrators resolve the overlaps.

### Not scheduled

These need a decision or hardware first:

- **Vulkan 1.1 compatibility mode:** AGENTS.md says not to build it until it is scheduled.
- **Singleplayer:** it needs an integrated server.
- **Windows qualification:** it needs a Windows machine.
- **Target-GPU qualification:** it needs GTX 10 series or older hardware.

---

## Wave 0: finish the current pack work and publish the repo

This wave runs in the existing checkout `~/Projects/Alkaline/legacy`, which has no usable git yet, so there are no
worktrees. Run 0.1 and 0.2 together, then 0.1b, then 0.3, then 0.4 last.

### 0.1 Wire OptiFine custom colors (Sonnet 5.5 High, port 25621)

```text
Read docs/agent-brief.md first and follow it. This checkout has no usable git (the index is corrupt); skip the branch and commit steps of the conflict protocol, but still write docs/handoff/0.1.md.

<goal>
OptiFine/MCPatcher custom colors (color.properties and custom colormaps) are parsed and partly applied but not yet wired into the game. Finish the wiring so the user's Xe Lite pack shows its custom sky, fog, water, grass, foliage, potion and spawn-egg colors, as OptiFine 1.8.9 does. The user plays with this pack, so correctness against OptiFine matters more than breadth.
</goal>

<current_state>
- modules/renderer/src/main/java/alkaline/renderer/world/CustomColors.java parses color.properties and colormaps (formats vanilla/grid/fixed). Its accessors include potion(effect), waterPotion(), egg(entity, layer), lilyPad(), waterDrop(), block(...), water(), sky(), fog(), underwater(), pine() and birch().
- BlockPalette already uses it for block tints and the water tint: instance tintColor and customColors(). ChunkMesher uses palette.waterTint for liquids.
- Sky.java (renderer/world) has the public fields customColors (default CustomColors.NONE), biome (default 1) and rainfall (default 0.4). It already applies the sky, fog and underwater colormaps, but nothing sets those fields yet.
- modules/game/src/main/java/alkaline/game/world/Biomes189.java has no rainfall table. Check whether modules/game/.../inventory/ItemColors.java or ItemCatalog already holds one before adding another. ClientWorld.biome(x, z) returns the biome id.
- The OptiFine reference is external/KotlinizedMCP-mcp/src/main/java/net/optifine/CustomColors.java and CustomColormap.java (read-only).
</current_state>

<do>
1. Add vanilla 1.8.9 rainfall per biome id, with public static float rainfall(int biome); take the values from the decompiled BiomeGenBase registrations. Mutated variants (id + 128) copy their parent; unknown ids default to 0.5. Add a small unit test.
2. In apps/game-client/.../GameView.java, before sky.update(...) each frame, set sky.customColors = palette.customColors(), sky.biome = the biome at the camera block, and sky.rainfall = rainfall(sky.biome). Reuse fields; allocate nothing per frame.
3. In apps/game-client/.../ItemCatalog.java, apply the custom tints:
   - potions (item 373): liquid layer from potion.<effect name>, and potion.water for water bottles;
   - spawn eggs (383): egg.shell.<entity> and egg.spots.<entity>;
   - lily pads: lilypad.
   Keep vanilla colors when the pack defines none.
4. If CustomColors exposes a water drop color and particles have a tint hook, apply drop.water; otherwise list it under Follow-ups.
</do>

<done_when>
- ./gradlew :modules:renderer:test :modules:game:test :apps:game-client:compileJava passes.
- Runtime check with Xe Lite selected:
  - put the zip from ~/.minecraft/resourcepacks into the scratch settings' resource_packs.selected (see ResourcePacks and ClientSettings.RESOURCE_PACKS for the format);
  - take screenshots at noon and at sunset looking at grass, water and the sky, and one with a potion and a spawn egg in the hotbar;
  - compare against the same screenshots with no pack. The colors must visibly change where Xe Lite defines them, and nowhere else.
</done_when>
```

### 0.1b Close the gaps left by 0.1 (Sonnet 5.5 High, port 25622)

0.1 left four items open, and its report deferred checks it could have done.

```text
Read docs/agent-brief.md first and follow it, especially "Settle a doubt before you defer it". This checkout has no usable git; skip the branch and commit steps. Append your results to docs/handoff/0.1.md under a new heading "0.1b".

<goal>
Custom colors were wired in by an earlier session. Its report left four items open. Close each one with evidence, as OptiFine 1.8.9 and vanilla 1.8.9 do it.
</goal>

<items>
1. **Sky and fog colormaps were never verified.**
   - The earlier session could not see them because Xe Lite's sky3 layer is a full skybox with blend=replace from 5:30 to 18:20. That is correct OptiFine behavior, not a timing bug, so do not change CustomSky.
   - Verify the colormaps where the skybox does not cover them: at night (/time set 18000), in fog at the horizon, and underwater.
   - If Xe Lite does not define sky, fog or underwater colormaps (check its color.properties and colormap/ folder first), make a tiny test pack in the scratch dir that defines them with obvious colors, and screenshot with and without it.
   - Fix any wiring bug you find in Sky.java or GameView.
2. **Lily pad item color.**
   - ItemCatalog tints the lily pad item 0x208030.
   - Vanilla ItemLilyPad.getColorFromItemStack returns 7455580 (0x71C35C); 0x208030 is the placed block's colorMultiplier.
   - Confirm both in the decompiled jar (ItemLilyPad, BlockLilyPad), fix the item, and add a test.
3. **Negative world time.**
   - When doDaylightCycle is false, the server sends a negative time in Time Update, and vanilla's client uses its absolute value (NetHandlerPlayClient.handleTimeUpdate and WorldClient.setWorldTime).
   - Confirm it in the decompiled jar and fix it where the client stores world time. Add a test.
   - At runtime, /gamerule doDaylightCycle false at /time set 6000 must stay daytime.
4. **drop.water.**
   - Rain splash, bubble, wake and drip particles have no per-spawn tint.
   - Add the smallest hook that lets those particle spawns take the custom water color (OptiFine's CustomColors.updateWaterFX: it uses the water colormap at the particle's position when drop.water is not set). Compute the color at spawn, never per frame per particle.
   - At runtime, use a test pack with a strong drop.water color and screenshot rain splashes.
</items>

<done_when>All four are closed with a test or a screenshot you inspected, the same Gradle tasks as 0.1 pass, and nothing is left under "Not done" without a stated attempt.</done_when>
```

### 0.2 Tests for chests, CTM, custom sky and custom colors (Haiku 5.5 High, no server)

```text
Read docs/agent-brief.md first and follow it. This checkout has no usable git; skip branch and commit steps, but write docs/handoff/0.2.md. Another session is editing GameView, ItemCatalog and Biomes189 at the same time: do not touch them. If Gradle reports a lock held by another build, wait a minute and retry.

<goal>
Four recent features have no unit tests. Add focused tests that pin their rules, so later waves cannot silently break them. Write tests only; if a test exposes a real bug, do not fix it, but describe it in the handoff note with the failing assertion.
</goal>

<own>Only new files under modules/renderer/src/test/java/ and apps/game-client/src/test/java/.</own>

<targets>
Read each class fully before writing its tests.
1. modules/renderer/.../world/ConnectedTextures.java, against the MCPatcher/OptiFine reference external/KotlinizedMCP-mcp/src/main/java/net/optifine/ConnectedTextures.java:
   - tile index for the ctm (47-tile), horizontal, vertical, horizontal+vertical, vertical+horizontal, top, random, repeat and fixed methods, using a fake Neighbors;
   - properties parsing: a blockN filename gives matchBlocks, anything else gives matchTiles; tile ranges like "0-3 5"; the faces bits;
   - connect=block versus connect=tile.
2. modules/renderer/.../world/CustomSky.java, against net/optifine/CustomSkyLayer.java:
   - time parsing ("hh:mm" gives (hh-6)*1000);
   - fade in and fade out brightness at boundary times, including wrapping past midnight;
   - rejection when the fade times do not sum to 24h or required keys are missing;
   - the axis mapping (z, y, -x), with default (1, 0, 0);
   - weather conditions.
3. modules/renderer/.../world/CustomColors.java:
   - hex color parsing;
   - colormap lookup for the vanilla (temperature/rainfall), grid (biome id by y) and fixed formats, using a tiny in-memory AssetSource;
   - potion and egg key mapping.
   See how existing tests build fake assets (grep for AssetSource in src/test).
4. apps/game-client/.../ChestTracker.java:
   - key() packing round trip;
   - blockAction only for action 1 on chest blocks;
   - the lid moves 0.1 per tick, clamped to 0..1;
   - previousLid interpolation.
</targets>

<done_when>The new tests pass with ./gradlew :modules:renderer:test :apps:game-client:test, and the handoff note lists every test class and any bug found.</done_when>
```

### 0.3 Docs for the pack work (Haiku 5.5 High, after 0.1 and 0.2)

```text
Read docs/agent-brief.md first. This one task is allowed to edit README.md, docs/decisions/0012-*.md and the memory file named below; then delete docs/handoff/0.1.md and 0.2.md after folding them in.

<goal>
Decision 0012 still says chests, CTM, custom sky and custom colors are "not implemented yet". They now are. Make the docs honest and current, in the existing plain style: short sentences, lists over walls of text, no marketing.
</goal>

<facts>Read docs/handoff/0.1.md and 0.2.md for the latest. In addition:
- Chests: ChestModels (lid, knob, base; single and large) and ChestTracker (rescans changed sections; Block Action 1 opens the lid 0.1 per tick). Chest items draw as 3D chests in hand and in the HUD.
- CTM (ConnectedTextures): optifine/ctm and mcpatcher/ctm with the ctm, horizontal, vertical, h+v, v+h, top, random, repeat and fixed methods. Faces with different CTM tiles never merge. Unsupported: ctm_compact, overlay, biome/height conditions, and non-cube models (panes, plants).
- Two block texture arrays (up to 2048 layers each, the GPU limit) with 16-bit texture ids; the shaders pick the sampler per vertex.
- Custom sky (CustomSky and SkyRenderer): OptiFine's rules for time, fade, rotation, axis, weather and blend. Every blend mode including replace. Fixed a vertically mirrored sun, moon and stars (sky geometry now uses the y-up viewport). Unsupported: biome conditions and transition smoothing.
- The image decoder limit was raised so 3072×2048 skies load.
- Custom colors: see 0.1. Unsupported: smoothing radius, xporb, redstone, text, sheep and map colors.
</facts>

<do>
1. Rewrite the "Not implemented yet" part of docs/decisions/0012-*.md into a section per feature, keeping the existing headings above it.
2. Update README.md only where it states these features are missing.
3. Update ~/.claude/projects/-home-bhop4real-Projects-Alkaline/memory/user-resource-packs.md: CTM, sky and colors are done (date 2026-10-23); list what is still unsupported.
</do>

<done_when>Docs updated; ./gradlew help still runs (no build files touched); handoff notes deleted.</done_when>
```

### 0.4 Audit .gitignore, re-create git, force push (Sonnet 5.5 Medium, last)

```text
Read docs/agent-brief.md first.

<goal>
The legacy repo's git index is corrupt. The user has explicitly asked to start the history over: re-initialize git in ~/Projects/Alkaline/legacy, commit everything needed to build Alkaline as one commit with the message "a huge pile of code", and force push it to https://github.com/alkaline-client/alkaline (branch main). This force push is authorized by the user for this task only. Before that, make sure the commit contains exactly what is needed to build, and nothing private or third-party.
</goal>

<steps>
1. Audit .gitignore. It must exclude:
   - build output (**/build/, .gradle/, .kotlin/) and IDE files;
   - external/ entirely (read-only upstream checkouts);
   - docs/handoff/;
   - logs, crash dumps and .env files;
   - the stray run-settings directories at the repo root: wayland/, x11/, low_latency/, uncapped/. These hold settings.json and profiles from test runs. Ignore them; do not delete them, they are the user's.
   Also search the tree for anything else private or third-party: *.jar except gradle/wrapper/gradle-wrapper.jar; Minecraft assets (assets/minecraft, *.ogg, vanilla textures); access tokens (grep -ri for accessToken, refresh_token, "Bearer "); machine-specific absolute paths in committed files; native libraries (*.so, *.dll, *.dylib). List what you find before changing anything.
2. Move the corrupt repo aside: mv .git ../legacy-git-backup-2026-10-23. Do not delete it. Then git init -b main.
3. git add -A; review `git status --short | head -200` and `git count-objects -vH` after committing. Nothing excluded by step 1 may appear, and no single file may be over 5 MB unless you can explain it.
4. Prove the commit builds without the ignored files: git clone the new repo into a scratch dir and run ./gradlew build there. If the build needs something that is ignored (for example a file under external/), stop and report it; do not commit third-party code to make it pass.
5. Commit with the message
   a huge pile of code

   Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
   (use your own model name). Then:
   git remote add origin https://github.com/alkaline-client/alkaline
   git push --force -u origin main
   If the push fails on authentication, stop and report the exact error. Do not try other credentials.
</steps>

<done_when>The remote main has one commit, the scratch clone builds, and the handoff note (docs/handoff/0.4.md, ignored by git) lists the .gitignore changes and the file count.</done_when>
```

---

## Wave 1: core gameplay gaps

Start every task in its own worktree from `main`, after wave 0 is pushed.

Before running a task, grep for its feature. Several prompts were written from an older decision record, and 1C and
2E turned out to be built already.

| Task | Model | Owns | Port |
|---|---|---|---|
| 1A Remaining mobs | Sonnet Medium | `EntityModels.java`, new model files, their tests | 25631 |
| 1B Chat | Opus (reworked by the planning session) | new chat files in `modules/ui` and `apps/game-client` | 25632 |
| 1C Chest-like screens | **Already built**: skip | inventory or window screen files in `modules/ui`, `Window` screen code | 25633 |
| 1D Sound | Opus High | new `audio` package, its build dependencies | 25634 |
| 1E Enchantment glint | Opus Medium | `ModelRenderer`, item shaders, `ItemIcons`, `CustomItems` glint | 25635 |

The integrator merges in this order: 1A, 1E, 1D, 1C, 1B.

### 1A Remaining mob models (Sonnet 5.5 Medium)

```text
Read docs/agent-brief.md first and follow it. Task id 1A, branch wave1/1A, server port 25631.

<goal>
Players on PvP servers see lobbies, mini-games and NPCs built from every mob type. Entities without a model draw as nothing, which reads as a bug. Add vanilla 1.8.9 models and animations for the mobs that are missing.
</goal>

<context>
modules/renderer/src/main/java/alkaline/renderer/model/EntityModels.java builds CuboidModel parts for blaze, chicken, cow, creeper, enderman, ghast, iron_golem, pig, sheep, skeleton, spider, squid, villager and wolf. Players and zombie-like bipeds use PlayerModels and BipedAnimator. Read EntityModels, CuboidModel, ModelBox, ModelPose and the existing tests to see how a model, its texture and its per-tick pose are expressed, and how apps/game-client/.../EntityView.java picks a model for an entity type.
</context>

<do>
Port, in this order, stopping to verify each group:
- witch, bat, rabbit, ocelot or cat, silverfish, endermite;
- slime and magma cube (the inner and outer slime layers), cave spider (scaled spider), mooshroom, snow golem, zombie villager, wither skeleton (scaled), giant;
- horse, donkey, mule, and zombie and skeleton horses (ModelHorse with its saddle, chest and armor variants);
- guardian and elder guardian, wither, ender dragon.
Read each mob's Model*, Render* and Layer* classes from the decompiled jar (exact boxes, pivots, texture sizes, scales, the animation math in setRotationAngles and setLivingAnimations, and the textures each variant picks from its metadata). Mob metadata comes from the entity tracker in modules/game; add a metadata accessor there only as a hook. If time runs short, finish whole groups and list the rest in the handoff note.
</do>

<done_when>
- Unit tests pin the part counts and the boxes of one model per group.
- At runtime, summon each mob in a row (/summon with NoAI:1) and screenshot them from the front and the side.
- Every mob is drawn, textured, and upright.
</done_when>
```

### 1B Chat input and chat screen (done: Sonnet, then reworked by Opus)

```text
Read docs/agent-brief.md first and follow it. Task id 1B, branch wave1/1B, server port 25632.

<goal>
The client cannot type chat or commands, which blocks almost every server (/login, /play, party chat). Add vanilla-equivalent chat input with Alkaline's design: T opens chat, / opens it with "/" typed, Enter sends, Escape closes. Keep up/down history, Tab completion through the Tab-Complete packet, and clickable or hoverable chat components where the received chat already carries them.
</goal>

<context>
- Received chat is already decoded and shown in the HUD (grep for Chat in apps/game-client and modules/ui).
- Serverbound chat and tab-complete senders exist in modules/game/.../net/ServerSession.java.
- In-game screens are Compose (modules/ui), hosted in the game by ClientApp; see how the inventory screen is opened and closed, and how it takes keyboard input.
- Vanilla rules to keep (GuiChat and GuiNewChat in the decompiled jar):
  - messages are cut at 100 characters;
  - § is not allowed;
  - the history holds the last 100 sent lines;
  - the open chat shows the full history with scrolling;
  - the chat closes after sending.
</context>

<design>
Follow AGENTS.md's simplicity rule and Material 3 Expressive: one input field at the bottom over the existing chat area, with no buttons. The open state reveals the scrollback, and nothing else changes.
</design>

<done_when>
- Unit tests cover the input rules (length, history, § filtering).
- At runtime, a script sends "hello" and "/time set 6000"; the server log shows both, and a screenshot shows the open chat with history.
</done_when>
```

### 1C Chest-like container screens (already built: skip)

Checked on 2026-10-23: `Window.open` and `ContainerScreen` already cover every chest-like window. A large chest
opened with its lid animating, six rows and its title, and a shift-clicked stack reached the player's inventory on
the server (`clear Player minecraft:diamond -1 0` found 5). The prompt below is kept for reference only.

```text
Read docs/agent-brief.md first and follow it. Task id 1C, branch wave1/1C, server port 25633.

<goal>
When a server opens a chest, the client currently closes it at once (servers use chests for menus and game selectors, so this blocks normal play). Draw container screens for the generic chest (1 to 6 rows, including the large chest), ender chest, dispenser, dropper, hopper, crafting table and furnace. Clicks must go through the existing Container.slotClick logic, so the server agrees with the client.
</goal>

<context>
- modules/game/.../inventory has Container, Inventory and Window. They already implement vanilla's slotClick and the per-slot rules, and WindowInput handles mouse input for any Container.
- The player inventory screen in modules/ui shows how a Container is laid out and drawn: slot layout, cursor stack, drag preview and tooltips.
- Find the "close at once" handling for Open Window in apps/game-client/.../PlaySession.java and replace it with opening the screen.
- Vanilla layouts come from GuiChest, GuiDispenser, GuiHopper, GuiCrafting and GuiFurnace. The furnace progress arrow and flame come from Window Property packets.
</context>

<design>Reuse the inventory screen's frosted panel and slot style; the window title comes from the Open Window packet with its § codes stripped. Do not add buttons.</design>

<done_when>
- Unit tests cover each layout's slot positions and count.
- At runtime: setblock a chest with items, open it with rclick, shift-click an item out, and check the server's inventory with /clear Player <item> 0.
- Also open a furnace and screenshot it while it smelts (see the brief's furnace gotcha: CookTimeTotal:200s).
</done_when>
```

### 1D Sound (Opus 5.5 High)

```text
Read docs/agent-brief.md first and follow it. Task id 1D, branch wave1/1D, server port 25634.

<goal>
Alkaline has no audio. PvP depends on sound: hits, footsteps, bow draws, potions and players nearby. Build a sound system that plays vanilla 1.8.9 sounds from the active assets (including resource packs, through the existing layered AssetSource). It must give low latency and never block the render thread.
</goal>

<constraints>
- Choose the backend deliberately and record why in the handoff note. LWJGL's OpenAL Soft plus stb_vorbis is the expected choice, but check that the pinned LWJGL version ships both for Linux and Windows. Pin versions in the existing Gradle version catalog.
- Decode .ogg off the render thread and cache decoded buffers with a memory cap. Each play request takes a sound event name, position, volume and pitch. Use a bounded source pool with vanilla-like priority, where the oldest quiet sound is dropped.
- Implement 1.8.9 sounds.json semantics: categories, random variants, the per-event "stream" flag, volume and pitch, and the 16-block attenuation of SoundManager and SoundEventAccessor.
- Sources:
  - the Sound Effect packet;
  - entity sounds the client plays itself (footsteps by block step sound, hit or hurt from Entity Status, the bow, eating);
  - UI clicks in menus.
  Read the vanilla call sites (decompiled jar) for which sounds the client plays locally and which the server sends, so nothing plays twice.
- Add volume settings per category (master, players, hostile, blocks, records/music off by default) under the existing generic settings grouping (AGENTS.md: Audio group). Listener position and orientation update from the camera every frame without allocating.
- Release every OpenAL handle on shutdown and when a resource pack reloads.
</constraints>

<done_when>
- Unit tests cover sounds.json parsing and attenuation math.
- At runtime, walk, hit a zombie and draw a bow on the server, with --stats on. Confirm sounds play: log the play requests, and if possible record the default monitor with `pw-record` or `parec` and check its levels.
- Record the frame-time p99 with and without audio.
</done_when>
```

### 1E Enchantment glint (Opus 5.5 Medium)

```text
Read docs/agent-brief.md first and follow it. Task id 1E, branch wave1/1E, server port 25635.

<goal>
Enchanted items and armor show no glint. PvP players read enchantments at a glance, and packs restyle the glint (CIT type=enchantment). Add vanilla's glint for held items, dropped items, HUD icons and armor, plus OptiFine CIT enchantment textures.
</goal>

<context>
- Vanilla: RenderItem.renderEffect draws the item twice more with misc/enchanted_item_glint.png, using GL_EQUAL depth and blend SRC_COLOR, ONE. The texture is scaled by 8 and scrolled over time at two angles (-50 and 10 degrees), with color 0x8040CC. LayerArmorBase uses its own color and timing for armor. Read the decompiled classes for the exact matrices and timing.
- In Alkaline, items draw through apps/game-client/.../EntityView.java and modules/renderer/.../vulkan/model/ModelRenderer.java. HUD icons are rasterized once by modules/renderer/.../model/ItemIcons.java (CPU); vanilla's GUI glint animates, so decide how to keep that cheap (for example an animated overlay pass in the HUD sprite path) and explain the choice.
- ItemStack (modules/game) knows whether a stack is enchanted. CustomItems already parses type=enchantment rules (texture, blend, speed, rotation, layer, duration); apply them per OptiFine (net/optifine/CustomItems*.java and CustomItemProperties in KotlinizedMCP).
- New pipelines need PipelineSpec blend modes; ADD and the OptiFine blends already exist (Blend in PipelineSpec).
</context>

<done_when>
- At runtime: hold an enchanted sword, wear enchanted armor in third person (F5), and place an enchanted book in the hotbar. Take screenshots a second apart; the glint must scroll.
- Compare one screenshot with vanilla's look (color, scale and direction) and describe any visible difference honestly.
- No per-frame allocation in the added draw path.
</done_when>
```

### 1Z Wave 1 integrator (Opus 5.5 Medium, on main)

```text
Read docs/agent-brief.md first. You integrate wave 1. You are the only session allowed to edit README.md and docs/decisions/, and to push main.

<goal>Merge the wave 1 branches into main without losing either side's behavior, prove the result works, and record it.</goal>

<steps>
1. Read docs/handoff/1A.md through 1E.md on their branches (git show wave1/1X:docs/handoff/1X.md). A task whose note says it is not done is still merged if what it did builds and is safe; list it as carried over.
2. Merge in this order with git merge --no-ff: wave1/1A, wave1/1E, wave1/1D, wave1/1C, wave1/1B. After each merge run ./gradlew build and fix breakage before the next merge.
3. Conflicts:
   - read both sides and the two handoff notes;
   - keep both behaviors;
   - hot-file conflicts (ClientApp, PlaySession, GameView, EntityView) are usually two hooks at the same spot: keep both, in merge order;
   - never resolve by dropping a side wholesale (`-X ours` or `-X theirs`);
   - if two tasks truly disagree on behavior, pick the one matching vanilla 1.8.9 and note it.
4. Run the full test suite. Then run one combined runtime check on port 25639:
   - open a chest via rclick;
   - type in chat;
   - hit a summoned witch while holding an enchanted sword, with sound on.
5. Write docs/decisions/0013-<short-title>.md in the style of 0012: what was built, the vanilla rules kept on purpose, how it was verified, and what is not done. Update the README's "not done" statements. Delete docs/handoff/.
6. Commit "Wave 1 integration" with your model's Co-Authored-By trailer, then git push origin main (a normal push, never force). Delete the merged wave1/* branches locally.
</steps>

<done_when>main builds, the tests pass, the combined runtime check passed (state exactly what you saw), and the decision record lists carry-overs.</done_when>
```

---

## Wave 2: world feedback, packs and remaining screens

| Task | Model | Owns | Port |
|---|---|---|---|
| 2A Objects | Sonnet High | new object model files, the object branch of `EntityView` | 25641 |
| 2B Block breaking | Sonnet High | new break-overlay files in renderer and world | 25642 |
| 2C CTM completion | Opus High | `ConnectedTextures`, `ChunkMesher`, `BlockPalette` | 25643 |
| 2D Server resource packs | **Done** on `wave2/2D` (planning session) | `ResourcePacks`, its new download code, its prompt screen | 25644 |
| 2E Remaining screens | **Already built**: skip | new screen files in `modules/ui` | 25645 |
| 2F Small parity fixes | Haiku Extra | `LocalPlayer` and input timing only | 25646 |

The integrator merges in this order: 2F, 2C, 2B, 2A, 2E, 2D.

### 2A Objects (Sonnet 5.5 High)

```text
Read docs/agent-brief.md first and follow it. Task id 2A, branch wave2/2A, server port 25641. You own the object (non-living) branch of apps/game-client/.../EntityView.java plus new files.

<goal>Tracked objects other than dropped items and projectiles draw as nothing. Draw them like vanilla 1.8.9: minecarts (all kinds, with the block inside), boats, paintings (by art title and facing), item frames with their item and rotation, experience orbs (size by value, color pulse), primed TNT (flash and swell), falling blocks, armor stands (pose metadata), fireworks rockets, and lightning bolts.</goal>

<context>The entity tracker (modules/game/.../entity/EntityTracker.java) already tracks paintings, orbs and lightning; read how objects are spawned (Spawn Object data) and how EntityView draws dropped items and projectiles (ProjectileModels, ItemMeshes). Read the Render* and Model* classes for each in the decompiled jar. Falling blocks and minecart contents use block models; reuse the item block-mesh path instead of meshing per frame.</context>

<done_when>At runtime, summon each object kind (/summon MinecartRideable, Boat, Painting, ItemFrame with Item, XPOrb with Value:50, PrimedTnt, FallingSand, ArmorStand with Pose, FireworksRocketEntity; and /summon LightningBolt). Screenshot each; each is drawn at the right place, size and texture. Unit tests pin painting art sizes and the orb size buckets.</done_when>
```

### 2B Block breaking feedback (Sonnet 5.5 High)

```text
Read docs/agent-brief.md first and follow it. Task id 2B, branch wave2/2B, server port 25642.

<goal>Breaking a block shows no crack stages, and hitting blocks makes no particles. Vanilla shows destroy_stage_0..9 over the block, from your own digging progress and from other players' Block Break Animation packets, plus block-crack particles while digging and when a block breaks. Add both.</goal>

<context>
- The Block Break Animation packet is decoded in modules/game/.../net/ServerSession.java but has no consumer.
- Local digging progress is computed where PlaySession sends digging packets; read how it tracks the hardness and the break time.
- The particle system already supports block-textured particles; check how projectile impact particles are spawned in GameView.
- Vanilla: RenderGlobal.drawBlockDamageTexture draws the block's model again with the destroy texture, using a multiplicative blend (DST_COLOR, SRC_COLOR) and polygon offset. EffectRenderer.addBlockHitEffects and addBlockDestroyEffects spawn the particles.
- Draw the overlay as a small dynamic mesh (at most a few blocks at once), rebuilt only when the stage changes, never per frame.
</context>

<done_when>At runtime, dig stone in survival with a script holding left click, and take screenshots at three progress points; the stages must be visible and the particles present. A second client is not available, so test the packet path with a unit test feeding Block Break Animation.</done_when>
```

### 2C CTM completion (Opus 5.5 High)

```text
Read docs/agent-brief.md first and follow it. Task id 2C, branch wave2/2C, server port 25643. You own modules/renderer/.../world/ConnectedTextures.java, ChunkMesher.java and BlockPalette.java.

<goal>Connected textures work for full cubes only. PvP packs (the user's Xe Lite included) connect glass panes, and use overlay and compact CTM. Complete CTM to OptiFine 1.8.9's behavior: non-cube models (panes and iron bars first; then any model face that is a full-face quad), method=overlay (with its layer and tint rules), ctm_compact, and the biomes, heights, minHeight and maxHeight conditions.</goal>

<context>
- Read ConnectedTextures, the CTM parts of ChunkMesher (connectedLayer, Neighbors, merge key bits 16..31, emitCube) and BlockPalette (registerConnectedTiles, ctmRule). Read OptiFine's net/optifine/ConnectedTextures.java and ConnectedProperties.java in KotlinizedMCP for the exact rules.
- There are two block texture arrays (up to 2048 layers each) with 16-bit texture ids; check the layer budget when adding tiles, and fail softly (skip the rule and log) rather than crash when it runs out.
- Panes connect by pane-specific rules (OptiFine's pane edge handling): check the reference.
</context>

<done_when>
- Unit tests cover compact tile selection, overlay tile selection and the pane connection rules.
- At runtime with Xe Lite: a wall of glass panes, a wall of stained glass panes, and a block type using overlay if Xe Lite has one (grep its ctm properties for method=overlay). Screenshot each, then without the pack.
- The mesh build time for a pane-heavy section does not regress by more than 20%; measure with the existing mesher benchmark or a JMH-style loop and report the numbers.
</done_when>
```

### 2D Server resource packs (done on `wave2/2D` by the planning session)

```text
Read docs/agent-brief.md first and follow it. Task id 2D, branch wave2/2D, server port 25644.

<goal>Servers send resource packs (Resource Pack Send), and Alkaline declines them all, so servers that require one kick the player. Support them as vanilla 1.8.9 does, with a safe download.</goal>

<rules>
- Prompt the player once per server with Accept and Decline. The answer is remembered in the existing server list entry; check how server entries are stored and add the field explicitly.
- Send the status packets in vanilla's order: accepted, then successfully loaded or failed download, or declined.
- Download to ~/.alkaline/legacy/server-resource-packs/<sha1>.zip:
  - over HTTPS or HTTP as vanilla allows;
  - with a 50 MB size cap, as vanilla's;
  - with a timeout, and no redirect to file: URLs;
  - verify the SHA-1 when the server gave one;
  - reuse a cached file with a matching hash.
  - "level://" URLs refer to the world folder; decline them.
- Apply the pack above the user's selected packs through the existing reload path (ClientApp.reloadResourcePacks), and remove it again when leaving the server.
- Downloads run off the render thread; the game keeps rendering.
</rules>

<done_when>Unit tests cover hash verification, the size cap and URL validation. At runtime, serve a small test pack from a local `python3 -m http.server` in a scratch dir, set resource-pack=<url> and resource-pack-sha1 in server.properties, join, accept, and screenshot a changed texture. Then decline on a second join and confirm the server log shows the declined status.</done_when>
```

### 2E Remaining container screens (already built: skip)

`ContainerScreen` already has the brewing stand, enchanting table, anvil, beacon, villager and horse screens and
their packets. Run this prompt only if a runtime check of one of them fails.

```text
Read docs/agent-brief.md first and follow it. Task id 2E, branch wave2/2E, server port 25645.

<goal>Wave 1 added chest-like screens. Add the rest that servers open: brewing stand, enchanting table (the three offers with their level costs and lapis; send Enchant Item), anvil (the rename field; send MC|ItemName), beacon (effect choice; send MC|Beacon), villager trading (send MC|TrSel; read the trade list from MC|TrList), and horse inventory.</goal>

<context>Read how the chest screen from wave 1 is built in modules/ui and opened from PlaySession (it replaced the "close at once" handling). The serverbound senders already exist in ServerSession. Layouts and rules come from GuiBrewingStand, GuiEnchantment, GuiRepair, GuiBeacon, GuiMerchant and GuiScreenHorseInventory, and from their Container classes, in the decompiled jar. Keep the frosted panel style; one primary action at most per screen (AGENTS.md).</context>

<done_when>Unit tests cover the slot layouts and the payload encoding. At runtime, open each block (setblock, then rclick) and a villager (/summon Villager with Offers), do one action in each, and confirm the server agrees (inventory or experience changes, checked with server commands). Screenshot each screen.</done_when>
```

### 2F Small vanilla parity fixes (Haiku 5.5 Extra)

`rightClickDelayTimer` already exists in `PlaySession`; item 2 may only need its test.

```text
Read docs/agent-brief.md first and follow it. Task id 2F, branch wave2/2F, server port 25646.

<goal>Earlier docs list small gameplay timing rules that were not ported. PvP players notice timing, so each must match vanilla 1.8.9 exactly. For each item, first check whether it is already implemented (grep, then read). Fix only those that are missing or wrong.</goal>

<items>
1. Minecraft.clickMouse: after a left click that hits nothing, leftClickCounter = 10 in survival, blocking attacks and swings until it counts down. Check Minecraft.runTick for where it decrements and for creative mode.
2. The right-click delay timer (rightClickDelayTimer = 4) for repeated use and place while held.
3. The swing animation restart rule: EntityLivingBase.swingItem only restarts when the swing is past half, or not swinging.
</items>
Read each rule from the decompiled jar (Minecraft, EntityLivingBase, PlayerControllerMP), not KotlinizedMCP.

<done_when>A unit test per rule in the style of VanillaPhysicsTest (grep for it). For a rule that was already correct, the handoff note says so and cites the vanilla line.</done_when>
```

### 2Z Wave 2 integrator (Opus 5.5 Medium, on main)

```text
Open docs/implementation-waves.md and follow the "1Z Wave 1 integrator" prompt exactly, with these changes:
- the branches are wave2/2F, 2C, 2B, 2A, 2E and 2D, merged in that order;
- the handoff notes are docs/handoff/2A.md through 2F.md;
- the decision record is 0014;
- the combined runtime check, on port 25649, is: with Xe Lite active, dig a block next to a glass-pane wall, ride a minecart, open a brewing stand, and join with a server pack offered.
Read docs/agent-brief.md first.
```

---

## Wave 3: identity, OptiFine extras and performance

| Task | Model | Owns | Port |
|---|---|---|---|
| 3A Name tags and scoreboard | Sonnet High | new name-tag and scoreboard files; the label hook in `EntityView` | 25651 |
| 3B OptiFine extras | Opus Medium | new random-entity, animation and emissive files; texture lookups | 25652 |
| 3C Sky and color leftovers | Sonnet Medium | `CustomSky`, `SkyRenderer`, `CustomColors` | 25653 |
| 3D Maps and world border | Sonnet Medium | new map and border files | 25654 |
| 3E Performance pass | Opus High | measured hot spots only; runs **after** the 3Z merge, alone | 25655 |

The integrator merges in this order: 3C, 3D, 3B, 3A. 3E runs on the merged main and is then merged on its own.

### 3A Name tags and scoreboard (Sonnet 5.5 High)

The sidebar scoreboard already exists (`PlaySession` and `HudBridge` pass `sidebarTitle` and `sidebar`); check it,
then focus on name tags, team formatting and the below-name score. Name tags are drawn in the world, not in Compose,
so this stays on Sonnet; if the sidebar's Compose element needs changes, hand that part to Opus.

```text
Read docs/agent-brief.md first and follow it. Task id 3A, branch wave3/3A, server port 25651.

<goal>PvP servers identify players by name tags, team colors and the sidebar scoreboard. Add them as vanilla 1.8.9 shows them.</goal>

<items>
- **Name tags** (RendererLivingEntity.renderLivingLabel and RenderPlayer.renderOffsetLivingLabel):
  - the 64-block range;
  - sneaking players' tags are dimmed and drawn behind walls only when not sneaking;
  - see-through depth;
  - the background quad;
  - team prefix and suffix and name tag visibility rules from the Teams packet;
  - the below-name objective score line.
- **Sidebar scoreboard** (GuiIngame.renderScoreboard): display slot 1 and team-colored sidebars; sorting; the 15-line limit; red score numbers. Render it as a HUD module so its position and scale are customizable like the others.
- **Tab list:** apply team prefixes, sorting and the list objective, if the player list exists without them.
</items>
First check what the Scoreboard Objective, Update Score, Display Scoreboard and Teams packets already store in modules/game. The text renderer used by the HUD must render § colors; reuse it.

<done_when>Unit tests cover team formatting, sidebar sorting and the line limit. At runtime, use /scoreboard commands to make an objective in the sidebar, a team with a prefix, and a below-name score. Join a second time with a different name if the client supports it; otherwise use a named armor stand (CustomName, CustomNameVisible) for the tag check. Take screenshots.</done_when>
```

### 3B OptiFine extras (Opus 5.5 Medium)

```text
Read docs/agent-brief.md first and follow it. Task id 3B, branch wave3/3B, server port 25652.

<goal>Finish the OptiFine features PvP packs commonly use, ported from OptiFine 1.8.9's rules (net/optifine in KotlinizedMCP: RandomEntities/RandomMobs, TextureAnimations, EmissiveTextures). Only read those sources; write Alkaline's own implementation.</goal>

<items>
1. **Random entities:** optifine/mob and mcpatcher/mob textures (name2.png ...) and the .properties rules (skins, weights, biomes, heights). Choose per entity id, deterministically, as OptiFine does.
2. **Custom texture animations:** optifine/anim and mcpatcher/anim properties (from, to, x, y, w, h, duration, interpolate). Animate block and item textures by updating texture array layers in beforeFrame only, and only when a frame changes.
3. **Emissive textures:** the suffix from emissive.properties (default _e). Draw emissive layers fullbright in blocks and entities, then add an extra pass where needed.
</items>
Measure the cost: animated layer uploads per second and frame-time p99 before and after, with Xe Lite.

<done_when>Unit tests cover the property parsing and the random choice. At runtime, use whatever Xe Lite or the vanilla assets provide; if no installed pack uses a feature, build a tiny test pack in the scratch dir. Take screenshots.</done_when>
```

### 3C Sky and color leftovers (Sonnet 5.5 Medium)

```text
Read docs/agent-brief.md first and follow it. Task id 3C, branch wave3/3C, server port 25653.

<goal>Close the documented gaps in custom sky and custom colors (see decision 0012):
- sky layers' biomes and heights conditions, and the transition smoothing between active layers;
- custom colors' smoothing radius for biome colormaps;
- the xporb, redstone, text (chat/HUD formatting colors), sheep and map colors.
Match OptiFine 1.8.9 (net/optifine/CustomSky*.java and CustomColors.java in KotlinizedMCP).</goal>

<context>Read CustomSky, SkyRenderer, CustomColors and Sky in modules/renderer. Smoothing must not cost per frame: compute it when a chunk meshes, or cache it per camera block.</context>

<done_when>Unit tests for each rule. At runtime with Xe Lite (it has color.properties and sky layers), take screenshots at the noon-to-sunset transition and of the colored items. Report which keys Xe Lite actually uses.</done_when>
```

### 3D Maps and world border (Sonnet 5.5 Medium)

```text
Read docs/agent-brief.md first and follow it. Task id 3D, branch wave3/3D, server port 25654.

<goal>Two decoded packets have no consumer:
- Maps: filled maps should show their 128×128 colors (Map packet data, MapColor table) in hand (ItemRenderer.renderItemMap, both one-handed and two-handed poses) and in item frames.
- World border: the border should draw as vanilla's animated forcefield wall (RenderGlobal.renderWorldBorder), with the warning tint (GuiIngame vignette), and the border must block client-side movement as vanilla's collision does.</goal>

<context>
- Map and World Border packets are decoded in ServerSession.
- Item frames come from wave 2; check how EntityView draws them and add the map case.
- The map texture uploads when its data changes (beforeFrame), not per frame.
</context>

<done_when>Unit tests cover the map color decoding and border interpolation. At runtime: /give a map, hold it, and put one in an item frame; set /worldborder set 20 then 10 over 10 seconds and screenshot the wall and the warning tint.</done_when>
```

### 3Z Wave 3 integrator (Opus 5.5 Medium, on main)

```text
Open docs/implementation-waves.md and follow the "1Z Wave 1 integrator" prompt exactly, with these changes:
- the branches are wave3/3C, 3D, 3B and 3A, merged in that order;
- the handoff notes are docs/handoff/3A.md through 3D.md;
- the decision record is 0015;
- the combined runtime check, on port 25659, is: a sidebar scoreboard visible, a map in hand, and a named armor stand in view, all at sunset with Xe Lite.
Read docs/agent-brief.md first.
```

### 3E Performance pass (Opus 5.5 High, after 3Z, alone)

```text
Read docs/agent-brief.md first and follow it. Task id 3E, branch wave3/3E, server port 25655. You may touch any file, but only to fix a measured hot spot.

<goal>Three waves added features. AGENTS.md puts performance first: frame-time consistency, input latency and allocation rate. Measure the client on a fixed scene, find the worst costs, and fix them with proof.</goal>

<steps>
1. Define one repeatable scene: Xe Lite, render distance 8, 30 summoned mixed mobs and objects, a chest open then closed, sound on, and the sidebar scoreboard. Use a fixed input script of 60 seconds that walks and turns.
2. Record the baseline: frame-time p50, p95, p99 and max (--stats); allocation rate and GC pauses (JFR: JAVA_OPTS=-XX:StartFlightRecording=...); and the top render-thread methods. Record the GPU, driver, OS and resolution.
3. Fix the top offenders in order of measured cost: per-frame allocations, render-thread blocking, redundant uploads, then draw-call counts. Re-measure after each fix and keep only fixes that help.
4. Add a "Performance pass" section with the before-and-after table to docs/decisions/0015-*.md. You run alone, so you may edit it. Do not claim a win that was not measured on the same scene.
5. Merge wave3/3E into main with --no-ff, run the full test suite again, and git push origin main (a normal push, never force).
</steps>

<done_when>Tests pass, the scene still renders the same (compare screenshots), and the table shows the measured result. If nothing beats noise, say so.</done_when>
```
