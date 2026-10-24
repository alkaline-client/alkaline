# Implementation waves

Run one session per task, in `~/Projects/Alkaline/legacy`, in its own worktree. Set the model and effort shown, then
paste the block. Sessions share no memory, so each block points to `docs/agent-brief.md`. Tasks in the same wave
touch different files and can run together. When a wave is done, run its integrator.

**Model choice:**
- **Opus:** Compose UI, new subsystems, hard mesher or shader work, and the integrators.
- **Sonnet:** most features.
- **Haiku:** tests, docs and small fixes.

If a session reports something as not done without saying what it tried, ask it once more in the same session.

## Done
- **Wave 0:** pack work, tests, docs, and the git reset.
- **Wave 1:** mobs, chat, sound and glint, merged into `main`.
- **2A:** objects (`wave2/2A`).
- **2D:** server resource packs (`wave2/2D`).
- **1C and 2E:** container screens. These were already built.
- **2B:** block breaking is in progress on `wave2/2B`.

## Remaining tasks

The tasks below are merged into three large ones to save credits. Run A and B at the same time, then C.

### A. OptiFine completion (Opus High, port 25643)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/optifine):

Connected textures (OptiFine 1.8.9 ConnectedTextures and ConnectedProperties)
- Glass panes and iron bars, then other full-face model quads
- method=overlay, ctm_compact, and the biomes and heights conditions

Other OptiFine features (net/optifine)
- Random entities (mob textures and properties)
- Texture animations (anim properties); upload changed layers in beforeFrame only
- Emissive textures
- Custom sky biomes and heights conditions, and transitions
- Custom colors: smoothing radius, xporb, redstone, text, sheep and map colors

Check
- Tests for the parsing and tile rules
- Screenshots with Xe Lite of a pane wall and a sunset; use a tiny test pack for features Xe Lite lacks
- Summoned TNT: use a long fuse and kill it right after the screenshot
```

### B. World and gameplay (Sonnet High, port 25651)
```
Tasks for legacy (read docs/agent-brief.md first; branch wave2/world):

Name tags (RendererLivingEntity.renderLivingLabel)
- Range, sneaking dimming, see-through, background
- Team prefix, suffix and visibility from the Teams packet; the below-name score; team prefixes and sorting in the tab list if missing
- The sidebar already exists; leave it unless it is wrong

Maps and world border
- Filled maps in hand (ItemRenderer.renderItemMap) and in item frames
- World border wall, warning tint and collision (RenderGlobal.renderWorldBorder)

Vanilla timing (check against the decompiled jar; fix only what is wrong)
- leftClickCounter = 10 after a left click that hits nothing (Minecraft.clickMouse, runTick)
- The swing restart rule (EntityLivingBase.swingItem)

Check
- Tests for the timing rules, team formatting and map colors
- Screenshots: a named armor stand, a team prefix, a map in hand, /worldborder set 20
```

### C. Merge and measure (Opus High, on main, after A, B and 2B)
```
Tasks for legacy (read docs/agent-brief.md first; you may edit README.md and docs/decisions and push main):

Merge
- Merge with --no-ff in this order: wave2/2A, wave2/2B, wave2/2D, wave2/optifine, wave2/world. Build after each merge
- On conflicts keep both sides; read the branches' docs/handoff notes
- Run all tests, then one in-game check with Xe Lite: objects, digging, a pane wall, a server pack, name tags
- Write docs/decisions/0014 from the handoff notes and delete docs/handoff

Performance
- One fixed scene: Xe Lite, render distance 8, 30 mobs and objects, sound on, a 60-second walking script
- Measure frame-time p50, p99 and max, plus allocation rate (JFR); fix the biggest costs and re-measure after each fix
- Add a before-and-after table to 0014, commit, push main
```

## Not scheduled
- **Vulkan 1.1 compatibility mode:** AGENTS.md says not to build it until it is scheduled.
- **Singleplayer:** it needs an integrated server.
- **Windows and target-GPU qualification:** they need that hardware.
